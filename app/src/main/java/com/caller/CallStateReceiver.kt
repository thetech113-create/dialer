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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log

class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val EVENT_COOLDOWN_MS = 1000L
        private var lastEventTime = 0L
        // STRICTNESS GATE: Deduplication ledger to prevent infinite CPU loops
        private val interceptedMissedCalls = mutableSetOf<String>()
        private var lastInterceptTime = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        var action = intent.action
        
        // Ensure we aren't looping the Missed Call cancellation
        if (action == TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) {
            
            val number = intent.dataString ?: "UNKNOWN"
            val currentTime = System.currentTimeMillis()
            
            // Debounce: If we processed a missed call in the last 3 seconds, DROP IT
            if (currentTime - lastInterceptTime < 3000) {
                UltraForensicLogger.deepest("CallStateReceiver: Loop prevented. Dropping redundant missed call intent.")
                return 
            }

            // Record execution to break the loop
            lastInterceptTime = currentTime
            
            UltraForensicLogger.deepest("CallStateReceiver: Cancelled OS Missed Call Notification. State Machine in InCallService handles UI.")
            cancelSystemMissedCallNotification(context)
            
            // Critical: Abort the broadcast so it doesn't bounce back from system default handlers
            if (isOrderedBroadcast) {
                abortBroadcast()
            }
            return
        }

        // Prevent duplicate events for ringing
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEventTime < EVENT_COOLDOWN_MS) {
            return
        }
        
        if (action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            
            // Only trigger for ringing state, other states are handled by InCallService
            if (stateStr == TelephonyManager.EXTRA_STATE_RINGING) {
                lastEventTime = currentTime
                Log.w(
                    P2P_FORENSIC_TAG,
                    "incoming_parallel_wake action=blocked source=CallStateReceiver authority=MyInCallService.showIncomingCallNotification"
                )
            }
        }
    }
    
    private fun cancelSystemMissedCallNotification(context: Context) {
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.cancelMissedCallsNotification()
            
            // Permanently silence the System Dialer by marking the call log as read
            markMissedCallsAsRead(context)
        } catch (e: Exception) {
            UltraForensicLogger.deepest("CallStateReceiver", "OS_INTERCEPT_FAIL", "Failed to cancel OS missed call: ${e.message}")
        }
    }
    
    private fun markMissedCallsAsRead(context: Context) {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.CallLog.Calls.IS_READ, 1)
                put(android.provider.CallLog.Calls.NEW, 0)
            }
            context.contentResolver.update(
                android.provider.CallLog.Calls.CONTENT_URI,
                values,
                "${android.provider.CallLog.Calls.TYPE} = ? AND ${android.provider.CallLog.Calls.IS_READ} = 0",
                arrayOf(android.provider.CallLog.Calls.MISSED_TYPE.toString())
            )
        } catch (e: Exception) {
                UltraForensicLogger.deepest("Failed to mark call log as read: ${e.message}")
        }
    }
    
}
