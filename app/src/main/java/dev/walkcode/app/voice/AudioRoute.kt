package dev.walkcode.app.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Where the conversation is heard. Voice-communication audio goes to the earpiece unless told
 * otherwise, which is inaudible with the phone in a hand or pocket, so pick a headset when one is
 * connected and the loudspeaker otherwise. Communication mode also suits the echo canceller.
 */
class AudioRoute(context: Context) {

    private val am = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL
    private var active = false

    /** Returns a short label of the chosen output ("speaker", "headset"), or null if none was set. */
    fun start(): String? {
        if (active) return null
        active = true
        previousMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val devices = am.availableCommunicationDevices
        val chosen = PREFERRED.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
            ?: return null
        am.setCommunicationDevice(chosen)
        return if (chosen.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "speaker" else "headset"
    }

    fun stop() {
        if (!active) return
        active = false
        am.clearCommunicationDevice()
        am.mode = previousMode
    }

    private companion object {
        /** Most specific first; the loudspeaker is the fallback when nothing is plugged in or paired. */
        val PREFERRED = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
    }
}
