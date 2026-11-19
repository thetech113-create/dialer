package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothCallFocusTest {
    @Test
    fun exclusiveFocusIsHeldWhileACallIsActiveOnBluetooth() {
        assertTrue(holdsExclusiveCallFocus(callActive = true, routeIsBluetooth = true))
    }

    @Test
    fun aCallOnTheSpeakerOrEarpieceLeavesOtherAppsAlone() {
        assertFalse(holdsExclusiveCallFocus(callActive = true, routeIsBluetooth = false))
    }

    @Test
    fun noActiveCallMeansNoFocusHold() {
        assertFalse(holdsExclusiveCallFocus(callActive = false, routeIsBluetooth = true))
        assertFalse(holdsExclusiveCallFocus(callActive = false, routeIsBluetooth = false))
    }

    @Test
    fun withoutAnAudioManagerNothingIsRequestedOrReleased() {
        val focus = BluetoothCallFocus(null)
        focus.sync(hold = true)
        focus.sync(hold = false)
        focus.release()
    }
}
