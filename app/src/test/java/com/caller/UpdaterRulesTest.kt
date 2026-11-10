package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterRulesTest {
    private val sha = "a".repeat(64)

    @Test
    fun versionsAreComparedAsNumbersNotText() {
        // 8.10.9 is older than 8.10.44, and 8.9.9 older than 8.10.9.
        assertEquals(true, compareVersionParts(listOf(8, 10, 44), listOf(8, 10, 9)) > 0)
        assertEquals(true, compareVersionParts(listOf(8, 10, 9), listOf(8, 9, 9)) > 0)
        assertEquals(true, compareVersionParts(listOf(8, 10, 46), listOf(8, 10, 47)) < 0)
        assertEquals(0, compareVersionParts(listOf(8, 10, 47), listOf(8, 10, 47)))
    }

    @Test
    fun missingPartsCountAsZero() {
        assertEquals(0, compareVersionParts(listOf(5, 0), listOf(5, 0, 0)))
        assertEquals(true, compareVersionParts(listOf(5, 1), listOf(5, 0, 9)) > 0)
    }

    @Test
    fun aConsistentNewerReleaseMayBeInstalled() {
        assertNull(releaseProblem("8.10.46", "8.10.46", 184, sha, 183))
        // Old releases wrote "5.0" for the tag v5.0.0: the same version.
        assertNull(releaseProblem("5.0.0", "5.0", 100, sha, 99))
    }

    @Test
    fun aReleaseWhoseManifestNamesAnotherVersionIsRejected() {
        assertEquals(
            "update.json says version \"8.10.47\" but the release is 8.10.46",
            releaseProblem("8.10.46", "8.10.47", 184, sha, 183)
        )
        assertEquals(
            "update.json says version \"\" but the release is 8.10.46",
            releaseProblem("8.10.46", "", 184, sha, 183)
        )
    }

    @Test
    fun aReleaseWithoutARealChecksumIsRejected() {
        assertEquals("the release has no valid checksum", releaseProblem("8.10.46", "8.10.46", 184, null, 183))
        assertEquals("the release has no valid checksum", releaseProblem("8.10.46", "8.10.46", 184, "abc", 183))
        assertEquals("the release has no valid checksum", releaseProblem("8.10.46", "8.10.46", 184, "g".repeat(64), 183))
    }

    @Test
    fun aReleaseThatIsNotNewerIsRejected() {
        assertEquals("it is not newer than the installed version", releaseProblem("8.10.46", "8.10.46", 183, sha, 183))
        assertEquals("it is not newer than the installed version", releaseProblem("8.10.46", "8.10.46", 100, sha, 183))
    }

    @Test
    fun aDownloadedFileIsInstalledOnlyWhenItIsTheNewestRelease() {
        assertEquals(PendingFileAction.INSTALL, pendingFileAction(pendingVersionCode = 192, newestVersionCode = 192, checkFailed = false))
        assertEquals(PendingFileAction.INSTALL, pendingFileAction(pendingVersionCode = 193, newestVersionCode = 192, checkFailed = false))
    }

    @Test
    fun anOlderDownloadedFileIsDeletedWhenANewerReleaseExists() {
        assertEquals(PendingFileAction.DISCARD, pendingFileAction(pendingVersionCode = 190, newestVersionCode = 192, checkFailed = false))
    }

    @Test
    fun aDownloadedFileIsDeletedWhenNothingIsNewerThanTheInstalledVersionAnyMore() {
        assertEquals(PendingFileAction.DISCARD, pendingFileAction(pendingVersionCode = 190, newestVersionCode = null, checkFailed = false))
    }

    @Test
    fun aDownloadedFileIsKeptWhenTheCheckCouldNotRun() {
        assertEquals(PendingFileAction.INSTALL, pendingFileAction(pendingVersionCode = 190, newestVersionCode = null, checkFailed = true))
    }

    @Test
    fun aSecondTapRightAfterTheFirstIsADuplicate() {
        assertEquals(true, isDuplicateTap(nowMs = 10_000, lastMs = 10_000, windowMs = 1500))
        assertEquals(true, isDuplicateTap(nowMs = 11_499, lastMs = 10_000, windowMs = 1500))
    }

    @Test
    fun aTapAfterTheWindowIsANewTap() {
        assertEquals(false, isDuplicateTap(nowMs = 11_500, lastMs = 10_000, windowMs = 1500))
        assertEquals(false, isDuplicateTap(nowMs = 60_000, lastMs = 10_000, windowMs = 1500))
    }

    @Test
    fun theVeryFirstTapIsNeverADuplicate() {
        // lastMs starts at 0 and the clock is far past the window.
        assertEquals(false, isDuplicateTap(nowMs = 5_000_000, lastMs = 0, windowMs = 1500))
    }

    @Test
    fun versionTextIsParsed() {
        assertEquals(listOf(8L, 10L, 46L), parseVersionParts("v8.10.46"))
        assertEquals(listOf(8L, 10L, 46L), parseVersionParts(" 8.10.46 "))
        assertNull(parseVersionParts("8.10.x"))
        assertNull(parseVersionParts(""))
    }
}
