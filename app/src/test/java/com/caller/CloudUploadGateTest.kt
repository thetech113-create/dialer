package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit F-PRIV-1: the cloud topics are public ntfy.sh topics, and the shared account's topic name comes from the
 * phone's own number. A phone with no linked phone on that account used to upload its last 80 calls there anyway
 * (app log 2026-11-17 09:12: "calls uploaded (3612 B); no linked devices known yet"). Nothing may be uploaded or
 * announced on an account that nobody is paired on.
 */
class CloudUploadGateTest {
    @Test
    fun sharedAccountWithNoLinkedPhoneUploadsNothing() {
        assertFalse(cloudAccountHasReader(isPrivateLink = false, hasPairedPeer = false))
    }

    @Test
    fun sharedAccountWithALinkedPhoneStillSyncs() {
        // A phone paired before one-way links (trust-store peer on the shared account) keeps syncing as before.
        assertTrue(cloudAccountHasReader(isPrivateLink = false, hasPairedPeer = true))
    }

    @Test
    fun privateLinkAlwaysHasAReader() {
        // The source of a link does not list its receiver as a peer, but the receiver reads the link's documents.
        assertTrue(cloudAccountHasReader(isPrivateLink = true, hasPairedPeer = false))
        assertTrue(cloudAccountHasReader(isPrivateLink = true, hasPairedPeer = true))
    }
}
