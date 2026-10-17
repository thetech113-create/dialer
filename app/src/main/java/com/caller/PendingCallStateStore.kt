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
import android.content.SharedPreferences

data class PendingCallState(
    val phoneNumber: String,
    val callState: String,
    val contactName: String,
    val timestamp: Long
)

/**
 * DPS-Safe Pending Call State Store
 * Persists call state during DPS phase for CPS restoration
 * Works with CallStateManager for robust call continuation
 */
object PendingCallStateStore {
    private const val PREFS_NAME = "pending_call_state_store"
    private const val KEY_PENDING_PHONE_NUMBER = "pending_phone_number"
    private const val KEY_PENDING_CALL_STATE = "pending_call_state"
    private const val KEY_PENDING_CONTACT_NAME = "pending_contact_name"
    private const val KEY_TIMESTAMP = "pending_timestamp"
    
    private fun getPrefs(context: Context): SharedPreferences {
        // CRITICAL FIX: Force SharedPreferences to use DPS to prevent CPS access crash
        val safeContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else context
        return safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Save call state for later restoration when CPS becomes available.
     */
    fun save(context: Context, phoneNumber: String, callState: String, contactName: String = "") {
        try {
            val prefs = getPrefs(context)
            prefs.edit()
                .putString(KEY_PENDING_PHONE_NUMBER, phoneNumber)
                .putString(KEY_PENDING_CALL_STATE, callState)
                .putString(KEY_PENDING_CONTACT_NAME, contactName)
                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                .apply()

            val maskedSaveNumber = maskPhoneNumber(phoneNumber)
            UltraForensicLogger.debug("PENDING_CALL_STATE_SAVED: phoneNumber=$maskedSaveNumber, callState=$callState")
        } catch (e: Exception) {
            UltraForensicLogger.deepest("PENDING_CALL_STATE_SAVE_ERROR: ${e.message}")
        }
    }

    /**
     * Get the pending phone number, if any.
     */
    fun getPhoneNumber(context: Context): String? {
        return try {
            val prefs = getPrefs(context)
            val phoneNumber = prefs.getString(KEY_PENDING_PHONE_NUMBER, null)
            val timestamp = prefs.getLong(KEY_TIMESTAMP, 0)
            if (phoneNumber != null && timestamp > 0) {
                val maskedRetrieveNumber = maskPhoneNumber(phoneNumber)
                UltraForensicLogger.debug("PENDING_PHONE_NUMBER_RETRIEVED: phoneNumber=$maskedRetrieveNumber timestamp=$timestamp")
                phoneNumber
            } else null
        } catch (e: Exception) {
            UltraForensicLogger.deepest("PENDING_PHONE_NUMBER_RETRIEVE_ERROR: ${e.message}")
            null
        }
    }
    
    /**
     * Get the complete pending call state, if any.
     */
    fun getPendingCallState(context: Context): PendingCallState? {
        return try {
            val prefs = getPrefs(context)
            val phoneNumber = prefs.getString(KEY_PENDING_PHONE_NUMBER, null)
            val callState = prefs.getString(KEY_PENDING_CALL_STATE, null)
            val contactName = prefs.getString(KEY_PENDING_CONTACT_NAME, "") ?: ""
            val timestamp = prefs.getLong(KEY_TIMESTAMP, 0)

            if (phoneNumber != null && callState != null && timestamp > 0) {
                val maskedStateNumber = maskPhoneNumber(phoneNumber)
                UltraForensicLogger.debug("PENDING_CALL_STATE_RETRIEVED: phoneNumber=$maskedStateNumber, callState=$callState")
                PendingCallState(phoneNumber, callState, contactName, timestamp)
            } else {
                null
            }
        } catch (e: Exception) {
            UltraForensicLogger.deepest("PENDING_CALL_STATE_RETRIEVE_ERROR: ${e.message}")
            null
        }
    }
    
    fun clear(context: Context) {
        try {
            val prefs = getPrefs(context)
            prefs.edit()
                .remove(KEY_PENDING_PHONE_NUMBER)
                .remove(KEY_PENDING_CALL_STATE)
                .remove(KEY_PENDING_CONTACT_NAME)
                .remove(KEY_TIMESTAMP)
                .apply()
            UltraForensicLogger.debug("Cleared pending call state")
        } catch (e: Exception) {
            UltraForensicLogger.deepest("PENDING_CALL_STATE_CLEAR_ERROR: ${e.message}")
        }
    }
    
    /**
     * Check if there's a pending call state waiting.
     */
    fun hasPendingCall(context: Context): Boolean {
        return getPhoneNumber(context) != null
    }

    /**
     * Restore pending call state from DPS.
     * This is called by DirectBootRestoreReceiver on ACTION_USER_UNLOCKED.
     */
    fun restore(context: Context): PendingCallState? {
        return getPendingCallState(context)
    }

    /**
     * Load pending call state for a specific phone number.
     * This is called by CallStateManager.forceResumeFromCps.
     */
    fun load(context: Context, phoneNumber: String): PendingCallState? {
        return try {
            val prefs = getPrefs(context)
            val storedPhoneNumber = prefs.getString(KEY_PENDING_PHONE_NUMBER, null)
            val callState = prefs.getString(KEY_PENDING_CALL_STATE, null)
            val contactName = prefs.getString(KEY_PENDING_CONTACT_NAME, "") ?: ""
            val timestamp = prefs.getLong(KEY_TIMESTAMP, 0)

            if (storedPhoneNumber == phoneNumber && callState != null && timestamp > 0) {
                val maskedLoadNumber = maskPhoneNumber(phoneNumber)
                UltraForensicLogger.debug("Loaded pending call state for phoneNumber=$maskedLoadNumber, callState=$callState")
                PendingCallState(phoneNumber, callState, contactName, timestamp)
            } else {
                null
            }
        } catch (e: Exception) {
            val maskedErrorNumber = maskPhoneNumber(phoneNumber)
            UltraForensicLogger.deepest("Failed to load pending call state for phoneNumber=$maskedErrorNumber: ${e.message}")
            null
        }
    }
}
