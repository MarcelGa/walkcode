package dev.walkcode.app.walk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.walkcode.app.data.AppSettings

/**
 * One-shot speech-to-text on the main thread. Prefers the on-device recognizer so dictation
 * works without a network round trip, and falls back to the system's default service.
 */
class Dictation(
    private val context: Context,
    private val settings: AppSettings,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    val listening: Boolean get() = recognizer != null

    fun available(): Boolean =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context) || SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (recognizer != null) return
        val chosen = settings.recognizer.value?.let(ComponentName::unflattenFromString)
        val r = if (chosen != null) SpeechRecognizer.createSpeechRecognizer(context, chosen)
        else if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context)
        else { onFailure("No speech recognizer on this device"); return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) {
                first(partialResults)?.let(onPartial)
            }

            override fun onResults(results: Bundle) {
                release()
                first(results)?.takeIf { it.isNotBlank() }?.let(onResult) ?: onFailure("Nothing heard")
            }

            override fun onError(error: Int) {
                release()
                // "No match" and "speech timeout" are just silence, not a fault worth a scary message.
                onFailure(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nothing heard"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection"
                    else -> "Speech recognition failed ($error)"
                })
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            settings.recognitionLang.value?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        })
    }

    /** Ends capture early; the recognizer still delivers what it heard via [onResult]. */
    fun stop() { recognizer?.stopListening() }

    fun cancel() {
        recognizer?.cancel()
        release()
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun first(b: Bundle): String? =
        b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
