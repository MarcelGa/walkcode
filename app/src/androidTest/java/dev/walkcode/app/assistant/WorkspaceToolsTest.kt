package dev.walkcode.app.assistant

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.walkcode.app.data.ShellKind
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.monitor.Monitor
import dev.walkcode.app.session.SessionService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walk mode's functions against the Docker tmux host (tools/testhost), without the voice model:
 * list, propose and send (nothing goes out without a proposal), then the overview and read showing what was sent.
 * Creates its own workspace and session and removes them again.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceToolsTest {
    @Test
    fun tools_list_send_and_read(): Unit = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = WalkcodeRepository.get(context)
        val service = SessionService(repo)
        val host = repo.hosts.first().firstOrNull { it.lanHost == "10.0.2.2" && it.port == 2222 }
            ?: repo.host(repo.addHost("docker", "dev", ShellKind.TMUX, lanHost = "10.0.2.2", port = 2222))!!
        val wsId = repo.addWorkspace("walktools", host.id, "/home/dev", projectId = null, agentPresetId = null)
        val session = repo.createSession(wsId)
        try {
            service.launch(host, repo.workspace(wsId)!!, session, resume = false).client.close()
            val tools = WorkspaceTools(repo, service, Monitor.get(context))

            val list = tools.call("list_workspaces", "{}")
            Log.i(TAG, "list_workspaces:\n$list")
            assertTrue(list, list.contains("Workspace \"walktools\"") && list.contains("id=${session.tmuxName}"))

            val question = "echo 'Should I deploy walk-tools to staging? (y/n)'"
            val early = tools.call("send_proposed", "{}")
            assertTrue(early, early.startsWith("Nothing is proposed"))
            val proposed = tools.call("propose_message", """{"session":"walktools","text":"$question"}""")
            assertTrue(proposed, proposed.startsWith("Not sent yet"))
            val sent = tools.call("send_proposed", "{}")
            assertTrue(sent, sent.startsWith("Sent"))
            // Sending again needs a new proposal.
            assertTrue(tools.call("send_proposed", "{}").startsWith("Nothing is proposed"))
            delay(1500)

            val overview = tools.call("workspace_overview", """{"workspace":"walktools"}""")
            Log.i(TAG, "workspace_overview:\n$overview")
            assertTrue(overview, overview.contains("Should I deploy walk-tools to staging? (y/n)"))
            val read = tools.call("read_session", """{"session":"${session.tmuxName}"}""")
            assertTrue(read, read.contains("Should I deploy walk-tools to staging? (y/n)"))
        } finally {
            runCatching { service.killSession(host, session) }
            repo.workspace(wsId)?.let { repo.deleteWorkspace(it) }
        }
    }

    private companion object {
        const val TAG = "WalkToolsTest"
    }
}
