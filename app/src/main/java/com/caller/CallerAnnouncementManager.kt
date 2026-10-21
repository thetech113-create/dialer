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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class CallerSpokenAlias(
    val id: String = UUID.randomUUID().toString(),
    val contactName: String,
    val phoneNumber: String,
    val spokenAlias: String
)

object CallerAnnouncementManager {
    private const val TAG = "CallerAnnouncementManager"
    private const val PREFS_NAME = "caller_announcement_prefs"
    private const val KEY_ENABLED = "announcements_enabled"
    private const val KEY_ALIAS_RULES_JSON = "custom_alias_rules_json"
    private const val NAME_KEY_PREFIX = "name_alias:"
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

                        val queued = pendingTextToSpeak
                        if (!queued.isNullOrBlank()) {
                            speakInternal(appCtx, queued)
                        }
                    } else {
                        isTtsInitialized = false
                        tts = null
                        Log.e(TAG, "TTS initialization failed: status=$status")
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
     * Returns all saved custom caller aliases (e.g. Hamsa -> Amma) for display in Settings.
     */
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
                    add(
                        CallerSpokenAlias(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            contactName = obj.optString("contactName").trim(),
                            phoneNumber = obj.optString("phoneNumber").trim(),
                            spokenAlias = spoken
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Saves or updates a custom spoken alias for a contact name and/or phone number
     * (e.g., contactName = "Hamsa", phoneNumber = "+91...", spokenAlias = "Amma").
     */
    fun saveCustomAlias(
        context: Context,
        contactName: String,
        phoneNumber: String,
        spokenAlias: String
    ) {
        val cleanName = contactName.trim()
        val cleanNumber = phoneNumber.trim()
        val cleanSpoken = spokenAlias.trim()
        if (cleanSpoken.isEmpty() || (cleanName.isEmpty() && cleanNumber.isEmpty())) return

        val existing = getAllCustomAliases(context).toMutableList()
        val numberSnapshot = buildP2PNumberSnapshot(cleanNumber)

        // Replace existing rule if it matches the same contact name or normalized number
        existing.removeAll { item ->
            val sameName = cleanName.isNotEmpty() && item.contactName.equals(cleanName, ignoreCase = true)
            val sameNumber = cleanNumber.isNotEmpty() && item.phoneNumber.isNotEmpty() &&
                buildP2PNumberSnapshot(item.phoneNumber).lookupKeys.intersect(numberSnapshot.lookupKeys.toSet()).isNotEmpty()
            sameName || sameNumber
        }

        existing.add(
            0,
            CallerSpokenAlias(
                contactName = cleanName,
                phoneNumber = cleanNumber,
                spokenAlias = cleanSpoken
            )
        )
        persistCustomAliases(context, existing)
    }

    fun deleteCustomAlias(context: Context, ruleId: String) {
        val existing = getAllCustomAliases(context).filterNot { it.id == ruleId }
        persistCustomAliases(context, existing)
    }

    private fun persistCustomAliases(context: Context, rules: List<CallerSpokenAlias>) {
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Clear old fast-lookup keys and rebuild from the structured list
        prefs.all.keys
            .filter { it != KEY_ENABLED && it != KEY_ALIAS_RULES_JSON }
            .forEach { editor.remove(it) }

        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject().apply {
                    put("id", rule.id)
                    put("contactName", rule.contactName)
                    put("phoneNumber", rule.phoneNumber)
                    put("spokenAlias", rule.spokenAlias)
                }
            )
            if (rule.phoneNumber.isNotBlank()) {
                val snapshot = buildP2PNumberSnapshot(rule.phoneNumber)
                editor.putString(rule.phoneNumber.trim(), rule.spokenAlias)
                snapshot.lookupKeys.forEach { key ->
                    if (key.isNotBlank()) editor.putString(key, rule.spokenAlias)
                }
            }
            if (rule.contactName.isNotBlank()) {
                editor.putString(
                    NAME_KEY_PREFIX + rule.contactName.lowercase(Locale.ROOT),
                    rule.spokenAlias
                )
            }
        }
        editor.putString(KEY_ALIAS_RULES_JSON, array.toString())
        editor.apply()
    }

    /**
     * Resolves a custom alias by phone number OR by saved contact name (e.g. "Hamsa" -> "Amma").
     */
    fun getAlias(context: Context, rawOrNormalizedNumber: String, contactName: String? = null): String {
        val prefs = prefsContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. Check phone number lookup keys (+91, 0, 10-digit)
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

        // 2. Check contact name match (exact match or first-name match, e.g. "Hamsa" or "Hamsa Nagaraj")
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
        saveCustomAlias(
            context = context,
            contactName = "",
            phoneNumber = rawOrNormalizedNumber,
            spokenAlias = alias
        )
    }

    fun removeAlias(context: Context, rawOrNormalizedNumber: String) {
        val snapshot = buildP2PNumberSnapshot(rawOrNormalizedNumber)
        val remaining = getAllCustomAliases(context).filterNot { rule ->
            rule.phoneNumber == rawOrNormalizedNumber.trim() ||
                buildP2PNumberSnapshot(rule.phoneNumber).lookupKeys.intersect(snapshot.lookupKeys.toSet()).isNotEmpty()
        }
        persistCustomAliases(context, remaining)
    }

    /**
     * Resolves Custom Spoken Alias ("Amma") -> Saved Contact Name ("Hamsa") -> Spoken Phone Number,
     * and announces "<Name> is calling".
     */
    fun announceIncomingCaller(
        context: Context,
        phoneNumber: String,
        contactName: String? = null
    ) {
        if (!isEnabled(context)) return

        val customAlias = getAlias(context, phoneNumber, contactName)
        val cleanContact = contactName?.trim().orEmpty().takeUnless {
            it.isBlank() || it.equals("Unknown", ignoreCase = true)
        }
        val spokenIdentity = when {
            customAlias.isNotBlank() -> customAlias
            !cleanContact.isNullOrBlank() -> cleanContact
            phoneNumber.isNotBlank() -> formatNumberForSpeech(phoneNumber)
            else -> "Unknown caller"
        }
        announce(context, spokenIdentity, repeatWhileRinging = true)
    }

    /**
     * Plays a single preview announcement immediately when the user tests an alias in Settings.
     */
    fun testAnnouncementPreview(context: Context, spokenAlias: String) {
        val cleaned = spokenAlias.trim().ifBlank { "Amma" }
        val text = if (cleaned.endsWith("calling", ignoreCase = true)) {
            cleaned
        } else {
            "$cleaned is calling"
        }
        val appCtx = context.applicationContext
        appContextRef = appCtx
        pendingTextToSpeak = text
        shouldKeepAnnouncing = false
        mainHandler.removeCallbacks(repeatRunnable)

        mainHandler.post {
            if (!isTtsInitialized || tts == null) {
                init(appCtx)
                return@post
            }
            speakInternal(appCtx, text, forceAudiblePreview = true)
        }
    }

    fun announce(context: Context, nameOrAlias: String, repeatWhileRinging: Boolean = true) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        if (!isEnabled(appCtx)) return

        val cleaned = nameOrAlias.trim()
        if (cleaned.isEmpty()) return

        if (!isRingerAudible(appCtx)) {
            Log.w(TAG, "Skipping caller announcement: device is in silent/vibrate mode")
            return
        }

        val fullText = if (cleaned.endsWith("calling", ignoreCase = true)) {
            cleaned
        } else {
            "$cleaned is calling"
        }

        val isSameTextAlreadyRunning = shouldKeepAnnouncing && pendingTextToSpeak == fullText && isPlaying
        pendingTextToSpeak = fullText
        shouldKeepAnnouncing = repeatWhileRinging

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

    private fun speakInternal(context: Context, text: String, forceAudiblePreview: Boolean = false) {
        if (!forceAudiblePreview && (!shouldKeepAnnouncing || !isRingerAudible(context))) return
        val engine = tts ?: return

        requestAudioFocusWithDucking(context)
        engine.setAudioAttributes(speechAudioAttributes)

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_RING)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "incoming_call_${System.currentTimeMillis()}"
        )
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

