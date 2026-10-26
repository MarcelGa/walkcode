package dev.walkcode.app.voice

import dev.walkcode.app.data.AppSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RealtimeProtocolTest {

    @Test fun session_update_sets_voice_pcm_and_server_vad() {
        val obj = Json.parseToJsonElement(RealtimeProtocol.sessionUpdate("cedar", "Be brief.")).jsonObject
        assertEquals("session.update", obj["type"]!!.jsonPrimitive.content)
        val session = obj["session"]!!.jsonObject
        assertEquals("realtime", session["type"]!!.jsonPrimitive.content)
        assertEquals("Be brief.", session["instructions"]!!.jsonPrimitive.content)
        val audio = session["audio"]!!.jsonObject
        val output = audio["output"]!!.jsonObject
        assertEquals("cedar", output["voice"]!!.jsonPrimitive.content)
        assertEquals("audio/pcm", output["format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("24000", output["format"]!!.jsonObject["rate"]!!.jsonPrimitive.content)
        val input = audio["input"]!!.jsonObject
        val vad = input["turn_detection"]!!.jsonObject
        assertEquals("server_vad", vad["type"]!!.jsonPrimitive.content)
        assertEquals(RealtimeProtocol.VAD_THRESHOLD.toString(), vad["threshold"]!!.jsonPrimitive.content)
        assertEquals("near_field", input["noise_reduction"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(RealtimeProtocol.DEFAULT_MODEL, session["model"]!!.jsonPrimitive.content)
    }

    @Test fun session_update_carries_the_chosen_model() {
        val session = Json.parseToJsonElement(
            RealtimeProtocol.sessionUpdate("marin", "Hi", model = RealtimeProtocol.CHEAPEST_MODEL),
        ).jsonObject["session"]!!.jsonObject
        assertEquals("gpt-realtime-2.1-mini", session["model"]!!.jsonPrimitive.content)
    }

    @Test fun every_model_has_a_label_and_the_cheapest_is_listed() {
        assertTrue(RealtimeProtocol.MODELS.any { it.id == RealtimeProtocol.CHEAPEST_MODEL })
        assertTrue(RealtimeProtocol.MODELS.any { it.id == RealtimeProtocol.DEFAULT_MODEL })
        assertEquals("GPT Realtime 2.1 mini", RealtimeProtocol.modelLabel(RealtimeProtocol.CHEAPEST_MODEL))
        assertEquals("some-future-model", RealtimeProtocol.modelLabel("some-future-model"))
    }

    @Test fun append_audio_encodes_pcm_as_base64() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val obj = Json.parseToJsonElement(RealtimeProtocol.appendAudio(pcm)).jsonObject
        assertEquals("input_audio_buffer.append", obj["type"]!!.jsonPrimitive.content)
        assertArrayEquals(pcm, Base64.getDecoder().decode(obj["audio"]!!.jsonPrimitive.content))
    }

    @Test fun parses_audio_delta_in_ga_and_beta_names() {
        val pcm = byteArrayOf(9, 8, 7, 6)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        for (type in listOf("response.output_audio.delta", "response.audio.delta")) {
            val event = RealtimeProtocol.parse("""{"type":"$type","delta":"$b64"}""")
            assertArrayEquals(pcm, (event as RealtimeProtocol.Event.AudioDelta).pcm)
        }
    }

    @Test fun parses_transcripts_and_control_events() {
        assertEquals(
            RealtimeProtocol.Event.AssistantText("Hi"),
            RealtimeProtocol.parse("""{"type":"response.output_audio_transcript.delta","delta":"Hi"}"""),
        )
        assertEquals(
            RealtimeProtocol.Event.UserText("open the repo"),
            RealtimeProtocol.parse("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"open the repo"}"""),
        )
        assertEquals(RealtimeProtocol.Event.SpeechStarted, RealtimeProtocol.parse("""{"type":"input_audio_buffer.speech_started"}"""))
        assertEquals(RealtimeProtocol.Event.ResponseDone(), RealtimeProtocol.parse("""{"type":"response.done"}"""))
    }

    @Test fun response_usage_is_split_by_modality_and_cache() {
        // Shape of the cost guide's example (developers.openai.com/api/docs/guides/realtime-costs).
        val event = RealtimeProtocol.parse(
            """{"type":"response.done","response":{"status":"completed","usage":{"total_tokens":253,"input_tokens":132,""" +
                """"output_tokens":121,"input_token_details":{"text_tokens":119,"audio_tokens":13,"image_tokens":0,""" +
                """"cached_tokens":64,"cached_tokens_details":{"text_tokens":64,"audio_tokens":0}},""" +
                """"output_token_details":{"text_tokens":30,"audio_tokens":91}}}}""",
        )
        val usage = Usage(textIn = 119, audioIn = 13, cachedTextIn = 64, cachedAudioIn = 0, textOut = 30, audioOut = 91)
        assertEquals(RealtimeProtocol.Event.ResponseDone(usage), event)
        assertEquals(253, usage.total)
    }

    @Test fun transcription_usage_in_tokens_or_seconds() {
        val tokens = RealtimeProtocol.parse(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"hi","usage":{"type":"tokens",""" +
                """"total_tokens":40,"input_tokens":30,"input_token_details":{"text_tokens":0,"audio_tokens":30},"output_tokens":10}}""",
        )
        assertEquals(RealtimeProtocol.Event.UserText("hi", Usage(audioIn = 30, textOut = 10)), tokens)
        val seconds = RealtimeProtocol.parse(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"hi","usage":{"type":"duration","seconds":3}}""",
        )
        assertEquals(RealtimeProtocol.Event.UserText("hi", Usage(audioIn = 30)), seconds)
    }

    @Test fun cost_uses_the_cached_rate_for_the_cached_part() {
        val prices = RealtimeProtocol.pricesFor("gpt-realtime-2")
        // 1M fresh audio in, 1M cached audio in, 1M audio out: $32 + $0.40 + $64.
        val u = Usage(audioIn = 2_000_000, cachedAudioIn = 1_000_000, audioOut = 1_000_000)
        assertEquals(96.40, prices.cost(u), 1e-9)
        // The mini model is cheaper on the same usage, an unknown model never cheaper than the full one.
        assertTrue(RealtimeProtocol.pricesFor(RealtimeProtocol.CHEAPEST_MODEL).cost(u) < prices.cost(u))
        assertEquals(prices.cost(u), RealtimeProtocol.pricesFor("gpt-realtime-9").cost(u), 1e-9)
    }

    @Test fun usage_formats() {
        assertEquals("$0.004", UsageFormat.usd(0.0042))
        assertEquals("$12.40", UsageFormat.usd(12.4))
        assertEquals("850", UsageFormat.tokens(850))
        assertEquals("12.3k", UsageFormat.tokens(12_340))
        assertEquals("1.2M", UsageFormat.tokens(1_234_567))
        assertEquals("$32 / $64", RealtimeProtocol.MODELS.first().priceLabel)
        assertEquals("$0.40", UsageFormat.rate(0.4))
    }

    @Test fun parses_error_message_and_ignores_unknown_events() {
        assertEquals(
            RealtimeProtocol.Event.Error("Invalid API key"),
            RealtimeProtocol.parse("""{"type":"error","error":{"message":"Invalid API key"}}"""),
        )
        assertEquals(RealtimeProtocol.Event.Ignored, RealtimeProtocol.parse("""{"type":"session.created"}"""))
        assertTrue(RealtimeProtocol.VOICES.contains(AppSettings.DEFAULT_REALTIME_VOICE))
    }

    @Test fun session_updated_means_ready() {
        assertEquals(RealtimeProtocol.Event.SessionReady, RealtimeProtocol.parse("""{"type":"session.updated","session":{}}"""))
    }

    @Test fun rejected_key_is_fatal_and_other_errors_are_not() {
        // With an invalid key the live API opens the socket, then sends this message (seen on the emulator).
        val rejected = RealtimeProtocol.parse(
            """{"type":"error","error":{"type":"invalid_request_error","code":"invalid_api_key",""" +
                """"message":"Incorrect API key provided: sk-proj-***0000. You can find your API key at https://platform.openai.com/account/api-keys."}}""",
        ) as RealtimeProtocol.Event.Error
        assertTrue(rejected.fatal)
        // An account without API credit: same flow, seen on the emulator with a valid key.
        val noCredit = RealtimeProtocol.parse(
            """{"type":"error","error":{"type":"insufficient_quota","code":"credit_balance_exhausted",""" +
                """"message":"You have no credits remaining. Add credits to continue using the API."}}""",
        ) as RealtimeProtocol.Event.Error
        assertTrue(noCredit.fatal)
        val minor = RealtimeProtocol.parse("""{"type":"error","error":{"code":"invalid_value","message":"Bad value"}}""")
            as RealtimeProtocol.Event.Error
        assertFalse(minor.fatal)
    }

    @Test fun failed_response_carries_its_error() {
        val event = RealtimeProtocol.parse(
            """{"type":"response.done","response":{"status":"failed","status_details":{"type":"failed",""" +
                """"error":{"type":"insufficient_quota","code":"insufficient_quota","message":"You exceeded your current quota"}}}}""",
        )
        assertEquals(RealtimeProtocol.Event.Error("You exceeded your current quota", "insufficient_quota"), event)
        assertTrue((event as RealtimeProtocol.Event.Error).fatal)
        assertEquals(
            RealtimeProtocol.Event.ResponseDone(),
            RealtimeProtocol.parse("""{"type":"response.done","response":{"status":"completed"}}"""),
        )
    }

    @Test fun tools_go_into_the_session_with_auto_choice() {
        val tool = RealtimeProtocol.function(
            "send_to_session", "Types text.",
            params = mapOf("session" to "Which.", "confirmed" to "Yes?"), required = listOf("session"), booleans = setOf("confirmed"),
        )
        val session = Json.parseToJsonElement(RealtimeProtocol.sessionUpdate("marin", "Hi", listOf(tool))).jsonObject["session"]!!.jsonObject
        assertEquals("auto", session["tool_choice"]!!.jsonPrimitive.content)
        val t = session["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", t["type"]!!.jsonPrimitive.content)
        assertEquals("send_to_session", t["name"]!!.jsonPrimitive.content)
        val props = t["parameters"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals("string", props["session"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", props["confirmed"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("session", t["parameters"]!!.jsonObject["required"]!!.jsonArray[0].jsonPrimitive.content)
        // Without tools nothing about them is sent.
        val plain = Json.parseToJsonElement(RealtimeProtocol.sessionUpdate("marin", "Hi")).jsonObject["session"]!!.jsonObject
        assertFalse(plain.containsKey("tools"))
    }

    @Test fun function_call_item_is_parsed_and_answered() {
        assertEquals(
            RealtimeProtocol.Event.FunctionCall("call_1", "read_session", """{"session":"wc-api-1"}"""),
            RealtimeProtocol.parse(
                """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_1",""" +
                    """"name":"read_session","arguments":"{\"session\":\"wc-api-1\"}"}}""",
            ),
        )
        // Other finished items (a spoken message) are not calls.
        assertEquals(
            RealtimeProtocol.Event.Ignored,
            RealtimeProtocol.parse("""{"type":"response.output_item.done","item":{"type":"message"}}"""),
        )
        val out = Json.parseToJsonElement(RealtimeProtocol.functionOutput("call_1", "3 sessions")).jsonObject
        assertEquals("conversation.item.create", out["type"]!!.jsonPrimitive.content)
        val item = out["item"]!!.jsonObject
        assertEquals("function_call_output", item["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", item["call_id"]!!.jsonPrimitive.content)
        assertEquals("3 sessions", item["output"]!!.jsonPrimitive.content)
    }

    @Test fun typed_message_is_a_user_input_text_item() {
        val obj = Json.parseToJsonElement(RealtimeProtocol.userText("Say hello")).jsonObject
        assertEquals("conversation.item.create", obj["type"]!!.jsonPrimitive.content)
        val item = obj["item"]!!.jsonObject
        assertEquals("user", item["role"]!!.jsonPrimitive.content)
        val part = item["content"]!!.jsonArray[0].jsonObject
        assertEquals("input_text", part["type"]!!.jsonPrimitive.content)
        assertEquals("Say hello", part["text"]!!.jsonPrimitive.content)
        assertEquals("""{"type":"response.create"}""", RealtimeProtocol.responseCreate())
    }
}
