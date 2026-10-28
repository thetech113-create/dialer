package com.caller.security

import java.io.File
import java.nio.charset.StandardCharsets

enum class TrustStatus {
    APPROVED,
    REVOKED
}

data class TrustedPeerRecord(
    val deviceId: String,
    val deviceName: String,
    val signingPublicKeyB64: String,
    val kemPublicKeyB64: String,
    val fingerprint: String,
    val pairedSessionId: String,
    val approvedAtMs: Long,
    val status: TrustStatus,
    val revokedAtMs: Long? = null,
    val channelSecretB64: String
)

interface TrustStore {
    fun approvePeer(record: TrustedPeerRecord)
    fun revokePeer(deviceId: String, nowMs: Long = System.currentTimeMillis()): Boolean
    fun getApprovedPeer(deviceId: String): TrustedPeerRecord?
    fun getAllApprovedPeers(): List<TrustedPeerRecord>
    fun isTrustedPeer(deviceId: String, signingPublicKeyB64: String): Boolean
    fun isRevoked(deviceId: String, signingPublicKeyB64: String? = null): Boolean
    fun isCorrupted(): Boolean
    fun clearAll()
}

data class TrustedDevice(
    val id: String,
    val name: String,
    val publicKey: String,
    val status: TrustStatus = TrustStatus.APPROVED,
    val addedAt: Long = System.currentTimeMillis()
)

class FileTrustStore(val file: File) : TrustStore {
    private val delegate = PersistentTrustStore(file)

    fun get(id: String): TrustedDevice? {
        val peer = delegate.getApprovedPeer(id) ?: return null
        return TrustedDevice(peer.deviceId, peer.deviceName, peer.signingPublicKeyB64, peer.status, peer.approvedAtMs)
    }

    fun put(device: TrustedDevice) {
        val rec = TrustedPeerRecord(
            deviceId = device.id,
            deviceName = device.name,
            signingPublicKeyB64 = device.publicKey,
            kemPublicKeyB64 = device.publicKey,
            fingerprint = Crypto.publicKeyFingerprint(device.publicKey),
            pairedSessionId = "ps_legacy_${device.id}",
            approvedAtMs = device.addedAt,
            status = device.status,
            channelSecretB64 = Crypto.encodeB64(Crypto.sha256(device.publicKey.toByteArray()))
        )
        delegate.approvePeer(rec)
    }

    fun revoke(id: String): Boolean = delegate.revokePeer(id)

    fun all(): List<TrustedDevice> = delegate.getAllApprovedPeers().map {
        TrustedDevice(it.deviceId, it.deviceName, it.signingPublicKeyB64, it.status, it.approvedAtMs)
    }

    override fun approvePeer(record: TrustedPeerRecord) = delegate.approvePeer(record)
    override fun revokePeer(deviceId: String, nowMs: Long): Boolean = delegate.revokePeer(deviceId, nowMs)
    override fun getApprovedPeer(deviceId: String): TrustedPeerRecord? = delegate.getApprovedPeer(deviceId)
    override fun getAllApprovedPeers(): List<TrustedPeerRecord> = delegate.getAllApprovedPeers()
    override fun isTrustedPeer(deviceId: String, signingPublicKeyB64: String): Boolean = delegate.isTrustedPeer(deviceId, signingPublicKeyB64)
    override fun isRevoked(deviceId: String, signingPublicKeyB64: String?): Boolean = delegate.isRevoked(deviceId, signingPublicKeyB64)
    override fun isCorrupted(): Boolean = delegate.isCorrupted()
    override fun clearAll(): Unit = delegate.clearAll()
}

/**
 * Thread-safe, tamper-evident persistent TrustStore backed by file storage with an HMAC-SHA256 seal.
 * Fails closed whenever the backing file or HMAC integrity tag is corrupted or modified.
 */
