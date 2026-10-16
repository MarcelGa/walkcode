package dev.walkcode.app.poll

/**
 * Turns poller events for one host into unread state and alerts. Pure logic, no I/O.
 *
 * All times are host epoch seconds. The tracker keeps a host clock from the latest event plus
 * the elapsed local monotonic time, so phone/host clock skew does not matter.
 *
 * Rules (Q26, Q47, Q56, Q61):
 * - unread: the pane's screen changed after it was last viewed (plus a short grace that covers
 *   the redraw caused by the phone attaching/detaching).
 * - NeedsAttention: the bell count went up since the pane was last viewed. Bells keep counting
 *   on the host, so one alert per increase (psmux counts 2 per bell; only "went up" matters).
 * - Quiet: the pane has unread output and has not changed for [quietAfterSec]; once per quiet
 *   period, and only where [quietEnabled] says so (agents that ring the bell can turn it off).
 * - Nothing alerts for the [focused] pane (the one on screen); it is continuously "viewed".
 */
class ActivityTracker(
    private val quietAfterSec: Long = 15,
    private val viewGraceSec: Long = 3,
    private val quietEnabled: (target: String) -> Boolean = { true },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    sealed interface Alert {
        val target: String
        data class NeedsAttention(override val target: String, val session: String) : Alert
        data class Quiet(override val target: String, val session: String, val idleSince: Long) : Alert
    }

    /** What survives a reconnect or app restart (persist per host). */
    data class Saved(val hash: String, val lastChange: Long, val bellSeen: Int, val viewedAt: Long)

    data class PaneView(
        val target: String,
        val session: String,
        val active: Boolean,
        val unread: Boolean,
        val lastChange: Long,
        val dead: Boolean,
        val command: String?,
        val title: String,
    )

    private class Mem(
        var session: String,
        var active: Boolean,
        var hash: String,
        var lastChange: Long,
        var bell: Int,
        var bellSeen: Int,
        var viewedAt: Long,
        var quietAlertedFor: Long,
        var dead: Boolean,
        var command: String?,
        var title: String,
    )

    private val panes = LinkedHashMap<String, Mem>()
    private val saved = HashMap<String, Saved>()
    private var clockHost = 0L
    private var clockNanos = 0L

    var focused: String? = null
        set(value) {
            val previous = field
            field = value
            if (previous != null && previous != value) markViewed(previous)
            if (value != null) markViewed(value)
        }

    fun hostNow(): Long = if (clockHost == 0L) 0L else clockHost + (nanoTime() - clockNanos) / 1_000_000_000L

    fun restore(state: Map<String, Saved>) {
        saved.clear(); saved.putAll(state)
    }

    fun persist(): Map<String, Saved> =
        saved + panes.mapValues { (_, m) -> Saved(m.hash, m.lastChange, m.bellSeen, m.viewedAt) }

    fun panes(): List<PaneView> = panes.map { (t, m) ->
        PaneView(t, m.session, m.active, isUnread(m), m.lastChange, m.dead, m.command, m.title)
    }

    fun isUnread(target: String): Boolean = panes[target]?.let(::isUnread) ?: false

    fun markViewed(target: String) {
        val m = panes[target] ?: return
        m.viewedAt = maxOf(hostNow(), m.lastChange)
        m.bellSeen = m.bell
        m.quietAlertedFor = m.lastChange
    }

    fun onEvent(e: PollEvent): List<Alert> {
        clockHost = e.time
        clockNanos = nanoTime()
        return when (e) {
            is PollEvent.Pane -> onPane(e)
            is PollEvent.Gone -> { panes.remove(e.target)?.let { saved.remove(e.target) }; emptyList() }
            is PollEvent.Ready, is PollEvent.Heartbeat -> tick()
        }
    }

    /** Re-evaluates time based alerts (quiet). Call every few seconds. */
    fun tick(): List<Alert> {
        val now = hostNow()
        return panes.mapNotNull { (t, m) -> quietCheck(t, m, now) }
    }

    private fun quietCheck(t: String, m: Mem, now: Long): Alert? {
        if (t == focused) return null
        if (m.active || m.lastChange == 0L || !isUnread(m)) return null
        if (m.quietAlertedFor == m.lastChange || !quietEnabled(t)) return null
        if (now - m.lastChange < quietAfterSec) return null
        m.quietAlertedFor = m.lastChange
        return Alert.Quiet(t, m.session, m.lastChange)
    }

    private fun onPane(e: PollEvent.Pane): List<Alert> {
        var m = panes[e.target]
        if (m == null) {
            val s = saved.remove(e.target)
            m = if (s != null) {
                // Seen before this connection: unchanged screen keeps its history, otherwise
                // it changed while we were away.
                val lastChange = if (s.hash == e.hash) s.lastChange else maxOf(e.since, e.time)
                Mem(e.session, e.active, e.hash, lastChange, e.bell,
                    bellSeen = if (e.bell < s.bellSeen) e.bell else s.bellSeen,
                    viewedAt = s.viewedAt, quietAlertedFor = s.lastChange,
                    dead = e.dead, command = e.command, title = e.title)
            } else {
                // Never seen: its current content and bells count as already read.
                Mem(e.session, e.active, e.hash, e.since, e.bell, bellSeen = e.bell,
                    viewedAt = e.time, quietAlertedFor = e.since,
                    dead = e.dead, command = e.command, title = e.title)
            }
            panes[e.target] = m
        } else {
            if (e.hash != m.hash || e.active) m.lastChange = maxOf(m.lastChange, if (e.since > 0) e.since else e.time)
            m.session = e.session; m.active = e.active; m.hash = e.hash
            m.dead = e.dead; m.command = e.command; m.title = e.title
            if (e.bell < m.bellSeen) m.bellSeen = e.bell   // window recreated, counter restarted
            m.bell = e.bell
        }

        if (e.target == focused) {
            markViewed(e.target)
            return emptyList()
        }
        val out = ArrayList<Alert>()
        if (m.bell > m.bellSeen) {
            m.bellSeen = m.bell
            out += Alert.NeedsAttention(e.target, m.session)
        }
        out += listOfNotNull(quietCheck(e.target, m, hostNow()))
        return out
    }

    private fun isUnread(m: Mem): Boolean = m.lastChange > m.viewedAt + viewGraceSec
}
