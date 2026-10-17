package dev.walkcode.app.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.walkcode.app.data.AgentPreset
import dev.walkcode.app.data.ShellKind
import dev.walkcode.app.data.WalkcodeRepository
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * M2 acceptance for the launcher against the Docker tmux host: creates the session in a
 * directory with a space (via `~`), starts the agent, lists it as running, re-attaches
 * without re-creating it, then kills it. Run through tools/spikes/m2-launch.ps1.
 */
@RunWith(AndroidJUnit4::class)
class SessionServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo by lazy { WalkcodeRepository.get(context) }
    private val service by lazy { SessionService(repo) }

    @Test
    fun launchListResumeKill(): Unit = runBlocking<Unit> {
        val hostId = repo.addHost("m2 docker", "dev", ShellKind.TMUX, lanHost = "10.0.2.2", port = 2222)
        val presetId = repo.upsertAgentPreset(
            AgentPreset(name = "fake", command = "echo WC_STARTED_FRESH; sleep 600", resumeCommand = "echo WC_STARTED_RESUME; sleep 600"),
        )
        val wsId = repo.addWorkspace("M2 Launch", hostId, "~/wc m2 dir", agentPresetId = presetId, args = "# it's arg")
        val host = repo.host(hostId)!!
        val ws = repo.workspace(wsId)!!

        val setup = service.connect(host)
        try { exec(setup.client, "mkdir -p \"\$HOME/wc m2 dir\"") } finally { setup.client.close() }

        val session = repo.createSession(wsId)
        val a = service.launch(host, ws, session, resume = false)
        try {
            assertEquals("tmux -u attach -t ${session.tmuxName}", a.command)
            assertTrue(a.pty)
        } finally { a.client.close() }

        val running = service.runningSessions(repo.host(hostId)!!)
        assertTrue("running: $running", session.tmuxName in running)

        val c = service.connect(host)
        try {
            Thread.sleep(1500)
            val screen = exec(c.client, "tmux capture-pane -p -t ${session.tmuxName}")
            assertTrue("agent output missing: $screen", screen.contains("WC_STARTED_FRESH"))
            assertTrue("cwd wrong", exec(c.client, "tmux display -p -t ${session.tmuxName} '#{pane_current_path}'").contains("wc m2 dir"))
        } finally { c.client.close() }

        // Existing session: attach only, no second agent start.
        service.launch(host, ws, session, resume = true).client.close()
        val c2 = service.connect(host)
        try {
            val screen = exec(c2.client, "tmux capture-pane -p -t ${session.tmuxName}")
            assertFalse("resume must not restart a live session", screen.contains("WC_STARTED_RESUME"))
        } finally { c2.client.close() }

        service.killSession(host, session)
        assertFalse(session.tmuxName in service.runningSessions(host))

        // Stopped session + resume uses the resume command.
        service.launch(host, ws, session, resume = true).client.close()
        val c3 = service.connect(host)
        try {
            Thread.sleep(1500)
            assertTrue(exec(c3.client, "tmux capture-pane -p -t ${session.tmuxName}").contains("WC_STARTED_RESUME"))
            exec(c3.client, "tmux kill-session -t ${session.tmuxName}")
        } finally { c3.client.close() }
    }

    private fun exec(ssh: SSHClient, cmd: String): String = ssh.startSession().use { s ->
        val c = s.exec(cmd)
        val out = c.inputStream.readBytes().toString(Charsets.UTF_8)
        c.join(10, TimeUnit.SECONDS)
        out
    }
}
