package dev.walkcode.app.poll

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/** Runs the host poller over an existing SSH connection and reports its events. */
class HostPoller(private val client: SSHClient, private val command: String) : Closeable {

    val bytesReceived = AtomicLong()

    @Volatile private var session: Session? = null
    @Volatile private var channel: Session.Command? = null

    /** Blocks until the channel ends (or [close] is called). Unparseable lines are skipped. */
    fun run(onEvent: (PollEvent) -> Unit) {
        val s = client.startSession()
        session = s
        val c = s.exec(command)
        channel = c
        CountingStream(c.inputStream).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) PollProtocol.parse(line)?.let(onEvent)
        }
    }

    /** The poller's stderr, for diagnostics after [run] returned. */
    fun errorOutput(): String = runCatching { channel?.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()

    override fun close() {
        runCatching { channel?.close() }
        runCatching { session?.close() }
    }

    private inner class CountingStream(input: InputStream) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) bytesReceived.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) bytesReceived.addAndGet(it.toLong()) }
    }
}
