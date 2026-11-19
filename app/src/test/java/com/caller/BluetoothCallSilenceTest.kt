package com.caller

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothCallSilenceTest {
    private val music = AudioManager.STREAM_MUSIC
    private val notification = AudioManager.STREAM_NOTIFICATION
    private val system = AudioManager.STREAM_SYSTEM

    @Test
    fun theCallSoundsAreZeroedWhileSilenced() {
        val levels = FakeLevels(music to 10, notification to 5, system to 7)
        BluetoothCallSilence(levels, MemoryStore()).sync(silence = true)
        assertEquals(0, levels.volume(music))
        assertEquals(0, levels.volume(notification))
        assertEquals(0, levels.volume(system))
    }

    @Test
    fun restoreBringsBackTheLevelsFromBeforeTheCall() {
        val levels = FakeLevels(music to 10, notification to 5, system to 7)
        val guard = BluetoothCallSilence(levels, MemoryStore())
        guard.sync(silence = true)
        guard.sync(silence = false)
        assertEquals(10, levels.volume(music))
        assertEquals(5, levels.volume(notification))
        assertEquals(7, levels.volume(system))
    }

    @Test
    fun silencingAgainKeepsTheLevelsFromBeforeTheCall() {
        val levels = FakeLevels(music to 10, notification to 5, system to 7)
        val store = MemoryStore()
        val guard = BluetoothCallSilence(levels, store)
        guard.sync(silence = true)
        guard.sync(silence = true)
        guard.sync(silence = false)
        assertEquals(10, levels.volume(music))
    }

    @Test
    fun aLevelRaisedDuringTheCallIsZeroedAgain() {
        val levels = FakeLevels(music to 10, notification to 5, system to 7)
        val guard = BluetoothCallSilence(levels, MemoryStore())
        guard.sync(silence = true)
        levels.setVolume(music, 8)
        guard.sync(silence = true)
        assertEquals(0, levels.volume(music))
    }

    @Test
    fun restoreWithNothingSavedChangesNothing() {
        val levels = FakeLevels(music to 10, notification to 5, system to 7)
        BluetoothCallSilence(levels, MemoryStore()).sync(silence = false)
        assertEquals(10, levels.volume(music))
        assertEquals(5, levels.volume(notification))
    }

    @Test
    fun aRingerModeChangedBySilencingIsPutBack() {
        val levels = FakeLevels(music to 10, notification to 5, system to 7)
        levels.zeroingNotificationSwitchesToVibrate = true
        val guard = BluetoothCallSilence(levels, MemoryStore())
        guard.sync(silence = true)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, levels.ringerMode())
        guard.sync(silence = false)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, levels.ringerMode())
    }

    @Test
    fun levelsSavedBeforeARestartAreRestoredAfterIt() {
        val levels = FakeLevels(music to 10, notification to 5, system to 7)
        val store = MemoryStore()
        BluetoothCallSilence(levels, store).sync(silence = true)
        // The app was killed here. A new process has only the saved store and the phone's current levels.
        BluetoothCallSilence(levels, store).restore()
        assertEquals(10, levels.volume(music))
        assertEquals(5, levels.volume(notification))
    }
}

private class FakeLevels(vararg start: Pair<Int, Int>) : SoundLevels {
    private val volumes = mutableMapOf(*start)
    private var ringer = AudioManager.RINGER_MODE_NORMAL
    var zeroingNotificationSwitchesToVibrate = false

    override fun volume(stream: Int): Int = volumes[stream] ?: 0

    override fun setVolume(stream: Int, volume: Int) {
        volumes[stream] = volume
        if (stream == AudioManager.STREAM_NOTIFICATION && volume == 0 && zeroingNotificationSwitchesToVibrate) {
            ringer = AudioManager.RINGER_MODE_VIBRATE
        }
    }

    override fun ringerMode(): Int = ringer

    override fun setRingerMode(mode: Int) {
        ringer = mode
    }
}

private class MemoryStore : SavedSoundStore {
    private var saved: SavedSound? = null

    override fun load(): SavedSound? = saved

    override fun save(saved: SavedSound) {
        this.saved = saved
    }

    override fun clear() {
        saved = null
    }
}
