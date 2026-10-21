package com.caller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.telephony.TelephonyManager
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.caller.logging.UltraForensicLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class CallerSpokenAlias(
    val id: String = UUID.randomUUID().toString(),
    val contactId: String = "",
    val contactName: String,
    val phoneNumbers: List<String>,
    val spokenAlias: String,
    val enabled: Boolean = true
) {
    val primaryNumber: String
        get() = phoneNumbers.firstOrNull().orEmpty()
}

data class SelectableSavedContact(
    val contactId: String,
    val name: String,
    val phoneNumbers: List<String>
)

object CallerAnnouncementManager {
    private const val TAG = "CallerAnnouncementManager"
    private const val FORENSIC_COMP = "CallerAnnouncement"
    private const val PREFS_NAME = "caller_announcement_prefs"
    private const val KEY_ENABLED = "announcements_enabled"
    private const val KEY_ALIAS_RULES_JSON = "custom_alias_rules_v2_json"
    private const val NAME_KEY_PREFIX = "name_alias:"
    private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    private const val REPEAT_DELAY_MS = 3200L
    private const val DEDUP_DISPATCH_WINDOW_MS = 2800L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var isTtsInitializing = false
    private var isPlaying = false
    private var receiverRegistered = false
    private var useSamsungAccessibilityFallback = false

    @Volatile
    private var pendingTextToSpeak: String? = null

    @Volatile
    private var lastDispatchedText: String? = null

    @Volatile
    private var lastSpeakDispatchElapsedMs: Long = 0L

    @Volatile
    private var shouldKeepAnnouncing = false

    private var appContextRef: Context? = null
    private var activeFocusRequest: AudioFocusRequest? = null