/**
 * Drop-in Settings card for enabling/disabling Caller Announcements and configuring
 * custom spoken names (e.g., Contact "Hamsa" -> Announces "Amma is calling").
 */
@Composable
fun CallerAnnouncementSettingsCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var announcementsEnabled by remember {
        mutableStateOf(CallerAnnouncementManager.isEnabled(context))
    }
    var customAliases by remember {
        mutableStateOf(CallerAnnouncementManager.getAllCustomAliases(context))
    }
    var showAddDialog by remember { mutableStateOf(false) }

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
                        text = "Announce Caller Name",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (announcementsEnabled) {
                            "Speaks caller name or custom nickname out loud when ringing"
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Custom Spoken Names",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Example: Saved as \"Hamsa\" → says \"Amma is calling\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { showAddDialog = true },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add custom spoken name",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add", fontSize = 13.sp)
                }
            }

            if (customAliases.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    customAliases.forEach { rule ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    val label = buildString {
                                        if (rule.contactName.isNotBlank()) append(rule.contactName)
                                        if (rule.phoneNumber.isNotBlank()) {
                                            if (isNotEmpty()) append(" • ")
                                            append(rule.phoneNumber)
                                        }
                                    }
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Says: \"${rule.spokenAlias} is calling\"",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

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
                                        contentDescription = "Delete custom name",
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

    if (showAddDialog) {
        var contactQueryOrName by remember { mutableStateOf("") }
        var phoneNumberInput by remember { mutableStateOf("") }
        var spokenAliasInput by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text("Custom Caller Announcement", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Pick a saved contact (like Hamsa) or enter their name/number, then set what the voice should announce (like Amma).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = contactQueryOrName,
                        onValueChange = { contactQueryOrName = it },
                        label = { Text("Saved Contact Name (e.g. Hamsa)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = phoneNumberInput,
                        onValueChange = { phoneNumberInput = it },
                        label = { Text("Phone Number (optional if name set)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = spokenAliasInput,
                        onValueChange = { spokenAliasInput = it },
                        label = { Text("Announce As (e.g. Amma)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
                            Text("Preview: \"${spokenAliasInput.trim()} is calling\"")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = spokenAliasInput.isNotBlank() &&
                        (contactQueryOrName.isNotBlank() || phoneNumberInput.isNotBlank()),
                    onClick = {
                        CallerAnnouncementManager.saveCustomAlias(
                            context = context,
                            contactName = contactQueryOrName,
                            phoneNumber = phoneNumberInput,
                            spokenAlias = spokenAliasInput
                        )
                        customAliases = CallerAnnouncementManager.getAllCustomAliases(context)
                        showAddDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
