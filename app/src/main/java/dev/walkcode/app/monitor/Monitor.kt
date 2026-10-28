package dev.walkcode.app.monitor

import android.content.Context
import dev.walkcode.app.data.AppSettings
import dev.walkcode.app.data.Host
import dev.walkcode.app.data.ShellKind
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.poll.HostPlatform
import dev.walkcode.app.poll.HostPoller
import dev.walkcode.app.poll.PollEvent
import dev.walkcode.app.poll.PollTiming
import dev.walkcode.app.poll.PollerCommand
import dev.walkcode.app.session.SessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient

/**
 * Process-wide watcher. While [start]ed it keeps a [HostMonitor] connected for every host
 * that has at least one watched session, and exposes what the UI and the foreground
 * service need: per-session activity, per-host link status and alerts.
 */
class Monitor private constructor(private val context: Context) {

    private val repo = WalkcodeRepository.get(context)
    private val settings = AppSettings.get(context)
    private val sessions = SessionService(repo)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activity = MutableStateFlow<Map<String, SessionActivity>>(emptyMap())
    val activity: StateFlow<Map<String, SessionActivity>> = _activity

    private val _status = MutableStateFlow<Map<Long, LinkStatus>>(emptyMap())
    val status: StateFlow<Map<Long, LinkStatus>> = _status

    /** Watched session names per host id (empty: nothing to monitor); null until first computed. */
    private val _watchedByHost = MutableStateFlow<Map<Long, Set<String>>?>(null)
    val watchedByHost: StateFlow<Map<Long, Set<String>>?> = _watchedByHost

    private val _alerts = MutableSharedFlow<HostAlert>(extraBufferCapacity = 32)
    val alerts: SharedFlow<HostAlert> = _alerts

    /** Set when the OS ended background monitoring (dataSync time limit); cleared by [resume]. */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    private val monitors = HashMap<Long, Pair<HostMonitor, Job>>()
    private var lastWatched: Map<Long, Set<String>> = emptyMap()
    private var lastHosts: Map<Long, Host> = emptyMap()
    private var reconciler: Job? = null
    private var settingsJob: Job? = null
    @Volatile private var focused: String? = null

