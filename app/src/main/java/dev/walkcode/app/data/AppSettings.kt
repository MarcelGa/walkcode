package dev.walkcode.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide preferences, kept in SharedPreferences (a handful of scalars; no need for Room). */
class AppSettings private constructor(private val prefs: SharedPreferences) {

    /** Seconds without output before a "quiet" alert (default 15, see docs/DECISIONS.md). */
    private val _quietAfterSec = MutableStateFlow(prefs.getInt(KEY_QUIET_SEC, DEFAULT_QUIET_SEC))
    val quietAfterSec: StateFlow<Int> = _quietAfterSec

    private val _quietAlerts = MutableStateFlow(prefs.getBoolean(KEY_QUIET_ENABLED, true))
    val quietAlerts: StateFlow<Boolean> = _quietAlerts

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

    companion object {
        const val DEFAULT_QUIET_SEC = 15
        const val MIN_QUIET_SEC = 5
        const val MAX_QUIET_SEC = 300
        private const val KEY_QUIET_SEC = "quiet_after_sec"
        private const val KEY_QUIET_ENABLED = "quiet_alerts"
        private const val KEY_BATTERY_PROMPTED = "battery_prompted"

        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext.getSharedPreferences("walkcode_settings", Context.MODE_PRIVATE))
                    .also { instance = it }
            }
    }
}
