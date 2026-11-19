package com.caller

import android.telecom.TelecomManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Block Private & Unknown Callers" drops only callers that hide their number; an unsaved ordinary number rings. */
class HiddenCallerRuleTest {
    @Test
    fun anOrdinaryNumberNotInContactsStillRings() {
        assertFalse(isHiddenCaller("+912231394334", TelecomManager.PRESENTATION_ALLOWED))
        assertFalse(isHiddenCaller("08035792626", TelecomManager.PRESENTATION_ALLOWED))
    }

    @Test
    fun aPrivateOrHiddenNumberIsDropped() {
        assertTrue(isHiddenCaller("", TelecomManager.PRESENTATION_RESTRICTED))
        assertTrue(isHiddenCaller("+919999999999", TelecomManager.PRESENTATION_RESTRICTED))
    }

    @Test
    fun anUnknownCallerIdIsDropped() {
        assertTrue(isHiddenCaller("", TelecomManager.PRESENTATION_UNKNOWN))
    }

    @Test
    fun aCallWithNoNumberIsDropped() {
        assertTrue(isHiddenCaller("", TelecomManager.PRESENTATION_ALLOWED))
        assertTrue(isHiddenCaller("Private", TelecomManager.PRESENTATION_ALLOWED))
    }

    @Test
    fun aPayphoneWithANumberRings() {
        assertFalse(isHiddenCaller("+918012345678", TelecomManager.PRESENTATION_PAYPHONE))
    }
}
