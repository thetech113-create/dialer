package com.caller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private const val PREFS_NAME = "caller_announcement_prefs"
    private const val KEY_ENABLED = "announcements_enabled"
    private const val KEY_ALIAS_RULES_JSON = "custom_alias_rules_v2_json"
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
     * Loads all configured per-contact announcement rules.
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

    /**
     * Saves or updates a custom announcement setting for a selected saved contact (including all of their numbers).
     */
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
            // Only index active/enabled rules into fast lookup keys
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

    /**
     * Resolves a custom alias by phone number OR by saved contact name (e.g. "Hamsa" -> "Amma").
     */
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

    /**
     * Resolves Custom Spoken Alias ("Amma") -> Saved Contact Name ("Hamsa") -> Spoken Phone Number,
     * and announces "Incoming call from <Name>".
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
     * Plays a single preview ("Incoming call from Amma") when testing in Settings.
     */
    fun testAnnouncementPreview(context: Context, spokenAlias: String) {
        val cleaned = spokenAlias.trim().ifBlank { "Amma" }
        val text = buildIncomingCallAnnouncementPhrase(cleaned)
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
            Log.w(TAG, "Skipping caller announcement: silent/vibrate mode")
            return
        }

        val fullText = buildIncomingCallAnnouncementPhrase(cleaned)

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

/**
 * Settings Card:
 * - Master Enable/Disable Switch
 * - Multiple Saved Contacts with individual custom spoken names & per-contact ON/OFF toggles
 * - Tap-to-Select Saved Contact Picker (no typing contact names or phone numbers)
 */
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
                    onClick = {
                        editingRule = null
                        isDialogOpen = true
                    },
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
                        // STEP 1: Tap to select directly from saved contacts (no manual typing of contact details)
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
                                                // Pre-fill existing alias if this contact already has a rule
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
                        // STEP 2: Saved contact is selected -> Choose preset chip or enter spoken name
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
