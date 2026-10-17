package dev.walkcode.app.monitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.walkcode.app.data.ShellKind
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.session.SessionService
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a test of its own: seeds the app database with a Docker-host workspace and a watched
 * session (and creates it on the host) so tools/spikes/m3-monitor.ps1 can drive the app.
 */
@RunWith(AndroidJUnit4::class)
class SeedWatchedSessionTest {
    @Test
    fun seed(): Unit = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = WalkcodeRepository.get(context)
        val hostId = repo.addHost("docker", "dev", ShellKind.TMUX, lanHost = "10.0.2.2", port = 2222)
        val projectId = repo.addProject("Work")
        val wsId = repo.addWorkspace("Seed", hostId, "/home/dev", projectId = projectId, agentPresetId = null)
        val session = repo.createSession(wsId, watched = true)
        SessionService(repo).launch(repo.host(hostId)!!, repo.workspace(wsId)!!, session, resume = false).client.close()
    }
}
