package dev.walkcode.app.voice

import android.util.Log
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * One WebSocket to the OpenAI Realtime API. Callbacks arrive on OkHttp's reader thread;
 * the owner has to hop to its own thread before touching UI state.
 */
class RealtimeClient(private val listener: Listener) {

    interface Listener {
        fun onOpen()
        fun onEvent(event: RealtimeProtocol.Event)
        fun onClosed(reason: String)
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null

    fun connect(apiKey: String, model: String, voice: String, instructions: String, tools: List<JsonObject> = emptyList()) {
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$model")
            .header("Authorization", "Bearer $apiKey")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(RealtimeProtocol.sessionUpdate(voice, instructions, tools, model))
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Event type only, never the content: transcripts and audio stay out of logcat.
                Log.d(TAG, "event ${EVENT_TYPE.find(text)?.groupValues?.get(1) ?: "?"}")
                val event = runCatching { RealtimeProtocol.parse(text) }
                    .getOrDefault(RealtimeProtocol.Event.Ignored)
                listener.onEvent(event)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "failure ${t.javaClass.simpleName}: ${t.message} http=${response?.code}")
                val detail = response?.let { "HTTP ${it.code}" } ?: t.message ?: "connection failed"
                listener.onClosed(detail)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(reason.ifBlank { "closed ($code)" })
            }
        })
    }

    /** Dropped silently while the socket is not open; the capture loop keeps running. */
    fun sendAudio(pcm: ByteArray) {
        socket?.send(RealtimeProtocol.appendAudio(pcm))
    }

    /** Answers one function call; after the last answer of a response, ask for the next with [requestResponse]. */
    fun sendFunctionOutput(callId: String, output: String) {
        socket?.send(RealtimeProtocol.functionOutput(callId, output))
    }

    fun requestResponse() {
        socket?.send(RealtimeProtocol.responseCreate())
    }

    /** A typed message, then a request for the model to answer it. */
    fun sendText(text: String) {
        socket?.send(RealtimeProtocol.userText(text))
        socket?.send(RealtimeProtocol.responseCreate())
    }

    fun close() {
        socket?.close(1000, "done")
        socket = null
    }

    private companion object {
        const val TAG = "WalkcodeVoice"
        val EVENT_TYPE = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"")
    }
}
