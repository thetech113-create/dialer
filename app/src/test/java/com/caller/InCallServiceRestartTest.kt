package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit F-CALL-3 (app log 2026-11-22 08:01:42-08:01:54, Redmi 13C): Telecom unbound the call service mid-call and gave
 * the call to Xiaomi's call screen. onDestroy saw "liveCalls=0 telecomInCall=true", restarted the service, the new
 * copy owned no call, stopped, was destroyed and restarted again: 8 cycles in 12 s, each posting an "ongoing call"
 * notification for a call this app no longer showed. Starting the service cannot make Telecom bind it again, so only a
 * service that still holds a call of its own restarts.
 */
class InCallServiceRestartTest {
    @Test
    fun aCallMovedToAnotherCallScreenDoesNotRestartTheService() {
        assertFalse(shouldRestartInCallServiceOnDestroy(terminationRequested = false, ownLiveCalls = 0))
    }

    @Test
    fun aServiceThatStillHoldsACallRestarts() {
        assertTrue(shouldRestartInCallServiceOnDestroy(terminationRequested = false, ownLiveCalls = 1))
    }

    @Test
    fun anEndedCallNeverRestarts() {
        assertFalse(shouldRestartInCallServiceOnDestroy(terminationRequested = true, ownLiveCalls = 1))
        assertFalse(shouldRestartInCallServiceOnDestroy(terminationRequested = true, ownLiveCalls = 0))
    }
}
