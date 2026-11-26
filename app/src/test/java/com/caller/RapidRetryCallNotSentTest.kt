package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rapid-retry regression (app log 2026-11-25 12:11, ColorOS): a first outgoing call ends, an immediate
 * redial connects (audio present), but the framework delivered the call to our InCallService without our
 * onCallAdded completing, so the app showed no call UI/bubble/notification and wrongly displayed
 * "Call not sent".
 *
 * reconcileFrameworkCall() re-adopts the call from the authoritative this.calls list; shouldReportCallNotSent()
 * then guarantees the dialer never claims "Call not sent" while a call is actually up. These tests pin that
 * decision for both reproduction scenarios (same number and different number).
 */
class RapidRetryCallNotSentTest {

    // Scenario A (same number) and Scenario B (different number): the retry connects, so Telecom reports a
    // live call. No false "Call not sent" may appear after a successful connection.
    @Test
    fun connectedRetryNeverReportsCallNotSent_viaTelecomInCall() {
        assertFalse(
            shouldReportCallNotSent(
                reconciledLiveCall = false,
                callStartedAfterDispatch = false,
                telecomInCall = true
            )
        )
    }

    // The reconcile adopted the framework-live call that the missed callback dropped: not "not sent".
    @Test
    fun reconciledLiveCallNeverReportsCallNotSent() {
        assertFalse(
            shouldReportCallNotSent(
                reconciledLiveCall = true,
                callStartedAfterDispatch = false,
                telecomInCall = false
            )
        )
    }

    // Our own onCallAdded ran for the dialed call: not "not sent".
    @Test
    fun ownCallAddedNeverReportsCallNotSent() {
        assertFalse(
            shouldReportCallNotSent(
                reconciledLiveCall = false,
                callStartedAfterDispatch = true,
                telecomInCall = false
            )
        )
    }

    // The genuine failure: the dial produced no call anywhere (the OEM silently refused placeCall). Only
    // here does the dialer show its own "Call not sent".
    @Test
    fun noCallAnywhereReportsCallNotSent() {
        assertTrue(
            shouldReportCallNotSent(
                reconciledLiveCall = false,
                callStartedAfterDispatch = false,
                telecomInCall = false
            )
        )
    }

    // Any single signal of a live call is enough to stay silent.
    @Test
    fun anyLiveSignalSuppressesCallNotSent() {
        assertFalse(shouldReportCallNotSent(reconciledLiveCall = true, callStartedAfterDispatch = true, telecomInCall = true))
        assertFalse(shouldReportCallNotSent(reconciledLiveCall = true, callStartedAfterDispatch = false, telecomInCall = true))
        assertFalse(shouldReportCallNotSent(reconciledLiveCall = false, callStartedAfterDispatch = true, telecomInCall = true))
    }
}
