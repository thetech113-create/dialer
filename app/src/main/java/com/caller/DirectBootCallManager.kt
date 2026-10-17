@file:Suppress(
    "all", 
    "WARNINGS", 
    "unused", 
    "UNUSED_VARIABLE",
    "UnusedImport", 
    "RemoveRedundantQualifierName", 
    "NAME_SHADOWING", 
    "LiftReturnOrAssignment", 
    "RegExpSimplifiable",
    "UNNECESSARY_SAFE_CALL",
    "UNNECESSARY_NOT_NULL_ASSERTION",
    "UselessCallOnNotNull",
    "RedundantSafeCall",
    "DEPRECATION",
    "IfThenToIfEmpty",
    "ifEmpty {...}",
    "SpellCheckingInspection",
    "Typo"
)

package com.caller

import com.caller.logging.UltraForensicLogger
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Data class to represent the result of Direct Boot restoration operations
 */
data class DirectBootRestorationResult(
    val isSuccessful: Boolean,
    val message: String
)

/**
 * DirectBootCallManager - Centralized manager for Direct Boot aware call operations
 * 
 * This class ensures that all call operations are fully compatible with Android's Direct Boot mode,
 * which allows the device to run in a limited state before the user unlocks it after a restart.
 * 
 * Key Features:
 * - Uses device-protected storage exclusively for Direct Boot compatibility
 * - Provides multiple backup and restoration strategies for call state
 * - Ensures call notifications and state survive force restarts and encrypted storage scenarios
 * - Implements comprehensive logging for debugging restart issues
 * - Caches contact data to prevent CPS access during DPS phase
 */
object DirectBootCallManager {
    
    private const val TAG = "DirectBootCallManager"
    private const val PREF_NAME = "direct_boot_calls"
    private const val CALL_STATE_FILE = "direct_boot_call_state.json"
    
    // Performance optimization: Prevent duplicate contact queries
    private val contactQueryCache = mutableMapOf<String, String>()
    private val queryTimestamps = mutableMapOf<String, Long>()
    private const val QUERY_DEBOUNCE_MS = 5000L // 5 seconds debounce
    
