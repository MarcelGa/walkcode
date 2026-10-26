package dev.walkcode.app.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Microphone capture and speaker playback at the Realtime API's rate (24 kHz, 16-bit mono PCM).
 * Uses the voice-communication path on both sides so the platform echo canceller can keep the
 * speaker output out of the microphone.
 */
class VoiceAudio {

    private val encoding = AudioFormat.ENCODING_PCM_16BIT
    private var record: AudioRecord? = null
    private var captureJob: Job? = null
    private var track: AudioTrack? = null
    private var playQueue: Channel<ByteArray>? = null
    private var playJob: Job? = null

    /** Streams 50 ms chunks to [onChunk] until [stopCapture]. Needs RECORD_AUDIO granted. */
    @SuppressLint("MissingPermission")
    fun startCapture(scope: CoroutineScope, onChunk: (ByteArray) -> Unit) {
        if (record != null) return
        val minBuf = AudioRecord.getMinBufferSize(RealtimeProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RealtimeProtocol.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(encoding)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 4))
            .build()
        rec.startRecording()
        record = rec
        // The loop owns the recorder and releases it when it ends, never while a read is in flight.
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(CHUNK_BYTES)
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    onChunk(buf.copyOf(n))
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        // Stopping ends a blocked read; the capture loop then releases the recorder.
        record?.let { runCatching { it.stop() } }
        record = null
    }

    /** Opens the speaker track and a queue that feeds it, so network reads never block on audio. */
    fun startPlayback(scope: CoroutineScope) {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(RealtimeProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, encoding)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RealtimeProtocol.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(encoding)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        playQueue = queue
        // The loop owns the track and releases it when it ends, never while a write is in flight.
        playJob = scope.launch(Dispatchers.IO) {
            try {
                for (chunk in queue) t.write(chunk, 0, chunk.size)
            } finally {
                runCatching { t.stop() }
                t.release()
            }
        }
    }

    fun play(pcm: ByteArray) {
        playQueue?.trySend(pcm)
    }

    /** Barge-in: drop queued speech and whatever the track has buffered. */
    fun interruptPlayback() {
        playQueue?.let { q -> while (q.tryReceive().isSuccess) Unit }
        track?.let { t ->
            runCatching {
                t.pause()
                t.flush()
                t.play()
            }
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        playQueue?.close()
        playQueue = null
        // Pausing ends a blocked write at once; the playback loop then releases the track.
        track?.let { t -> runCatching { t.pause(); t.flush() } }
        track = null
    }

    companion object {
        /** 50 ms of 24 kHz 16-bit mono audio. */
        private const val CHUNK_BYTES = RealtimeProtocol.SAMPLE_RATE / 20 * 2
    }
}
