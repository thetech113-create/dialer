package com.caller

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.caller.logging.UltraForensicLogger

class MmsDeliverReceiver : BroadcastReceiver() {
    companion object {
        private const val MMS_NOTIFICATION_CHANNEL_ID = "mms_notifications"
        private const val TAG = "MmsDeliverReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        UltraForensicLogger.debug("=== MmsDeliverReceiver Triggered ===")
        UltraForensicLogger.debug("Action: ${intent.action}")

        val action = intent.action
        if (action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) {
            return
        }

        notifySmsThreadsChanged(context)

        try {
            val bundle = intent.extras ?: return
            val senderNumber = bundle.getString("sender") ?: bundle.getString("from") ?: return

            Log.w(TAG, "MMS received from: $senderNumber")

            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val contactName = getContactName(context, senderNumber)
                showMmsNotification(context, senderNumber, contactName)
            } else {
                showMmsNotification(context, senderNumber, senderNumber)
            }

            broadcastMmsUpdate(context, senderNumber)
        } catch (e: Exception) {
            UltraForensicLogger.deepest("Error in MmsDeliverReceiver: ${e.message}")
            Log.e(TAG, "Error in MmsDeliverReceiver", e)
        }
    }

    private fun getContactName(context: Context, phoneNumber: String): String {
        return try {
            val uri = android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(android.net.Uri.encode(phoneNumber))
                .build()

            context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    phoneNumber
                }
            } ?: phoneNumber
        } catch (_: Exception) {
            phoneNumber
        }
    }

    private fun showMmsNotification(
        context: Context,
        phoneNumber: String,
        contactName: String
    ) {
        try {
            createNotificationChannel(context)

            val notificationId = phoneNumber.hashCode() + 1000
            val messageText = "Media message"

            val intent = Intent(context, MessagingActivity::class.java).apply {
                action = ACTION_OPEN_MESSAGES
                putExtra(EXTRA_SMS_ADDRESS, phoneNumber)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, MMS_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle(contactName)
                .setContentText(messageText)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(longArrayOf(0, 500))
                .setLights(-0x1, 500, 500)
                .build()

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify("mms_$phoneNumber", notificationId, notification)

            Log.w(TAG, "MMS notification shown for $contactName")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing MMS notification", e)
        }
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MMS_NOTIFICATION_CHANNEL_ID,
                "MMS Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming MMS messages"
                enableVibration(true)
                enableLights(true)
            }

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun broadcastMmsUpdate(context: Context, phoneNumber: String) {
        try {
            val updateIntent = Intent(SMS_THREADS_UPDATED_ACTION).apply {
                `package` = context.packageName
                putExtra(EXTRA_SMS_ADDRESS, phoneNumber)
                putExtra("timestamp", System.currentTimeMillis())
                putExtra("isMms", true)
            }
            context.sendBroadcast(updateIntent)
            Log.w(TAG, "MMS update broadcast sent for $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting MMS update", e)
        }
    }
}
