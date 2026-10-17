package com.caller

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.caller.logging.UltraForensicLogger

class SmsMonitoringService : Service() {
    companion object {
        private const val TAG = "SmsMonitoringService"

        fun startService(context: android.content.Context) {
            try {
                val intent = Intent(context, SmsMonitoringService::class.java)
                context.startService(intent)
                Log.w(TAG, "SMS Monitoring Service started")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting SMS Monitoring Service", e)
            }
        }

        fun stopService(context: android.content.Context) {
            try {
                val intent = Intent(context, SmsMonitoringService::class.java)
                context.stopService(intent)
                Log.w(TAG, "SMS Monitoring Service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping SMS Monitoring Service", e)
            }
        }
    }

    private var smsDeliverReceiver: SmsDeliverReceiver? = null
    private var mmsDeliverReceiver: MmsDeliverReceiver? = null

    override fun onCreate() {
        super.onCreate()
        UltraForensicLogger.debug("SMS Monitoring Service created")
        Log.w(TAG, "Service onCreate called")
        registerReceivers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        UltraForensicLogger.debug("SMS Monitoring Service started")
        Log.w(TAG, "Service onStartCommand called")

        if (intent == null) {
            registerReceivers()
        }

        return START_STICKY_COMPATIBILITY
    }

    override fun onDestroy() {
        super.onDestroy()
        UltraForensicLogger.debug("SMS Monitoring Service destroyed")
        Log.w(TAG, "Service onDestroy called")
        unregisterReceivers()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun registerReceivers() {
        try {
            smsDeliverReceiver = SmsDeliverReceiver()
            mmsDeliverReceiver = MmsDeliverReceiver()

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val smsFilter = IntentFilter(Telephony.Sms.Intents.SMS_DELIVER_ACTION).apply {
                    priority = 1000
                }
                registerReceiver(smsDeliverReceiver, smsFilter, Manifest.permission.RECEIVE_SMS, null)
                Log.w(TAG, "SMS Deliver receiver registered")
            }

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECEIVE_MMS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val mmsFilter = IntentFilter(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).apply {
                    addDataType("application/vnd.wap.mms-message")
                    priority = 1000
                }
                registerReceiver(mmsDeliverReceiver, mmsFilter, Manifest.permission.RECEIVE_MMS, null)
                Log.w(TAG, "MMS Deliver receiver registered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receivers", e)
        }
    }

    private fun unregisterReceivers() {
        try {
            smsDeliverReceiver?.let {
                unregisterReceiver(it)
                Log.w(TAG, "SMS Deliver receiver unregistered")
            }
            mmsDeliverReceiver?.let {
                unregisterReceiver(it)
                Log.w(TAG, "MMS Deliver receiver unregistered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers", e)
        }
    }
}
