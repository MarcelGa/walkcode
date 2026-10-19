package dev.walkcode.app.walk

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import dev.walkcode.app.data.AppSettings

/**
 * Reads agent output aloud with the platform TTS engine (no Play Services dependency).
 * Engine, voice and rate come from [settings] and are re-read on every [speak], so a change
 * in Settings applies to the next response; only an engine change rebuilds the engine.
 */
class Speaker(private val context: Context, private val settings: AppSettings) {

    private class Item(val text: String, val voice: String?, val flush: Boolean)

    private var tts: TextToSpeech? = null
    private var engine: String? = null
    @Volatile private var ready = false
    private val pending = ArrayDeque<Item>()

    fun speak(text: String) {
        val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        submit(ResponseExtractor.chunks(text, max).map { Item(it, null, false) })
    }

    /** Plays [text] right now with [voice] (a voice name, null = the selected one) without changing any setting. */
    fun preview(text: String, voice: String?) = submit(listOf(Item(text, voice, true)), replace = true)

    fun stop() {
        synchronized(this) { pending.clear() }
        tts?.stop()
    }

    fun shutdown() {
        synchronized(this) { pending.clear(); release() }
    }

    private fun submit(items: List<Item>, replace: Boolean = false) {
        synchronized(this) {
            ensureEngine()
            if (replace) pending.clear()
            for (i in items) if (ready) play(i) else pending.addLast(i)
        }
    }

    private fun ensureEngine() {
        val wanted = settings.ttsEngine.value
        if (tts != null && wanted == engine) return
        release()
        engine = wanted
        val listener = TextToSpeech.OnInitListener { status ->
            synchronized(this) {
                ready = status == TextToSpeech.SUCCESS
                if (!ready) Log.w(TAG, "TTS init failed: $status")
                // Text that arrived before the engine was up is read now rather than dropped.
                while (ready && pending.isNotEmpty()) play(pending.removeFirst())
            }
        }
        tts = if (wanted == null) TextToSpeech(context.applicationContext, listener)
        else TextToSpeech(context.applicationContext, listener, wanted)
    }

    private fun release() {
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun play(item: Item) {
        val t = tts ?: return
        t.setSpeechRate(settings.ttsRate.value)
        val name = item.voice ?: settings.ttsVoice.value
        // A voice that vanished (engine updated) just leaves the engine default in place.
        if (name != null) t.voices?.firstOrNull { it.name == name }?.let(t::setVoice)
        t.speak(item.text, if (item.flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "walk-${System.nanoTime()}")
    }

    private companion object {
        const val TAG = "WalkcodeWalk"
    }
}
