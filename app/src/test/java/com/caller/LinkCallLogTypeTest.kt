package com.caller

import android.provider.CallLog.Calls
import org.junit.Assert.assertEquals
import org.junit.Test

/** The call type the phone sends to the PC: "blocked" only for a number that really is on the block list. */
class LinkCallLogTypeTest {
    @Test
    fun ordinaryTypesAreUnchanged() {
        assertEquals("in", linkCallLogType(Calls.INCOMING_TYPE, numberIsBlocked = false))
        assertEquals("out", linkCallLogType(Calls.OUTGOING_TYPE, numberIsBlocked = false))
        assertEquals("missed", linkCallLogType(Calls.MISSED_TYPE, numberIsBlocked = false))
        assertEquals("rejected", linkCallLogType(Calls.REJECTED_TYPE, numberIsBlocked = false))
    }

    @Test
    fun aCallFromABlockedNumberIsSentAsBlocked() {
        assertEquals("blocked", linkCallLogType(Calls.BLOCKED_TYPE, numberIsBlocked = true))
    }

    @Test
    fun aCallAFilterStoppedIsSentAsMissed() {
        assertEquals("missed", linkCallLogType(Calls.BLOCKED_TYPE, numberIsBlocked = false))
    }

    @Test
    fun voicemailAndAnsweredElsewhereFollowRecents() {
        assertEquals("missed", linkCallLogType(Calls.VOICEMAIL_TYPE, numberIsBlocked = false))
        assertEquals("in", linkCallLogType(Calls.ANSWERED_EXTERNALLY_TYPE, numberIsBlocked = false))
    }

    @Test
    fun anUnknownTypeIsStillOther() {
        assertEquals("other", linkCallLogType(10, numberIsBlocked = false))
    }
}
