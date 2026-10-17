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
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.caller.logging.UltraForensicLogger
import java.util.regex.Pattern

class SmsDeliverReceiver : BroadcastReceiver() {
    companion object {
        private const val SMS_NOTIFICATION_CHANNEL_ID = "sms_notifications"
        private const val TAG = "SmsDeliverReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        UltraForensicLogger.debug("=== SmsDeliverReceiver Triggered ===")
        UltraForensicLogger.debug("Action: ${intent.action}")

        val action = intent.action
        if (action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            return
        }

        persistIncomingSmsFromIntent(context, intent)
        runCatching {
            val relayIntent = Intent(intent).apply {
                setAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            }
            CallerIdReceiver().onReceive(context, relayIntent)
        }.onFailure { error ->
            Log.e(TAG, "Failed to relay SMS_DELIVER to CallerIdReceiver: ${error.message}", error)
        }

        try {
            val bundle = intent.extras ?: return
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            val format = bundle.getString("format")

            for (pdu in pdus) {
                if (pdu == null) continue

                try {
                    val message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        SmsMessage.createFromPdu(pdu as ByteArray, format)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsMessage.createFromPdu(pdu as ByteArray)
                    }

                    val senderNumber = message.originatingAddress ?: continue
                    val messageBody = message.messageBody ?: continue
                    val timestamp = message.timestampMillis

                    Log.w(TAG, "SMS received from: $senderNumber, body: ${messageBody.take(50)}")

                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_CONTACTS
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        val contactName = getContactName(context, senderNumber)
                        showSmsNotification(
                            context,
                            senderNumber,
                            contactName,
                            messageBody,
                            timestamp
                        )
                    } else {
                        showSmsNotification(context, senderNumber, senderNumber, messageBody, timestamp)
                    }

                    storeSmsMessage(context, senderNumber, messageBody, timestamp, false)

                    broadcastSmsUpdate(context, senderNumber)
                } catch (e: Exception) {
                    UltraForensicLogger.deepest("Error processing SMS: ${e.message}")
                    Log.e(TAG, "Error processing SMS message", e)
                }
            }
        } catch (e: Exception) {
            UltraForensicLogger.deepest("Error in SmsDeliverReceiver: ${e.message}")
            Log.e(TAG, "Error in SmsDeliverReceiver", e)
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

    private fun showSmsNotification(
        context: Context,
        phoneNumber: String,
        contactName: String,
        messageBody: String,
        timestamp: Long
    ) {
        try {
            // Create channel immediately (fixes first notification not showing)
            createNotificationChannel(context)

            val notificationId = phoneNumber.hashCode()
            val notificationText = if (messageBody.length > 100) {
                messageBody.take(100) + "..."
            } else {
                messageBody
            }

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

            val builder = NotificationCompat.Builder(context, SMS_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle(contactName)
                .setContentText(notificationText)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(messageBody)
                )
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(longArrayOf(0, 500))
                .setLights(-0x1, 500, 500)

            val otpCode = extractOtpCode(messageBody)
            if (otpCode.isNotEmpty()) {
                val copyIntent = Intent(context, CopyOtpReceiver::class.java).apply {
                    action = "com.caller.COPY_OTP"
                    putExtra("otp_code", otpCode)
                }
                val copyPendingIntent = PendingIntent.getBroadcast(
                    context,
                    notificationId + 1000,
                    copyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    android.R.drawable.ic_menu_edit,
                    "Copy Code",
                    copyPendingIntent
                )
                Log.w(TAG, "OTP detected and copy button added: $otpCode")
            }

            val notification = builder.build()

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify("sms_$phoneNumber", notificationId, notification)

            Log.w(TAG, "SMS notification shown for $contactName")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing SMS notification", e)
        }
    }

    private fun extractOtpCode(messageBody: String): String {
        return try {
            // Only match explicit OTP/Code keywords followed by digits
            // Reject if it looks like a phone number (too many digits or preceded by common phone patterns)
            val patterns = listOf(
                Pattern.compile("(?:your\\s+)?[Oo][Tt][Pp]\\s*[:\\-]\\s*([\\d\\s-]{4,10})\\b"),
                Pattern.compile("[Cc]ode\\s*[:\\-]\\s*([\\d\\s-]{4,10})\\b"),
                Pattern.compile("[Vv]erification\\s+[Cc]ode\\s*[:\\-]\\s*([\\d\\s-]{4,10})\\b"),
                Pattern.compile("[Vv]erify\\s+[Cc]ode\\s*[:\\-]\\s*([\\d\\s-]{4,10})\\b")
            )

            for (pattern in patterns) {
                val matcher = pattern.matcher(messageBody)
                if (matcher.find()) {
                    val code = matcher.group(1)?.replace(Regex("[^\\d]"), "") ?: ""
                    // Only return if exactly 4-6 digits and NOT preceded by country/area code patterns
                    if (code.length in 4..6 && !isPhoneNumberPattern(code, messageBody)) {
                        return code
                    }
                }
            }
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting OTP code", e)
            ""
        }
    }

    private fun isPhoneNumberPattern(code: String, messageBody: String): Boolean {
        // Reject if code looks like part of a phone number
        return code.startsWith("18") || code.startsWith("91") ||
               messageBody.contains("call.*$code".toRegex(RegexOption.IGNORE_CASE))
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SMS_NOTIFICATION_CHANNEL_ID,
                "SMS Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming SMS messages"
                enableVibration(true)
                enableLights(true)
            }

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun storeSmsMessage(
        context: Context,
        address: String,
        body: String,
        timestamp: Long,
        isOutgoing: Boolean
    ) {
        try {
            val hasWriteSmsPermission = ContextCompat.checkSelfPermission(
                context,
                "android.permission.WRITE_SMS"
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasWriteSmsPermission) {
                Log.w(TAG, "Missing WRITE_SMS permission, cannot store message")
                return
            }

            val values = android.content.ContentValues().apply {
                put("address", address)
                put("body", body)
                put("date", timestamp)
                put("type", if (isOutgoing) 2 else 1)
                put("read", 0)
                put("seen", 0)
            }

            context.contentResolver.insert(
                Telephony.Sms.CONTENT_URI,
                values
            )

            Log.w(TAG, "SMS message stored: from $address")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing SMS message", e)
        }
    }

    private fun broadcastSmsUpdate(context: Context, phoneNumber: String) {
        try {
            val updateIntent = Intent(SMS_THREADS_UPDATED_ACTION).apply {
                `package` = context.packageName
                putExtra(EXTRA_SMS_ADDRESS, phoneNumber)
                putExtra("timestamp", System.currentTimeMillis())
            }
            context.sendBroadcast(updateIntent)
            Log.w(TAG, "SMS update broadcast sent for $phoneNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting SMS update", e)
        }
    }
}
