package dev.walkcode.app.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * Wire format of the OpenAI Realtime API (GA shape): the messages this app sends and the
 * events it reads. Pure functions, so the JSON can be unit tested without a network.
 * See docs/DECISIONS.md ("Voice orchestrator PoC").
 */
object RealtimeProtocol {
    /** A Realtime model offered in Settings, with its list prices (for the usage estimate). */
    data class Model(val id: String, val label: String, val prices: Prices) {
        /** The audio rate per 1M tokens, in / out: what most of a spoken conversation costs. */
        val priceLabel: String get() = "${UsageFormat.rate(prices.audioIn)} / ${UsageFormat.rate(prices.audioOut)}"
    }

    // OpenAI's pricing page, 2026-10-26.
    private val FULL_PRICES = Prices(textIn = 4.0, textOut = 24.0, audioIn = 32.0, audioOut = 64.0, cachedTextIn = 0.40, cachedAudioIn = 0.40)
    private val MINI_PRICES = Prices(textIn = 0.60, textOut = 2.40, audioIn = 10.0, audioOut = 20.0, cachedTextIn = 0.06, cachedAudioIn = 0.30)

    /**
     * The mini model costs about a third on audio (far less on text) but is weaker at calling
     * functions. Automated tests use [CHEAPEST_MODEL] so they cost little.
     */
    val MODELS = listOf(
        Model("gpt-realtime-2", "GPT Realtime 2", FULL_PRICES),
        Model("gpt-realtime-2.1", "GPT Realtime 2.1", FULL_PRICES),
        Model("gpt-realtime-2.1-mini", "GPT Realtime 2.1 mini", MINI_PRICES),
    )
    const val DEFAULT_MODEL = "gpt-realtime-2"
    const val CHEAPEST_MODEL = "gpt-realtime-2.1-mini"

    /** Turns what the user said into the transcript on screen; billed apart from the conversation. */
    const val TRANSCRIBE_MODEL = "gpt-4o-mini-transcribe"
    val TRANSCRIBE_PRICES = Prices(textIn = 1.25, textOut = 5.0, audioIn = 1.25, audioOut = 0.0, cachedTextIn = 1.25, cachedAudioIn = 1.25)

    /** Prices for [id]; a model this build does not list is estimated at the full rate, never too low. */
    fun pricesFor(id: String): Prices = when (id) {
        TRANSCRIBE_MODEL -> TRANSCRIBE_PRICES
        else -> MODELS.firstOrNull { it.id == id }?.prices ?: FULL_PRICES
    }
    const val SAMPLE_RATE = 24000
    const val VAD_THRESHOLD = 0.65
    /** A little over the 500 ms default, so a pause mid-sentence does not split it into two paid replies. */
    const val VAD_SILENCE_MS = 700

    /** The label for [id]; an id this build does not list is shown as is. */
    fun modelLabel(id: String): String = MODELS.firstOrNull { it.id == id }?.label ?: id

    /**
     * Every Realtime preset voice, the two newest (recommended by OpenAI) first. No cloning, so a
     * workspace's voice is one of these. All of them also exist on the speech endpoint ([VoicePreview]).
     */
    val VOICES = listOf("marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse")

    /** "marin" -> "Marin", for lists and for the preview's spoken name. */
    fun voiceLabel(voice: String): String = voice.replaceFirstChar { it.uppercase() }

    /** Error codes after which the session cannot do anything useful, so it is ended. */
    private val FATAL_CODES = setOf("invalid_api_key", "insufficient_quota", "credit_balance_exhausted", "model_not_found")

