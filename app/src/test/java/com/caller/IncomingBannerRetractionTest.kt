package com.caller

import org.junit.Assert.assertEquals
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

    // ---- Full screen vs bubble overlay ----
    // Full screen (call screen confirmed for the ringing call): quiet. Bubble overlay / another app / call waiting:
    // the high-priority banner, which must actually pop.

    @Test
    fun fullScreenVisibleForRingingCall_isFullScreenSurface() {
        assertEquals(IncomingSurface.FULL_SCREEN, incomingSurfaceFor(ringingCallId = "A", retractedCallId = "A", callScreenVisible = true))
    }

    // In another app the bubble overlay shows and the call screen is not visible: banner, never quiet.
    @Test
    fun bubbleOverlayInAnotherApp_isBannerSurface() {
        assertEquals(IncomingSurface.BANNER, incomingSurfaceFor(ringingCallId = "A", retractedCallId = null, callScreenVisible = false))
        // ...also after the full screen had made this call quiet and the user went to another app.
        assertEquals(IncomingSurface.BANNER, incomingSurfaceFor(ringingCallId = "A", retractedCallId = "A", callScreenVisible = false))
    }

    // First banner post for a call in another app: it alerts (heads-up Answer/Decline pops).
    @Test
    fun bubbleOverlay_firstBannerPostForCall_alerts() {
        assertTrue(shouldRealertIncomingBanner("A", IncomingSurface.BANNER, bannerAlertedCallId = null, screenInteractive = true))
    }

    // The bug: full screen made it quiet, then the user switched to another app (bubble overlay). The banner post is an
    // update of an already-alerted notification posted with setOnlyAlertOnce, so it must re-alert to pop at all.
    @Test
    fun fullScreenThenAnotherApp_bannerReAlerts() {
        // While the full screen showed it, the quiet post cleared the alerted marker.
        val alertedAfterQuiet: String? = null
        assertTrue(shouldRealertIncomingBanner("A", IncomingSurface.BANNER, alertedAfterQuiet, screenInteractive = true))
    }

    // Call waiting with the bubble overlay: the waiting call replaces the ongoing call's notification (same id) and must
    // pop, even though the first call's notification alerted before.
    @Test
    fun callWaiting_waitingCallBannerAlerts() {
        assertTrue(shouldRealertIncomingBanner("B", IncomingSurface.BANNER, bannerAlertedCallId = "A", screenInteractive = true))
    }

    // Repeated banner posts for the same call (reposter, name resolved) do not pop again every few seconds.
    @Test
    fun repeatedBannerPostsForSameCall_doNotReAlert() {
        assertFalse(shouldRealertIncomingBanner("A", IncomingSurface.BANNER, bannerAlertedCallId = "A", screenInteractive = true))
    }

    // Full screen visible: never alerts (no banner on top of the call screen).
    @Test
    fun fullScreenSurface_neverReAlerts() {
        assertFalse(shouldRealertIncomingBanner("A", IncomingSurface.FULL_SCREEN, bannerAlertedCallId = null, screenInteractive = true))
    }

    // Display off (power key silencing): no re-alert; it alerts once the display is on again.
    @Test
    fun displayOff_doesNotReAlert() {
        assertFalse(shouldRealertIncomingBanner("A", IncomingSurface.BANNER, bannerAlertedCallId = null, screenInteractive = false))
    }

    @Test
    fun noRingingCall_doesNotReAlert() {
        assertFalse(shouldRealertIncomingBanner(null, IncomingSurface.BANNER, bannerAlertedCallId = null, screenInteractive = true))
    }

    // ---- Full screen opening: no banner flash before the call screen ----

    // Home screen / this app, unlocked: Android would show the full-screen notification as a banner for a moment
    // before the call screen covers it. Open it quietly instead.
    @Test
    fun unlockedPhoneInUse_fullScreenOpensQuietly() {
        assertTrue(shouldOpenFullScreenQuietly("A", allowFullScreen = true, deviceLocked = false, screenInteractive = true, otherLiveCalls = 0, alreadyTriedForCallId = null))
    }

    // While it opens, the call is on the full-screen surface: re-posts stay quiet and never re-alert.
    @Test
    fun whileFullScreenOpens_surfaceIsFullScreen_noAlert() {
        val surface = incomingSurfaceFor("A", retractedCallId = null, callScreenVisible = false, openingFullScreenCallId = "A")
        assertEquals(IncomingSurface.FULL_SCREEN, surface)
        assertFalse(shouldRealertIncomingBanner("A", surface, bannerAlertedCallId = null, screenInteractive = true))
    }

    // Locked or display off: Android launches the full-screen intent itself (no banner) - keep that path.
    @Test
    fun lockedOrDisplayOff_keepsFullScreenIntentPath() {
        assertFalse(shouldOpenFullScreenQuietly("A", allowFullScreen = true, deviceLocked = true, screenInteractive = true, otherLiveCalls = 0, alreadyTriedForCallId = null))
        assertFalse(shouldOpenFullScreenQuietly("A", allowFullScreen = true, deviceLocked = false, screenInteractive = false, otherLiveCalls = 0, alreadyTriedForCallId = null))
    }

    // Another app in front (bubble overlay): no full screen, so the banner - never quiet.
    @Test
    fun bubbleOverlay_neverOpensQuietly() {
        assertFalse(shouldOpenFullScreenQuietly("A", allowFullScreen = false, deviceLocked = false, screenInteractive = true, otherLiveCalls = 0, alreadyTriedForCallId = null))
        assertEquals(IncomingSurface.BANNER, incomingSurfaceFor("A", retractedCallId = null, callScreenVisible = false, openingFullScreenCallId = null))
    }

    // Call waiting: the call screen shows the active call, the waiting call's banner is the way to answer.
    @Test
    fun callWaiting_neverOpensQuietly() {
        assertFalse(shouldOpenFullScreenQuietly("B", allowFullScreen = true, deviceLocked = false, screenInteractive = true, otherLiveCalls = 1, alreadyTriedForCallId = null))
    }

    // Only once per call: after the call screen did not appear (or was left), the banner path stays.
    @Test
    fun quietOpenOnlyOncePerCall() {
        assertFalse(shouldOpenFullScreenQuietly("A", allowFullScreen = true, deviceLocked = false, screenInteractive = true, otherLiveCalls = 0, alreadyTriedForCallId = "A"))
        assertTrue(shouldOpenFullScreenQuietly("B", allowFullScreen = true, deviceLocked = false, screenInteractive = true, otherLiveCalls = 0, alreadyTriedForCallId = "A"))
    }

    // The call screen never appeared: the banner comes back (and pops), so the call can always be answered.
    @Test
    fun callScreenNotShown_fallsBackToBanner_andItPops() {
        assertTrue(shouldFallBackToBanner(ringingCallId = "A", openingCallId = "A", callScreenVisible = false))
        val surfaceAfterFallback = incomingSurfaceFor("A", retractedCallId = null, callScreenVisible = false, openingFullScreenCallId = null)
        assertEquals(IncomingSurface.BANNER, surfaceAfterFallback)
        assertTrue(shouldRealertIncomingBanner("A", surfaceAfterFallback, bannerAlertedCallId = null, screenInteractive = true))
    }

    @Test
    fun callScreenShown_noFallback() {
        assertFalse(shouldFallBackToBanner(ringingCallId = "A", openingCallId = "A", callScreenVisible = true))
        assertFalse(shouldFallBackToBanner(ringingCallId = "B", openingCallId = "A", callScreenVisible = false))
        assertFalse(shouldFallBackToBanner(ringingCallId = null, openingCallId = "A", callScreenVisible = false))
    }
}
