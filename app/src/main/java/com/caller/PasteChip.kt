package com.caller

/**
 * The "Paste <number>" chip on an empty keypad. The system clipboard keeps its content for ever, so showing the chip
 * whenever the clipboard holds a number made it come back again and again. A copied number is offered only for a short
 * time after it was copied, and only until it has been pasted.
 */
internal class PasteChip {
    private var usedCopiedAtMs = 0L

    /** How much longer the chip for a number copied at [copiedAtMs] shows; 0 means it is not shown. */
    fun remainingMs(copiedAtMs: Long, nowMs: Long): Long {
        if (copiedAtMs <= 0L || copiedAtMs == usedCopiedAtMs) return 0L
        val age = (nowMs - copiedAtMs).coerceAtLeast(0L)
        return (LIFETIME_MS - age).coerceAtLeast(0L)
    }

    /** The number copied at [copiedAtMs] was pasted: it is not offered again. A later copy is a new copy. */
    fun markUsed(copiedAtMs: Long) {
        usedCopiedAtMs = copiedAtMs
    }

    companion object {
        const val LIFETIME_MS = 60_000L

        /** One for the whole process, so a pasted number stays pasted when the screen is rebuilt. */
        val shared = PasteChip()
    }
}
