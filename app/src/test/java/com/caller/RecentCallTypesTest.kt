package com.caller

import android.provider.CallLog.Calls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recents must never draw a call someone made TO this phone as an outgoing call that "didn't connect". */
class RecentCallTypesTest {
    @Test
    fun ordinaryCallsKeepTheirType() {
        assertEquals(CallType.OUTGOING, recentCallTypeFor(Calls.OUTGOING_TYPE))
        assertEquals(CallType.INCOMING, recentCallTypeFor(Calls.INCOMING_TYPE))
        assertEquals(CallType.MISSED, recentCallTypeFor(Calls.MISSED_TYPE))
    }

    @Test
    fun aStoppedIncomingCallIsAMissedCallNotAnOutgoingOne() {
        assertEquals(CallType.MISSED, recentCallTypeFor(Calls.BLOCKED_TYPE))
        assertEquals(CallType.MISSED, recentCallTypeFor(Calls.REJECTED_TYPE))
    }

    @Test
    fun voicemailAndAnsweredElsewhereAreIncomingCalls() {
        assertEquals(CallType.MISSED, recentCallTypeFor(Calls.VOICEMAIL_TYPE))
        assertEquals(CallType.INCOMING, recentCallTypeFor(Calls.ANSWERED_EXTERNALLY_TYPE))
    }

    @Test
    fun aNumberIsBlockedOnlyWhenItIsOnTheBlockList() {
        val list = setOf("912231394334")
        assertTrue(isOnBlockList("+91 22 3139 4334") { it in list })
        assertFalse(isOnBlockList("+918035792626") { it in list })
    }

    @Test
    fun theLastTenDigitsAlsoMatchTheBlockList() {
        val list = setOf("2231394334")
        assertTrue(isOnBlockList("+912231394334") { it in list })
    }

    @Test
    fun aHiddenNumberIsNeverOnTheBlockList() {
        assertFalse(isOnBlockList("") { true })
        assertFalse(isOnBlockList("Private") { true })
    }
}
