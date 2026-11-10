package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdaterNextVersionTest {
    private val tags = listOf("v8.10.47", "v8.10.46", "v8.10.45", "v8.10.44", "v8.9.9", "v8.10.9")

    private fun next(installed: String) = pickNextTag(parseVersionParts(installed)!!, tags)

    @Test
    fun aPhoneGetsTheNextReleaseNotTheNewest() {
        assertEquals("v8.10.45", next("8.10.44"))
        assertEquals("v8.10.46", next("8.10.45"))
        assertEquals("v8.10.47", next("8.10.46"))
    }

    @Test
    fun theNewestReleaseHasNothingAboveIt() {
        assertNull(next("8.10.47"))
        assertNull(next("8.11.0"))
    }

    @Test
    fun versionsAreComparedAsNumbersNotText() {
        // 8.10.9 is older than 8.10.44, and 8.9.9 older than 8.10.9.
        assertEquals("v8.10.9", next("8.9.9"))
        assertEquals("v8.10.44", next("8.10.9"))
    }

    @Test
    fun aPhoneFarBehindStillMovesOneStep() {
        assertEquals("v8.9.9", next("8.9.0"))
    }

    @Test
    fun tagsThatAreNotVersionsAreIgnored() {
        assertEquals("v8.10.45", pickNextTag(listOf(8, 10, 44), listOf("latest", "v8.10.45", "nightly", "v8.10.46")))
        assertNull(pickNextTag(listOf(8, 10, 44), listOf("latest", "nightly")))
    }

    private val sha = "a".repeat(64)

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
    fun versionTextIsParsed() {
        assertEquals(listOf(8L, 10L, 46L), parseVersionParts("v8.10.46"))
        assertEquals(listOf(8L, 10L, 46L), parseVersionParts(" 8.10.46 "))
        assertNull(parseVersionParts("8.10.x"))
        assertNull(parseVersionParts(""))
    }
}
