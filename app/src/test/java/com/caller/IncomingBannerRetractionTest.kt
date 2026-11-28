package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The incoming call's answering surface, strictly one at a time (IncomingMode):
 * - Full screen (home screen / inside the app / locked / display off): the Answer/Decline notification is quiet from
 *   the very first post - the banner never shows, not before the full screen, not with it, even if the call screen is
 *   slow to appear (a late full screen is fine).
 * - Bubble overlay (another app in front), call waiting, or a call that is on the banner for good: the high-priority
 *   banner, posted in full so that it pops - and no full screen is opened after it.
 *
 * Device facts behind this (user's OPPO CPH2827, Android 16 logs): canUseFullScreenIntent=false, so a full-screen-intent
 * notification is only shown as a banner; the call screen was always opened by the app's own start (~0.36 s inside the
 * app). The first "quiet first" attempt fell back to the banner after 1.5 s, before a slow call screen arrived - the
 * "banner, then full screen" the user rejected.
 */
class IncomingBannerRetractionTest {

    private fun mode(
        anotherAppInFront: Boolean = false,
        otherLiveCalls: Int = 0,
        callScreenShowingThisCall: Boolean = false,
        bannerOnlyForThisCall: Boolean = false,
        silencedByPowerKey: Boolean = false
    ) = decideIncomingMode(anotherAppInFront, otherLiveCalls, callScreenShowingThisCall, bannerOnlyForThisCall, silencedByPowerKey)

    // ---- Full screen: no banner at all ----

    // Home screen / inside the app / locked, call screen not up yet: open it, quiet from the first post.
    @Test
    fun fullScreenAllowed_callScreenNotUpYet_opensQuietly() {
        assertEquals(IncomingMode.FULL_SCREEN_OPENING, mode())
    }

    // The call screen is up for the call: quiet, nothing else.
    @Test
    fun callScreenUpForCall_isShown() {
        assertEquals(IncomingMode.FULL_SCREEN_SHOWN, mode(callScreenShowingThisCall = true))
    }

    // Neither full-screen mode ever posts the banner or re-alerts.
    @Test
    fun fullScreenModes_neverAlert() {
        assertFalse(shouldRealertIncomingBanner("A", IncomingSurface.FULL_SCREEN, bannerAlertedCallId = null, screenInteractive = true))
    }

    // Paused (shade pulled down) but still started, or display off (power key): still the call screen's call - quiet.
    @Test
    fun pausedOrDisplayOff_callScreenStillCountsAsShowing() {
        assertTrue(isCallScreenShowingCall("A", confirmedCallId = "A", callScreenStarted = true, displayInteractive = true))
        assertTrue(isCallScreenShowingCall("A", confirmedCallId = "A", callScreenStarted = false, displayInteractive = false))
    }

    // Left (stopped, display on), not confirmed yet, or confirmed for another call: not showing this call.
    @Test
    fun callScreenNotShowingThisCall() {
        assertFalse(isCallScreenShowingCall("A", confirmedCallId = "A", callScreenStarted = false, displayInteractive = true))
        assertFalse(isCallScreenShowingCall("A", confirmedCallId = null, callScreenStarted = true, displayInteractive = true))
        assertFalse(isCallScreenShowingCall("B", confirmedCallId = "A", callScreenStarted = true, displayInteractive = true))
        assertFalse(isCallScreenShowingCall(null, confirmedCallId = null, callScreenStarted = true, displayInteractive = true))
    }

    // The user's phone may not use a full-screen intent: it would only be a banner - so never attach it there.
    @Test
    fun fullScreenIntent_onlyWhereAndroidOpensItWithoutABanner() {
        assertFalse(fullScreenIntentOpensWithoutBanner(fullScreenIntentUsable = false, deviceLocked = true, screenInteractive = true))
        assertFalse(fullScreenIntentOpensWithoutBanner(fullScreenIntentUsable = false, deviceLocked = false, screenInteractive = false))
        // Unlocked phone in use: Android shows a full-screen intent as a banner first - never.
        assertFalse(fullScreenIntentOpensWithoutBanner(fullScreenIntentUsable = true, deviceLocked = false, screenInteractive = true))
        // Phones where it is usable: locked / display off open it without a banner.
        assertTrue(fullScreenIntentOpensWithoutBanner(fullScreenIntentUsable = true, deviceLocked = true, screenInteractive = true))
        assertTrue(fullScreenIntentOpensWithoutBanner(fullScreenIntentUsable = true, deviceLocked = false, screenInteractive = false))
    }

    // ---- Opening the call screen: retries, long wait, then the banner ----

