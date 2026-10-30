package com.caller.security

import java.nio.charset.StandardCharsets

enum class PendingSessionStage {
    AWAITING_CODE_VERIFICATION,
    CODE_VERIFIED_AWAITING_EXPLICIT_APPROVAL,
    APPROVED_FINALIZED,
    INVALIDATED
}

data class PairingRequestMessage(
    val protocolVersion: Int,
    val sessionId: String,
    val joinerDeviceId: String,
    val joinerDeviceName: String,
    val joinerSigningPubB64: String,
    val joinerKemPubB64: String,
    val joinerEphemeralKemPubB64: String,
    val requestNonceB64: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val codeCommitmentB64: String,
    val joinerSignatureB64: String
)

data class PendingPairingDisplayInfo(
    val sessionId: String,
    val joinerDeviceId: String,
    val joinerDeviceName: String,
    val joinerFingerprint: String,
    val safetyNumber: String,
    val stage: PendingSessionStage,
    val expiresAtMs: Long
)

data class ApprovalReceiptMessage(
    val protocolVersion: Int,
    val sessionId: String,
    val hostDeviceId: String,
    val hostDeviceName: String,
    val hostSigningPubB64: String,
    val hostKemPubB64: String,
    val hostEphemeralKemPubB64: String,
    val joinerDeviceId: String,
    val joinerSigningPubB64: String,
    val approvalNonceB64: String,
    val approvedAtMs: Long,
    val expiresAtMs: Long,
    val transcriptHashB64: String,
    val keyConfirmationMacB64: String,
    val hostSignatureB64: String
)

class PairingOffer(val code: String, val ownerId: String, val expiresAt: Long) {
    val displayCode: String get() = code
    val link: String get() = "dialerpair:$code:$ownerId"
}

data class PairingLink(val code: String, val ownerId: String) {
    companion object {
        fun parse(s: String): PairingLink? {
            val p = s.trim().split(':')
            if (p.size != 3 || p[0] != "dialerpair") return null
            return PairingLink(p[1], p[2])
        }
    }
}

data class PairRequest(
    val publicKey: String,
    val nameB64: String,
    val nonce: String,
    val timestamp: Long,
    val proof: String,
    val signature: String,
    val modelDeviceId: String = ""
) {
    fun toWire(): String = org.json.JSONObject().apply {
        put("pk", publicKey)
        put("name", nameB64)
        put("nonce", nonce)
        put("ts", timestamp)
        put("proof", proof)
        put("sig", signature)
        if (modelDeviceId.isNotBlank()) put("devId", modelDeviceId)
    }.toString()

    companion object {
        fun fromWire(s: String): PairRequest? = runCatching {
            val obj = org.json.JSONObject(s)
            PairRequest(
                obj.getString("pk"),
                obj.getString("name"),
                obj.getString("nonce"),
                obj.getLong("ts"),
                obj.getString("proof"),
                obj.getString("sig"),
                obj.optString("devId", "")
            )
        }.getOrNull()
    }
}

class PairStatus(val state: String, val ownerPublicKey: String?, val ownerNameB64: String?, val signature: String?) {
    fun toWire(): String = org.json.JSONObject().apply {
        put("state", state)
        ownerPublicKey?.let { put("owner", it) }
        ownerNameB64?.let { put("ownername", it) }
        signature?.let { put("sig", it) }
    }.toString()

    companion object {
        fun fromWire(s: String): PairStatus = runCatching {
            val obj = org.json.JSONObject(s)
            PairStatus(
                obj.optString("state", "UNKNOWN"),
                obj.optString("owner").takeIf { it.isNotBlank() },
                obj.optString("ownername").takeIf { it.isNotBlank() },
                obj.optString("sig").takeIf { it.isNotBlank() }
            )
        }.getOrDefault(PairStatus("UNKNOWN", null, null, null))
    }
}

class PairingClient(private val identity: DeviceIdentity, private val deviceName: String, private val modelDeviceId: String = "") {
    fun buildRequest(link: PairingLink): PairRequest {
        val nonce = Crypto.randomNonceB64(16)
        val ts = System.currentTimeMillis()
        val proof = Crypto.encodeB64(Crypto.hmacSha256(link.code.toByteArray(), Crypto.canonicalTranscript(link.ownerId, identity.deviceId, nonce, ts.toString())))
        val nameB64 = Crypto.encodeB64(deviceName.toByteArray())
        val sig = identity.sign(Crypto.canonicalTranscript(link.ownerId, identity.signingPublicKeyB64, nameB64, nonce, ts.toString(), proof))
        return PairRequest(identity.signingPublicKeyB64, nameB64, nonce, ts, proof, sig, modelDeviceId)
    }
}

