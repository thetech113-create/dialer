package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit F-DIAL-1: every call (contact, recent row, widget, tel: link) goes through sanitizePhoneNumberForCall. It
 * dropped the pause (,) and wait (;) marks and cut the text to 15 characters, so an extension was dialed as part of
 * the number and a long USSD code was sent cut short.
 */
class DialStringTest {
    @Test
    fun formattingIsStillRemoved() {
        assertEquals("+919876543210", sanitizePhoneNumberForCall("+91 98765-43210"))
        assertEquals("08012345678", sanitizePhoneNumberForCall("(080) 1234 5678"))
    }

    @Test
    fun pauseAndWaitMarksAreKept() {
        // "Call, pause, then dial 123": without the pause the extension became part of the number.
        assertEquals("08012345678,,123", sanitizePhoneNumberForCall("080-12345678,,123"))
        assertEquals("18001234567;4321#", sanitizePhoneNumberForCall("1800 123 4567;4321#"))
    }

    @Test
    fun longUssdCodesAreNotCut() {
        assertEquals("*141*9876543210*100#", sanitizePhoneNumberForCall("*141*9876543210*100#"))
        assertTrue(isSafePhoneNumber("*141*9876543210*100#"))
    }

    @Test
    fun aFullLengthInternationalNumberKeepsItsLastDigit() {
        // E.164 allows 15 digits; with the "+" that is 16 characters.
        assertEquals("+123456789012345", sanitizePhoneNumberForCall("+123456789012345"))
        assertTrue(isSafePhoneNumber("+123456789012345"))
    }

    @Test
    fun shortAndEmptyInputStayInvalid() {
        assertFalse(isSafePhoneNumber(""))
        assertFalse(isSafePhoneNumber("12"))
        assertTrue(isSafePhoneNumber("100"))
    }
}
