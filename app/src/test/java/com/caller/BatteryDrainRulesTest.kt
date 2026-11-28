package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Battery drain (a user uninstalled the app over it). The user's own log (OPPO, Android 16, app exempt from battery
 * optimisation) showed, with the phone idle from 00:00 to 04:17: an account sync every 45 s - with no device linked
 * ("nothing uploaded: no phone is linked") - each one a cloud request, a call-log read and two full Recents reloads of
 * about 6 s each. These rules pin what may run in the background now: as instant as before while the phone is in use,
 * nothing while the display is off or nothing is linked.
 */
class BatteryDrainRulesTest {

    // Nothing linked: no background sync at all (it was every 45 s, all day and night).
    @Test
    fun noLinkedDevice_noPeriodicSync() {
        assertFalse(shouldRunPeriodicSync(hasLinkedDevice = false, displayOn = true))
        assertFalse(shouldRunPeriodicSync(hasLinkedDevice = false, displayOn = false))
    }

    // Display off (phone in the pocket / at night): no background sync, even with a linked device.
    @Test
    fun displayOff_noPeriodicSync() {
        assertFalse(shouldRunPeriodicSync(hasLinkedDevice = true, displayOn = false))
    }

    // A linked device and the phone in use: as quick as before (every 45 s).
    @Test
    fun linkedAndInUse_syncsAsQuickAsBefore() {
        assertTrue(shouldRunPeriodicSync(hasLinkedDevice = true, displayOn = true))
        assertTrue(PERIODIC_SYNC_INTERVAL_MS <= 45_000L)
    }

    // Instant when the user looks: display on / app opened syncs at once with a linked device...
    @Test
    fun displayOnOrAppOpened_syncsAtOnce() {
        assertTrue(shouldSyncOnEvent(hasLinkedDevice = true, msSinceLastSyncStart = EVENT_SYNC_MIN_GAP_MS))
        assertTrue(shouldSyncOnEvent(hasLinkedDevice = true, msSinceLastSyncStart = 10 * 60 * 1000L))
    }

    // ...but not with nothing linked, and not as a burst right after another sync.
    @Test
    fun eventSync_guarded() {
        assertFalse(shouldSyncOnEvent(hasLinkedDevice = false, msSinceLastSyncStart = 10 * 60 * 1000L))
        assertFalse(shouldSyncOnEvent(hasLinkedDevice = true, msSinceLastSyncStart = 2_000L))
    }

    // The in-call wake lock is let go once no call needs it - not held for up to 4 hours after a dial that never became
    // a call or a cleanup that did not run.
    @Test
    fun callWakeLock_releasedWhenNoCallForConsecutiveChecks() {
        assertTrue(shouldReleaseIdleCallWakeLock(ownLiveCallCount = 0, telecomFrameworkInCall = false, idleChecks = CALL_WAKE_LOCK_IDLE_CHECKS_TO_RELEASE))
        assertTrue(CALL_WAKE_LOCK_IDLE_CHECKS_TO_RELEASE * CALL_WAKE_LOCK_WATCHDOG_MS <= 5 * 60 * 1000L)
    }

    // A call (ours, or one Telecom still has, e.g. an immediate redial being set up) keeps it; one idle check is not enough.
    @Test
    fun callWakeLock_keptWhileACallNeedsIt() {
        assertFalse(shouldReleaseIdleCallWakeLock(ownLiveCallCount = 1, telecomFrameworkInCall = false, idleChecks = 5))
        assertFalse(shouldReleaseIdleCallWakeLock(ownLiveCallCount = 0, telecomFrameworkInCall = true, idleChecks = 5))
        assertFalse(shouldReleaseIdleCallWakeLock(ownLiveCallCount = 0, telecomFrameworkInCall = false, idleChecks = 1))
    }
}