class PairingHost(
    private val identity: DeviceIdentity,
    private val trustStore: TrustStore,
    private val hostName: String
) {
    sealed interface RequestResult {
        data class Accepted(val pairingId: String) : RequestResult
        data class Rejected(val reason: String) : RequestResult
    }

    data class PendingView(val id: String, val name: String, val deviceId: String, val modelDeviceId: String = "")

    private val coordinator = MainDevicePairingCoordinator(identity, trustStore)
    private var activeCode: String? = null

    @Synchronized
    fun createOffer(): PairingOffer = createOfferWithCode(Crypto.generateSixDigitPairingCode())

    @Synchronized
    fun createOfferWithCode(code: String): PairingOffer {
        activeCode = code
        return PairingOffer(code, identity.id, System.currentTimeMillis() + 120_000L)
    }

    @Synchronized
    fun handleRequest(req: PairRequest): RequestResult {
        val code = activeCode ?: return RequestResult.Rejected("no active offer")
        val devId = "dev_" + Crypto.publicKeyFingerprint(req.publicKey)

        // Do not recreate pending approval dialog if device is already approved or handled
        if (trustStore.getApprovedPeer(devId) != null) {
            return RequestResult.Accepted("already_approved_$devId")
        }
        if (handledDevIds.contains(devId)) {
            return RequestResult.Rejected("request already handled for device $devId")
        }

        val name = String(Crypto.decodeB64(req.nameB64), Charsets.UTF_8)
        val expectedProof = Crypto.encodeB64(Crypto.hmacSha256(code.toByteArray(), Crypto.canonicalTranscript(identity.id, devId, req.nonce, req.timestamp.toString())))
        if (!Crypto.constantTimeEqualsUtf8(expectedProof, req.proof)) {
            return RequestResult.Rejected("invalid pairing code")
        }
        val existing = pendingReqs.entries.firstOrNull { it.value.deviceId == devId }
        if (existing != null) {
            return RequestResult.Accepted(existing.key)
        }
        val id = Crypto.randomNonceB64(16)
        val modelDevId = req.modelDeviceId.ifBlank { devId }
        val rec = TrustedPeerRecord(
            deviceId = devId,
            deviceName = name,
            signingPublicKeyB64 = req.publicKey,
            kemPublicKeyB64 = req.publicKey,
            fingerprint = Crypto.publicKeyFingerprint(req.publicKey),
            pairedSessionId = id,
            approvedAtMs = System.currentTimeMillis(),
            status = TrustStatus.APPROVED,
            channelSecretB64 = Crypto.encodeB64(Crypto.sha256(req.publicKey.toByteArray())),
            modelDeviceId = modelDevId
        )
        pendingReqs[id] = rec
        return RequestResult.Accepted(id)
    }

    private val pendingReqs = LinkedHashMap<String, TrustedPeerRecord>()
    private val approvedReqs = HashSet<String>()
    private val deniedReqs = HashSet<String>()
    private val handledDevIds = HashSet<String>()

    @Synchronized
    fun pendingRequests(): List<PendingView> = pendingReqs.map { (id, rec) ->
        PendingView(id, rec.deviceName, rec.deviceId, rec.modelDeviceId)
    }

    @Synchronized
    fun getPendingRecord(pairingId: String): TrustedPeerRecord? = pendingReqs[pairingId]

    @Synchronized
    fun approve(pairingId: String): Boolean {
        val rec = pendingReqs[pairingId] ?: return false
        trustStore.approvePeer(rec)
        approvedReqs.add(pairingId)
        handledDevIds.add(rec.deviceId)
        pendingReqs.remove(pairingId)
        return true
    }

    @Synchronized
    fun deny(pairingId: String) {
        val rec = pendingReqs[pairingId]
        if (rec != null) {
            handledDevIds.add(rec.deviceId)
        }
        deniedReqs.add(pairingId)
        pendingReqs.remove(pairingId)
    }

    @Synchronized
    fun status(pairingId: String): PairStatus {
        if (approvedReqs.contains(pairingId)) {
            val nameB64 = Crypto.encodeB64(hostName.toByteArray())
            val sig = identity.sign(Crypto.canonicalTranscript(pairingId, "APPROVED", identity.publicKeyB64, nameB64))
            return PairStatus("APPROVED", identity.publicKeyB64, nameB64, sig)
        }
        if (deniedReqs.contains(pairingId)) {
            return PairStatus("DENIED", null, null, null)
        }
        return PairStatus("PENDING", null, null, null)
    }
}

/**
 * Step 1 & Step 5 handler on the New (Joining) Device.
 */
