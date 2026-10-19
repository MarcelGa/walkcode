package dev.walkcode.app.session

import dev.walkcode.app.data.ShellKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuxCommandsTest {

    @Test fun posixQuoteEscapesSingleQuotes() {
        assertEquals("'it'\\''s'", MuxCommands.posixQuote("it's"))
    }

    @Test fun posixPathExpandsTildeOutsideQuotes() {
        assertEquals("\"\$HOME\"'/src/my app'", MuxCommands.posixPath("~/src/my app"))
        assertEquals("\"\$HOME\"", MuxCommands.posixPath("~"))
        assertEquals("'/opt/x'", MuxCommands.posixPath("/opt/x"))
    }

    @Test fun windowsPathUsesUserProfileAndBackslashes() {
        assertEquals("\"%USERPROFILE%\\src\\tollbff\"", MuxCommands.windowsPath("~/src/tollbff"))
        assertEquals("\"C:\\work\"", MuxCommands.windowsPath("C:/work"))
    }

    @Test fun tmuxNewSessionAndSend() {
        val c = MuxCommands(ShellKind.TMUX)
        assertTrue(c.usesPty)
        assertEquals("tmux new-session -d -s wc-a-1 -c '/srv' -x 80 -y 24", c.newSession("wc-a-1", "/srv"))
        assertEquals(
            listOf("tmux send-keys -t wc-a-1 -l -- 'claude --x'", "tmux send-keys -t wc-a-1 Enter"),
            c.sendCommand("wc-a-1", "claude --x"),
        )
        assertEquals("tmux -u attach -t wc-a-1", c.attach("wc-a-1"))
    }

    @Test fun psmuxSubmitsWithRawCarriageReturnAndStartsShellInit() {
        val c = MuxCommands(ShellKind.PSMUX)
        assertFalse(c.usesPty)
        assertTrue(c.newSession("wc-a-1", "~/x").contains("shell-init.ps1"))
        assertTrue(c.newSession("wc-a-1", "~/x").contains("set WALKCODE_SESSION=1&&"))
        assertEquals("psmux send-keys -t wc-a-1 -H 0d", c.sendCommand("wc-a-1", "claude").last())
        assertEquals("psmux attach -t wc-a-1", c.attach("wc-a-1"))
    }

    @Test fun captureReadsHistoryOrJustTheScreen() {
        assertEquals("tmux capture-pane -p -J -t wc-a-1 -S -300", MuxCommands(ShellKind.TMUX).capture("wc-a-1", 300))
        assertEquals("psmux capture-pane -p -J -t wc-a-1", MuxCommands(ShellKind.PSMUX).capture("wc-a-1", null))
    }
}
