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

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_UNLOCKED) {
            // PRIVACY COMPLIANCE: Reduce verbose logging noise
            // Log.d("ContactApp", "BootReceiver: User unlocked, checking for pending call state")
            
            if (PendingCallStateStore.hasPendingCall(context)) {
                // PRIVACY COMPLIANCE: Reduce verbose logging noise
                // Log.d("ContactApp", "BootReceiver: Found pending call state, starting CallStateResumeService")
                val serviceIntent = Intent(context, CallStateResumeService::class.java)
                
                // Start as foreground service if Android 8.0+
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                // PRIVACY COMPLIANCE: Reduce verbose logging noise
            // Log.d("ContactApp", "BootReceiver: No pending call state found")
            }
        }
    }
}