class PersistentTrustStore(
    private val storageFile: File? = null,
    private val integrityKey: ByteArray = Crypto.randomBytes(32)
) : TrustStore {

    private val lock = Any()
    private val recordsByDeviceId = LinkedHashMap<String, TrustedPeerRecord>()
    private val revokedKeyFingerprints = LinkedHashSet<String>()
    private var corruptedState: Boolean = false

    init {
        if (storageFile != null && storageFile.exists()) {
            loadFromDiskLocked()
        }
    }

    override fun isCorrupted(): Boolean = synchronized(lock) { corruptedState }

    override fun approvePeer(record: TrustedPeerRecord) = synchronized(lock) {
        check(!corruptedState) { "TrustStore is in a corrupted state; fail closed." }
        val computedFp = Crypto.publicKeyFingerprint(record.signingPublicKeyB64)
        require(record.fingerprint == computedFp) { "Record fingerprint mismatch" }
        require(record.deviceId == "dev_$computedFp") { "Record deviceId is not bound to public key" }
        require(record.status == TrustStatus.APPROVED) { "approvePeer requires APPROVED status" }

        // If previously approved under a DIFFERENT public key, reject key substitution unless explicitly re-paired
        val existing = recordsByDeviceId[record.deviceId]
        if (existing != null && existing.status == TrustStatus.REVOKED && existing.pairedSessionId == record.pairedSessionId) {
            throw SecurityException("Cannot re-approve a revoked peer using an old pairing session")
        }

        revokedKeyFingerprints.remove(computedFp)
        recordsByDeviceId[record.deviceId] = record
        persistToDiskLocked()
    }

    override fun revokePeer(deviceId: String, nowMs: Long): Boolean = synchronized(lock) {
        if (corruptedState) return false
        val existing = recordsByDeviceId[deviceId] ?: return false
        revokedKeyFingerprints.add(existing.fingerprint)
        recordsByDeviceId[deviceId] = existing.copy(
            status = TrustStatus.REVOKED,
            revokedAtMs = nowMs,
            channelSecretB64 = ""
        )
        persistToDiskLocked()
        true
    }

    override fun getApprovedPeer(deviceId: String): TrustedPeerRecord? = synchronized(lock) {
        if (corruptedState) return null
        val rec = recordsByDeviceId[deviceId] ?: return null
        if (rec.status != TrustStatus.APPROVED) return null
        if (revokedKeyFingerprints.contains(rec.fingerprint)) return null
        val computedFp = Crypto.publicKeyFingerprint(rec.signingPublicKeyB64)
        if (computedFp != rec.fingerprint || rec.deviceId != "dev_$computedFp") {
            corruptedState = true
            return null
        }
        rec
    }

    override fun getAllApprovedPeers(): List<TrustedPeerRecord> = synchronized(lock) {
        if (corruptedState) return emptyList()
        recordsByDeviceId.values.filter {
            it.status == TrustStatus.APPROVED && !revokedKeyFingerprints.contains(it.fingerprint)
        }
    }

    override fun isTrustedPeer(deviceId: String, signingPublicKeyB64: String): Boolean = synchronized(lock) {
        if (corruptedState) return false
        val rec = getApprovedPeer(deviceId) ?: return false
        Crypto.constantTimeEqualsUtf8(rec.signingPublicKeyB64, signingPublicKeyB64)
    }

    override fun isRevoked(deviceId: String, signingPublicKeyB64: String?): Boolean = synchronized(lock) {
        if (corruptedState) return true
        val rec = recordsByDeviceId[deviceId]
        if (rec != null && rec.status == TrustStatus.REVOKED) return true
        if (!signingPublicKeyB64.isNullOrBlank()) {
            val fp = try {
                Crypto.publicKeyFingerprint(signingPublicKeyB64)
            } catch (_: Throwable) {
                return true
            }
            if (revokedKeyFingerprints.contains(fp)) return true
        }
        false
    }

    override fun clearAll(): Unit = synchronized(lock) {
        recordsByDeviceId.clear()
        revokedKeyFingerprints.clear()
        corruptedState = false
        storageFile?.delete()
        Unit
    }

    private fun serializeRecordsLocked(): String {
        val sb = StringBuilder()
        sb.append("V1\n")
        for (rec in recordsByDeviceId.values) {
            val safeName = Crypto.encodeB64(rec.deviceName.toByteArray(StandardCharsets.UTF_8))
            sb.append(
                listOf(
                    rec.deviceId,
                    safeName,
                    rec.signingPublicKeyB64,
                    rec.kemPublicKeyB64,
                    rec.fingerprint,
                    rec.pairedSessionId,
                    rec.approvedAtMs.toString(),
                    rec.status.name,
                    (rec.revokedAtMs ?: -1L).toString(),
                    rec.channelSecretB64
                ).joinToString("|")
            ).append("\n")
        }
        return sb.toString()
    }

    private fun persistToDiskLocked() {
        val file = storageFile ?: return
        val body = serializeRecordsLocked()
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val macB64 = Crypto.encodeB64(Crypto.hmacSha256(integrityKey, bodyBytes))
        val fullContent = "$macB64\n$body"
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        tmpFile.writeText(fullContent, StandardCharsets.UTF_8)
        if (!tmpFile.renameTo(file)) {
            file.writeText(fullContent, StandardCharsets.UTF_8)
            tmpFile.delete()
        }
    }

    fun reloadFromDisk() = synchronized(lock) {
        loadFromDiskLocked()
    }

    private fun loadFromDiskLocked() {
        val file = storageFile ?: return
        recordsByDeviceId.clear()
        revokedKeyFingerprints.clear()
        if (!file.exists()) return
        try {
            val text = file.readText(StandardCharsets.UTF_8)
            val newlineIdx = text.indexOf('\n')
            if (newlineIdx <= 0) {
                corruptedState = true
                return
            }
            val expectedMacB64 = text.substring(0, newlineIdx)
            val body = text.substring(newlineIdx + 1)
            val actualMac = Crypto.hmacSha256(integrityKey, body.toByteArray(StandardCharsets.UTF_8))
            val expectedMac = Crypto.decodeB64(expectedMacB64)
            if (!Crypto.constantTimeEquals(actualMac, expectedMac)) {
                corruptedState = true
                return
            }
            val lines = body.lines().filter { it.isNotBlank() }
            if (lines.isEmpty() || lines.first() != "V1") {
                corruptedState = true
                return
            }
            for (line in lines.drop(1)) {
                val parts = line.split("|")
                if (parts.size != 10) {
                    corruptedState = true
                    return
                }
                val deviceId = parts[0]
                val deviceName = String(Crypto.decodeB64(parts[1]), StandardCharsets.UTF_8)
                val signingPub = parts[2]
                val kemPub = parts[3]
                val fingerprint = parts[4]
                val sessionId = parts[5]
                val approvedAt = parts[6].toLong()
                val status = TrustStatus.valueOf(parts[7])
                val revokedAt = parts[8].toLong().takeIf { it >= 0L }
                val channelSecret = parts[9]

                val computedFp = Crypto.publicKeyFingerprint(signingPub)
                if (computedFp != fingerprint || deviceId != "dev_$computedFp") {
                    corruptedState = true
                    return
                }
                val rec = TrustedPeerRecord(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    signingPublicKeyB64 = signingPub,
                    kemPublicKeyB64 = kemPub,
                    fingerprint = fingerprint,
                    pairedSessionId = sessionId,
                    approvedAtMs = approvedAt,
                    status = status,
                    revokedAtMs = revokedAt,
                    channelSecretB64 = channelSecret
                )
                recordsByDeviceId[deviceId] = rec
                if (status == TrustStatus.REVOKED) {
                    revokedKeyFingerprints.add(fingerprint)
                }
            }
            corruptedState = false
        } catch (_: Throwable) {
            recordsByDeviceId.clear()
            revokedKeyFingerprints.clear()
            corruptedState = true
        }
    }
}
