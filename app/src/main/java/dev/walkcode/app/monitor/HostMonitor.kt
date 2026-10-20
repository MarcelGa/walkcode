package dev.walkcode.app.monitor

import dev.walkcode.app.poll.ActivityTracker
import dev.walkcode.app.poll.PollEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable

/** An open poller channel on one host. [run] blocks until the channel ends or [close] is called. */
interface PollLink : Closeable {
    val address: String
    fun run(onEvent: (PollEvent) -> Unit)
}

sealed interface LinkStatus {
    data object Connecting : LinkStatus
    data class Connected(val address: String) : LinkStatus
    /** The link dropped (or never came up); [attempt] counts consecutive failures. */
    data class Reconnecting(val attempt: Int, val reason: String) : LinkStatus
}

/** What the UI shows for one session of a watched host. */
data class SessionActivity(val active: Boolean, val unread: Boolean, val idleSec: Long)

data class HostAlert(
    val hostId: Long,
    val hostName: String,
    val session: String,
    val kind: Kind,
    val idleSec: Long = 0,
) {
    enum class Kind { ATTENTION, QUIET }
}

/**
 * Keeps one host's poller connected: opens a [PollLink], feeds its events to an
 * [ActivityTracker], and reconnects with backoff when the link drops. Alerts are only
 * raised for sessions the user watches (Session.watched); everything else is ignored.
 */
class HostMonitor(
    private val hostId: Long,
    private val hostName: String,
    private val open: suspend () -> PollLink,
    private val watched: () -> Set<String>,
    private val onAlert: (HostAlert) -> Unit,
    quietAfterSec: Long = 15,
    quietEnabled: () -> Boolean = { true },
    private val backoffMs: List<Long> = listOf(2_000, 5_000, 10_000, 30_000),
    private val tickMs: Long = 5_000,
    private val staleMs: Long = 75_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Called with the raw pane title whenever a session's title changes; must not block. */
    private val onTitle: (session: String, rawTitle: String) -> Unit = { _, _ -> },
) {
    private val tracker = ActivityTracker(quietAfterSec = quietAfterSec, quietEnabled = { quietEnabled() })
    /** Last title reported per session; guarded by [tracker]'s lock like the tracker itself. */
    private val reportedTitles = HashMap<String, String>()

    private val _status = MutableStateFlow<LinkStatus>(LinkStatus.Connecting)
    val status: StateFlow<LinkStatus> = _status

    private val _activity = MutableStateFlow<Map<String, SessionActivity>>(emptyMap())
    val activity: StateFlow<Map<String, SessionActivity>> = _activity

    @Volatile private var focusedSession: String? = null

    /** The session currently on screen is "viewed": it never alerts and is never unread. */
    fun setFocused(session: String?) {
        focusedSession = session
        synchronized(tracker) { applyFocus(); publish() }
    }

    fun launchIn(scope: CoroutineScope): Job = scope.launch { loop() }

    private suspend fun loop() {
        var failures = 0
        var reason = ""
        while (currentCoroutineContext().isActive) {
            _status.value = if (failures == 0) LinkStatus.Connecting else LinkStatus.Reconnecting(failures, reason)
            var link: PollLink? = null
            try {
                link = open()
                _status.value = LinkStatus.Connected(link.address)
                failures = 0
                pump(link)
                reason = "connection closed"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reason = e.message ?: e::class.java.simpleName
            } finally {
                runCatching { link?.close() }
            }
            failures++
            _status.value = LinkStatus.Reconnecting(failures, reason)
            delay(backoffMs[minOf(failures - 1, backoffMs.size - 1)])
        }
    }

    /** Runs the blocking poller read and the periodic tick until the link ends. */
    private suspend fun pump(link: PollLink) = coroutineScope {
        // The poller heartbeats every 30 s, so a long silence means the connection is dead
        // even though the socket never reported an error (phone slept, Wi-Fi roamed).
        val silentMs = java.util.concurrent.atomic.AtomicLong()
        val stale = java.util.concurrent.atomic.AtomicBoolean()
        val ticker = launch {
            while (true) {
                delay(tickMs)
                val silent = silentMs.addAndGet(tickMs)
                if (silent >= staleMs) {
                    stale.set(true)
                    runCatching { link.close() }
                    return@launch
                }
                synchronized(tracker) { raise(tracker.tick()); publish() }
            }
        }
        val reader = async(io) {
            link.run { e ->
                silentMs.set(0)
                synchronized(tracker) { raise(tracker.onEvent(e)); applyFocus(); publish() }
            }
        }
        try {
            reader.await()
        } catch (e: Exception) {
            if (stale.get()) throw java.io.IOException("no heartbeat from host") else throw e
        } finally {
            // On cancellation the blocking read only ends once the link is closed.
            runCatching { link.close() }
            ticker.cancel()
        }
        if (stale.get()) throw java.io.IOException("no heartbeat from host")
    }

    private fun applyFocus() {
        val session = focusedSession
        val target = session?.let { s -> tracker.panes().firstOrNull { it.session == s }?.target }
        if (tracker.focused != target) tracker.focused = target
    }

    private fun raise(alerts: List<ActivityTracker.Alert>) {
        val w = watched()
        for (a in alerts) {
            when (a) {
                is ActivityTracker.Alert.NeedsAttention ->
                    if (a.session in w) onAlert(HostAlert(hostId, hostName, a.session, HostAlert.Kind.ATTENTION))
                is ActivityTracker.Alert.Quiet ->
                    if (a.session in w) onAlert(HostAlert(hostId, hostName, a.session, HostAlert.Kind.QUIET, tracker.hostNow() - a.idleSince))
            }
        }
    }

    private fun publish() {
        val now = tracker.hostNow()
        val bySession = tracker.panes().groupBy { it.session }
        for ((session, panes) in bySession) {
            val title = (panes.firstOrNull { it.active } ?: panes.first()).title
            if (reportedTitles[session] != title) {
                reportedTitles[session] = title
                onTitle(session, title)
            }
        }
        _activity.value = bySession.mapValues { (_, panes) ->
            SessionActivity(
                active = panes.any { it.active },
                unread = panes.any { it.unread },
                idleSec = (now - (panes.maxOf { it.lastChange })).coerceAtLeast(0),
            )
        }
    }
}
