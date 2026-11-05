package dev.walkcode.app.monitor

/**
 * Keeps the last message of one host's watched sessions current. Reading a screen is an SSH exec,
 * so a session is only read when the poller saw its screen change, and once more after a read that
 * failed. An idle session therefore costs nothing.
 */
class ScreenRefresh(
    private val capture: suspend (session: String) -> String?,
    private val onReading: (session: String, reading: ScreenReading) -> Unit,
) {
    private val readAt = HashMap<String, Long>()

    /** One pass. [changes] is each session's last screen change, as the poller reported it. */
    suspend fun pass(watched: Set<String>, changes: Map<String, Long>) {
        for (session in watched) {
            val change = changes[session] ?: continue
            if (readAt[session] == change) continue
            val screen = capture(session) ?: continue
            readAt[session] = change
            ScreenReader.read(screen)?.let { onReading(session, it) }
        }
    }
}
