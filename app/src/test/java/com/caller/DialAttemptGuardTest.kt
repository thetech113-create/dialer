package com.caller

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Duplicate-dial prevention (app log 2026-11-28 08:04:32-33: the green button pressed again 0.585 s after the first
 * dial, while call #1 was still dialing, sent a second dial that Telecom refused - the system's "Call not sent").
 *
 * One unresolved initial dial attempt permits exactly one outgoing dispatch; a genuinely new dial is never blocked by
 * stale state. Each test drives the guard the way the app does: tryAcquire (tap) -> dispatch (placeCall) -> Telecom
 * callbacks (onCallAdded / state changes), with a fake clock and live-call snapshots.
 */
class DialAttemptGuardTest {

    private class Harness {
        var now = 10_000L
        val logs: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val guard = DialAttemptGuard(clock = { now }, log = { logs += it })
        val placed = AtomicInteger(0)

        /** A tap on the call button: MainActivity.startCall -> placeOutgoingCall -> dispatchPlaceCall. */
        fun tap(snapshot: LiveCallSnapshot = IDLE, branch: String = "direct_place"): Long? {
            val id = guard.tryAcquire("keypad", snapshot) ?: return null
            guard.dispatch(id, branch) { placed.incrementAndGet() }
            return id
        }

        fun count(event: String) = logs.count { it.startsWith("$event ") }
    }

    private companion object {
        val IDLE = LiveCallSnapshot.NONE
        fun live(vararg calls: Pair<String, LiveCallPhase>, telecomInCall: Boolean = calls.isNotEmpty()) =
            LiveCallSnapshot(mapOf(*calls), telecomInCall)

        val SETTING_UP = LiveCallPhase.OUTGOING_SETTING_UP
        val ESTABLISHED = LiveCallPhase.ESTABLISHED
    }

    // ---------- A. Rapid repeated taps ----------

    @Test
    fun rapidTaps_2_5_10_dispatchExactlyOnce() {
        for (taps in listOf(2, 5, 10)) {
            val h = Harness()
            repeat(taps) { h.tap(); h.now += 60 }
            assertEquals("taps=$taps", 1, h.placed.get())
            assertEquals("taps=$taps", taps - 1, h.count(DialAttemptEvents.DUPLICATE_BLOCKED))
            assertTrue(h.guard.locked.value)
        }
    }

    // Taps keep coming while Telecom adds the call and it dials (the log case): still one dispatch.
    @Test
    fun rapidTaps_acrossTelecomAddingTheCall_dispatchExactlyOnce() {
        val h = Harness()
        h.tap()
        h.now += 178
        h.tap()
        h.guard.onTelecomCallAdded("c1", SETTING_UP)
        repeat(8) { h.now += 50; h.tap(live("c1" to SETTING_UP)) }
        assertEquals(1, h.placed.get())
        assertEquals(DialAttemptState.TELECOM_ADDED, h.guard.currentState())
    }

    // ---------- B. Simultaneous events ----------

    @Test
    fun concurrentRequests_onlyOnePassesTheAtomicGuard() {
        repeat(200) { round ->
            val h = Harness()
            val start = CountDownLatch(1)
            val acquired = AtomicInteger(0)
            val threads = (1..8).map {
                thread {
                    start.await()
                    val id = h.guard.tryAcquire("thread$it", IDLE)
                    if (id != null) {
                        acquired.incrementAndGet()
                        h.guard.dispatch(id, "direct_place") { h.placed.incrementAndGet() }
                    }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals("round=$round", 1, acquired.get())
            assertEquals("round=$round", 1, h.placed.get())
        }
    }

    // Two dispatches of the same attempt at once (e.g. direct + a delayed hold-then-place): one placeCall.
    @Test
    fun concurrentDispatchOfOneAttempt_placesOnce() {
        repeat(200) {
            val h = Harness()
            val id = h.guard.tryAcquire("keypad", IDLE)!!
            val start = CountDownLatch(1)
            val threads = listOf("direct_place", "hold_then_place", "direct_place", "hold_then_place").map { branch ->
                thread { start.await(); h.guard.dispatch(id, branch) { h.placed.incrementAndGet() } }
            }
            start.countDown()
            threads.forEach { it.join() }
            assertEquals(1, h.placed.get())
        }
    }

    // ---------- C. UI recreation ----------

    @Test
    fun keypadRecreatedWhileDialEstablishing_staysDisabledForSameAttempt_noSecondDial() {
        val h = Harness()
        val first = h.tap()!!
        // The recreated keypad has no state of its own: it reads the process-wide guard.
        val recreatedScreenSeesLocked = h.guard.locked.value
        assertTrue(recreatedScreenSeesLocked)
        assertEquals(first, h.guard.currentAttempt()!!.id)
        assertNull(h.tap())
        assertEquals(1, h.placed.get())
    }

    @Test
    fun keypadRecreatedAfterAttemptResolved_isEnabled_notStaleDisabled() {
        val h = Harness()
        h.tap()
        h.guard.onTelecomCallAdded("c1", SETTING_UP)
        h.guard.onCallUpdate("c1", ESTABLISHED)
        assertFalse(h.guard.locked.value)
    }

    // ---------- D. Delayed Telecom callback ----------

    @Test
    fun onCallAddedDelayed_secondPressStaysBlocked() {
        val h = Harness()
        h.tap()
        h.now += 2_000 // no onCallAdded yet
        assertNull(h.tap())
        assertEquals(DialAttemptState.WAITING_FOR_TELECOM, h.guard.currentState())
        h.now += 500
        h.guard.onTelecomCallAdded("c1", SETTING_UP)
        assertNull(h.tap(live("c1" to SETTING_UP)))
        assertEquals(1, h.placed.get())
        assertTrue(h.guard.locked.value)
    }

    // Not released because the call screen was launched or drawn: only Telecom state moves the attempt.
    @Test
    fun callStillDialingLongAfterPlaceCall_staysLocked() {
        val h = Harness()
        h.tap()
        h.guard.onTelecomCallAdded("c1", SETTING_UP)
        h.now += 45_000 // a long-ringing outgoing call
        h.guard.revalidate(live("c1" to SETTING_UP))
        assertTrue(h.guard.locked.value)
        assertNull(h.tap(live("c1" to SETTING_UP)))
    }

    // ---------- E. Telecom never adds the call ----------

    @Test
    fun telecomNeverAddsCall_attemptTimesOut_newDialWorks() {
        val h = Harness()
        h.tap()
        h.now += DialAttemptGuard.TELECOM_ADD_WINDOW_MS + 1
        h.guard.revalidate(IDLE)
        assertEquals(DialAttemptState.FAILED, h.guard.currentState())
        assertFalse(h.guard.locked.value)
        assertNotNull(h.tap())
        assertEquals(2, h.placed.get())
        assertTrue(h.logs.any { it.startsWith(DialAttemptEvents.FAILED) && "reason=telecom_never_added_call" in it })
    }

    // The post-dial check (2.5 s) finds no call for this dial: it releases the attempt, retry accepted.
    @Test
    fun postDialCheckReportsCallNotSent_releasesAttempt() {
        val h = Harness()
        val id = h.tap()!!
        h.now += 2_500
        h.guard.fail(id, "no_call_created_after_place_call")
        assertFalse(h.guard.locked.value)
        assertNotNull(h.tap())
    }

    // Telecom reports a call only this dial can have made, but our service was not given it: stays locked a little
    // longer (no dial Telecom would refuse), then is released - isInCall alone never locks forever.
    @Test
    fun unseenCallFromIdle_lockedThenReleased() {
        val h = Harness()
        h.tap()
        h.now += DialAttemptGuard.TELECOM_ADD_WINDOW_MS + 1
        val telecomOnly = live(telecomInCall = true)
        assertNull(h.tap(telecomOnly))
        h.now = 10_000L + DialAttemptGuard.UNSEEN_CALL_WINDOW_MS + 1
        assertNotNull(h.tap(telecomOnly))
    }

    // Telecom "in call" because of ANOTHER call that was up before this dial: says nothing about this dial.
    @Test
    fun telecomInCallBecauseOfOtherCall_doesNotKeepRefusedDialLocked() {
        val h = Harness()
        h.tap(live("a" to ESTABLISHED), branch = "hold_then_place")
        h.now += DialAttemptGuard.TELECOM_ADD_WINDOW_MS + 1
        h.guard.revalidate(live("a" to ESTABLISHED))
        assertEquals(DialAttemptState.FAILED, h.guard.currentState())
        assertFalse(h.guard.locked.value)
    }

    // ---------- F. Immediate redial ----------

    @Test
    fun disconnectThenImmediateRedialToDifferentNumber_proceedsWithoutDelay() {
        val h = Harness()
        h.tap()
        h.guard.onTelecomCallAdded("c1", SETTING_UP)
        h.guard.onCallUpdate("c1", ESTABLISHED)
        h.guard.onCallUpdate("c1", null) // ended
        // No time passes: the redial goes straight through.
        assertNotNull(h.tap(IDLE))
        assertEquals(2, h.placed.get())
    }

    // Hung up while still dialing, then dial again at once.
    @Test
    fun hangUpWhileDialingThenImmediateRedial_proceeds() {
        val h = Harness()
        h.tap()
        h.guard.onTelecomCallAdded("c1", SETTING_UP)
        h.guard.onCallUpdate("c1", null)
        assertEquals(DialAttemptState.DISCONNECTED, h.guard.currentState())
        assertFalse(h.guard.locked.value)
        assertNotNull(h.tap(IDLE))
    }

    // The ended call's state callback never arrived (service went away): live state shows no call -> not blocked.
    @Test
    fun boundCallGoneWithoutCallback_staleAttemptReleasedOnNextTap() {
        val h = Harness()
        h.tap()
        h.guard.onTelecomCallAdded("c1", SETTING_UP)
        assertNotNull(h.tap(IDLE))
        assertTrue(h.logs.any { it.startsWith(DialAttemptEvents.DISCONNECTED) })
    }

    // ---------- G. Legitimate hold-and-place ----------

    @Test
    fun existingCallActive_holdAndPlaceIsAllowed() {
        val h = Harness()
        val id = h.tap(live("a" to ESTABLISHED), branch = "hold_then_place")
        assertNotNull(id)
        assertEquals(1, h.placed.get())
        // The new call is bound, not the existing one.
        h.guard.onTelecomCallAdded("b", SETTING_UP)
        assertEquals("b", h.guard.currentAttempt()!!.callId)
        // ...and while the new call dials, another tap is refused.
        assertNull(h.tap(live("a" to ESTABLISHED, "b" to SETTING_UP)))
        // Once it is answered, calling a third person is allowed again.
        h.guard.onCallUpdate("b", ESTABLISHED)
        assertNotNull(h.tap(live("a" to ESTABLISHED, "b" to ESTABLISHED), branch = "hold_then_place"))
    }

    @Test
    fun existingCallOnHold_placeIsAllowed() {
        val h = Harness()
        assertNotNull(h.tap(live("a" to ESTABLISHED), branch = "hold_then_place"))
    }

    // A ringing incoming call does not block an outgoing dial.
    @Test
    fun ringingIncomingCall_doesNotBlock() {
        val h = Harness()
        assertNotNull(h.tap(live("in" to LiveCallPhase.RINGING_INCOMING)))
    }

    // An outgoing call being set up that came from elsewhere (e.g. a car kit) blocks: Telecom would refuse.
    @Test
    fun outgoingCallFromElsewhereStillDialing_blocks() {
        val h = Harness()
        assertNull(h.tap(live("x" to SETTING_UP)))
        assertEquals(0, h.placed.get())
    }

    // ---------- H. Old callback race ----------

    @Test
    fun lateCallbacksFromAttemptA_cannotChangeAttemptB() {
        val h = Harness()
        val a = h.tap()!!
        h.guard.onTelecomCallAdded("ca", SETTING_UP)
        h.guard.onCallUpdate("ca", ESTABLISHED) // A finished its setup
        val b = h.tap(live("ca" to ESTABLISHED), branch = "hold_then_place")!!
        val bBefore = h.guard.currentAttempt()

        // Late, out-of-order callbacks belonging to A.
        h.guard.onCallUpdate("ca", null)
        h.guard.onCallUpdate("ca", ESTABLISHED)
        h.guard.onTelecomCallAdded("ca", ESTABLISHED)
        h.guard.fail(a, "late_post_dial_check")
        h.guard.userChoiceMade(a)
        assertFalse(h.guard.dispatch(a, "hold_then_place") { h.placed.incrementAndGet() })

        assertEquals(bBefore, h.guard.currentAttempt())
        assertEquals(b, h.guard.currentAttempt()!!.id)
        assertEquals(DialAttemptState.WAITING_FOR_TELECOM, h.guard.currentState())
        assertTrue(h.guard.locked.value)
        assertEquals(2, h.placed.get())
        assertTrue(h.logs.any { it.startsWith(DialAttemptEvents.STALE_CALLBACK_IGNORED) && "attempt=$a" in it && "current=$b" in it })

        // B still binds and resolves through its own call.
        h.guard.onTelecomCallAdded("cb", SETTING_UP)
        assertEquals("cb", h.guard.currentAttempt()!!.callId)
    }

    // A's call ended before B started: a late "call added" for A's call must not be taken as B's call.
    @Test
    fun lateCallAddedForEndedCallOfA_isNotBoundToB() {
        val h = Harness()
        h.tap()
        h.guard.onTelecomCallAdded("ca", SETTING_UP)
        h.guard.onCallUpdate("ca", null)
        h.tap(IDLE)
        h.guard.onTelecomCallAdded("ca", ESTABLISHED)
        assertEquals(DialAttemptState.WAITING_FOR_TELECOM, h.guard.currentState())
        assertNull(h.guard.currentAttempt()!!.callId)
    }

    // ---------- I. Every dispatch branch shares the same atomic protection ----------

    @Test
    fun directPlace_dispatchesOnceThroughGuard() {
        val h = Harness()
        val id = h.guard.tryAcquire("keypad", IDLE)!!
        assertTrue(h.guard.dispatch(id, "direct_place") { h.placed.incrementAndGet() })
        assertFalse(h.guard.dispatch(id, "direct_place") { h.placed.incrementAndGet() })
        assertEquals(1, h.placed.get())
    }

    @Test
    fun holdThenPlace_dispatchesOnceThroughGuard_andNotAfterRelease() {
        val h = Harness()
        val id = h.guard.tryAcquire("keypad", live("a" to ESTABLISHED))!!
        // A tap during the 200 ms hold delay is refused by the same guard.
        assertNull(h.guard.tryAcquire("keypad", live("a" to ESTABLISHED)))
        h.now += 200
        assertTrue(h.guard.dispatch(id, "hold_then_place") { h.placed.incrementAndGet() })
        assertFalse(h.guard.dispatch(id, "direct_place") { h.placed.incrementAndGet() })
        assertEquals(1, h.placed.get())

        // A delayed hold-then-place dispatch whose attempt was already released places nothing.
        val h2 = Harness()
        val id2 = h2.guard.tryAcquire("keypad", live("a" to ESTABLISHED))!!
        h2.guard.fail(id2, "cancelled")
        assertFalse(h2.guard.dispatch(id2, "hold_then_place") { h2.placed.incrementAndGet() })
        assertEquals(0, h2.placed.get())
    }

    @Test
    fun allEntryPointsShareOneGuard() {
        for (first in listOf("keypad", "widget", "pc_link", "voicemail")) {
            val h = Harness()
            val id = h.guard.tryAcquire(first, IDLE)!!
            h.guard.dispatch(id, first) { h.placed.incrementAndGet() }
            for (other in listOf("keypad", "widget", "pc_link", "voicemail")) {
                assertNull("$first then $other", h.guard.tryAcquire(other, IDLE))
            }
            assertEquals(1, h.placed.get())
        }
    }

    // ---------- J. Failure recovery ----------

    @Test
    fun placeCallThrows_buttonRestored_retryAccepted() {
        val h = Harness()
        val id = h.guard.tryAcquire("keypad", IDLE)!!
        try {
            h.guard.dispatch(id, "direct_place") { throw SecurityException("denied") }
            fail("expected the exception to reach the caller")
        } catch (_: SecurityException) {
        }
        assertEquals(DialAttemptState.FAILED, h.guard.currentState())
        assertFalse(h.guard.locked.value)
        assertNotNull(h.tap())
        assertEquals(1, h.placed.get())
    }

    @Test
    fun simChoiceCancelled_released_retryAccepted() {
        val h = Harness()
        val id = h.guard.tryAcquire("keypad", IDLE)!!
        h.guard.awaitUserChoice(id)
        h.now += 30_000 // picker open longer than the dispatch window: still locked
        assertNull(h.tap())
        h.guard.fail(id, "sim_choice_cancelled")
        assertFalse(h.guard.locked.value)
        assertNotNull(h.tap())
    }

    // An accepted attempt that is never dispatched (e.g. its screen died mid-way) is not trusted past its deadline.
    @Test
    fun acceptedButNeverDispatched_releasedAfterItsDeadline() {
        val h = Harness()
        h.guard.tryAcquire("keypad", IDLE)!!
        h.now += DialAttemptGuard.DISPATCH_WINDOW_MS - 1
        assertNull(h.tap())
        h.now += 2
        assertNotNull(h.tap())
        assertTrue(h.logs.any { it.startsWith(DialAttemptEvents.FAILED) && "never_dispatched_before_deadline" in it })
    }

    // ---------- Logging ----------

    @Test
    fun everyLogLineCarriesAnAttemptId_andAllEventsAreLogged() {
        val h = Harness()
        val a = h.tap()!!
        h.tap()
        h.guard.onTelecomCallAdded("c1", SETTING_UP)
        h.guard.onCallUpdate("c1", ESTABLISHED)
        h.tap(live("c1" to ESTABLISHED), branch = "hold_then_place")
        h.guard.onCallUpdate("c1", null)
        h.now += DialAttemptGuard.TELECOM_ADD_WINDOW_MS + 1
        h.guard.revalidate(live("c1" to ESTABLISHED))
        h.tap(IDLE)
        h.guard.onTelecomCallAdded("c3", SETTING_UP)
        h.guard.onCallUpdate("c3", null)

        assertTrue(h.logs.all { Regex("attempt=\\d+").containsMatchIn(it) })
        for (event in listOf(
            DialAttemptEvents.STARTED, DialAttemptEvents.BUTTON_DISABLED, DialAttemptEvents.GUARD_ACQUIRED,
            DialAttemptEvents.DUPLICATE_BLOCKED, DialAttemptEvents.TELECOM_ADDED, DialAttemptEvents.ESTABLISHED,
            DialAttemptEvents.FAILED, DialAttemptEvents.DISCONNECTED, DialAttemptEvents.GUARD_RELEASED,
            DialAttemptEvents.STALE_CALLBACK_IGNORED
        )) {
            assertTrue("missing $event", h.count(event) > 0)
        }
        assertTrue(h.logs.first().contains("attempt=$a"))
    }
}
