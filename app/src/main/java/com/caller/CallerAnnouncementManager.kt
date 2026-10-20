package com.caller

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

object CallerAnnouncementManager {
    private const val TAG = "CallerAnnouncementManager"
    private const val PREFS_NAME = "caller_announcement_prefs"
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var isPlaying = false

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsInitialized = true
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(0.9f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isPlaying = true
                    }
                    override fun onDone(utteranceId: String?) {
                        isPlaying = false
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isPlaying = false
                    }
                })
                Log.d(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS initialization failed")
            }
        }
    }

    fun getAlias(context: Context, normalizedNumber: String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(normalizedNumber, "") ?: ""
    }

    fun setAlias(context: Context, normalizedNumber: String, alias: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(normalizedNumber, alias.trim()).apply()
    }

    fun removeAlias(context: Context, normalizedNumber: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(normalizedNumber).apply()
    }

    fun announce(context: Context, nameOrAlias: String) {
        if (!isTtsInitialized || tts == null) {
            init(context)
            // It might not be ready immediately, but we try
        }
        val text = "$nameOrAlias is calling"
        
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_RING)

        // Use RING stream so it plays over the ringtone and follows ring volume
        tts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "incoming_call_utterance")
    }

    fun stop() {
        if (isPlaying || tts?.isSpeaking == true) {
            tts?.stop()
        }
        isPlaying = false
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsInitialized = false
        isPlaying = false
    }
}
