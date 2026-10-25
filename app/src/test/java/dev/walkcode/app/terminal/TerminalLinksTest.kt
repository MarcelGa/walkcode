package dev.walkcode.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalLinksTest {
    @Test fun finds_url_under_tap() {
        val line = "see https://example.com/a?b=1 now"
        assertEquals("https://example.com/a?b=1", TerminalLinks.urlAt(line, 6))
        assertEquals("https://example.com/a?b=1", TerminalLinks.urlAt(line, 28))
    }

    @Test fun tap_outside_url_finds_nothing() {
        val line = "see https://example.com now"
        assertNull(TerminalLinks.urlAt(line, 0))
        assertNull(TerminalLinks.urlAt(line, 27))
    }

    @Test fun trailing_punctuation_is_not_part_of_url() {
        assertEquals("https://x.y/z", TerminalLinks.urlAt("(see https://x.y/z).", 8))
        assertEquals("http://a.b", TerminalLinks.urlAt("go to http://a.b.", 6))
    }

    @Test fun stops_at_quotes_and_brackets() {
        assertEquals("https://x.y", TerminalLinks.urlAt("\"https://x.y\"", 3))
    }

    @Test fun non_http_schemes_are_ignored() {
        assertNull(TerminalLinks.urlAt("file:///sdcard/a", 3))
    }

    @Test fun two_urls_on_one_line() {
        val line = "https://a.b https://c.d"
        assertEquals("https://a.b", TerminalLinks.urlAt(line, 2))
        assertEquals("https://c.d", TerminalLinks.urlAt(line, 15))
    }
}