class JoinerPairingSession private constructor(
    val joinerIdentity: DeviceIdentity,
    val sessionId: String,
    val pairingCode: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    private val ephemeralKemKeyPair: java.security.KeyPair,
    val requestMessage: PairingRequestMessage
) {
    private var finalized = false

    companion object {
        const val PAIRING_TTL_MS: Long = 120_000L // 2 minutes strict TTL

        fun initiate(
            joinerIdentity: DeviceIdentity,
            nowMs: Long = System.currentTimeMillis()
        ): JoinerPairingSession {
            val sessionId = "ps_" + Crypto.randomNonceB64(18)
            val code = Crypto.generateSixDigitPairingCode()
            val ephemeralKp = Crypto.generateEcKeyPair()
            val ephemeralPubB64 = Crypto.encodeB64(ephemeralKp.public.encoded)
            val requestNonceB64 = Crypto.randomNonceB64(18)
            val expiresAtMs = nowMs + PAIRING_TTL_MS

            val coreTranscript = PairingTranscripts.buildRequestCoreTranscript(
                protocolVersion = Crypto.PROTOCOL_VERSION,
                sessionId = sessionId,
                joinerDeviceId = joinerIdentity.deviceId,
                joinerDeviceName = joinerIdentity.deviceName,
                joinerSigningPubB64 = joinerIdentity.signingPublicKeyB64,
                joinerKemPubB64 = joinerIdentity.kemPublicKeyB64,
                joinerEphemeralKemPubB64 = ephemeralPubB64,
                requestNonceB64 = requestNonceB64,
                createdAtMs = createdAtMs(nowMs),
                expiresAtMs = expiresAtMs
            )
            val codeCommitmentB64 = PairingTranscripts.computeCodeCommitment(
                pairingCode = code,
                sessionId = sessionId,
                coreTranscript = coreTranscript
            )
            val signedTranscript = PairingTranscripts.buildRequestSignedTranscript(
                coreTranscript = coreTranscript,
                codeCommitmentB64 = codeCommitmentB64
            )
            val sigB64 = joinerIdentity.sign(signedTranscript)

            val msg = PairingRequestMessage(
                protocolVersion = Crypto.PROTOCOL_VERSION,
                sessionId = sessionId,
                joinerDeviceId = joinerIdentity.deviceId,
                joinerDeviceName = joinerIdentity.deviceName,
                joinerSigningPubB64 = joinerIdentity.signingPublicKeyB64,
                joinerKemPubB64 = joinerIdentity.kemPublicKeyB64,
                joinerEphemeralKemPubB64 = ephemeralPubB64,
                requestNonceB64 = requestNonceB64,
                createdAtMs = nowMs,
                expiresAtMs = expiresAtMs,
                codeCommitmentB64 = codeCommitmentB64,
                joinerSignatureB64 = sigB64
            )
            return JoinerPairingSession(
                joinerIdentity = joinerIdentity,
                sessionId = sessionId,
                pairingCode = code,
                createdAtMs = nowMs,
                expiresAtMs = expiresAtMs,
                ephemeralKemKeyPair = ephemeralKp,
                requestMessage = msg
            )
        }

        private fun createdAtMs(nowMs: Long): Long = nowMs
    }

    /**
     * Step 5 on Joiner: Verifies Host's ApprovalReceipt, checks MITM/transcript binding,
     * and persists Host in Joiner's TrustStore.
     */
    @Synchronized
    fun verifyApprovalAndEstablishTrust(
        receipt: ApprovalReceiptMessage,
        joinerTrustStore: TrustStore,
        nowMs: Long = System.currentTimeMillis()
    ): TrustedPeerRecord {
        if (finalized) {
            throw SecurityException("Joiner pairing session already finalized or consumed")
        }
        if (nowMs > expiresAtMs || nowMs > receipt.expiresAtMs || receipt.approvedAtMs > nowMs + 30_000L) {
            finalized = true
            throw SecurityException("Approval receipt or pairing session has expired")
        }
        if (receipt.protocolVersion != Crypto.PROTOCOL_VERSION) {
            finalized = true
            throw SecurityException("Unsupported protocol version in approval receipt")
        }
        if (receipt.sessionId != sessionId) {
            throw SecurityException("Session ID mismatch in approval receipt")
        }
        if (receipt.joinerDeviceId != joinerIdentity.deviceId ||
            !Crypto.constantTimeEqualsUtf8(receipt.joinerSigningPubB64, joinerIdentity.signingPublicKeyB64)
        ) {
            throw SecurityException("Approval receipt is not bound to this device's identity")
        }
        val expectedHostId = "dev_" + Crypto.publicKeyFingerprint(receipt.hostSigningPubB64)
        if (receipt.hostDeviceId != expectedHostId) {
            throw SecurityException("Host deviceId does not match host signing public key")
        }
        if (joinerTrustStore.isRevoked(receipt.hostDeviceId, receipt.hostSigningPubB64)) {
            throw SecurityException("Host identity is revoked in local TrustStore")
        }

        val expectedTranscriptHashB64 = PairingTranscripts.computeFullCeremonyTranscriptHash(
            request = requestMessage,
            pairingCode = pairingCode,
            hostDeviceId = receipt.hostDeviceId,
            hostDeviceName = receipt.hostDeviceName,
            hostSigningPubB64 = receipt.hostSigningPubB64,
            hostKemPubB64 = receipt.hostKemPubB64,
            hostEphemeralKemPubB64 = receipt.hostEphemeralKemPubB64,
            approvalNonceB64 = receipt.approvalNonceB64,
            approvedAtMs = receipt.approvedAtMs,
            receiptExpiresAtMs = receipt.expiresAtMs
        )
        if (!Crypto.constantTimeEqualsUtf8(expectedTranscriptHashB64, receipt.transcriptHashB64)) {
            finalized = true
            throw SecurityException("Ceremony transcript hash mismatch (potential MITM or modified request)")
        }

        val signedApprovalBytes = PairingTranscripts.buildApprovalSignedTranscript(
            protocolVersion = receipt.protocolVersion,
            sessionId = receipt.sessionId,
            hostDeviceId = receipt.hostDeviceId,
            joinerDeviceId = receipt.joinerDeviceId,
            transcriptHashB64 = receipt.transcriptHashB64,
            keyConfirmationMacB64 = receipt.keyConfirmationMacB64
        )
        if (!Crypto.verifyEcdsa(receipt.hostSigningPubB64, signedApprovalBytes, receipt.hostSignatureB64)) {
            finalized = true
            throw SecurityException("Invalid host ECDSA signature on approval receipt")
        }

        // Compute mutually authenticated channel secret via ephemeral ECDH + static ECDH + HKDF-SHA256
        val ephemSecret = Crypto.computeEcdhSharedSecret(
            ephemeralKemKeyPair.private,
            receipt.hostEphemeralKemPubB64
        )
        val staticSecret = joinerIdentity.deriveSharedSecret(receipt.hostKemPubB64)
        val channelSecret = PairingTranscripts.deriveMutualChannelSecret(
            ephemeralSharedSecret = ephemSecret,
            staticSharedSecret = staticSecret,
            transcriptHashB64 = expectedTranscriptHashB64
        )

        val expectedKeyConfirmMacB64 = Crypto.encodeB64(
            Crypto.hmacSha256(
                channelSecret,
                "HOST_KEY_CONFIRM|$sessionId|${receipt.hostDeviceId}|${joinerIdentity.deviceId}"
                    .toByteArray(StandardCharsets.UTF_8)
            )
        )
        if (!Crypto.constantTimeEqualsUtf8(expectedKeyConfirmMacB64, receipt.keyConfirmationMacB64)) {
            finalized = true
            throw SecurityException("Key confirmation MAC verification failed")
        }

        finalized = true
        val record = TrustedPeerRecord(
            deviceId = receipt.hostDeviceId,
            deviceName = receipt.hostDeviceName,
            signingPublicKeyB64 = receipt.hostSigningPubB64,
            kemPublicKeyB64 = receipt.hostKemPubB64,
            fingerprint = Crypto.publicKeyFingerprint(receipt.hostSigningPubB64),
            pairedSessionId = sessionId,
            approvedAtMs = nowMs,
            status = TrustStatus.APPROVED,
            channelSecretB64 = Crypto.encodeB64(channelSecret)
        )
        joinerTrustStore.approvePeer(record)
        return record
    }
}

