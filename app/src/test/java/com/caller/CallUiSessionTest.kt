package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallUiSessionTest {
    @Test
    fun everyNewSessionHasItsOwnId() {
        val ids = (1..200).map { newCallUiSessionId() }.toSet()
        assertEquals(200, ids.size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun aScreenIsResetWhenAnotherKnownSessionArrives() {
        assertTrue(callUiSessionChanged(ownSessionId = "a1b2c3d4", incomingSessionId = "e5f6a7b8"))
    }

    @Test
    fun theSameSessionKeepsItsScreen() {
        assertFalse(callUiSessionChanged("a1b2c3d4", "a1b2c3d4"))
    }

    @Test
    fun anUnknownSessionNeverResetsAScreen() {
        // A launch that carries no id, or a screen that has not learned its id yet, behaves as before.
        assertFalse(callUiSessionChanged("a1b2c3d4", ""))
        assertFalse(callUiSessionChanged("", "e5f6a7b8"))
        assertFalse(callUiSessionChanged("", ""))
    }

    @Test
    fun aSnapshotOfAnOlderSessionDoesNotSeedANewCallScreen() {
        assertFalse(stickySnapshotBelongsToSession(snapshotSessionId = "a1b2c3d4", ownSessionId = "e5f6a7b8"))
    }

    @Test
    fun aSnapshotOfTheSameSessionSeedsItsScreen() {
        assertTrue(stickySnapshotBelongsToSession("a1b2c3d4", "a1b2c3d4"))
    }

    @Test
    fun aSnapshotOrScreenWithNoSessionIsStillAllowed() {
        assertTrue(stickySnapshotBelongsToSession("", "e5f6a7b8"))
        assertTrue(stickySnapshotBelongsToSession("a1b2c3d4", ""))
        assertTrue(stickySnapshotBelongsToSession("", ""))
    }

    @Test
    fun twoCallsInARowAreTwoSessionsAndTheSecondDoesNotInheritTheFirst() {
        // First call: screen opened for session 1. It ends. A second call is added: the service mints session 2.
        val first = newCallUiSessionId()
        val second = newCallUiSessionId()
        assertNotEquals(first, second)
        assertTrue("the old screen must reset for the new call", callUiSessionChanged(first, second))
        assertFalse("the first call's snapshot must not seed the second call", stickySnapshotBelongsToSession(first, second))
    }
}
