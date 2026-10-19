package dev.walkcode.app.walk

import dev.walkcode.app.walk.VoiceCatalog.Choice
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceFilterTest {
    private val voices = listOf(
        Choice("en-gb-x-female-1", "English (United Kingdom) · en-gb-x-female-1"),
        Choice("en-us-x-male-2", "English (United States) · en-us-x-male-2"),
        Choice("de-de-x-female-1", "German · de-de-x-female-1"),
    )

    @Test fun blankQueryKeepsEverything() = assertEquals(voices, VoiceCatalog.filter(voices, "  "))

    @Test fun termsAreAndedInAnyOrderIgnoringCase() {
        assertEquals(listOf(voices[0]), VoiceCatalog.filter(voices, "FEMALE english"))
    }

    @Test fun matchesIdAndLabel() {
        assertEquals(listOf(voices[2]), VoiceCatalog.filter(voices, "german"))
        assertEquals(listOf(voices[1]), VoiceCatalog.filter(voices, "us-x-male"))
    }
}
