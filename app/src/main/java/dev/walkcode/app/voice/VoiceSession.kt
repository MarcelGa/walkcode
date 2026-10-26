package dev.walkcode.app.voice

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Functions the voice model may call, with the instructions that explain them to it. */
interface ToolHost {
    val instructions: String
    val definitions: List<JsonObject>
    /** A short note for the transcript while [name] runs, e.g. "Reading the screens". */
    fun label(name: String, arguments: String): String
    /** Runs [name]; the text goes back to the model. A throw is reported to the model as a failure. */
    suspend fun call(name: String, arguments: String): String
}

/**
 * One live voice conversation: socket, microphone, speaker, the transcript shown on screen and,
 * with [tools], the function calls the model makes (answered once its response is done).
 */
class VoiceSession(
    private val scope: CoroutineScope,
    private val route: AudioRoute? = null,
    private val audio: VoiceAudio = VoiceAudio(),
    private val tools: ToolHost? = null,
    /** Every response and transcription the server reports, with its estimated price, for the usage totals. */
    private val onUsage: (model: String, usage: Usage, usd: Double) -> Unit = { _, _, _ -> },
) : RealtimeClient.Listener {

    enum class State { Idle, Connecting, Live, Error }

    /** Tokens and estimated dollars of the current (or last) conversation. */
    data class Spend(val tokens: Long = 0, val usd: Double = 0.0)

    /** [tool] marks a note that a function ran, shown apart from what was said. */
    data class Turn(val fromUser: Boolean, val text: String, val tool: Boolean = false)

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state

    /** The last thing that went wrong: why the session ended, or a server error while live. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem

    /** Where the reply is heard ("speaker", "headset") while live. */
    private val _output = MutableStateFlow<String?>(null)
    val output: StateFlow<String?> = _output

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns

    private val _spend = MutableStateFlow(Spend())
    val spend: StateFlow<Spend> = _spend

    private var client: RealtimeClient? = null
    private var model = RealtimeProtocol.DEFAULT_MODEL
    private var assistantOpen = false
    /** Calls of the response in progress; their results are sent together when it is done. */
    private val pendingCalls = mutableListOf<Pair<String, Deferred<String>>>()

    /** Caller must have the RECORD_AUDIO permission. */
    fun start(apiKey: String, model: String, voice: String) {
        if (client != null) return
        Log.i(TAG, "start: key ${apiKey.length} chars, model $model, voice $voice")
        _state.value = State.Connecting
        _problem.value = null
        _spend.value = Spend()
        this.model = model
        val c = RealtimeClient(this)
        client = c
        // A bad key or URL throws while the request is built; show that instead of crashing the app.
        runCatching { c.connect(apiKey, model, voice,tools?.instructions ?: INSTRUCTIONS, tools?.definitions.orEmpty()) }
            .onFailure {
                Log.w(TAG, "connect threw ${it.javaClass.simpleName}")
                fail(it.message ?: "could not start the connection")
            }
    }

    /** A typed message instead of speech; answered out loud like a spoken one. */
    fun send(text: String) {
        val c = client ?: return
        if (_state.value != State.Live || text.isBlank()) return
        assistantOpen = false
        _turns.value = _turns.value + Turn(fromUser = true, text = text.trim())
        c.sendText(text.trim())
    }

    fun stop() {
        val c = client ?: return
        client = null
        c.close()
        release()
        _state.value = State.Idle
    }

    // Listener callbacks arrive on OkHttp's thread; state is only touched on the main thread,
    // and nothing from a session that was already stopped or failed is applied.

    override fun onOpen() {
        Log.i(TAG, "socket open")
    }

    override fun onEvent(event: RealtimeProtocol.Event) {
        scope.launch(Dispatchers.Main.immediate) {
            if (client == null) return@launch
            when (event) {
                // Live only once the server accepted our session settings; a rejected key never gets here.
                RealtimeProtocol.Event.SessionReady -> if (_state.value == State.Connecting) {
                    // A device without a usable mic or speaker path throws here; end with the reason, not a crash.
                    runCatching {
                        _output.value = route?.start()
                        audio.startPlayback(scope)
                        audio.startCapture(scope) { client?.sendAudio(it) }
                    }.onFailure {
                        Log.w(TAG, "audio start threw ${it.javaClass.simpleName}")
                        fail("Audio could not start: ${it.message ?: it.javaClass.simpleName}")
                        return@launch
                    }
                    _state.value = State.Live
                }
                is RealtimeProtocol.Event.AudioDelta -> audio.play(event.pcm)
                is RealtimeProtocol.Event.AssistantText -> appendAssistant(event.delta)
                is RealtimeProtocol.Event.UserText -> {
                    event.usage?.let { record(RealtimeProtocol.TRANSCRIBE_MODEL, it) }
                    assistantOpen = false
                    if (event.text.isNotBlank()) _turns.value = _turns.value + Turn(fromUser = true, text = event.text.trim())
                }
                // Barge-in: the user started talking, so stop whatever reply is still playing.
                RealtimeProtocol.Event.SpeechStarted -> {
                    audio.interruptPlayback()
                    assistantOpen = false
                }
                // Started at once so slow SSH work overlaps the rest of the response.
                is RealtimeProtocol.Event.FunctionCall -> {
                    val host = tools
                    Log.i(TAG, "tool ${event.name}")
                    assistantOpen = false
                    _turns.value = _turns.value + Turn(fromUser = false, text = host?.label(event.name, event.arguments) ?: event.name, tool = true)
                    pendingCalls += event.callId to scope.async {
                        if (host == null) "No functions are available."
                        else runCatching { host.call(event.name, event.arguments) }
                            .getOrElse { "Failed: ${it.message ?: it.javaClass.simpleName}" }
                    }
                }
                // The model waits for function results; send them all, then ask it to go on.
                is RealtimeProtocol.Event.ResponseDone -> {
                    event.usage?.let { record(model, it) }
                    assistantOpen = false
                    if (pendingCalls.isNotEmpty()) {
                        val calls = pendingCalls.toList()
                        pendingCalls.clear()
                        val c = client
                        scope.launch {
                            val outputs = calls.map { (id, result) -> id to result.await() }
                            if (c == null || client !== c) return@launch   // the session ended meanwhile
                            for ((id, out) in outputs) c.sendFunctionOutput(id, out)
                            c.requestResponse()
                        }
                    }
                }
                // Before the session is ready any error means it cannot be used (bad key, bad settings).
                is RealtimeProtocol.Event.Error -> {
                    Log.w(TAG, "server error code=${event.code} fatal=${event.fatal}")
                    if (event.fatal || _state.value == State.Connecting) fail(event.message) else _problem.value = event.message
                }
                RealtimeProtocol.Event.Ignored -> Unit
            }
        }
    }

    override fun onClosed(reason: String) {
        Log.i(TAG, "closed: $reason")
        scope.launch(Dispatchers.Main.immediate) {
            // stop() and fail() clear client first, so a close we asked for lands here with nothing to report.
            if (client == null) return@launch
            fail(reason)
        }
    }

    private fun fail(reason: String) {
        val c = client
        client = null
        c?.close()
        release()
        _problem.value = reason
        _state.value = State.Error
    }

    private fun release() {
        audio.stopCapture()
        audio.stopPlayback()
        route?.stop()
        _output.value = null
        assistantOpen = false
        pendingCalls.forEach { it.second.cancel() }
        pendingCalls.clear()
    }

    private fun record(model: String, usage: Usage) {
        val usd = RealtimeProtocol.pricesFor(model).cost(usage)
        // Counts only, never content: the log shows what a turn cost without what was said.
        Log.i(TAG, "usage $model tokens=${usage.total} cached=${usage.cachedIn} audioOut=${usage.audioOut} usd=${UsageFormat.usd(usd)}")
        _spend.value = _spend.value.let { Spend(it.tokens + usage.total, it.usd + usd) }
        onUsage(model, usage, usd)
    }

    private fun appendAssistant(delta: String) {
        val list = _turns.value
        if (assistantOpen && list.isNotEmpty()) {
            _turns.value = list.dropLast(1) + Turn(fromUser = false, text = list.last().text + delta)
        } else {
            assistantOpen = true
            _turns.value = list + Turn(fromUser = false, text = delta)
        }
    }

    companion object {
        private const val TAG = "WalkcodeVoice"
        private const val INSTRUCTIONS =
            "You are the voice of one Walkcode workspace: a developer's coding-agent workspace on their own machines. " +
                "Talk like a calm, competent colleague. Keep replies to one or two short sentences unless asked for detail. " +
                "You cannot run commands or read files yet; say so plainly if asked to do something you cannot do."
    }
}
