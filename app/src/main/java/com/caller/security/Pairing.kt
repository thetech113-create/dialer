package com.caller.security

import kotlin.math.abs

/** Tiny key=value wire format. All values are base64 / numbers / fixed words, so no escaping is needed. */
object Wire {
    fun encode(m: Map<String, String>): String = m.entries.joinToString("\n") { "${it.key}=${it.value}" }
    fun decode(s: String): Map<String, String> =
        s.lineSequence().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
        }.toMap()
}

object Pairing {
    const val CODE_LENGTH = 10
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 32 symbols, no look-alikes
    private val NONCE_RE = Regex("[A-Za-z0-9+/_-]{8,64}")

    fun newCode(): String = buildString {
        repeat(CODE_LENGTH) { append(ALPHABET[Crypto.randomIndex(ALPHABET.length)]) }
    }

    fun normalizeCode(s: String): String = s.uppercase().filter { it != '-' && it != ' ' }
    fun formatCode(c: String): String = c.substring(0, 5) + "-" + c.substring(5)
    fun validNonce(n: String): Boolean = NONCE_RE.matches(n)

    fun cleanName(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8).filter { !it.isISOControl() }.take(64).ifBlank { "Unnamed device" }

    fun proofBytes(ownerId: String, joinerId: String, nonce: String, ts: Long): ByteArray =
        Crypto.canon("dialer-pair-proof-v1", ownerId, joinerId, nonce, ts.toString())

    fun requestBytes(ownerId: String, pk: String, nameB64: String, nonce: String, ts: Long, proof: String): ByteArray =
        Crypto.canon("dialer-pair-req-v1", ownerId, pk, nameB64, nonce, ts.toString(), proof)

    fun resultBytes(pairingId: String, joinerId: String, state: String, ownerPk: String, ownerNameB64: String): ByteArray =
        Crypto.canon("dialer-pair-result-v1", pairingId, joinerId, state, ownerPk, ownerNameB64)
}

/** What the trusted device shows (QR or copy/paste): the one-time code plus its own identity fingerprint. */
class PairingOffer(val code: String, val ownerId: String, val expiresAt: Long) {
    val displayCode: String get() = Pairing.formatCode(code)
    val link: String get() = "dialerpair:$code:$ownerId"
}

