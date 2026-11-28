package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwapButtonVisibilityTest {
    @Test
    fun hiddenWhileTheNewCallIsStillDialling() {
        // The user's screenshot: SAKET on hold, the new call still dialling. Swapping can do nothing yet.
        assertFalse(showSecondaryCallAction(primaryState = "DIALING", secondaryState = "HOLDING"))
    }

    @Test
    fun shownOnceTheNewCallIsAnswered() {
        assertTrue(showSecondaryCallAction(primaryState = "ACTIVE", secondaryState = "HOLDING"))
        assertTrue(showSecondaryCallAction(primaryState = "HOLDING", secondaryState = "ACTIVE"))
    }

    @Test
    fun ringingSecondCallStillShowsSwapAndAnswer() {
        assertTrue(showSecondaryCallAction(primaryState = "ACTIVE", secondaryState = "RINGING"))
    }

    @Test
    fun hiddenWhenNoCallIsOnHold() {
        assertFalse(showSecondaryCallAction(primaryState = "ACTIVE", secondaryState = "DIALING"))
        assertFalse(showSecondaryCallAction(primaryState = "HOLDING", secondaryState = "HOLDING"))
        assertFalse(showSecondaryCallAction(primaryState = "ACTIVE", secondaryState = null))
    }
}
