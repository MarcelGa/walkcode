package dev.walkcode.app.monitor

import dev.walkcode.app.monitor.ScreenReading.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenReaderTest {
    private fun read(vararg lines: String) = ScreenReader.read(lines.joinToString("\n"))

    @Test fun claude_permission_prompt_needs_input_with_the_question() {
        val r = read(
            "● Update(src/Parser.kt)",
            "╭──────────────────────────────────────────────╮",
            "│ Edit file                                    │",
            "│ Do you want to make this edit to Parser.kt?  │",
            "│ ❯ 1. Yes                                     │",
            "│   2. Yes, allow all edits during this session │",
            "│   3. No, and tell Claude what to do (esc)    │",
            "╰──────────────────────────────────────────────╯",
        )
        assertEquals(ScreenReading(State.NEEDS_INPUT, "Do you want to make this edit to Parser.kt?"), r)
    }

    @Test fun copilot_command_prompt_needs_input() {
        val r = read(
            "Run shell command: npm test",
            "Do you want to run this command?",
            "❯ 1. Yes",
            "  2. Yes, and approve npm for the rest of the session",
            "  3. No, and tell Copilot what to do differently (Esc)",
        )
        assertEquals(State.NEEDS_INPUT, r?.state)
        assertEquals("Do you want to run this command?", r?.line)
    }

    @Test fun opencode_permission_needs_input() {
        val r = read("Permission required", "bash: rm -rf build", "Allow once   Allow always   Reject")
        assertEquals(State.NEEDS_INPUT, r?.state)
    }

    @Test fun shell_yes_no_needs_input() {
        assertEquals(State.NEEDS_INPUT, read("Overwrite config.json? (y/n)")?.state)
        assertEquals(State.NEEDS_INPUT, read("Overwrite config.json? [y/N]")?.state)
    }

    @Test fun running_turn_is_working() {
        val r = read("● Reading 3 files", "✻ Pondering… (12s · esc to interrupt)", "╭────╮", "│ >  │", "╰────╯")
        assertEquals(ScreenReading(State.WORKING, null), r)
    }

    @Test fun finished_turn_is_done_with_the_last_answer_line() {
        val r = read(
            "> fix the parser",
            "● Fixed the off-by-one in Parser.kt; all 42 tests pass.",
            "╭──────────────────────────╮",
            "│ >                        │",
            "╰──────────────────────────╯",
            "  ? for shortcuts",
        )
        assertEquals(ScreenReading(State.DONE, "Fixed the off-by-one in Parser.kt; all 42 tests pass."), r)
    }

    @Test fun answered_prompt_far_above_does_not_count() {
        val lines = listOf("Do you want to proceed?", "❯ 1. Yes") + (1..30).map { "output line $it" }
        assertEquals(State.DONE, ScreenReader.read(lines.joinToString("\n"))?.state)
    }

    @Test fun blank_screen_reads_nothing() {
        assertNull(read("", "   ", "╭──╮", "╰──╯"))
    }

    @Test fun alert_text_prefers_the_reading() {
        val bell = HostAlert(1, "h", "s", HostAlert.Kind.ATTENTION)
        assertEquals("Needs your attention", bell.text())
        assertEquals("Needs your input: Proceed?", bell.copy(reading = ScreenReading(State.NEEDS_INPUT, "Proceed?")).text())
        assertEquals("Done", bell.copy(kind = HostAlert.Kind.QUIET, reading = ScreenReading(State.DONE, null)).text())
    }
}
