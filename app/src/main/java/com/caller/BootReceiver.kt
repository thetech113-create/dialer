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
        val action = intent.action.orEmpty()
        Log.w("BootReceiver", "Boot broadcast received: action=$action")

        // 1. Instantly warm up the com.caller process so Telecom binding never times out
        try { InCallServiceEnhancements.warmUpProcess(context) } catch (_: Exception) {}

        // 2. Reschedule any direct boot or pending call state restorations
        try { DirectBootCallRescheduler(context).restoreCallNotifications() } catch (_: Exception) {}

        if (action == Intent.ACTION_USER_UNLOCKED && PendingCallStateStore.hasPendingCall(context)) {
            val serviceIntent = Intent(context, CallStateResumeService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
