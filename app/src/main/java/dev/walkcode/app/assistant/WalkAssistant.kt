package dev.walkcode.app.assistant

import android.content.Context
import dev.walkcode.app.data.AppSettings
import dev.walkcode.app.data.WalkcodeRepository
import dev.walkcode.app.monitor.Monitor
import dev.walkcode.app.session.SessionService
import dev.walkcode.app.voice.AudioRoute
import dev.walkcode.app.voice.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Walk mode: one voice conversation for the whole app, with [WorkspaceTools] over all workspaces.
 * It outlives screens (switched on and off on the Projects screen) and, through [WalkService],
 * keeps listening while the phone is locked.
 */
class WalkAssistant private constructor(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = AppSettings.get(app)
    private val repo = WalkcodeRepository.get(app)

    val session = VoiceSession(scope, AudioRoute(app), tools = WorkspaceTools(repo, SessionService(repo), Monitor.get(app)))

    /** On while connecting or live; the home toggle shows this. */
    val on: StateFlow<Boolean> = session.state
        .map { it == VoiceSession.State.Connecting || it == VoiceSession.State.Live }
        .stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // The service only exists while the conversation does, including when it ends by itself (an error).
        scope.launch { on.collect { if (it) WalkService.start(app) else WalkService.stop(app) } }
    }

    /** False without a key. The caller must hold RECORD_AUDIO and be in the foreground (microphone service rules). */
    fun start(): Boolean {
        val key = settings.openAiKey.value ?: return false
        session.start(key, settings.realtimeVoice.value)
        return true
    }

    fun stop() = session.stop()

    companion object {
        @Volatile private var instance: WalkAssistant? = null

        fun get(context: Context): WalkAssistant =
            instance ?: synchronized(this) { instance ?: WalkAssistant(context).also { instance = it } }
    }
}
