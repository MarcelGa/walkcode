package dev.walkcode.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Pane title storage on the device's real SQLite (no host needed). See tools/spikes/autotitle.ps1. */
@RunWith(AndroidJUnit4::class)
class AutoTitleStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo by lazy { WalkcodeRepository.get(context) }

    @Test
    fun titleStoredAndLabelPrecedence(): Unit = runBlocking<Unit> {
        val hostId = repo.addHost("autotitle store", "nobody", ShellKind.TMUX, lanHost = "127.0.0.1", port = 1)
        val wsId = repo.addWorkspace("AutoTitle Store", hostId, "~")
        val session = repo.createSession(wsId)

        repo.recordPaneTitle(session.tmuxName, "* Fix login bug")
        assertEquals("Fix login bug", repo.sessionByName(session.tmuxName)!!.autoTitle)
        assertEquals("Fix login bug", repo.sessionByName(session.tmuxName)!!.displayName())

        // Unchanged title is a no-op; the value stays put.
        repo.recordPaneTitle(session.tmuxName, "✳ Fix login bug")
        assertEquals("Fix login bug", repo.sessionByName(session.tmuxName)!!.autoTitle)

        repo.renameSession(session.id, "build")
        assertEquals("build", repo.sessionByName(session.tmuxName)!!.displayName())
        // The label does not overwrite the stored title.
        repo.recordPaneTitle(session.tmuxName, "* Investigate flaky test")
        assertEquals("Investigate flaky test", repo.sessionByName(session.tmuxName)!!.autoTitle)
        assertEquals("build", repo.sessionByName(session.tmuxName)!!.displayName())

        repo.renameSession(session.id, "  ")
        assertEquals("Investigate flaky test", repo.sessionByName(session.tmuxName)!!.displayName())

        // A symbol-only title clears the automatic name, so the UI falls back to #N.
        repo.recordPaneTitle(session.tmuxName, "✳")
        val cleared = repo.sessionByName(session.tmuxName)!!
        assertNull(cleared.autoTitle)
        assertEquals("#1", cleared.displayName())
    }
}