/**
 * Step 2, Step 3, and Step 4 coordinator on the Main (Already Trusted) Device.
 */
class MainDevicePairingCoordinator(
    private val hostIdentity: DeviceIdentity,
    private val trustStore: TrustStore
) {
    companion object {
        const val MAX_CODE_ATTEMPTS_PER_SESSION = 3
        const val MAX_GLOBAL_CODE_ATTEMPTS_PER_WINDOW = 5
        const val RATE_LIMIT_WINDOW_MS = 60_000L
        const val MAX_PENDING_SESSIONS = 8
    }

    private data class InternalPendingSession(
        val request: PairingRequestMessage,
        val coreTranscript: ByteArray,
        var stage: PendingSessionStage,
        var failedCodeAttempts: Int = 0,
        var verifiedCodeValue: String? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as InternalPendingSession
            if (request != other.request) return false
            if (!coreTranscript.contentEquals(other.coreTranscript)) return false
            if (stage != other.stage) return false
            if (failedCodeAttempts != other.failedCodeAttempts) return false
            if (verifiedCodeValue != other.verifiedCodeValue) return false
            return true
        }

        override fun hashCode(): Int {
            var result = request.hashCode()
            result = 31 * result + coreTranscript.contentHashCode()
            result = 31 * result + stage.hashCode()
            result = 31 * result + failedCodeAttempts
            result = 31 * result + verifiedCodeValue.hashCode()
            return result
        }
    }

    private val lock = Any()
    private val pendingBySessionId = LinkedHashMap<String, InternalPendingSession>()
    private val consumedSessionIds = LinkedHashSet<String>()
    private val globalAttemptTimestamps = ArrayDeque<Long>()

    /**
     * Step 2: Receive and cryptographically validate a pending request from a joining device.
     * Does NOT trust the device or verify the code yet.
     */
    fun receivePairingRequest(
        request: PairingRequestMessage,
        nowMs: Long = System.currentTimeMillis()
    ): PendingPairingDisplayInfo = synchronized(lock) {
        pruneExpiredLocked(nowMs)
        if (trustStore.isCorrupted()) {
            throw SecurityException("TrustStore is corrupted; refusing pairing requests")
        }
        if (request.protocolVersion != Crypto.PROTOCOL_VERSION) {
            throw SecurityException("Unsupported protocol version: ${request.protocolVersion}")
        }
        if (nowMs > request.expiresAtMs || request.createdAtMs > nowMs + 30_000L ||
            request.expiresAtMs - request.createdAtMs > JoinerPairingSession.PAIRING_TTL_MS + 5_000L
        ) {
            throw SecurityException("Pairing request is expired or has invalid timestamps")
        }
        if (consumedSessionIds.contains(request.sessionId) || pendingBySessionId.containsKey(request.sessionId)) {
            throw SecurityException("Duplicate or replayed pairing sessionId: ${request.sessionId}")
        }

        // Verify joinerDeviceId is strictly derived from joinerSigningPubB64
        val computedFp = Crypto.publicKeyFingerprint(request.joinerSigningPubB64)
        val expectedDeviceId = "dev_$computedFp"
        if (request.joinerDeviceId != expectedDeviceId) {
            throw SecurityException("Forged joinerDeviceId: does not match signing public key fingerprint")
        }

        // Reject ambiguous concurrent pending requests for the same deviceId or same deviceName
        val existingPendingForSameDevice = pendingBySessionId.values.any {
            it.stage != PendingSessionStage.INVALIDATED &&
                (it.request.joinerDeviceId == request.joinerDeviceId)
        }
        if (existingPendingForSameDevice) {
            throw SecurityException("Ambiguous duplicate pending request for device ${request.joinerDeviceId}")
        }
        if (pendingBySessionId.size >= MAX_PENDING_SESSIONS) {
            throw SecurityException("Too many concurrent pending pairing sessions")
        }

        // Validate EC public keys parse cleanly
        Crypto.parsePublicKeySpkiB64(request.joinerSigningPubB64)
        Crypto.parsePublicKeySpkiB64(request.joinerKemPubB64)
        Crypto.parsePublicKeySpkiB64(request.joinerEphemeralKemPubB64)

        val coreTranscript = PairingTranscripts.buildRequestCoreTranscript(
            protocolVersion = request.protocolVersion,
            sessionId = request.sessionId,
            joinerDeviceId = request.joinerDeviceId,
            joinerDeviceName = request.joinerDeviceName,
            joinerSigningPubB64 = request.joinerSigningPubB64,
            joinerKemPubB64 = request.joinerKemPubB64,
            joinerEphemeralKemPubB64 = request.joinerEphemeralKemPubB64,
            requestNonceB64 = request.requestNonceB64,
            createdAtMs = request.createdAtMs,
            expiresAtMs = request.expiresAtMs
        )
        val signedTranscript = PairingTranscripts.buildRequestSignedTranscript(
            coreTranscript = coreTranscript,
            codeCommitmentB64 = request.codeCommitmentB64
        )
        if (!Crypto.verifyEcdsa(request.joinerSigningPubB64, signedTranscript, request.joinerSignatureB64)) {
            throw SecurityException("Invalid ECDSA signature on pairing request")
        }

        val session = InternalPendingSession(
            request = request,
            coreTranscript = coreTranscript,
            stage = PendingSessionStage.AWAITING_CODE_VERIFICATION
        )
        pendingBySessionId[request.sessionId] = session

        PendingPairingDisplayInfo(
            sessionId = request.sessionId,
            joinerDeviceId = request.joinerDeviceId,
            joinerDeviceName = request.joinerDeviceName,
            joinerFingerprint = computedFp,
            safetyNumber = Crypto.shortSafetyNumber(request.joinerSigningPubB64),
            stage = session.stage,
            expiresAtMs = request.expiresAtMs
        )
    }

    /**
     * Step 3: Verify the short-lived pairing code bound to the exact pending session.
     * Matching the code NEVER establishes trust by itself—it only unlocks Step 4 (explicit approval).
     */
    fun verifyPairingCode(
        sessionId: String,
        enteredCode: String,
        nowMs: Long = System.currentTimeMillis()
    ): PendingPairingDisplayInfo = synchronized(lock) {
        pruneExpiredLocked(nowMs)
        enforceGlobalRateLimitLocked(nowMs)

        val session = pendingBySessionId[sessionId]
            ?: throw SecurityException("Unknown or expired pairing session: $sessionId")

        if (session.stage != PendingSessionStage.AWAITING_CODE_VERIFICATION) {
            session.stage = PendingSessionStage.INVALIDATED
            pendingBySessionId.remove(sessionId)
            consumedSessionIds.add(sessionId)
            throw SecurityException("Session $sessionId is not in AWAITING_CODE_VERIFICATION state")
        }

        val sanitizedCode = enteredCode.trim()
        val expectedCommitmentB64 = PairingTranscripts.computeCodeCommitment(
            pairingCode = sanitizedCode,
            sessionId = session.request.sessionId,
            coreTranscript = session.coreTranscript
        )
        val actualCommitmentBytes = Crypto.decodeB64(session.request.codeCommitmentB64)
        val expectedCommitmentBytes = Crypto.decodeB64(expectedCommitmentB64)

        if (!Crypto.constantTimeEquals(actualCommitmentBytes, expectedCommitmentBytes)) {
            session.failedCodeAttempts++
            if (session.failedCodeAttempts >= MAX_CODE_ATTEMPTS_PER_SESSION) {
                session.stage = PendingSessionStage.INVALIDATED
                pendingBySessionId.remove(sessionId)
                consumedSessionIds.add(sessionId)
                throw SecurityException("Maximum code attempts exceeded; pairing session invalidated")
            }
            throw SecurityException("Incorrect pairing code for session $sessionId")
        }

        session.stage = PendingSessionStage.CODE_VERIFIED_AWAITING_EXPLICIT_APPROVAL
        session.verifiedCodeValue = sanitizedCode

        val fp = Crypto.publicKeyFingerprint(session.request.joinerSigningPubB64)
        PendingPairingDisplayInfo(
            sessionId = session.request.sessionId,
            joinerDeviceId = session.request.joinerDeviceId,
            joinerDeviceName = session.request.joinerDeviceName,
            joinerFingerprint = fp,
            safetyNumber = Crypto.shortSafetyNumber(session.request.joinerSigningPubB64),
            stage = session.stage,
            expiresAtMs = session.request.expiresAtMs
        )
    }

    /**
     * Step 4: Explicit, deliberate approval on the Main Device after code verification.
     * Cryptographically binds the approval to the exact joiner identity, session transcript, and ECDH key exchange.
     */
    fun approvePendingSession(
        sessionId: String,
        expectedJoinerDeviceId: String,
        expectedJoinerFingerprint: String,
        explicitUserConfirmation: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): ApprovalReceiptMessage = synchronized(lock) {
        pruneExpiredLocked(nowMs)
        if (!explicitUserConfirmation) {
            throw SecurityException("Explicit user confirmation is required to approve a device")
        }
        if (trustStore.isCorrupted()) {
            throw SecurityException("TrustStore is corrupted; cannot approve device")
        }

        val session = pendingBySessionId[sessionId]
            ?: throw SecurityException("Pending session $sessionId not found or expired")

        if (session.stage != PendingSessionStage.CODE_VERIFIED_AWAITING_EXPLICIT_APPROVAL) {
            throw SecurityException("Cannot approve session $sessionId before pairing code is verified")
        }
        val verifiedCode = session.verifiedCodeValue
            ?: throw SecurityException("Missing verified code state for session $sessionId")

        val actualFp = Crypto.publicKeyFingerprint(session.request.joinerSigningPubB64)
        if (session.request.joinerDeviceId != expectedJoinerDeviceId ||
            !Crypto.constantTimeEqualsUtf8(actualFp, expectedJoinerFingerprint)
        ) {
            session.stage = PendingSessionStage.INVALIDATED
            pendingBySessionId.remove(sessionId)
            consumedSessionIds.add(sessionId)
            throw SecurityException("Request substitution detected: joiner identity does not match expected target")
        }

        // Single-use session consumption
        session.stage = PendingSessionStage.APPROVED_FINALIZED
        pendingBySessionId.remove(sessionId)
        consumedSessionIds.add(sessionId)

        val hostEphemeralKp = Crypto.generateEcKeyPair()
        val hostEphemeralPubB64 = Crypto.encodeB64(hostEphemeralKp.public.encoded)
        val approvalNonceB64 = Crypto.randomNonceB64(18)
        val receiptExpiresAtMs = minOf(session.request.expiresAtMs, nowMs + 60_000L)

        val transcriptHashB64 = PairingTranscripts.computeFullCeremonyTranscriptHash(
            request = session.request,
            pairingCode = verifiedCode,
            hostDeviceId = hostIdentity.deviceId,
            hostDeviceName = hostIdentity.deviceName,
            hostSigningPubB64 = hostIdentity.signingPublicKeyB64,
            hostKemPubB64 = hostIdentity.kemPublicKeyB64,
            hostEphemeralKemPubB64 = hostEphemeralPubB64,
            approvalNonceB64 = approvalNonceB64,
            approvedAtMs = nowMs,
            receiptExpiresAtMs = receiptExpiresAtMs
        )

        val ephemSecret = Crypto.computeEcdhSharedSecret(
            hostEphemeralKp.private,
            session.request.joinerEphemeralKemPubB64
        )
        val staticSecret = hostIdentity.deriveSharedSecret(session.request.joinerKemPubB64)
        val channelSecret = PairingTranscripts.deriveMutualChannelSecret(
            ephemeralSharedSecret = ephemSecret,
            staticSharedSecret = staticSecret,
            transcriptHashB64 = transcriptHashB64
        )

        val keyConfirmMacB64 = Crypto.encodeB64(
            Crypto.hmacSha256(
                channelSecret,
                "HOST_KEY_CONFIRM|$sessionId|${hostIdentity.deviceId}|${session.request.joinerDeviceId}"
                    .toByteArray(StandardCharsets.UTF_8)
            )
        )

        val signedApprovalBytes = PairingTranscripts.buildApprovalSignedTranscript(
            protocolVersion = Crypto.PROTOCOL_VERSION,
            sessionId = sessionId,
            hostDeviceId = hostIdentity.deviceId,
            joinerDeviceId = session.request.joinerDeviceId,
            transcriptHashB64 = transcriptHashB64,
            keyConfirmationMacB64 = keyConfirmMacB64
        )
        val hostSigB64 = hostIdentity.sign(signedApprovalBytes)

        val peerRecord = TrustedPeerRecord(
            deviceId = session.request.joinerDeviceId,
            deviceName = session.request.joinerDeviceName,
            signingPublicKeyB64 = session.request.joinerSigningPubB64,
            kemPublicKeyB64 = session.request.joinerKemPubB64,
            fingerprint = actualFp,
            pairedSessionId = sessionId,
            approvedAtMs = nowMs,
            status = TrustStatus.APPROVED,
            channelSecretB64 = Crypto.encodeB64(channelSecret)
        )
        trustStore.approvePeer(peerRecord)

        ApprovalReceiptMessage(
            protocolVersion = Crypto.PROTOCOL_VERSION,
            sessionId = sessionId,
            hostDeviceId = hostIdentity.deviceId,
            hostDeviceName = hostIdentity.deviceName,
            hostSigningPubB64 = hostIdentity.signingPublicKeyB64,
            hostKemPubB64 = hostIdentity.kemPublicKeyB64,
            hostEphemeralKemPubB64 = hostEphemeralPubB64,
            joinerDeviceId = session.request.joinerDeviceId,
            joinerSigningPubB64 = session.request.joinerSigningPubB64,
            approvalNonceB64 = approvalNonceB64,
            approvedAtMs = nowMs,
            expiresAtMs = receiptExpiresAtMs,
            transcriptHashB64 = transcriptHashB64,
            keyConfirmationMacB64 = keyConfirmMacB64,
            hostSignatureB64 = hostSigB64
        )
    }

    fun rejectPendingSession(sessionId: String) = synchronized(lock) {
        pendingBySessionId.remove(sessionId)?.let {
            it.stage = PendingSessionStage.INVALIDATED
            consumedSessionIds.add(sessionId)
        }
    }

    fun getPendingSessions(nowMs: Long = System.currentTimeMillis()): List<PendingPairingDisplayInfo> =
        synchronized(lock) {
            pruneExpiredLocked(nowMs)
            pendingBySessionId.values.map { s ->
                val fp = Crypto.publicKeyFingerprint(s.request.joinerSigningPubB64)
                PendingPairingDisplayInfo(
                    sessionId = s.request.sessionId,
                    joinerDeviceId = s.request.joinerDeviceId,
                    joinerDeviceName = s.request.joinerDeviceName,
                    joinerFingerprint = fp,
                    safetyNumber = Crypto.shortSafetyNumber(s.request.joinerSigningPubB64),
                    stage = s.stage,
                    expiresAtMs = s.request.expiresAtMs
                )
            }
        }

    private fun pruneExpiredLocked(nowMs: Long) {
        val iter = pendingBySessionId.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (nowMs > entry.value.request.expiresAtMs || entry.value.stage == PendingSessionStage.INVALIDATED) {
                consumedSessionIds.add(entry.key)
                iter.remove()
            }
        }
    }

    private fun enforceGlobalRateLimitLocked(nowMs: Long) {
        while (globalAttemptTimestamps.isNotEmpty() && nowMs - globalAttemptTimestamps.first() > RATE_LIMIT_WINDOW_MS) {
            globalAttemptTimestamps.removeFirst()
        }
        if (globalAttemptTimestamps.size >= MAX_GLOBAL_CODE_ATTEMPTS_PER_WINDOW) {
            throw SecurityException("Rate limit exceeded for pairing code verification; try again later")
        }
        globalAttemptTimestamps.addLast(nowMs)
    }
}

