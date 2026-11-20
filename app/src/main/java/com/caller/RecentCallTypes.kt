package com.caller

import android.provider.CallLog.Calls

/**
 * How Recents shows an Android call-log type. Calls made TO this phone that were blocked, rejected, sent to voicemail
 * or answered on another device keep the incoming side. Before, every type other than incoming, outgoing and missed was
 * drawn as an outgoing call that "didn't connect", so a blocked caller looked like a call the user had made.
 */
internal fun recentCallTypeFor(callLogType: Int): CallType = when (callLogType) {
    Calls.INCOMING_TYPE, Calls.ANSWERED_EXTERNALLY_TYPE -> CallType.INCOMING
    Calls.MISSED_TYPE, Calls.REJECTED_TYPE, Calls.BLOCKED_TYPE, Calls.VOICEMAIL_TYPE -> CallType.MISSED
    else -> CallType.OUTGOING
}

/**
 * The call type the PC Link sends for a call-log row. Like Recents: voicemail counts as missed, answered elsewhere as
 * incoming, and a blocked call is "blocked" (older PCs show unknown values as "Other", as before).
 */
internal fun linkCallLogType(callLogType: Int): String = when (callLogType) {
    Calls.INCOMING_TYPE, Calls.ANSWERED_EXTERNALLY_TYPE -> "in"
    Calls.OUTGOING_TYPE -> "out"
    Calls.MISSED_TYPE, Calls.VOICEMAIL_TYPE -> "missed"
    Calls.REJECTED_TYPE -> "rejected"
    Calls.BLOCKED_TYPE -> "blocked"
    else -> "other"
}

/** True for a call Android logged as blocked (by the block list or by call screening). */
internal fun isBlockedCallLogType(callLogType: Int): Boolean = callLogType == Calls.BLOCKED_TYPE
