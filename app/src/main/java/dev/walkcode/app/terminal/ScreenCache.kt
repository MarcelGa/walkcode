package dev.walkcode.app.terminal

import android.content.Context
import java.io.File

/**
 * Last screen text of each session, so reopening a session shows something while the SSH
 * reconnect runs instead of a blank terminal. Plain text in the app cache directory: losing
 * it only costs the placeholder, so the OS may reclaim it freely.
 */
class ScreenCache(private val dir: File) {

    companion object {
        /** Enough to fill a phone screen and some scrollback; the rest of the transcript is not needed. */
        const val KEEP_LINES = 200

        fun of(context: Context) = ScreenCache(File(context.cacheDir, "screens"))
    }

    fun load(sessionId: Long): String? = runCatching { file(sessionId).takeIf { it.isFile }?.readText() }.getOrNull()

    /** Keeps only the last [KEEP_LINES] lines; the terminal scrolls back to the live screen anyway. */
    fun save(sessionId: Long, text: String) {
        val tail = text.lines().takeLast(KEEP_LINES).joinToString("\n")
        runCatching {
            dir.mkdirs()
            file(sessionId).writeText(tail)
        }
    }

    /** Called when the session is killed or removed, so no stale text outlives it. */
    fun delete(sessionId: Long) {
        file(sessionId).delete()
    }

    private fun file(sessionId: Long) = File(dir, "$sessionId.txt")
}
