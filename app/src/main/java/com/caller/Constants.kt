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

const val CALL_UI_ACTION_WAKE = "WAKE_UI"
const val CALL_UI_EXTRA_STATE_HINT = "CALL_UI_EXTRA_STATE_HINT"
const val CALL_UI_EXTRA_PHONE_NUMBER_HINT = "CALL_UI_EXTRA_PHONE_NUMBER_HINT"
const val CALL_UI_EXTRA_CONTACT_NAME_HINT = "CALL_UI_EXTRA_CONTACT_NAME_HINT"
const val CALL_UI_EXTRA_IS_CONFERENCE_HINT = "CALL_UI_EXTRA_IS_CONFERENCE_HINT"
const val CALL_UI_EXTRA_SIM_LABEL = "CALL_UI_EXTRA_SIM_LABEL"
const val POWER_END_RECOVERY_ACTION = "POWER_END_RECOVERY"
const val POWER_END_RECOVERY_PHONE_EXTRA = "POWER_END_RECOVERY_PHONE_EXTRA"

data class IncomingCallUiDecision(
    val allowFullScreen: Boolean,
    val reason: String,
    val authorityPackage: String = ""
)

fun resolveIncomingCallUiDecision(
    foregroundPackage: String,
    myPackage: String,
    defaultDialerPackage: String,
    launcherPackage: String,
    assumeDialerOwnership: Boolean = false,
    isDeviceLocked: Boolean = false,
    isScreenInteractive: Boolean = true
): IncomingCallUiDecision {
    val normalizedForeground = foregroundPackage.trim().takeUnless {
        it.isBlank() || it.equals("UNKNOWN", ignoreCase = true)
    }.orEmpty()
    val weAreDefaultDialer = defaultDialerPackage.isNotBlank() && defaultDialerPackage == myPackage

    return when {
        isDeviceLocked -> IncomingCallUiDecision(
            allowFullScreen = true,
            reason = "locked_screen"
        )
        !isScreenInteractive -> IncomingCallUiDecision(
            allowFullScreen = true,
            reason = "screen_off"
        )
        assumeDialerOwnership -> IncomingCallUiDecision(
            allowFullScreen = true,
            reason = if (weAreDefaultDialer) "service_owned_default_dialer" else "service_owned_incall"
        )
        // Being the default dialer does NOT mean full screen: inside another app (WhatsApp etc.) the user
        // gets the Answer/Decline banner; full screen only in the dialer or on the home screen (below).
        normalizedForeground.isEmpty() -> IncomingCallUiDecision(
            allowFullScreen = false,
            reason = "unknown_foreground"
        )
        normalizedForeground == myPackage -> IncomingCallUiDecision(
            allowFullScreen = true,
            reason = "own_dialer",
            authorityPackage = normalizedForeground
        )
        defaultDialerPackage.isNotBlank() && normalizedForeground == defaultDialerPackage -> IncomingCallUiDecision(
            allowFullScreen = true,
            reason = "default_dialer",
            authorityPackage = normalizedForeground
        )
        launcherPackage.isNotBlank() && normalizedForeground == launcherPackage -> IncomingCallUiDecision(
            allowFullScreen = true,
            reason = "default_launcher",
            authorityPackage = normalizedForeground
        )
        else -> IncomingCallUiDecision(
            allowFullScreen = false,
            reason = "third_party_foreground",
            authorityPackage = normalizedForeground
        )
    }
}

object Constants {
    const val ACTION_WIDGET_CALL = "com.caller.ACTION_WIDGET_CALL"
    const val ACTION_NOTIFICATION_DISMISSED = "com.caller.NOTIFICATION_DISMISSED"
    const val ACTION_STOP_FOREGROUND_SERVICE = "com.caller.ACTION_STOP_FOREGROUND_SERVICE"
    const val ACTION_RESUME_CALL_AUDIO = "com.caller.ACTION_RESUME_CALL_AUDIO"
    const val ACTION_REPOST_NOTIFICATION = "com.caller.REPOST_NOTIFICATION"
    const val EXTRA_PHONE_NUMBER = "PHONE_NUMBER"
    const val EXTRA_WIDGET_PHONE_NUMBER = "WIDGET_PHONE_NUMBER"
    
    // CRITICAL: Actions for the notification buttons
    const val ACTION_ANSWER_CALL = "com.caller.ACTION_ANSWER_CALL"
    const val ACTION_REJECT_CALL = "com.caller.ACTION_REJECT_CALL"
    
    const val NOTIFICATION_ID_INCOMING_CALL = 2001
    const val NOTIFICATION_ID_ONGOING_CALL = 2002
    const val NOTIFICATION_ID_MISSED_CALL = 2003
    const val NOTIFICATION_ID_POWER_END_RECOVERY = 2004
    
    // Notification repost constants from alarm service
    const val REPOST_DELAY_MS: Long = 3000L
    const val MAX_NOTIFICATION_FAILURES = 3
}


