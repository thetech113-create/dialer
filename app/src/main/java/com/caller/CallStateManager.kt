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
import android.content.Intent
import android.util.Log

/**
 * CallStateManager
 * Manages call state transitions between DPS and CPS phases
 * Works with PendingCallStateStore for robust call state management
 */
object CallStateManager {
    
    private const val TAG = "CallStateManager"
    
    /**
     * Resume call state from CPS and clear pending call marker.
     */
    fun resume(context: Context, phoneNumber: String) {
        try {
            val pendingState = PendingCallStateStore.load(context, phoneNumber)
            if (pendingState != null) {
                val maskedPendingNumber = maskPhoneNumber(pendingState.phoneNumber)
                UltraForensicLogger.debug("Resuming call state for phoneNumber: $maskedPendingNumber, state: ${pendingState.callState}")
                
                // Restore call notification based on saved state
                when (pendingState.callState) {
                    "RINGING" -> {
                        // This would typically be handled by the telecom system on restart
                        Log.d(TAG, "Call was ringing, will be handled by telecom system")
                    }
                    "ACTIVE", "DIALING", "CONNECTING" -> {
                        // Show ongoing call notification
                        try {
                            val myInCallService = MyInCallService.getInstance()
                            if (myInCallService != null) {
                                val notificationMethod = myInCallService.javaClass.getDeclaredMethod("showOngoingCallNotification", String::class.java)
                                notificationMethod.isAccessible = true
                                notificationMethod.invoke(myInCallService, pendingState.phoneNumber)
                                val maskedRestoreNumber = maskPhoneNumber(pendingState.phoneNumber)
                                UltraForensicLogger.debug("Restored ongoing call notification for: $maskedRestoreNumber")
                            } else {
                                // Start service if not running
                                val serviceIntent = Intent(context, MyInCallService::class.java)
                                serviceIntent.action = "RESTORE_CALL_STATE"
                                serviceIntent.putExtra("PHONE_NUMBER", pendingState.phoneNumber)
                                serviceIntent.putExtra("CALL_STATE", pendingState.callState)
                                serviceIntent.putExtra("CONTACT_NAME", pendingState.contactName)
                                
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    context.startForegroundService(serviceIntent)
                                } else {
                                    context.startService(serviceIntent)
                                }
                                
                                UltraForensicLogger.debug("Started MyInCallService for call state restoration")
                            }
                        } catch (e: Exception) {
                            UltraForensicLogger.deepest("Failed to restore call notification: ${e.message}")
                        }
                    }
                }
                
                // Clear the pending state after restoration
                PendingCallStateStore.clear(context)
                val maskedCompleteNumber = maskPhoneNumber(phoneNumber)
                UltraForensicLogger.debug("Call state restoration completed for: $maskedCompleteNumber")
            } else {
                val maskedFoundNumber = maskPhoneNumber(phoneNumber)
                UltraForensicLogger.debug("No pending call state found for phoneNumber: $maskedFoundNumber")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume call state: ${e.message}", e)
        }
    }
    
    /**
     * Force resume call state from CPS for a specific phone number.
     */
    fun forceResumeFromCps(context: Context, phoneNumber: String) {
        try {
            val maskedForceNumber = maskPhoneNumber(phoneNumber)
            UltraForensicLogger.debug("Force resuming call state from CPS for phoneNumber: $maskedForceNumber")
            resume(context, phoneNumber)
        } catch (e: Exception) {
            UltraForensicLogger.deepest("Failed to force resume call state from CPS: ${e.message}")
        }
    }
    
    /**
     * Mark call state as pending for CPS restoration.
     */
    fun markPending(context: Context, phoneNumber: String, callState: String, contactName: String = "") {
        try {
            PendingCallStateStore.save(context, phoneNumber, callState, contactName)
            val maskedMarkNumber = maskPhoneNumber(phoneNumber)
            UltraForensicLogger.debug("Marked call state as pending for phoneNumber: $maskedMarkNumber, state: $callState")
        } catch (e: Exception) {
            UltraForensicLogger.deepest("Failed to mark call state as pending: ${e.message}")
        }
    }
    
    /**
     * Check if there's a pending call state for restoration.
     */
    fun hasPendingCallState(context: Context): Boolean {
        return PendingCallStateStore.hasPendingCall(context)
    }
    
    /**
     * Get the current pending call state.
     */
    fun getPendingCallState(context: Context): PendingCallState? {
        return PendingCallStateStore.getPendingCallState(context)
    }
}
