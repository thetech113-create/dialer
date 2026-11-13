package com.caller

import com.caller.logging.AppLogRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppLogRecorderTest {
    private fun tempFile(text: String): File =
        File.createTempFile("app_logcat", ".txt").apply { deleteOnExit(); writeText(text) }

    @Test
    fun theLastRecordedLineGivesTheTimeToContinueFrom() {
        val text = "--------- beginning of main\n" +
            "11-13 10:43:13.642  1728  2437 I wm_set_resumed_activity: [0,com.caller/.CallActivity]\n" +
            "11-13 10:43:17.099  1728  3163 D ActivityTaskManager: next\n" +
            "11-13 10:43:17.1"
        assertEquals("11-13 10:43:17.099", AppLogRecorder.lastLogcatTimestamp(text))
    }

    @Test
    fun aTextWithoutLogLinesHasNoTime() {
        assertNull(AppLogRecorder.lastLogcatTimestamp(""))
        assertNull(AppLogRecorder.lastLogcatTimestamp("--------- beginning of main\nsome text\n"))
    }

    @Test
    fun aShortTextIsKeptWhole() {
        assertEquals("a\nb\nc", AppLogRecorder.keepTail("a\nb\nc", 100))
        assertEquals("", AppLogRecorder.keepTail("a\nb", 0))
    }

    @Test
    fun aLongTextIsCutAtALineBoundary() {
        // The last 7 characters are "\nline3\n"-ish; the partial "ine2" line must not be shown.
        assertEquals("line3", AppLogRecorder.keepTail("line1\nline2\nline3", 9))
        assertEquals("line3\nline4", AppLogRecorder.keepTail("line1\nline2\nline3\nline4", 13))
    }

    @Test
    fun aTextThatStartsInTheMiddleOfALineDropsThatLine() {
        assertEquals("whole1\nwhole2", AppLogRecorder.keepTail("rtial\nwhole1\nwhole2", 100, startsMidLine = true))
    }

    @Test
    fun theTailOfAFileStartsAtALineBoundary() {
        val lines = (1..200).joinToString("\n") { "line number $it" } + "\n"
        val file = tempFile(lines)
        val tail = AppLogRecorder.readTail(file, 100)
        assertTrue(tail.length <= 100)
        assertTrue(tail.startsWith("line number "))
        assertTrue(tail.endsWith("line number 200\n"))
    }

    @Test
    fun aSmallFileIsReadWhole() {
        val file = tempFile("only line\n")
        assertEquals("only line\n", AppLogRecorder.readTail(file, 1000))
    }

    @Test
    fun aMissingFileOrAnEmptyBudgetGivesNothing() {
        assertEquals("", AppLogRecorder.readTail(File("does-not-exist.txt"), 100))
        assertEquals("", AppLogRecorder.readTail(tempFile("text\n"), 0))
    }

    @Test
    fun multiByteCharactersDoNotBreakTheTail() {
        val file = tempFile((1..50).joinToString("\n") { "amount ₹$it" } + "\n")
        val tail = AppLogRecorder.readTail(file, 60)
        assertTrue(tail.startsWith("amount "))
        assertTrue(tail.endsWith("₹50\n"))
        assertTrue(!tail.contains('�'))
    }
}
