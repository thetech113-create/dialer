package com.caller.security

import java.nio.charset.StandardCharsets

enum class SyncOperation {
    SYNC_PUSH_CALLS,
    SYNC_PULL_CALLS,
    SYNC_SNAPSHOT_EXCHANGE,
    HEARTBEAT_RECONNECT
}

data class AuthenticatedSyncEnvelope(
    val protocolVersion: Int,
    val senderDeviceId: String,
    val recipientDeviceId: String,
    val operation: SyncOperation,
    val sessionId: String,
    val timestampMs: Long,
    val nonceB64: String,
    val ephemeralKemPubB64: String,
    val ivB64: String,
    val ciphertextAndTagB64: String,
    val senderSignatureB64: String
)

data class VerifiedSyncPayload(
    val senderDeviceId: String,
    val senderDeviceName: String,
    val operation: SyncOperation,
    val sessionId: String,
    val timestampMs: Long,
    val plaintextUtf8: String
)

/**
 * Enforces Section 6 (Authentication Before Every Sync):
 * 1. Authenticate peer cryptographic identity (ECDSA P-256 signature).
 * 2. Verify peer remains trusted and is not revoked in TrustStore.
 * 3. Establish/validate authenticated secure channel (ephemeral ECDH + pinned channel secret + AES-256-GCM).
 * 4. Validate authenticity, integrity, freshness (timestamp window), and session/nonce anti-replay.
 * 5. Authorize the requested SyncOperation.
 * 6. Only then decrypt and return the call-history payload.
 */
