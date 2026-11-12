package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SmsDirectionTest {
    private val spam = SmsSpamVerdict(isPotentialSpam = true, score = 99, riskTier = SmsSpamRiskTier.HIGH_CONFIDENCE_SPAM)

    @Test
    fun aReceivedMessageIsChecked() {
        assertEquals(spam, verdictForMessageType(1) { spam })
    }

    @Test
    fun nothingYouSendIsEverMarkedOrChecked() {
        // 2 sent, 3 draft, 4 outbox, 5 failed, 6 queued.
        for (type in 2..6) {
            var checked = false
            val verdict = verdictForMessageType(type) {
                checked = true
                spam
            }
            assertFalse("type $type must not be marked", verdict.isPotentialSpam)
            assertFalse("type $type must not even be checked", checked)
        }
    }

    @Test
    fun anUnknownTypeIsNotMarked() {
        assertFalse(verdictForMessageType(0) { spam }.isPotentialSpam)
        assertFalse(verdictForMessageType(-1) { spam }.isPotentialSpam)
    }
}