internal object PairingTranscripts {
    fun buildRequestCoreTranscript(
        protocolVersion: Int,
        sessionId: String,
        joinerDeviceId: String,
        joinerDeviceName: String,
        joinerSigningPubB64: String,
        joinerKemPubB64: String,
        joinerEphemeralKemPubB64: String,
        requestNonceB64: String,
        createdAtMs: Long,
        expiresAtMs: Long
    ): ByteArray = Crypto.canonicalTranscript(
        "PAIRING_REQUEST_V1",
        protocolVersion.toString(),
        sessionId,
        joinerDeviceId,
        joinerDeviceName,
        joinerSigningPubB64,
        joinerKemPubB64,
        joinerEphemeralKemPubB64,
        requestNonceB64,
        createdAtMs.toString(),
        expiresAtMs.toString()
    )

    fun computeCodeCommitment(
        pairingCode: String,
        sessionId: String,
        coreTranscript: ByteArray
    ): String {
        val codeKey = Crypto.hkdfSha256(
            ikm = pairingCode.toByteArray(StandardCharsets.UTF_8),
            salt = sessionId.toByteArray(StandardCharsets.UTF_8),
            info = "TRILLION_GUARD_CODE_COMMIT_V1".toByteArray(StandardCharsets.UTF_8),
            outputLengthBytes = 32
        )
        return Crypto.encodeB64(Crypto.hmacSha256(codeKey, coreTranscript))
    }

