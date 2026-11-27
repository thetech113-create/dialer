package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Immediate-redial lifecycle regression. When Call A ends it schedules an async disconnect cleanup that
 * tears the InCallService down (drop foreground, stopSelf, clear notifications/UI). The defect was that
 * the teardown was gated only on this service's own call list (this.calls); an immediately-redialed
 * Call B can be live in the Telecom framework before it is delivered into this.calls, so the cleanup
 * tore the service down out from under Call B. Waiting 1-2 minutes worked only because the cleanup had
 * long finished before the next dial.
 *
 * shouldTearDownAfterDisconnect() encodes the corrected ownership rule: the previous call's cleanup may
 * tear down ONLY when there is no call left anywhere - neither in this service's list nor in Telecom.
 * The rule takes no phone number, which is exactly the point: teardown ownership is per call-presence,
 * never per dialled number, so same-number and different-number redials behave identically.
 */
class DisconnectCleanupOwnershipTest {

    // End A -> immediately dial (same OR different number): Call B is live in Telecom but not yet in this
    // service's list. The previous call's cleanup must NOT tear down.
    @Test
    fun immediateRedial_frameworkInCall_doesNotTearDown() {
        assertFalse(shouldTearDownAfterDisconnect(ownLiveCallCount = 0, telecomFrameworkInCall = true))
    }

    // Call B already adopted into this.calls while A's cleanup runs: must NOT tear down.
    @Test
    fun newCallAdopted_doesNotTearDown() {
        assertFalse(shouldTearDownAfterDisconnect(ownLiveCallCount = 1, telecomFrameworkInCall = true))
    }

    // Genuine end: no call in this service and none in Telecom -> cleanup proceeds (cleanup is not skipped).
    @Test
    fun noCallAnywhere_tearsDown() {
        assertTrue(shouldTearDownAfterDisconnect(ownLiveCallCount = 0, telecomFrameworkInCall = false))
    }

    // Defensive: this service still holds a live call even if the global flag is momentarily false -> keep it.
    @Test
    fun ownLiveCall_doesNotTearDown() {
        assertFalse(shouldTearDownAfterDisconnect(ownLiveCallCount = 2, telecomFrameworkInCall = false))
    }

    // Delayed redial (after 1-2 minutes): A's cleanup already ran while nothing was live, which is correct;
    // the later dial is a fresh, independent call. The rule still only tears down when nothing is live.
    @Test
    fun teardownRuleIsNumberAgnostic_sameAndDifferentNumberIdentical() {
        // Same decision regardless of which number was dialled, because the function has no number input.
        assertTrue(shouldTearDownAfterDisconnect(0, false))
        assertFalse(shouldTearDownAfterDisconnect(0, true))
    }
}
