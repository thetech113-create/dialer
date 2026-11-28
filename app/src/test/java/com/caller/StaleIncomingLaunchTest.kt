package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleIncomingLaunchTest {
    @Test
    fun ringingScreenWithNoCallOnThePhoneIsRefused() {
        // 2026-11-28 18:28:50: a leftover launch opened "SAKET, ringing" while the phone had no call.
        assertTrue(isStaleIncomingLaunch(stateHint = "RINGING", phoneHasLiveCall = false))
    }

    @Test
    fun realRingingCallStillOpens() {
        assertFalse(isStaleIncomingLaunch(stateHint = "RINGING", phoneHasLiveCall = true))
    }

    @Test
    fun dialingLaunchIsLeftAlone() {
        // The widget opens the screen right after placeCall, before the phone may report the call.
        assertFalse(isStaleIncomingLaunch(stateHint = "DIALING", phoneHasLiveCall = false))
        assertFalse(isStaleIncomingLaunch(stateHint = null, phoneHasLiveCall = false))
    }
}
