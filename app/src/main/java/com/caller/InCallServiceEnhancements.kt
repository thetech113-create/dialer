package com.caller

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Comprehensive fixes for OEM dialer fallback issues
 * Addresses Root Causes #1, #2, #3, #4, #10, #15
 */
object InCallServiceEnhancements {

    private const val TAG = "InCallServiceFixes"
    const val PROCESS_WARMUP_CHANNEL_ID = "process_warmup_channel"
    private const val PERSISTENT_SERVICE_CHANNEL_ID = "persistent_service_channel"
    private var persistentWakeLock: PowerManager.WakeLock? = null
    private var isProcessWarmedUp = false

    /**
     * ROOT CAUSE #2 FIX: Process warming to prevent cold start timeouts
     * Call this immediately when InCallService starts
     */
    fun warmUpProcess(context: Context) {
        if (isProcessWarmedUp) return
        
        try {
            // No warm-up foreground service here: this runs from onCallAdded, when the in-call service is
            // already foreground with the call notification. Its own "Call Service Ready" notification showed
            // up as a second, different notification during the first call after the app started.

            // Acquire partial wake lock to prevent deep sleep during critical periods
            acquireProcessWakeLock(context)
            
            isProcessWarmedUp = true
            Log.w(TAG, "Process warming completed - OEM cold start timeout mitigation active")
        } catch (e: Exception) {
            Log.e(TAG, "Process warming failed", e)
        }
    }

    /**
     * ROOT CAUSE #3 & #4 FIX: Enhanced notification with FullScreenIntent priority
     * Addresses OEM background activity blocking and lock screen issues
     */
    fun createCriticalIncomingCallNotification(
        context: Context,
        phoneNumber: String,
        contactName: String,
        callActivityIntent: Intent
    ): Notification {
        val notificationManager = NotificationManagerCompat.from(context)
        
        // Ensure high priority channel exists
        createCriticalCallChannel(context)
        
        // Enhanced FullScreenIntent with maximum priority flags
        val fullScreenIntent = callActivityIntent.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_FROM_BACKGROUND
        }
        
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 
            0, 
            fullScreenIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CRITICAL_CALL_CHANNEL_ID)
            .setContentTitle("Incoming Call")
            .setContentText(contactName.ifBlank { phoneNumber })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true) // CRITICAL: Must be true
            .setOngoing(true)
            .setAutoCancel(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 1000, 1000, 1000))
            .setSound(null) // Let InCallService handle ringing
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
    }

    /**
     * ROOT CAUSE #10 FIX: Battery optimization whitelist verification
     */
    fun ensureBatteryOptimizationWhitelist(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = context.packageName
            val isIgnoring = powerManager.isIgnoringBatteryOptimizations(packageName)
            
            if (!isIgnoring) {
                Log.w(TAG, "Battery optimization not whitelisted - OEM Doze mode fallback risk")
                // Only reported here. The exemption is asked for from the app's own screens (MainActivity and its Settings
                // card): starting the system screen from a call would put it in front of the incoming call.
            }
            
            isIgnoring
        } catch (e: Exception) {
            Log.e(TAG, "Battery optimization check failed", e)
            false
        }
    }

    /**
     * ROOT CAUSE #5 FIX: Default dialer role verification with race condition prevention
     */
    fun verifyDefaultDialerRole(context: Context): Boolean {
        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val defaultDialer = telecomManager.defaultDialerPackage
            val isDefault = defaultDialer == context.packageName
            
            Log.w(TAG, "Default dialer verification: current=$defaultDialer isDefault=$isDefault")
            
            if (!isDefault) {
                Log.e(TAG, "RACE CONDITION DETECTED: Default dialer role lost during call setup")
            }
            
            isDefault
        } catch (e: Exception) {
            Log.e(TAG, "Default dialer verification failed", e)
            false
        }
    }

    /**
     * ROOT CAUSE #15 FIX: System UI takeover prevention
     * Ensures UI is shown within Telecom's strict window
     */
    fun guaranteeUiDisplay(context: Context, callActivityIntent: Intent) {
        try {
            // Method 1: Direct activity start with background permission
            if (Build.VERSION.SDK_INT >= 34) { // UPSIDE_DOWN_CAKE = 34
                val options = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    options.pendingIntentBackgroundActivityStartMode = 
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                context.startActivity(callActivityIntent, options.toBundle())
            } else {
                context.startActivity(callActivityIntent)
            }
            
            // MyInCallService already owns the real call notification/foreground entry.
            // Posting a second backup notification here creates an unwanted duplicate
            // swipe-away "Incoming Call" card.
            Log.w(TAG, "UI start requested (direct launch only); whether the screen became visible is recorded by incoming_ui_visibility_check")
            
        } catch (e: Exception) {
            Log.e(TAG, "UI display guarantee failed", e)
        }
    }

    private fun createProcessWarmupChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PROCESS_WARMUP_CHANNEL_ID,
                "Process Warmup",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps dialer process ready for incoming calls"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createCriticalCallChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CRITICAL_CALL_CHANNEL_ID,
                "Critical Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming call notifications with maximum priority"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(true)
                enableVibration(true)
                setBypassDnd(true)
            }
            
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireProcessWakeLock(context: Context) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            persistentWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CallerApp:ProcessWarmup"
            ).apply {
                setReferenceCounted(false)
                // Long enough for Telecom to bind the in-call service for a call that is starting; it was 10 minutes,
                // also at every boot and phone-state change, which kept the phone awake for nothing (battery).
                acquire(60 * 1000L)
            }
            Log.w(TAG, "Process wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Wake lock acquisition failed", e)
        }
    }

    fun releaseProcessWakeLock() {
        persistentWakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.w(TAG, "Process wake lock released")
            }
        }
        persistentWakeLock = null
    }

    private const val CRITICAL_CALL_CHANNEL_ID = "critical_call_channel"
}

/**
 * Short-lived service used to warm the process before call handling ramps up.
 */
class ProcessWarmupService : Service() {
    private val stopHandler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createWarmupNotification()
        stopHandler.removeCallbacks(stopRunnable)
        stopHandler.postDelayed(stopRunnable, 3_000L)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        stopHandler.removeCallbacks(stopRunnable)
        super.onDestroy()
    }

    private fun createWarmupNotification() {
        val notification = NotificationCompat.Builder(this, InCallServiceEnhancements.PROCESS_WARMUP_CHANNEL_ID)
            .setContentTitle("Call Service Ready")
            .setContentText("Prepared for incoming calls")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()

        if (Build.VERSION.SDK_INT >= 35) {  // Android 15+ (API 35)
            startForeground(
                8888,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {  // API 34
            startForeground(
                8888,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else {
            startForeground(8888, notification)
        }
    }
}

