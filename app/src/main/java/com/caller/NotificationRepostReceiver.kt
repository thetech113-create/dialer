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
import com.caller.logging.UltraForensicLogger

class NotificationRepostReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NotificationRepostReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) {
            UltraForensicLogger.deepest("NotificationRepostReceiver", "NULL_CONTEXT", "Context or intent is null")
            return
        }

        try {
            val action = intent.action
            val phoneNumber = intent.getStringExtra("PHONE_NUMBER") ?: ""
            val maskedPhoneNumber = maskPhoneNumber(phoneNumber)
            
            UltraForensicLogger.debug("NotificationRepostReceiver", "INTENT_RECEIVED", "Received intent with action: $action for phone number: $maskedPhoneNumber")
            
            // Handle notification dismissed action
            if (action == Constants.ACTION_NOTIFICATION_DISMISSED) {
                UltraForensicLogger.debug("NotificationRepostReceiver", "NOTIFICATION_DISMISSED", "Notification dismissed for phone number: $maskedPhoneNumber, reposting...")
                
                // Send a broadcast to the MyInCallService to repost the notification
                val repostIntent = Intent(context, MyInCallService::class.java)
                repostIntent.action = Constants.ACTION_REPOST_NOTIFICATION
                repostIntent.putExtra("PHONE_NUMBER", phoneNumber)
                
                // Start the service with the repost action
                context.startService(repostIntent)
                
                UltraForensicLogger.debug("NotificationRepostReceiver", "REPOST_SENT", "Sent repost request to MyInCallService for phone number: $maskedPhoneNumber")
            }
            
        } catch (e: Exception) {
            UltraForensicLogger.deepest("NotificationRepostReceiver", "INTENT_ERROR", "Failed to handle intent: ${e.message}")
        }
    }
}
