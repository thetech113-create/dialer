package com.caller

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
import android.util.Log
import java.util.Locale

object CallerAnnouncementManager {
    private const val TAG = "CallerAnnouncementManager"
    private const val PREFS_NAME = "caller_announcement_prefs"
    private const val KEY_ENABLED = "announcements_enabled"
    private const val REPEAT_DELAY_MS = 3500L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var isTtsInitializing = false
    private var isPlaying = false

    @Volatile
    private var pendingTextToSpeak: String? = null

    @Volatile
    private var shouldKeepAnnouncing = false

    private var appContextRef: Context? = null
    private var activeFocusRequest: AudioFocusRequest? = null

    private val speechAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val ctx = appContextRef ?: return
            val text = pendingTextToSpeak ?: return
            if (!shouldKeepAnnouncing || !isDeviceStillRinging(ctx)) {
                stop()
                return
            }
            speakInternal(ctx, text)
        }
    }

    private fun prefsContext(context: Context): Context {
        return DirectBootCallManager.getDirectBootContext(context.applicationContext)
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) stop()
    }

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        if (tts != null && (isTtsInitialized || isTtsInitializing)) return

        isTtsInitializing = true
        runCatching {
            tts = TextToSpeech(appCtx) { status ->
                mainHandler.post {
                    isTtsInitializing = false
                    if (status == TextToSpeech.SUCCESS) {
                        val engine = tts ?: return@post
                        isTtsInitialized = true
                        val localeResult = engine.setLanguage(Locale.getDefault())
                        if (localeResult == TextToSpeech.LANG_MISSING_DATA ||
                            localeResult == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            engine.setLanguage(Locale.US)
                        }
                        engine.setSpeechRate(0.92f)
                        engine.setAudioAttributes(speechAudioAttributes)
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                isPlaying = true
                            }

                            override fun onDone(utteranceId: String?) {
                                isPlaying = false
                                abandonAudioFocus(appCtx)
                                mainHandler.removeCallbacks(repeatRunnable)
                                if (shouldKeepAnnouncing && isDeviceStillRinging(appCtx)) {
                                    mainHandler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
                                }
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                isPlaying = false
                                abandonAudioFocus(appCtx)
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                isPlaying = false
                                abandonAudioFocus(appCtx)
                            }
                        })
                        Log.w(TAG, "TTS initialized successfully")

                        // CRITICAL FIX: Immediately speak any announcement queued while TTS was binding
                        val queued = pendingTextToSpeak
                        if (!queued.isNullOrBlank() && shouldKeepAnnouncing) {
                            speakInternal(appCtx, queued)
                        }
                    } else {
                        isTtsInitialized = false
                        tts = null
                        Log.e(TAG, "TTS initialization failed with status=$status")
                    }
                }
            }
        }.onFailure { e ->
            isTtsInitializing = false
            isTtsInitialized = false
            Log.e(TAG, "TTS constructor failed: ${e.message}", e)
        }
    }

    /**
     * Looks up a saved custom alias across all normalized forms of the phone number (+91, 10-digit, etc.).
     */
    fun getAlias(context: Context, rawOrNormalizedNumber: String): String {
        if (rawOrNormalizedNumber.isBlank()) return ""
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val snapshot = buildP2PNumberSnapshot(rawOrNormalizedNumber)
        val candidateKeys = LinkedHashSet<String>().apply {
            add(rawOrNormalizedNumber.trim())
            addAll(snapshot.lookupKeys)
        }
        for (key in candidateKeys) {
            if (key.isBlank()) continue
            val value = prefs.getString(key, null)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return ""
    }

    /**
     * Saves the alias under all normalized lookup keys for the phone number so incoming calls always match.
     */
    fun setAlias(context: Context, rawOrNormalizedNumber: String, alias: String) {
        if (rawOrNormalizedNumber.isBlank()) return
        val trimmedAlias = alias.trim()
        if (trimmedAlias.isEmpty()) {
            removeAlias(context, rawOrNormalizedNumber)
            return
        }
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val snapshot = buildP2PNumberSnapshot(rawOrNormalizedNumber)
        val editor = prefs.edit()
        editor.putString(rawOrNormalizedNumber.trim(), trimmedAlias)
        snapshot.lookupKeys.forEach { key ->
            if (key.isNotBlank()) editor.putString(key, trimmedAlias)
        }
        editor.apply()
    }

    fun removeAlias(context: Context, rawOrNormalizedNumber: String) {
        if (rawOrNormalizedNumber.isBlank()) return
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val snapshot = buildP2PNumberSnapshot(rawOrNormalizedNumber)
        val editor = prefs.edit()
        editor.remove(rawOrNormalizedNumber.trim())
        snapshot.lookupKeys.forEach { key ->
            if (key.isNotBlank()) editor.remove(key)
        }
        editor.apply()
    }

    /**
     * Resolves Alias -> Contact Name -> Spoken Phone Number and starts announcing.
     */
    fun announceIncomingCaller(
        context: Context,
        phoneNumber: String,
        contactName: String? = null
    ) {
        val alias = getAlias(context, phoneNumber)
        val cleanContact = contactName?.trim().orEmpty().takeUnless {
            it.isBlank() || it.equals("Unknown", ignoreCase = true)
        }
        val spokenIdentity = when {
            alias.isNotBlank() -> alias
            !cleanContact.isNullOrBlank() -> cleanContact
            phoneNumber.isNotBlank() -> formatNumberForSpeech(phoneNumber)
            else -> "Unknown caller"
        }
        announce(context, spokenIdentity)
    }

    fun announce(context: Context, nameOrAlias: String) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        if (!isEnabled(appCtx)) return

        val cleaned = nameOrAlias.trim()
        if (cleaned.isEmpty()) return

        if (!isRingerAudible(appCtx)) {
            Log.w(TAG, "Skipping caller announcement: device is in silent/vibrate mode")
            return
        }

        val fullText = if (cleaned.endsWith("is calling", ignoreCase = true)) {
            cleaned
        } else {
            "Incoming call from $cleaned"
        }

        val isSameTextAlreadyRunning = shouldKeepAnnouncing && pendingTextToSpeak == fullText && isPlaying
        pendingTextToSpeak = fullText
        shouldKeepAnnouncing = true

        if (isSameTextAlreadyRunning) return

        mainHandler.post {
            if (!isTtsInitialized || tts == null) {
                Log.w(TAG, "TTS not ready yet; queueing announcement: $fullText")
                init(appCtx)
                return@post
            }
            speakInternal(appCtx, fullText)
        }
    }

    private fun speakInternal(context: Context, text: String) {
        if (!shouldKeepAnnouncing || !isRingerAudible(context)) return
        val engine = tts ?: return

        requestAudioFocusWithDucking(context)
        engine.setAudioAttributes(speechAudioAttributes)

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_RING)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val result = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "incoming_call_${System.currentTimeMillis()}"
        )
        Log.w(TAG, "TTS speak dispatched result=$result text=$text")
    }

    private fun isRingerAudible(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
            val ringerNormal = am.ringerMode == AudioManager.RINGER_MODE_NORMAL
            val ringVolume = am.getStreamVolume(AudioManager.STREAM_RING)
            ringerNormal && ringVolume > 0
        } catch (_: Exception) {
            true
        }
    }

    private fun isDeviceStillRinging(context: Context): Boolean {
        val snapshot = MyInCallService.peekLastCallUiSnapshot()
        if (snapshot != null) {
            return snapshot.isInCall &&
                (snapshot.primaryState == "RINGING" || snapshot.secondaryState == "RINGING")
        }
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.callState == TelephonyManager.CALL_STATE_RINGING
        } catch (_: Exception) {
            false
        }
    }

    private fun requestAudioFocusWithDucking(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(speechAudioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener {}
                    .build()
                activeFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_RING,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (_: Exception) {}
    }

    private fun abandonAudioFocus(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                activeFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    private fun formatNumberForSpeech(phoneNumber: String): String {
        val snapshot = buildP2PNumberSnapshot(phoneNumber)
        val cleanDigits = snapshot.last10.ifBlank { snapshot.digits }
        return if (cleanDigits.isNotBlank()) {
            cleanDigits.toCharArray().joinToString(" ")
        } else {
            phoneNumber
        }
    }

    fun stop() {
        shouldKeepAnnouncing = false
        pendingTextToSpeak = null
        mainHandler.removeCallbacks(repeatRunnable)
        mainHandler.post {
            runCatching {
                if (isPlaying || tts?.isSpeaking == true) {
                    tts?.stop()
                }
            }
            isPlaying = false
            appContextRef?.let { abandonAudioFocus(it) }
        }
    }

    fun shutdown() {
        stop()
        mainHandler.post {
            runCatching {
                tts?.shutdown()
            }
            tts = null
            isTtsInitialized = false
            isTtsInitializing = false
        }
    }
}
