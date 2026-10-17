package com.caller

import android.annotation.SuppressLint
import android.content.Context
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log

/**
 * Fixes for ConnectionService routing failures
 * Addresses Root Cause #3: ConnectionService returning null
 */
object ConnectionServiceFixes {

    private const val TAG = "ConnectionServiceFixes"

    @SuppressLint("MissingPermission")
    fun createSafeOutgoingConnection(
        context: Context,
        request: ConnectionRequest
    ): Connection? {
        return try {
            val handle = request.address
            Log.w(TAG, "Creating outgoing connection for: $handle")

            if (handle == null) {
                Log.e(TAG, "Null handle in connection request")
                return createErrorConnection("Invalid phone number")
            }

            val connection = createConfiguredConnection(context, handle, request)
            connection.setActive()
            connection.setInitialized()

            Log.w(TAG, "Outgoing connection created successfully")
            connection
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception creating connection", e)
            createErrorConnection("Permission denied", DisconnectCause.LOCAL)
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating connection", e)
            createErrorConnection("Connection failed", DisconnectCause.ERROR)
        }
    }

    @SuppressLint("MissingPermission")
    fun createSafeIncomingConnection(
        context: Context,
        request: ConnectionRequest
    ): Connection? {
        return try {
            val handle = request.address
            Log.w(TAG, "Creating incoming connection for: $handle")

            if (handle == null) {
                Log.e(TAG, "Null handle in incoming connection request")
                return createErrorConnection("Invalid incoming phone number")
            }

            val connection = createConfiguredConnection(context, handle, request)
            connection.setRinging()
            connection.setInitialized()

            Log.w(TAG, "Incoming connection created successfully")
            connection
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception creating incoming connection", e)
            createErrorConnection("Permission denied", DisconnectCause.LOCAL)
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating incoming connection", e)
            createErrorConnection("Incoming connection failed", DisconnectCause.ERROR)
        }
    }

    private fun createConfiguredConnection(
        context: Context,
        handle: android.net.Uri,
        request: ConnectionRequest
    ): Connection {
        val connection = object : Connection() {
            override fun onShowIncomingCallUi() {
                super.onShowIncomingCallUi()
                Log.w(TAG, "Incoming call UI requested")
                launchCallActivity(context, handle, true)
            }

            override fun onCallAudioStateChanged(state: CallAudioState?) {
                super.onCallAudioStateChanged(state)
                Log.w(TAG, "Audio state changed: $state")
            }

            override fun onStateChanged(state: Int) {
                super.onStateChanged(state)
                Log.w(TAG, "Connection state: ${stateToString(state)}")
            }

            override fun onDisconnect() {
                super.onDisconnect()
                Log.w(TAG, "Connection disconnected")
            }

            override fun onAbort() {
                super.onAbort()
                Log.w(TAG, "Connection aborted")
            }

            override fun onHold() {
                super.onHold()
                Log.w(TAG, "Connection held")
            }

            override fun onUnhold() {
                super.onUnhold()
                Log.w(TAG, "Connection unheld")
            }
        }

        connection.setAddress(handle, TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(
            request.address?.schemeSpecificPart ?: "Unknown",
            TelecomManager.PRESENTATION_ALLOWED
        )
        connection.setVideoState(VideoProfile.STATE_AUDIO_ONLY)
        connection.setRingbackRequested(true)
        connection.setInitializing()

        connection.setConnectionCapabilities(
            Connection.CAPABILITY_HOLD or
                Connection.CAPABILITY_SUPPORT_HOLD or
                Connection.CAPABILITY_MUTE
        )

        return connection
    }

    private fun createErrorConnection(
        errorMessage: String,
        disconnectCause: Int = DisconnectCause.ERROR
    ): Connection {
        return object : Connection() {
            override fun onShowIncomingCallUi() {}

            override fun onDisconnect() {
                super.onDisconnect()
                Log.w(TAG, "Error connection disconnected: $errorMessage")
            }
        }.apply {
            setDisconnected(DisconnectCause(disconnectCause, errorMessage))
            setInitialized()
        }
    }


    private fun launchCallActivity(context: Context, handle: android.net.Uri, isIncoming: Boolean) {
        try {
            val phoneNumber = handle.schemeSpecificPart.orEmpty()
            val stateHint = if (isIncoming) "RINGING" else "DIALING"
            val intent = android.content.Intent(context, CallActivity::class.java).apply {
                action = CALL_UI_ACTION_WAKE
                putExtra("WAKE_REASON", if (isIncoming) "connection_incoming" else "connection_outgoing")
                putExtra(CALL_UI_EXTRA_PHONE_NUMBER_HINT, phoneNumber)
                putExtra(CALL_UI_EXTRA_STATE_HINT, stateHint)
                putExtra(CALL_UI_EXTRA_CONTACT_NAME_HINT, phoneNumber)
                putExtra(CALL_UI_EXTRA_IS_CONFERENCE_HINT, false)
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch CallActivity", e)
        }
    }

    private fun stateToString(state: Int): String {
        return when (state) {
            Connection.STATE_INITIALIZING -> "INITIALIZING"
            Connection.STATE_DIALING -> "DIALING"
            Connection.STATE_RINGING -> "RINGING"
            Connection.STATE_ACTIVE -> "ACTIVE"
            Connection.STATE_HOLDING -> "HOLDING"
            Connection.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "UNKNOWN($state)"
        }
    }

    fun validateConnectionRequest(request: ConnectionRequest): Boolean {
        return try {
            val handle = request.address
            if (handle == null) {
                Log.e(TAG, "Connection request has null address")
                false
            } else {
                val scheme = handle.scheme
                if (scheme != "tel") {
                    Log.e(TAG, "Unsupported URI scheme: $scheme")
                    false
                } else {
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection request validation failed", e)
            false
        }
    }
}
