package com.caller.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The phone that shows a pairing code builds a new PairingHost for every code, but its trust store is the saved file.
 * A phone that was approved by an earlier pairing is therefore still in the store when it pairs again (a one-way
 * receiver is not even listed on the phone that made the code, so it cannot be removed there).
 */
class PairingHostRepairTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val hostIdentity = DeviceIdentity.generateNew("Host-Phone")
    private val joinerIdentity = DeviceIdentity.generateNew("Joining-Phone")
    private val integrityKey = Crypto.randomBytes(32)

    private fun newHost(file: java.io.File) =
        PairingHost(hostIdentity, PersistentTrustStore(file, integrityKey), "Host-Phone")

    private fun requestFor(host: PairingHost, code: String): PairRequest {
        val offer = host.createOfferWithCode(code)
        return PairingClient(joinerIdentity, "Joining-Phone", "dev_JOINER_1")
            .buildRequest(PairingLink(code, offer.ownerId))
    }

    private fun trustFile() = tempFolder.newFile("host_trust.store").also { it.delete() }

    @Test
    fun aFirstRequestWaitsForTheHostsApproval() {
        val host = newHost(trustFile())
        val result = host.handleRequest(requestFor(host, "111111"))
        assertTrue(result is PairingHost.RequestResult.Accepted)
        assertEquals(1, host.pendingRequests().size)
    }

    @Test
    fun aPhoneApprovedBeforeIsAskedAboutAgainWhenItPairsWithANewCode() {
        val file = trustFile()

        val first = newHost(file)
        first.handleRequest(requestFor(first, "111111"))
        assertTrue(first.approve(first.pendingRequests().single().id))

        // Show Code again: a new host object and a new code, the saved trust store still holds the approved phone.
        val second = newHost(file)
        val result = second.handleRequest(requestFor(second, "222222"))

        assertTrue(result is PairingHost.RequestResult.Accepted)
        assertEquals("the host must get an approval prompt for the new code", 1, second.pendingRequests().size)
    }

    @Test
    fun aPhoneThatDoesNotKnowTheCodeIsStillRefusedEvenIfItWasApprovedBefore() {
        val file = trustFile()

        val first = newHost(file)
        first.handleRequest(requestFor(first, "111111"))
        first.approve(first.pendingRequests().single().id)

        val second = newHost(file)
        second.createOfferWithCode("222222")
        // The request was built for another code: its proof does not match this offer.
        val wrong = PairingClient(joinerIdentity, "Joining-Phone", "dev_JOINER_1")
            .buildRequest(PairingLink("999999", second.createOfferWithCode("222222").ownerId))
        val result = second.handleRequest(wrong)

        assertTrue(result is PairingHost.RequestResult.Rejected)
        assertTrue(second.pendingRequests().isEmpty())
    }

    @Test
    fun aRequestAlreadyAnsweredInThisOfferDoesNotAskAgain() {
        val host = newHost(trustFile())
        val request = requestFor(host, "111111")
        host.handleRequest(request)
        assertTrue(host.approve(host.pendingRequests().single().id))

        // The host keeps polling for a moment after approving and sees the same request again.
        val again = host.handleRequest(request)

        assertFalse(again is PairingHost.RequestResult.Accepted && host.pendingRequests().isNotEmpty())
        assertTrue(host.pendingRequests().isEmpty())
    }
}
