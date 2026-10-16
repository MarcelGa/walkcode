package dev.walkcode.app.terminal

import com.termux.terminal.TerminalTransport
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [command] (e.g. `tmux -u attach -t name`) over an existing SSH connection. Only the
 * channel is owned here; the [SSHClient] belongs to the caller.
 *
 * [pty] = true allocates a remote PTY and reports resizes as SSH window-change requests (tmux).
 * [pty] = false uses a plain channel (psmux on Windows: a remote PTY there means ConPTY, which
 * swallows Ctrl-C and mouse bytes). Without a PTY the size travels in-band: psmux asks with
 * XTWINOPS `ESC[18t`, the emulator answers `ESC[8;rows;cols t`, and on resize we send that
 * report unprompted.
 */
class SshTerminalTransport(
    private val client: SSHClient,
    private val command: String,
    private val pty: Boolean = true,
    private val term: String = "xterm-256color",
) : TerminalTransport {

    // Resize and close send SSH packets; Android forbids network I/O on the main thread.
    private val control = Executors.newSingleThreadExecutor { Thread(it, "ssh-pty-control") }
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile private var session: Session? = null
    @Volatile private var channel: Session.Command? = null
    @Volatile private var output: OutputStream? = null

    override fun open(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        val s = client.startSession()
        session = s
        if (pty) s.allocatePTY(term, columns, rows, columns * cellWidthPixels, rows * cellHeightPixels, emptyMap())
        val c = s.exec(command)
        channel = c
        output = LockedOutputStream(c.outputStream)
    }

    override fun getInputStream(): InputStream = requireNotNull(channel).inputStream

    override fun getOutputStream(): OutputStream = requireNotNull(output)

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        val c = channel ?: return
        if (closed.get()) return
        control.execute {
            runCatching {
                if (pty) {
                    // The exec channel is a SessionChannel, which also implements Shell's window-change request.
                    (c as Session.Shell).changeWindowDimensions(columns, rows, columns * cellWidthPixels, rows * cellHeightPixels)
                } else {
                    output?.apply { write("\u001b[8;$rows;${columns}t".toByteArray()); flush() }
                }
            }
        }
    }

    override fun getExitStatus(): Int = channel?.exitStatus ?: -1

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        control.execute {
            runCatching { channel?.close() }
            runCatching { session?.close() }
        }
        control.shutdown()
    }

    /** Keeps in-band size reports from interleaving with keystrokes written by the session. */
    private inner class LockedOutputStream(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = synchronized(writeLock) { out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = synchronized(writeLock) { out.write(b, off, len) }
        override fun flush() = synchronized(writeLock) { out.flush() }
        override fun close() = out.close()
    }
}
