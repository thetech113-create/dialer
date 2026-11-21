package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The silent SMS is skipped only when the server already gave both phones each other's names. */
class SilentSmsBackupTest {
    @Test
    fun bothNamesOnServerSkipsTheSms() {
        assertTrue(serverSharedNames(200, "Ravi", "Vishwa", "Vishwa"))
    }

    @Test
    fun otherPhoneNotOnServerSendsTheSms() {
        assertFalse(serverSharedNames(404, "", "Vishwa", "Vishwa"))
        assertFalse(serverSharedNames(200, " ", "Vishwa", "Vishwa"))
    }

    @Test
    fun serverErrorOrLimitSendsTheSms() {
        assertFalse(serverSharedNames(429, "Ravi", "Vishwa", "Vishwa"))
        assertFalse(serverSharedNames(500, "Ravi", "Vishwa", "Vishwa"))
    }

    @Test
    fun myNameNotUploadedOrChangedSendsTheSms() {
        assertFalse(serverSharedNames(200, "Ravi", null, "Vishwa"))
        assertFalse(serverSharedNames(200, "Ravi", "", "Vishwa"))
        // Renamed on this phone but the server still has the old name: the other phone must get the new one by SMS.
        assertFalse(serverSharedNames(200, "Ravi", "Vishwa", "Vishwa B"))
        assertFalse(serverSharedNames(200, "Ravi", "Vishwa", null))
    }
}
