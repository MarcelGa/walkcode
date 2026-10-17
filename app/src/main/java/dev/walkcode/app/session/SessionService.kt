package dev.walkcode.app.session

import dev.walkcode.app.data.Host
import dev.walkcode.app.data.Session
import dev.walkcode.app.data.Workspace
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.ssh.DeviceKey
import dev.walkcode.app.ssh.HostConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.util.concurrent.TimeUnit

/** An SSH connection plus what is needed to attach a terminal to a session on it. */
class Attachment(val client: SSHClient, val command: String, val pty: Boolean, val address: String)

/**
 * Connect/launch flows shared by the workspace and terminal screens: opens a host
 * connection (persisting the TOFU pin on first use), checks which sessions exist and
 * starts the agent when a session is missing.
 */
class SessionService(
    private val repository: WalkcodeRepository,
    private val connector: HostConnector = HostConnector(DeviceKey.getOrCreate()),
) {

    suspend fun connect(host: Host, onAttempt: (String) -> Unit = {}): HostConnector.Connected {
        val connected = connector.connect(host, onAttempt = onAttempt)
        if (connected.newlyTrusted) {
            repository.trustHostKey(host.id, connected.hostKey.algorithm, connected.hostKey.fingerprint)
        }
        return connected
    }

    /** Names of the sessions currently alive on the host. */
    suspend fun runningSessions(host: Host): Set<String> {
        val connected = connect(host)
        return try {
            val out = exec(connected.client, MuxCommands(host.shell).listSessions()).output
            out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        } finally {
            close(connected.client)
        }
    }

    /**
     * Connects, creates the multiplexer session (and starts the agent in it) when it does
     * not exist yet, and returns what the terminal needs to attach. [resume] starts the
     * agent with the preset's resume command instead of a fresh run.
     */
    suspend fun launch(
        host: Host,
        workspace: Workspace,
        session: Session,
        resume: Boolean,
        onAttempt: (String) -> Unit = {},
    ): Attachment {
        val connected = connect(host, onAttempt)
        try {
            val mux = MuxCommands(host.shell)
            val exists = exec(connected.client, mux.hasSession(session.tmuxName)).status == 0
            if (!exists) {
                val created = exec(connected.client, mux.newSession(session.tmuxName, workspace.directory))
                check(created.status == 0) { "could not create session: ${created.output.trim()}" }
                val preset = workspace.agentPresetId?.let { repository.agentPreset(it) }
                if (preset != null) {
                    val base = if (resume) preset.resumeCommand ?: preset.command else preset.command
                    val line = listOfNotNull(base, workspace.args?.takeIf { it.isNotBlank() }).joinToString(" ")
                    for (cmd in mux.sendCommand(session.tmuxName, line)) exec(connected.client, cmd)
                }
            }
            repository.touchWorkspace(workspace.id)
            return Attachment(connected.client, mux.attach(session.tmuxName), mux.usesPty, connected.address)
        } catch (e: Throwable) {
            close(connected.client)
            throw e
        }
    }

    suspend fun killSession(host: Host, session: Session) {
        val connected = connect(host)
        try {
            val mux = MuxCommands(host.shell)
            exec(connected.client, mux.killSession(session.tmuxName))
        } finally {
            close(connected.client)
        }
    }

    fun close(client: SSHClient) {
        // Disconnecting sends packets, which Android forbids on the main thread.
        Thread { runCatching { client.disconnect() } }.start()
    }

    private class ExecResult(val status: Int, val output: String)

    private suspend fun exec(client: SSHClient, command: String): ExecResult = withContext(Dispatchers.IO) {
        client.startSession().use { s ->
            val c = s.exec(command)
            val out = c.inputStream.readBytes().toString(Charsets.UTF_8)
            c.join(10, TimeUnit.SECONDS)
            ExecResult(c.exitStatus ?: -1, out)
        }
    }
}
