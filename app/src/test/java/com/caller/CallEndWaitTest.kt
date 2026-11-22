package com.caller

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-11-23: a dial the network refused (*#2864#*#*) left the call screen on "Ending": the phone still reported a live
 * call when the screen checked once after the end, and nothing checked again. The screen now waits until it clears.
 */
class CallEndWaitTest {
    @Test
    fun keepsCheckingUntilThePhoneReportsNoCall() = runBlocking {
        var checks = 0
        awaitNoLiveCall(isLive = { ++checks < 4 }, pollMs = 1L)
        assertEquals(4, checks)
    }

    @Test
    fun returnsAtOnceWhenNoCall() = runBlocking {
        var checks = 0
        awaitNoLiveCall(isLive = { checks++; false }, pollMs = 1L)
        assertEquals(1, checks)
    }
}
