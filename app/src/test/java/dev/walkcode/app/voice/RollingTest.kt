package dev.walkcode.app.voice

import dev.walkcode.app.voice.VoiceSession.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingTest {
    private val every = 15 * 60_000L

    @Test fun waits_for_the_interval_and_for_quiet() {
        assertFalse(Rolling.due(nowMs = every - 1, sinceMs = 0, lastActivityMs = 0, busy = false, everyMs = every))
        assertFalse(Rolling.due(nowMs = every, sinceMs = 0, lastActivityMs = every - 5_000, busy = false, everyMs = every))
        assertTrue(Rolling.due(nowMs = every, sinceMs = 0, lastActivityMs = every - Rolling.QUIET_MS, busy = false, everyMs = every))
    }

    @Test fun never_rolls_while_something_is_in_progress() {
        assertFalse(Rolling.due(nowMs = 10 * every, sinceMs = 0, lastActivityMs = 0, busy = true, everyMs = every))
    }

    @Test fun a_long_chatty_session_takes_a_short_pause() {
        val now = 2 * every
        assertTrue(Rolling.due(now, 0, now - Rolling.SHORT_QUIET_MS, busy = false, everyMs = every))
        assertFalse(Rolling.due(now, 0, now - 1_000, busy = false, everyMs = every))
    }

    @Test fun default_interval_is_used() {
        assertFalse(Rolling.due(nowMs = Rolling.EVERY_MS - 1, sinceMs = 0, lastActivityMs = 0, busy = false))
        assertTrue(Rolling.due(nowMs = Rolling.EVERY_MS, sinceMs = 0, lastActivityMs = 0, busy = false))
    }

    @Test fun carryover_keeps_the_newest_turns_in_order() {
        val turns = listOf(
            Turn(fromUser = true, text = "old question"),
            Turn(fromUser = false, text = "old answer"),
            Turn(fromUser = false, text = "Reading the screens", tool = true),
            Turn(fromUser = true, text = "what is api doing"),
            Turn(fromUser = false, text = "It is running the tests."),
        )
        assertEquals(
            "User: old question\nYou: old answer\n(done: Reading the screens)\nUser: what is api doing\nYou: It is running the tests.",
            Rolling.carryover(turns),
        )
        // Only what fits, cut at a turn boundary, newest kept.
        assertEquals("User: what is api doing\nYou: It is running the tests.", Rolling.carryover(turns, maxChars = 55))
    }

    @Test fun one_huge_turn_is_shortened_from_its_start() {
        val c = Rolling.carryover(listOf(Turn(fromUser = false, text = "x".repeat(100) + "END")), maxChars = 20)!!
        assertEquals(20, c.length)
        assertTrue(c.startsWith("…") && c.endsWith("END"))
    }

    @Test fun nothing_said_means_no_carryover() {
        assertNull(Rolling.carryover(emptyList()))
        assertNull(Rolling.carryover(listOf(Turn(fromUser = false, text = Rolling.NOTE, tool = true))))
        assertEquals("Base.", Rolling.instructions("Base.", null))
        assertTrue(Rolling.instructions("Base.", "User: hi").let { it.startsWith("Base.") && "User: hi" in it })
    }
}
