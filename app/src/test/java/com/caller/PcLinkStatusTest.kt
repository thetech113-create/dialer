package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcLinkStatusTest {
    @Test
    fun theConnectedPcIsShownAsConnected() {
        assertTrue(pcRowIsConnected("pc-1", "pc-1"))
        assertEquals("Connected", pcRowStatusText(true))
    }

    @Test
    fun aPairedPcThatIsNotConnectedIsNotShownAsPaired() {
        // The bug: every row said "Paired PC" whether or not the PC was reachable (for example out of range).
        assertFalse(pcRowIsConnected("pc-1", null))
        assertEquals("Not connected", pcRowStatusText(false))
    }

    @Test
    fun anotherPcBeingConnectedDoesNotMakeThisOneConnected() {
        assertFalse(pcRowIsConnected("pc-1", "pc-2"))
    }

    @Test
    fun aBlankIdIsNeverConnected() {
        assertFalse(pcRowIsConnected("", ""))
        assertFalse(pcRowIsConnected("  ", "  "))
    }
}
