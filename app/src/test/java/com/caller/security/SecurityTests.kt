package com.caller.security

import java.nio.file.Files
import org.junit.Test
import org.junit.Assert.*

class TestClock { @Volatile var t = 1_700_000_000_000L }

class Fixture {
    val clock = TestClock()
    val now: () -> Long = { clock.t }
    val hostId = DeviceIdentity.generate()
    val hostTrust = InMemoryTrustStore()
    val joinerId = DeviceIdentity.generate()
    val joinerTrust = InMemoryTrustStore()
    val host = PairingHost(hostId, hostTrust, "Phone", now)
    val joiner = PairingClient(joinerId, "Laptop", now)
    val auth = SyncAuthenticator(hostId, hostTrust, now)

    fun linkFromNewOffer(): PairingLink = PairingLink.parse(host.createOffer().link)!!

    fun pairFully(): String {
        val link = linkFromNewOffer()
        val r = host.handleRequest(joiner.buildRequest(link)) as PairingHost.RequestResult.Accepted
        assertTrue(host.approve(r.pairingId))
        val dev = joiner.verifyApproval(link, r.pairingId, host.status(r.pairingId))
        assertNotNull(dev)
        joinerTrust.put(dev!!)
        return r.pairingId
    }

    fun signed(method: String = "GET", target: String = "/sync/calls", body: ByteArray = ByteArray(0)) =
        SyncAuth.sign(joinerId, hostId.id, method, target, body, now())

    fun check(headers: Map<String, String>, method: String = "GET", target: String = "/sync/calls", body: ByteArray = ByteArray(0)) =
        auth.authenticate(method, target, headers, body)
}

class PairingSecurityTests {
    @Test fun sameTypedNumberDoesNotPair() {
        val f = Fixture()
        f.host.createOffer()
        val r = f.host.handleRequest(f.joiner.buildRequest(PairingLink("1111", f.hostId.id)))
        assertTrue(r is PairingHost.RequestResult.Rejected)
        assertTrue(f.hostTrust.all().isEmpty())
        assertTrue(f.check(f.signed()) is AuthResult.Denied)
    }

    @Test fun unknownDeviceIsDenied() {
        val f = Fixture()
        assertTrue(f.check(f.signed()) is AuthResult.Denied)
    }

    @Test fun correctCodeAloneIsNotEnoughWithoutApproval() {
        val f = Fixture()
        val link = f.linkFromNewOffer()
        val r = f.host.handleRequest(f.joiner.buildRequest(link)) as PairingHost.RequestResult.Accepted
        assertEquals("PENDING", f.host.status(r.pairingId).state)
        assertTrue(f.check(f.signed()) is AuthResult.Denied)
        f.host.deny(r.pairingId)
        assertEquals("DENIED", f.host.status(r.pairingId).state)
        assertTrue(f.check(f.signed()) is AuthResult.Denied)
    }

    @Test fun approvedDeviceCanSync() {
        val f = Fixture()
        f.pairFully()
        assertTrue(f.check(f.signed()) is AuthResult.Allowed)
    }

    @Test fun revokedDeviceIsDeniedOnNextRequest() {
        val f = Fixture()
        f.pairFully()
        assertTrue(f.check(f.signed()) is AuthResult.Allowed)
        f.hostTrust.revoke(f.joinerId.id)
        assertTrue(f.check(f.signed()) is AuthResult.Denied)
    }

    @Test fun replayedRequestIsDenied() {
        val f = Fixture()
        f.pairFully()
        val h = f.signed()
        assertTrue(f.check(h) is AuthResult.Allowed)
        assertTrue(f.check(h) is AuthResult.Denied)
    }

    @Test fun tamperedBodyOrTargetIsDenied() {
        val f = Fixture()
        f.pairFully()
        val h = f.signed("POST", "/sync/calls", "a".toByteArray())
        assertTrue(f.check(h, "POST", "/sync/calls", "b".toByteArray()) is AuthResult.Denied)
        val h2 = f.signed()
        assertTrue(f.check(h2, "GET", "/sync/other") is AuthResult.Denied)
    }

    @Test fun staleTimestampIsDenied() {
        val f = Fixture()
        f.pairFully()
        val h = f.signed()
        f.clock.t += 120_000
        assertTrue(f.check(h) is AuthResult.Denied)
    }

    @Test fun requestSignedForAnotherHostIsDenied() {
        val f = Fixture()
        f.pairFully()
        val other = DeviceIdentity.generate()
        val h = SyncAuth.sign(f.joinerId, other.id, "GET", "/sync/calls", ByteArray(0), f.now())
        assertTrue(f.check(h) is AuthResult.Denied)
    }

