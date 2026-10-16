package dev.walkcode.app.poll

/** One line of the host poller's output (see assets/poller/poll.sh). Times are host epoch seconds. */
sealed interface PollEvent {
    val time: Long

    /** A pane appeared or one of its tracked properties changed. */
    data class Pane(
        override val time: Long,
        /** `session:window.pane`; unique per host for both tmux and psmux. */
        val target: String,
        val session: String,
        val active: Boolean,
        /** Host time of the last observed screen change, 0 if unknown (first sighting). */
        val since: Long,
        val hash: String,
        val bell: Int,
        val dead: Boolean,
        /** Foreground command, or null if the host did not report one. */
        val command: String?,
        val title: String,
    ) : PollEvent

    data class Gone(override val time: Long, val target: String) : PollEvent

    /** First full pass finished: every existing pane has been reported. */
    data class Ready(override val time: Long) : PollEvent

    data class Heartbeat(override val time: Long) : PollEvent
}

object PollProtocol {
    /** Parses a poller line; returns null for anything unrecognised (forward compatible). */
    fun parse(line: String): PollEvent? {
        val f = line.trimEnd('\r').split('\t', limit = 11)
        val time = f.getOrNull(1)?.toLongOrNull() ?: return null
        return when (f[0]) {
            "S" -> if (f.size < 10) null else PollEvent.Pane(
                time = time,
                target = f[2],
                session = f[3],
                active = f[4] == "active",
                since = f[5].toLongOrNull() ?: 0,
                hash = f[6],
                bell = f[7].toIntOrNull() ?: 0,
                dead = f[8] == "1",
                command = f[9].takeUnless { it == "-" || it.isEmpty() },
                title = f.getOrElse(10) { "" },
            )
            "G" -> f.getOrNull(2)?.let { PollEvent.Gone(time, it) }
            "R" -> PollEvent.Ready(time)
            "H" -> PollEvent.Heartbeat(time)
            else -> null
        }
    }
}
