package com.caller.logging

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * The file behind "Share Logs": every log kept on the phone in one text file, not just the screenful the log screen
 * shows. When everything together is over [MAX_EXPORT_BYTES] the newest lines are kept and the oldest are cut, always
 * at a line boundary.
 */
internal object LogExport {
    const val MAX_EXPORT_BYTES = 15L * 1024 * 1024
    private const val RESERVE_BYTES = 1024L
    private const val BLOCK_BYTES = 64 * 1024

    /** The last [take] bytes of [file], which has [size] bytes. */
    internal data class Chunk(val file: File, val size: Long, val take: Long)

    /**
     * Picks the newest [budget] bytes out of [filesOldestFirst]: the newest file whole, then older files, the last of
     * them cut to its tail when it no longer fits. Empty files are skipped. The result is oldest first.
     */
    internal fun selectTails(filesOldestFirst: List<File>, budget: Long): List<Chunk> {
        var remaining = budget
        val chosen = ArrayList<Chunk>()
        for (file in filesOldestFirst.asReversed()) {
            if (remaining <= 0) break
            val size = file.length()
            if (size <= 0) continue
            val take = minOf(size, remaining)
            chosen += Chunk(file, size, take)
            remaining -= take
        }
        return chosen.asReversed()
    }

    /**
     * Writes [header], then the forensic session files, then the app's own log files, all oldest first. The app log gets
     * at most half of [maxBytes]; the sessions get the rest.
     */
    fun write(
        out: OutputStream,
        header: String,
        sessionsOldestFirst: List<File>,
        appLogsOldestFirst: List<File>,
        maxBytes: Long = MAX_EXPORT_BYTES
    ) {
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val appBudget = minOf(appLogsOldestFirst.sumOf { it.length() }, maxBytes / 2)
        val sessionBudget = (maxBytes - headerBytes.size - appBudget - RESERVE_BYTES).coerceAtLeast(0L)
        out.write(headerBytes)
        writeSection(
            out, "=== FORENSIC EVENTS (all sessions kept on the phone, oldest first) ===",
            selectTails(sessionsOldestFirst, sessionBudget)
        )
        writeSection(
            out, "=== APP LOG (this app's own log lines, oldest first) ===",
            selectTails(appLogsOldestFirst, appBudget)
        )
    }

    private fun writeSection(out: OutputStream, title: String, chunks: List<Chunk>) {
        out.write("\n$title\n".toByteArray(Charsets.UTF_8))
        if (chunks.isEmpty()) {
            out.write("(none)\n".toByteArray(Charsets.UTF_8))
            return
        }
        for (chunk in chunks) {
            val cut = if (chunk.take < chunk.size) " (older part cut)" else ""
            out.write("--- ${chunk.file.name}$cut ---\n".toByteArray(Charsets.UTF_8))
            copyTail(chunk, out)
            out.write('\n'.code)
        }
    }

    /** Copies the last [Chunk.take] bytes of the file, starting at a line boundary. */
    private fun copyTail(chunk: Chunk, out: OutputStream) {
        RandomAccessFile(chunk.file, "r").use { raf ->
            val length = raf.length()
            val start = (length - chunk.take).coerceAtLeast(0L)
            var dropping = false
            if (start > 0) {
                raf.seek(start - 1)
                dropping = raf.read() != '\n'.code
            }
            raf.seek(start)
            val buffer = ByteArray(BLOCK_BYTES)
            while (true) {
                val read = raf.read(buffer)
                if (read <= 0) break
                var from = 0
                if (dropping) {
                    val newline = (0 until read).firstOrNull { buffer[it] == '\n'.code.toByte() } ?: continue
                    from = newline + 1
                    dropping = false
                }
                out.write(buffer, from, read - from)
            }
        }
    }
}