    /** Starts monitoring; idempotent. */
    @Synchronized
    fun start() {
        if (reconciler?.isActive == true) return
        _paused.value = false
        settingsJob = scope.launch { settings.quietAfterSec.drop(1).collect { restartHostMonitors() } }
        reconciler = scope.launch {
            combine(repo.hosts, repo.workspaces, repo.allSessions) { hosts, workspaces, sessions ->
                val hostOf = workspaces.associate { it.id to it.hostId }
                val live = hosts.associateBy { it.id }
                sessions.filter { it.watched }
                    .mapNotNull { s -> hostOf[s.workspaceId]?.takeIf { it in live }?.let { it to s.tmuxName } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { it.value.toSet() } to live
            }.collect { (watched, hosts) ->
                _watchedByHost.value = watched
                lastWatched = watched; lastHosts = hosts
                reconcile(watched, hosts)
            }
        }
    }

    /** The tracker's quiet threshold is fixed at construction, so a changed threshold rebuilds the monitors. */
    @Synchronized
    private fun restartHostMonitors() {
        if (reconciler == null) return
        monitors.values.forEach { it.second.cancel() }
        monitors.clear()
        reconcile(lastWatched, lastHosts)
    }

    /** Stops all host connections. [byOs] marks the stop as a time-limit pause for the UI. */
    @Synchronized
    fun stop(byOs: Boolean = false) {
        reconciler?.cancel(); reconciler = null
        settingsJob?.cancel(); settingsJob = null
        monitors.values.forEach { it.second.cancel() }
        monitors.clear()
        activityByHost.clear()
        _watchedByHost.value = null   // a restarted service must not read the previous run's map
        _activity.value = emptyMap()
        _status.value = emptyMap()
        _paused.value = byOs
    }

    fun resume() { _paused.value = false }

    /** Marks the session on screen as viewed; null when no terminal is open. */
    fun setFocused(session: String?) {
        focused = session
        synchronized(this) { monitors.values.forEach { it.first.setFocused(session) } }
    }

    @Synchronized
    private fun reconcile(watched: Map<Long, Set<String>>, hosts: Map<Long, Host>) {
        for (id in monitors.keys.toList() - watched.keys) {
            monitors.remove(id)?.second?.cancel()
            _status.update { it - id }
            publishActivity(id, null)
        }
        for ((id, names) in watched) {
            if (id in monitors) continue
            val host = hosts[id] ?: continue
            val monitor = HostMonitor(
                hostId = id,
                hostName = host.name,
                open = { openLink(id) },
                watched = { _watchedByHost.value?.get(id).orEmpty() },
                onAlert = { a -> scope.launch { emitAlert(id, a) } },
                quietAfterSec = settings.quietAfterSec.value.toLong(),
                quietEnabled = { settings.quietAlerts.value },
                onTitle = { session, title -> scope.launch { repo.recordPaneTitle(session, title) } },
            )
            monitor.setFocused(focused)
            val job = scope.launch {
                launch { monitor.status.collect { s -> _status.update { it + (id to s) } } }
                launch { monitor.activity.collect { a -> publishActivity(id, a) } }
                monitor.launchIn(this).join()
            }
            monitors[id] = monitor to job
        }
    }

    /**
     * Reads the screen before notifying so the alert can say whether the agent asks something or
     * finished. A quiet alert on a screen that is still working is dropped (a long tool run, not
     * a stop). Capped so a slow host delays the alert by seconds at most, never drops it.
     */
    private suspend fun emitAlert(hostId: Long, alert: HostAlert) {
        val monitor = synchronized(this) { monitors[hostId]?.first }
        val screen = monitor?.let { m ->
            val read = scope.async { m.capture(alert.session) }
            withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { read.await() }.also { if (it == null) read.cancel() }
        }
        val reading = screen?.let(ScreenReader::read)
        if (alert.kind == HostAlert.Kind.QUIET && reading?.state == ScreenReading.State.WORKING) return
        _alerts.emit(alert.copy(reading = reading))
    }

    private val activityByHost = HashMap<Long, Map<String, SessionActivity>>()

    @Synchronized
    private fun publishActivity(hostId: Long, activity: Map<String, SessionActivity>?) {
        if (activity == null) activityByHost.remove(hostId) else activityByHost[hostId] = activity
        _activity.value = activityByHost.values.fold(emptyMap()) { acc, m -> acc + m }
    }

    private suspend fun openLink(hostId: Long): PollLink {
        // Re-read the host each time: its addresses or pin may have been edited since.
        val host = requireNotNull(repo.host(hostId)) { "host $hostId was removed" }
        val connected = sessions.connect(host)
        val platform = if (host.shell == ShellKind.PSMUX) HostPlatform.WINDOWS else HostPlatform.POSIX
        val asset = if (platform == HostPlatform.WINDOWS) PollerCommand.WINDOWS_ASSET else PollerCommand.POSIX_ASSET
        val script = context.assets.open(asset).bufferedReader().use { it.readText() }
        val command = PollerCommand.build(platform, script, PollTiming())
        return SshPollLink(connected.client, connected.address, host.shell, HostPoller(connected.client, command), sessions)
    }

    private class SshPollLink(
        private val client: SSHClient,
        override val address: String,
        private val shell: ShellKind,
        private val poller: HostPoller,
        private val sessions: SessionService,
    ) : PollLink {
        override suspend fun capture(session: String): String? = sessions.capture(client, shell, session, history = null)

        override fun run(onEvent: (PollEvent) -> Unit) {
            poller.run(onEvent)
            // run() returns when the channel ends; an ended poller means the link is unusable.
            throw java.io.IOException(poller.errorOutput().trim().ifEmpty { "poller ended" })
        }

        override fun close() {
            runCatching { poller.close() }
            Thread { runCatching { client.disconnect() } }.start()
        }
    }

    companion object {
        private const val CAPTURE_TIMEOUT_MS = 4_000L

        @Volatile private var instance: Monitor? = null

        fun get(context: Context): Monitor =
            instance ?: synchronized(this) { instance ?: Monitor(context.applicationContext).also { instance = it } }
    }
}
