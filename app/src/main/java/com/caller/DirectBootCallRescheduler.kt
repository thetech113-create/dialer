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
import android.util.Log

/**
 * DirectBootCallRescheduler
 *
 * Re-restores call notifications from the call state storage (which
 * already uses device-protected storage internally). Designed to be called from
 * a BootReceiver that receives LOCKED_BOOT_COMPLETED / BOOT_COMPLETED.
 *
 * Usage (example from BootReceiver):
 *   DirectBootCallRescheduler(context).restoreCallNotifications()
 */
class DirectBootCallRescheduler(private val context: Context) {

    companion object {
        private const val TAG = "DirectBootCallRescheduler"
    }

    /**
     * Restore all call notifications from Direct Boot storage.
     */
    fun restoreCallNotifications() {
        try {
            Log.d(TAG, "Attempting to restore call notifications from Direct Boot storage.")

            // Check if there's an active call in Direct Boot storage
            if (!DirectBootCallManager.hasActiveCallInDirectBootStorage(context)) {
                Log.d(TAG, "No active calls found in Direct Boot storage.")
                return
            }

            val (phoneNumber, callState, contactName) = DirectBootCallManager.getSavedCallState(context)
            
            if (phoneNumber.isEmpty()) {
                Log.w(TAG, "Invalid call state data in Direct Boot storage")
                return
            }

            Log.d(TAG, "Restoring call notification: ${maskPhoneNumber(phoneNumber)} ($callState)")

            // Start MyInCallService to restore notifications
            try {
                val serviceIntent = android.content.Intent(context, MyInCallService::class.java)
                serviceIntent.action = "RESTORE_CALL_STATE"
                serviceIntent.putExtra("PHONE_NUMBER", phoneNumber)
                serviceIntent.putExtra("CALL_STATE", callState)
                serviceIntent.putExtra("CONTACT_NAME", contactName)
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                
                Log.d(TAG, "Successfully started MyInCallService for call restoration")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MyInCallService: ${e.message}", e)
            }

        } catch (t: Throwable) {
            Log.e(TAG, "DirectBootCallRescheduler.restoreCallNotifications failed", t)
        }
    }
}
