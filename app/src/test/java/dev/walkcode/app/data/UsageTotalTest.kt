package dev.walkcode.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTotalTest {

    private val rows = listOf(
        VoiceUsage("2026-09-30", "gpt-realtime-2", textIn = 100, audioIn = 900, cachedIn = 500, audioOut = 1000, costUsd = 0.10),
        VoiceUsage("2026-10-01", "gpt-realtime-2", audioIn = 1000, audioOut = 1000, costUsd = 0.20),
        VoiceUsage("2026-10-26", "gpt-realtime-2.1-mini", audioIn = 500, cachedIn = 200, audioOut = 500, costUsd = 0.03),
    )

    @Test fun sums_rows_from_a_day_on() {
        val all = UsageTotal.of(rows)
        assertEquals(5000, all.tokens)
        assertEquals(700, all.cachedIn)
        assertEquals(0.33, all.usd, 1e-9)
        val october = UsageTotal.of(rows, "2026-10-01")
        assertEquals(3000, october.tokens)
        assertEquals(0.23, october.usd, 1e-9)
        assertEquals(1000, UsageTotal.of(rows, "2026-10-26").tokens)
        assertEquals(UsageTotal(), UsageTotal.of(rows, "2026-11-01"))
    }
}
