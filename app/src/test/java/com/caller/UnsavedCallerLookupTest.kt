package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class UnsavedCallerLookupTest {
    private fun location(number: String) = numberLocationFor(number, "IN", Locale.ENGLISH)

    @Test
    fun landlineShowsItsCity() {
        assertEquals("Bangalore, Karnataka", location("+918022334455"))
        assertEquals("Bangalore, Karnataka", location("08022334455"))
    }

    @Test
    fun indianMobileShowsTheCountry() {
        assertEquals("India", location("+919845012345"))
        assertEquals("India", location("9845012345"))
    }

    @Test
    fun foreignNumberShowsItsCountry() {
        assertEquals("United States", location("+14155550123"))
    }

    @Test
    fun shortOrBrokenNumberShowsNothing() {
        assertEquals("", location("198"))
        assertEquals("", location(""))
        assertEquals("", location("abc"))
    }

    @Test
    fun numberForGoogleMapsIsInternational() {
        val number = internationalNumber("09845012345", "IN")!!
        assertEquals("+919845012345", number.e164)
        assertEquals(91, number.countryCode)
        assertEquals("IN", number.region)
        assertEquals("US", internationalNumber("+14155550123", "IN")!!.region)
        assertNull(internationalNumber("198", "IN"))
    }

    @Test
    fun namesFoundDuringACallMatchAnyWayTheNumberIsWritten() {
        UnsavedCallerNames.put("+91 80 2233 4455", "Corner Bakery", fromGoogleMaps = true)
        assertEquals("Corner Bakery", UnsavedCallerNames.nameFor("08022334455"))
        assertTrue(UnsavedCallerNames.isGoogleMapsName("8022334455", "Corner Bakery"))
        assertFalse(UnsavedCallerNames.isGoogleMapsName("8022334455", "Someone Else"))

        UnsavedCallerNames.put("+919845012345", "Work Colleague", fromGoogleMaps = false)
        assertFalse(UnsavedCallerNames.isGoogleMapsName("9845012345", "Work Colleague"))
    }
}
