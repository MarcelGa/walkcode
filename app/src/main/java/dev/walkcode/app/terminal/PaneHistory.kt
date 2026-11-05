package dev.walkcode.app.terminal

import com.termux.terminal.TerminalTransport
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch

/** What the terminal asks the host when the user swipes through a session. */
interface PaneHistorySource {
    /** The pane runs a full-screen program (less, vim): it has no history and scrolls by itself. */
    suspend fun isFullScreen(): Boolean

    /** The pane's history and screen with colours, oldest line first; null if it cannot be read. */
    suspend fun history(): String?
}

object PaneHistory {
    /** A local emulator's transcript; tmux's own default limit is 2000 lines. */
    const val ROWS = 10_000

    private val SGR = Regex("\u001b\\[[0-9;:]*m")

    /**
     * Captured pane text as terminal input: lines need a CR on a terminal, the blank end of a
     * part-filled pane would hide the text, and a resting cursor is meaningless here. Input modes are
     * copied from the live terminal because keys pressed while reading are encoded by this emulator;
     * without bracketed paste, a pasted line break would submit the agent's prompt.
     */
    fun toTerminalBytes(captured: String, applicationCursorKeys: Boolean, bracketedPaste: Boolean): ByteArray {
        val lines = captured.replace("\r\n", "\n").split('\n')
            .dropLastWhile { it.replace(SGR, "").isBlank() }
        return buildString {
            append("\u001b[?25l")
            if (applicationCursorKeys) append("\u001b[?1h")
            if (bracketedPaste) append("\u001b[?2004h")
            append(lines.joinToString("\r\n"))
            append("\u001b[0m")
        }.toByteArray(Charsets.UTF_8)
    }
}

/**
 * The transport under a local history view: nothing arrives from it, and everything written to it
 * is the user's input, given to [onInput] on a background thread.
 */
class PaneHistoryTransport(private val onInput: (ByteArray, Int, Int) -> Unit) : TerminalTransport {
    private val closed = CountDownLatch(1)

    private val input = object : InputStream() {
        override fun read(): Int {
            closed.await()
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = read()
    }

    private val output = object : OutputStream() {
        override fun write(b: Int) = onInput(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) = onInput(b, off, len)
    }

    override fun open(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {}
    override fun getInputStream(): InputStream = input
    override fun getOutputStream(): OutputStream = output
    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {}
    override fun getExitStatus(): Int = 0
    override fun close() = closed.countDown()
}