    @Test
    fun openingSteps() {
        assertEquals(StrictFullScreenStep.CONFIRMED, strictFullScreenStep(stillRingingSameCall = true, callScreenUp = true, elapsedMs = 1_000))
        assertEquals(StrictFullScreenStep.RETRY, strictFullScreenStep(stillRingingSameCall = true, callScreenUp = false, elapsedMs = 1_000))
        assertEquals(StrictFullScreenStep.RETRY, strictFullScreenStep(stillRingingSameCall = true, callScreenUp = false, elapsedMs = 2_500))
        assertEquals(StrictFullScreenStep.FALL_BACK, strictFullScreenStep(stillRingingSameCall = true, callScreenUp = false, elapsedMs = STRICT_FULL_SCREEN_DEADLINE_MS))
        assertEquals(StrictFullScreenStep.STOP, strictFullScreenStep(stillRingingSameCall = false, callScreenUp = false, elapsedMs = 1_000))
    }

    // A late full screen is fine: only the final checkpoint falls back, and it leaves a long gap after the last start.
    @Test
    fun onlyTheFinalCheckpointFallsBack_afterALongGap() {
        assertEquals(STRICT_FULL_SCREEN_DEADLINE_MS, STRICT_FULL_SCREEN_CHECKS_MS.last())
        STRICT_FULL_SCREEN_CHECKS_MS.dropLast(1).forEach { atMs ->
            assertEquals(StrictFullScreenStep.RETRY, strictFullScreenStep(stillRingingSameCall = true, callScreenUp = false, elapsedMs = atMs))
        }
        val lastStart = STRICT_FULL_SCREEN_CHECKS_MS[STRICT_FULL_SCREEN_CHECKS_MS.size - 2]
        assertTrue(STRICT_FULL_SCREEN_DEADLINE_MS - lastStart >= 3_000L)
        // The old 1.5 s fallback came before the slow call screen.
        assertTrue(STRICT_FULL_SCREEN_DEADLINE_MS > 1_500L)
    }

    // The fallback: the banner pops in full (the quiet first post had cleared the alerted marker).
    @Test
    fun afterFallback_bannerPopsInFull() {
        assertEquals(IncomingMode.BANNER, mode(bannerOnlyForThisCall = true))
        assertTrue(shouldRealertIncomingBanner("A", IncomingSurface.BANNER, bannerAlertedCallId = null, screenInteractive = true))
    }

    // A call screen the app opened automatically that arrives after the call went to the banner is sent back...
    @Test
    fun lateAutomaticCallScreen_isSentBack() {
        assertTrue(shouldRejectLateCallScreen("incoming_strict_full_screen", bannerOnlyForThisCall = true, userOpenedCallScreenForThisCall = false))
        assertTrue(shouldRejectLateCallScreen("incoming_strict_full_screen_retry", bannerOnlyForThisCall = true, userOpenedCallScreenForThisCall = false))
    }

    // ...but one the user opened (banner, bubble, Recents) or one for a call still on the full screen is kept.
    @Test
    fun userOpenedOrStillFullScreen_isKept() {
        assertFalse(shouldRejectLateCallScreen(null, bannerOnlyForThisCall = true, userOpenedCallScreenForThisCall = false))
        assertFalse(shouldRejectLateCallScreen("call_bubble_tap", bannerOnlyForThisCall = true, userOpenedCallScreenForThisCall = false))
        assertFalse(shouldRejectLateCallScreen("incoming_strict_full_screen", bannerOnlyForThisCall = false, userOpenedCallScreenForThisCall = false))
        // The user opened the call screen from the banner: it is the surface (quiet), not the banner.
        assertEquals(IncomingMode.FULL_SCREEN_SHOWN, mode(callScreenShowingThisCall = true, bannerOnlyForThisCall = true))
    }

    // The user opened the call screen from the banner, then a late automatic start arrives on that same screen: kept.
    @Test
    fun lateAutomaticStart_onUserOpenedScreen_isKept() {
        assertFalse(shouldRejectLateCallScreen("incoming_strict_full_screen_retry", bannerOnlyForThisCall = true, userOpenedCallScreenForThisCall = true))
    }

    // The call screen is up for the call: it wins over a stale or missing "which app is in front" reading.
    @Test
    fun callScreenUp_winsOverAStaleForegroundReading() {
        assertEquals(IncomingMode.FULL_SCREEN_SHOWN, mode(anotherAppInFront = true, callScreenShowingThisCall = true))
    }

    // Only a clear "another app" reading moves an opening full screen to the banner - not an empty/unknown one.
    @Test
    fun onlyAClearAnotherAppReadingCounts() {
        assertTrue(isAnotherAppReading("third_party_foreground"))
        assertFalse(isAnotherAppReading("unknown_foreground"))
        assertFalse(isAnotherAppReading("default_launcher"))
        assertFalse(isAnotherAppReading("own_dialer"))
        assertTrue(ANOTHER_APP_READINGS_TO_SWITCH >= 2)
    }

    // Power key while the call screen was opening: quiet and nothing opened (no display turned back on, no banner).
    @Test
    fun silencedByPowerKey_quietAndNothingOpened() {
        assertEquals(IncomingMode.FULL_SCREEN_SHOWN, mode(silencedByPowerKey = true))
        assertFalse(shouldRealertIncomingBanner("A", IncomingSurface.FULL_SCREEN, bannerAlertedCallId = null, screenInteractive = false))
    }

