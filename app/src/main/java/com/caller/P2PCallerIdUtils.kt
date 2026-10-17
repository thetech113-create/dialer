package com.caller

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

const val P2P_FORENSIC_TAG = "P2P_FORENSIC"
const val P2P_CACHE_UPDATED_ACTION = "com.caller.P2P_CACHE_UPDATED"
const val P2P_SMS_SENT_ACTION = "com.caller.P2P_SMS_SENT"
const val P2P_SMS_DELIVERED_ACTION = "com.caller.P2P_SMS_DELIVERED"
const val P2P_SMS_PORT: Short = 8901

private const val P2P_CACHE_PREFS = "p2p_name_cache"
private const val P2P_CAPABILITY_PREFS = "p2p_peer_capability"
private const val P2P_TRANSPORT_PREFS = "p2p_transport_preference"
private const val P2P_PROFILE_PREFS = "local_user_profile"
private const val P2P_UNKNOWN_NAME = "Unknown"
internal const val P2P_PROFILE_CACHE_TTL_MS = 24L * 60 * 60 * 1000
private const val P2P_CAPABILITY_TTL_MS = 30L * 24 * 60 * 60 * 1000
private const val P2P_TRANSPORT_TTL_MS = 30L * 24 * 60 * 60 * 1000
private const val P2P_SEND_DEDUPE_WINDOW_MS = 15_000L
private const val P2P_TEXT_SMS_PREFIX = "//P2P:"
private const val EXTRA_P2P_DESTINATION = "p2p_destination"
private const val EXTRA_P2P_TYPE = "p2p_type"
private const val EXTRA_P2P_TRIGGER = "p2p_trigger"
private const val EXTRA_P2P_PAYLOAD = "p2p_payload"
const val EXTRA_P2P_TRANSPORT = "p2p_transport"

enum class P2PMessageType {
    CALLER,
    REPLY
}

enum class P2PPeerCapability {
    UNKNOWN,
    NO_APP,
    HAS_APP_CONFIRMED
}

enum class P2PTransportMode {
    UNKNOWN,
    DATA,
    TEXT,
    UNSUPPORTED
}

fun isNumberVideoCallCapable(phoneNumber: String): Boolean {
    val digits = phoneNumber.filter { it.isDigit() }
    if (digits.isEmpty()) return true

    // Shortcodes, emergency numbers, and carrier service numbers (length < 7) do not support ViLTE video calls
    if (digits.length < 7) {
        return false
    }

    // Toll-Free and Service number prefixes that never support ViLTE video calls
    if (digits.startsWith("1800") ||
        digits.startsWith("1860") ||
        digits.startsWith("0800") ||
        digits.startsWith("800") ||
        digits.startsWith("888") ||
        digits.startsWith("877") ||
        digits.startsWith("866") ||
        digits.startsWith("855") ||
        digits.startsWith("844") ||
        digits.startsWith("833") ||
        digits.startsWith("155")
    ) {
        return false
    }

    return true
}

data class P2PNumberSnapshot(
    val raw: String,
    val digits: String,
    val normalized: String,
    val last10: String,
    val lookupKeys: List<String>,
    val smsDestination: String
)

data class P2PParsedPayload(
    val type: P2PMessageType,
    val declaredNumber: String,
    val profileName: String,
    val timestamp: Long,
    val rawPayload: String
)

data class P2PCacheLookupResult(
    val name: String?,
    val source: String,
    val matchedKey: String?,
    val lookupKeysTried: List<String>,
    val rawCacheValue: String?
)

data class LocalP2PProfile(
    val name: String,
    val source: String,
    val declaredNumber: String
)

data class P2PPeerCapabilityResult(
    val capability: P2PPeerCapability,
    val source: String,
    val matchedKey: String?,
    val lookupKeysTried: List<String>,
    val rawCacheValue: String?
)

data class P2PTransportPreferenceResult(
    val transportMode: P2PTransportMode,
    val source: String,
    val matchedKey: String?,
    val lookupKeysTried: List<String>,
    val rawCacheValue: String?
)

data class P2PSendResult(
    val requested: Boolean,
    val blockedReason: String? = null,
    val retryable: Boolean = false,
    val destination: P2PNumberSnapshot? = null,
    val payload: String? = null
)

object P2PSendDedupeRegistry {
    private val recentSends = ConcurrentHashMap<String, Long>()

    fun shouldSuppress(key: String, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        recentSends.entries.removeIf { now - it.value > windowMs }
        val lastSent = recentSends[key]
        return if (lastSent != null && now - lastSent < windowMs) {
            true
        } else {
            recentSends[key] = now
            false
        }
    }

    fun clear(key: String) {
        recentSends.remove(key)
    }
}

private object P2PLogThrottleRegistry {
    private val recentLogs = ConcurrentHashMap<String, Long>()

    fun shouldLog(key: String, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        recentLogs.entries.removeIf { now - it.value > windowMs }
        val lastLogged = recentLogs[key]
        if (lastLogged != null && now - lastLogged < windowMs) {
            return false
        }
        recentLogs[key] = now
        return true
    }
}

fun logP2PThrottled(level: Int, dedupeKey: String, message: String, windowMs: Long = 5000L) {
    if (!P2PLogThrottleRegistry.shouldLog(dedupeKey, windowMs)) return
    when (level) {
        Log.ERROR -> Log.e(P2P_FORENSIC_TAG, message)
        Log.INFO -> Log.i(P2P_FORENSIC_TAG, message)
        Log.DEBUG -> Log.d(P2P_FORENSIC_TAG, message)
        else -> Log.w(P2P_FORENSIC_TAG, message)
    }
}

internal fun p2pContext(context: Context): Context {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext() ?: context
    } else {
        context
    }
}

fun sanitizeP2PDisplayName(rawName: String): String {
    val allowed = rawName.trim().filter { char ->
        char.isLetterOrDigit() || char.isWhitespace() || char in "._-'()"
    }
    return allowed.replace(Regex("\\s+"), " ").take(64)
}

fun buildP2PNumberSnapshot(rawNumber: String): P2PNumberSnapshot {
    val raw = rawNumber.trim()
    val digits = raw.filter { it.isDigit() }
    val normalized = when {
        digits.length == 11 && digits.startsWith("1") -> digits.drop(1)
        digits.length >= 10 -> digits.takeLast(10)
        else -> digits
    }
    val last10 = if (digits.length >= 10) digits.takeLast(10) else digits
    val lookupKeys = LinkedHashSet<String>().apply {
        if (digits.isNotEmpty()) add(digits)
        if (normalized.isNotEmpty()) add(normalized)
        if (last10.isNotEmpty()) add(last10)
    }.toList()
    val smsDestination = when {
        raw.startsWith("+") && digits.isNotEmpty() -> "+$digits"
        digits.isNotEmpty() -> digits
        else -> raw.replace(Regex("[^\\d*#+]"), "")
    }
    return P2PNumberSnapshot(
        raw = raw,
        digits = digits,
        normalized = normalized,
        last10 = last10,
        lookupKeys = lookupKeys,
        smsDestination = smsDestination
    )
}

fun maskP2PNumberForLog(value: String): String {
    return maskPhoneNumber(value)
}

fun maskP2PDedupeKeyForLog(value: String): String {
    if (value.isBlank()) return ""
    return Regex("\\+?\\d{3,}").replace(value) { maskPhoneNumber(it.value) }
}

fun maskP2PKeysForLog(keys: Collection<String>): List<String> {
    return keys.map { maskP2PNumberForLog(it) }
}

fun maskP2PDisplayValueForLog(value: String): String {
    return if (containsPhoneLikeDigits(value)) {
        maskP2PNumberForLog(value)
    } else {
        value
    }
}

