package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasteChipTest {
    private val copiedAt = 1_000_000L

    @Test
    fun aFreshCopyIsShownForTheRestOfItsLifetime() {
        val remaining = PasteChip().remainingMs(copiedAt, copiedAt + 10_000L)
        assertEquals(PasteChip.LIFETIME_MS - 10_000L, remaining)
    }

    @Test
    fun theChipVanishesOnceItsLifetimeIsOver() {
        assertEquals(0L, PasteChip().remainingMs(copiedAt, copiedAt + PasteChip.LIFETIME_MS))
        assertEquals(0L, PasteChip().remainingMs(copiedAt, copiedAt + PasteChip.LIFETIME_MS + 5_000L))
    }

    @Test
    fun aCopyThatWasPastedIsNotOfferedAgain() {
        val chip = PasteChip()
        chip.markUsed(copiedAt)
        assertEquals(0L, chip.remainingMs(copiedAt, copiedAt + 1_000L))
    }

    @Test
    fun aNewCopyIsOfferedEvenAfterAnEarlierOneWasPasted() {
        val chip = PasteChip()
        chip.markUsed(copiedAt)
        // The same digits copied again have a newer copy time, so they are a new copy.
        assertTrue(chip.remainingMs(copiedAt + 30_000L, copiedAt + 31_000L) > 0L)
    }

    @Test
    fun aCopyWithNoKnownTimeIsNotOffered() {
        assertEquals(0L, PasteChip().remainingMs(0L, copiedAt))
    }

    @Test
    fun aCopyTimedInTheFutureNeverShowsLongerThanTheLifetime() {
        assertEquals(PasteChip.LIFETIME_MS, PasteChip().remainingMs(copiedAt + 5_000L, copiedAt))
    }
}
