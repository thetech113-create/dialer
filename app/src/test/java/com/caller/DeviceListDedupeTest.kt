package com.caller

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceListDedupeTest {
    private data class Dev(val id: String, val name: String, val link: Boolean = false)

    private fun keep(items: List<Dev>) = keepOnePerDevice(items, { it.id }, { it.name }, { it.link })

    @Test
    fun aListWithNoRepeatsIsUnchanged() {
        val items = listOf(Dev("1", "Redmi"), Dev("2", "OPPO"), Dev("3", "Pixel"))
        assertEquals(items, keep(items))
    }

    @Test
    fun theSameDeviceLinkedAgainShowsOnce() {
        // The bug: the same device linked again was listed again.
        val items = listOf(Dev("2", "Redmi"), Dev("1", "Redmi"))
        assertEquals(listOf(Dev("2", "Redmi")), keep(items))
    }

    @Test
    fun theSameIdWithAnotherNameShowsOnce() {
        assertEquals(listOf(Dev("1", "Redmi")), keep(listOf(Dev("1", "Redmi"), Dev("1", "Redmi 2"))))
    }

    @Test
    fun capitalsAndSpacesDoNotMakeAnotherDevice() {
        assertEquals(listOf(Dev("1", "Redmi Note")), keep(listOf(Dev("1", "Redmi Note"), Dev("2", " redmi note "))))
    }

    @Test
    fun blankAndPlaceholderNamesNeverMergeDifferentDevices() {
        val items = listOf(Dev("1", ""), Dev("2", ""), Dev("3", "PC"), Dev("4", "pc"))
        assertEquals(items, keep(items))
    }

    @Test
    fun thePreferredEntryWinsAndTheOriginalOrderIsKept() {
        val items = listOf(Dev("a", "Z phone"), Dev("b", "Redmi"), Dev("c", "Redmi", link = true), Dev("d", "OPPO"))
        assertEquals(listOf(Dev("a", "Z phone"), Dev("c", "Redmi", link = true), Dev("d", "OPPO")), keep(items))
    }

    @Test
    fun identicalEntriesCollapseToOne() {
        assertEquals(listOf(Dev("1", "Redmi")), keep(listOf(Dev("1", "Redmi"), Dev("1", "Redmi"))))
    }

    private fun pc(id: String, name: String, at: Long = 0L) = PairedPc(id, name, at)

    @Test
    fun aPcPairedAgainShowsOnceAndTheNewestPairingIsKept() {
        val shown = pcsToShow(listOf(pc("old", "DESKTOP-A", 100), pc("new", "DESKTOP-A", 200)), null)
        assertEquals(listOf("new"), shown.map { it.id })
    }

    @Test
    fun theConnectedPcIsTheRowKeptEvenIfAnotherCopyIsNewer() {
        val shown = pcsToShow(listOf(pc("live", "DESKTOP-A", 100), pc("newer", "DESKTOP-A", 200)), "live")
        assertEquals(listOf("live"), shown.map { it.id })
    }

    @Test
    fun differentPcsStayAndAreSortedByName() {
        val shown = pcsToShow(listOf(pc("2", "Work"), pc("1", "Home")), null)
        assertEquals(listOf("Home", "Work"), shown.map { it.name })
    }

    @Test
    fun staleCopiesAreTheOtherEntriesWithTheSameName() {
        val pcs = listOf(pc("a", "Home"), pc("b", "home"), pc("c", "Work"))
        assertEquals(listOf("b"), staleCopyIds(pcs, "a", "Home"))
        assertEquals(emptyList<String>(), staleCopyIds(pcs, "c", "Work"))
    }

    @Test
    fun aGenericNameNeverMarksAnotherPcAsAStaleCopy() {
        val pcs = listOf(pc("a", "PC"), pc("b", "PC"), pc("c", ""))
        assertEquals(emptyList<String>(), staleCopyIds(pcs, "a", "PC"))
        assertEquals(emptyList<String>(), staleCopyIds(pcs, "c", ""))
    }
}
