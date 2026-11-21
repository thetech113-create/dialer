package com.caller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.ContactsContract
import android.provider.ContactsContract.Directory
import android.provider.ContactsContract.PhoneLookup
import android.telephony.TelephonyManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Names for numbers that are not saved, in Google Phone's order (its shipped app, checked 2026-11-21): other contact
// lists (work profile, work/email account directories), the network's name (CNAP, read in MyInCallService), then
// Google Maps business listings through our server. Plus where the number is from, offline.

/** Settings switch for the Google Maps business names (on by default, like Google Phone's caller ID). */
internal const val BUSINESS_CALLER_ID_PREF = "business_caller_id_enabled"

internal fun isBusinessCallerIdOn(context: Context): Boolean =
    p2pContext(context).getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .getBoolean(BUSINESS_CALLER_ID_PREF, true)

/**
 * Names found for this app run only. Google Maps terms allow showing a business name but not storing it, so nothing
 * here is written to disk; Recents keeps showing the number.
 */
internal object UnsavedCallerNames {
    private class Entry(val name: String, val fromGoogleMaps: Boolean)

    private val names = ConcurrentHashMap<String, Entry>()

    private fun key(number: String): String = number.filter(Char::isDigit).takeLast(10)

    fun put(number: String, name: String, fromGoogleMaps: Boolean) {
        val key = key(number)
        if (key.isNotEmpty() && name.isNotBlank()) names[key] = Entry(name.trim(), fromGoogleMaps)
    }

    fun nameFor(number: String): String? = names[key(number)]?.name

    fun isGoogleMapsName(number: String, name: String): Boolean =
        names[key(number)]?.let { it.fromGoogleMaps && it.name == name } == true
}

private const val TAG = "UnsavedCallerLookup"
private val lookupExecutor = Executors.newSingleThreadExecutor()

/**
 * Once per call, for a number with no saved or P2P name: other contact lists first, then (only if the network sent no
 * name either) Google Maps. A name found refreshes the call screen.
 */
internal fun lookUpUnsavedCaller(context: Context, remoteNumber: String, networkName: String) {
    if (remoteNumber.isBlank()) return
    val appContext = context.applicationContext
    lookupExecutor.execute {
        runCatching {
            if (hasKnownName(appContext, remoteNumber)) return@runCatching
            val directoryName = findDirectoryContactName(appContext, remoteNumber)
            Log.w(TAG, "directory_lookup result=${if (directoryName != null) "hit" else "empty"}")
            if (directoryName != null) {
                UnsavedCallerNames.put(remoteNumber, directoryName, fromGoogleMaps = false)
                refreshCallScreenName()
                return@runCatching
            }
            // Some networks send the number itself as the caller name; only a real name skips Google Maps.
            if (networkName.none(Char::isLetter) && isBusinessCallerIdOn(appContext)) {
                requestGoogleMapsBusinessName(appContext, remoteNumber)
            }
        }.onFailure { Log.e(TAG, "lookup_failed error=${it.message}") }
    }
}

/** A saved contact or a P2P name (from the server or silent SMS). */
internal fun hasKnownName(context: Context, number: String): Boolean {
    if (DirectBootCallManager.getDpsContactName(context, number) != "Unknown") return true
    if (!canReadContacts(context)) return false
    return queryName(context, Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))) != null
}

internal fun refreshCallScreenName() {
    Handler(Looper.getMainLooper()).post { MyInCallService.getInstance()?.refreshCallerIdentityAfterP2P() }
}

private fun canReadContacts(context: Context): Boolean =
    context.getSystemService(UserManager::class.java)?.isUserUnlocked != false &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

private fun queryName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
    }
}.getOrNull()

/** Work-profile contacts, then each work/email account directory (Exchange, Google Workspace), as Google Phone does. */
internal fun findDirectoryContactName(context: Context, number: String): String? {
    if (!canReadContacts(context)) return null
    fun lookupUri(directoryId: Long?): Uri = PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI.buildUpon()
        .appendPath(number)
        .apply { directoryId?.let { appendQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY, it.toString()) } }
        .build()
    queryName(context, lookupUri(null))?.let { return it }
    val remoteDirectoryIds = runCatching {
        context.contentResolver.query(Directory.ENTERPRISE_CONTENT_URI, arrayOf(Directory._ID), null, null, null)
            ?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
    }.getOrNull().orEmpty().filter(Directory::isRemoteDirectoryId)
    return remoteDirectoryIds.firstNotNullOfOrNull { queryName(context, lookupUri(it)) }
}

/** The country this phone is in ("IN"), for numbers dialled without a country code. */
internal fun phoneRegion(context: Context): String {
    val telephony = context.getSystemService(TelephonyManager::class.java)
    return listOf(telephony?.networkCountryIso, telephony?.simCountryIso)
        .firstOrNull { !it.isNullOrBlank() }?.uppercase(Locale.ROOT) ?: "IN"
}

/** A number as Google Maps needs it: E.164, its country code and its country, or null if it is not a real number. */
internal data class InternationalNumber(val e164: String, val countryCode: Int, val region: String)

internal fun internationalNumber(number: String, phoneRegion: String): InternationalNumber? = runCatching {
    val util = PhoneNumberUtil.getInstance()
    val parsed = util.parse(number, phoneRegion)
    if (!util.isValidNumber(parsed)) return null
    InternationalNumber(
        e164 = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164),
        countryCode = parsed.countryCode,
        region = util.getRegionCodeForNumber(parsed) ?: return null
    )
}.getOrNull()

/** "Bangalore, Karnataka" for a landline, "India" for most Indian mobiles, the country for a foreign number. */
internal fun numberLocationFor(number: String, phoneRegion: String, locale: Locale = Locale.getDefault()): String =
    runCatching {
        val util = PhoneNumberUtil.getInstance()
        val parsed = util.parse(number, phoneRegion)
        if (!util.isValidNumber(parsed)) return ""
        PhoneNumberOfflineGeocoder.getInstance().getDescriptionForNumber(parsed, locale, phoneRegion)
    }.getOrDefault("")

private val locationCache = ConcurrentHashMap<String, String>()

internal fun numberLocation(context: Context, number: String): String {
    if (number.isBlank()) return ""
    return locationCache.getOrPut(number) { numberLocationFor(number, phoneRegion(context)) }
}

/** Where [number] is from, worked out off the main thread (the first lookup reads the geocoder's data file). */
@Composable
internal fun rememberNumberLocation(number: String): String {
    val context = LocalContext.current.applicationContext
    val location by produceState(initialValue = locationCache[number].orEmpty(), number) {
        value = withContext(Dispatchers.IO) { numberLocation(context, number) }
    }
    return location
}
