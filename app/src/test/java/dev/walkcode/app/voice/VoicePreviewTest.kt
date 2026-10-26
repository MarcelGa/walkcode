package dev.walkcode.app.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePreviewTest {

    @Test fun speech_request_asks_for_raw_pcm_in_the_chosen_voice() {
        val obj = Json.parseToJsonElement(VoicePreview.speechRequest("cedar", "Hi.")).jsonObject
        assertEquals(VoicePreview.MODEL, obj["model"]!!.jsonPrimitive.content)
        assertEquals("cedar", obj["voice"]!!.jsonPrimitive.content)
        assertEquals("Hi.", obj["input"]!!.jsonPrimitive.content)
        assertEquals("pcm", obj["response_format"]!!.jsonPrimitive.content)
    }

    @Test fun cache_file_differs_by_voice_and_text_and_is_stable() {
        val marin = VoicePreview.fileName("marin", VoicePreview.phrase("Marin"))
        assertEquals(marin, VoicePreview.fileName("marin", VoicePreview.phrase("Marin")))
        assertTrue(marin.startsWith("marin-") && marin.endsWith(".pcm"))
        assertNotEquals(marin, VoicePreview.fileName("cedar", VoicePreview.phrase("Marin")))
        assertNotEquals(marin, VoicePreview.fileName("marin", VoicePreview.phrase("Karl")))
    }

    @Test fun phrase_names_the_assistant() {
        assertEquals("Hey, this is voice agent Karl.", VoicePreview.phrase("Karl"))
        assertEquals("Marin", RealtimeProtocol.voiceLabel("marin"))
    }

    @Test fun error_message_prefers_the_api_text() {
        assertEquals(
            "You exceeded your current quota.",
            VoicePreview.errorMessage(429, """{"error":{"message":"You exceeded your current quota.","code":"insufficient_quota"}}"""),
        )
        assertEquals("The OpenAI API key was rejected", VoicePreview.errorMessage(401, "not json"))
        assertEquals("HTTP 500", VoicePreview.errorMessage(500, null))
    }
}