class SyncAuthenticator(
    private val localIdentity: DeviceIdentity,
    private val trustStore: TrustStore,
    private val allowedOperations: Set<SyncOperation> = SyncOperation.entries.toSet()
) {
    companion object {
        const val MAX_CLOCK_SKEW_MS: Long = 120_000L
        private const val MAX_REPLAY_CACHE_SIZE: Int = 4096
    }

    private val replayLock = Any()
    private val seenNonces = LinkedHashMap<String, Long>()

    fun createAuthenticatedEnvelope(
        recipientDeviceId: String,
        operation: SyncOperation,
        plaintextPayloadUtf8: String,
        nowMs: Long = System.currentTimeMillis()
    ): AuthenticatedSyncEnvelope {
        if (trustStore.isCorrupted()) {
            throw SecurityException("TrustStore is corrupted; refusing to send sync traffic")
        }
        if (trustStore.isRevoked(recipientDeviceId)) {
            throw SecurityException("Recipient device $recipientDeviceId is revoked")
        }
        val peer = trustStore.getApprovedPeer(recipientDeviceId)
            ?: throw SecurityException("Recipient device $recipientDeviceId is not trusted")

        val ephemeralKp = Crypto.generateEcKeyPair()
        val ephemeralPubB64 = Crypto.encodeB64(ephemeralKp.public.encoded)
        val nonceB64 = Crypto.randomNonceB64(24)

        val aadBytes = buildEnvelopeAad(
            protocolVersion = Crypto.PROTOCOL_VERSION,
            senderDeviceId = localIdentity.deviceId,
            recipientDeviceId = recipientDeviceId,
            operation = operation,
            sessionId = peer.pairedSessionId,
            timestampMs = nowMs,
            nonceB64 = nonceB64,
            ephemeralKemPubB64 = ephemeralPubB64
        )

        val ephemSecret = Crypto.computeEcdhSharedSecret(ephemeralKp.private, peer.kemPublicKeyB64)
        val pinnedSecret = Crypto.decodeB64(peer.channelSecretB64)
        val messageKey = derivePerMessageAesKey(
            ephemeralSecret = ephemSecret,
            pinnedChannelSecret = pinnedSecret,
            aadBytes = aadBytes
        )

        val encrypted = Crypto.encryptAesGcm(
            key32Bytes = messageKey,
            plaintext = plaintextPayloadUtf8.toByteArray(StandardCharsets.UTF_8),
            aad = aadBytes
        )

        val signingTranscript = buildSigningTranscript(
            aadBytes = aadBytes,
            ivB64 = encrypted.ivB64,
            ciphertextAndTagB64 = encrypted.ciphertextAndTagB64
        )
        val signatureB64 = localIdentity.sign(signingTranscript)

        return AuthenticatedSyncEnvelope(
            protocolVersion = Crypto.PROTOCOL_VERSION,
            senderDeviceId = localIdentity.deviceId,
            recipientDeviceId = recipientDeviceId,
            operation = operation,
            sessionId = peer.pairedSessionId,
            timestampMs = nowMs,
            nonceB64 = nonceB64,
            ephemeralKemPubB64 = ephemeralPubB64,
            ivB64 = encrypted.ivB64,
            ciphertextAndTagB64 = encrypted.ciphertextAndTagB64,
            senderSignatureB64 = signatureB64
        )
    }

    fun verifyAndDecrypt(
        envelope: AuthenticatedSyncEnvelope,
        expectedOperation: SyncOperation? = null,
        nowMs: Long = System.currentTimeMillis()
    ): VerifiedSyncPayload {
        // Check 0: Fail closed if TrustStore is corrupted or protocol downgraded
        if (trustStore.isCorrupted()) {
            throw SecurityException("TrustStore is corrupted; failing closed")
        }
        if (envelope.protocolVersion != Crypto.PROTOCOL_VERSION) {
            throw SecurityException("Unsupported or downgraded sync protocol version: ${envelope.protocolVersion}")
        }
        if (envelope.recipientDeviceId != localIdentity.deviceId) {
            throw SecurityException("Envelope recipient (${envelope.recipientDeviceId}) does not match local device (${localIdentity.deviceId})")
        }

        // Check 1 & 2: Verify peer remains trusted and has not been revoked
        if (trustStore.isRevoked(envelope.senderDeviceId)) {
            throw SecurityException("Sender device ${envelope.senderDeviceId} has been revoked")
        }
        val peer = trustStore.getApprovedPeer(envelope.senderDeviceId)
            ?: throw SecurityException("Sender device ${envelope.senderDeviceId} is untrusted")

        if (envelope.sessionId != peer.pairedSessionId) {
            throw SecurityException("Session binding mismatch for peer ${envelope.senderDeviceId}")
        }

        // Check 3: Authorize requested operation
        if (envelope.operation !in allowedOperations) {
            throw SecurityException("Operation ${envelope.operation} is not permitted on this endpoint")
        }
        if (expectedOperation != null && envelope.operation != expectedOperation) {
            throw SecurityException("Unexpected sync operation: expected $expectedOperation, got ${envelope.operation}")
        }

        // Check 4: Freshness validation
        val skew = kotlin.math.abs(nowMs - envelope.timestampMs)
        if (skew > MAX_CLOCK_SKEW_MS) {
            throw SecurityException("Sync message timestamp outside allowed freshness window")
        }

        // Check 5: Verify peer ECDSA signature BEFORE touching replay state or decrypting
        val aadBytes = buildEnvelopeAad(
            protocolVersion = envelope.protocolVersion,
            senderDeviceId = envelope.senderDeviceId,
            recipientDeviceId = envelope.recipientDeviceId,
            operation = envelope.operation,
            sessionId = envelope.sessionId,
            timestampMs = envelope.timestampMs,
            nonceB64 = envelope.nonceB64,
            ephemeralKemPubB64 = envelope.ephemeralKemPubB64
        )
        val signingTranscript = buildSigningTranscript(
            aadBytes = aadBytes,
            ivB64 = envelope.ivB64,
            ciphertextAndTagB64 = envelope.ciphertextAndTagB64
        )
        if (!Crypto.verifyEcdsa(peer.signingPublicKeyB64, signingTranscript, envelope.senderSignatureB64)) {
            throw SecurityException("ECDSA signature verification failed for sender ${envelope.senderDeviceId}")
        }

        // Check 6: Nonce anti-replay check (scoped to sender + session + nonce)
        val replayKey = "${envelope.senderDeviceId}|${envelope.sessionId}|${envelope.nonceB64}"
        synchronized(replayLock) {
            pruneExpiredNoncesLocked(nowMs)
            if (seenNonces.containsKey(replayKey)) {
                throw SecurityException("Replayed sync message detected (nonce already used)")
            }
            // Re-verify revocation inside lock to prevent concurrent sync + revocation race condition
            if (trustStore.isRevoked(envelope.senderDeviceId) || trustStore.getApprovedPeer(envelope.senderDeviceId) == null) {
                throw SecurityException("Peer ${envelope.senderDeviceId} was revoked concurrently")
            }
            seenNonces[replayKey] = envelope.timestampMs
        }

        // Check 7: Derive per-message AES-256-GCM key and authenticate/decrypt payload
        val ephemSecret = localIdentity.deriveSharedSecret(envelope.ephemeralKemPubB64)
        val pinnedSecret = Crypto.decodeB64(peer.channelSecretB64)
        val messageKey = derivePerMessageAesKey(
            ephemeralSecret = ephemSecret,
            pinnedChannelSecret = pinnedSecret,
            aadBytes = aadBytes
        )
        val plaintextBytes = try {
            Crypto.decryptAesGcm(
                key32Bytes = messageKey,
                ivB64 = envelope.ivB64,
                ciphertextAndTagB64 = envelope.ciphertextAndTagB64,
                aad = aadBytes
            )
        } catch (e: Throwable) {
            throw SecurityException("AES-GCM authenticated decryption failed", e)
        }

        return VerifiedSyncPayload(
            senderDeviceId = peer.deviceId,
            senderDeviceName = peer.deviceName,
            operation = envelope.operation,
            sessionId = peer.pairedSessionId,
            timestampMs = envelope.timestampMs,
            plaintextUtf8 = String(plaintextBytes, StandardCharsets.UTF_8)
        )
    }

    private fun pruneExpiredNoncesLocked(nowMs: Long) {
        val iter = seenNonces.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (nowMs - entry.value > MAX_CLOCK_SKEW_MS * 2) {
                iter.remove()
            }
        }
        while (seenNonces.size > MAX_REPLAY_CACHE_SIZE) {
            val firstKey = seenNonces.keys.firstOrNull() ?: break
            seenNonces.remove(firstKey)
        }
    }

    private fun buildEnvelopeAad(
        protocolVersion: Int,
        senderDeviceId: String,
        recipientDeviceId: String,
        operation: SyncOperation,
        sessionId: String,
        timestampMs: Long,
        nonceB64: String,
        ephemeralKemPubB64: String
    ): ByteArray = Crypto.canonicalTranscript(
        "SYNC_ENVELOPE_AAD_V1",
        protocolVersion.toString(),
        senderDeviceId,
        recipientDeviceId,
        operation.name,
        sessionId,
        timestampMs.toString(),
        nonceB64,
        ephemeralKemPubB64
    )

    private fun buildSigningTranscript(
        aadBytes: ByteArray,
        ivB64: String,
        ciphertextAndTagB64: String
    ): ByteArray = Crypto.canonicalTranscript(
        "SYNC_ENVELOPE_SIG_V1",
        Crypto.encodeB64(aadBytes),
        ivB64,
        Crypto.encodeB64(Crypto.sha256(Crypto.decodeB64(ciphertextAndTagB64)))
    )

    private fun derivePerMessageAesKey(
        ephemeralSecret: ByteArray,
        pinnedChannelSecret: ByteArray,
        aadBytes: ByteArray
    ): ByteArray = Crypto.hkdfSha256(
        ikm = ephemeralSecret + pinnedChannelSecret,
        salt = Crypto.sha256(aadBytes),
        info = "TRILLION_GUARD_PER_MESSAGE_AES256_GCM_V1".toByteArray(StandardCharsets.UTF_8),
        outputLengthBytes = 32
    )
}