fun maskP2PCacheValueForLog(rawValue: String): String {
    val parts = rawValue.split("|")
    val name = parts.getOrNull(0).orEmpty()
    val expiry = parts.getOrNull(1).orEmpty()
    return buildString {
        append(maskP2PDisplayValueForLog(name))
        if (expiry.isNotBlank()) {
            append("|")
            append(expiry)
        }
    }
}

fun maskP2PPayloadForLog(rawPayload: String): String {
    val parsedPayload = parseP2PPayload(rawPayload)
    if (parsedPayload != null) {
        return "${parsedPayload.type.name}|${maskP2PNumberForLog(parsedPayload.declaredNumber)}|${maskP2PDisplayValueForLog(parsedPayload.profileName)}|${parsedPayload.timestamp}"
    }
    return Regex("\\+?\\d{5,}").replace(rawPayload) { match ->
        maskP2PNumberForLog(match.value)
    }
}

fun logP2PNumberTrace(stage: String, snapshot: P2PNumberSnapshot) {
    Log.w(
        P2P_FORENSIC_TAG,
        "number_trace stage=$stage raw=${maskP2PNumberForLog(snapshot.raw)} digits=${maskP2PNumberForLog(snapshot.digits)} normalized=${maskP2PNumberForLog(snapshot.normalized)} last10=${maskP2PNumberForLog(snapshot.last10)} lookupKeys=${maskP2PKeysForLog(snapshot.lookupKeys)}"
    )
}

private fun persistP2PPeerCapability(
    context: Context,
    keysToSave: Collection<String>,
    capability: P2PPeerCapability,
    source: String,
    trigger: String,
    timestamp: Long
): Boolean {
    if (keysToSave.isEmpty()) {
        Log.w(
            P2P_FORENSIC_TAG,
            "capability_save_blocked trigger=$trigger capability=${capability.name} source=$source reason=no_keys"
        )
        return false
    }

    val safeContext = p2pContext(context)
    val prefs = safeContext.getSharedPreferences(P2P_CAPABILITY_PREFS, Context.MODE_PRIVATE)
    val cacheValue = "${capability.name}|$source|${timestamp + P2P_CAPABILITY_TTL_MS}"
    val editor = prefs.edit()
    keysToSave.forEach { key ->
        editor.putString(key, cacheValue)
        Log.w(
            P2P_FORENSIC_TAG,
            "capability_save_prepare trigger=$trigger capability=${capability.name} source=$source key=${maskP2PNumberForLog(key)} value=$cacheValue"
        )
    }
    val commitResult = editor.commit()
    Log.w(
        P2P_FORENSIC_TAG,
        "capability_save_result trigger=$trigger capability=${capability.name} source=$source keys=${maskP2PKeysForLog(keysToSave)} commit=$commitResult"
    )
    return commitResult
}

fun storeP2PPeerCapability(
    context: Context,
    phoneNumberRaw: String,
    capability: P2PPeerCapability,
    source: String,
    trigger: String,
    timestamp: Long = System.currentTimeMillis()
): Boolean {
    val snapshot = buildP2PNumberSnapshot(phoneNumberRaw)
    return persistP2PPeerCapability(
        context = context,
        keysToSave = snapshot.lookupKeys,
        capability = capability,
        source = source,
        trigger = trigger,
        timestamp = timestamp
    )
}

private fun persistP2PTransportPreference(
    context: Context,
    keysToSave: Collection<String>,
    transportMode: P2PTransportMode,
    source: String,
    trigger: String,
    timestamp: Long
): Boolean {
    if (keysToSave.isEmpty()) {
        Log.w(
            P2P_FORENSIC_TAG,
            "transport_pref_save_blocked trigger=$trigger transport=${transportMode.name} source=$source reason=no_keys"
        )
        return false
    }

    val safeContext = p2pContext(context)
    val prefs = safeContext.getSharedPreferences(P2P_TRANSPORT_PREFS, Context.MODE_PRIVATE)
    val cacheValue = "${transportMode.name}|$source|${timestamp + P2P_TRANSPORT_TTL_MS}"
    val editor = prefs.edit()
    keysToSave.forEach { key ->
        editor.putString(key, cacheValue)
        Log.w(
            P2P_FORENSIC_TAG,
            "transport_pref_save_prepare trigger=$trigger transport=${transportMode.name} source=$source key=${maskP2PNumberForLog(key)} value=$cacheValue"
        )
    }
    val commitResult = editor.commit()
    Log.w(
        P2P_FORENSIC_TAG,
        "transport_pref_save_result trigger=$trigger transport=${transportMode.name} source=$source keys=${maskP2PKeysForLog(keysToSave)} commit=$commitResult"
    )
    return commitResult
}

fun storeP2PTransportPreference(
    context: Context,
    phoneNumberRaw: String,
    transportMode: P2PTransportMode,
    source: String,
    trigger: String,
    timestamp: Long = System.currentTimeMillis()
): Boolean {
    val snapshot = buildP2PNumberSnapshot(phoneNumberRaw)
    return persistP2PTransportPreference(
        context = context,
        keysToSave = snapshot.lookupKeys,
        transportMode = transportMode,
        source = source,
        trigger = trigger,
        timestamp = timestamp
    )
}

fun markP2PSmsUnsupportedPeer(
    context: Context,
    phoneNumberRaw: String,
    source: String,
    trigger: String,
    timestamp: Long = System.currentTimeMillis()
): Boolean {
    val snapshot = buildP2PNumberSnapshot(phoneNumberRaw)
    if (snapshot.lookupKeys.isEmpty()) {
        Log.w(
            P2P_FORENSIC_TAG,
            "sms_unsupported_mark_skipped trigger=$trigger source=$source reason=no_keys raw=${maskP2PNumberForLog(phoneNumberRaw)}"
        )
        return false
    }

    val saved = persistP2PTransportPreference(
        context = context,
        keysToSave = snapshot.lookupKeys,
        transportMode = P2PTransportMode.UNSUPPORTED,
        source = source,
        trigger = trigger,
        timestamp = timestamp
    )
    Log.w(
        P2P_FORENSIC_TAG,
        "sms_unsupported_mark_result trigger=$trigger source=$source raw=${maskP2PNumberForLog(phoneNumberRaw)} keys=${maskP2PKeysForLog(snapshot.lookupKeys)} saved=$saved"
    )
    return saved
}

fun lookupP2PPeerCapability(context: Context, phoneNumber: String, trigger: String): P2PPeerCapabilityResult {
    val safeContext = p2pContext(context)
    val prefs = safeContext.getSharedPreferences(P2P_CAPABILITY_PREFS, Context.MODE_PRIVATE)
    val snapshot = buildP2PNumberSnapshot(phoneNumber)

    val keysTried = mutableListOf<String>()
    for (key in snapshot.lookupKeys) {
        keysTried.add(key)
        val rawValue = prefs.getString(key, null)
        if (rawValue.isNullOrBlank()) {
            logP2PThrottled(
                level = Log.DEBUG,
                dedupeKey = "capability_lookup_miss:$trigger:$key",
                message = "capability_lookup trigger=$trigger key=${maskP2PNumberForLog(key)} value=null"
            )
            continue
        }

        val parts = rawValue.split("|")
        val capability = runCatching {
            P2PPeerCapability.valueOf(parts.getOrNull(0).orEmpty())
        }.getOrDefault(P2PPeerCapability.UNKNOWN)
        val source = parts.getOrNull(1).orEmpty().ifBlank { "unknown" }
        val expiry = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val isValid = capability != P2PPeerCapability.UNKNOWN && System.currentTimeMillis() < expiry
        Log.w(
            P2P_FORENSIC_TAG,
            "capability_validation trigger=$trigger key=${maskP2PNumberForLog(key)} capability=${capability.name} source=$source expiry=$expiry valid=$isValid now=${System.currentTimeMillis()}"
        )

        if (isValid) {
            return P2PPeerCapabilityResult(
                capability = capability,
                source = source,
                matchedKey = key,
                lookupKeysTried = keysTried.toList(),
                rawCacheValue = rawValue
            )
        }

        prefs.edit().remove(key).commit()
        Log.w(
            P2P_FORENSIC_TAG,
            "capability_cleanup trigger=$trigger removedKey=${maskP2PNumberForLog(key)} reason=expired_or_invalid"
        )
    }

    return P2PPeerCapabilityResult(
        capability = P2PPeerCapability.UNKNOWN,
        source = "none",
        matchedKey = null,
        lookupKeysTried = keysTried,
        rawCacheValue = null
    )
}

