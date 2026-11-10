package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Full screen for an incoming call only in the dialer, on the home screen, when locked or with the screen off. */
class IncomingCallUiDecisionTest {
    private fun decide(
        foreground: String,
        locked: Boolean = false,
        interactive: Boolean = true
    ) = resolveIncomingCallUiDecision(
        foregroundPackage = foreground,
        myPackage = "com.caller",
        defaultDialerPackage = "com.caller",
        launcherPackage = "com.miui.home",
        isDeviceLocked = locked,
        isScreenInteractive = interactive
    )

    @Test
    fun anotherAppInFrontKeepsTheBannerOnly() {
        val decision = decide("com.whatsapp")
        assertFalse(decision.allowFullScreen)
        assertEquals("third_party_foreground", decision.reason)
    }

    @Test
    fun unknownForegroundKeepsTheBannerOnly() {
        assertFalse(decide("").allowFullScreen)
    }

    @Test
    fun dialerAndHomeScreenGetTheFullScreen() {
        assertTrue(decide("com.caller").allowFullScreen)
        assertTrue(decide("com.miui.home").allowFullScreen)
    }

    @Test
    fun lockedOrScreenOffGetsTheFullScreenEvenWithAnotherAppLastInFront() {
        assertTrue(decide("com.whatsapp", locked = true).allowFullScreen)
        assertTrue(decide("com.whatsapp", interactive = false).allowFullScreen)
    }
}
