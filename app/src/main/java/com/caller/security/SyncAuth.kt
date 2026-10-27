package com.caller.security

import kotlin.math.abs

object SyncAuth {
    const val HDR_ID = "x-device-id"
    const val HDR_TS = "x-timestamp"
    const val HDR_NONCE = "x-nonce"
    const val HDR_SIG = "x-signature"
    const val HDR_RESP_SIG = "x-resp-signature"

    /** Signed over method, exact target (path+query), time, nonce, body hash, receiver id and sender id. */
    fun requestBytes(method: String, target: String, ts: Long, nonce: String, body: ByteArray, audienceId: String, callerId: String): ByteArray =
        Crypto.canon("dialer-sync-v1", method.uppercase(), target, ts.toString(), nonce, Crypto.hex(Crypto.sha256(body)), audienceId, callerId)

    fun responseBytes(requestNonce: String, callerId: String, status: Int, body: ByteArray): ByteArray =
        Crypto.canon("dialer-sync-resp-v1", requestNonce, callerId, status.toString(), Crypto.hex(Crypto.sha256(body)))

    /** Headers a client attaches to every sync request. A fresh nonce is used each call. */
    fun sign(identity: DeviceIdentity, audienceId: String, method: String, target: String, body: ByteArray, now: Long): Map<String, String> {
        val nonce = Crypto.b64url(Crypto.randomBytes(16))
        val sig = identity.sign(requestBytes(method, target, now, nonce, body, audienceId, identity.id))
        return mapOf(HDR_ID to identity.id, HDR_TS to now.toString(), HDR_NONCE to nonce, HDR_SIG to Crypto.b64(sig))
    }
}

sealed interface AuthResult {
    data class Allowed(val device: TrustedDevice, val nonce: String) : AuthResult
    data class Denied(val reason: String) : AuthResult
}

/**
 * Gatekeeper for every sync handler. Network location (Wi-Fi, Tailscale, IP, subnet) is never
 * consulted: only a valid signature from a currently TRUSTED device key passes.
 */
class SyncAuthenticator(
    private val identity: DeviceIdentity,
    private val trustStore: TrustStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val skewMs: Long = 60_000,
    private val onDenied: (String) -> Unit = {},
) {
    private val seen = LinkedHashMap<String, Long>()
    private val nonceTtlMs = skewMs * 2 + 1_000

    fun authenticate(method: String, target: String, headers: Map<String, String>, body: ByteArray): AuthResult {
        val h = headers.mapKeys { it.key.lowercase() }
        val id = h[SyncAuth.HDR_ID] ?: return deny("missing credentials")
        val nonce = h[SyncAuth.HDR_NONCE] ?: return deny("missing credentials")
        val ts = h[SyncAuth.HDR_TS]?.toLongOrNull() ?: return deny("missing credentials")
        val sigB64 = h[SyncAuth.HDR_SIG] ?: return deny("missing credentials")
        if (!Pairing.validNonce(nonce)) return deny("bad nonce")

        val device = trustStore.get(id)
        if (device == null) return deny("unknown device")
        if (device.status != TrustStatus.TRUSTED) return deny("revoked device")

        val now = clock()
        if (abs(now - ts) > skewMs) return deny("stale timestamp")

        val pub = runCatching { Crypto.publicKeyFromEncoded(Crypto.unb64(device.publicKey)) }.getOrNull() ?: return deny("bad stored key")
        val sig = runCatching { Crypto.unb64(sigB64) }.getOrNull() ?: return deny("bad signature")
        if (!Crypto.verify(pub, SyncAuth.requestBytes(method, target, ts, nonce, body, identity.id, id), sig)) return deny("bad signature")

        // Only remember nonces of requests that already verified, so outsiders can't fill the cache.
        if (!remember("$id:$nonce", now)) return deny("replay")
        return AuthResult.Allowed(device, nonce)
    }

    fun signResponse(requestNonce: String, callerId: String, status: Int, body: ByteArray): String =
        Crypto.b64(identity.sign(SyncAuth.responseBytes(requestNonce, callerId, status, body)))

    @Synchronized
    private fun remember(key: String, now: Long): Boolean {
        val iter = seen.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value < now) iter.remove() else break
        }
        if (seen.containsKey(key)) return false
        seen[key] = now + nonceTtlMs
        return true
    }

    private fun deny(reason: String): AuthResult {
        onDenied(reason)
        return AuthResult.Denied(reason)
    }
}
