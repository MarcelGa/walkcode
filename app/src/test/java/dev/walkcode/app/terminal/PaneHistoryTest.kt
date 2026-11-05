package dev.walkcode.app.terminal

import dev.walkcode.app.data.ShellKind
import dev.walkcode.app.session.MuxCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneHistoryTest {
    private fun bytes(s: String, cursor: Boolean = false, paste: Boolean = false) =
        PaneHistory.toTerminalBytes(s, cursor, paste).toString(Charsets.UTF_8)

    @Test fun lines_get_a_carriage_return() {
        assertTrue(bytes("one\ntwo").contains("one\r\ntwo"))
        assertTrue(bytes("one\r\ntwo").contains("one\r\ntwo"))
    }

    @Test fun blank_end_of_the_pane_is_dropped() {
        val out = bytes("one\n\n\u001b[0m\n   \n")
        assertTrue(out.contains("one"))
        assertFalse(out.contains("one\r\n"))
    }

    @Test fun blank_lines_inside_the_text_stay() {
        assertTrue(bytes("one\n\ntwo\n").contains("one\r\n\r\ntwo"))
    }

    @Test fun colours_stay_and_are_reset_at_the_end() {
        val out = bytes("\u001b[31mred\n")
        assertTrue(out.contains("\u001b[31mred"))
        assertTrue(out.endsWith("\u001b[0m"))
    }

    @Test fun cursor_is_hidden_and_live_input_modes_are_copied() {
        assertTrue(bytes("x").startsWith("\u001b[?25l"))
        assertFalse(bytes("x").contains("?1h"))
        assertFalse(bytes("x").contains("?2004h"))
        assertTrue(bytes("x", cursor = true).contains("\u001b[?1h"))
        assertTrue(bytes("x", paste = true).contains("\u001b[?2004h"))
    }

    @Test fun mux_commands() {
        assertEquals("tmux capture-pane -p -e -J -S -500 -t s", MuxCommands(ShellKind.TMUX).scrollback("s", 500))
        assertEquals("psmux capture-pane -p -e -J -S -500 -t s", MuxCommands(ShellKind.PSMUX).scrollback("s", 500))
        assertEquals("tmux list-panes -t s -F '#{alternate_on}'", MuxCommands(ShellKind.TMUX).alternateScreen("s"))
        assertEquals("psmux list-panes -t s -F \"#{alternate_on}\"", MuxCommands(ShellKind.PSMUX).alternateScreen("s"))
    }
}
