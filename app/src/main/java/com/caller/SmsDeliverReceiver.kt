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
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

class SmsDeliverReceiver : BroadcastReceiver() {
    companion object {
        private const val SMS_NOTIFICATION_CHANNEL_ID = "sms_notifications"
        private const val TAG = "SmsDeliverReceiver"
        private const val DEDUP_WINDOW_MS = 10_000L
        private val recentSmsCache = ConcurrentHashMap<String, Long>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        UltraForensicLogger.debug("=== SmsDeliverReceiver Triggered ===")
        UltraForensicLogger.debug("Action: ${intent.action}")

        // 1. CRITICAL: Only handle SMS_DELIVER_ACTION
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            return
        }

        // 2. Verify this app is currently the Default SMS App before processing
        if (!isDefaultSmsApp(context)) {
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // 3. Combine all PDUs into ONE single message (fixes multi-part Sender ID messages)
        val firstMessage = messages.first()
        val sender = firstMessage.displayOriginatingAddress
            ?: firstMessage.originatingAddress
            ?: return
        val fullBody = messages.joinToString(separator = "") {
            it.displayMessageBody.orEmpty().ifBlank { it.messageBody.orEmpty() }
        }.trim()
        if (fullBody.isEmpty()) return

        val timestamp = firstMessage.timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        val now = System.currentTimeMillis()

        // 4. In-memory deduplication guard (blocks double-broadcasts within 10 seconds)
        val signature = "${sender.trim().uppercase()}|${fullBody.hashCode()}"
        val previousTime = recentSmsCache.put(signature, now)
        if (previousTime != null && (now - previousTime) < DEDUP_WINDOW_MS) {
            Log.w(TAG, "Blocked duplicate in-memory SMS from $sender")
            return
        }
        recentSmsCache.entries.removeIf { now - it.value > 60_000L }

        // 5. Store message into system SMS database (with DB deduplication check)
        persistIncomingSmsFromIntent(context, intent)

        // 6. Relay to CallerIdReceiver for P2P Caller ID processing
        runCatching {
            val relayIntent = Intent(intent).apply {
                setAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            }
            CallerIdReceiver().onReceive(context, relayIntent)
        }.onFailure { error ->
            Log.e(TAG, "Failed to relay SMS_DELIVER to CallerIdReceiver: ${error.message}", error)
        }

        // 7. Show single consolidated Notification
        try {
            val contactName = if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CONTACTS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                getContactName(context, sender)
            } else {
                sender
            }

            showSmsNotification(
                context,
                sender,
                contactName,
                fullBody,
                timestamp
            )

            broadcastSmsUpdate(context, sender)
        } catch (e: Exception) {
            UltraForensicLogger.deepest("Error processing SMS: ${e.message}")
            Log.e(TAG, "Error processing SMS notification", e)
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

            val spamVerdict = analyzeSmsSpam(context, phoneNumber, messageBody)
            val finalTitle = if (spamVerdict.isPotentialSpam) "⚠️ Spam Warning: $contactName" else contactName
            val finalNotificationText = if (spamVerdict.isPotentialSpam) "[Spam Warning] $notificationText" else notificationText

            val builder = NotificationCompat.Builder(context, SMS_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle(finalTitle)
                .setContentText(finalNotificationText)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(if (spamVerdict.isPotentialSpam) "${spamVerdict.summary}\n\n$messageBody" else messageBody)
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