data class PairingLink(val code: String, val ownerId: String) {
    companion object {
        fun parse(s: String): PairingLink? {
            val p = s.trim().split(':')
            if (p.size != 3 || p[0] != "dialerpair") return null
            val code = Pairing.normalizeCode(p[1])
            if (code.length !in 6..10 || !code.all { it.isLetterOrDigit() }) return null
            if (!Regex("[0-9a-f]{64}").matches(p[2])) return null
            return PairingLink(code, p[2])
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
) {
    fun toWire(): String = Wire.encode(
        mapOf("pk" to publicKey, "name" to nameB64, "nonce" to nonce, "ts" to timestamp.toString(), "proof" to proof, "sig" to signature),
    )

    companion object {
        fun fromWire(s: String): PairRequest? {
            val m = Wire.decode(s)
            return PairRequest(
                m["pk"] ?: return null,
                m["name"] ?: return null,
                m["nonce"] ?: return null,
                m["ts"]?.toLongOrNull() ?: return null,
                m["proof"] ?: return null,
                m["sig"] ?: return null,
            )
        }
    }
}

class PairStatus(val state: String, val ownerPublicKey: String?, val ownerNameB64: String?, val signature: String?) {
    fun toWire(): String {
        val m = linkedMapOf("state" to state)
        ownerPublicKey?.let { m["owner"] = it }
        ownerNameB64?.let { m["ownername"] = it }
        signature?.let { m["sig"] = it }
        return Wire.encode(m)
    }

    companion object {
        fun fromWire(s: String): PairStatus {
            val m = Wire.decode(s)
            return PairStatus(m["state"] ?: "UNKNOWN", m["owner"], m["ownername"], m["sig"])
        }
    }
}

/** New-device side of pairing. */
class PairingClient(
    private val identity: DeviceIdentity,
    private val deviceName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun buildRequest(link: PairingLink): PairRequest {
        val nonce = Crypto.b64url(Crypto.randomBytes(16))
        val ts = clock()
        val proof = Crypto.b64(Crypto.hmac(link.code.toByteArray(Charsets.UTF_8), Pairing.proofBytes(link.ownerId, identity.id, nonce, ts)))
        val nameB64 = Crypto.b64(deviceName.toByteArray(Charsets.UTF_8))
        val sig = Crypto.b64(identity.sign(Pairing.requestBytes(link.ownerId, identity.publicKeyB64, nameB64, nonce, ts, proof)))
        return PairRequest(identity.publicKeyB64, nameB64, nonce, ts, proof, sig)
    }

    fun verifyApproval(link: PairingLink, pairingId: String, st: PairStatus): TrustedDevice? {
        if (st.state != "APPROVED") return null
        val pk = st.ownerPublicKey ?: return null
        val nameB64 = st.ownerNameB64 ?: return null
        val sig = runCatching { Crypto.unb64(st.signature ?: return null) }.getOrNull() ?: return null
        val pub = runCatching { Crypto.publicKeyFromEncoded(Crypto.unb64(pk)) }.getOrNull() ?: return null
        if (Crypto.fingerprint(pub) != link.ownerId) return null
        if (!Crypto.verify(pub, Pairing.resultBytes(pairingId, identity.id, "APPROVED", pk, nameB64), sig)) return null
        val name = Pairing.cleanName(runCatching { Crypto.unb64(nameB64) }.getOrDefault(ByteArray(0)))
        return TrustedDevice(link.ownerId, name, pk, TrustStatus.TRUSTED, clock())
    }
}

/** Already-trusted device side: shows an offer, receives requests, requires explicit approval. */
class PairingHost(
    private val identity: DeviceIdentity,
    private val trustStore: TrustStore,
    private val hostName: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val offerTtlMs: Long = 120_000,
    private val pendingTtlMs: Long = 300_000,
    private val maxAttempts: Int = 5,
    private val skewMs: Long = 60_000,
) {
    sealed interface RequestResult {
        data class Accepted(val pairingId: String) : RequestResult
        data class Rejected(val reason: String) : RequestResult
    }

    data class PendingView(val id: String, val deviceId: String, val name: String)

    private enum class State { PENDING, APPROVED, DENIED }

    private class ActiveOffer(val code: String, val expiresAt: Long) {
        var attempts = 0
        val nonces = HashSet<String>()
    }

    private class Pending(val id: String, val deviceId: String, val publicKey: String, val name: String, val createdAt: Long) {
        var state = State.PENDING
    }

    private var offer: ActiveOffer? = null
    private val pending = LinkedHashMap<String, Pending>()

    @Synchronized
    fun createOffer(): PairingOffer {
        val o = ActiveOffer(Pairing.newCode(), clock() + offerTtlMs)
        offer = o
        return PairingOffer(o.code, identity.id, o.expiresAt)
    }

    @Synchronized
    fun createOfferWithCode(customCode: String): PairingOffer {
        val o = ActiveOffer(customCode, clock() + offerTtlMs)
        offer = o
        return PairingOffer(o.code, identity.id, o.expiresAt)
    }

    @Synchronized
    fun cancelOffer() { offer = null }

    @Synchronized
    fun handleRequest(req: PairRequest): RequestResult {
        val now = clock()
        val o = offer ?: return reject("no active pairing offer")
        if (now > o.expiresAt) { offer = null; return reject("pairing offer expired") }
        if (abs(now - req.timestamp) > skewMs) return reject("bad timestamp")
        if (!Pairing.validNonce(req.nonce)) return reject("bad request")
        val pub = runCatching { Crypto.publicKeyFromEncoded(Crypto.unb64(req.publicKey)) }.getOrNull() ?: return reject("bad key")
        val sig = runCatching { Crypto.unb64(req.signature) }.getOrNull() ?: return reject("bad request")
        val proof = runCatching { Crypto.unb64(req.proof) }.getOrNull() ?: return reject("bad request")
        val deviceId = Crypto.fingerprint(pub)
        if (!Crypto.verify(pub, Pairing.requestBytes(identity.id, req.publicKey, req.nameB64, req.nonce, req.timestamp, req.proof), sig)) {
            return reject("bad signature")
        }
        if (!o.nonces.add(req.nonce)) return reject("replay")
        val expected = Crypto.hmac(o.code.toByteArray(Charsets.UTF_8), Pairing.proofBytes(identity.id, deviceId, req.nonce, req.timestamp))
        if (!Crypto.constantTimeEquals(expected, proof)) {
            o.attempts++
            if (o.attempts >= maxAttempts) offer = null
            return reject("invalid pairing code")
        }
        offer = null
        pending.values.removeAll { now - it.createdAt > pendingTtlMs * 3 }
        val name = Pairing.cleanName(runCatching { Crypto.unb64(req.nameB64) }.getOrDefault(ByteArray(0)))
        val id = Crypto.b64url(Crypto.randomBytes(16))
        pending[id] = Pending(id, deviceId, req.publicKey, name, now)
        return RequestResult.Accepted(id)
    }

    @Synchronized
    fun pendingRequests(): List<PendingView> {
        val now = clock()
        return pending.values
            .filter { it.state == State.PENDING && now - it.createdAt <= pendingTtlMs }
            .map { PendingView(it.id, it.deviceId, it.name) }
    }

    @Synchronized
    fun approve(pairingId: String): Boolean {
        val p = pending[pairingId] ?: return false
        if (p.state != State.PENDING || clock() - p.createdAt > pendingTtlMs) return false
        trustStore.put(TrustedDevice(p.deviceId, p.name, p.publicKey, TrustStatus.TRUSTED, clock()))
        p.state = State.APPROVED
        return true
    }

    @Synchronized
    fun deny(pairingId: String) {
        pending[pairingId]?.takeIf { it.state == State.PENDING }?.state = State.DENIED
    }

    @Synchronized
    fun status(pairingId: String): PairStatus {
        val p = pending[pairingId] ?: return PairStatus("UNKNOWN", null, null, null)
        return when {
            p.state == State.APPROVED -> {
                val nameB64 = Crypto.b64(hostName.toByteArray(Charsets.UTF_8))
                val sig = identity.sign(Pairing.resultBytes(pairingId, p.deviceId, "APPROVED", identity.publicKeyB64, nameB64))
                PairStatus("APPROVED", identity.publicKeyB64, nameB64, Crypto.b64(sig))
            }
            p.state == State.DENIED -> PairStatus("DENIED", null, null, null)
            clock() - p.createdAt > pendingTtlMs -> PairStatus("EXPIRED", null, null, null)
            else -> PairStatus("PENDING", null, null, null)
        }
    }

    private fun reject(reason: String) = RequestResult.Rejected(reason)
}