    /**
     * Configures the session once the socket is open: model, voice, instructions, PCM in and out,
     * server VAD, and the functions the model may call ([tools], built by [function]).
     */
    fun sessionUpdate(
        voice: String,
        instructions: String,
        tools: List<JsonObject> = emptyList(),
        model: String = DEFAULT_MODEL,
    ): String = buildJsonObject {
        put("type", "session.update")
        putJsonObject("session") {
            put("type", "realtime")
            put("model", model)
            putJsonArray("output_modalities") { add(JsonPrimitive("audio")) }
            put("instructions", instructions)
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools))
                put("tool_choice", "auto")
            }
            putJsonObject("audio") {
                putJsonObject("input") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", SAMPLE_RATE)
                    }
                    // Wind, traffic and voices nearby start false turns outdoors, and each one bills
                    // its audio plus a whole reply. Filtering before the VAD and a stricter
                    // threshold (default 0.5) keep those out; silence is free either way.
                    putJsonObject("noise_reduction") { put("type", "near_field") }
                    putJsonObject("turn_detection") {
                        put("type", "server_vad")
                        put("threshold", VAD_THRESHOLD)
                        put("silence_duration_ms", VAD_SILENCE_MS)
                    }
                    // Transcript of what the user said, so the screen can show both sides.
                    putJsonObject("transcription") { put("model", TRANSCRIBE_MODEL) }
                }
                putJsonObject("output") {
                    putJsonObject("format") {
                        put("type", "audio/pcm")
                        put("rate", SAMPLE_RATE)
                    }
                    put("voice", voice)
                }
            }
        }
    }.toString()

    /** One chunk of microphone audio: 16-bit little-endian mono PCM at [SAMPLE_RATE], base64 in JSON. */
    fun appendAudio(pcm: ByteArray): String = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", Base64.getEncoder().encodeToString(pcm))
    }.toString()

    /**
     * A function the model may call. [params] maps each parameter name to its description; all are
     * strings except the names in [booleans]. Names in [required] must be present.
     */
    fun function(
        name: String,
        description: String,
        params: Map<String, String> = emptyMap(),
        required: List<String> = emptyList(),
        booleans: Set<String> = emptySet(),
    ): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", name)
        put("description", description)
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                for ((p, d) in params) putJsonObject(p) {
                    put("type", if (p in booleans) "boolean" else "string")
                    put("description", d)
                }
            }
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
    }

    /** The result of a function call; follow the results of one response with [responseCreate]. */
    fun functionOutput(callId: String, output: String): String = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", output)
        }
    }.toString()

    /** A typed user message; follow it with [responseCreate] to get a spoken answer. */
    fun userText(text: String): String = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "message")
            put("role", "user")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
            }
        }
    }.toString()

    fun responseCreate(): String = buildJsonObject { put("type", "response.create") }.toString()

    sealed interface Event {
        /** The server applied our session.update; audio sent from now on uses our format. */
        data object SessionReady : Event
        data class AudioDelta(val pcm: ByteArray) : Event
        data class AssistantText(val delta: String) : Event
        /** [usage] is what transcribing it cost ([TRANSCRIBE_MODEL]), when the server says. */
        data class UserText(val text: String, val usage: Usage? = null) : Event
        /** The user started speaking: any reply still playing should stop (barge-in). */
        data object SpeechStarted : Event
        /** [usage] is what this response cost: the whole conversation as input, plus its output. */
        data class ResponseDone(val usage: Usage? = null) : Event
        /** The model wants [name] run with [arguments] (a JSON object as text); answer with [functionOutput]. */
        data class FunctionCall(val callId: String, val name: String, val arguments: String) : Event
        data class Error(val message: String, val code: String? = null) : Event {
            val fatal: Boolean get() = code in FATAL_CODES || message.startsWith("Incorrect API key")
        }
        data object Ignored : Event
    }

    fun parse(text: String): Event {
        val obj = Json.parseToJsonElement(text).jsonObject
        fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.content
        fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
        return when (obj.str("type")) {
            "session.updated" -> Event.SessionReady
            // "response.audio.*" is the beta name of the same events.
            "response.output_audio.delta", "response.audio.delta" ->
                Event.AudioDelta(Base64.getDecoder().decode(obj.str("delta").orEmpty()))
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" ->
                Event.AssistantText(obj.str("delta").orEmpty())
            "conversation.item.input_audio_transcription.completed" ->
                Event.UserText(obj.str("transcript").orEmpty(), obj.obj("usage")?.let(::transcriptionUsage))
            "input_audio_buffer.speech_started" -> Event.SpeechStarted
            // The finished output item carries the whole call (name, id and arguments) in one place.
            "response.output_item.done" -> {
                val item = obj.obj("item")
                val callId = item?.str("call_id")
                val name = item?.str("name")
                if (item?.str("type") == "function_call" && callId != null && name != null) {
                    Event.FunctionCall(callId, name, item.str("arguments") ?: "{}")
                } else {
                    Event.Ignored
                }
            }
            "response.done" -> {
                // A failed response (e.g. no quota) arrives here, not as an "error" event.
                val response = obj.obj("response")
                if (response?.str("status") == "failed") {
                    val error = response.obj("status_details")?.obj("error")
                    Event.Error(error?.str("message") ?: "The response failed", error?.str("code"))
                } else {
                    Event.ResponseDone(response?.obj("usage")?.let(::responseUsage))
                }
            }
            "error" -> {
                val error = obj.obj("error")
                Event.Error(error?.str("message") ?: text, error?.str("code"))
            }
            else -> Event.Ignored
        }
    }

    private fun JsonObject.long(name: String): Long = (this[name] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0
    private fun JsonObject.sub(name: String): JsonObject? = this[name] as? JsonObject

    /** `response.usage`: input split by text and audio, each with its cached part; output by text and audio. */
    private fun responseUsage(u: JsonObject): Usage {
        val input = u.sub("input_token_details")
        val cached = input?.sub("cached_tokens_details")
        val output = u.sub("output_token_details")
        return Usage(
            textIn = input?.long("text_tokens") ?: 0,
            audioIn = input?.long("audio_tokens") ?: 0,
            cachedTextIn = cached?.long("text_tokens") ?: 0,
            cachedAudioIn = cached?.long("audio_tokens") ?: 0,
            textOut = output?.long("text_tokens") ?: 0,
            audioOut = output?.long("audio_tokens") ?: 0,
        )
    }

    /**
     * Transcription usage comes as tokens or, for duration-billed models, as seconds; seconds are
     * counted at the Realtime rate for user audio (10 tokens a second) so both add up the same way.
     */
    private fun transcriptionUsage(u: JsonObject): Usage = when ((u["type"] as? JsonPrimitive)?.content) {
        "duration" -> Usage(audioIn = ((u["seconds"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0).times(10).toLong())
        else -> {
            val input = u.sub("input_token_details")
            Usage(
                textIn = input?.long("text_tokens") ?: 0,
                audioIn = input?.long("audio_tokens") ?: u.long("input_tokens"),
                textOut = u.long("output_tokens"),
            )
        }
    }
}
