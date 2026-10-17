package com.caller

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val SMS_ROLE_TAG = "SMS_ROLE"

class ComposeSmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forwardToMessaging(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        forwardToMessaging(intent)
    }

    private fun forwardToMessaging(sourceIntent: Intent?) {
        val launchIntent = Intent(this, MessagingActivity::class.java).apply {
            action = ACTION_OPEN_MESSAGES
            putExtra(EXTRA_SMS_ADDRESS, extractSmsAddress(sourceIntent))
            putExtra(EXTRA_SMS_BODY, extractSmsBody(sourceIntent))
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        startActivity(launchIntent)
        finish()
    }
}



class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = extractSmsAddress(intent)
        val body = extractSmsBody(intent)

        if (address.isBlank() || body.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        CoroutineScope(Dispatchers.IO).launch {
            val result = sendSmsMessage(this@HeadlessSmsSendService, address, body)
            if (result.isFailure) {
                Log.e(
                    SMS_ROLE_TAG,
                    "Headless SMS send failed: ${result.exceptionOrNull()?.message}",
                    result.exceptionOrNull()
                )
            }
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }
}