fun lookupP2PTransportPreference(context: Context, phoneNumber: String, trigger: String): P2PTransportPreferenceResult {
    val safeContext = p2pContext(context)
    val prefs = safeContext.getSharedPreferences(P2P_TRANSPORT_PREFS, Context.MODE_PRIVATE)
    val snapshot = buildP2PNumberSnapshot(phoneNumber)

    val keysTried = mutableListOf<String>()
    for (key in snapshot.lookupKeys) {
        keysTried.add(key)
        val rawValue = prefs.getString(key, null)
        if (rawValue.isNullOrBlank()) {
            logP2PThrottled(
                level = Log.DEBUG,
                dedupeKey = "transport_pref_lookup_miss:$trigger:$key",
                message = "transport_pref_lookup trigger=$trigger key=${maskP2PNumberForLog(key)} value=null"
            )
            continue
        }

        val parts = rawValue.split("|")
        val transportMode = runCatching {
            P2PTransportMode.valueOf(parts.getOrNull(0).orEmpty())
        }.getOrDefault(P2PTransportMode.UNKNOWN)
        val source = parts.getOrNull(1).orEmpty().ifBlank { "unknown" }
        val expiry = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val isValid = transportMode != P2PTransportMode.UNKNOWN && System.currentTimeMillis() < expiry
        Log.w(
            P2P_FORENSIC_TAG,
            "transport_pref_validation trigger=$trigger key=${maskP2PNumberForLog(key)} transport=${transportMode.name} source=$source expiry=$expiry valid=$isValid now=${System.currentTimeMillis()}"
        )

        if (isValid) {
            return P2PTransportPreferenceResult(
                transportMode = transportMode,
                source = source,
                matchedKey = key,
                lookupKeysTried = keysTried.toList(),
                rawCacheValue = rawValue
            )
        }

        prefs.edit().remove(key).commit()
        Log.w(
            P2P_FORENSIC_TAG,
            "transport_pref_cleanup trigger=$trigger removedKey=${maskP2PNumberForLog(key)} reason=expired_or_invalid"
        )
    }

    return P2PTransportPreferenceResult(
        transportMode = P2PTransportMode.UNKNOWN,
        source = "none",
        matchedKey = null,
        lookupKeysTried = keysTried,
        rawCacheValue = null
    )
}

private fun logP2PSmsDecision(
    destinationSnapshot: P2PNumberSnapshot,
    messageType: P2PMessageType,
    trigger: String,
    capabilityResult: P2PPeerCapabilityResult,
    action: String,
    reason: String
) {
    val decisionNumber = destinationSnapshot.smsDestination.ifBlank { destinationSnapshot.raw }
    Log.w(
        P2P_FORENSIC_TAG,
        "[SMS_DECISION] number=${maskP2PNumberForLog(decisionNumber)} capability=${capabilityResult.capability.name} source=${capabilityResult.source} action=$action reason=$reason trigger=$trigger type=${messageType.name} matchedKey=${maskP2PNumberForLog(capabilityResult.matchedKey.orEmpty())}"
    )
}

fun buildP2PPayload(type: P2PMessageType, declaredNumber: String, profileName: String, timestamp: Long = System.currentTimeMillis()): String {
    val sanitizedName = sanitizeP2PDisplayName(profileName)
    val declaredSnapshot = buildP2PNumberSnapshot(declaredNumber)
    return "${type.name}|${declaredSnapshot.digits}|$sanitizedName|$timestamp"
}

fun buildP2PTextSmsPayload(rawPayload: String): String {
    return "$P2P_TEXT_SMS_PREFIX$rawPayload"
}

fun unwrapP2PTextSmsPayload(rawBody: String): String? {
    val trimmed = rawBody.trim()
    if (!trimmed.startsWith(P2P_TEXT_SMS_PREFIX)) {
        return null
    }
    return trimmed.removePrefix(P2P_TEXT_SMS_PREFIX).trim()
}

private fun buildP2PSendDedupeKey(
    destinationSnapshot: P2PNumberSnapshot,
    messageType: P2PMessageType
): String {
    val numberKey = destinationSnapshot.normalized.ifBlank {
        destinationSnapshot.last10.ifBlank {
            destinationSnapshot.smsDestination.ifBlank { destinationSnapshot.raw }
        }
    }
    return "send:${messageType.name}:$numberKey"
}

private fun buildP2PTextFallbackDedupeKey(
    destinationSnapshot: P2PNumberSnapshot,
    messageType: P2PMessageType,
    payload: String
): String {
    val numberKey = destinationSnapshot.normalized.ifBlank {
        destinationSnapshot.last10.ifBlank {
            destinationSnapshot.smsDestination.ifBlank { destinationSnapshot.raw }
        }
    }
    return "text:${messageType.name}:$numberKey:${payload.hashCode()}"
}

fun parseP2PPayload(rawPayload: String): P2PParsedPayload? {
    val trimmed = rawPayload.trim()
    val parts = trimmed.split("|")
    val type = when (parts.firstOrNull()) {
        P2PMessageType.CALLER.name -> P2PMessageType.CALLER
        P2PMessageType.REPLY.name -> P2PMessageType.REPLY
        else -> null
    } ?: return null

    return if (parts.size >= 4) {
        P2PParsedPayload(
            type = type,
            declaredNumber = buildP2PNumberSnapshot(parts[1]).digits,
            profileName = sanitizeP2PDisplayName(parts[2]),
            timestamp = parts[3].toLongOrNull() ?: 0L,
            rawPayload = trimmed
        )
    } else if (parts.size >= 2) {
        P2PParsedPayload(
            type = type,
            declaredNumber = "",
            profileName = sanitizeP2PDisplayName(parts[1]),
            timestamp = 0L,
            rawPayload = trimmed
        )
    } else {
        null
    }
}

fun resolveLocalP2PProfile(context: Context): LocalP2PProfile? {
    val safeContext = p2pContext(context)
    val dpsPrefs = safeContext.getSharedPreferences(P2P_PROFILE_PREFS, Context.MODE_PRIVATE)
    val isUserUnlocked = try {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        userManager.isUserUnlocked
    } catch (_: Exception) {
        true
    }

    val dpsName = sanitizeP2PDisplayName(dpsPrefs.getString("my_name", "") ?: "")
    if (dpsName.isNotBlank() && dpsName != P2P_UNKNOWN_NAME) {
        val declaredNumber = resolveDeviceDeclaredNumber(context)
        Log.w(
            P2P_FORENSIC_TAG,
            "profile_source selected=DPS name=${maskP2PDisplayValueForLog(dpsName)} declaredNumber=${maskP2PNumberForLog(declaredNumber)} userUnlocked=$isUserUnlocked"
        )
        return LocalP2PProfile(dpsName, "local_user_profile_dps", declaredNumber)
    }

    if (!isUserUnlocked) {
        Log.w(
            P2P_FORENSIC_TAG,
            "profile_source selected=NONE reason=credential_storage_locked skip=CPS"
        )
        return null
    }

    val cpsPrefs = try {
        context.getSharedPreferences(P2P_PROFILE_PREFS, Context.MODE_PRIVATE)
    } catch (e: Exception) {
        Log.e(
            P2P_FORENSIC_TAG,
            "profile_source selected=NONE reason=cps_open_failed error=${e.message}",
            e
        )
        null
    }

    if (cpsPrefs == null) {
        return null
    }

    val cpsName = sanitizeP2PDisplayName(cpsPrefs.getString("my_name", "") ?: "")
    if (cpsName.isNotBlank() && cpsName != P2P_UNKNOWN_NAME) {
        val declaredNumber = resolveDeviceDeclaredNumber(context)
        Log.w(
            P2P_FORENSIC_TAG,
            "profile_source selected=CPS name=${maskP2PDisplayValueForLog(cpsName)} declaredNumber=${maskP2PNumberForLog(declaredNumber)}"
        )
        return LocalP2PProfile(cpsName, "local_user_profile_cps", declaredNumber)
    }

    Log.w(P2P_FORENSIC_TAG, "profile_source selected=NONE reason=blank_or_unknown")
    return null
}

