package com.caller.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecurityTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var hostIdentity: DeviceIdentity
    private lateinit var joinerIdentity: DeviceIdentity
    private lateinit var attackerIdentity: DeviceIdentity

    private lateinit var hostTrustFile: File
    private lateinit var joinerTrustFile: File
    private lateinit var hostIntegrityKey: ByteArray
    private lateinit var joinerIntegrityKey: ByteArray

    private lateinit var hostTrustStore: PersistentTrustStore
    private lateinit var joinerTrustStore: PersistentTrustStore
    private lateinit var hostCoordinator: MainDevicePairingCoordinator

    private val hostCallHistory = mutableListOf("""{"callId":"c1","remoteNumber":"+919876543210","durationSec":45}""")
    private val ingestedByHost = mutableListOf<String>()

    @Before
    fun setUp() {
        hostIdentity = DeviceIdentity.generateNew("Main-Phone-Host")
        joinerIdentity = DeviceIdentity.generateNew("Secondary-Phone-Joiner")
        attackerIdentity = DeviceIdentity.generateNew("Attacker-Phone")

        hostTrustFile = tempFolder.newFile("host_trust.store")
        joinerTrustFile = tempFolder.newFile("joiner_trust.store")
        hostTrustFile.delete()
        joinerTrustFile.delete()

        hostIntegrityKey = Crypto.randomBytes(32)
        joinerIntegrityKey = Crypto.randomBytes(32)

        hostTrustStore = PersistentTrustStore(hostTrustFile, hostIntegrityKey)
        joinerTrustStore = PersistentTrustStore(joinerTrustFile, joinerIntegrityKey)
        hostCoordinator = MainDevicePairingCoordinator(hostIdentity, hostTrustStore)
    }

    private fun buildHostGateway(): AuthenticatedSyncGateway {
        val hostAuth = SyncAuthenticator(hostIdentity, hostTrustStore)
        return AuthenticatedSyncGateway(
            authenticator = hostAuth,
            callHistoryProvider = { hostCallHistory.joinToString(",", "[", "]") },
            callHistoryIngestor = { _, json -> ingestedByHost.add(json) }
        )
    }

    private fun completeValidPairing(nowMs: Long = 1_700_000_000_000L): JoinerPairingSession {
        val joinerSession = JoinerPairingSession.initiate(joinerIdentity, nowMs)
        val pendingInfo = hostCoordinator.receivePairingRequest(joinerSession.requestMessage, nowMs + 100)
        assertEquals(PendingSessionStage.AWAITING_CODE_VERIFICATION, pendingInfo.stage)

        // Matching code alone MUST NOT establish trust
        val verifiedInfo = hostCoordinator.verifyPairingCode(
            sessionId = pendingInfo.sessionId,
            enteredCode = joinerSession.pairingCode,
            nowMs = nowMs + 200
        )
        assertEquals(PendingSessionStage.CODE_VERIFIED_AWAITING_EXPLICIT_APPROVAL, verifiedInfo.stage)
        assertFalse(hostTrustStore.isTrustedPeer(joinerIdentity.deviceId, joinerIdentity.signingPublicKeyB64))

        // Explicit approval on Main Device
        val receipt = hostCoordinator.approvePendingSession(
            sessionId = verifiedInfo.sessionId,
            expectedJoinerDeviceId = joinerIdentity.deviceId,
            expectedJoinerFingerprint = joinerIdentity.fingerprint,
            explicitUserConfirmation = true,
            nowMs = nowMs + 300
        )
        joinerSession.verifyApprovalAndEstablishTrust(receipt, joinerTrustStore, nowMs + 400)
        return joinerSession
    }

    // Scenario 1: Correct code and valid explicit approval from the intended device
    @Test
    fun scenario01_correctCodeAndValidApprovalEstablishesMutualTrustAndSyncs() {
        val nowMs = 1_700_000_000_000L
        completeValidPairing(nowMs)

        assertTrue(hostTrustStore.isTrustedPeer(joinerIdentity.deviceId, joinerIdentity.signingPublicKeyB64))
        assertTrue(joinerTrustStore.isTrustedPeer(hostIdentity.deviceId, hostIdentity.signingPublicKeyB64))

        val joinerAuth = SyncAuthenticator(joinerIdentity, joinerTrustStore)
        val hostGateway = buildHostGateway()

        val pullEnv = joinerAuth.createAuthenticatedEnvelope(
            recipientDeviceId = hostIdentity.deviceId,
            operation = SyncOperation.SYNC_PULL_CALLS,
            plaintextPayloadUtf8 = """{"request":"PULL"}""",
            nowMs = nowMs + 1_000
        )
        val resp = hostGateway.handleIncomingSync(
            SyncTransportRequest(
                endpointPath = AuthenticatedSyncGateway.PATH_PULL_CALLS,
                sourceNetworkTag = "WIFI_LAN:192.168.1.10",
                envelope = pullEnv
            ),
            nowMs = nowMs + 1_100
        )
        assertEquals(200, resp.statusCode)
        assertNotNull(resp.responseEnvelope)

        val decrypted = joinerAuth.verifyAndDecrypt(
            resp.responseEnvelope!!,
            expectedOperation = SyncOperation.SYNC_PULL_CALLS,
            nowMs = nowMs + 1_200
        )
        assertTrue(decrypted.plaintextUtf8.contains("+919876543210"))
    }

    // Scenario 2 & Bug Report Acceptance Test 1: Incorrect code or shared manual code ("1111") alone never pairs
    @Test
    fun scenario02_incorrectCodeAndSharedManualNumber1111CannotPair() {
        val nowMs = 1_700_000_000_000L
        val joinerSession = JoinerPairingSession.initiate(joinerIdentity, nowMs)
        hostCoordinator.receivePairingRequest(joinerSession.requestMessage, nowMs + 50)

        try {
            hostCoordinator.verifyPairingCode(joinerSession.sessionId, "1111", nowMs + 100)
            fail("Expected SecurityException for wrong code 1111")
        } catch (_: SecurityException) {
            // Expected
        }
        assertFalse(hostTrustStore.isTrustedPeer(joinerIdentity.deviceId, joinerIdentity.signingPublicKeyB64))
    }

    // Scenario 3: Attacker without the correct code cannot approve or sync
    @Test
    fun scenario03_attackerWithoutCorrectCodeDeniedApprovalAndSync() {
        val nowMs = 1_700_000_000_000L
        val attackerSession = JoinerPairingSession.initiate(attackerIdentity, nowMs)
        hostCoordinator.receivePairingRequest(attackerSession.requestMessage, nowMs + 50)

        try {
            hostCoordinator.approvePendingSession(
                sessionId = attackerSession.sessionId,
                expectedJoinerDeviceId = attackerIdentity.deviceId,
                expectedJoinerFingerprint = attackerIdentity.fingerprint,
                explicitUserConfirmation = true,
                nowMs = nowMs + 100
            )
            fail("Expected failure when approving before code verification")
        } catch (_: SecurityException) {
            // Expected
        }
        assertFalse(hostTrustStore.isTrustedPeer(attackerIdentity.deviceId, attackerIdentity.signingPublicKeyB64))
    }

    // Scenario 4: Attacker who intercepts/obtains the legitimate joiner's code cannot use it for their own device
    @Test
    fun scenario04_attackerInterceptingCodeCannotAuthorizeAttackerDevice() {
        val nowMs = 1_700_000_000_000L
        val legitimateSession = JoinerPairingSession.initiate(joinerIdentity, nowMs)
        val stolenCode = legitimateSession.pairingCode

        // Attacker creates their own session and tries to verify using the stolen code
        val attackerSession = JoinerPairingSession.initiate(attackerIdentity, nowMs)
        hostCoordinator.receivePairingRequest(attackerSession.requestMessage, nowMs + 50)

        try {
            hostCoordinator.verifyPairingCode(attackerSession.sessionId, stolenCode, nowMs + 100)
            if (stolenCode != attackerSession.pairingCode) {
                fail("Stolen code from another device/session must not verify attacker's session")
            }
        } catch (_: SecurityException) {
            // Expected
        }

        // Even if legitimate joiner's code is verified on legitimate session, it STILL is not trusted without Step 4 approval
        hostCoordinator.receivePairingRequest(legitimateSession.requestMessage, nowMs + 120)
        hostCoordinator.verifyPairingCode(legitimateSession.sessionId, stolenCode, nowMs + 150)
        assertFalse(hostTrustStore.isTrustedPeer(joinerIdentity.deviceId, joinerIdentity.signingPublicKeyB64))
        assertFalse(hostTrustStore.isTrustedPeer(attackerIdentity.deviceId, attackerIdentity.signingPublicKeyB64))
    }

    // Scenario 5: Code guessing, brute-force attempts, and global rate limiting
    @Test
    fun scenario05_codeGuessingAndBruteForceLockoutAndRateLimit() {
        val nowMs = 1_700_000_000_000L
        val joinerSession = JoinerPairingSession.initiate(joinerIdentity, nowMs)
        hostCoordinator.receivePairingRequest(joinerSession.requestMessage, nowMs + 10)

        // 3 wrong attempts permanently invalidate the session
        for (attempt in 1..3) {
            try {
                hostCoordinator.verifyPairingCode(joinerSession.sessionId, "00000$attempt", nowMs + attempt * 10L)
                fail("Should fail on wrong code")
            } catch (_: SecurityException) {
                // Expected
            }
        }

        // 4th attempt with the ACTUAL correct code must now fail because the session was destroyed
        try {
            hostCoordinator.verifyPairingCode(joinerSession.sessionId, joinerSession.pairingCode, nowMs + 100)
            fail("Session should have been permanently invalidated after 3 wrong guesses")
        } catch (_: SecurityException) {
            // Expected
        }

        // Global rate limit check (more than 5 attempts inside 60s window)
        val s2 = JoinerPairingSession.initiate(DeviceIdentity.generateNew("Joiner-2"), nowMs + 200)
        hostCoordinator.receivePairingRequest(s2.requestMessage, nowMs + 210)
        try {
            hostCoordinator.verifyPairingCode(s2.sessionId, "999999", nowMs + 220)
        } catch (_: SecurityException) {}

        try {
            hostCoordinator.verifyPairingCode(s2.sessionId, s2.pairingCode, nowMs + 230)
            fail("Global rate limit (5 attempts / 60s) should have blocked the 6th attempt")
        } catch (e: SecurityException) {
            assertTrue(e.message?.contains("Rate limit") == true)
        }
    }

    // Scenario 6: Expired and reused codes
    @Test
    fun scenario06_expiredAndReusedCodesAreRejected() {
        val nowMs = 1_700_000_000_000L
        val joinerSession = JoinerPairingSession.initiate(joinerIdentity, nowMs)
        hostCoordinator.receivePairingRequest(joinerSession.requestMessage, nowMs + 100)

        // Try verifying after 120s TTL has elapsed
        try {
            hostCoordinator.verifyPairingCode(
                sessionId = joinerSession.sessionId,
                enteredCode = joinerSession.pairingCode,
                nowMs = nowMs + JoinerPairingSession.PAIRING_TTL_MS + 1_000L
            )
            fail("Expired pairing session must be rejected")
        } catch (_: SecurityException) {
            // Expected
        }

        // Verify single-use code: once verified, a second verifyPairingCode call on the same session invalidates it
        val s2 = JoinerPairingSession.initiate(joinerIdentity, nowMs + 300_000L)
        hostCoordinator.receivePairingRequest(s2.requestMessage, nowMs + 300_100L)
        hostCoordinator.verifyPairingCode(s2.sessionId, s2.pairingCode, nowMs + 300_200L)
        try {
            hostCoordinator.verifyPairingCode(s2.sessionId, s2.pairingCode, nowMs + 300_300L)
            fail("Reusing code verification on same session must fail and invalidate")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    // Scenario 7: Approval replay
    @Test
    fun scenario07_approvalReplayIsRejected() {
        val nowMs = 1_700_000_000_000L
        val joinerSession = JoinerPairingSession.initiate(joinerIdentity, nowMs)
        hostCoordinator.receivePairingRequest(joinerSession.requestMessage, nowMs + 50)
        hostCoordinator.verifyPairingCode(joinerSession.sessionId, joinerSession.pairingCode, nowMs + 100)

        val receipt = hostCoordinator.approvePendingSession(
            sessionId = joinerSession.sessionId,
            expectedJoinerDeviceId = joinerIdentity.deviceId,
            expectedJoinerFingerprint = joinerIdentity.fingerprint,
            explicitUserConfirmation = true,
            nowMs = nowMs + 150
        )

        // Re-approving the same session on Host fails
        try {
            hostCoordinator.approvePendingSession(
                sessionId = joinerSession.sessionId,
                expectedJoinerDeviceId = joinerIdentity.deviceId,
                expectedJoinerFingerprint = joinerIdentity.fingerprint,
                explicitUserConfirmation = true,
                nowMs = nowMs + 200
            )
            fail("Re-approving consumed session must fail")
        } catch (_: SecurityException) {
            // Expected
        }

        // First verification on Joiner succeeds
        joinerSession.verifyApprovalAndEstablishTrust(receipt, joinerTrustStore, nowMs + 250)

        // Replaying the approval receipt on Joiner fails
        try {
            joinerSession.verifyApprovalAndEstablishTrust(receipt, joinerTrustStore, nowMs + 260)
            fail("Replaying approval receipt on joiner session must fail")
        } catch (_: SecurityException) {
            // Expected
        }

        // Replaying the approval receipt onto a different Joiner session fails
        val otherSession = JoinerPairingSession.initiate(attackerIdentity, nowMs + 300)
        try {
            otherSession.verifyApprovalAndEstablishTrust(receipt, PersistentTrustStore(), nowMs + 350)
            fail("Replaying approval receipt to another device/session must fail")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    // Scenario 8: Pairing-request substitution and session swapping
    @Test
    fun scenario08_pairingRequestSubstitutionAndSessionSwappingRejected() {
        val nowMs = 1_700_000_000_000L
        val joinerSession = JoinerPairingSession.initiate(joinerIdentity, nowMs)
        val attackerSession = JoinerPairingSession.initiate(attackerIdentity, nowMs)

        hostCoordinator.receivePairingRequest(joinerSession.requestMessage, nowMs + 50)
        hostCoordinator.receivePairingRequest(attackerSession.requestMessage, nowMs + 60)

        hostCoordinator.verifyPairingCode(attackerSession.sessionId, attackerSession.pairingCode, nowMs + 100)

        // Host user intends to approve joinerIdentity, but attacker's sessionId is swapped in
        try {
            hostCoordinator.approvePendingSession(
                sessionId = attackerSession.sessionId,
                expectedJoinerDeviceId = joinerIdentity.deviceId,
                expectedJoinerFingerprint = joinerIdentity.fingerprint,
                explicitUserConfirmation = true,
                nowMs = nowMs + 150
            )
            fail("Session swapping must be rejected when joiner identity does not match expected target")
        } catch (_: SecurityException) {
            // Expected
        }
        assertFalse(hostTrustStore.isTrustedPeer(attackerIdentity.deviceId, attackerIdentity.signingPublicKeyB64))
    }

    // Scenario 9: Forged device identity
    @Test
    fun scenario09_forgedDeviceIdentityIsRejected() {
        val nowMs = 1_700_000_000_000L
        val attackerSession = JoinerPairingSession.initiate(attackerIdentity, nowMs)
        // Attacker claims joinerIdentity's deviceId while signing with attacker's key
        val forgedRequest = attackerSession.requestMessage.copy(
            joinerDeviceId = joinerIdentity.deviceId
        )
        try {
            hostCoordinator.receivePairingRequest(forgedRequest, nowMs + 50)
            fail("Forged deviceId not matching signing public key fingerprint must be rejected")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    // Scenario 10: Modified or forged approval receipt
    @Test
    fun scenario10_modifiedOrForgedApprovalReceiptIsRejected() {
        val nowMs = 1_700_000_000_000L
        val joinerSession = JoinerPairingSession.initiate(joinerIdentity, nowMs)
        hostCoordinator.receivePairingRequest(joinerSession.requestMessage, nowMs + 50)
        hostCoordinator.verifyPairingCode(joinerSession.sessionId, joinerSession.pairingCode, nowMs + 100)

        val validReceipt = hostCoordinator.approvePendingSession(
            sessionId = joinerSession.sessionId,
            expectedJoinerDeviceId = joinerIdentity.deviceId,
            expectedJoinerFingerprint = joinerIdentity.fingerprint,
            explicitUserConfirmation = true,
            nowMs = nowMs + 150
        )

        // Tamper with hostSigningPubB64 or signature
        val forgedReceipt = validReceipt.copy(
            hostSigningPubB64 = attackerIdentity.signingPublicKeyB64,
            hostDeviceId = attackerIdentity.deviceId
        )
        try {
            joinerSession.verifyApprovalAndEstablishTrust(forgedReceipt, joinerTrustStore, nowMs + 200)
            fail("Forged approval receipt must be rejected")
        } catch (_: SecurityException) {
            // Expected
        }
        assertFalse(joinerTrustStore.isTrustedPeer(attackerIdentity.deviceId, attackerIdentity.signingPublicKeyB64))
    }

    // Scenario 11: Man-in-the-middle (MITM) ephemeral key substitution attempt
    @Test
    fun scenario11_manInTheMiddleEphemeralKeySubstitutionFails() {
        val nowMs = 1_700_000_000_000L
        val joinerSession = JoinerPairingSession.initiate(joinerIdentity, nowMs)
        val mitmEphemeralKp = Crypto.generateEcKeyPair()
        val mitmEphemeralPubB64 = Crypto.encodeB64(mitmEphemeralKp.public.encoded)

        // MITM attempts to swap joinerEphemeralKemPubB64 in transit
        val tamperedReq = joinerSession.requestMessage.copy(
            joinerEphemeralKemPubB64 = mitmEphemeralPubB64
        )
        try {
            hostCoordinator.receivePairingRequest(tamperedReq, nowMs + 50)
            fail("MITM tampered ephemeral key in request must fail ECDSA verification")
        } catch (_: SecurityException) {
            // Expected
        }

        // MITM attempts to swap hostEphemeralKemPubB64 in the ApprovalReceipt
        hostCoordinator.receivePairingRequest(joinerSession.requestMessage, nowMs + 60)
        hostCoordinator.verifyPairingCode(joinerSession.sessionId, joinerSession.pairingCode, nowMs + 100)
        val receipt = hostCoordinator.approvePendingSession(
            sessionId = joinerSession.sessionId,
            expectedJoinerDeviceId = joinerIdentity.deviceId,
            expectedJoinerFingerprint = joinerIdentity.fingerprint,
            explicitUserConfirmation = true,
            nowMs = nowMs + 150
        )
        val mitmReceipt = receipt.copy(hostEphemeralKemPubB64 = mitmEphemeralPubB64)
        try {
            joinerSession.verifyApprovalAndEstablishTrust(mitmReceipt, joinerTrustStore, nowMs + 200)
            fail("MITM tampered ephemeral key in receipt must fail transcript hash verification")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    // Scenario 12: Duplicate simultaneous pairing requests
    @Test
    fun scenario12_duplicateSimultaneousPairingRequestsFromSameDeviceRejected() {
        val nowMs = 1_700_000_000_000L
        val s1 = JoinerPairingSession.initiate(joinerIdentity, nowMs)
        val s2 = JoinerPairingSession.initiate(joinerIdentity, nowMs + 10)

        hostCoordinator.receivePairingRequest(s1.requestMessage, nowMs + 20)
        try {
            hostCoordinator.receivePairingRequest(s2.requestMessage, nowMs + 30)
            fail("Ambiguous second pending request for the same device identity must be rejected")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    // Scenario 13: Revoked device attempting to reconnect or sync
    @Test
    fun scenario13_revokedDeviceDeniedImmediatelyAndRequiresNewApprovalToRePair() {
        val nowMs = 1_700_000_000_000L
        completeValidPairing(nowMs)

        val joinerAuth = SyncAuthenticator(joinerIdentity, joinerTrustStore)
        val hostGateway = buildHostGateway()

        // Revoke joiner on Host
        assertTrue(hostTrustStore.revokePeer(joinerIdentity.deviceId, nowMs + 500))
        assertTrue(hostTrustStore.isRevoked(joinerIdentity.deviceId))

        // Revoked device attempts to pull call history using previously established session
        val env = joinerAuth.createAuthenticatedEnvelope(
            recipientDeviceId = hostIdentity.deviceId,
            operation = SyncOperation.SYNC_PULL_CALLS,
            plaintextPayloadUtf8 = """{"request":"PULL"}""",
            nowMs = nowMs + 600
        )
        val resp = hostGateway.handleIncomingSync(
            SyncTransportRequest(
                endpointPath = AuthenticatedSyncGateway.PATH_PULL_CALLS,
                sourceNetworkTag = "WIFI_LAN:192.168.1.10",
                envelope = env
            ),
            nowMs = nowMs + 650
        )
        assertEquals(403, resp.statusCode)
        assertNull(resp.responseEnvelope)
    }

    // Scenario 14: Untrusted device attempting every sync entry point (Wi-Fi, Tailscale, Cloud Relay)
    @Test
    fun scenario14_untrustedDeviceDeniedAcrossAllSyncEntryPoints() {
        val nowMs = 1_700_000_000_000L
        val hostGateway = buildHostGateway()

        // Attacker fabricates a local trust entry for Host to craft a self-signed envelope
        val fakeTrustStore = PersistentTrustStore()
        fakeTrustStore.approvePeer(
            TrustedPeerRecord(
                deviceId = hostIdentity.deviceId,
                deviceName = hostIdentity.deviceName,
                signingPublicKeyB64 = hostIdentity.signingPublicKeyB64,
                kemPublicKeyB64 = hostIdentity.kemPublicKeyB64,
                fingerprint = hostIdentity.fingerprint,
                pairedSessionId = "ps_fake",
                approvedAtMs = nowMs,
                status = TrustStatus.APPROVED,
                channelSecretB64 = Crypto.encodeB64(Crypto.randomBytes(32))
            )
        )
        val attackerAuth = SyncAuthenticator(attackerIdentity, fakeTrustStore)

        val endpoints = listOf(
            AuthenticatedSyncGateway.PATH_PUSH_CALLS to SyncOperation.SYNC_PUSH_CALLS,
            AuthenticatedSyncGateway.PATH_PULL_CALLS to SyncOperation.SYNC_PULL_CALLS,
            AuthenticatedSyncGateway.PATH_SNAPSHOT_EXCHANGE to SyncOperation.SYNC_SNAPSHOT_EXCHANGE,
            AuthenticatedSyncGateway.PATH_HEARTBEAT to SyncOperation.HEARTBEAT_RECONNECT
        )
        val networks = listOf("WIFI_LAN:192.168.1.99", "TAILSCALE:100.101.102.103", "CLOUD_RELAY:ntfy")

        for ((path, op) in endpoints) {
            for (net in networks) {
                val env = attackerAuth.createAuthenticatedEnvelope(
                    recipientDeviceId = hostIdentity.deviceId,
                    operation = op,
                    plaintextPayloadUtf8 = """{"malicious":"payload"}""",
                    nowMs = nowMs + 100
                )
                val res = hostGateway.handleIncomingSync(
                    SyncTransportRequest(
                        endpointPath = path,
                        sourceNetworkTag = net,
                        sharedAccountCodeHint = "1111",
                        envelope = env
                    ),
                    nowMs = nowMs + 150
                )
                assertEquals("Endpoint $path over $net must reject untrusted peer", 403, res.statusCode)
                assertNull(res.responseEnvelope)
            }
        }
        assertTrue(ingestedByHost.isEmpty())
    }

    // Scenario 15: Previously trusted device reconnecting after application / phone restart
    @Test
    fun scenario15_previouslyTrustedDeviceReconnectsAfterRestartWithoutRePairing() {
        val nowMs = 1_700_000_000_000L
        completeValidPairing(nowMs)

        // Simulate app restart by serializing identities and reloading TrustStores from disk
        val reloadedHostIdentity = DeviceIdentity.fromSerialized(hostIdentity.exportSerializableSecrets())
        val reloadedJoinerIdentity = DeviceIdentity.fromSerialized(joinerIdentity.exportSerializableSecrets())

        val reloadedHostStore = PersistentTrustStore(hostTrustFile, hostIntegrityKey)
        val reloadedJoinerStore = PersistentTrustStore(joinerTrustFile, joinerIntegrityKey)

        assertTrue(reloadedHostStore.isTrustedPeer(reloadedJoinerIdentity.deviceId, reloadedJoinerIdentity.signingPublicKeyB64))
        assertTrue(reloadedJoinerStore.isTrustedPeer(reloadedHostIdentity.deviceId, reloadedHostIdentity.signingPublicKeyB64))

        val joinerAuth = SyncAuthenticator(reloadedJoinerIdentity, reloadedJoinerStore)
        val hostAuth = SyncAuthenticator(reloadedHostIdentity, reloadedHostStore)
        val gateway = AuthenticatedSyncGateway(
            authenticator = hostAuth,
            callHistoryProvider = { hostCallHistory.joinToString(",", "[", "]") },
            callHistoryIngestor = { _, json -> ingestedByHost.add(json) }
        )

        val env = joinerAuth.createAuthenticatedEnvelope(
            recipientDeviceId = reloadedHostIdentity.deviceId,
            operation = SyncOperation.SYNC_PULL_CALLS,
            plaintextPayloadUtf8 = """{"reconnect":true}""",
            nowMs = nowMs + 50_000
        )
        val resp = gateway.handleIncomingSync(
            SyncTransportRequest(
                endpointPath = AuthenticatedSyncGateway.PATH_PULL_CALLS,
                sourceNetworkTag = "WIFI_LAN:192.168.1.50",
                envelope = env
            ),
            nowMs = nowMs + 50_100
        )
        assertEquals(200, resp.statusCode)
    }

    // Scenario 16: Network changes between Wi-Fi and Tailscale
    @Test
    fun scenario16_seamlessAuthenticatedSyncAcrossWifiAndTailscaleTransitions() {
        val nowMs = 1_700_000_000_000L
        completeValidPairing(nowMs)

        val joinerAuth = SyncAuthenticator(joinerIdentity, joinerTrustStore)
        val hostGateway = buildHostGateway()

        // First sync over local Wi-Fi
        val wifiEnv = joinerAuth.createAuthenticatedEnvelope(
            recipientDeviceId = hostIdentity.deviceId,
            operation = SyncOperation.SYNC_PUSH_CALLS,
            plaintextPayloadUtf8 = """{"callId":"wifi_call_1"}""",
            nowMs = nowMs + 1_000
        )
        val wifiResp = hostGateway.handleIncomingSync(
            SyncTransportRequest(
                endpointPath = AuthenticatedSyncGateway.PATH_PUSH_CALLS,
                sourceNetworkTag = "WIFI_LAN:192.168.1.12",
                envelope = wifiEnv
            ),
            nowMs = nowMs + 1_050
        )
        assertEquals(200, wifiResp.statusCode)

        // Device switches to Tailscale IP (100.90.1.5) and pushes next call record
        val tailscaleEnv = joinerAuth.createAuthenticatedEnvelope(
            recipientDeviceId = hostIdentity.deviceId,
            operation = SyncOperation.SYNC_PUSH_CALLS,
            plaintextPayloadUtf8 = """{"callId":"tailscale_call_2"}""",
            nowMs = nowMs + 2_000
        )
        val tsResp = hostGateway.handleIncomingSync(
            SyncTransportRequest(
                endpointPath = AuthenticatedSyncGateway.PATH_PUSH_CALLS,
                sourceNetworkTag = "TAILSCALE:100.90.1.5",
                envelope = tailscaleEnv
            ),
            nowMs = nowMs + 2_050
        )
        assertEquals(200, tsResp.statusCode)
        assertEquals(2, ingestedByHost.size)
    }

    // Scenario 17: Process termination during pairing or approval leaves no half-trusted state
    @Test
    fun scenario17_processTerminationBeforeApprovalCompletionLeavesZeroTrust() {
        val nowMs = 1_700_000_000_000L
        val joinerSession = JoinerPairingSession.initiate(joinerIdentity, nowMs)
        hostCoordinator.receivePairingRequest(joinerSession.requestMessage, nowMs + 50)
        hostCoordinator.verifyPairingCode(joinerSession.sessionId, joinerSession.pairingCode, nowMs + 100)

        // Simulate process termination before approvePendingSession is called
        val restartedHostStore = PersistentTrustStore(hostTrustFile, hostIntegrityKey)
        val restartedCoordinator = MainDevicePairingCoordinator(hostIdentity, restartedHostStore)

        assertFalse(restartedHostStore.isTrustedPeer(joinerIdentity.deviceId, joinerIdentity.signingPublicKeyB64))
        assertTrue(restartedCoordinator.getPendingSessions(nowMs + 200).isEmpty())
    }

    // Scenario 18: Corrupted or tampered trust-store data fails closed
    @Test
    fun scenario18_corruptedOrTamperedTrustStoreFailsClosed() {
        val nowMs = 1_700_000_000_000L
        completeValidPairing(nowMs)

        // Tamper with the trust store file on disk
        val rawText = hostTrustFile.readText(Charsets.UTF_8)
        hostTrustFile.writeText(rawText + "\nTAMPERED_LINE", Charsets.UTF_8)
        hostTrustStore.reloadFromDisk()

        assertTrue(hostTrustStore.isCorrupted())
        assertFalse(hostTrustStore.isTrustedPeer(joinerIdentity.deviceId, joinerIdentity.signingPublicKeyB64))

        val joinerAuth = SyncAuthenticator(joinerIdentity, joinerTrustStore)
        val hostGateway = buildHostGateway()
        val env = joinerAuth.createAuthenticatedEnvelope(
            recipientDeviceId = hostIdentity.deviceId,
            operation = SyncOperation.SYNC_PULL_CALLS,
            plaintextPayloadUtf8 = """{"request":"PULL"}""",
            nowMs = nowMs + 1_000
        )
        val resp = hostGateway.handleIncomingSync(
            SyncTransportRequest(
                endpointPath = AuthenticatedSyncGateway.PATH_PULL_CALLS,
                sourceNetworkTag = "WIFI_LAN:192.168.1.12",
                envelope = env
            ),
            nowMs = nowMs + 1_050
        )
        assertEquals(403, resp.statusCode)
    }

    // Scenario 19: Concurrent sync and revocation + sync replay rejection
    @Test
    fun scenario19_concurrentSyncAndRevocationAndReplayRejection() {
        val nowMs = 1_700_000_000_000L
        completeValidPairing(nowMs)

        val joinerAuth = SyncAuthenticator(joinerIdentity, joinerTrustStore)
        val hostGateway = buildHostGateway()

        val validEnv = joinerAuth.createAuthenticatedEnvelope(
            recipientDeviceId = hostIdentity.deviceId,
            operation = SyncOperation.SYNC_PUSH_CALLS,
            plaintextPayloadUtf8 = """{"callId":"call_once"}""",
            nowMs = nowMs + 1_000
        )
        val firstResp = hostGateway.handleIncomingSync(
            SyncTransportRequest(
                endpointPath = AuthenticatedSyncGateway.PATH_PUSH_CALLS,
                sourceNetworkTag = "WIFI_LAN:192.168.1.10",
                envelope = validEnv
            ),
            nowMs = nowMs + 1_050
        )
        assertEquals(200, firstResp.statusCode)

        // Replaying the exact same signed sync envelope must be rejected
        val replayResp = hostGateway.handleIncomingSync(
            SyncTransportRequest(
                endpointPath = AuthenticatedSyncGateway.PATH_PUSH_CALLS,
                sourceNetworkTag = "WIFI_LAN:192.168.1.10",
                envelope = validEnv
            ),
            nowMs = nowMs + 1_060
        )
        assertEquals(403, replayResp.statusCode)

        // Envelope created right before revocation, processed right after revocation
        val preRevokeEnv = joinerAuth.createAuthenticatedEnvelope(
            recipientDeviceId = hostIdentity.deviceId,
            operation = SyncOperation.SYNC_PUSH_CALLS,
            plaintextPayloadUtf8 = """{"callId":"call_after_revoke"}""",
            nowMs = nowMs + 2_000
        )
        hostTrustStore.revokePeer(joinerIdentity.deviceId, nowMs + 2_010)

        val postRevokeResp = hostGateway.handleIncomingSync(
            SyncTransportRequest(
                endpointPath = AuthenticatedSyncGateway.PATH_PUSH_CALLS,
                sourceNetworkTag = "WIFI_LAN:192.168.1.10",
                envelope = preRevokeEnv
            ),
            nowMs = nowMs + 2_020
        )
        assertEquals(403, postRevokeResp.statusCode)
        assertEquals(1, ingestedByHost.size)
    }

    // Scenario 20: Full bidirectional snapshot regression test for authorized devices
    @Test
    fun scenario20_regressionBidirectionalSnapshotSyncWorksForApprovedPeers() {
        val nowMs = 1_700_000_000_000L
        completeValidPairing(nowMs)

        val joinerAuth = SyncAuthenticator(joinerIdentity, joinerTrustStore)
        val hostGateway = buildHostGateway()

        val snapshotEnv = joinerAuth.createAuthenticatedEnvelope(
            recipientDeviceId = hostIdentity.deviceId,
            operation = SyncOperation.SYNC_SNAPSHOT_EXCHANGE,
            plaintextPayloadUtf8 = """[{"callId":"joiner_c1","remoteNumber":"+919900112233","durationSec":120}]""",
            nowMs = nowMs + 5_000
        )
        val resp = hostGateway.handleIncomingSync(
            SyncTransportRequest(
                endpointPath = AuthenticatedSyncGateway.PATH_SNAPSHOT_EXCHANGE,
                sourceNetworkTag = "TAILSCALE:100.64.0.2",
                envelope = snapshotEnv
            ),
            nowMs = nowMs + 5_100
        )
        assertEquals(200, resp.statusCode)
        assertEquals(1, ingestedByHost.size)
        assertTrue(ingestedByHost.first().contains("+919900112233"))

        val hostSnapshot = joinerAuth.verifyAndDecrypt(
            resp.responseEnvelope!!,
            expectedOperation = SyncOperation.SYNC_SNAPSHOT_EXCHANGE,
            nowMs = nowMs + 5_200
        )
        assertTrue(hostSnapshot.plaintextUtf8.contains("+919876543210"))
    }
}
