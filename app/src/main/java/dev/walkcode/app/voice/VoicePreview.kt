package dev.walkcode.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Speaks a short sample in a Realtime preset voice so the user can choose one by ear. Uses the
 * speech endpoint instead of a Realtime session: the same preset voices, one HTTP request, no
 * microphone, and a sample costs a fraction of a cent. Samples are kept in memory and, with [dir],
 * as files that survive leaving the screen and restarting the app, so a sample is paid for once.
 */
class VoicePreview(private val scope: CoroutineScope, private val dir: File? = null) {

    sealed interface State {
        data object Idle : State
        data class Loading(val voice: String) : State
        data class Playing(val voice: String) : State
        data class Failed(val voice: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val cache = HashMap<String, ByteArray>()
    private var job: Job? = null

    /** Stops whatever sample is playing, then plays [text] in [voice]. */
    fun play(apiKey: String, voice: String, text: String) {
        stop()
        job = scope.launch {
            val cacheKey = "$voice\n$text"
            val file = dir?.let { File(it, fileName(voice, text)) }
            val pcm = cache[cacheKey] ?: withContext(Dispatchers.IO) { file?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() } } ?: run {
                _state.value = State.Loading(voice)
                val result = withContext(Dispatchers.IO) { runCatching { fetch(apiKey, voice, text) } }
                val fetched = result.getOrElse {
                    Log.w(TAG, "preview $voice failed: ${it.javaClass.simpleName}")
                    _state.value = State.Failed(voice, it.message ?: "Could not load the sample")
                    return@launch
                }
                if (file != null) withContext(Dispatchers.IO) { save(file, fetched) }
                fetched
            }
            cache[cacheKey] = pcm
            _state.value = State.Playing(voice)
            withContext(Dispatchers.IO) { playPcm(pcm) }
            if (isActive) _state.value = State.Idle
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = State.Idle
    }

    /** Through a temporary file, so a write cut short (app killed) never leaves a truncated sample to replay. */
    private fun save(file: File, pcm: ByteArray) {
        if (pcm.isEmpty()) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(pcm)
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { Log.w(TAG, "preview cache write failed: ${it.javaClass.simpleName}") }
    }

    private fun fetch(apiKey: String, voice: String, text: String): ByteArray {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .post(speechRequest(voice, text).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            Log.d(TAG, "preview $voice http=${response.code}")
            val body = response.body
            if (!response.isSuccessful) throw IllegalStateException(errorMessage(response.code, body?.string()))
            return body?.bytes() ?: ByteArray(0)
        }
    }

    /** Media usage, not voice communication: a sample should come out of the speaker like any other sound. */
    private suspend fun playPcm(pcm: ByteArray) {
        // Whole 16-bit frames only; a static track rejects a buffer that ends mid-frame.
        val size = pcm.size - pcm.size % 2
        if (size == 0) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RealtimeProtocol.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, size)
            track.play()
            val frames = size / 2
            // A static track has no completion callback that survives cancellation cleanly; poll the head.
            while (track.playbackHeadPosition < frames && track.playState == AudioTrack.PLAYSTATE_PLAYING) delay(50)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    companion object {
        private const val TAG = "WalkcodeVoice"
        const val MODEL = "gpt-4o-mini-tts"

        /**
         * One file per model, voice and text: a workspace's assistant name changes the text, so it gets
         * its own sample, and a new [MODEL] never replays audio made by the old one.
         */
        fun fileName(voice: String, text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$MODEL\n$voice\n$text".toByteArray())
            return "$voice-" + digest.take(8).joinToString("") { "%02x".format(it) } + ".pcm"
        }

        /** What the sample says; [name] is the assistant's name, or the voice's own when it has none. */
        fun phrase(name: String): String = "Hey, this is voice agent $name."

        /** Raw PCM back, so it plays at the same 24 kHz 16-bit mono as the Realtime session. */
        fun speechRequest(voice: String, text: String): String = buildJsonObject {
            put("model", MODEL)
            put("voice", voice)
            put("input", text)
            put("response_format", "pcm")
        }.toString()

        /** The API's own message when the body has one ("You exceeded your current quota…"). */
        fun errorMessage(code: Int, body: String?): String {
            val message = body?.let {
                runCatching {
                    val error = Json.parseToJsonElement(it) as? JsonObject
                    ((error?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.content
                }.getOrNull()
            }
            return when {
                !message.isNullOrBlank() -> message
                code == 401 -> "The OpenAI API key was rejected"
                else -> "HTTP $code"
            }
        }
    }
}
