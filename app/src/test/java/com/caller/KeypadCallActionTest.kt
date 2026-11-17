package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypadCallActionTest {
    @Test
    fun aTypedNumberIsDialed() {
        assertEquals(KeypadCallAction.Dial("9876543210"), keypadCallAction("9876543210", "9123456789"))
    }

    @Test
    fun aTypedNumberIsDialedEvenWithNoRecentCall() {
        assertEquals(KeypadCallAction.Dial("100"), keypadCallAction("100", ""))
    }

    @Test
    fun anEmptyKeypadOnlyShowsTheLastNumberAndNeverDials() {
        // The bug: the first tap on an empty keypad placed the call at once.
        val action = keypadCallAction("", "9123456789")
        assertEquals(KeypadCallAction.ShowLastNumber("9123456789"), action)
        assertFalse(action is KeypadCallAction.Dial)
    }

    @Test
    fun theSecondTapDialsTheNumberThatWasShown() {
        val first = keypadCallAction("", "9123456789") as KeypadCallAction.ShowLastNumber
        // The keypad now holds that number, so the next tap dials it.
        assertEquals(KeypadCallAction.Dial("9123456789"), keypadCallAction(first.number, "9123456789"))
    }

    @Test
    fun anEditedLastNumberIsDialedAsEdited() {
        val first = keypadCallAction("", "9123456789") as KeypadCallAction.ShowLastNumber
        val edited = first.number.dropLast(1) + "0"
        assertEquals(KeypadCallAction.Dial("9123456780"), keypadCallAction(edited, "9123456789"))
    }

    @Test
    fun anEmptyKeypadWithNoRecentCallDoesNothing() {
        assertEquals(KeypadCallAction.Nothing, keypadCallAction("", ""))
        assertEquals(KeypadCallAction.Nothing, keypadCallAction("", "   "))
    }

    @Test
    fun noEmptyKeypadInputEverDials() {
        listOf("", " ", "9123456789", "+919123456789").forEach { last ->
            assertFalse(keypadCallAction("", last) is KeypadCallAction.Dial)
        }
        assertTrue(keypadCallAction("1", "") is KeypadCallAction.Dial)
    }
}

class KeypadClearAfterCallTest {
    @Test
    fun theKeypadIsClearedWhenItsNumberWasDialed() {
        assertTrue(keypadShowsDialedNumber("9876543210", "9876543210"))
    }

    @Test
    fun aFormattedDialedNumberStillMatchesTheKeypad() {
        assertTrue(keypadShowsDialedNumber("+919876543210", "+91 98765-43210"))
    }

    @Test
    fun aRedialedNumberShownOnTheKeypadIsCleared() {
        val shown = (keypadCallAction("", "9123456789") as KeypadCallAction.ShowLastNumber).number
        assertTrue(keypadShowsDialedNumber(shown, "9123456789"))
    }

    @Test
    fun aCallToAnotherNumberLeavesTheKeypadAlone() {
        assertFalse(keypadShowsDialedNumber("98765", "9123456789"))
    }

    @Test
    fun anEmptyKeypadHasNothingToClear() {
        assertFalse(keypadShowsDialedNumber("", "9123456789"))
        assertFalse(keypadShowsDialedNumber("", ""))
        assertFalse(keypadShowsDialedNumber("9876543210", ""))
    }
}
