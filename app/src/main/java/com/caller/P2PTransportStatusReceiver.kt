package com.caller

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class P2PTransportStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val destinationRaw = intent.getStringExtra("p2p_destination") ?: ""
        val payloadType = intent.getStringExtra("p2p_type") ?: "UNKNOWN"
        val trigger = intent.getStringExtra("p2p_trigger") ?: "UNKNOWN"
        val payload = intent.getStringExtra("p2p_payload") ?: ""
        val transport = intent.getStringExtra(EXTRA_P2P_TRANSPORT) ?: P2PTransportMode.DATA.name
        val result = describeTransportStatus(resultCode)

        Log.w(
            P2P_FORENSIC_TAG,
            "transport_status action=${intent.action} type=$payloadType trigger=$trigger transport=$transport destinationRaw=${maskP2PNumberForLog(destinationRaw)} resultCode=$resultCode result=$result payload=${maskP2PPayloadForLog(payload)}"
        )

        if (intent.action != P2P_SMS_SENT_ACTION ||
            resultCode == Activity.RESULT_OK
        ) {
            return
        }

        if (transport == P2PTransportMode.TEXT.name) {
            val markedUnsupported = markP2PSmsUnsupportedPeer(
                context = context,
                phoneNumberRaw = destinationRaw,
                source = "text_sent_result_$result",
                trigger = trigger
            )
            Log.w(
                P2P_FORENSIC_TAG,
                "transport_text_failure_mark trigger=$trigger result=$result markedUnsupported=$markedUnsupported destinationRaw=${maskP2PNumberForLog(destinationRaw)}"
            )
            return
        }

        if (transport != P2PTransportMode.DATA.name) {
            return
        }

        val messageType = runCatching { P2PMessageType.valueOf(payloadType) }.getOrNull()
        if (messageType == null || payload.isBlank()) {
            Log.w(
                P2P_FORENSIC_TAG,
                "transport_fallback_skipped trigger=$trigger reason=invalid_message_type_or_payload type=$payloadType transport=$transport destinationRaw=${maskP2PNumberForLog(destinationRaw)}"
            )
            return
        }

        val fallbackResult = sendP2PTextFallbackMessage(
            context = context,
            destinationNumberRaw = destinationRaw,
            messageType = messageType,
            trigger = "$trigger:data_sent_result_$result",
            payload = payload
        )
        Log.w(
            P2P_FORENSIC_TAG,
            "transport_fallback_result trigger=$trigger originalTransport=$transport fallbackTransport=${P2PTransportMode.TEXT.name} type=${messageType.name} requested=${fallbackResult.requested} blockedReason=${fallbackResult.blockedReason} destinationRaw=${maskP2PNumberForLog(destinationRaw)}"
        )
    }
}
