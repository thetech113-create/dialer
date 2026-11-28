package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * System "Call not sent" on a call that worked (app log 2026-11-28 08:04:32-33): the green keypad button was
 * pressed again 0.6 s after the first dial, while call #1 was still DIALING and before its call screen had drawn
 * (the number stays on the keypad until then). The second placeCall went out as hold_then_place, Telecom refused
 * it, and the OEM showed its own "Call not sent" while call #1 carried on normally.
 *
 * shouldIgnoreNewDial() drops a dial while an earlier one is still being set up, without blocking a call placed
 * during an ACTIVE call or an immediate redial after a call has ended.
 */
class DuplicateDialGuardTest {

    // Second tap before the first dial has reached Telecom (no onCallAdded yet): ignore.
    @Test
    fun secondTapBeforeTelecomAddsTheFirstCall_isIgnored() {
        assertTrue(shouldIgnoreNewDial(lastDialDispatchedAt = 1_000L, lastCallAddedAt = 500L, outgoingCallsBeingSetUp = 0))
    }

    // The log case: call #1 is in Telecom and still DIALING when the button is pressed again: ignore.
    @Test
    fun secondTapWhileFirstCallIsDialing_isIgnored() {
        assertTrue(shouldIgnoreNewDial(lastDialDispatchedAt = 1_000L, lastCallAddedAt = 1_200L, outgoingCallsBeingSetUp = 1))
    }

    // First call answered (ACTIVE): placing another call (hold then place) is still allowed.
    @Test
    fun dialDuringActiveCall_isAllowed() {
        assertFalse(shouldIgnoreNewDial(lastDialDispatchedAt = 0L, lastCallAddedAt = 1_200L, outgoingCallsBeingSetUp = 0))
    }

    // Immediate redial after a call has ended: the ended call is not live, the old dial is resolved -> allowed.
    // (The previously fixed immediate-redial behaviour must not be blocked.)
    @Test
    fun immediateRedialAfterCallEnded_isAllowed() {
        assertFalse(shouldIgnoreNewDial(lastDialDispatchedAt = 0L, lastCallAddedAt = 1_200L, outgoingCallsBeingSetUp = 0))
    }

    // A dial that reached Telecom but whose marker is not yet cleared: only live state decides; nothing dialling -> allowed.
    @Test
    fun earlierDialAlreadyInTelecomAndNotDialling_isAllowed() {
        assertFalse(shouldIgnoreNewDial(lastDialDispatchedAt = 1_000L, lastCallAddedAt = 1_200L, outgoingCallsBeingSetUp = 0))
    }

    // Very first dial of the session: nothing in flight -> allowed.
    @Test
    fun firstDial_isAllowed() {
        assertFalse(shouldIgnoreNewDial(lastDialDispatchedAt = 0L, lastCallAddedAt = 0L, outgoingCallsBeingSetUp = 0))
    }
}
