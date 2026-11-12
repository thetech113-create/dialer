package com.caller

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsRiskTierTest {
    private fun tier(
        technical: Boolean = false,
        legitimate: Boolean = false,
        scamSign: Boolean = false,
        probability: Float,
        suspicious: Double? = 0.33,
        high: Double? = 0.85
    ) = smsRiskTier(technical, legitimate, scamSign, probability, suspicious, high)

    @Test
    fun theModelAloneNeverFlagsAMessage() {
        // A recharge confirmation, an appointment reminder: the model scores them 0.88 and 0.91, with no scam sign.
        assertEquals(SmsSpamRiskTier.LOW_RISK, tier(probability = 0.88f))
        assertEquals(SmsSpamRiskTier.LOW_RISK, tier(probability = 0.91f))
        assertEquals(SmsSpamRiskTier.LOW_RISK, tier(probability = 0.40f))
        assertEquals(SmsSpamRiskTier.LOW_RISK, tier(probability = 1.0f))
    }

    @Test
    fun aScamSignConfirmedByTheModelIsFlagged() {
        assertEquals(SmsSpamRiskTier.SUSPICIOUS, tier(scamSign = true, probability = 0.33f))
        assertEquals(SmsSpamRiskTier.SUSPICIOUS, tier(scamSign = true, probability = 0.84f))
        assertEquals(SmsSpamRiskTier.HIGH_CONFIDENCE_SPAM, tier(scamSign = true, probability = 0.85f))
        assertEquals(SmsSpamRiskTier.HIGH_CONFIDENCE_SPAM, tier(scamSign = true, probability = 0.99f))
    }

    @Test
    fun aScamSignTheModelDoesNotBackIsNotFlagged() {
        assertEquals(SmsSpamRiskTier.LOW_RISK, tier(scamSign = true, probability = 0.32f))
        assertEquals(SmsSpamRiskTier.LOW_RISK, tier(scamSign = true, probability = 0.0f))
    }

    @Test
    fun aDeceptiveLinkOrApkIsFlaggedWithoutTheModel() {
        assertEquals(SmsSpamRiskTier.HIGH_CONFIDENCE_SPAM, tier(technical = true, probability = 0.1f))
        assertEquals(SmsSpamRiskTier.HIGH_CONFIDENCE_SPAM, tier(technical = true, probability = 0.1f, suspicious = null, high = null))
        // It beats the one-way patterns too (a lookalike link inside an OTP-looking text).
        assertEquals(SmsSpamRiskTier.HIGH_CONFIDENCE_SPAM, tier(technical = true, legitimate = true, probability = 0.1f))
    }

    @Test
    fun aKnownOneWayPatternIsNeverFlaggedByTheModel() {
        assertEquals(SmsSpamRiskTier.LOW_RISK, tier(legitimate = true, scamSign = true, probability = 0.99f))
        assertEquals(SmsSpamRiskTier.LOW_RISK, tier(legitimate = true, probability = 0.99f))
    }

    @Test
    fun withoutAModelNothingButADeceptiveLinkIsFlagged() {
        assertEquals(SmsSpamRiskTier.LOW_RISK, tier(scamSign = true, probability = 0f, suspicious = null, high = null))
        assertEquals(SmsSpamRiskTier.LOW_RISK, tier(probability = 1f, suspicious = null, high = null))
    }
}
