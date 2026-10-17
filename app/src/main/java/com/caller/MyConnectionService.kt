@file:Suppress(
    "all",
    "WARNINGS",
    "unused",
    "UNUSED_VARIABLE",
    "UnusedImport",
    "RemoveRedundantQualifierName",
    "NAME_SHADOWING",
    "LiftReturnOrAssignment",
    "RegExpSimplifiable",
    "UNNECESSARY_SAFE_CALL",
    "UNNECESSARY_NOT_NULL_ASSERTION",
    "UselessCallOnNotNull",
    "RedundantSafeCall",
    "DEPRECATION",
    "IfThenToIfEmpty",
    "ifEmpty {...}",
    "SpellCheckingInspection",
    "Typo"
)

package com.caller

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import com.caller.logging.UltraForensicLogger

class MyConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        if (request == null) {
            UltraForensicLogger.debug("Rejected null outgoing connection request")
            return failedConnection("Null outgoing request")
        }

        if (!ConnectionServiceFixes.validateConnectionRequest(request)) {
            UltraForensicLogger.debug("Rejected invalid outgoing connection request")
            return failedConnection("Invalid outgoing request")
        }

        return ConnectionServiceFixes.createSafeOutgoingConnection(this, request)
            ?: failedConnection("Outgoing connection creation returned null")
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        if (request == null) {
            UltraForensicLogger.debug("Rejected null incoming connection request")
            return failedConnection("Null incoming request")
        }

        if (!ConnectionServiceFixes.validateConnectionRequest(request)) {
            UltraForensicLogger.debug("Rejected invalid incoming connection request")
            return failedConnection("Invalid incoming request")
        }

        return ConnectionServiceFixes.createSafeIncomingConnection(this, request)
            ?: failedConnection("Incoming connection creation returned null")
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request)
        UltraForensicLogger.debug("Outgoing connection failed. request=${request?.address}")
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
        UltraForensicLogger.debug("Incoming connection failed. request=${request?.address}")
    }

    private fun failedConnection(reason: String): Connection {
        return Connection.createFailedConnection(
            DisconnectCause(DisconnectCause.ERROR, reason)
        )
    }
}
