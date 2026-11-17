package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VoicemailKeyTest {
    @Test
    fun aLongPrintableKeyIsAccepted() {
        assertNull(voicemailKeyProblem("correct-horse-battery-staple-1234"))
    }

    @Test
    fun anEmptyKeyIsRefused() {
        assertNotNull(voicemailKeyProblem(""))
        assertNotNull(voicemailKeyProblem("     "))
    }

    @Test
    fun aKeyShorterThanTheServerMinimumIsRefused() {
        assertEquals(16, VOICEMAIL_KEY_MIN_LENGTH)
        assertNotNull(voicemailKeyProblem("a".repeat(15)))
        assertNull(voicemailKeyProblem("a".repeat(16)))
    }

    @Test
    fun spacesAroundTheKeyAreIgnoredButSpacesInsideAreRefused() {
        assertNull(voicemailKeyProblem("  " + "a".repeat(16) + "  "))
        assertNotNull(voicemailKeyProblem("aaaaaaaa aaaaaaaa"))
    }

    @Test
    fun charactersThatCannotTravelInAnHttpHeaderAreRefused() {
        // OkHttp throws on non-ASCII or control characters in a header value.
        assertNotNull(voicemailKeyProblem("a".repeat(16) + "é"))
        assertNotNull(voicemailKeyProblem("a".repeat(8) + "\n" + "a".repeat(8)))
        assertNotNull(voicemailKeyProblem("a".repeat(16) + "\u0000"))
    }

    @Test
    fun theLengthIsCountedAfterTrimming() {
        assertNotNull(voicemailKeyProblem(" " + "a".repeat(15) + " "))
    }
}
