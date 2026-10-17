package com.caller

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.telecom.Call
import android.util.Log
import androidx.core.app.NotificationCompat

object ForegroundServiceFixes {

    private const val TAG = "ForegroundServiceFixes"
    private const val CALL_SERVICE_CHANNEL_ID = "call_service_channel"
    private var isForegroundServiceStarted = false

    fun safeStartForegroundService(context: Context, call: Call) {
        if (isForegroundServiceStarted) return

        try {
            createNotificationChannel(context)
            createMinimalCallNotification(context, call)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(
                    android.content.Intent(context, MyInCallService::class.java).apply {
                        action = "START_FOREGROUND_SERVICE"
                        putExtra("CALL_ID", call.details.handle?.schemeSpecificPart ?: "")
                    }
                )
            } else {
                context.startService(
                    android.content.Intent(context, MyInCallService::class.java)
                )
            }

            isForegroundServiceStarted = true
            Log.w(TAG, "Foreground service started safely")

        } catch (e: Exception) {
            Log.e(TAG, "Foreground service blocked by OS. Using normal UI fallback.", e)
            startCallActivityDirectly(context, call)
        }
    }

    fun delayedForegroundServiceStart(context: Context, call: Call, delayMs: Long = 1000) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isForegroundServiceStarted) {
                safeStartForegroundService(context, call)
            }
        }, delayMs)
    }

    private fun startCallActivityDirectly(context: Context, call: Call) {
        try {
            val phoneNumber = call.details.handle?.schemeSpecificPart ?: "Unknown"
            val stateHint = if (call.state == Call.STATE_RINGING) "RINGING" else "DIALING"

            val intent = android.content.Intent(context, CallActivity::class.java).apply {
                action = CALL_UI_ACTION_WAKE
                putExtra("WAKE_REASON", "fgs_blocked_fallback")
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

            if (context is Activity) {
                context.startActivity(intent)
            } else {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Activity fallback failed", e)
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CALL_SERVICE_CHANNEL_ID,
                "Call Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Handles ongoing calls"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createMinimalCallNotification(context: Context, call: Call): Notification {
        val phoneNumber = call.details.handle?.schemeSpecificPart ?: "Unknown"

        return NotificationCompat.Builder(context, CALL_SERVICE_CHANNEL_ID)
            .setContentTitle("Ongoing Call")
            .setContentText("Call with $phoneNumber")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun onForegroundServiceDestroyed() {
        isForegroundServiceStarted = false
    }

    fun canStartForegroundService(context: Context): Boolean {
        return true
    }
}
