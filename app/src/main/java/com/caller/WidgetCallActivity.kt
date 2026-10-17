package com.caller

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

class WidgetCallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val phoneNumber = intent?.getStringExtra(Constants.EXTRA_WIDGET_PHONE_NUMBER)
        if (phoneNumber.isNullOrBlank() || !isSafePhoneNumber(phoneNumber)) {
            finishTask()
            return
        }

        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ||
            !DualSimFixes.hasRequiredSubscriptionPermissions(this)
        ) {
            routeToMainActivity()
            return
        }

        try {
            val dialableNumber = sanitizePhoneNumberForCall(phoneNumber)
            val finalUri = Uri.fromParts("tel", dialableNumber, null)
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            val simOptions = DualSimFixes.resolveSimCallOptions(this)

            when {
                simOptions.isEmpty() -> {
                    Log.e("OutgoingCallAudit", "widget_sim_detection_failed reason=no_sim_detected")
                    finishTask()
                }
                simOptions.size == 1 -> {
                    placeWidgetCall(phoneNumber, dialableNumber, finalUri, telecomManager, simOptions.first())
                }
                else -> {
                    DualSimFixes.showSimSelectionDialog(
                        activity = this,
                        options = simOptions,
                        onSelected = { selectedSim ->
                            placeWidgetCall(phoneNumber, dialableNumber, finalUri, telecomManager, selectedSim)
                        },
                        onCancelled = {
                            finishTask()
                        }
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w("OutgoingCallAudit", "widget_sim_detection_failed reason=security_exception error=${e.message}")
            routeToMainActivity()
        } catch (e: IllegalStateException) {
            Log.e("OutgoingCallAudit", "widget_sim_detection_failed reason=illegal_state error=${e.message}", e)
            finishTask()
        } catch (e: Exception) {
            Log.e("OutgoingCallAudit", "widget_call_failed reason=unexpected error=${e.message}", e)
            routeToMainActivity()
        }
    }

    private fun placeWidgetCall(
        phoneNumber: String,
        dialableNumber: String,
        finalUri: Uri,
        telecomManager: TelecomManager,
        selectedSim: DualSimFixes.SimCallOption
    ) {
        Log.w(
            "OutgoingCallAudit",
            "outgoing_call_prepare branch=widget raw=${maskPhoneNumber(phoneNumber)} dialable=${maskPhoneNumber(dialableNumber)} finalUri=${maskPhoneNumber(finalUri.schemeSpecificPart.orEmpty())} api=TelecomManager.placeCall selectedSimId=${selectedSim.subscriptionId} slot=${selectedSim.slotIndex} carrier=${selectedSim.carrierName} handle=${selectedSim.phoneAccountHandle.id}"
        )

        val extras = DualSimFixes.buildCallExtras(selectedSim)
        telecomManager.placeCall(finalUri, extras)

        startActivity(Intent(this, CallActivity::class.java).apply {
            action = CALL_UI_ACTION_WAKE
            putExtra(CALL_UI_EXTRA_PHONE_NUMBER_HINT, phoneNumber)
            putExtra(CALL_UI_EXTRA_CONTACT_NAME_HINT, phoneNumber)
            putExtra(CALL_UI_EXTRA_STATE_HINT, "DIALING")
            putExtra(CALL_UI_EXTRA_IS_CONFERENCE_HINT, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finishTask()
    }

    private fun routeToMainActivity() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finishTask()
    }

    private fun finishTask() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }
}

