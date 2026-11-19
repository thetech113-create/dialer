package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkDtmfTest {
    @Test
    fun everyKeypadCharacterIsAccepted() {
        for (key in "0123456789*#") assertEquals(key, linkDtmfDigit(key.toString()))
    }

    @Test
    fun theExtendedToneLettersAreAccepted() {
        for (key in "ABCD") assertEquals(key, linkDtmfDigit(key.toString()))
    }

    @Test
    fun nothingElseIsAccepted() {
        assertNull(linkDtmfDigit(null))
        assertNull(linkDtmfDigit(""))
        assertNull(linkDtmfDigit(" "))
        assertNull(linkDtmfDigit("x"))
        assertNull(linkDtmfDigit("+"))
        assertNull(linkDtmfDigit("a"))
    }

    @Test
    fun onlyOneCharacterPerMessageIsAccepted() {
        assertNull(linkDtmfDigit("12"))
        assertNull(linkDtmfDigit("5#"))
        assertNull(linkDtmfDigit("\n"))
    }
}
