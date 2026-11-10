package com.caller

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnNumberStateTest {
    private val a = "8431573944"
    private val b = "9019051474"

    @Test
    fun notVerifiedAsksAndSuggestsTheSimNumber() {
        assertEquals(OwnNumberState.NeedsVerification(a, false, ""), decideOwnNumberState(null, listOf(a)))
        assertEquals(OwnNumberState.NeedsVerification("", false, ""), decideOwnNumberState(null, emptyList()))
    }

    @Test
    fun verifiedAndTheSameSimIsLocked() {
        assertEquals(OwnNumberState.Verified(a), decideOwnNumberState(a, listOf(a)))
        // The server may hold the number with the country code.
        assertEquals(OwnNumberState.Verified(a), decideOwnNumberState("918431573944", listOf(a)))
    }

    @Test
    fun verifiedWithSecondSimStillLocked() {
        assertEquals(OwnNumberState.Verified(a), decideOwnNumberState(a, listOf(a, b)))
        assertEquals(OwnNumberState.Verified(a), decideOwnNumberState(a, listOf(b, a)))
    }

    @Test
    fun verifiedWhenTheSimNumberCannotBeReadStaysLocked() {
        assertEquals(OwnNumberState.Verified(a), decideOwnNumberState(a, emptyList()))
    }

    @Test
    fun aDifferentSimNumberAsksAgainAndSuggestsTheNewNumber() {
        assertEquals(OwnNumberState.NeedsVerification(b, true, a), decideOwnNumberState(a, listOf(b)))
    }

    @Test
    fun aNumberTheServerVerifiedIsNotAskedAgainWhileTheSimsAreTheSameAsAtVerification() {
        assertEquals(OwnNumberState.Verified(a), decideOwnNumberState(a, listOf(b), setOf(b)))
        // ...but a SIM that changed since then is asked about.
        assertEquals(OwnNumberState.NeedsVerification(b, true, a), decideOwnNumberState(a, listOf(b), setOf("9999999999")))
    }
}