    private val ringAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private val samsungFallbackAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }

    private val focusAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
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
            speakInternal(ctx, text, forceAudiblePreview = false)
        }
    }

    private val callStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context?.applicationContext ?: context ?: return
            when (intent?.action) {
                "SILENCE_RINGTONE", "FINISH_CALL_ACTIVITY" -> {
                    UltraForensicLogger.info(FORENSIC_COMP, "STOP_SIGNAL", "Received ${intent.action}")
                    stop()
                }
                "CALL_STATE_UPDATED" -> {
                    val isInCall = intent.getBooleanExtra("IS_IN_CALL", false)
                    val primaryState = intent.getStringExtra("PRIMARY_STATE").orEmpty()
                    val primaryNumber = intent.getStringExtra("PRIMARY_NUMBER").orEmpty()
                    val primaryName = intent.getStringExtra("PRIMARY_NAME").orEmpty()

                    if (isInCall && (primaryState == "RINGING" || primaryState == "NEW")) {
                        announceIncomingCaller(
                            context = ctx,
                            phoneNumber = primaryNumber,
                            contactName = primaryName
                        )
                    } else if (!isInCall || (primaryState.isNotBlank() && primaryState != "RINGING" && primaryState != "NEW")) {
                        stop()
                    }
                }
            }
        }
    }

    private fun safeAppContext(context: Context): Context {
        return context.applicationContext ?: context
    }

    private fun registerCallReceiverIfNeeded(context: Context) {
        if (receiverRegistered) return
        runCatching {
            val appCtx = safeAppContext(context)
            val filter = IntentFilter().apply {
                addAction("CALL_STATE_UPDATED")
                addAction("SILENCE_RINGTONE")
                addAction("FINISH_CALL_ACTIVITY")
            }
            ContextCompat.registerReceiver(
                appCtx,
                callStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    private fun prefsContext(context: Context): Context {
        return DirectBootCallManager.getDirectBootContext(safeAppContext(context))
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        UltraForensicLogger.info(FORENSIC_COMP, "SETTING_CHANGED", "enabled=$enabled")
        if (!enabled) stop()
    }

    @JvmOverloads
    fun init(context: Context, useDefaultEngineFallback: Boolean = false) {
        val appCtx = safeAppContext(context)
        appContextRef = appCtx
        registerCallReceiverIfNeeded(appCtx)

        if (tts != null && (isTtsInitialized || isTtsInitializing)) return

        isTtsInitializing = true
        // Always try binding directly to Google TTS first (fixes Samsung com.samsung.SMT silence bug)
        val tryGoogleEngineFirst = !useDefaultEngineFallback
        UltraForensicLogger.info(
            FORENSIC_COMP,
            "TTS_INIT_START",
            "tryGoogleEngineFirst=$tryGoogleEngineFirst manufacturer=${Build.MANUFACTURER}"
        )

        val initListener = TextToSpeech.OnInitListener { status ->
            mainHandler.post {
                isTtsInitializing = false
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@post
                    isTtsInitialized = true

                    configureInstalledVoiceAndLanguage(engine)
                    engine.setPitch(1.0f)
                    engine.setSpeechRate(1.0f)
                    engine.setAudioAttributes(
                        if (useSamsungAccessibilityFallback) samsungFallbackAudioAttributes else ringAudioAttributes
                    )

                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isPlaying = true
                            UltraForensicLogger.info(FORENSIC_COMP, "UTTERANCE_START", "id=$utteranceId")
                        }

                        override fun onDone(utteranceId: String?) {
                            isPlaying = false
                            abandonAudioFocus(appCtx)
                            UltraForensicLogger.info(
                                FORENSIC_COMP,
                                "UTTERANCE_DONE",
                                "id=$utteranceId keepAnnouncing=$shouldKeepAnnouncing"
                            )
                            mainHandler.removeCallbacks(repeatRunnable)
                            if (shouldKeepAnnouncing && isDeviceStillRinging(appCtx)) {
                                mainHandler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            handleUtteranceError(appCtx, utteranceId, -1)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            handleUtteranceError(appCtx, utteranceId, errorCode)
                        }
                    })

                    UltraForensicLogger.info(
                        FORENSIC_COMP,
                        "TTS_INIT_SUCCESS",
                        "defaultEngine=${ runCatching { engine.defaultEngine }.getOrDefault("unknown") }"
                    )

                    val queued = pendingTextToSpeak
                    if (!queued.isNullOrBlank()) {
                        speakInternal(appCtx, queued, forceAudiblePreview = !shouldKeepAnnouncing)
                    }
                } else {
                    isTtsInitialized = false
                    runCatching { tts?.shutdown() }
                    tts = null
                    UltraForensicLogger.forensic(
                        FORENSIC_COMP,
                        "TTS_INIT_FAILED",
                        "status=$status tryGoogleEngineFirst=$tryGoogleEngineFirst"
                    )
                    // If Google TTS failed to bind (e.g. disabled on device), fall back to system default engine
                    if (tryGoogleEngineFirst) {
                        init(appCtx, useDefaultEngineFallback = true)
                    }
                }
            }
        }

        runCatching {
            tts = if (tryGoogleEngineFirst) {
                TextToSpeech(appCtx, initListener, GOOGLE_TTS_PACKAGE)
            } else {
                TextToSpeech(appCtx, initListener)
            }
        }.onFailure { e ->
            isTtsInitializing = false
            isTtsInitialized = false
            tts = null
            UltraForensicLogger.deepest(FORENSIC_COMP, "TTS_CONSTRUCTOR_EXCEPTION", e.message.orEmpty(), e)
            if (tryGoogleEngineFirst) {
                init(appCtx, useDefaultEngineFallback = true)
            }
        }
    }

    /**
     * Selects a verified installed voice on disk so Samsung devices with Locale en_IN
     * never fail silently due to KEY_FEATURE_NOT_INSTALLED.
     */
    private fun configureInstalledVoiceAndLanguage(engine: TextToSpeech) {
        val preferredLocales = listOf(
            Locale("en", "IN"),
            Locale.getDefault(),
            Locale.UK,
            Locale.US
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val installedVoice: Voice? = runCatching {
                val allVoices = engine.voices ?: emptySet()
                val installedVoices = allVoices.filter { voice ->
                    val features = voice.features ?: emptySet()
                    !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) &&
                        voice.locale != null &&
                        voice.locale.language.equals("en", ignoreCase = true)
                }
                // Prefer offline installed voice matching en_IN -> default -> en_GB -> en_US
                installedVoices.firstOrNull { v ->
                    !v.isNetworkConnectionRequired &&
                        v.locale.country.equals("IN", ignoreCase = true)
                } ?: installedVoices.firstOrNull { v ->
                    !v.isNetworkConnectionRequired &&
                        v.locale.country.equals(Locale.getDefault().country, ignoreCase = true)
                } ?: installedVoices.firstOrNull { v ->
                    !v.isNetworkConnectionRequired
                } ?: installedVoices.firstOrNull()
            }.getOrNull()

            if (installedVoice != null) {
                val voiceResult = runCatching { engine.setVoice(installedVoice) }.getOrDefault(TextToSpeech.ERROR)
                if (voiceResult == TextToSpeech.SUCCESS) {
                    UltraForensicLogger.info(
                        FORENSIC_COMP,
                        "VOICE_SELECTED",
                        "name=${installedVoice.name} locale=${installedVoice.locale}"
                    )
                    return
                }
            }
        }

        for (candidate in preferredLocales) {
            val avail = runCatching { engine.isLanguageAvailable(candidate) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (avail >= TextToSpeech.LANG_AVAILABLE) {
                val setRes = runCatching { engine.setLanguage(candidate) }.getOrDefault(TextToSpeech.ERROR)
                if (setRes >= TextToSpeech.LANG_AVAILABLE) {
                    UltraForensicLogger.info(FORENSIC_COMP, "LOCALE_SELECTED", "locale=$candidate result=$setRes")
                    return
                }
            }
        }
        runCatching { engine.setLanguage(Locale.US) }
    }

    private fun handleUtteranceError(context: Context, utteranceId: String?, errorCode: Int) {
        isPlaying = false
        abandonAudioFocus(context)
        UltraForensicLogger.forensic(
            FORENSIC_COMP,
            "UTTERANCE_ERROR",
            "id=$utteranceId errorCode=$errorCode samsungFallback=$useSamsungAccessibilityFallback"
        )
        // If Samsung blocked STREAM_RING during ringing, switch to accessibility speech attributes and retry once
        if (!useSamsungAccessibilityFallback && Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            useSamsungAccessibilityFallback = true
            val text = pendingTextToSpeak
            if (!text.isNullOrBlank() && shouldKeepAnnouncing) {
                lastSpeakDispatchElapsedMs = 0L
                mainHandler.postDelayed({
                    speakInternal(context, text, forceAudiblePreview = false)
                }, 150L)
            }
        }
    }

    fun getAllCustomAliases(context: Context): List<CallerSpokenAlias> {
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_ALIAS_RULES_JSON, null).orEmpty()
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val spoken = obj.optString("spokenAlias").trim()
                    if (spoken.isEmpty()) continue
                    val numbersArray = obj.optJSONArray("phoneNumbers")
                    val numbers = buildList {
                        if (numbersArray != null) {
                            for (j in 0 until numbersArray.length()) {
                                val num = numbersArray.optString(j).trim()
                                if (num.isNotEmpty()) add(num)
                            }
                        }
                        val singleFallback = obj.optString("phoneNumber").trim()
                        if (singleFallback.isNotEmpty() && singleFallback !in this) {
                            add(singleFallback)
                        }
                    }
                    add(
                        CallerSpokenAlias(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            contactId = obj.optString("contactId").trim(),
                            contactName = obj.optString("contactName").trim(),
                            phoneNumbers = numbers,
                            spokenAlias = spoken,
                            enabled = obj.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveCustomContactRule(
        context: Context,
        contactId: String,
        contactName: String,
        phoneNumbers: List<String>,
        spokenAlias: String,
        enabled: Boolean = true,
        editingRuleId: String? = null
    ) {
        val cleanName = contactName.trim()
        val cleanNumbers = phoneNumbers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val cleanSpoken = spokenAlias.trim()
        if (cleanSpoken.isEmpty() || (cleanName.isEmpty() && cleanNumbers.isEmpty())) return

        val incomingLookupKeys = LinkedHashSet<String>().apply {
            cleanNumbers.forEach { num ->
                add(num)
                addAll(buildP2PNumberSnapshot(num).lookupKeys)
            }
        }

        val existing = getAllCustomAliases(context).toMutableList()
        val existingIdx = existing.indexOfFirst { item ->
            (editingRuleId != null && item.id == editingRuleId) ||
                (contactId.isNotBlank() && item.contactId == contactId) ||
                (cleanName.isNotEmpty() && item.contactName.equals(cleanName, ignoreCase = true)) ||
                item.phoneNumbers.any { num ->
                    buildP2PNumberSnapshot(num).lookupKeys.intersect(incomingLookupKeys).isNotEmpty()
                }
        }

        val updatedRule = CallerSpokenAlias(
            id = if (existingIdx >= 0) existing[existingIdx].id else (editingRuleId ?: UUID.randomUUID().toString()),
            contactId = contactId,
            contactName = cleanName,
            phoneNumbers = cleanNumbers,
            spokenAlias = cleanSpoken,
            enabled = enabled
        )

        if (existingIdx >= 0) {
            existing[existingIdx] = updatedRule
        } else {
            existing.add(0, updatedRule)
        }
        persistCustomAliases(context, existing)
    }

    fun setCustomRuleEnabled(context: Context, ruleId: String, enabled: Boolean) {
        val updated = getAllCustomAliases(context).map { rule ->
            if (rule.id == ruleId) rule.copy(enabled = enabled) else rule
        }
        persistCustomAliases(context, updated)
    }

    fun deleteCustomAlias(context: Context, ruleId: String) {
        val remaining = getAllCustomAliases(context).filterNot { it.id == ruleId }
        persistCustomAliases(context, remaining)
    }

    private fun persistCustomAliases(context: Context, rules: List<CallerSpokenAlias>) {
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        prefs.all.keys
            .filter { it != KEY_ENABLED && it != KEY_ALIAS_RULES_JSON }
            .forEach { editor.remove(it) }

        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject().apply {
                    put("id", rule.id)
                    put("contactId", rule.contactId)
                    put("contactName", rule.contactName)
                    put("phoneNumbers", JSONArray(rule.phoneNumbers))
                    put("spokenAlias", rule.spokenAlias)
                    put("enabled", rule.enabled)
                }
            )
            if (rule.enabled) {
                rule.phoneNumbers.forEach { rawNum ->
                    if (rawNum.isNotBlank()) {
                        val snapshot = buildP2PNumberSnapshot(rawNum)
                        editor.putString(rawNum.trim(), rule.spokenAlias)
                        snapshot.lookupKeys.forEach { key ->
                            if (key.isNotBlank()) editor.putString(key, rule.spokenAlias)
                        }
                    }
                }
                if (rule.contactName.isNotBlank()) {
                    editor.putString(
                        NAME_KEY_PREFIX + rule.contactName.lowercase(Locale.ROOT),
                        rule.spokenAlias
                    )
                }
            }
        }
        editor.putString(KEY_ALIAS_RULES_JSON, array.toString())
        editor.apply()
    }

    @JvmOverloads
    fun getAlias(context: Context, rawOrNormalizedNumber: String, contactName: String? = null): String {
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (rawOrNormalizedNumber.isNotBlank()) {
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
        }

        val cleanContact = contactName?.trim().orEmpty()
        if (cleanContact.isNotBlank() && !cleanContact.equals("Unknown", ignoreCase = true)) {
            val lowerFull = cleanContact.lowercase(Locale.ROOT)
            prefs.getString(NAME_KEY_PREFIX + lowerFull, null)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                return it
            }
            val firstWord = lowerFull.substringBefore(' ').trim()
            if (firstWord.isNotEmpty()) {
                prefs.getString(NAME_KEY_PREFIX + firstWord, null)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    return it
                }
            }
        }

        return ""
    }

    fun setAlias(context: Context, rawOrNormalizedNumber: String, alias: String) {
        saveCustomContactRule(
            context = context,
            contactId = "",
            contactName = "",
            phoneNumbers = listOf(rawOrNormalizedNumber),
            spokenAlias = alias,
            enabled = true
        )
    }

    fun removeAlias(context: Context, rawOrNormalizedNumber: String) {
        val snapshot = buildP2PNumberSnapshot(rawOrNormalizedNumber)
        val remaining = getAllCustomAliases(context).filterNot { rule ->
            rule.phoneNumbers.any { num ->
                num == rawOrNormalizedNumber.trim() ||
                    buildP2PNumberSnapshot(num).lookupKeys.intersect(snapshot.lookupKeys.toSet()).isNotEmpty()
            }
        }
        persistCustomAliases(context, remaining)
    }

    private fun buildIncomingCallAnnouncementPhrase(nameOrAlias: String): String {
        val cleaned = nameOrAlias.trim()
        if (cleaned.startsWith("Incoming call", ignoreCase = true)) {
            return cleaned
        }
        return "Incoming call from $cleaned"
    }

    fun announceIncomingCaller(
        context: Context,
        phoneNumber: String,
        contactName: String? = null
    ) {
        val appCtx = safeAppContext(context)
        if (!isEnabled(appCtx)) {
            UltraForensicLogger.info(FORENSIC_COMP, "SKIP_DISABLED", "Caller announcement is disabled in prefs")
            return
        }

        val resolvedContactName = contactName?.trim()?.takeUnless {
            it.isBlank() || it.equals("Unknown", ignoreCase = true) || it == phoneNumber.trim()
        } ?: if (phoneNumber.isNotBlank()) {
            DirectBootCallManager.getDpsContactName(appCtx, phoneNumber).takeUnless {
                it.isBlank() || it.equals("Unknown", ignoreCase = true)
            }
        } else {
            null
        }

        val customAlias = getAlias(appCtx, phoneNumber, resolvedContactName)
        val spokenIdentity = when {
            customAlias.isNotBlank() -> customAlias
            !resolvedContactName.isNullOrBlank() -> resolvedContactName
            phoneNumber.isNotBlank() -> formatNumberForSpeech(phoneNumber)
            else -> "Unknown caller"
        }

        UltraForensicLogger.info(
            FORENSIC_COMP,
            "ANNOUNCE_RESOLVED",
            "hasCustomAlias=${customAlias.isNotBlank()} hasContact=${!resolvedContactName.isNullOrBlank()} spokenIdentity=$spokenIdentity"
        )
        announce(appCtx, spokenIdentity, repeatWhileRinging = true)
    }

    fun testAnnouncementPreview(context: Context, spokenAlias: String) {
        val cleaned = spokenAlias.trim().ifBlank { "Amma" }
        val text = buildIncomingCallAnnouncementPhrase(cleaned)
        val appCtx = safeAppContext(context)
        appContextRef = appCtx
        pendingTextToSpeak = text
        shouldKeepAnnouncing = false
        lastSpeakDispatchElapsedMs = 0L
        mainHandler.removeCallbacks(repeatRunnable)

        mainHandler.post {
            if (!isTtsInitialized || tts == null) {
                init(appCtx)
                return@post
            }
            speakInternal(appCtx, text, forceAudiblePreview = true)
        }
    }

    @JvmOverloads
    fun announce(context: Context, nameOrAlias: String, repeatWhileRinging: Boolean = true) {
        val appCtx = safeAppContext(context)
        appContextRef = appCtx
        registerCallReceiverIfNeeded(appCtx)

        if (!isEnabled(appCtx)) return

        val cleaned = nameOrAlias.trim()
        if (cleaned.isEmpty()) return

        if (!isRingerAudible(appCtx)) {
            UltraForensicLogger.info(
                FORENSIC_COMP,
                "SKIP_SILENT_RINGER",
                "Ringer mode is silent/vibrate and no headset connected"
            )
            return
        }

        val fullText = buildIncomingCallAnnouncementPhrase(cleaned)
        val nowElapsed = SystemClock.elapsedRealtime()

        // CRITICAL FIX: Prevent rapid duplicate CALL_STATE_UPDATED / Compose recompositions
        // from calling QUEUE_FLUSH and cancelling in-flight synthesis before onStart() fires!
        val isDuplicateInFlight = shouldKeepAnnouncing &&
            pendingTextToSpeak == fullText &&
            (isPlaying || isTtsInitializing ||
                (lastDispatchedText == fullText && (nowElapsed - lastSpeakDispatchElapsedMs) < DEDUP_DISPATCH_WINDOW_MS))

        pendingTextToSpeak = fullText
        shouldKeepAnnouncing = repeatWhileRinging

        if (isDuplicateInFlight) {
            return
        }

        mainHandler.post {
            if (!isTtsInitialized || tts == null) {
                UltraForensicLogger.info(FORENSIC_COMP, "QUEUE_PENDING_INIT", "text=$fullText")
                init(appCtx)
                return@post
            }
            speakInternal(appCtx, fullText, forceAudiblePreview = false)
        }
    }

    private fun speakInternal(context: Context, text: String, forceAudiblePreview: Boolean = false) {
        if (!forceAudiblePreview && (!shouldKeepAnnouncing || !isRingerAudible(context))) return
        val engine = tts ?: return

        lastDispatchedText = text
        lastSpeakDispatchElapsedMs = SystemClock.elapsedRealtime()

        requestAudioFocusWithDucking(context)
        engine.setPitch(1.0f)
        engine.setSpeechRate(1.0f)

        val targetAttributes = if (useSamsungAccessibilityFallback) {
            samsungFallbackAudioAttributes
        } else {
            ringAudioAttributes
        }
        val targetStream = if (useSamsungAccessibilityFallback) {
            AudioManager.STREAM_NOTIFICATION
        } else {
            AudioManager.STREAM_RING
        }

        engine.setAudioAttributes(targetAttributes)

        val utteranceId = "incoming_call_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, targetStream)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val speakResult = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            utteranceId
        )
        UltraForensicLogger.info(
            FORENSIC_COMP,
            "SPEAK_DISPATCHED",
            "result=$speakResult stream=$targetStream text=$text utteranceId=$utteranceId"
        )

        if (speakResult == TextToSpeech.ERROR) {
            handleUtteranceError(context, utteranceId, TextToSpeech.ERROR)
        }
    }

    private fun isRingerAudible(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true
            val ringerNormal = am.ringerMode == AudioManager.RINGER_MODE_NORMAL
            val ringVolume = am.getStreamVolume(AudioManager.STREAM_RING)
            if (ringerNormal && ringVolume > 0) return true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val hasHeadset = outputs.any {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                if (hasHeadset) return true
            }
            false
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
                    .setAudioAttributes(focusAudioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(false)
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
        lastDispatchedText = null
        lastSpeakDispatchElapsedMs = 0L
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

private fun groupSavedContactsForPicker(rawContacts: List<Contact>): List<SelectableSavedContact> {
    val grouped = linkedMapOf<String, MutableList<Contact>>()
    rawContacts.forEach { contact ->
        val nameKey = contact.name.trim().takeIf { it.isNotEmpty() && !it.equals("Unknown", ignoreCase = true) }
            ?: return@forEach
        val groupKey = nameKey.lowercase(Locale.ROOT)
        grouped.getOrPut(groupKey) { mutableListOf() }.add(contact)
    }
    return grouped.values.mapNotNull { list ->
        val first = list.firstOrNull() ?: return@mapNotNull null
        val distinctNumbers = list.map { it.phoneNumber.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { buildP2PNumberSnapshot(it).normalized.ifBlank { it } }
        if (distinctNumbers.isEmpty()) return@mapNotNull null
        SelectableSavedContact(
            contactId = first.id,
            name = first.name.trim(),
            phoneNumbers = distinctNumbers
        )
    }.sortedBy { it.name.lowercase(Locale.ROOT) }
}

private fun queryDeviceContactsFallback(context: Context): List<Contact> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }
    val list = mutableListOf<Contact>()
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
        ContactsContract.CommonDataKinds.Phone.NUMBER
    )
    runCatching {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val id = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else ""
                val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                val number = if (numIdx >= 0) cursor.getString(numIdx).orEmpty().trim() else ""
                if (name.isNotEmpty() && number.isNotEmpty()) {
                    list.add(Contact(id = id, name = name, phoneNumber = number))
                }
            }
        }
    }
    return list
}

