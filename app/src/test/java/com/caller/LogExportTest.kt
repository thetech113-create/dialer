package com.caller

import com.caller.logging.LogExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class LogExportTest {
    private fun file(name: String, text: String): File =
        File.createTempFile(name, ".txt").apply { deleteOnExit(); writeText(text) }

    private fun export(sessions: List<File>, appLogs: List<File>, maxBytes: Long, header: String = "H\n"): String {
        val out = ByteArrayOutputStream()
        LogExport.write(out, header, sessions, appLogs, maxBytes)
        return out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun everythingIsExportedOldestFirstWhenItFits() {
        val old = file("old", "11111\n22222\n")
        val new = file("new", "aaaaa\nbbbbb\n")
        val appOld = file("appold", "x1\n")
        val appNew = file("appnew", "x2\n")
        val text = export(listOf(old, new), listOf(appOld, appNew), 100_000)
        assertTrue(text.startsWith("H\n"))
        assertTrue(text.indexOf("11111") < text.indexOf("aaaaa"))
        assertTrue(text.indexOf("bbbbb") < text.indexOf("=== APP LOG"))
        assertTrue(text.indexOf("x1") < text.indexOf("x2"))
        assertFalse(text.contains("older part cut"))
    }

    @Test
    fun theNewestSessionIsKeptWholeAndTheOlderOneStartsAtALine() {
        // 12 bytes for the newest session, 12 of the older one's 18, which starts exactly at a line.
        val old = file("old", "11111\n22222\n33333\n")
        val new = file("new", "aaaaa\nbbbbb\n")
        val text = export(listOf(old, new), emptyList(), maxBytes = 24L + 2L + 1024L)
        assertTrue(text.contains("aaaaa\nbbbbb\n"))
        assertTrue(text.contains("22222\n33333\n"))
        assertFalse(text.contains("11111"))
        assertTrue(text.contains("(older part cut)"))
    }

    @Test
    fun aCutThatLandsInTheMiddleOfALineDropsThatLine() {
        val old = file("old", "11111\n22222\n33333\n")
        val text = export(listOf(old), emptyList(), maxBytes = 10L + 2L + 1024L)
        assertTrue(text.contains("33333\n"))
        assertFalse(text.contains("222"))
        assertFalse(text.contains("11111"))
    }

    @Test
    fun theAppLogGetsAtMostHalfOfTheLimit() {
        val sessions = file("session", "s".repeat(50) + "\n")
        val big = file("applog", (1..400).joinToString("") { "line $it\n" })
        // 4 KB: the app log may take 2 KB, 1 KB is kept back for the titles, the session gets the rest.
        val text = export(listOf(sessions), listOf(big), maxBytes = 4_096L)
        val appPart = text.substringAfter("=== APP LOG")
        assertTrue(appPart.length <= 2_048 + 200)
        assertTrue(appPart.contains("line 400\n"))
        assertFalse(appPart.contains("line 1\n"))
        // The session still fits whole next to it.
        assertTrue(text.contains("s".repeat(50)))
    }

    @Test
    fun emptyAndMissingLogsGiveNoneNotAnError() {
        val empty = file("empty", "")
        val text = export(listOf(empty), emptyList(), 100_000)
        assertEquals(2, Regex("\\(none\\)").findAll(text).count())
    }
}
