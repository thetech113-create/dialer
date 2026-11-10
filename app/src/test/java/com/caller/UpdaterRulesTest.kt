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
    fun theNextVersionIsExactlyOneStepUp() {
        assertEquals(true, isNextVersion(listOf(8, 10, 54), listOf(8, 10, 55)))
        assertEquals(true, isNextVersion(listOf(8, 10, 55), listOf(8, 11, 0)))
        assertEquals(true, isNextVersion(listOf(8, 10, 55), listOf(9, 0, 0)))
        assertEquals(true, isNextVersion(listOf(5, 0), listOf(5, 0, 1)))
    }

    @Test
    fun aSkippedOrOlderVersionIsNotTheNextOne() {
        assertEquals(false, isNextVersion(listOf(8, 10, 54), listOf(8, 10, 56)))
        assertEquals(false, isNextVersion(listOf(8, 10, 55), listOf(8, 11, 1)))
        assertEquals(false, isNextVersion(listOf(8, 10, 55), listOf(9, 1, 0)))
        assertEquals(false, isNextVersion(listOf(8, 10, 55), listOf(8, 10, 55)))
        assertEquals(false, isNextVersion(listOf(8, 10, 55), listOf(8, 10, 54)))
    }

    private fun v(patch: Long) = listOf(8L, 10L, patch)

    @Test
    fun theNewestReleaseIsAllowedWhenEveryReleaseBeforeItIsThereInOrder() {
        // On 8.10.54 with 8.10.59 as the newest: 55, 56, 57, 58 are all there.
        assertNull(releaseOrderProblem(v(54), v(59), listOf(v(59), v(58), v(57), v(56), v(55), v(54), v(53))))
        // The list may come in any order and may repeat a release.
        assertNull(releaseOrderProblem(v(54), v(59), listOf(v(56), v(59), v(55), v(58), v(57), v(57))))
        // One step up: nothing lies in between.
        assertNull(releaseOrderProblem(v(54), v(55), listOf(v(55))))
        // A new series starts at 0.
        assertNull(releaseOrderProblem(v(55), listOf(8, 11, 1), listOf(listOf(8, 11, 1), listOf(8, 11, 0), v(56))))
    }

    @Test
    fun theNewestReleaseIsRefusedWhenAReleaseBeforeItIsMissing() {
        assertEquals(
            "a release is missing between 8.10.56 and 8.10.58",
            releaseOrderProblem(v(54), v(59), listOf(v(59), v(58), v(56), v(55), v(54)))
        )
        assertEquals(
            "a release is missing between 8.10.54 and 8.10.56",
            releaseOrderProblem(v(54), v(59), listOf(v(59), v(58), v(57), v(56)))
        )
        assertEquals(
            "a release is missing between 8.10.58 and 8.10.60",
            releaseOrderProblem(v(57), v(60), listOf(v(60), v(58)))
        )
    }

    @Test
    fun theNewestReleaseIsRefusedWhenTheListDisagreesWithIt() {
        assertEquals(
            "release 8.10.60 is above the one marked as the latest",
            releaseOrderProblem(v(54), v(59), listOf(v(60), v(59), v(58), v(57), v(56), v(55)))
        )
        assertEquals(
            "it is not in the list of releases",
            releaseOrderProblem(v(54), v(59), listOf(v(58), v(57), v(56), v(55)))
        )
        assertEquals("it is not in the list of releases", releaseOrderProblem(v(54), v(59), emptyList()))
    }

    @Test
    fun oneRequestNormallyReachesFromTheNewestDownToTheInstalledRelease() {
        assertEquals(10, releasePageSize(v(54), v(59)))
        assertEquals(41, releasePageSize(v(19), v(59)))
        assertEquals(100, releasePageSize(v(1), v(400)))
        assertEquals(31, releasePageSize(v(55), listOf(8, 11, 3)))
    }

    /** A different file checksum for every number. */
    private fun file(number: Int) = number.toString(16).padStart(64, '0')

    private fun release(
        patch: Int,
        code: Long,
        name: String = "8.10.$patch",
        sha256: String? = file(patch),
        attached: String? = sha256
    ) = AppUpdateManager.Release("8.10.$patch", name, code, "https://example.invalid/Caller-8.10.$patch.apk", sha256, attached)

    @Test
    fun theNewestReleaseIsAllowedWhenEveryReleaseBeforeItIsAVersionOfItsOwn() {
        // A phone on 8.10.54 (version code 192) with 8.10.59 as the newest.
        assertNull(
            releaseChainProblem(192, listOf(release(55, 193), release(56, 194), release(57, 195), release(58, 196), release(59, 197)))
        )
        // One step up: only the newest release itself.
        assertNull(releaseChainProblem(192, listOf(release(55, 193))))
        // A version code may jump, it only has to go up.
        assertNull(releaseChainProblem(192, listOf(release(55, 193), release(56, 200))))
        // An upload GitHub kept no checksum of is judged by its update.json alone.
        assertNull(releaseChainProblem(192, listOf(release(55, 193, attached = null), release(56, 194))))
        // GitHub writes its checksum in small letters; update.json may use capitals.
        assertNull(releaseChainProblem(192, listOf(release(55, 193, sha256 = "AB".repeat(32), attached = "ab".repeat(32)))))
    }

    @Test
    fun aReleaseThatIsAnotherReleaseWithOnlyTheNumberChangedIsRefused() {
        // The file of 8.10.59 attached again as 8.10.55.
        assertEquals(
            "release 8.10.59 has the same file as release 8.10.55",
            releaseChainProblem(
                192,
                listOf(release(55, 193, sha256 = file(59)), release(56, 194), release(57, 195), release(58, 196), release(59, 197))
            )
        )
        // The update.json of 8.10.59 attached to 8.10.55.
        assertEquals(
            "release 8.10.55: update.json says version \"8.10.59\" but the release is 8.10.55",
            releaseChainProblem(192, listOf(release(55, 197, name = "8.10.59", sha256 = file(59)), release(56, 194)))
        )
        // An update.json written for 8.10.55, but the attached file is the one of 8.10.59.
        assertEquals(
            "release 8.10.55: the attached file is not the one its checksum names",
            releaseChainProblem(192, listOf(release(55, 193, attached = file(59)), release(56, 194)))
        )
    }

    @Test
    fun theVersionCodeHasToGoUpWithEveryRelease() {
        // 8.10.55 built from 8.10.59 keeps the version code of 8.10.59.
        assertEquals(
            "release 8.10.56 has version code 194, which is not above 197 of the version before it",
            releaseChainProblem(192, listOf(release(55, 197), release(56, 194), release(57, 195)))
        )
        assertEquals(
            "release 8.10.56 has version code 193, which is not above 193 of the version before it",
            releaseChainProblem(192, listOf(release(55, 193), release(56, 193)))
        )
        // The first release of the row is compared with the installed app.
        assertEquals(
            "release 8.10.55 has version code 192, which is not above 192 of the version before it",
            releaseChainProblem(192, listOf(release(55, 192), release(56, 194)))
        )
        // An update.json without a version code.
        assertEquals(
            "release 8.10.55 has version code -1, which is not above 192 of the version before it",
            releaseChainProblem(192, listOf(release(55, -1), release(56, 194)))
        )
    }

    @Test
    fun aReleaseInBetweenWithoutARealChecksumIsRefused() {
        assertEquals(
            "release 8.10.56: the release has no valid checksum",
            releaseChainProblem(192, listOf(release(55, 193), release(56, 194, sha256 = null), release(57, 195)))
        )
        assertEquals(
            "release 8.10.56: the release has no valid checksum",
            releaseChainProblem(192, listOf(release(55, 193), release(56, 194, sha256 = "abc"), release(57, 195)))
        )
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
