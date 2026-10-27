package com.caller.security

class HandlerResult(val status: Int, val body: ByteArray)

fun interface SyncHandler {
    fun handle(device: TrustedDevice, method: String, target: String, body: ByteArray): HandlerResult
}

/** Authenticated client for a paired peer. Also verifies the peer's signature on every success response. */
class SecureSyncClient(
    private val identity: DeviceIdentity,
    private val trustStore: TrustStore,
    private val peerId: String,
    private val baseUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Response(val status: Int, val body: ByteArray)

    fun request(method: String, target: String, body: ByteArray = ByteArray(0)): Response {
        val peer = trustStore.get(peerId)?.takeIf { it.status == TrustStatus.TRUSTED }
            ?: throw SecurityException("peer is not trusted")
        val headers = SyncAuth.sign(identity, peerId, method, target, body, clock())
        val r = httpCall(method, baseUrl + target, headers, body)
        if (r.status in 200..299) {
            val sig = r.headers[SyncAuth.HDR_RESP_SIG]?.let { runCatching { Crypto.unb64(it) }.getOrNull() }
                ?: throw SecurityException("unsigned response")
            val pub = Crypto.publicKeyFromEncoded(Crypto.unb64(peer.publicKey))
            val nonce = headers.getValue(SyncAuth.HDR_NONCE)
            if (!Crypto.verify(pub, SyncAuth.responseBytes(nonce, identity.id, r.status, r.body), sig)) {
                throw SecurityException("response signature invalid")
            }
        }
        return Response(r.status, r.body)
    }

    private class RawResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun httpCall(method: String, urlStr: String, headers: Map<String, String>, body: ByteArray): RawResponse {
        val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            doInput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body.isNotEmpty()) {
                doOutput = true
                outputStream.use { it.write(body) }
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
            val respBody = stream?.use { it.readBytes() } ?: ByteArray(0)
            val respHeaders = conn.headerFields.mapNotNull { (k, v) ->
                if (k != null && v.isNotEmpty()) k to v[0] else null
            }.toMap()
            RawResponse(code, respHeaders, respBody)
        } finally {
            conn.disconnect()
        }
    }
}