    // Power key after the opening started: no more starts (they would turn the display back on). Display already off
    // when the call came: the starts are what wakes the phone.
    @Test
    fun powerKeyDuringOpening_stopsFurtherStarts() {
        assertTrue(displayWentOffDuringOpening(lastScreenOffAtElapsed = 5_500L, openingStartedAtElapsed = 5_000L))
        assertFalse(displayWentOffDuringOpening(lastScreenOffAtElapsed = 4_000L, openingStartedAtElapsed = 5_000L))
        assertFalse(displayWentOffDuringOpening(lastScreenOffAtElapsed = 0L, openingStartedAtElapsed = 5_000L))
    }

    // ---- Bubble overlay / another app: the banner in full ----

    @Test
    fun anotherAppInFront_isBanner_andItPops() {
        assertEquals(IncomingMode.BANNER, mode(anotherAppInFront = true))
        assertTrue(shouldRealertIncomingBanner("A", IncomingSurface.BANNER, bannerAlertedCallId = null, screenInteractive = true))
    }

    // Full screen first, then the user goes to another app while it rings: the banner pops again.
    @Test
    fun fullScreenThenAnotherApp_bannerPopsAgain() {
        assertEquals(IncomingMode.BANNER, mode(anotherAppInFront = true, bannerOnlyForThisCall = true))
        val alertedAfterQuiet: String? = null
        assertTrue(shouldRealertIncomingBanner("A", IncomingSurface.BANNER, alertedAfterQuiet, screenInteractive = true))
    }

    // Repeated banner posts for the same call (reposter, name resolved) do not pop again every few seconds.
    @Test
    fun repeatedBannerPosts_doNotPopAgain() {
        assertFalse(shouldRealertIncomingBanner("A", IncomingSurface.BANNER, bannerAlertedCallId = "A", screenInteractive = true))
    }

    // Display off (power key silencing): no re-alert.
    @Test
    fun displayOff_noReAlert() {
        assertFalse(shouldRealertIncomingBanner("A", IncomingSurface.BANNER, bannerAlertedCallId = null, screenInteractive = false))
        assertFalse(shouldRealertIncomingBanner(null, IncomingSurface.BANNER, bannerAlertedCallId = null, screenInteractive = true))
    }

    // ---- Call waiting ----

    // The call screen shows the active call; the waiting call's banner is the way to answer - no full screen with it.
    @Test
    fun callWaiting_isBanner_andThatBannerPops() {
        assertEquals(IncomingMode.BANNER, mode(otherLiveCalls = 1))
        assertEquals(IncomingMode.BANNER, mode(otherLiveCalls = 1, callScreenShowingThisCall = true))
        assertTrue(shouldRealertIncomingBanner("B", IncomingSurface.BANNER, bannerAlertedCallId = "A", screenInteractive = true))
    }

    // ---- Banner for good: until the phone is next woken ----

    @Test
    fun bannerOnly_holdsForTheSameCallUntilTheDisplayIsNextOn() {
        val mark = BannerOnlyMark("A", screenOnEpoch = 3)
        assertTrue(isBannerOnly(mark, "A", screenOnEpoch = 3))
        // Waking the phone (lock screen): the full screen gets another chance.
        assertFalse(isBannerOnly(mark, "A", screenOnEpoch = 4))
        // Another call is not affected.
        assertFalse(isBannerOnly(mark, "B", screenOnEpoch = 3))
        assertFalse(isBannerOnly(null, "A", screenOnEpoch = 3))
        assertFalse(isBannerOnly(mark, null, screenOnEpoch = 3))
    }

    // Banner-only means no full screen: the mode is the banner, not an opening.
    @Test
    fun bannerOnly_neverOpensTheFullScreen() {
        assertEquals(IncomingMode.BANNER, mode(bannerOnlyForThisCall = true))
    }

    // ---- Leaving the call screen ----

    @Test
    fun leftScreenWhileStillRinging_restoresBanner() {
        assertTrue(shouldRestoreIncomingBanner(retractedCallId = "A", ringingCallId = "A", screenInteractive = true))
    }

    @Test
    fun displayOff_doesNotRestoreBanner() {
        assertFalse(shouldRestoreIncomingBanner(retractedCallId = "A", ringingCallId = "A", screenInteractive = false))
    }

    @Test
    fun otherOrNoRingingCall_doesNotRestoreBanner() {
        assertFalse(shouldRestoreIncomingBanner(retractedCallId = "A", ringingCallId = "B", screenInteractive = true))
        assertFalse(shouldRestoreIncomingBanner(retractedCallId = "A", ringingCallId = null, screenInteractive = true))
        assertFalse(shouldRestoreIncomingBanner(retractedCallId = null, ringingCallId = "A", screenInteractive = true))
    }
}