    fun buildRequestSignedTranscript(
        coreTranscript: ByteArray,
        codeCommitmentB64: String
    ): ByteArray = Crypto.canonicalTranscript(
        Crypto.encodeB64(coreTranscript),
        codeCommitmentB64
    )

    fun computeFullCeremonyTranscriptHash(
        request: PairingRequestMessage,
        pairingCode: String,
        hostDeviceId: String,
        hostDeviceName: String,
        hostSigningPubB64: String,
        hostKemPubB64: String,
        hostEphemeralKemPubB64: String,
        approvalNonceB64: String,
        approvedAtMs: Long,
        receiptExpiresAtMs: Long
    ): String {
        val codeDigestB64 = Crypto.encodeB64(
            Crypto.hmacSha256(
                request.sessionId.toByteArray(StandardCharsets.UTF_8),
                pairingCode.toByteArray(StandardCharsets.UTF_8)
            )
        )
        val fullBytes = Crypto.canonicalTranscript(
            "PAIRING_FULL_CEREMONY_V1",
            request.protocolVersion.toString(),
            request.sessionId,
            request.joinerDeviceId,
            request.joinerDeviceName,
            request.joinerSigningPubB64,
            request.joinerKemPubB64,
            request.joinerEphemeralKemPubB64,
            request.requestNonceB64,
            request.createdAtMs.toString(),
            request.expiresAtMs.toString(),
            request.codeCommitmentB64,
            codeDigestB64,
            hostDeviceId,
            hostDeviceName,
            hostSigningPubB64,
            hostKemPubB64,
            hostEphemeralKemPubB64,
            approvalNonceB64,
            approvedAtMs.toString(),
            receiptExpiresAtMs.toString()
        )
        return Crypto.encodeB64(Crypto.sha256(fullBytes))
    }

    fun buildApprovalSignedTranscript(
        protocolVersion: Int,
        sessionId: String,
        hostDeviceId: String,
        joinerDeviceId: String,
        transcriptHashB64: String,
        keyConfirmationMacB64: String
    ): ByteArray = Crypto.canonicalTranscript(
        "PAIRING_APPROVAL_V1",
        protocolVersion.toString(),
        sessionId,
        hostDeviceId,
        joinerDeviceId,
        transcriptHashB64,
        keyConfirmationMacB64
    )

    fun deriveMutualChannelSecret(
        ephemeralSharedSecret: ByteArray,
        staticSharedSecret: ByteArray,
        transcriptHashB64: String
    ): ByteArray {
        val combinedIkm = ephemeralSharedSecret + staticSharedSecret
        return Crypto.hkdfSha256(
            ikm = combinedIkm,
            salt = Crypto.decodeB64(transcriptHashB64),
            info = "TRILLION_GUARD_MUTUAL_SYNC_CHANNEL_V1".toByteArray(StandardCharsets.UTF_8),
            outputLengthBytes = 32
        )
    }
}
