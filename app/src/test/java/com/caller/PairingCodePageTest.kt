package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodePageTest {
    @Test
    fun theCodePageClosesOnceThePairingIsApprovedOrDenied() {
        // Approving or denying clears the active code while the page still shows the code.
        assertTrue(pairingCodePageShouldClose(codeIsShown = true, activePairingCode = null))
    }

    @Test
    fun theCodePageStaysWhileTheCodeIsStillOffered() {
        assertFalse(pairingCodePageShouldClose(codeIsShown = true, activePairingCode = "482910"))
    }

    @Test
    fun askingForAnotherCodeDoesNotCloseThePage() {
        // The new code replaces the old one at once, before the page shows it: the active code is never empty in between.
        assertFalse(pairingCodePageShouldClose(codeIsShown = true, activePairingCode = "731054"))
    }

    @Test
    fun aPageThatShowsNoCodeIsLeftAlone() {
        // Entering a code on this phone, or a page that was never opened, is not closed by this rule.
        assertFalse(pairingCodePageShouldClose(codeIsShown = false, activePairingCode = null))
        assertFalse(pairingCodePageShouldClose(codeIsShown = false, activePairingCode = "482910"))
    }
}
