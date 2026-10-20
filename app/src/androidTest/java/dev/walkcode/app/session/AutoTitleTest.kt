package dev.walkcode.app.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.walkcode.app.data.ShellKind
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.data.displayName
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Pane title -> session name on the Windows desktop (psmux): the title is set with
 * `psmux select-pane -T`, listed by [SessionService.runningSessions], stored as the automatic
 * name, and a user label still overrides it. Run through tools/spikes/autotitle.ps1.
 */
@RunWith(AndroidJUnit4::class)
class AutoTitleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo by lazy { WalkcodeRepository.get(context) }
    private val service by lazy { SessionService(repo) }

    @Test
    fun paneTitleNamesSessionWindows(): Unit = runBlocking<Unit> {
        val user = InstrumentationRegistry.getArguments().getString("winUser") ?: error("pass -e winUser <windows user>")
        val hostId = repo.addHost("autotitle windows", user, ShellKind.PSMUX, lanHost = "10.0.2.2", port = 22)
        val wsId = repo.addWorkspace("AutoTitle Win", hostId, "~/wc autotitle dir")
        val host = repo.host(hostId)!!
        val ws = repo.workspace(wsId)!!

        val setup = service.connect(host)
        try { exec(setup.client, "mkdir \"%USERPROFILE%\\wc autotitle dir\" 2>nul") } finally { setup.client.close() }

        val session = repo.createSession(wsId)
        try {
            service.launch(host, ws, session, resume = false).client.close()

            // The agent would set this itself; select-pane is the deterministic stand-in. The
            // leading "* " is what a status glyph looks like once it arrives through cmd.exe.
            val set = service.connect(host)
            try {
                exec(set.client, "psmux select-pane -t ${session.tmuxName} -T \"* Fix login bug\"")
            } finally { set.client.close() }

            val panes = service.runningSessions(repo.host(hostId)!!)
            val raw = panes[session.tmuxName]
            assertTrue("pane title not listed: $panes", raw != null && raw.contains("Fix login bug"))

            repo.recordPaneTitle(session.tmuxName, raw!!)
            val stored = repo.sessionByName(session.tmuxName)!!
            assertEquals("Fix login bug", stored.autoTitle)
            assertEquals("Fix login bug", stored.displayName())

            // A user label wins, and clearing it brings the title back.
            repo.renameSession(stored.id, "build")
            assertEquals("build", repo.sessionByName(session.tmuxName)!!.displayName())
            repo.renameSession(stored.id, null)
            assertEquals("Fix login bug", repo.sessionByName(session.tmuxName)!!.displayName())

            // A new title on the pane replaces the automatic name.
            val again = service.connect(host)
            try {
                exec(again.client, "psmux select-pane -t ${session.tmuxName} -T \"* Investigate flaky test\"")
            } finally { again.client.close() }
            repo.recordPaneTitle(session.tmuxName, service.runningSessions(host)[session.tmuxName]!!)
            assertEquals("Investigate flaky test", repo.sessionByName(session.tmuxName)!!.displayName())

            // Empty title after the agent exits: no automatic name, so the UI falls back to #1.
            repo.recordPaneTitle(session.tmuxName, "   ")
            val blank = repo.sessionByName(session.tmuxName)!!
            assertNull(blank.autoTitle)
            assertEquals("#1", blank.displayName())
        } finally {
            service.killSession(host, session)
        }
    }

    private fun exec(ssh: SSHClient, cmd: String): String = ssh.startSession().use { s ->
        val c = s.exec(cmd)
        val out = c.inputStream.readBytes().toString(Charsets.UTF_8)
        c.join(10, TimeUnit.SECONDS)
        out
    }
}
