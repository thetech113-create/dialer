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
    fun aBlockedIncomingCallIsNotShownAsOutgoing() {
        assertEquals(CallType.MISSED, recentCallTypeFor(Calls.BLOCKED_TYPE))
        assertTrue(isBlockedCallLogType(Calls.BLOCKED_TYPE))
    }

    @Test
    fun aRejectedIncomingCallIsNotShownAsOutgoing() {
        assertEquals(CallType.MISSED, recentCallTypeFor(Calls.REJECTED_TYPE))
        assertFalse(isBlockedCallLogType(Calls.REJECTED_TYPE))
    }

    @Test
    fun voicemailAndAnsweredElsewhereAreIncomingCalls() {
        assertEquals(CallType.MISSED, recentCallTypeFor(Calls.VOICEMAIL_TYPE))
        assertEquals(CallType.INCOMING, recentCallTypeFor(Calls.ANSWERED_EXTERNALLY_TYPE))
    }

    @Test
    fun onlyTheBlockedTypeCountsAsBlocked() {
        assertFalse(isBlockedCallLogType(Calls.INCOMING_TYPE))
        assertFalse(isBlockedCallLogType(Calls.MISSED_TYPE))
        assertFalse(isBlockedCallLogType(Calls.OUTGOING_TYPE))
    }
}
