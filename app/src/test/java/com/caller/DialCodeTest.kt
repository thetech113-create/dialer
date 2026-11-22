package com.caller

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-11-23: *#2864#*#* typed on the keypad was placed as a call, so the call screen opened and showed "Ending".
 * It is not a phone number and not a USSD code (those end with #), so it must not be dialled.
 */
class DialCodeTest {
    @Test
    fun aCodeEndingInStarIsNotDialled() {
        assertEquals(DialCode.NotDialable, dialCodeOf("*#2864#*#*"))
        assertEquals(DialCode.NotDialable, dialCodeOf("#12*"))
    }

    @Test
    fun androidSecretCodesGoToThePhoneNotTheNetwork() {
        assertEquals(DialCode.Secret("4636"), dialCodeOf("*#*#4636#*#*"))
    }

    @Test
    fun numbersAndUssdCodesAreStillCalled() {
        for (s in listOf("9876543210", "+919876543210", "*123#", "*#06#", "#31#9876543210", "*31#9876543210", "18001234567,1*", "*2864#")) {
            assertEquals(s, DialCode.Call, dialCodeOf(s))
        }
    }
}