    @Test fun offerIsSingleUse() {
        val f = Fixture()
        val link = f.linkFromNewOffer()
        assertTrue(f.host.handleRequest(f.joiner.buildRequest(link)) is PairingHost.RequestResult.Accepted)
        val attacker = PairingClient(DeviceIdentity.generate(), "Evil", f.now)
        assertTrue(f.host.handleRequest(attacker.buildRequest(link)) is PairingHost.RequestResult.Rejected)
    }

    @Test fun guessingLocksTheOfferOut() {
        val f = Fixture()
        val link = f.linkFromNewOffer()
        repeat(5) {
            val bad = PairingLink("AAAAAAAAAA", f.hostId.id)
            assertTrue(f.host.handleRequest(f.joiner.buildRequest(bad)) is PairingHost.RequestResult.Rejected)
        }
        assertTrue(f.host.handleRequest(f.joiner.buildRequest(link)) is PairingHost.RequestResult.Rejected)
    }

    @Test fun expiredOfferIsRejected() {
        val f = Fixture()
        val link = f.linkFromNewOffer()
        f.clock.t += 121_000
        assertTrue(f.host.handleRequest(f.joiner.buildRequest(link)) is PairingHost.RequestResult.Rejected)
    }

    @Test fun impostorHostCannotBePinned() {
        val f = Fixture()
        val attackerId = DeviceIdentity.generate()
        val attackerHost = PairingHost(attackerId, InMemoryTrustStore(), "Evil", f.now)
        val attackerLink = PairingLink.parse(attackerHost.createOffer().link)!!
        val r = attackerHost.handleRequest(f.joiner.buildRequest(attackerLink)) as PairingHost.RequestResult.Accepted
        attackerHost.approve(r.pairingId)
        val realLink = PairingLink(attackerLink.code, f.hostId.id)
        assertNull(f.joiner.verifyApproval(realLink, r.pairingId, attackerHost.status(r.pairingId)))
    }

    @Test fun requestBoundToAnotherHostIsRejected() {
        val f = Fixture()
        val offer = f.host.createOffer()
        val wrongOwner = PairingLink(offer.code, DeviceIdentity.generate().id)
        assertTrue(f.host.handleRequest(f.joiner.buildRequest(wrongOwner)) is PairingHost.RequestResult.Rejected)
    }

    @Test fun trustAndIdentitySurviveRestart() {
        val dir = Files.createTempDirectory("dialer-sec").toFile()
        val hostIdFile = dir.resolve("host.id")
        val trustFile = dir.resolve("trust.db")
        val joiner = DeviceIdentity.generate()
        val clock = TestClock()
        val now: () -> Long = { clock.t }

        val hostId = DeviceIdentity.loadOrCreate(hostIdFile)
        val host = PairingHost(hostId, FileTrustStore(trustFile), "Phone", now)
        val link = PairingLink.parse(host.createOffer().link)!!
        val r = host.handleRequest(PairingClient(joiner, "Laptop", now).buildRequest(link)) as PairingHost.RequestResult.Accepted
        assertTrue(host.approve(r.pairingId))

        val hostId2 = DeviceIdentity.loadOrCreate(hostIdFile)
        assertEquals(hostId.id, hostId2.id)
        val store2 = FileTrustStore(trustFile)
        val auth2 = SyncAuthenticator(hostId2, store2, now)
        val h = SyncAuth.sign(joiner, hostId2.id, "GET", "/sync/calls", ByteArray(0), now())
        assertTrue(auth2.authenticate("GET", "/sync/calls", h, ByteArray(0)) is AuthResult.Allowed)

        store2.revoke(joiner.id)
        val auth3 = SyncAuthenticator(hostId2, FileTrustStore(trustFile), now)
        val h2 = SyncAuth.sign(joiner, hostId2.id, "GET", "/sync/calls", ByteArray(0), now())
        assertTrue(auth3.authenticate("GET", "/sync/calls", h2, ByteArray(0)) is AuthResult.Denied)
    }

    @Test fun sixDigitPasscodePairingFlow() {
        val f = Fixture()
        val code6 = "482916"
        f.host.createOfferWithCode(code6)
        val link = PairingLink(code6, f.hostId.id)
        val r = f.host.handleRequest(f.joiner.buildRequest(link)) as PairingHost.RequestResult.Accepted
        assertEquals("PENDING", f.host.status(r.pairingId).state)
        assertTrue(f.host.approve(r.pairingId))
        val dev = f.joiner.verifyApproval(link, r.pairingId, f.host.status(r.pairingId))
        assertNotNull(dev)
    }
}
