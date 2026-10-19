package dev.walkcode.app.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseExtractorTest {
    @Test fun returns_only_new_lines() {
        val before = "old line one\nold line two"
        val after = "old line one\nold line two\nThe fix is in Parser.kt"
        assertEquals("The fix is in Parser.kt", ResponseExtractor.newText(before, after))
    }

    @Test fun survives_capture_window_scrolling() {
        val before = "a first\nb second\nc third"
        val after = "b second\nc third\nd fourth"
        assertEquals("d fourth", ResponseExtractor.newText(before, after))
    }

    @Test fun strips_bullet_markers_and_markdown() {
        val out = ResponseExtractor.newText("", "● Updated `Parser.kt` with **new** logic")
        assertEquals("Updated Parser.kt with new logic", out)
    }

    @Test fun drops_box_drawing_input_spinner_and_footer() {
        val after = listOf(
            "╭──────────────────╮",
            "│ > fix the bug    │",
            "╰──────────────────╯",
            "✻ Pondering… (3s · esc to interrupt)",
            "  ? for shortcuts",
            "● Done, tests pass",
        ).joinToString("\n")
        assertEquals("Done, tests pass", ResponseExtractor.newText("", after))
    }

    @Test fun drops_echo_of_dictated_text() {
        assertEquals("Sure", ResponseExtractor.newText("", "run the tests\nSure", sent = "Run the tests"))
    }

    @Test fun urls_are_not_spelled_out() {
        assertEquals("See link for details", ResponseExtractor.newText("", "See https://example.com/a/b?c=d for details"))
    }

    @Test fun repeated_line_counts_as_new_when_it_appears_again() {
        assertEquals("ok", ResponseExtractor.newText("ok", "ok\nok"))
    }

    @Test fun chunks_respect_limit_on_line_boundaries() {
        val chunks = ResponseExtractor.chunks("aaaa\nbbbb\ncccc", 9)
        assertEquals(listOf("aaaa\nbbbb", "cccc"), chunks)
        assertTrue(ResponseExtractor.chunks("x".repeat(25), 10).all { it.length <= 10 })
    }
}
