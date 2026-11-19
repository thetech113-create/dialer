package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothCallFocusTest {
    @Test
    fun theGuardsApplyWhileACallIsActiveOnBluetooth() {
        assertTrue(callIsOnBluetoothRoute(callActive = true, routeIsBluetooth = true))
    }

    @Test
    fun aCallOnTheSpeakerOrEarpieceLeavesOtherAppsAlone() {
        assertFalse(callIsOnBluetoothRoute(callActive = true, routeIsBluetooth = false))
    }

    @Test
    fun noActiveCallMeansNoGuards() {
        assertFalse(callIsOnBluetoothRoute(callActive = false, routeIsBluetooth = true))
        assertFalse(callIsOnBluetoothRoute(callActive = false, routeIsBluetooth = false))
    }

    @Test
    fun withoutAnAudioManagerNothingIsRequestedOrReleased() {
        val focus = BluetoothCallFocus(null)
        focus.sync(hold = true)
        focus.sync(hold = false)
        focus.release()
    }
}
