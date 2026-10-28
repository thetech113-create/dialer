package com.caller.security

data class SyncTransportRequest(
    val endpointPath: String,
    val sourceNetworkTag: String, // e.g., "WIFI_LAN:192.168.1.25", "TAILSCALE:100.84.12.9", "CLOUD_RELAY"
    val sharedAccountCodeHint: String? = null, // Untrusted discovery hint (e.g., "1111")
    val envelope: AuthenticatedSyncEnvelope?
)

data class SyncTransportResponse(
    val statusCode: Int,
    val responseEnvelope: AuthenticatedSyncEnvelope? = null,
    val errorMessage: String? = null
)

/**
 * Gatekeeper for all incoming sync transport endpoints (Wi-Fi TCP, Tailscale TCP, HTTP, Background Workers).
 * Enforces that network reachability or a matching short code ("1111") NEVER grants access.
 */
class AuthenticatedSyncGateway(
    private val authenticator: SyncAuthenticator,
    private val callHistoryProvider: (senderDeviceId: String) -> String,
    private val callHistoryIngestor: (senderDeviceId: String, payloadJson: String) -> Unit
) {
    companion object {
        const val PATH_PUSH_CALLS = "/v1/sync/push"
        const val PATH_PULL_CALLS = "/v1/sync/pull"
        const val PATH_SNAPSHOT_EXCHANGE = "/v1/sync/snapshot"
        const val PATH_HEARTBEAT = "/v1/sync/heartbeat"
    }

    fun handleIncomingSync(
        request: SyncTransportRequest,
        nowMs: Long = System.currentTimeMillis()
    ): SyncTransportResponse {
        val env = request.envelope
            ?: return SyncTransportResponse(statusCode = 401, errorMessage = "Missing authenticated sync envelope")

        val expectedOp = when (request.endpointPath) {
            PATH_PUSH_CALLS -> SyncOperation.SYNC_PUSH_CALLS
            PATH_PULL_CALLS -> SyncOperation.SYNC_PULL_CALLS
            PATH_SNAPSHOT_EXCHANGE -> SyncOperation.SYNC_SNAPSHOT_EXCHANGE
            PATH_HEARTBEAT -> SyncOperation.HEARTBEAT_RECONNECT
            else -> return SyncTransportResponse(statusCode = 404, errorMessage = "Unknown sync endpoint")
        }

        val verified = try {
            authenticator.verifyAndDecrypt(
                envelope = env,
                expectedOperation = expectedOp,
                nowMs = nowMs
            )
        } catch (secEx: SecurityException) {
            return SyncTransportResponse(statusCode = 403, errorMessage = "Access denied: ${secEx.message}")
        } catch (_: Throwable) {
            return SyncTransportResponse(statusCode = 403, errorMessage = "Access denied")
        }

        return try {
            when (expectedOp) {
                SyncOperation.SYNC_PUSH_CALLS -> {
                    callHistoryIngestor(verified.senderDeviceId, verified.plaintextUtf8)
                    val ackEnvelope = authenticator.createAuthenticatedEnvelope(
                        recipientDeviceId = verified.senderDeviceId,
                        operation = SyncOperation.SYNC_PUSH_CALLS,
                        plaintextPayloadUtf8 = """{"status":"INGESTED"}""",
                        nowMs = nowMs
                    )
                    SyncTransportResponse(statusCode = 200, responseEnvelope = ackEnvelope)
                }

                SyncOperation.SYNC_PULL_CALLS -> {
                    val historyJson = callHistoryProvider(verified.senderDeviceId)
                    val replyEnvelope = authenticator.createAuthenticatedEnvelope(
                        recipientDeviceId = verified.senderDeviceId,
                        operation = SyncOperation.SYNC_PULL_CALLS,
                        plaintextPayloadUtf8 = historyJson,
                        nowMs = nowMs
                    )
                    SyncTransportResponse(statusCode = 200, responseEnvelope = replyEnvelope)
                }

                SyncOperation.SYNC_SNAPSHOT_EXCHANGE -> {
                    callHistoryIngestor(verified.senderDeviceId, verified.plaintextUtf8)
                    val localSnapshotJson = callHistoryProvider(verified.senderDeviceId)
                    val replyEnvelope = authenticator.createAuthenticatedEnvelope(
                        recipientDeviceId = verified.senderDeviceId,
                        operation = SyncOperation.SYNC_SNAPSHOT_EXCHANGE,
                        plaintextPayloadUtf8 = localSnapshotJson,
                        nowMs = nowMs
                    )
                    SyncTransportResponse(statusCode = 200, responseEnvelope = replyEnvelope)
                }

                SyncOperation.HEARTBEAT_RECONNECT -> {
                    val ackEnvelope = authenticator.createAuthenticatedEnvelope(
                        recipientDeviceId = verified.senderDeviceId,
                        operation = SyncOperation.HEARTBEAT_RECONNECT,
                        plaintextPayloadUtf8 = """{"status":"ALIVE"}""",
                        nowMs = nowMs
                    )
                    SyncTransportResponse(statusCode = 200, responseEnvelope = ackEnvelope)
                }
            }
        } catch (secEx: SecurityException) {
            SyncTransportResponse(statusCode = 403, errorMessage = "Access denied during response: ${secEx.message}")
        }
    }
}