private val QUICK_SPOKEN_PRESETS = listOf(
    "Amma", "Appa", "Akka", "Anna", "Ajji", "Home", "Boss"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallerAnnouncementSettingsCard(
    contacts: List<Contact> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var announcementsEnabled by remember {
        mutableStateOf(CallerAnnouncementManager.isEnabled(context))
    }
    var customAliases by remember {
        mutableStateOf(CallerAnnouncementManager.getAllCustomAliases(context))
    }
    var loadedFallbackContacts by remember { mutableStateOf<List<Contact>>(emptyList()) }

    LaunchedEffect(contacts) {
        CallerAnnouncementManager.init(context)
        if (contacts.isEmpty()) {
            loadedFallbackContacts = withContext(Dispatchers.IO) {
                queryDeviceContactsFallback(context)
            }
        }
    }

    val allAvailableContacts = remember(contacts, loadedFallbackContacts) {
        groupSavedContactsForPicker(if (contacts.isNotEmpty()) contacts else loadedFallbackContacts)
    }

    var isDialogOpen by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<CallerSpokenAlias?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Caller ID Announcement",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Announce Incoming Calls",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (announcementsEnabled) {
                            "Says \"Incoming call from [Name]\" out loud while ringing"
                        } else {
                            "Disabled — incoming calls will only play your ringtone"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = announcementsEnabled,
                    onCheckedChange = { enabled ->
                        announcementsEnabled = enabled
                        CallerAnnouncementManager.setEnabled(context, enabled)
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Custom Contact Announcements",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Select saved contacts and set a custom spoken name for each (e.g. Hamsa → \"Incoming call from Amma\")",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = {
                        editingRule = null
                        isDialogOpen = true
                    },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Select contact",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Select Contact", fontSize = 13.sp)
                }
            }

            if (customAliases.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    customAliases.forEach { rule ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    editingRule = rule
                                    isDialogOpen = true
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rule.contactName.ifBlank {
                                            formatPhoneNumberForDisplay(rule.primaryNumber)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (rule.phoneNumbers.isNotEmpty()) {
                                        Text(
                                            text = rule.phoneNumbers.joinToString(", ") {
                                                formatPhoneNumberForDisplay(it)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (rule.enabled) {
                                            "Says: \"Incoming call from ${rule.spokenAlias}\""
                                        } else {
                                            "Custom announcement paused (uses default name)"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (rule.enabled) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        },
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { enabled ->
                                        CallerAnnouncementManager.setCustomRuleEnabled(context, rule.id, enabled)
                                        customAliases = CallerAnnouncementManager.getAllCustomAliases(context)
                                    }
                                )

                                IconButton(
                                    onClick = {
                                        CallerAnnouncementManager.testAnnouncementPreview(
                                            context,
                                            rule.spokenAlias
                                        )
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = "Test voice",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        CallerAnnouncementManager.deleteCustomAlias(context, rule.id)
                                        customAliases = CallerAnnouncementManager.getAllCustomAliases(context)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (isDialogOpen) {
        val initialSelectedContact = remember(editingRule) {
            editingRule?.let { rule ->
                SelectableSavedContact(
                    contactId = rule.contactId,
                    name = rule.contactName,
                    phoneNumbers = rule.phoneNumbers
                )
            }
        }
        var selectedContact by remember(editingRule) { mutableStateOf(initialSelectedContact) }
        var searchFilter by remember { mutableStateOf("") }
        var spokenAliasInput by remember(editingRule) {
            mutableStateOf(editingRule?.spokenAlias.orEmpty())
        }

        val filteredContacts = remember(allAvailableContacts, searchFilter) {
            val q = searchFilter.trim()
            if (q.isEmpty()) {
                allAvailableContacts
            } else {
                allAvailableContacts.filter { c ->
                    c.name.contains(q, ignoreCase = true) ||
                        c.phoneNumbers.any { it.contains(q) }
                }
            }
        }

        AlertDialog(
            onDismissRequest = { isDialogOpen = false },
            title = {
                Text(
                    text = if (selectedContact == null) {
                        "Select Saved Contact"
                    } else if (editingRule != null) {
                        "Edit Contact Announcement"
                    } else {
                        "Set Spoken Name"
                    },
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val chosen = selectedContact
                    if (chosen == null) {
                        OutlinedTextField(
                            value = searchFilter,
                            onValueChange = { searchFilter = it },
                            placeholder = { Text("Search saved contacts...") },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                if (searchFilter.isNotEmpty()) {
                                    IconButton(onClick = { searchFilter = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )

                        if (filteredContacts.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No matching saved contacts found",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            ) {
                                items(
                                    items = filteredContacts,
                                    key = { "${it.contactId}_${it.name}" }
                                ) { contactItem ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedContact = contactItem
                                                val existingForContact = customAliases.firstOrNull {
                                                    it.contactName.equals(contactItem.name, ignoreCase = true)
                                                }
                                                if (existingForContact != null && spokenAliasInput.isBlank()) {
                                                    spokenAliasInput = existingForContact.spokenAlias
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Person,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = contactItem.name,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = contactItem.phoneNumbers.joinToString(", ") {
                                                    formatPhoneNumberForDisplay(it)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                }
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chosen.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = chosen.phoneNumbers.joinToString(", ") {
                                            formatPhoneNumberForDisplay(it)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { selectedContact = null }) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Change")
                                }
                            }
                        }

                        Text(
                            text = "Quick Select Spoken Name:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            QUICK_SPOKEN_PRESETS.forEach { preset ->
                                val isSelected = spokenAliasInput.trim().equals(preset, ignoreCase = true)
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    modifier = Modifier.clickable {
                                        spokenAliasInput = preset
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = preset,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = spokenAliasInput,
                            onValueChange = { spokenAliasInput = it },
                            label = { Text("Spoken Name (e.g. Amma)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )

                        if (spokenAliasInput.isNotBlank()) {
                            TextButton(
                                onClick = {
                                    CallerAnnouncementManager.testAnnouncementPreview(
                                        context,
                                        spokenAliasInput
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test: \"Incoming call from ${spokenAliasInput.trim()}\"")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (selectedContact != null) {
                    Button(
                        enabled = spokenAliasInput.isNotBlank(),
                        onClick = {
                            val chosen = selectedContact ?: return@Button
                            CallerAnnouncementManager.saveCustomContactRule(
                                context = context,
                                contactId = chosen.contactId,
                                contactName = chosen.name,
                                phoneNumbers = chosen.phoneNumbers,
                                spokenAlias = spokenAliasInput,
                                enabled = editingRule?.enabled ?: true,
                                editingRuleId = editingRule?.id
                            )
                            customAliases = CallerAnnouncementManager.getAllCustomAliases(context)
                            isDialogOpen = false
                        }
                    ) {
                        Text("Save")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { isDialogOpen = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
