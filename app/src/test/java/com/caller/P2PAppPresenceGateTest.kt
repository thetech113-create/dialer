package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * User rule 2026-11-22: a caller-ID SMS goes only to a phone known to have this app. Before, every call sent one to any
 * number not yet marked "no app" (UNKNOWN_BOOTSTRAP_ALLOWED), so people without the app got hidden texts they never
 * agreed to, paid for by the caller.
 */
class P2PAppPresenceGateTest {
    @Test
    fun aNumberWithNoKnownAppGetsNoSms() {
        assertFalse(p2pSmsAllowedToPeer(P2PPeerCapability.UNKNOWN, peerKnownToHaveApp = false))
    }

    @Test
    fun aConfirmedAppUserGetsTheSms() {
        assertTrue(p2pSmsAllowedToPeer(P2PPeerCapability.HAS_APP_CONFIRMED, peerKnownToHaveApp = false))
    }

    @Test
    fun aPresenceCheckOrAMessageFromThePeerLetsTheSmsThrough() {
        assertTrue(p2pSmsAllowedToPeer(P2PPeerCapability.UNKNOWN, peerKnownToHaveApp = true))
    }

    @Test
    fun aPeerMarkedWithoutTheAppNeverGetsOne() {
        assertFalse(p2pSmsAllowedToPeer(P2PPeerCapability.NO_APP, peerKnownToHaveApp = true))
        assertFalse(p2pSmsAllowedToPeer(P2PPeerCapability.NO_APP, peerKnownToHaveApp = false))
    }
}
