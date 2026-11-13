package com.caller.logging

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Flight recorder for this app's own log lines.
 *
 * The call code writes most of its evidence (CallForensic, P2P_FORENSIC, CMF_RINGTONE_DEBUG, OEM_FALLBACK_TRACE ...) to
 * the phone's system log, a ring buffer of a few minutes that can only be read with a laptop. This keeps those lines
 * in a rolling file on the phone, so a call that went wrong away from the laptop can be read afterwards: in the
 * "Diagnostics & Forensic Logs" screen, or over adb later. An app can only read its own lines, so nothing of other
 * apps is recorded. On Android 11+ the files are in the app's folder on shared storage (open to adb), otherwise in
 * private storage.
 */
object AppLogRecorder {
    private const val TAG = "AppLogRecorder"
    private const val CURRENT_FILE = "app_logcat.txt"
    private const val OLD_FILE = "app_logcat_old.txt"
    private const val MAX_FILE_BYTES = 3L * 1024 * 1024
    private const val FIRST_RUN_LINES = "300"
    private val started = AtomicBoolean(false)
    @Volatile private var directory: File? = null
    private val timestampRegex = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) """)

    /** Starts recording once per process; later calls do nothing. */
    fun start(context: Context, privateContext: Context) {
        if (!started.compareAndSet(false, true)) return
        val dir = chooseDirectory(context, privateContext)
        directory = dir
        Thread({ runForever(dir) }, "app-log-recorder").apply { isDaemon = true }.start()
    }

    /** The newest [maxChars] characters of the recorded lines, older file first, starting at a line boundary. */
    fun tail(maxChars: Int): String {
        val dir = directory ?: return ""
        val current = readTail(File(dir, CURRENT_FILE), maxChars)
        if (current.length >= maxChars) return current
        return readTail(File(dir, OLD_FILE), maxChars - current.length) + current
    }

    /** The recorded files that exist and are not empty, oldest first (for the Share Logs file). */
    fun files(): List<File> {
        val dir = directory ?: return emptyList()
        return listOf(File(dir, OLD_FILE), File(dir, CURRENT_FILE)).filter { it.isFile && it.length() > 0 }
    }

    /** Empties the recorded lines. The recorder keeps running and appends to the emptied file. */
    fun clear() {
        val dir = directory ?: return
        runCatching { File(dir, OLD_FILE).delete() }
        runCatching { File(dir, CURRENT_FILE).takeIf { it.isFile }?.let { FileWriter(it, false).close() } }
    }

    private fun chooseDirectory(context: Context, privateContext: Context): File {
        val shared = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.getExternalFilesDir("logs") }.getOrNull()
        } else {
            null
        }
        return (shared ?: File(privateContext.filesDir, "logs")).also { it.mkdirs() }
    }

    private fun runForever(dir: File) {
        var quickFailures = 0
        while (true) {
            val startedAt = System.currentTimeMillis()
            runCatching { recordOnce(dir) }.onFailure { Log.w(TAG, "recorder run ended: ${it.message}") }
            quickFailures = if (System.currentTimeMillis() - startedAt < 10_000L) quickFailures + 1 else 0
            if (quickFailures >= 3) {
                Log.w(TAG, "log recorder gave up: logcat keeps ending at once")
                return
            }
            runCatching { Thread.sleep(5_000L) }
        }
    }

    /** One logcat run: catches up from the last recorded line (the phone keeps a few minutes), then follows live. */
    private fun recordOnce(dir: File) {
        val current = File(dir, CURRENT_FILE)
        val since = lastLogcatTimestamp(readTail(current, 8_192))
        val process = ProcessBuilder(listOf("logcat", "-v", "threadtime", "-T", since ?: FIRST_RUN_LINES))
            .redirectErrorStream(true)
            .start()
        try {
            var size = current.length()
            var writer = FileWriter(current, true)
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    writer.append(line).append('\n')
                    writer.flush()
                    size += line.length + 1
                    if (size > MAX_FILE_BYTES) {
                        writer.close()
                        File(dir, OLD_FILE).delete()
                        current.renameTo(File(dir, OLD_FILE))
                        writer = FileWriter(current, true)
                        size = 0
                    }
                }
            } finally {
                runCatching { writer.close() }
            }
        } finally {
            process.destroy()
        }
    }

    /** The time ("MM-dd HH:mm:ss.SSS") of the last logcat line in [text], or null when it has none. */
    internal fun lastLogcatTimestamp(text: String): String? {
        for (line in text.lineSequence().toList().asReversed()) {
            timestampRegex.find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    /**
     * The last [maxChars] characters of [text], cut so that it starts at a line boundary. [startsMidLine] says that
     * [text] itself begins in the middle of a line (it was read from the middle of a file).
     */
    internal fun keepTail(text: String, maxChars: Int, startsMidLine: Boolean = false): String {
        if (maxChars <= 0) return ""
        val cutOff = text.length > maxChars
        val part = if (cutOff) text.takeLast(maxChars) else text
        if (!cutOff && !startsMidLine) return part
        val newline = part.indexOf('\n')
        return if (newline >= 0) part.substring(newline + 1) else part
    }

    /** The last [maxChars] characters of a text file, starting at a line boundary; empty when it cannot be read. */
    internal fun readTail(file: File, maxChars: Int): String {
        if (maxChars <= 0 || !file.isFile) return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val take = minOf(length, maxChars.toLong() * 3L).toInt()
                raf.seek(length - take)
                val bytes = ByteArray(take)
                raf.readFully(bytes)
                keepTail(String(bytes, Charsets.UTF_8), maxChars, startsMidLine = take < length)
            }
        }.getOrDefault("")
    }
}
