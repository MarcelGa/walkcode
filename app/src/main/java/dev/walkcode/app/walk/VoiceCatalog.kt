package dev.walkcode.app.walk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** What the user can pick from in Settings, as installed on this phone. */
object VoiceCatalog {

    class Choice(val id: String, val label: String)

    class TtsInfo(val engines: List<Choice>, val voices: List<Voice>)

    /** Installed text-to-speech engines and the voices of [engine] (null: the phone's default). */
    suspend fun tts(context: Context, engine: String?): TtsInfo = suspendCancellableCoroutine { cont ->
        var tts: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            val t = tts
            val info = if (status == TextToSpeech.SUCCESS && t != null) {
                TtsInfo(
                    t.engines.map { Choice(it.name, it.label) },
                    runCatching { t.voices.orEmpty().toList() }.getOrDefault(emptyList()),
                )
            } else TtsInfo(emptyList(), emptyList())
            t?.shutdown()
            if (cont.isActive) cont.resume(info)
        }
        tts = if (engine == null) TextToSpeech(context.applicationContext, listener)
        else TextToSpeech(context.applicationContext, listener, engine)
        cont.invokeOnCancellation { tts?.shutdown() }
    }

    /** Installed speech recognition services; ids are flattened component names. */
    fun recognizers(context: Context): List<Choice> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map {
            val cn = ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)
            Choice(cn.flattenToString(), it.serviceInfo.applicationInfo.loadLabel(pm).toString())
        }.distinctBy { it.id }
    }

    /** Languages offered for dictation: the ones the TTS voices cover, which is what the user has set up. */
    fun languages(voices: List<Voice>): List<Choice> =
        voices.map { it.locale }.distinct()
            .map { Choice(it.toLanguageTag(), it.getDisplayName(Locale.getDefault())) }
            .sortedBy { it.label }

    fun voiceLabel(v: Voice): String =
        v.locale.getDisplayName(Locale.getDefault()) + " · " + v.name

    /**
     * Every whitespace-separated term of [query] must occur (case-insensitive) in the label or
     * id, in any order: "english gb female" finds "English (United Kingdom) · en-gb-x-female-1".
     */
    fun filter(options: List<Choice>, query: String): List<Choice> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return options
        return options.filter { o ->
            val hay = (o.label + " " + o.id).lowercase()
            terms.all { it in hay }
        }
    }

    private val samples = mapOf(
        "en" to "This is how responses will sound while you walk.",
        "de" to "So klingen die Antworten, während du unterwegs bist.",
        "fr" to "Voici comment les réponses sonneront pendant votre marche.",
        "es" to "Así sonarán las respuestas mientras caminas.",
        "it" to "Ecco come suoneranno le risposte mentre cammini.",
        "pt" to "É assim que as respostas vão soar enquanto você caminha.",
        "nl" to "Zo klinken de antwoorden terwijl je wandelt.",
        "pl" to "Tak będą brzmiały odpowiedzi podczas spaceru.",
        "cs" to "Takto budou znít odpovědi, když jdete na procházku.",
        "sk" to "Takto budú znieť odpovede, keď idete na prechádzku.",
        "hr" to "Ovako će zvučati odgovori dok šećeš.",
        "sv" to "Så här kommer svaren att låta när du går.",
    )

    /** A sentence in the voice's own language, since an English line in a German voice says little about it. */
    fun sampleFor(v: Voice): String = samples[v.locale.language] ?: samples.getValue("en")
}
