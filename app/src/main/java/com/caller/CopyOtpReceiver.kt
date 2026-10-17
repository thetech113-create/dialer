package com.caller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import android.widget.Toast
import com.caller.logging.UltraForensicLogger

class CopyOtpReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CopyOtpReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        UltraForensicLogger.debug("=== CopyOtpReceiver Triggered ===")

        if (intent.action != "com.caller.COPY_OTP") {
            return
        }

        try {
            val otpCode = intent.getStringExtra("otp_code") ?: return

            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OTP", otpCode)
            clipboard.setPrimaryClip(clip)

            Log.w(TAG, "OTP code copied to clipboard: $otpCode")
            Toast.makeText(context, "OTP copied: $otpCode", Toast.LENGTH_SHORT).show()

            UltraForensicLogger.debug("OTP code successfully copied to clipboard")
        } catch (e: Exception) {
            Log.e(TAG, "Error copying OTP code", e)
            UltraForensicLogger.deepest("Error in CopyOtpReceiver: ${e.message}")
        }
    }
}
