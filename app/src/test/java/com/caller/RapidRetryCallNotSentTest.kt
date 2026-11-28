package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's own "Call not sent", decided per dial: it appears only when THIS dial produced no call.
 *
 * Rapid-retry regression (app log 2026-11-25 12:11, ColorOS): a redial connected but was not adopted by the app;
 * it must never be reported "Call not sent". App log 2026-11-28 08:04: a second dial was refused while another
 * call was live; the old rule used the global "Telecom in call" flag, so the app stayed silent and only the
 * system's own "Call not sent" appeared.
 */
class RapidRetryCallNotSentTest {

    // Redial from idle connects but our service never received it: Telecom's live call can only be this dial.
    @Test
    fun connectedRetryFromIdle_neverReportsCallNotSent() {
        assertFalse(
            shouldReportCallNotSent(
                newCallForThisDial = false,
                callStartedAfterDispatch = false,
                telecomInCallAttributableToThisDial = true
            )
        )
    }

    // Reconciliation adopted the dial's call into this.calls (a new call id): not "not sent".
    @Test
    fun newCallForThisDial_neverReportsCallNotSent() {
        assertFalse(
            shouldReportCallNotSent(
                newCallForThisDial = true,
                callStartedAfterDispatch = false,
                telecomInCallAttributableToThisDial = false
            )
        )
    }

    // Our onCallAdded ran for the dialed call: not "not sent".
    @Test
    fun ownCallAdded_neverReportsCallNotSent() {
        assertFalse(
            shouldReportCallNotSent(
                newCallForThisDial = false,
                callStartedAfterDispatch = true,
                telecomInCallAttributableToThisDial = false
            )
        )
    }

    // The dial produced no call anywhere (the OEM silently refused placeCall): report.
    @Test
    fun noCallAnywhere_reportsCallNotSent() {
        assertTrue(
            shouldReportCallNotSent(
                newCallForThisDial = false,
                callStartedAfterDispatch = false,
                telecomInCallAttributableToThisDial = false
            )
        )
    }

    // A call was already up before this dial (e.g. hold-then-place) and the new dial was refused: Telecom being
    // "in call" belongs to the other call, so it is not attributable -> the app now reports it (it used to stay
    // silent while only the system's "Call not sent" appeared).
    @Test
    fun refusedDialWhileAnotherCallIsLive_reportsCallNotSent() {
        // Telecom is in call (the other call), but one call was live before this dial.
        val attributable = isTelecomInCallAttributableToDial(telecomInCall = true, callsLiveBeforeDial = 1)
        assertFalse(attributable)
        assertTrue(
            shouldReportCallNotSent(
                newCallForThisDial = false,
                callStartedAfterDispatch = false,
                telecomInCallAttributableToThisDial = attributable
            )
        )
    }

    // From idle, Telecom's live call can only be this dial's: credited to it, so no "not sent".
    @Test
    fun telecomInCallFromIdle_isAttributedToTheDial() {
        assertTrue(isTelecomInCallAttributableToDial(telecomInCall = true, callsLiveBeforeDial = 0))
        assertFalse(isTelecomInCallAttributableToDial(telecomInCall = false, callsLiveBeforeDial = 0))
    }

    // Any single signal that this dial produced a call is enough to stay silent.
    @Test
    fun anySignalOfThisDialsCall_suppressesCallNotSent() {
        assertFalse(shouldReportCallNotSent(newCallForThisDial = true, callStartedAfterDispatch = true, telecomInCallAttributableToThisDial = true))
        assertFalse(shouldReportCallNotSent(newCallForThisDial = true, callStartedAfterDispatch = false, telecomInCallAttributableToThisDial = true))
        assertFalse(shouldReportCallNotSent(newCallForThisDial = false, callStartedAfterDispatch = true, telecomInCallAttributableToThisDial = true))
    }
}
