package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Smart Spam Protection flags suspected spam (the call rings) and drops only clear spam. */
class SpamDecisionTest {
    @Test
    fun anOrdinaryCallerRings() {
        assertEquals(SpamAction.ALLOW, spamDecision(score = 0f, isWangiri = false, stirFailed = false))
        assertEquals(SpamAction.ALLOW, spamDecision(score = 49f, isWangiri = false, stirFailed = false))
    }

    @Test
    fun suspectedSpamRingsWithAFlag() {
        assertEquals(SpamAction.FLAG, spamDecision(score = 50f, isWangiri = false, stirFailed = false))
        assertEquals(SpamAction.FLAG, spamDecision(score = 80f, isWangiri = false, stirFailed = false))
        assertEquals(SpamAction.FLAG, spamDecision(score = 99f, isWangiri = false, stirFailed = false))
    }

    @Test
    fun clearSpamIsDropped() {
        assertEquals(SpamAction.DROP, spamDecision(score = 100f, isWangiri = false, stirFailed = false))
        assertEquals(SpamAction.DROP, spamDecision(score = 0f, isWangiri = false, stirFailed = true))
    }

    /** Audit SPAM-1: a prefix alone (Latvia, Serbia, Belarus, ...) is no proof; real calls from there must ring. */
    @Test
    fun aCallFromAFraudPronePrefixRingsWithAFlag() {
        assertEquals(SpamAction.FLAG, spamDecision(score = 80f, isWangiri = true, stirFailed = false))
        assertEquals(SpamAction.FLAG, spamDecision(score = 0f, isWangiri = true, stirFailed = false))
        // The same prefix plus machine-timed repeat calls is still clear spam.
        assertEquals(SpamAction.DROP, spamDecision(score = 100f, isWangiri = true, stirFailed = false))
    }

    @Test
    fun aFlagCountsOnlyForAShortTime() {
        val now = 1_000_000_000L
        assertTrue(isSpamFlagFresh(flaggedAt = now - 60_000L, now = now))
        assertFalse(isSpamFlagFresh(flaggedAt = now - SPAM_FLAG_WINDOW_MS - 1, now = now))
        assertFalse(isSpamFlagFresh(flaggedAt = 0L, now = now))
    }
}
