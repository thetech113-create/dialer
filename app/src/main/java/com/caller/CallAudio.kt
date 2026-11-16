package com.caller

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Speakerphone relay: Android gives apps no access to a cellular call's audio, so with the
 * call on loudspeaker this records the phone's microphone for the PC, and plays the PC's microphone
 * out loud so the call picks it up.
 */
@SuppressLint("MissingPermission")
class CallAudio(
    private val context: Context,
    private val send: (JSONObject) -> Boolean,
    /** Sends one frame of sound; returns false when it was dropped because the link is behind. */
    private val sendFrame: (JSONObject) -> Boolean,
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var played = 0
    @Volatile private var written = 0L

    val isRunning: Boolean get() = running

    /** Returns null when the relay started, otherwise why it could not. */
    fun start(preferredSource: String, usage: String): String? = synchronized(lock) {
        stopLocked()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Microphone permission is not granted in the phone app"
        }
        requestSpeaker()

        val format = AudioFormat.Builder()
            .setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        val player = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            if (usage == "media") AudioAttributes.USAGE_MEDIA
                            else AudioAttributes.USAGE_VOICE_COMMUNICATION
                        )
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(format.setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(FRAME_BYTES * 8)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: RuntimeException) {
            return "The phone could not open audio playback: ${e.message}"
        }

        val sources = (listOf(preferredSource) + SOURCES.keys).distinct().filter { it in SOURCES }
        var opened: Pair<String, AudioRecord>? = null
        for (name in sources) {
            opened = open(name)?.let { name to it }
            if (opened != null) break
        }
        if (opened == null) {
            player.release()
            return "The phone would not let the app record its microphone"
        }

        player.play()
        track = player
        record = opened.second
        played = 0
        written = 0
        running = true
        worker = thread(name = "call-audio") { capture(opened.first, sources) }
        null
    }

    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        if (!running && record == null) return
        running = false
        worker?.let { if (it !== Thread.currentThread()) it.join(1500) }
        worker = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        send(JSONObject().put("t", "audio_state").put("on", false))
    }

    /** Sound from the PC's microphone; dropped rather than queued when the phone falls behind. */
    fun play(pcm: ByteArray) {
        val player = track ?: return
        if (!running) return
        try {
            val accepted = player.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
            if (accepted > 0) written += accepted
            played++
        } catch (_: IllegalStateException) {
        }
    }

    private fun capture(openedSource: String, requestedSources: List<String>) {
        val rec = record ?: return
        val pcm = ByteArray(FRAME_BYTES)
        var sourceName = openedSource
        var lastSpeaker = SystemClock.uptimeMillis()

        send(
            JSONObject()
                .put("t", "audio_state")
                .put("on", true)
                .put("source", sourceName)
                .put("sources", requestedSources.joinToString(","))
        )

        try {
            rec.startRecording()
            while (running) {
                val read = readFull(rec, pcm)
                if (read < FRAME_BYTES) break
                val now = SystemClock.uptimeMillis()
                if (now - lastSpeaker > 2000) {
                    requestSpeaker()
                    lastSpeaker = now
                }
                val encoded = Base64.encodeToString(pcm, Base64.NO_WRAP)
                val peak = peakPcm16(pcm)
                val frame = JSONObject().put("t", "pcm").put("d", encoded).put("p", peak)
                if (!sendFrame(frame)) {
                    // Frame dropped by queue
                }
            }
        } catch (_: Exception) {
        } finally {
            synchronized(lock) {
                if (running) {
                    running = false
                    record?.let { runCatching { it.stop() }; it.release() }; record = null
                    track?.let { runCatching { it.stop() }; it.release() }; track = null
                    send(JSONObject().put("t", "audio_state").put("on", false))
                }
            }
        }
    }

    private fun readFull(rec: AudioRecord, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size && running) {
            val n = rec.read(buf, got, buf.size - got)
            if (n <= 0) break
            got += n
        }
        return got
    }

    private fun requestSpeaker() {
        try {
            audio.isSpeakerphoneOn = true
            audio.mode = AudioManager.MODE_IN_CALL
        } catch (_: Exception) {}
    }

    private fun open(sourceName: String): AudioRecord? {
        val source = SOURCES[sourceName] ?: return null
        val minBuf = AudioRecord.getMinBufferSize(
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val bufSize = maxOf(minBuf * 2, FRAME_BYTES * 4)
        return try {
            val rec = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .build()
            if (rec.state == AudioRecord.STATE_INITIALIZED) rec else { rec.release(); null }
        } catch (_: Exception) {
            null
        }
    }

    private fun peakPcm16(pcm: ByteArray): Int {
        var max = 0
        var i = 0
        while (i < pcm.size - 1) {
            val sample = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            val abs = abs(sample.toShort().toInt())
            if (abs > max) max = abs
            i += 2
        }
        return max
    }

    companion object {
        const val RATE = 16000
        const val FRAME_BYTES = 640 // 20 ms at 16 kHz 16-bit mono

        val SOURCES = mapOf(
            "voice_communication" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "mic" to MediaRecorder.AudioSource.MIC,
            "camcorder" to MediaRecorder.AudioSource.CAMCORDER,
            "voice_recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "unprocessed" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
        )
    }
}
