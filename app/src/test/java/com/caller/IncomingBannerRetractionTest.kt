package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Incoming call showed TWO answering surfaces at once: the full-screen call screen and, on top of it, the
 * heads-up Answer/Decline banner. The banner is now retracted (the same notification re-posted quietly) once
 * CallActivity is confirmed on screen for the ringing call, and brought back if that screen goes away while
 * the call still rings. These pin the per-call rules.
 */
class IncomingBannerRetractionTest {

    // Full-screen call screen visible for the call that is ringing -> one surface: banner retracted.
    @Test
    fun callScreenVisibleForRingingCall_postsQuietly() {
        assertTrue(shouldPostIncomingQuietly(ringingCallId = "A", retractedCallId = "A", callScreenVisible = true))
    }

    // Never pre-emptive: before the screen has been confirmed for this call, the banner stays (a blocked or
    // failed screen launch must not leave the user with no way to answer).
    @Test
    fun notYetConfirmed_keepsBanner() {
        assertFalse(shouldPostIncomingQuietly(ringingCallId = "A", retractedCallId = null, callScreenVisible = true))
    }

    // The quiet state belongs to one call only: a different (new) ringing call always gets its banner.
    @Test
    fun differentRingingCall_keepsBanner() {
        assertFalse(shouldPostIncomingQuietly(ringingCallId = "B", retractedCallId = "A", callScreenVisible = true))
    }

    // Screen no longer visible -> banner, even for the same call.
    @Test
    fun callScreenNotVisible_keepsBanner() {
        assertFalse(shouldPostIncomingQuietly(ringingCallId = "A", retractedCallId = "A", callScreenVisible = false))
    }

    // No ringing call (answered / ended) -> nothing to quieten.
    @Test
    fun noRingingCall_notQuiet() {
        assertFalse(shouldPostIncomingQuietly(ringingCallId = null, retractedCallId = "A", callScreenVisible = true))
    }

    // Left the call screen (Home / another app / Back) while the same call still rings -> banner comes back.
    @Test
    fun leftScreenWhileStillRinging_restoresBanner() {
        assertTrue(shouldRestoreIncomingBanner(retractedCallId = "A", ringingCallId = "A", screenInteractive = true))
    }

    // Power key (display off) -> stay quiet: re-alerting would fight the user silencing the call.
    @Test
    fun displayOff_doesNotRestoreBanner() {
        assertFalse(shouldRestoreIncomingBanner(retractedCallId = "A", ringingCallId = "A", screenInteractive = false))
    }

    // The retracted call is no longer the one ringing (answered, ended, or another call) -> no restore.
    @Test
    fun otherOrNoRingingCall_doesNotRestoreBanner() {
        assertFalse(shouldRestoreIncomingBanner(retractedCallId = "A", ringingCallId = "B", screenInteractive = true))
        assertFalse(shouldRestoreIncomingBanner(retractedCallId = "A", ringingCallId = null, screenInteractive = true))
        assertFalse(shouldRestoreIncomingBanner(retractedCallId = null, ringingCallId = "A", screenInteractive = true))
    }
}
