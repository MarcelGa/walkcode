package dev.walkcode.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiKeyTest {

    @Test fun plain_key_is_kept() {
        assertEquals("sk-proj-abc_DEF-123", OpenAiKey.clean("sk-proj-abc_DEF-123"))
    }

    @Test fun surrounding_whitespace_and_line_breaks_are_dropped() {
        assertEquals("sk-proj-abc123", OpenAiKey.clean("  sk-proj-abc123\n"))
    }

    @Test fun label_line_before_the_key_is_dropped() {
        assertEquals("sk-proj-abc123", OpenAiKey.clean("OpenAI\nsk-proj-abc123"))
        assertEquals("sk-proj-abc123", OpenAiKey.clean("OpenAI key:\nsk-proj-abc123\nCreated today"))
    }

    @Test fun label_glued_to_the_key_by_an_earlier_build_is_cut_off() {
        assertEquals("sk-proj-abc123", OpenAiKey.clean("OpenAIsk-proj-abc123"))
    }

    @Test fun blank_or_missing_is_null() {
        assertNull(OpenAiKey.clean(null))
        assertNull(OpenAiKey.clean(" \n "))
    }

    @Test fun without_an_sk_token_the_longest_token_wins() {
        assertEquals("abcdefghijkl", OpenAiKey.clean("key abcdefghijkl"))
    }

    @Test fun masked_shows_prefix_and_last_four() {
        assertEquals("sk-proj-…1234", OpenAiKey.masked("sk-proj-abcdefgh1234"))
        assertEquals("sk-…1234", OpenAiKey.masked("sk-abcdefgh1234"))
        assertEquals("…", OpenAiKey.masked("sk-12"))
    }
}
