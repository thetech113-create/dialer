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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import com.caller.logging.UltraForensicLogger

class CallerIdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UltraForensicLogger.debug("=== CallerIdReceiver Triggered ===")
        UltraForensicLogger.debug("Action: ${intent.action}")

        val action = intent.action
        if (action != "android.intent.action.DATA_SMS_RECEIVED" &&
            action != "android.provider.Telephony.SMS_RECEIVED"
        ) {
            return
        }

        try {
            val bundle = intent.extras ?: return
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            val format = bundle.getString("format")

            for (pdu in pdus) {
                if (pdu == null) continue

                val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    SmsMessage.createFromPdu(pdu as ByteArray, format)
                } else {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(pdu as ByteArray)
                }

                val senderNumber = message.originatingAddress ?: continue
                val rawBody = when {
                    message.userData?.isNotEmpty() == true -> String(message.userData ?: byteArrayOf(), Charsets.UTF_8).trim()
                    !message.messageBody.isNullOrBlank() -> message.messageBody.trim()
                    else -> ""
                }
                val transportMode = if (action == "android.provider.Telephony.SMS_RECEIVED") {
                    P2PTransportMode.TEXT
                } else {
                    P2PTransportMode.DATA
                }
                val body = if (transportMode == P2PTransportMode.TEXT) {
                    unwrapP2PTextSmsPayload(rawBody).orEmpty()
                } else {
                    rawBody
                }

                val senderSnapshot = buildP2PNumberSnapshot(senderNumber)
                logP2PNumberTrace("receiver_sender", senderSnapshot)
                if (body.isBlank()) {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "receive_validation result=ignored reason=blank_or_non_p2p_payload action=$action transport=${transportMode.name} senderRaw=${maskP2PNumberForLog(senderSnapshot.raw)}"
                    )
                    continue
                }
                // First-time server number check (own number → own number); not a caller-ID payload.
                if (handleP2PVerifySms(context, body)) continue
                Log.w(P2P_FORENSIC_TAG, "receive_raw action=$action transport=${transportMode.name} payload=${maskP2PPayloadForLog(body)}")

                val parsedPayload = parseP2PPayload(body)
                if (parsedPayload == null) {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "receive_validation result=ignored reason=unrecognized_payload action=$action transport=${transportMode.name} senderRaw=${maskP2PNumberForLog(senderSnapshot.raw)}"
                    )
                    continue
                }

                val declaredSnapshot = buildP2PNumberSnapshot(parsedPayload.declaredNumber)
                logP2PNumberTrace("receiver_declared", declaredSnapshot)
                Log.w(
                    P2P_FORENSIC_TAG,
                    "receive_parsed type=${parsedPayload.type.name} parsedName=${maskP2PDisplayValueForLog(parsedPayload.profileName)} parsedNumber=${maskP2PNumberForLog(parsedPayload.declaredNumber)} timestamp=${parsedPayload.timestamp}"
                )

                val saveResult = storeP2PProfile(
                    context = context,
                    transportNumberRaw = senderNumber,
                    declaredNumberRaw = parsedPayload.declaredNumber,
                    profileNameRaw = parsedPayload.profileName,
                    transportMode = transportMode,
                    payloadType = parsedPayload.type,
                    trigger = "CallerIdReceiver.onReceive"
                )
                Log.w(
                    P2P_FORENSIC_TAG,
                    "receive_save_result type=${parsedPayload.type.name} transport=${transportMode.name} validationResult=${parsedPayload.profileName.isNotBlank()} saveResult=$saveResult transportKeys=${maskP2PKeysForLog(senderSnapshot.lookupKeys)} declaredKeys=${maskP2PKeysForLog(declaredSnapshot.lookupKeys)}"
                )

                val activeNumber = MyInCallService.getInstance()?.getActivePrimaryNumberForP2P().orEmpty()
                val activeSnapshot = buildP2PNumberSnapshot(activeNumber)
                val activeMatch = activeSnapshot.lookupKeys.intersect(
                    (senderSnapshot.lookupKeys + declaredSnapshot.lookupKeys).toSet()
                ).isNotEmpty()
                Log.w(
                    P2P_FORENSIC_TAG,
                    "receive_active_match activeRaw=${maskP2PNumberForLog(activeNumber)} activeNormalized=${maskP2PNumberForLog(activeSnapshot.normalized)} activeLast10=${maskP2PNumberForLog(activeSnapshot.last10)} incomingKeys=${maskP2PKeysForLog(senderSnapshot.lookupKeys + declaredSnapshot.lookupKeys)} matched=$activeMatch"
                )

                if (parsedPayload.type == P2PMessageType.CALLER) {
                    Log.w(
                        P2P_FORENSIC_TAG,
                        "reverse_reply_attempt sender=${maskP2PNumberForLog(senderSnapshot.raw)} senderDigits=${maskP2PNumberForLog(senderSnapshot.digits)}"
                    )
                    try {
                        val result = sendP2PProfileMessage(
                            context = context,
                            destinationNumberRaw = senderNumber,
                            messageType = P2PMessageType.REPLY,
                            trigger = "CallerIdReceiver.reverse_reply"
                        )
                        Log.w(
                            P2P_FORENSIC_TAG,
                            "reverse_reply_result requested=${result.requested} blocked=${result.blockedReason} sender=${maskP2PNumberForLog(senderSnapshot.raw)}"
                        )
                    } catch (e: Exception) {
                        Log.e(
                            P2P_FORENSIC_TAG,
                            "reverse_reply_error sender=${maskP2PNumberForLog(senderSnapshot.raw)} error=${e.message}"
                        )
                    }
                }

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    MyInCallService.getInstance()?.refreshCallerIdentityAfterP2P()
                }
            }
        } catch (e: Exception) {
            UltraForensicLogger.deepest("Error in CallerIdReceiver: ${e.message}")
        }
    }
}
