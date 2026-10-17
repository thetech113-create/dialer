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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DirectBootRestoreReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DirectBootRestoreReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            Log.e(TAG, "Context or intent is null")
            return
        }

        try {
            val action = intent.action
            
            Log.d(TAG, "Received intent with action: $action")
            
            // Handle boot completed actions
            if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                Log.d(TAG, "Boot completed, restoring call notifications...")
                
                // Use DirectBootCallRescheduler to restore call notifications
                val rescheduler = DirectBootCallRescheduler(context)
                rescheduler.restoreCallNotifications()
                
                // Also check for pending call state and mark it for CPS restoration
                if (DirectBootCallManager.hasActiveCallInDirectBootStorage(context)) {
                    val (phoneNumber, callState, contactName) = DirectBootCallManager.getSavedCallState(context)
                    if (phoneNumber.isNotEmpty()) {
                        CallStateManager.markPending(context, phoneNumber, callState, contactName)
                        Log.d(TAG, "Marked call state as pending for CPS restoration: ${maskPhoneNumber(phoneNumber)} ($callState)")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle intent: ${e.message}", e)
        }
    }
}
