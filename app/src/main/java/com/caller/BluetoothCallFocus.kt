package com.caller

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Whether exclusive audio focus should be held: a call is active and its audio is on a Bluetooth route. On this phone the
 * call's Bluetooth link also carries whatever else is playing (a YouTube video reached the PC during a call), so while
 * this holds other apps are kept from playing into it. Apps that respect audio focus pause and cannot start.
 */
internal fun holdsExclusiveCallFocus(callActive: Boolean, routeIsBluetooth: Boolean): Boolean =
    callActive && routeIsBluetooth

/** Holds one exclusive audio focus request, released by the same object that asked for it. */
internal class BluetoothCallFocus(private val audioManager: AudioManager?) {
    private var request: AudioFocusRequest? = null

    /** Asks for or gives up exclusive focus to match [hold]. Safe to call on every call-state change. */
    @Synchronized
    fun sync(hold: Boolean) {
        if (!hold) {
            release()
            return
        }
        val manager = audioManager ?: return
        if (request != null) return
        try {
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { change -> Log.w(TAG, "call_focus change=$change") }
                .build()
            val granted = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.w(TAG, "call_focus action=hold granted=$granted")
            if (granted) request = focus
        } catch (e: RuntimeException) {
            Log.e(TAG, "call_focus action=hold failed: ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        val focus = request ?: return
        request = null
        try {
            audioManager?.abandonAudioFocusRequest(focus)
            Log.w(TAG, "call_focus action=release")
        } catch (e: RuntimeException) {
            Log.e(TAG, "call_focus action=release failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "CallFocus"
    }
}
