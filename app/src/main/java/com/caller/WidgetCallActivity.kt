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
    // The widget's dial attempt (shared outgoing-dial guard), while it has not been handed to Telecom yet.
    private var attemptId: Long? = null

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

        // Same guard as the app's keypad: a widget tap while another dial is still being set up dials nothing.
        val id = DialAttempts.tryAcquire(this, source = "widget")
        if (id == null) {
            finishTask()
            return
        }
        attemptId = id

        try {
            val dialableNumber = sanitizePhoneNumberForCall(phoneNumber)
            val finalUri = Uri.fromParts("tel", dialableNumber, null)
            val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
            val simOptions = DualSimFixes.resolveSimCallOptions(this)

            when {
                simOptions.isEmpty() -> {
                    Log.e("OutgoingCallAudit", "widget_sim_detection_failed reason=no_sim_detected")
                    releaseAttempt("no_sim")
                    finishTask()
                }
                simOptions.size == 1 -> {
                    placeWidgetCall(id, phoneNumber, dialableNumber, finalUri, telecomManager, simOptions.first())
                }
                else -> {
                    DialAttempts.guard.awaitUserChoice(id)
                    DualSimFixes.showSimSelectionDialog(
                        activity = this,
                        options = simOptions,
                        onSelected = { selectedSim ->
                            DialAttempts.guard.userChoiceMade(id)
                            placeWidgetCall(id, phoneNumber, dialableNumber, finalUri, telecomManager, selectedSim)
                        },
                        onCancelled = {
                            releaseAttempt("sim_choice_cancelled")
                            finishTask()
                        }
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w("OutgoingCallAudit", "widget_sim_detection_failed reason=security_exception error=${e.message}")
            releaseAttempt("security_exception")
            routeToMainActivity()
        } catch (e: IllegalStateException) {
            Log.e("OutgoingCallAudit", "widget_sim_detection_failed reason=illegal_state error=${e.message}", e)
            releaseAttempt("illegal_state")
            finishTask()
        } catch (e: Exception) {
            Log.e("OutgoingCallAudit", "widget_call_failed reason=unexpected error=${e.message}", e)
            releaseAttempt("unexpected_error")
            routeToMainActivity()
        }
    }

    override fun onDestroy() {
        // Closed before the call was handed to Telecom (e.g. the SIM picker went away with the screen): release it.
        releaseAttempt("widget_closed_before_dispatch")
        super.onDestroy()
    }

    private fun releaseAttempt(reason: String) {
        val id = attemptId ?: return
        attemptId = null
        DialAttempts.guard.fail(id, reason)
    }

    private fun placeWidgetCall(
        id: Long,
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
        // Handed to Telecom: from here the attempt is resolved by Telecom's callbacks, not by this screen closing.
        attemptId = null
        if (!DialAttempts.guard.dispatch(id, "widget") { telecomManager.placeCall(finalUri, extras) }) {
            finishTask()
            return
        }

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