private fun resolveDeviceDeclaredNumber(context: Context): String {
    return try {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val raw = telephonyManager.line1Number ?: ""
        buildP2PNumberSnapshot(raw).digits
    } catch (_: Exception) {
        ""
    }
}

fun lookupP2PCache(context: Context, phoneNumber: String, trigger: String): P2PCacheLookupResult {
    val safeContext = p2pContext(context)
    val prefs = safeContext.getSharedPreferences(P2P_CACHE_PREFS, Context.MODE_PRIVATE)
    val snapshot = buildP2PNumberSnapshot(phoneNumber)
    logP2PNumberTrace("lookup:$trigger", snapshot)

    val keysTried = mutableListOf<String>()
    for (key in snapshot.lookupKeys) {
        keysTried.add(key)
        val rawValue = prefs.getString(key, null)
        if (rawValue.isNullOrBlank()) {
            logP2PThrottled(
                level = Log.DEBUG,
                dedupeKey = "cache_lookup_miss:$trigger:$key",
                message = "cache_lookup trigger=$trigger key=${maskP2PNumberForLog(key)} value=null"
            )
            continue
        }
        Log.w(
            P2P_FORENSIC_TAG,
            "cache_lookup trigger=$trigger key=${maskP2PNumberForLog(key)} value=${maskP2PCacheValueForLog(rawValue)}"
        )

        val parts = rawValue.split("|")
        val name = sanitizeP2PDisplayName(parts.getOrNull(0) ?: "")
        val expiry = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val isValid = name.isNotBlank() && name != P2P_UNKNOWN_NAME && System.currentTimeMillis() < expiry
        Log.w(
            P2P_FORENSIC_TAG,
            "cache_validation trigger=$trigger key=${maskP2PNumberForLog(key)} parsedName=${maskP2PDisplayValueForLog(name)} expiry=$expiry valid=$isValid now=${System.currentTimeMillis()}"
        )

        if (isValid) {
            val source = when {
                key == snapshot.digits -> "exact"
                key == snapshot.last10 -> "last10"
                else -> "fallback"
            }
            return P2PCacheLookupResult(
                name = name,
                source = source,
                matchedKey = key,
                lookupKeysTried = keysTried.toList(),
                rawCacheValue = rawValue
            )
        }

        prefs.edit().remove(key).commit()
        Log.w(
            P2P_FORENSIC_TAG,
            "cache_cleanup trigger=$trigger removedKey=${maskP2PNumberForLog(key)} reason=expired_or_invalid"
        )
    }

    return P2PCacheLookupResult(
        name = null,
        source = "miss",
        matchedKey = null,
        lookupKeysTried = keysTried,
        rawCacheValue = null
    )
}

/**
 * Name received over P2P for [phoneNumber], but only if it arrived within [maxAgeMs].
 * Used by Recents so a caller's shared name is shown for a limited window after the call.
 */
fun lookupRecentP2PName(context: Context, phoneNumber: String, maxAgeMs: Long): String? {
    val lookup = lookupP2PCache(context, phoneNumber, "Recents")
    val name = lookup.name ?: return null
    val expiry = lookup.rawCacheValue?.split("|")?.getOrNull(1)?.toLongOrNull() ?: return null
    val receivedAt = expiry - P2P_PROFILE_CACHE_TTL_MS
    return name.takeIf { System.currentTimeMillis() - receivedAt <= maxAgeMs }
}

fun storeP2PProfile(
    context: Context,
    transportNumberRaw: String,
    declaredNumberRaw: String,
    profileNameRaw: String,
    transportMode: P2PTransportMode,
    payloadType: P2PMessageType,
    trigger: String,
    receivedTimestamp: Long = System.currentTimeMillis()
): Boolean {
    val safeContext = p2pContext(context)
    val prefs = safeContext.getSharedPreferences(P2P_CACHE_PREFS, Context.MODE_PRIVATE)
    val transportSnapshot = buildP2PNumberSnapshot(transportNumberRaw)
    val declaredSnapshot = buildP2PNumberSnapshot(declaredNumberRaw)
    val profileName = sanitizeP2PDisplayName(profileNameRaw)

    logP2PNumberTrace("store_transport:$trigger", transportSnapshot)
    logP2PNumberTrace("store_declared:$trigger", declaredSnapshot)

    if (profileName.isBlank() || profileName == P2P_UNKNOWN_NAME) {
        Log.w(
            P2P_FORENSIC_TAG,
            "cache_save_blocked trigger=$trigger type=${payloadType.name} reason=invalid_name name=${maskP2PDisplayValueForLog(profileName)}"
        )
        return false
    }

    val keysToSave = LinkedHashSet<String>().apply {
        addAll(transportSnapshot.lookupKeys)
        addAll(declaredSnapshot.lookupKeys)
    }.filter { it.isNotBlank() }

    if (keysToSave.isEmpty()) {
        Log.w(
            P2P_FORENSIC_TAG,
            "cache_save_blocked trigger=$trigger type=${payloadType.name} reason=no_keys transportRaw=${maskP2PNumberForLog(transportNumberRaw)} declaredRaw=${maskP2PNumberForLog(declaredNumberRaw)}"
        )
        return false
    }

    val cacheValue = "$profileName|${receivedTimestamp + P2P_PROFILE_CACHE_TTL_MS}"
    val editor = prefs.edit()
    keysToSave.forEach { key ->
        editor.putString(key, cacheValue)
        Log.w(
            P2P_FORENSIC_TAG,
            "cache_save_prepare trigger=$trigger type=${payloadType.name} key=${maskP2PNumberForLog(key)} value=${maskP2PCacheValueForLog(cacheValue)}"
        )
    }
    val commitResult = editor.commit()
    Log.w(
        P2P_FORENSIC_TAG,
        "cache_save_result trigger=$trigger type=${payloadType.name} keys=${maskP2PKeysForLog(keysToSave)} commit=$commitResult name=${maskP2PDisplayValueForLog(profileName)}"
    )

    if (commitResult) {
        persistP2PPeerCapability(
            context = safeContext,
            keysToSave = keysToSave,
            capability = P2PPeerCapability.HAS_APP_CONFIRMED,
            source = "cache_handshake",
            trigger = "$trigger:${payloadType.name}",
            timestamp = receivedTimestamp
        )
        persistP2PTransportPreference(
            context = safeContext,
            keysToSave = keysToSave,
            transportMode = transportMode,
            source = "receiver_${transportMode.name.lowercase()}",
            trigger = "$trigger:${payloadType.name}",
            timestamp = receivedTimestamp
        )
    }

    DirectBootCallManager.invalidateLookupCacheKeys(keysToSave, "P2P_CACHE_WRITE:$trigger")

    Intent(P2P_CACHE_UPDATED_ACTION).apply {
        `package` = context.packageName
        putExtra("transport_number_raw", transportSnapshot.raw)
        putExtra("transport_number_normalized", transportSnapshot.normalized)
        putExtra("declared_number_raw", declaredSnapshot.raw)
        putExtra("declared_number_normalized", declaredSnapshot.normalized)
        putExtra("profile_name", profileName)
        putExtra("payload_type", payloadType.name)
        context.sendBroadcast(this)
    }

    return commitResult
}

