package com.caller

import android.provider.CallLog.Calls

/**
 * How Recents shows an Android call-log type. Calls made TO this phone that were blocked, rejected, sent to voicemail
 * or answered on another device keep the incoming side. Before, every type other than incoming, outgoing and missed was
 * drawn as an outgoing call that "didn't connect", so a stopped caller looked like a call the user had made.
 * Whether a row says "Blocked" is decided separately, by the block list alone (see [isOnBlockList]).
 */
internal fun recentCallTypeFor(callLogType: Int): CallType = when (callLogType) {
    Calls.INCOMING_TYPE, Calls.ANSWERED_EXTERNALLY_TYPE -> CallType.INCOMING
    Calls.MISSED_TYPE, Calls.REJECTED_TYPE, Calls.BLOCKED_TYPE, Calls.VOICEMAIL_TYPE -> CallType.MISSED
    else -> CallType.OUTGOING
}

/**
 * True when [number] is on the user's block list, which stores the number's digits and, for longer numbers, its last
 * ten digits. This is the only meaning of "blocked" the app shows: a call Android stopped for any other reason (a
 * filter) is a missed call, and the number can still be blocked from its menu.
 */
internal fun isOnBlockList(number: String, listed: (String) -> Boolean): Boolean {
    val digits = number.filter { it.isDigit() }
    if (digits.isEmpty()) return false
    return listed(digits) || (digits.length >= 10 && listed(digits.takeLast(10)))
}

/**
 * The call type the PC Link sends for a call-log row, with the same rules as Recents: voicemail counts as missed,
 * answered elsewhere as incoming, and "blocked" only when the number is on the block list; a call a filter stopped is
 * "missed" (older PCs show unknown values as "Other", as before).
 */
internal fun linkCallLogType(callLogType: Int, numberIsBlocked: Boolean): String = when (callLogType) {
    Calls.INCOMING_TYPE, Calls.ANSWERED_EXTERNALLY_TYPE -> "in"
    Calls.OUTGOING_TYPE -> "out"
    Calls.MISSED_TYPE, Calls.VOICEMAIL_TYPE -> "missed"
    Calls.REJECTED_TYPE -> "rejected"
    Calls.BLOCKED_TYPE -> if (numberIsBlocked) "blocked" else "missed"
    else -> "other"
}
