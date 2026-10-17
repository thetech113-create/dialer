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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import com.caller.logging.UltraForensicLogger
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * CallStateResumeService
 * Small foreground service used after device unlock to resume call state
 * from the pending call state store and transition from DPS to CPS.
 */
class CallStateResumeService : Service() {
    companion object {
        private const val TAG = "CallStateResumeService"
    }

    private var appCtx: Context? = null

    override fun onCreate() {
        super.onCreate()
        appCtx = applicationContext
        // Initialize UltraForensicLogger
        UltraForensicLogger.init(this)
        UltraForensicLogger.info("CallStateResumeService started")
        ensureForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val ctx = appCtx ?: this
            val phoneNumber = PendingCallStateStore.getPhoneNumber(ctx)
            
            if (phoneNumber != null) {
                // GHOST KILLER: Double check with TelecomManager before resuming a potentially dead state
                val telecomManager = ctx.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                
                @Suppress("MissingPermission")
                val isActuallyInCall = try { telecomManager.isInCall } catch (e: Exception) { false }

                if (isActuallyInCall) {
                    UltraForensicLogger.forensic("CallStateResumeService", "VALID_RESUME", "Telecom confirms active call. Resuming state.")
                    CallStateManager.resume(ctx, phoneNumber)
                } else {
                    UltraForensicLogger.ultra("CallStateResumeService", "GHOST_BLOCKED", "Telecom reports NO call. Purging stale pending state.")
                }
                
                // ALWAYS clear the pending state so it can never be read a second time
                PendingCallStateStore.clear(ctx)
                DirectBootCallManager.removeCallStateFromDirectBootStorage(ctx)
            }
        } catch (e: Exception) {
            UltraForensicLogger.deepest("CallStateResumeService", "RESUME_ERROR", "${e.message}")
        } finally {
            stopForeground(true)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForeground() {
        val channelId = "ongoing_call_channel_v3"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Remove channel creation - use channel created by MyInCallService

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Restoring call state…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        // Match the manifest-declared FGS type on Android 10+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1004, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(1004, notification)
        }
    }
}
