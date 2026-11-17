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

class DeferredKeypadClearTest {
    @Test
    fun theKeypadIsClearedOnlyOnceItIsHidden() {
        val clear = DeferredKeypadClear()
        clear.request("9876543210", "9876543210")
        // Nothing is returned until the keypad is hidden, so the keypad cannot be seen empty before the call screen opens.
        assertEquals("", clear.digitsOnceHidden("9876543210"))
    }

    @Test
    fun aCallThatDidNotStartKeepsTheNumber() {
        val clear = DeferredKeypadClear()
        clear.request("9876543210", "9876543210")
        clear.cancel()
        assertEquals("9876543210", clear.digitsOnceHidden("9876543210"))
    }

    @Test
    fun hidingTheKeypadWithNoCallPlacedChangesNothing() {
        assertEquals("98765", DeferredKeypadClear().digitsOnceHidden("98765"))
    }

    @Test
    fun aCallToAnotherNumberLeavesTheKeypadAlone() {
        val clear = DeferredKeypadClear()
        clear.request("98765", "9123456789")
        assertEquals("98765", clear.digitsOnceHidden("98765"))
    }

    @Test
    fun aNumberTypedAfterTheCallIsNotCleared() {
        val clear = DeferredKeypadClear()
        clear.request("9876543210", "9876543210")
        assertEquals("1234", clear.digitsOnceHidden("1234"))
    }

    @Test
    fun theClearHappensOnlyOnce() {
        val clear = DeferredKeypadClear()
        clear.request("9876543210", "9876543210")
        assertEquals("", clear.digitsOnceHidden("9876543210"))
        // The next time the keypad is hidden (a later visit), a number typed meanwhile stays.
        assertEquals("9876543210", clear.digitsOnceHidden("9876543210"))
    }
}

class CallScreenShownTest {
    @Test
    fun everyListenerIsToldWhenTheCallScreenIsShown() {
        var first = 0
        var second = 0
        val a: () -> Unit = { first++ }
        val b: () -> Unit = { second++ }
        CallScreenShown.add(a)
        CallScreenShown.add(b)
        CallScreenShown.notifyShown()
        CallScreenShown.remove(a)
        CallScreenShown.remove(b)
        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun aRemovedListenerIsNotToldAgain() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        CallScreenShown.add(listener)
        CallScreenShown.remove(listener)
        CallScreenShown.notifyShown()
        assertEquals(0, calls)
    }

    @Test
    fun theSameListenerAddedTwiceIsToldOnce() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        CallScreenShown.add(listener)
        CallScreenShown.add(listener)
        CallScreenShown.notifyShown()
        CallScreenShown.remove(listener)
        assertEquals(1, calls)
    }
}
