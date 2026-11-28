package com.caller

import android.content.Context
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log

/**
 * The app's single outgoing-dial guard (one per process, so it survives the keypad screen being recreated) and the
 * live state it is checked against. Every path that places an outgoing call goes through [tryAcquire] and then
 * [DialAttemptGuard.dispatch]: MainActivity (keypad, contacts, recents, swipe, video, power-key recovery; both the
 * direct-place and the hold-then-place branch), WidgetCallActivity, LinkService ("dial" from the PC) and
 * VoicemailManager.
 */
internal object DialAttempts {
    val guard = DialAttemptGuard(
        clock = { SystemClock.elapsedRealtime() },
        log = { Log.w(P2P_FORENSIC_TAG, it) }
    )

    /** Live state right now: the InCallService's calls and Telecom's in-call flag. Never cached. */
    fun snapshot(context: Context): LiveCallSnapshot {
        val calls = MyInCallService.getInstance()?.dialGuardCallPhases().orEmpty()
        val telecomInCall = runCatching {
            (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).isInCall
        }.getOrDefault(false)
        return LiveCallSnapshot(calls, telecomInCall)
    }

    /** Accept a new dial (returns its attempt id) or refuse it (null) while one is still being set up. */
    fun tryAcquire(context: Context, source: String): Long? = guard.tryAcquire(source, snapshot(context))

    /** Re-check the current attempt against live state (releases it once established, ended or timed out). */
    fun revalidate(context: Context) = guard.revalidate(snapshot(context))
}
