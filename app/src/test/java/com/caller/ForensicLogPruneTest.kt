package com.caller

import com.caller.logging.FORENSIC_LOG_MAX_AGE_MS
import com.caller.logging.pruneOldForensicSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Audit F-LOG-1: forensic session files were never deleted (an export of 2026-11-22 still held files from 10-22). */
class ForensicLogPruneTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun sessionsOlderThanTwoWeeksAreDeletedNewerOnesAndOtherFilesStay() {
        val now = 1_800_000_000_000L
        val dir = folder.newFolder("logs")
        val old = dir.resolve("Caller_Session_2026-10-22_14-14-40.txt").apply { writeText("x"); setLastModified(now - FORENSIC_LOG_MAX_AGE_MS - 60_000) }
        val recent = dir.resolve("Caller_Session_2026-11-20_08-00-00.txt").apply { writeText("x"); setLastModified(now - 60_000) }
        val other = dir.resolve("app_log.txt").apply { writeText("x"); setLastModified(now - FORENSIC_LOG_MAX_AGE_MS - 60_000) }

        assertEquals(1, pruneOldForensicSessions(dir, now))
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(other.exists())
    }

    @Test
    fun aMissingFolderIsNotAnError() {
        assertEquals(0, pruneOldForensicSessions(folder.root.resolve("absent"), 1_800_000_000_000L))
    }
}
