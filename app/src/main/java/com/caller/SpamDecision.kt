package com.caller

import android.content.Context
import android.os.Build

/** What Smart Spam Protection does with an incoming call. */
internal enum class SpamAction { ALLOW, FLAG, DROP }

/**
 * Smart Spam Protection flags suspected spam and drops only clear spam: a caller ID the network reports as faked
 * (STIR/SHAKEN failed) or a score of 100 or more (machine-timed repeat calls plus a burst). An international fraud
 * prefix (Wangiri) alone is flagged, not dropped: real people call from those countries too (audit SPAM-1). A flagged
 * call rings and its call screen says "Potential spam".
 */
internal fun spamDecision(score: Float, isWangiri: Boolean, stirFailed: Boolean): SpamAction = when {
    stirFailed || score >= 100f -> SpamAction.DROP
    isWangiri || score >= 50f -> SpamAction.FLAG
    else -> SpamAction.ALLOW
}

/** How long a flag from call screening marks the number's call as "Potential spam". */
internal const val SPAM_FLAG_WINDOW_MS = 10 * 60 * 1000L

internal fun isSpamFlagFresh(flaggedAt: Long, now: Long): Boolean =
    flaggedAt > 0L && now - flaggedAt in 0..SPAM_FLAG_WINDOW_MS

private fun spamFlagPrefs(context: Context) =
    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.createDeviceProtectedStorageContext() else context)
        .getSharedPreferences("suspected_spam_calls", Context.MODE_PRIVATE)

/** Remembers that call screening flagged this number, so the call screen can show it as possible spam. */
internal fun flagSuspectedSpam(context: Context, digits: String) {
    if (digits.isEmpty()) return
    spamFlagPrefs(context).edit().putLong(digits, System.currentTimeMillis()).apply()
}

internal fun isFlaggedSuspectedSpam(context: Context, digits: String): Boolean =
    digits.isNotEmpty() && isSpamFlagFresh(spamFlagPrefs(context).getLong(digits, 0L), System.currentTimeMillis())