@Suppress("DEPRECATION")
private fun sendP2PTextTransport(
    context: Context,
    destinationSnapshot: P2PNumberSnapshot,
    messageType: P2PMessageType,
    trigger: String,
    payload: String,
    reason: String
): P2PSendResult {
    return try {
        val smsManager = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        val textBody = buildP2PTextSmsPayload(payload)
        val sentIntent = PendingIntent.getBroadcast(
            context,
            "TEXT:${messageType.name}:${destinationSnapshot.smsDestination}:sent".hashCode(),
            Intent(P2P_SMS_SENT_ACTION).apply {
                `package` = context.packageName
                putExtra(EXTRA_P2P_DESTINATION, destinationSnapshot.raw)
                putExtra(EXTRA_P2P_TYPE, messageType.name)
                putExtra(EXTRA_P2P_TRIGGER, trigger)
                putExtra(EXTRA_P2P_PAYLOAD, payload)
                putExtra(EXTRA_P2P_TRANSPORT, P2PTransportMode.TEXT.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val deliveredIntent = PendingIntent.getBroadcast(
            context,
            "TEXT:${messageType.name}:${destinationSnapshot.smsDestination}:delivered".hashCode(),
            Intent(P2P_SMS_DELIVERED_ACTION).apply {
                `package` = context.packageName
                putExtra(EXTRA_P2P_DESTINATION, destinationSnapshot.raw)
                putExtra(EXTRA_P2P_TYPE, messageType.name)
                putExtra(EXTRA_P2P_TRIGGER, trigger)
                putExtra(EXTRA_P2P_PAYLOAD, payload)
                putExtra(EXTRA_P2P_TRANSPORT, P2PTransportMode.TEXT.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        smsManager.sendTextMessage(
            destinationSnapshot.smsDestination,
            null,
            textBody,
            sentIntent,
            deliveredIntent
        )

        Log.w(
            P2P_FORENSIC_TAG,
            "send_requested trigger=$trigger type=${messageType.name} transport=${P2PTransportMode.TEXT.name} dest=${maskP2PNumberForLog(destinationSnapshot.smsDestination)} reason=$reason payloadSummary=${maskP2PPayloadForLog(payload)}"
        )

        P2PSendResult(
            requested = true,
            destination = destinationSnapshot,
            payload = payload
        )
    } catch (e: Exception) {
        Log.e(
            P2P_FORENSIC_TAG,
            "send_failed trigger=$trigger type=${messageType.name} transport=${P2PTransportMode.TEXT.name} dest=${maskP2PNumberForLog(destinationSnapshot.smsDestination)} error=${e.message}",
            e
        )
        P2PSendResult(
            requested = false,
            blockedReason = "text_send_exception:${e.javaClass.simpleName}",
            destination = destinationSnapshot,
            payload = payload
        )
    }
}

fun sendP2PTextFallbackMessage(
    context: Context,
    destinationNumberRaw: String,
    messageType: P2PMessageType,
    trigger: String,
    payload: String
): P2PSendResult {
    val destinationSnapshot = buildP2PNumberSnapshot(destinationNumberRaw)
    if (destinationSnapshot.smsDestination.isBlank()) {
        Log.w(
            P2P_FORENSIC_TAG,
            "text_fallback_blocked trigger=$trigger type=${messageType.name} reason=blank_destination raw=${maskP2PNumberForLog(destinationNumberRaw)}"
        )
        return P2PSendResult(
            requested = false,
            blockedReason = "blank_destination",
            destination = destinationSnapshot,
            payload = payload
        )
    }

    if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
        Log.w(
            P2P_FORENSIC_TAG,
            "text_fallback_blocked trigger=$trigger type=${messageType.name} reason=missing_permission permission=SEND_SMS dest=${maskP2PNumberForLog(destinationSnapshot.smsDestination)}"
        )
        return P2PSendResult(
            requested = false,
            blockedReason = "missing_permission",
            destination = destinationSnapshot,
            payload = payload
        )
    }

    val fallbackDedupeKey = buildP2PTextFallbackDedupeKey(destinationSnapshot, messageType, payload)
    if (P2PSendDedupeRegistry.shouldSuppress(fallbackDedupeKey, P2P_SEND_DEDUPE_WINDOW_MS)) {
        Log.w(
            P2P_FORENSIC_TAG,
            "text_fallback_blocked trigger=$trigger type=${messageType.name} reason=deduplicated dest=${maskP2PNumberForLog(destinationSnapshot.smsDestination)} dedupeKey=${maskP2PDedupeKeyForLog(fallbackDedupeKey)}"
        )
        return P2PSendResult(
            requested = false,
            blockedReason = "deduplicated",
            destination = destinationSnapshot,
            payload = payload
        )
    }

    val sendResult = sendP2PTextTransport(
        context = context,
        destinationSnapshot = destinationSnapshot,
        messageType = messageType,
        trigger = trigger,
        payload = payload,
        reason = "DATA_FALLBACK"
    )
    if (sendResult.requested) {
        storeP2PTransportPreference(
            context = context,
            phoneNumberRaw = destinationSnapshot.raw,
            transportMode = P2PTransportMode.TEXT,
            source = "data_fallback",
            trigger = trigger
        )
    } else {
        P2PSendDedupeRegistry.clear(fallbackDedupeKey)
    }
    return sendResult
}

@Suppress("DEPRECATION")
fun sendP2PProfileMessage(
    context: Context,
    destinationNumberRaw: String,
    messageType: P2PMessageType,
    trigger: String
): P2PSendResult {
    val destinationSnapshot = buildP2PNumberSnapshot(destinationNumberRaw)
    logP2PNumberTrace("send:$trigger:${messageType.name}", destinationSnapshot)

    val cleanNum = destinationSnapshot.digits
    val isKnownUnsupported = cleanNum == String(Base64.decode("MTk4", 0)) ||
        cleanNum == String(Base64.decode("MTkxNQ==", 0)) ||
        cleanNum.contains(String(Base64.decode("MTgwMDExNDAwMA==", 0))) ||
        cleanNum.contains(String(Base64.decode("MTgwMDEwMzYyODY=", 0))) ||
        cleanNum.contains(String(Base64.decode("MTgwMDEyMzQ=", 0))) ||
        cleanNum.contains(String(Base64.decode("MTgwMDIxMDA=", 0))) ||
        cleanNum.contains(String(Base64.decode("MTgwMDExMjIxMQ==", 0))) ||
        cleanNum.contains(String(Base64.decode("MTgwMDQyNTM4MDA=", 0))) ||
        cleanNum.contains(String(Base64.decode("MTgwMDExMTEwOQ==", 0)))

    if (isKnownUnsupported) {
        markP2PSmsUnsupportedPeer(
            context = context,
            phoneNumberRaw = destinationNumberRaw,
            source = "known_unsupported",
            trigger = trigger
        )
        Log.w(
            P2P_FORENSIC_TAG,
            "send_blocked trigger=$trigger type=${messageType.name} reason=known_unsupported_number dest=${maskP2PNumberForLog(destinationSnapshot.smsDestination)}"
        )
        return P2PSendResult(
            requested = false,
            blockedReason = "known_unsupported_number",
            destination = destinationSnapshot
        )
    }

    val capabilityResult = lookupP2PPeerCapability(
        context = context,
        phoneNumber = destinationNumberRaw,
        trigger = "send_gate:$trigger:${messageType.name}"
    )

    if (destinationSnapshot.smsDestination.isBlank()) {
        logP2PSmsDecision(
            destinationSnapshot = destinationSnapshot,
            messageType = messageType,
            trigger = trigger,
            capabilityResult = capabilityResult,
            action = "BLOCKED",
            reason = "BLANK_DESTINATION"
        )
        Log.w(
            P2P_FORENSIC_TAG,
            "send_blocked trigger=$trigger type=${messageType.name} reason=blank_destination raw=${maskP2PNumberForLog(destinationNumberRaw)}"
        )
        return P2PSendResult(
            requested = false,
            blockedReason = "blank_destination",
            destination = destinationSnapshot
        )
    }

    if (capabilityResult.capability == P2PPeerCapability.NO_APP) {
        logP2PSmsDecision(
            destinationSnapshot = destinationSnapshot,
            messageType = messageType,
            trigger = trigger,
            capabilityResult = capabilityResult,
            action = "BLOCKED",
            reason = "PEER_MARKED_NO_APP"
        )
        Log.w(P2P_FORENSIC_TAG, "SMS BLOCKED: peer explicitly marked as no-app")
        return P2PSendResult(
            requested = false,
            blockedReason = "capability_blocked:${capabilityResult.capability.name}",
            destination = destinationSnapshot
        )
    }

    if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
        logP2PSmsDecision(
            destinationSnapshot = destinationSnapshot,
            messageType = messageType,
            trigger = trigger,
            capabilityResult = capabilityResult,
            action = "BLOCKED",
            reason = "MISSING_PERMISSION"
        )
        Log.w(
            P2P_FORENSIC_TAG,
            "send_blocked trigger=$trigger type=${messageType.name} reason=missing_permission permission=SEND_SMS dest=${maskP2PNumberForLog(destinationSnapshot.smsDestination)}"
        )
        return P2PSendResult(
            requested = false,
            blockedReason = "missing_permission",
            retryable = false,
            destination = destinationSnapshot
        )
    }

    val localProfile = resolveLocalP2PProfile(context)
    // For REPLY messages, use fallback if profile locked (device not unlocked yet)
    // REPLY should go through to indicate app is present, even if name not ready
    val finalProfile = localProfile ?: if (messageType == P2PMessageType.REPLY) {
        try {
            val declaredNumber = resolveDeviceDeclaredNumber(context)
            LocalP2PProfile(name = "App User", source = "fallback_locked_device", declaredNumber = declaredNumber)
        } catch (e: Exception) {
            Log.w(P2P_FORENSIC_TAG, "send_reply_fallback_failed error=${e.message}")
            null
        }
    } else {
        null
    }

    if (finalProfile == null) {
        logP2PSmsDecision(
            destinationSnapshot = destinationSnapshot,
            messageType = messageType,
            trigger = trigger,
            capabilityResult = capabilityResult,
            action = "BLOCKED",
            reason = "MISSING_PROFILE"
        )
        Log.w(
            P2P_FORENSIC_TAG,
            "send_blocked trigger=$trigger type=${messageType.name} reason=missing_profile dest=${maskP2PNumberForLog(destinationSnapshot.smsDestination)}"
        )
        return P2PSendResult(
            requested = false,
            blockedReason = "missing_profile",
            retryable = false,
            destination = destinationSnapshot
        )
    }

    val payload = buildP2PPayload(messageType, finalProfile.declaredNumber, finalProfile.name)
    val transportPreference = lookupP2PTransportPreference(
        context = context,
        phoneNumber = destinationNumberRaw,
        trigger = "send_transport:$trigger:${messageType.name}"
    )
    Log.w(
        P2P_FORENSIC_TAG,
        "send_prepare trigger=$trigger type=${messageType.name} profileSource=${finalProfile.source} destRaw=${maskP2PNumberForLog(destinationSnapshot.raw)} destNormalized=${maskP2PNumberForLog(destinationSnapshot.normalized)} destLast10=${maskP2PNumberForLog(destinationSnapshot.last10)} transportPref=${transportPreference.transportMode.name} transportSource=${transportPreference.source} payload=${maskP2PPayloadForLog(payload)}"
    )
    val dedupeKey = buildP2PSendDedupeKey(destinationSnapshot, messageType)
    // REPLY messages ALWAYS go through - they're responses and shouldn't be deduplicated
    val shouldCheckDedup = messageType != P2PMessageType.REPLY
    if (shouldCheckDedup && P2PSendDedupeRegistry.shouldSuppress(dedupeKey, P2P_SEND_DEDUPE_WINDOW_MS)) {
        logP2PSmsDecision(
            destinationSnapshot = destinationSnapshot,
            messageType = messageType,
            trigger = trigger,
            capabilityResult = capabilityResult,
            action = "BLOCKED",
            reason = "DEDUPLICATED"
        )
        Log.w(
            P2P_FORENSIC_TAG,
            "send_blocked trigger=$trigger type=${messageType.name} reason=deduplicated dest=${maskP2PNumberForLog(destinationSnapshot.smsDestination)} dedupeKey=${maskP2PDedupeKeyForLog(dedupeKey)} windowMs=$P2P_SEND_DEDUPE_WINDOW_MS"
        )
        return P2PSendResult(
            requested = false,
            blockedReason = "deduplicated",
            destination = destinationSnapshot,
            payload = payload
        )
    }
    if (transportPreference.transportMode == P2PTransportMode.TEXT) {
        logP2PSmsDecision(
            destinationSnapshot = destinationSnapshot,
            messageType = messageType,
            trigger = trigger,
            capabilityResult = capabilityResult,
            action = "ALLOWED",
            reason = "TEXT_TRANSPORT_PREFERRED"
        )
        val textResult = sendP2PTextTransport(
            context = context,
            destinationSnapshot = destinationSnapshot,
            messageType = messageType,
            trigger = trigger,
            payload = payload,
            reason = "PREFERRED_TRANSPORT"
        )
        if (!textResult.requested) {
            P2PSendDedupeRegistry.clear(dedupeKey)
        }
        return textResult
    }
    if (transportPreference.transportMode == P2PTransportMode.UNSUPPORTED) {
        logP2PSmsDecision(
            destinationSnapshot = destinationSnapshot,
            messageType = messageType,
            trigger = trigger,
            capabilityResult = capabilityResult,
            action = "BLOCKED",
            reason = "PEER_MARKED_SMS_UNSUPPORTED"
        )
        Log.w(
            P2P_FORENSIC_TAG,
            "send_blocked trigger=$trigger type=${messageType.name} reason=sms_unsupported transportSource=${transportPreference.source} dest=${maskP2PNumberForLog(destinationSnapshot.smsDestination)}"
        )
        return P2PSendResult(
            requested = false,
            blockedReason = "transport_blocked:${transportPreference.transportMode.name}",
            destination = destinationSnapshot,
            payload = payload
        )
    }
    val allowReason = if (capabilityResult.capability == P2PPeerCapability.HAS_APP_CONFIRMED) {
        "CONFIRMED_APP_USER"
    } else {
        "UNKNOWN_BOOTSTRAP_ALLOWED"
    }
    logP2PSmsDecision(
        destinationSnapshot = destinationSnapshot,
        messageType = messageType,
        trigger = trigger,
        capabilityResult = capabilityResult,
        action = "ALLOWED",
        reason = allowReason
    )

    return try {
        val smsManager = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        val sentIntent = PendingIntent.getBroadcast(
            context,
            "${messageType.name}:${destinationSnapshot.smsDestination}:sent".hashCode(),
            Intent(P2P_SMS_SENT_ACTION).apply {
                `package` = context.packageName
                putExtra(EXTRA_P2P_DESTINATION, destinationSnapshot.raw)
                putExtra(EXTRA_P2P_TYPE, messageType.name)
                putExtra(EXTRA_P2P_TRIGGER, trigger)
                putExtra(EXTRA_P2P_PAYLOAD, payload)
                putExtra(EXTRA_P2P_TRANSPORT, P2PTransportMode.DATA.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val deliveredIntent = PendingIntent.getBroadcast(
            context,
            "${messageType.name}:${destinationSnapshot.smsDestination}:delivered".hashCode(),
            Intent(P2P_SMS_DELIVERED_ACTION).apply {
                `package` = context.packageName
                putExtra(EXTRA_P2P_DESTINATION, destinationSnapshot.raw)
                putExtra(EXTRA_P2P_TYPE, messageType.name)
                putExtra(EXTRA_P2P_TRIGGER, trigger)
                putExtra(EXTRA_P2P_PAYLOAD, payload)
                putExtra(EXTRA_P2P_TRANSPORT, P2PTransportMode.DATA.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        smsManager.sendDataMessage(
            destinationSnapshot.smsDestination,
            null,
            P2P_SMS_PORT,
            payload.toByteArray(Charsets.UTF_8),
            sentIntent,
            deliveredIntent
        )

        Log.w(
            P2P_FORENSIC_TAG,
            "send_requested trigger=$trigger type=${messageType.name} transport=${P2PTransportMode.DATA.name} dest=${maskP2PNumberForLog(destinationSnapshot.smsDestination)} port=$P2P_SMS_PORT payloadSummary=${maskP2PPayloadForLog(payload)}"
        )

        P2PSendResult(
            requested = true,
            destination = destinationSnapshot,
            payload = payload
        )
    } catch (e: Exception) {
        P2PSendDedupeRegistry.clear(dedupeKey)
        Log.e(
            P2P_FORENSIC_TAG,
            "send_failed trigger=$trigger type=${messageType.name} transport=${P2PTransportMode.DATA.name} dest=${maskP2PNumberForLog(destinationSnapshot.smsDestination)} error=${e.message}",
            e
        )
        logP2PSmsDecision(
            destinationSnapshot = destinationSnapshot,
            messageType = messageType,
            trigger = trigger,
            capabilityResult = capabilityResult,
            action = "FAILED",
            reason = "SEND_EXCEPTION_${e.javaClass.simpleName}"
        )

        val transportPref = lookupP2PTransportPreference(
            context = context,
            phoneNumber = destinationNumberRaw,
            trigger = "send_data_exception:$trigger"
        )

        if (transportPref.transportMode == P2PTransportMode.UNSUPPORTED) {
            Log.w(
                P2P_FORENSIC_TAG,
                "sms_fallback_blocked trigger=$trigger type=${messageType.name} reason=marked_unsupported dest=${maskP2PNumberForLog(destinationSnapshot.smsDestination)}"
            )
            return P2PSendResult(
                requested = false,
                blockedReason = "send_exception_no_sms_fallback:${e.javaClass.simpleName}",
                destination = destinationSnapshot,
                payload = payload
            )
        } else {
            val fallbackResult = sendP2PTextFallbackMessage(
                context = context,
                destinationNumberRaw = destinationSnapshot.raw,
                messageType = messageType,
                trigger = "$trigger:data_exception",
                payload = payload
            )
            return if (fallbackResult.requested) {
                fallbackResult
            } else {
                P2PSendResult(
                    requested = false,
                    blockedReason = "send_exception:${e.javaClass.simpleName}",
                    destination = destinationSnapshot,
                    payload = payload
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Backup that survives "Clear data": a small text file in Download/CallerApp/ holding the user's
// own profile name and the received P2P names (with their original expiry). Clear data wipes the
// app's private storage, but not this shared file.
//
// Format (names are sanitized, so they never contain '|' or line breaks):
//   CALLER_P2P_BACKUP|1
//   PROFILE|<my name>
//   P2P|<lookup key>|<name>|<expiry millis>
// ---------------------------------------------------------------------------------------------

private const val P2P_BACKUP_FILE_NAME = "caller_p2p_backup.txt"
private const val P2P_BACKUP_HEADER = "CALLER_P2P_BACKUP|1"

data class P2PBackupRestoreResult(
    val profileName: String?,
    val namesRestored: Int
)

private fun p2pBackupRelativePath(): String = android.os.Environment.DIRECTORY_DOWNLOADS + "/CallerApp/"

/** The user's own name plus every P2P name that has not expired yet. */
fun buildP2PBackupText(context: Context): String {
    val safeContext = p2pContext(context)
    val profileName = sanitizeP2PDisplayName(
        safeContext.getSharedPreferences(P2P_PROFILE_PREFS, Context.MODE_PRIVATE).getString("my_name", null)
            ?: runCatching {
                context.getSharedPreferences(P2P_PROFILE_PREFS, Context.MODE_PRIVATE).getString("my_name", null)
            }.getOrNull()
            ?: ""
    )
    // Only the user's own profile name is backed up. P2P names (other people) come back only from a
    // real call, not from a Clear-data restore (the user's choice).
    return buildString {
        appendLine(P2P_BACKUP_HEADER)
        if (profileName.isNotBlank() && profileName != P2P_UNKNOWN_NAME) {
            appendLine("PROFILE|$profileName")
        }
    }
}

/**
 * Restores a backup made by [buildP2PBackupText]. Never overwrites a profile name the user already
 * has, skips expired P2P names, and keeps a newer saved name over an older backed-up one.
 */
fun applyP2PBackupText(context: Context, text: String): P2PBackupRestoreResult? {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.firstOrNull() != P2P_BACKUP_HEADER) {
        Log.w(P2P_FORENSIC_TAG, "p2p_backup_restore result=rejected reason=bad_header")
        return null
    }
    val safeContext = p2pContext(context)
    val now = System.currentTimeMillis()
    var restoredProfile: String? = null
    var restoredNames = 0

    val dpsProfilePrefs = safeContext.getSharedPreferences(P2P_PROFILE_PREFS, Context.MODE_PRIVATE)
    val cachePrefs = safeContext.getSharedPreferences(P2P_CACHE_PREFS, Context.MODE_PRIVATE)
    val cacheEditor = cachePrefs.edit()

    lines.drop(1).forEach { line ->
        val parts = line.split("|")
        when (parts.firstOrNull()) {
            "PROFILE" -> {
                val name = sanitizeP2PDisplayName(parts.getOrNull(1).orEmpty())
                if (name.isNotBlank() && name != P2P_UNKNOWN_NAME &&
                    dpsProfilePrefs.getString("my_name", "").isNullOrBlank()
                ) {
                    dpsProfilePrefs.edit().putString("my_name", name).commit()
                    runCatching {
                        context.getSharedPreferences(P2P_PROFILE_PREFS, Context.MODE_PRIVATE)
                            .edit().putString("my_name", name).commit()
                    }
                    restoredProfile = name
                }
            }
            // "P2P|…" lines in older backups are ignored: P2P names are not restored after Clear data.
        }
    }
    cacheEditor.commit()
    Log.w(
        P2P_FORENSIC_TAG,
        "p2p_backup_restore result=ok profileRestored=${restoredProfile != null} namesRestored=$restoredNames"
    )
    return P2PBackupRestoreResult(restoredProfile, restoredNames)
}

// Block Store (Google Play services) keeps one small blob outside the app's data, so it survives
// Clear data without any special permission (Play-policy friendly). Limit is 4 KB per entry.
private const val P2P_BLOCKSTORE_MAX_BYTES = 4000
private const val P2P_BLOCKSTORE_TIMEOUT_S = 10L
private val p2pBackupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

/** Keeps the header and profile line, then as many P2P names as fit, longest-lived first. */
private fun fitP2PBackupForBlockStore(text: String): ByteArray {
    val lines = text.lines().filter { it.isNotBlank() }
    val fixed = lines.filterNot { it.startsWith("P2P|") }
    val names = lines.filter { it.startsWith("P2P|") }
        .sortedByDescending { it.substringAfterLast('|').toLongOrNull() ?: 0L }
    val out = StringBuilder()
    fixed.forEach { out.appendLine(it) }
    for (line in names) {
        if ((out.length + line.length + 1) * 3 > P2P_BLOCKSTORE_MAX_BYTES) break // UTF-8 worst case
        out.appendLine(line)
    }
    return out.toString().toByteArray(Charsets.UTF_8)
}

private fun readBlockStoreP2PBackup(context: Context): String? = runCatching {
    val client = com.google.android.gms.auth.blockstore.Blockstore.getClient(context)
    val key = com.google.android.gms.auth.blockstore.BlockstoreClient.DEFAULT_BYTES_DATA_KEY
    val request = com.google.android.gms.auth.blockstore.RetrieveBytesRequest.Builder()
        .setKeys(listOf(key))
        .build()
    val response = com.google.android.gms.tasks.Tasks.await(
        client.retrieveBytes(request), P2P_BLOCKSTORE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS
    )
    response.blockstoreDataMap[key]?.bytes?.toString(Charsets.UTF_8)
}.onFailure { e ->
    Log.e(P2P_FORENSIC_TAG, "p2p_backup_blockstore_read result=failed error=${e.message}")
}.getOrNull()

private fun writeBlockStoreP2PBackup(context: Context, bytes: ByteArray) {
    runCatching {
        val client = com.google.android.gms.auth.blockstore.Blockstore.getClient(context)
        val builder = com.google.android.gms.auth.blockstore.StoreBytesData.Builder().setBytes(bytes)
        // Also back up to the Google account (reinstall / new phone) when it can be end-to-end encrypted.
        val e2ee = runCatching {
            com.google.android.gms.tasks.Tasks.await(
                client.isEndToEndEncryptionAvailable, P2P_BLOCKSTORE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS
            )
        }.getOrDefault(false)
        if (e2ee) builder.setShouldBackupToCloud(true)
        val stored = com.google.android.gms.tasks.Tasks.await(
            client.storeBytes(builder.build()), P2P_BLOCKSTORE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS
        )
        Log.w(P2P_FORENSIC_TAG, "p2p_backup_blockstore_save result=ok bytes=$stored cloud=$e2ee")
    }.onFailure { e ->
        Log.e(P2P_FORENSIC_TAG, "p2p_backup_blockstore_save result=failed error=${e.message}")
    }
}

// App-owned Android account: the system keeps its data in the accounts database, outside the app's data.
private const val P2P_BACKUP_ACCOUNT_TYPE = "com.caller.backup"
private const val P2P_BACKUP_ACCOUNT_KEY = "p2p_backup"

private fun p2pBackupAccount(context: Context, create: Boolean): android.accounts.Account? {
    val manager = android.accounts.AccountManager.get(context)
    manager.getAccountsByType(P2P_BACKUP_ACCOUNT_TYPE).firstOrNull()?.let { return it }
    if (!create) return null
    val account = android.accounts.Account("Caller backup", P2P_BACKUP_ACCOUNT_TYPE)
    return if (manager.addAccountExplicitly(account, null, null)) account else null
}

private fun readAccountP2PBackup(context: Context): String? = runCatching {
    val account = p2pBackupAccount(context, create = false)
    val text = account?.let { android.accounts.AccountManager.get(context).getUserData(it, P2P_BACKUP_ACCOUNT_KEY) }
    Log.w(P2P_FORENSIC_TAG, "p2p_backup_account_read accountFound=${account != null} bytes=${text?.length ?: 0}")
    text
}.onFailure { e ->
    Log.e(P2P_FORENSIC_TAG, "p2p_backup_account_read result=failed error=${e.message}")
}.getOrNull()

private fun writeAccountP2PBackup(context: Context, text: String) {
    runCatching {
        val account = p2pBackupAccount(context, create = true) ?: error("addAccountExplicitly failed")
        android.accounts.AccountManager.get(context).setUserData(account, P2P_BACKUP_ACCOUNT_KEY, text)
        Log.w(P2P_FORENSIC_TAG, "p2p_backup_account_save result=ok bytes=${text.length}")
    }.onFailure { e ->
        Log.e(P2P_FORENSIC_TAG, "p2p_backup_account_save result=failed error=${e.message}")
    }
}

/** This app's backup file in Download/CallerApp/, if the app can see it (Android 10+). */
private fun findOwnP2PBackupUri(context: Context): android.net.Uri? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
    return runCatching {
        context.contentResolver.query(
            collection,
            arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(P2P_BACKUP_FILE_NAME, p2pBackupRelativePath()),
            "${android.provider.MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
    }.getOrNull()
}

/**
 * Saves the backup to Block Store (automatic restore after Clear data) and to
 * Download/CallerApp/caller_p2p_backup.txt (manual "Restore from Backup" fallback).
 */
fun saveP2PBackupFile(context: Context) {
    val appContext = context.applicationContext
    p2pBackupExecutor.execute {
        // Profile empty = data was cleared and not restored yet. Merge the stored backup first so this
        // write (e.g. a P2P name arriving before the app is opened) can't replace it with less.
        val profileMissing = p2pContext(appContext).getSharedPreferences(P2P_PROFILE_PREFS, Context.MODE_PRIVATE)
            .getString("my_name", "").isNullOrBlank()
        if (profileMissing) {
            (readAccountP2PBackup(appContext) ?: readBlockStoreP2PBackup(appContext))
                ?.let { applyP2PBackupText(appContext, it) }
        }
        val backupText = buildP2PBackupText(appContext)
        writeAccountP2PBackup(appContext, backupText)
        writeBlockStoreP2PBackup(appContext, fitP2PBackupForBlockStore(backupText))
        saveP2PBackupDownloadFile(appContext)
    }
}

private fun saveP2PBackupDownloadFile(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    runCatching {
        val text = buildP2PBackupText(context)
        val resolver = context.contentResolver
        val uri = findOwnP2PBackupUri(context) ?: resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, P2P_BACKUP_FILE_NAME)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, p2pBackupRelativePath())
            }
        ) ?: return
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        Log.w(P2P_FORENSIC_TAG, "p2p_backup_save result=ok bytes=${text.length}")
    }.onFailure { e ->
        Log.e(P2P_FORENSIC_TAG, "p2p_backup_save result=failed error=${e.message}")
    }
}

/** After Clear data: restores from the backup file automatically if the app can still read it. */
fun autoRestoreP2PBackup(context: Context): P2PBackupRestoreResult? {
    // App account first (kept by the system across Clear data), then Block Store (kept across
    // uninstall/reinstall; Play services deletes it on Clear data). Must be called off the main thread.
    readAccountP2PBackup(context)?.let { text ->
        applyP2PBackupText(context, text)?.let { result ->
            Log.w(P2P_FORENSIC_TAG, "p2p_backup_auto_restore result=ok source=account")
            return result
        }
    }
    readBlockStoreP2PBackup(context)?.let { text ->
        applyP2PBackupText(context, text)?.let { result ->
            Log.w(P2P_FORENSIC_TAG, "p2p_backup_auto_restore result=ok source=blockstore")
            return result
        }
    }
    val uri = findOwnP2PBackupUri(context) ?: run {
        Log.w(P2P_FORENSIC_TAG, "p2p_backup_auto_restore result=no_readable_file")
        return null
    }
    return runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return null
        applyP2PBackupText(context, text)
    }.onFailure { e ->
        Log.e(P2P_FORENSIC_TAG, "p2p_backup_auto_restore result=failed error=${e.message}")
    }.getOrNull()
}

fun describeTransportStatus(resultCode: Int): String {
    return when (resultCode) {
        android.app.Activity.RESULT_OK -> "RESULT_OK"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE"
        SmsManager.RESULT_ERROR_NULL_PDU -> "RESULT_ERROR_NULL_PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF"
        else -> "RESULT_$resultCode"
    }
}