    // Add this helper method to check if device-protected storage is available
    fun isDeviceProtectedStorageAvailable(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.isDeviceProtectedStorage ||
                ContextCompat.createDeviceProtectedStorageContext(context) != null
        } else {
            // For older versions, assume it's not available
            false
        }
    }
    
    // Get device-protected context
    fun getDirectBootContext(context: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else {
            // For older versions, return the regular context
            context
        }
    }
    
    // CRITICAL FIX: Bulk cache all contacts so they exist BEFORE the phone locks
    fun syncContactsToDps(context: Context, contacts: List<Contact>) {
        try {
            if (!isDeviceProtectedStorageAvailable(context)) return
            val deviceContext = getDirectBootContext(context)
            val prefs = deviceContext.getSharedPreferences("dps_bulk_contacts", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.clear()
            contacts.forEach { contact ->
                val numberSnapshot = buildP2PNumberSnapshot(contact.phoneNumber)
                if (numberSnapshot.lookupKeys.isNotEmpty() && contact.name != "Unknown") {
                    numberSnapshot.lookupKeys.forEach { key ->
                        editor.putString(key, contact.name)
                    }
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "dps_contact_sync raw=${maskP2PNumberForLog(contact.phoneNumber)} normalized=${maskP2PNumberForLog(numberSnapshot.normalized)} last10=${maskP2PNumberForLog(numberSnapshot.last10)} keys=${maskP2PKeysForLog(numberSnapshot.lookupKeys)} name=${maskP2PDisplayValueForLog(contact.name)}"
                    )
                }
            }
            editor.apply()
        } catch (e: Exception) {}
    }
    
    /**
     * Cache contact data to device-protected storage to prevent CPS access during DPS
     * @return true if caching was successful, false otherwise
     */
    private fun cacheContactData(context: Context, phoneNumber: String, passedContactName: String = ""): Boolean {
        return try {
            val deviceContext = getDirectBootContext(context)
            val cacheDir = deviceContext.filesDir
            val outputFile = File(cacheDir, "contact_$phoneNumber.cache")
            
            // CRITICAL FIX: Prevent CPS Contacts DB access by using the securely passed name
            val finalName = if (passedContactName.isNotEmpty() && passedContactName != "Unknown") passedContactName else "Unknown"
            
            // Delete existing cache file if it exists
            if (outputFile.exists()) {
                outputFile.delete()
            }
            
            // Write contact data to cache file with DPS metadata
            FileOutputStream(outputFile).use { outputStream ->
                val contactData = "$finalName|DPS_CACHED|${System.currentTimeMillis()}"
                outputStream.write(contactData.toByteArray())
                outputStream.fd.sync() // Ensure all data is written to disk
                android.util.Log.d(TAG, "Successfully cached contact data for: ${maskPhoneNumber(phoneNumber)} (DPS: ${maskP2PDisplayValueForLog(finalName)})")
                true
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to cache contact data for ${maskPhoneNumber(phoneNumber)}: ${e.message}", e)
            false
        }
    }
    
    /**
     * Get cached contact data from device-protected storage with DPS metadata
     */
    private fun getCachedContactData(context: Context, phoneNumber: String): String? {
        return try {
            val deviceContext = getDirectBootContext(context)
            val prefs = deviceContext.getSharedPreferences("dps_bulk_contacts", Context.MODE_PRIVATE)
            val numberSnapshot = buildP2PNumberSnapshot(phoneNumber)
            logP2PNumberTrace("dps_contact_lookup", numberSnapshot)

            var bulkName: String? = null
            for (key in numberSnapshot.lookupKeys) {
                val value = prefs.getString(key, null)
                Log.w(
                    P2P_FORENSIC_TAG,
                    "dps_contact_lookup key=${maskP2PNumberForLog(key)} value=${value?.let(::maskP2PDisplayValueForLog)}"
                )
                if (!value.isNullOrBlank() && value != "Unknown") {
                    bulkName = value
                    break
                }
            }

            if (bulkName != null && bulkName != "Unknown") return bulkName

            val cacheFile = File(deviceContext.filesDir, "contact_$phoneNumber.cache")
            
            if (cacheFile.exists()) {
                val cachedData = cacheFile.readText()
                val parts = cachedData.split("|")
                val contactName = parts.getOrNull(0) ?: "Unknown"
                if (contactName != "Unknown") return contactName
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun invalidateLookupCacheKeys(rawKeys: Collection<String>, reason: String) {
        val resolvedKeys = LinkedHashSet<String>()
        rawKeys.forEach { rawKey ->
            val snapshot = buildP2PNumberSnapshot(rawKey)
            resolvedKeys.addAll(snapshot.lookupKeys)
        }
        resolvedKeys.forEach { key ->
            contactQueryCache.remove(key)
            queryTimestamps.remove(key)
        }
        Log.w(
            P2P_FORENSIC_TAG,
            "lookup_cache_invalidate reason=$reason keys=${maskP2PKeysForLog(resolvedKeys)}"
        )
    }
    
    /**
     * Get all cached phone numbers from DPS storage
     */
    fun getAllCachedPhoneNumbers(context: Context): List<String> {
        return try {
            val deviceContext = getDirectBootContext(context)
            val cacheDir = deviceContext.filesDir
            val cachedFiles = cacheDir.listFiles { file -> 
                file.name.startsWith("contact_") && file.name.endsWith(".cache") 
            } ?: emptyArray()
            
            cachedFiles.mapNotNull { file ->
                val phoneNumber = file.name.removePrefix("contact_").removeSuffix(".cache")
                phoneNumber.ifEmpty { null }
            }
        } catch (e: Exception) {
            UltraForensicLogger.deepest("Failed to get cached phone numbers: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Get DPS contact name for a specific phone number
     */
    fun getDpsContactName(context: Context, phoneNumber: String): String {
        val numberSnapshot = buildP2PNumberSnapshot(phoneNumber)
        logP2PNumberTrace("DirectBootCallManager.getDpsContactName", numberSnapshot)

        val cacheKey = numberSnapshot.normalized.ifBlank { numberSnapshot.digits }
        val currentTime = System.currentTimeMillis()
        val lastQueryTime = queryTimestamps[cacheKey] ?: 0L
        val cachedResult = contactQueryCache[cacheKey]

        if (
            currentTime - lastQueryTime < QUERY_DEBOUNCE_MS &&
            !cachedResult.isNullOrBlank() &&
            cachedResult != "Unknown"
        ) {
            Log.w(
                P2P_FORENSIC_TAG,
                "lookup_cache_hit type=memory raw=${maskP2PNumberForLog(phoneNumber)} normalized=${maskP2PNumberForLog(numberSnapshot.normalized)} last10=${maskP2PNumberForLog(numberSnapshot.last10)} value=${maskP2PDisplayValueForLog(cachedResult)}"
            )
            return cachedResult
        }

        queryTimestamps[cacheKey] = currentTime

        val p2pLookup = lookupP2PCache(context, phoneNumber, "DirectBootCallManager.getDpsContactName")
        if (!p2pLookup.name.isNullOrBlank()) {
            contactQueryCache[cacheKey] = p2pLookup.name
            Log.w(
                P2P_FORENSIC_TAG,
                "lookup_cache_hit type=p2p raw=${maskP2PNumberForLog(phoneNumber)} normalized=${maskP2PNumberForLog(numberSnapshot.normalized)} last10=${maskP2PNumberForLog(numberSnapshot.last10)} source=${p2pLookup.source} matchedKey=${p2pLookup.matchedKey?.let(::maskP2PNumberForLog)} keysTried=${maskP2PKeysForLog(p2pLookup.lookupKeysTried)} value=${p2pLookup.name?.let(::maskP2PDisplayValueForLog)}"
            )
            return p2pLookup.name
        }

        val fallbackResult = getCachedContactData(context, phoneNumber) ?: "Unknown"
        if (fallbackResult != "Unknown" && fallbackResult.isNotBlank()) {
            contactQueryCache[cacheKey] = fallbackResult
        } else {
            contactQueryCache.remove(cacheKey)
        }

        Log.w(
            P2P_FORENSIC_TAG,
            "lookup_fallback raw=${maskP2PNumberForLog(phoneNumber)} normalized=${maskP2PNumberForLog(numberSnapshot.normalized)} last10=${maskP2PNumberForLog(numberSnapshot.last10)} fallback=${maskP2PDisplayValueForLog(fallbackResult)} keysTried=${maskP2PKeysForLog(p2pLookup.lookupKeysTried)}"
        )

        return fallbackResult
    }
    
    /**
     * Check if phone number has DPS cache
     */
    fun hasDpsCache(context: Context, phoneNumber: String): Boolean {
        return try {
            val deviceContext = getDirectBootContext(context)
            val cacheFile = File(deviceContext.filesDir, "contact_$phoneNumber.cache")
            cacheFile.exists()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Clear DPS cache for specific phone number
     */
    fun clearDpsCache(context: Context, phoneNumber: String) {
        try {
            val deviceContext = getDirectBootContext(context)
            val cacheFile = File(deviceContext.filesDir, "contact_$phoneNumber.cache")
            if (cacheFile.exists()) {
                cacheFile.delete()
                android.util.Log.d(TAG, "Cleared DPS cache for: ${maskPhoneNumber(phoneNumber)}")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to clear DPS cache for ${maskPhoneNumber(phoneNumber)}: ${e.message}", e)
        }
    }
    
    /**
     * Clear all DPS caches
     */
    fun clearAllDpsCaches(context: Context) {
        try {
            val deviceContext = getDirectBootContext(context)
            val cacheDir = deviceContext.filesDir
            val cachedFiles = cacheDir.listFiles { file -> 
                file.name.startsWith("contact_") && file.name.endsWith(".cache") 
            } ?: emptyArray()
            
            cachedFiles.forEach { file ->
                file.delete()
            }
            
            // PERFORMANCE FIX: Clear performance cache as well
            contactQueryCache.clear()
            queryTimestamps.clear()
            
            android.util.Log.d(TAG, "Cleared all DPS caches (${cachedFiles.size} files) and performance cache")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to clear all DPS caches: ${e.message}", e)
        }
    }
    
    /**
     * Clear performance cache only (for memory management)
     */
    fun clearPerformanceCache() {
        contactQueryCache.clear()
        queryTimestamps.clear()
        android.util.Log.d(TAG, "Cleared performance cache")
    }
    
    /**
     * Get contact name from regular storage (for caching purposes)
     */
    private fun getContactNameFromRegularStorage(context: Context, phoneNumber: String): String {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val userManager = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
                if (!userManager.isUserUnlocked) return "Unknown"
            }
        } catch (e: Exception) {
            return "Unknown"
        }

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(
                P2P_FORENSIC_TAG,
                "directboot_contacts_lookup action=skip reason=missing_READ_CONTACTS raw=${maskP2PNumberForLog(phoneNumber)}"
            )
            return "Unknown"
        }

        val uri = android.net.Uri.withAppendedPath(
            android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, 
            android.net.Uri.encode(phoneNumber)
        )
        var contactName = "Unknown"
        
        try {
            context.contentResolver.query(
                uri, 
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), 
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    contactName = cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error looking up contact: ${e.message}")
        }
        
        return contactName
    }
    
    /**
     * Save current call state to device-protected storage with caching
     */
    fun saveCallStateToDirectBootStorage(context: Context, phoneNumber: String, callState: String, contactName: String = "") {
        try {
            // Only proceed if device-protected storage is available
            if (!isDeviceProtectedStorageAvailable(context)) {
                UltraForensicLogger.deepest("Device-protected storage not available, skipping Direct Boot save")
                return
            }
            
            val deviceContext = getDirectBootContext(context)
            val prefs = deviceContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            
            // CRITICAL FIX: Pass the contactName down to avoid CPS fallback query
            cacheContactData(context, phoneNumber, contactName)
            
            // Save call state data
            prefs.edit()
                .putString("current_call_phone_number", phoneNumber)
                .putString("current_call_state", callState)
                .putString("current_call_contact_name", contactName)
                .putLong("current_call_timestamp", System.currentTimeMillis())
                .putBoolean("has_active_call", true)
                .apply()
                
            android.util.Log.d(TAG, "Call state saved to Direct Boot storage: ${maskPhoneNumber(phoneNumber)}, state: $callState, contact cached=${contactName.isNotBlank()}")
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save call state to Direct Boot storage: ${e.message}", e)
        }
    }
    
    /**
     * Remove call state from device-protected storage
     */
    fun removeCallStateFromDirectBootStorage(context: Context) {
        try {
            // Only proceed if device-protected storage is available
            if (!isDeviceProtectedStorageAvailable(context)) {
                UltraForensicLogger.deepest("Device-protected storage not available, skipping Direct Boot remove")
                return
            }
            
            val deviceContext = getDirectBootContext(context)
            val prefs = deviceContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            
            prefs.edit()
                .remove("current_call_phone_number")
                .remove("current_call_state")
                .remove("current_call_contact_name")
                .remove("current_call_timestamp")
                .remove("has_active_call")
                .apply()
                
            android.util.Log.d(TAG, "Call state removed from Direct Boot storage")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to remove call state from Direct Boot storage: ${e.message}", e)
        }
    }
    
    /**
     * Restore call state from device-protected storage
     * @return DirectBootRestorationResult indicating success/failure and a message
     */
    fun restoreCallStateFromDirectBootStorage(context: Context): DirectBootRestorationResult {
        try {
            // Only proceed if device-protected storage is available
            if (!isDeviceProtectedStorageAvailable(context)) {
                val message = "Device-protected storage not available"
                android.util.Log.w(TAG, message)
                return DirectBootRestorationResult(false, message)
            }
            
            val deviceContext = getDirectBootContext(context)
            val prefs = deviceContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            
            val hasActiveCall = prefs.getBoolean("has_active_call", false)
            
            if (!hasActiveCall) {
                val message = "No active call found in Direct Boot storage"
                android.util.Log.d(TAG, message)
                return DirectBootRestorationResult(true, message)
            }
            
            val phoneNumber = prefs.getString("current_call_phone_number", "") ?: ""
            val callState = prefs.getString("current_call_state", "") ?: ""
            val contactName = prefs.getString("current_call_contact_name", "") ?: ""
            val timestamp = prefs.getLong("current_call_timestamp", 0L)
            
            if (phoneNumber.isEmpty()) {
                val message = "Invalid call state data in Direct Boot storage"
                android.util.Log.e(TAG, message)
                return DirectBootRestorationResult(false, message)
            }
            
            android.util.Log.d(TAG, "Restored call state from Direct Boot storage: ${maskPhoneNumber(phoneNumber)}, state: $callState, contact cached=${contactName.isNotBlank()}")
            
            // Trigger call state restoration in MyInCallService
            val myInCallService = MyInCallService.getInstance()
            if (myInCallService != null) {
                // Restore the call notification based on the saved state
                when (callState) {
                    "RINGING" -> {
                        // This would typically be handled by the telecom system on restart
                        android.util.Log.d(TAG, "Call was ringing, will be handled by telecom system")
                    }
                    "ACTIVE", "DIALING", "CONNECTING" -> {
                        // Show ongoing call notification
                        try {
                            val notificationMethod = myInCallService.javaClass.getDeclaredMethod("showOngoingCallNotification", String::class.java)
                            notificationMethod.isAccessible = true
                            notificationMethod.invoke(myInCallService, phoneNumber)
                            android.util.Log.d(TAG, "Restored ongoing call notification for: ${maskPhoneNumber(phoneNumber)}")
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Failed to restore call notification: ${e.message}", e)
                        }
                    }
                }
            }
            
            val message = "Successfully restored call state: ${maskPhoneNumber(phoneNumber)} ($callState)"
            return DirectBootRestorationResult(true, message)
            
        } catch (e: Exception) {
            val errorMessage = "Critical error in Direct Boot call state restoration: ${e.message}"
            android.util.Log.e(TAG, errorMessage, e)
            return DirectBootRestorationResult(false, errorMessage)
        }
    }
    
    /**
     * Check if there's an active call in Direct Boot storage
     */
    fun hasActiveCallInDirectBootStorage(context: Context): Boolean {
        return try {
            if (!isDeviceProtectedStorageAvailable(context)) {
                return false
            }
            
            val deviceContext = getDirectBootContext(context)
            val prefs = deviceContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.getBoolean("has_active_call", false)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error checking active call in Direct Boot storage: ${e.message}", e)
            false
        }
    }
    
    /**
     * Get saved call state information using cached contact data
     */
    fun getSavedCallState(context: Context): Triple<String, String, String> {
        return try {
            if (!isDeviceProtectedStorageAvailable(context)) {
                return Triple("", "", "")
            }
            
            val deviceContext = getDirectBootContext(context)
            val prefs = deviceContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            
            val phoneNumber = prefs.getString("current_call_phone_number", "") ?: ""
            val callState = prefs.getString("current_call_state", "") ?: ""
            
            // CRITICAL FIX: Only use the safe DPS cache. Completely removed the CPS fallback query.
            val contactName = getCachedContactData(context, phoneNumber) ?: "Unknown"
            
            Triple(phoneNumber, callState, contactName)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error getting saved call state: ${e.message}", e)
            Triple("", "", "")
        }
    }
}
