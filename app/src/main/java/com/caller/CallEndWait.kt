package com.caller

import kotlinx.coroutines.delay

/**
 * Waits until the phone no longer reports a live call, checking every [pollMs]. After a call ends, Telecom or the
 * telephony state can still say "in call" for a while (seen on OPPO and Xiaomi). Checking once and giving up left the
 * call screen on "Ending" for good, because no further call update arrives to check again.
 */
internal suspend fun awaitNoLiveCall(isLive: () -> Boolean, pollMs: Long = 500L) {
    while (isLive()) delay(pollMs)
}
