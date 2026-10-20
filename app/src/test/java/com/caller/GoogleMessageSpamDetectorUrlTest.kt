package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMessageSpamDetectorUrlTest {
    @Test
    fun verifiedKotakFraudReportingHostIsNotCalledALookalike() {
        val result = analyzeUrlStructure(
            "https://kotak.bank.in/KBANKT/Fraud",
            setOf("kotak", "transaction")
        )

        assertFalse(result.isLookalikeBrand)
        assertFalse(result.isIpOrDeceptive)
    }

    @Test
    fun kotakLookalikeOnDifferentHostIsStillDetected() {
        val result = analyzeUrlStructure(
            "https://kotak-bank-login.xyz/verify",
            setOf("kotak", "verify")
        )

        assertTrue(result.isLookalikeBrand)
    }

    @Test
    fun deceptiveSuffixIsNotAcceptedAsKotakSubdomain() {
        val result = analyzeUrlStructure(
            "https://kotak.bank.in.evil.example/KBANKT/Fraud",
            setOf("kotak")
        )

        assertTrue(result.isLookalikeBrand)
    }
}
