package com.caller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.caller.logging.UltraForensicLogger

class OtpAutoFillService {
    companion object {
        private const val TAG = "OtpAutoFillService"
        private const val ACTION_SMS_RETRIEVER = "com.google.android.gms.auth.api.phone.SMS_RETRIEVED"

        fun startOtpAutoFill(context: Context) {
            try {
                if (!isGooglePlayServicesAvailable(context)) {
                    Log.w(TAG, "Google Play Services not available for OTP auto-fill")
                    return
                }

                val task = SmsRetriever.getClient(context).startSmsRetriever()
                task.addOnSuccessListener {
                    Log.w(TAG, "SMS Retriever started successfully")
                    UltraForensicLogger.debug("OTP Auto-fill service started")
                }
                task.addOnFailureListener { e ->
                    Log.e(TAG, "Failed to start SMS Retriever: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting OTP auto-fill service", e)
            }
        }

        private fun isGooglePlayServicesAvailable(context: Context): Boolean {
            return try {
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
            } catch (e: Exception) {
                false
            }
        }
    }
}

class OtpSmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "OtpSmsReceiver"
        private const val ACTION_SMS_RETRIEVER = "com.google.android.gms.auth.api.phone.SMS_RETRIEVED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_RETRIEVER) {
            return
        }

        try {
            val extras = intent.extras
            if (extras != null && extras.containsKey(SmsRetriever.EXTRA_SMS_MESSAGE)) {
                val message = extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE) ?: return
                val otpCode = extractOtpFromMessage(message)
                if (otpCode.isNotEmpty()) {
                    copyOtpToClipboard(context, otpCode)
                    broadcastOtpForAutoFill(context, otpCode)
                    Log.w(TAG, "OTP auto-detected and prepared for auto-fill: $otpCode")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in OtpSmsReceiver: ${e.message}", e)
        }
    }

    private fun extractOtpFromMessage(message: String): String {
        return try {
            // Only match explicit OTP/Code keywords followed by digits
            val patterns = listOf(
                Regex("(?:your\\s+)?[Oo][Tt][Pp]\\s*[:\\-]\\s*([\\d\\s-]{4,10})\\b"),
                Regex("[Cc]ode\\s*[:\\-]\\s*([\\d\\s-]{4,10})\\b"),
                Regex("[Vv]erification\\s+[Cc]ode\\s*[:\\-]\\s*([\\d\\s-]{4,10})\\b"),
                Regex("[Vv]erify\\s+[Cc]ode\\s*[:\\-]\\s*([\\d\\s-]{4,10})\\b")
            )

            for (pattern in patterns) {
                val match = pattern.find(message)
                if (match != null) {
                    val code = match.groupValues[1].replace(Regex("[^\\d]"), "")
                    // Only return if exactly 4-6 digits and NOT a phone number pattern
                    if (code.length in 4..6 && !isPhoneNumberPattern(code, message)) {
                        return code
                    }
                }
            }
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting OTP", e)
            ""
        }
    }

    private fun isPhoneNumberPattern(code: String, message: String): Boolean {
        // Reject if code looks like part of a phone number
        return code.startsWith("18") || code.startsWith("91") ||
               message.contains("call.*$code".toRegex(RegexOption.IGNORE_CASE))
    }

    private fun copyOtpToClipboard(context: Context, otpCode: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("OTP", otpCode)
            clipboard.setPrimaryClip(clip)
            Log.w(TAG, "OTP auto-copied to clipboard: $otpCode")
        } catch (e: Exception) {
            Log.e(TAG, "Error copying OTP to clipboard", e)
        }
    }

    private fun broadcastOtpForAutoFill(context: Context, otpCode: String) {
        try {
            val intent = Intent("com.caller.OTP_AUTO_FILL_READY").apply {
                putExtra("otp_code", otpCode)
                `package` = context.packageName
            }
            context.sendBroadcast(intent)
            Log.w(TAG, "OTP broadcast sent for auto-fill")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting OTP", e)
        }
    }
}
