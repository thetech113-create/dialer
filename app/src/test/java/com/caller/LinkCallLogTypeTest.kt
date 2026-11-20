package com.caller

import android.provider.CallLog.Calls
import org.junit.Assert.assertEquals
import org.junit.Test

/** The call type the phone sends to the PC: a blocked call must not arrive as "other". */
class LinkCallLogTypeTest {
    @Test
    fun ordinaryTypesAreUnchanged() {
        assertEquals("in", linkCallLogType(Calls.INCOMING_TYPE))
        assertEquals("out", linkCallLogType(Calls.OUTGOING_TYPE))
        assertEquals("missed", linkCallLogType(Calls.MISSED_TYPE))
        assertEquals("rejected", linkCallLogType(Calls.REJECTED_TYPE))
    }

    @Test
    fun aBlockedCallIsSentAsBlocked() {
        assertEquals("blocked", linkCallLogType(Calls.BLOCKED_TYPE))
    }

    @Test
    fun voicemailAndAnsweredElsewhereFollowRecents() {
        assertEquals("missed", linkCallLogType(Calls.VOICEMAIL_TYPE))
        assertEquals("in", linkCallLogType(Calls.ANSWERED_EXTERNALLY_TYPE))
    }

    @Test
    fun anUnknownTypeIsStillOther() {
        assertEquals("other", linkCallLogType(10))
    }
}
