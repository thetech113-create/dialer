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
import android.telecom.CallAudioState
import android.util.Log
import com.caller.logging.UltraForensicLogger

class NotificationRepostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action.orEmpty()
        Log.w("NotificationAction", "Broadcast action received: $action")

        val inCallService = MyInCallService.getInstance()
        when (action) {
            Constants.ACTION_ANSWER_CALL -> {
                inCallService?.answerCall()
            }
            Constants.ACTION_REJECT_CALL -> {
                inCallService?.rejectCall()
            }
            Constants.ACTION_DISCONNECT_CALL -> {
                inCallService?.disconnectCall()
            }
            Constants.ACTION_MUTE_CALL -> {
                val currentMute = inCallService?.getCurrentCallStateFromSync()?.isMuted == true
                inCallService?.setMuted(!currentMute)
            }
            Constants.ACTION_SPEAKER_CALL -> {
                val currentSpeaker = inCallService?.getCurrentCallStateFromSync()?.isSpeakerOn == true
                val nextRoute = if (currentSpeaker) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
                inCallService?.setCallAudioRoute(nextRoute)
            }
            Constants.ACTION_NOTIFICATION_DISMISSED -> {
                val phoneNumber = intent.getStringExtra("PHONE_NUMBER").orEmpty()
                val repostIntent = Intent(context, MyInCallService::class.java).apply {
                    this.action = Constants.ACTION_REPOST_NOTIFICATION
                    putExtra("PHONE_NUMBER", phoneNumber)
                }
                try {
                    context.startService(repostIntent)
                } catch (_: Exception) {}
            }
        }
    }
}
