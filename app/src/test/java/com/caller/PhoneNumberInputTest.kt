package com.caller

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberInputTest {
    @Test
    fun aPlainTenDigitNumberIsKept() {
        assertEquals("9876543210", cleanIndianMobileInput("9876543210"))
    }

    @Test
    fun spacesDashesAndBracketsAreRemoved() {
        assertEquals("9876543210", cleanIndianMobileInput("98765 43210"))
        assertEquals("9876543210", cleanIndianMobileInput("(98765)-43210"))
    }

    @Test
    fun aPastedCountryCodeIsRemoved() {
        assertEquals("9876543210", cleanIndianMobileInput("+91 98765-43210"))
        assertEquals("9876543210", cleanIndianMobileInput("919876543210"))
        assertEquals("9876543210", cleanIndianMobileInput("0091 9876543210"))
    }

    @Test
    fun aLeadingZeroIsRemoved() {
        assertEquals("9876543210", cleanIndianMobileInput("09876543210"))
    }

    @Test
    fun theFirstDigitCanOnlyBeSixToNine() {
        assertEquals("", cleanIndianMobileInput("5"))
        assertEquals("", cleanIndianMobileInput("0"))
        assertEquals("876543210", cleanIndianMobileInput("5876543210"))
        assertEquals("6", cleanIndianMobileInput("6"))
    }

    @Test
    fun noMoreThanTenDigitsAreKept() {
        assertEquals("9876543210", cleanIndianMobileInput("98765432101234"))
    }

    @Test
    fun aNumberThatStartsWith91IsNotMistakenForACountryCode() {
        assertEquals("9198765432", cleanIndianMobileInput("9198765432"))
        // The 11th digit typed after a full 10-digit number starting with 91 is simply ignored.
        assertEquals("9198765432", cleanIndianMobileInput("91987654321"))
    }

    @Test
    fun theSameNumberInDifferentFormsIsListedOnce() {
        assertEquals(
            listOf("8431573944"),
            distinctMobileNumbers(listOf("8431573944", "918431573944", "+91 84315 73944"))
        )
        assertEquals(
            listOf("9019051474", "8431573944"),
            distinctMobileNumbers(listOf("9019051474", "+919019051474", "8431573944", "918431573944"))
        )
    }

    @Test
    fun differentNumbersAndShortCodesAreKept() {
        assertEquals(listOf("8431573944", "9019051474", "198"), distinctMobileNumbers(listOf("8431573944", "9019051474", "198", " 198 ")))
        assertEquals(emptyList<String>(), distinctMobileNumbers(listOf("", "  ")))
    }

    @Test
    fun theNumberIsShownInGroupsOfFive() {
        assertEquals("98765 43210", groupMobileDigits("9876543210"))
        assertEquals("98765", groupMobileDigits("98765"))
        assertEquals("987", groupMobileDigits("987"))
        assertEquals("", groupMobileDigits(""))
    }

    @Test
    fun lettersAreIgnored() {
        assertEquals("9876", cleanIndianMobileInput("98a76b"))
    }
}
