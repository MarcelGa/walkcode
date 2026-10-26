package dev.walkcode.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.walkcode.app.voice.OpenAiKey
import dev.walkcode.app.voice.RealtimeProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide preferences, kept in SharedPreferences (a handful of scalars; no need for Room). */
class AppSettings private constructor(private val prefs: SharedPreferences) {

    /** Seconds without output before a "quiet" alert (default 15, see docs/DECISIONS.md). */
    private val _quietAfterSec = MutableStateFlow(prefs.getInt(KEY_QUIET_SEC, DEFAULT_QUIET_SEC))
    val quietAfterSec: StateFlow<Int> = _quietAfterSec

    private val _quietAlerts = MutableStateFlow(prefs.getBoolean(KEY_QUIET_ENABLED, true))
    val quietAlerts: StateFlow<Boolean> = _quietAlerts

    /** Walk mode: read agent responses aloud (persisted so it survives leaving the terminal). */
    private val _walkMode = MutableStateFlow(prefs.getBoolean(KEY_WALK_MODE, false))
    val walkMode: StateFlow<Boolean> = _walkMode

    /** Press Enter after a dictation so the agent gets it without a second tap. */
    private val _dictationAutoSend = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SEND, true))
    val dictationAutoSend: StateFlow<Boolean> = _dictationAutoSend

    /** Voice choices; null means "use the phone's default". Packages/ids are as Android reports them. */
    private val _ttsEngine = MutableStateFlow(prefs.getString(KEY_TTS_ENGINE, null))
    val ttsEngine: StateFlow<String?> = _ttsEngine
    private val _ttsVoice = MutableStateFlow(prefs.getString(KEY_TTS_VOICE, null))
    val ttsVoice: StateFlow<String?> = _ttsVoice
    private val _ttsRate = MutableStateFlow(prefs.getFloat(KEY_TTS_RATE, 1f))
    val ttsRate: StateFlow<Float> = _ttsRate
    /** Flattened ComponentName of the recognition service. */
    private val _recognizer = MutableStateFlow(prefs.getString(KEY_RECOGNIZER, null))
    val recognizer: StateFlow<String?> = _recognizer
    /** BCP-47 tag for dictation. */
    private val _recognitionLang = MutableStateFlow(prefs.getString(KEY_RECOGNITION_LANG, null))
    val recognitionLang: StateFlow<String?> = _recognitionLang

    /** OpenAI API key for the voice PoC. Only in this app's private preferences; never in the config backup. */
    // Cleaned on read too: values saved by earlier builds may still hold a raw multi-line paste.
    private val _openAiKey = MutableStateFlow(OpenAiKey.clean(prefs.getString(KEY_OPENAI_KEY, null)))
    val openAiKey: StateFlow<String?> = _openAiKey
    /** Preset OpenAI Realtime voice (no cloning). */
    private val _realtimeVoice = MutableStateFlow(prefs.getString(KEY_REALTIME_VOICE, null) ?: DEFAULT_REALTIME_VOICE)
    val realtimeVoice: StateFlow<String> = _realtimeVoice
    /** Realtime model id; walk mode uses it from its next start. */
    private val _realtimeModel = MutableStateFlow(prefs.getString(KEY_REALTIME_MODEL, null) ?: RealtimeProtocol.DEFAULT_MODEL)
    val realtimeModel: StateFlow<String> = _realtimeModel

    fun setOpenAiKey(v: String?) {
        val clean = OpenAiKey.clean(v)
        put(KEY_OPENAI_KEY, clean)
        _openAiKey.value = clean
    }
    fun setRealtimeVoice(v: String) { put(KEY_REALTIME_VOICE, v); _realtimeVoice.value = v }
    fun setRealtimeModel(v: String) { put(KEY_REALTIME_MODEL, v); _realtimeModel.value = v }

    /** Changing the engine invalidates the voice, which belongs to one engine. */
    fun setTtsEngine(v: String?) { put(KEY_TTS_ENGINE, v); _ttsEngine.value = v; setTtsVoice(null) }
    fun setTtsVoice(v: String?) { put(KEY_TTS_VOICE, v); _ttsVoice.value = v }
    fun setTtsRate(v: Float) { prefs.edit().putFloat(KEY_TTS_RATE, v).apply(); _ttsRate.value = v }
    fun setRecognizer(v: String?) { put(KEY_RECOGNIZER, v); _recognizer.value = v }
    fun setRecognitionLang(v: String?) { put(KEY_RECOGNITION_LANG, v); _recognitionLang.value = v }

    private fun put(key: String, v: String?) = prefs.edit().apply { if (v == null) remove(key) else putString(key, v) }.apply()

    /** True once the battery-optimisation prompt has been shown, so it is not repeated. */
    var batteryPromptShown: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPTED, false)
        set(v) { prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, v).apply() }

    fun setQuietAfterSec(sec: Int) {
        val v = sec.coerceIn(MIN_QUIET_SEC, MAX_QUIET_SEC)
        prefs.edit().putInt(KEY_QUIET_SEC, v).apply()
        _quietAfterSec.value = v
    }

    fun setQuietAlerts(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUIET_ENABLED, enabled).apply()
        _quietAlerts.value = enabled
    }

    fun setWalkMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WALK_MODE, enabled).apply()
        _walkMode.value = enabled
    }

    fun setDictationAutoSend(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SEND, enabled).apply()
        _dictationAutoSend.value = enabled
    }

    companion object {
        const val DEFAULT_QUIET_SEC = 15
        const val MIN_QUIET_SEC = 5
        const val MAX_QUIET_SEC = 300
        private const val KEY_QUIET_SEC = "quiet_after_sec"
        private const val KEY_QUIET_ENABLED = "quiet_alerts"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_TTS_VOICE = "tts_voice"
        private const val KEY_TTS_RATE = "tts_rate"
        private const val KEY_RECOGNIZER = "recognizer"
        private const val KEY_RECOGNITION_LANG = "recognition_lang"
        private const val KEY_WALK_MODE = "walk_mode"
        private const val KEY_AUTO_SEND = "dictation_auto_send"
        private const val KEY_BATTERY_PROMPTED = "battery_prompted"
        private const val KEY_OPENAI_KEY = "openai_key"
        private const val KEY_REALTIME_VOICE = "realtime_voice"
        private const val KEY_REALTIME_MODEL = "realtime_model"
        const val DEFAULT_REALTIME_VOICE = "marin"

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext.getSharedPreferences("walkcode_settings", Context.MODE_PRIVATE))
                    .also { instance = it }
            }
    }
}
