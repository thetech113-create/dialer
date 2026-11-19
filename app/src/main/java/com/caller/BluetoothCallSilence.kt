package com.caller

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.util.Log

/**
 * The sound levels a call silences while it is on Bluetooth: media, notifications and system sounds. Alarms are not
 * silenced. On a phone Android ties the system volume to the ringer (AudioService aliases STREAM_SYSTEM to STREAM_RING,
 * and a zero there switches the ringer to vibrate), so the silence steps aside whenever a call rings: see
 * [bluetoothSilenceApplies].
 */
internal val CALL_SILENCED_STREAMS: List<Int> = listOf(
    AudioManager.STREAM_MUSIC,
    AudioManager.STREAM_NOTIFICATION,
    AudioManager.STREAM_SYSTEM,
)

/**
 * Whether the Bluetooth-call silence applies now. Never while a call is ringing: a second call arriving during a
 * Bluetooth call must ring, and the silence would keep the ringer at zero (vibrate) for the whole call.
 */
internal fun bluetoothSilenceApplies(onBluetoothCall: Boolean, callRinging: Boolean): Boolean =
    onBluetoothCall && !callRinging

/** The levels as they stood before a call silenced them. */
internal data class SavedSound(val volumes: Map<Int, Int>, val ringerMode: Int)

/** The phone's sound levels, as the call needs them. */
internal interface SoundLevels {
    fun volume(stream: Int): Int
    fun setVolume(stream: Int, volume: Int)
    fun ringerMode(): Int
    fun setRingerMode(mode: Int)
}

/** Keeps the saved levels in a form that survives the app being killed. */
internal interface SavedSoundStore {
    fun load(): SavedSound?
    fun save(saved: SavedSound)
    fun clear()
}

internal const val CALL_SILENCE_PREFS = "bt_call_silence"

/**
 * Silences [CALL_SILENCED_STREAMS] while a call is on a Bluetooth route, so that none of those sounds reaches the call's
 * Bluetooth link. The levels from before the call are saved first; [restore] puts them back exactly, and also puts back
 * the ringer mode if silencing changed it.
 */
internal class BluetoothCallSilence(private val levels: SoundLevels, private val store: SavedSoundStore) {

    /** Silences while [silence] is true, and restores the saved levels when it is false. Safe to call on every change. */
    @Synchronized
    fun sync(silence: Boolean) {
        if (silence) silence() else restore()
    }

    private fun silence() {
        if (store.load() == null) {
            // Saved before anything is zeroed, so a restore can always undo the silence.
            store.save(
                SavedSound(
                    volumes = CALL_SILENCED_STREAMS.associateWith { levels.volume(it) },
                    ringerMode = levels.ringerMode(),
                )
            )
        }
        // Zeroed on every call, not once: a level raised during the call would otherwise reach the call.
        CALL_SILENCED_STREAMS.forEach { levels.setVolume(it, 0) }
    }

    /** Puts back the saved levels, if any. Does nothing when nothing was silenced. */
    @Synchronized
    fun restore() {
        val saved = store.load() ?: return
        saved.volumes.forEach { (stream, volume) -> levels.setVolume(stream, volume) }
        if (levels.ringerMode() != saved.ringerMode) levels.setRingerMode(saved.ringerMode)
        store.clear()
    }
}

/** The phone's own sound levels, through AudioManager. A change the system refuses is logged, not thrown. */
internal class AndroidSoundLevels(private val audio: AudioManager) : SoundLevels {
    override fun volume(stream: Int): Int = audio.getStreamVolume(stream)

    override fun setVolume(stream: Int, volume: Int) {
        try {
            audio.setStreamVolume(stream, volume, 0)
        } catch (e: RuntimeException) {
            Log.e(TAG, "call_silence stream=$stream volume=$volume failed: ${e.message}")
        }
    }

    override fun ringerMode(): Int = audio.ringerMode

    override fun setRingerMode(mode: Int) {
        try {
            audio.ringerMode = mode
        } catch (e: RuntimeException) {
            Log.e(TAG, "call_silence ringer mode=$mode failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "CallSilence"
    }
}

/** The saved levels in a preferences file, written before anything is zeroed so that a kill cannot lose them. */
internal class PrefsSavedSound(private val prefs: SharedPreferences) : SavedSoundStore {
    override fun load(): SavedSound? {
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null
        return SavedSound(
            volumes = CALL_SILENCED_STREAMS.associateWith { prefs.getInt(volumeKey(it), 0) },
            ringerMode = prefs.getInt(KEY_RINGER, AudioManager.RINGER_MODE_NORMAL),
        )
    }

    override fun save(saved: SavedSound) {
        val edit = prefs.edit().putBoolean(KEY_ACTIVE, true).putInt(KEY_RINGER, saved.ringerMode)
        saved.volumes.forEach { (stream, volume) -> edit.putInt(volumeKey(stream), volume) }
        edit.commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private fun volumeKey(stream: Int): String = "volume_$stream"

    private companion object {
        const val KEY_ACTIVE = "active"
        const val KEY_RINGER = "ringer"
    }
}

/**
 * Puts back levels that a call left silenced because the app was killed during it. Does nothing while this process has
 * a live call, since that call keeps its own silence.
 */
internal fun restoreLeftOverCallSilence(context: Context) {
    if (MyInCallService.getInstance() != null) return
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    val prefs = context.getSharedPreferences(CALL_SILENCE_PREFS, Context.MODE_PRIVATE)
    BluetoothCallSilence(AndroidSoundLevels(audio), PrefsSavedSound(prefs)).restore()
}
