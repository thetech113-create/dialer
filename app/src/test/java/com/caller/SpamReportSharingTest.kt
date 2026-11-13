package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpamReportSharingTest {
    private fun remote(device: String, sender: String, spam: Int, ham: Int = 0, at: Long = 1L) =
        RemoteSenderReport(device, sender, spam, ham, at)

    @Test
    fun aPhoneNumberIsOneSenderHoweverItIsWritten() {
        val key = senderReportKey("+919876543210")
        assertEquals(key, senderReportKey("9876543210"))
        assertEquals(key, senderReportKey("09876543210"))
        assertEquals(key, senderReportKey("+91 98765 43210"))
        assertEquals(key, senderReportKey(" 98765-43210 "))
        assertNotEquals(key, senderReportKey("+919876543211"))
    }

    @Test
    fun aNameSenderIgnoresCaseAndSurroundingSpaces() {
        assertEquals(senderReportKey("AX-HDFCBK"), senderReportKey("ax-hdfcbk"))
        assertEquals(senderReportKey("AX-HDFCBK"), senderReportKey("  AX-HDFCBK "))
        assertNotEquals(senderReportKey("AX-HDFCBK"), senderReportKey("AX-HDFCBL"))
    }

    @Test
    fun theKeyIsAShortHashAndNeverTheSenderItself() {
        val key = senderReportKey("+919876543210")
        assertEquals(24, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertFalse(key.contains("9876543210"))
    }

    @Test
    fun aShortCodeIsNotTreatedAsAPhoneNumber() {
        assertNotEquals(senderReportKey("56767"), senderReportKey("5676700000"))
        assertNotEquals(senderReportKey("56767"), senderReportKey("12356767"))
    }

    @Test
    fun aSenderIsReportedAfterTwoNetReports() {
        assertFalse(isSenderReportedAsSpam(spamReports = 0, hamReports = 0, remoteNet = 0))
        assertFalse(isSenderReportedAsSpam(spamReports = 1, hamReports = 0, remoteNet = 0))
        assertTrue(isSenderReportedAsSpam(spamReports = 2, hamReports = 0, remoteNet = 0))
        assertTrue(isSenderReportedAsSpam(spamReports = 5, hamReports = 0, remoteNet = 0))
    }

    @Test
    fun aNotSpamAnswerTakesOneReportBack() {
        assertFalse(isSenderReportedAsSpam(spamReports = 2, hamReports = 1, remoteNet = 0))
        assertTrue(isSenderReportedAsSpam(spamReports = 3, hamReports = 1, remoteNet = 0))
        assertFalse(isSenderReportedAsSpam(spamReports = 0, hamReports = 3, remoteNet = 0))
    }

    @Test
    fun theReportsOfThePairedPhonesCountToo() {
        // One report here and one on the other phone is two.
        assertTrue(isSenderReportedAsSpam(spamReports = 1, hamReports = 0, remoteNet = 1))
        assertTrue(isSenderReportedAsSpam(spamReports = 0, hamReports = 0, remoteNet = 2))
        assertFalse(isSenderReportedAsSpam(spamReports = 0, hamReports = 0, remoteNet = 1))
        // The other phone said "Not spam" once, this phone reported twice: net one.
        assertFalse(isSenderReportedAsSpam(spamReports = 2, hamReports = 0, remoteNet = -1))
    }

    @Test
    fun theOwnPhoneRowsAreLeftOutOfWhatThePairedPhonesReported() {
        val nets = remoteSenderNets(
            listOf(remote("me", "s1", 5), remote("other", "s1", 1), remote("other", "s2", 0, ham = 2)),
            ownDeviceId = "me"
        )
        assertEquals(mapOf("s1" to 1, "s2" to -2), nets)
    }

    @Test
    fun aPhoneThatIsInTwoPairingsCountsOnce() {
        // The same phone paired twice shows up in two links; the newest row is the one that counts.
        val nets = remoteSenderNets(
            listOf(remote("other", "s1", 1, at = 10L), remote("other", "s1", 2, at = 20L), remote("other", "s1", 1, at = 5L)),
            ownDeviceId = "me"
        )
        assertEquals(mapOf("s1" to 2), nets)
    }

    @Test
    fun differentPairedPhonesAddUp() {
        val nets = remoteSenderNets(listOf(remote("a", "s1", 1), remote("b", "s1", 1)), ownDeviceId = "me")
        assertEquals(mapOf("s1" to 2), nets)
    }

    @Test
    fun onePhoneCannotAddMoreThanThreeEitherWay() {
        assertEquals(mapOf("s1" to 3), remoteSenderNets(listOf(remote("a", "s1", 500)), ownDeviceId = "me"))
        assertEquals(mapOf("s1" to -3), remoteSenderNets(listOf(remote("a", "s1", 0, ham = 500)), ownDeviceId = "me"))
    }

    @Test
    fun rowsWithoutAPhoneOrSenderAreIgnored() {
        val nets = remoteSenderNets(listOf(remote("", "s1", 3), remote("a", "", 3)), ownDeviceId = "me")
        assertTrue(nets.isEmpty())
    }
}
