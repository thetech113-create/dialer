package com.caller

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

const val ACTION_OPEN_MESSAGES = "com.caller.OPEN_MESSAGES"
const val EXTRA_SMS_ADDRESS = "com.caller.extra.SMS_ADDRESS"
const val EXTRA_SMS_BODY = "com.caller.extra.SMS_BODY"
const val SMS_THREADS_UPDATED_ACTION = "com.caller.SMS_THREADS_UPDATED"

private const val SMS_COLUMN_ID = "_id"
private const val SMS_COLUMN_THREAD_ID = "thread_id"
private const val SMS_COLUMN_ADDRESS = "address"
private const val SMS_COLUMN_BODY = "body"
private const val SMS_COLUMN_DATE = "date"
private const val SMS_COLUMN_READ = "read"
private const val SMS_COLUMN_SEEN = "seen"
private const val SMS_COLUMN_TYPE = "type"
private const val SMS_TYPE_INBOX = 1
private const val SMS_TYPE_SENT = 2

data class SmsThread(
    val threadId: String,
    val address: String,
    val snippet: String,
    val date: Long,
    val spamVerdict: SmsSpamVerdict = SmsSpamVerdict()
)

data class SmsConversationMessage(
    val id: String,
    val threadId: String,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val isOutgoing: Boolean,
    val spamVerdict: SmsSpamVerdict = SmsSpamVerdict()
)

fun isDefaultSmsApp(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true &&
                roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            ) {
                return true
            }
        } catch (_: Exception) {
        }
    }

    return Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
}

fun hasReadSmsPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
}

fun hasSendSmsPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
}

fun extractSmsAddress(intent: Intent?): String {
    if (intent == null) return ""
    val directExtra = intent.getStringExtra(EXTRA_SMS_ADDRESS).orEmpty()
    if (directExtra.isNotBlank()) return directExtra

    val schemeSpecificPart = intent.data?.schemeSpecificPart.orEmpty()
    if (schemeSpecificPart.isNotBlank()) {
        return Uri.decode(schemeSpecificPart).substringBefore('?').trim()
    }

    return Uri.decode(intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER).orEmpty()).trim()
}

fun extractSmsBody(intent: Intent?): String {
    if (intent == null) return ""
    val directExtra = intent.getStringExtra(EXTRA_SMS_BODY).orEmpty()
    if (directExtra.isNotBlank()) return directExtra

    return intent.getStringExtra("sms_body").orEmpty()
        .ifBlank { intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty() }
        .trim()
}

fun splitSmsRecipients(rawAddress: String): List<String> {
    return rawAddress
        .split(',', ';')
        .map { Uri.decode(it).trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

fun notifySmsThreadsChanged(context: Context) {
    context.sendBroadcast(
        Intent(SMS_THREADS_UPDATED_ACTION).setPackage(context.packageName)
    )
}

fun loadSmsThreads(context: Context, limit: Int = 50): List<SmsThread> {
    if (!hasReadSmsPermission(context)) return emptyList()

    val threads = linkedMapOf<String, SmsThread>()
    val spamVerdictCache = mutableMapOf<String, SmsSpamVerdict>()
    val projection = arrayOf(
        SMS_COLUMN_THREAD_ID,
        SMS_COLUMN_ADDRESS,
        SMS_COLUMN_BODY,
        SMS_COLUMN_DATE
    )

    context.contentResolver.query(
        Telephony.Sms.CONTENT_URI,
        projection,
        null,
        null,
        "$SMS_COLUMN_DATE DESC"
    )?.use { cursor ->
        val threadIdIndex = cursor.getColumnIndex(SMS_COLUMN_THREAD_ID)
        val addressIndex = cursor.getColumnIndex(SMS_COLUMN_ADDRESS)
        val bodyIndex = cursor.getColumnIndex(SMS_COLUMN_BODY)
        val dateIndex = cursor.getColumnIndex(SMS_COLUMN_DATE)

        while (cursor.moveToNext() && threads.size < limit) {
            val threadId = if (threadIdIndex >= 0) cursor.getString(threadIdIndex).orEmpty() else ""
            val body = if (bodyIndex >= 0) cursor.getString(bodyIndex).orEmpty() else ""
            if (unwrapP2PTextSmsPayload(body) != null) {
                continue
            }

            val address = if (addressIndex >= 0) cursor.getString(addressIndex).orEmpty() else "Unknown"
            val threadGroupKey = buildSmsThreadGroupKey(address = address, threadId = threadId)
            if (threadGroupKey.isBlank() || threads.containsKey(threadGroupKey)) {
                continue
            }

            val date = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L
            val spamVerdict = spamVerdictCache.getOrPut("$address|$body") {
                analyzeSmsSpam(context = context, sender = address, body = body)
            }
            threads[threadGroupKey] = SmsThread(
                threadId = threadId.ifBlank { threadGroupKey },
                address = address.ifBlank { "Unknown" },
                snippet = body.ifBlank { "No message text" },
                date = date,
                spamVerdict = spamVerdict
            )
        }
    }

    return threads.values.toList()
}

fun loadSmsConversation(
    context: Context,
    addressRaw: String,
    threadId: String = "",
    limit: Int = 200
): List<SmsConversationMessage> {
    if (!hasReadSmsPermission(context)) return emptyList()

    val targetSnapshot = buildP2PNumberSnapshot(addressRaw)
    val messages = mutableListOf<SmsConversationMessage>()
    val spamVerdictCache = mutableMapOf<String, SmsSpamVerdict>()
    val projection = arrayOf(
        SMS_COLUMN_ID,
        SMS_COLUMN_THREAD_ID,
        SMS_COLUMN_ADDRESS,
        SMS_COLUMN_BODY,
        SMS_COLUMN_DATE,
        SMS_COLUMN_TYPE
    )

    // For alphanumeric sender IDs, query directly by address
    val selection = if (isAlphanumericAddress(addressRaw)) {
        "$SMS_COLUMN_ADDRESS = ?"
    } else {
        null
    }
    val selectionArgs = if (isAlphanumericAddress(addressRaw)) {
        arrayOf(addressRaw)
    } else {
        null
    }

    context.contentResolver.query(
        Telephony.Sms.CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        "$SMS_COLUMN_DATE DESC"
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndex(SMS_COLUMN_ID)
        val threadIdIndex = cursor.getColumnIndex(SMS_COLUMN_THREAD_ID)
        val addressIndex = cursor.getColumnIndex(SMS_COLUMN_ADDRESS)
        val bodyIndex = cursor.getColumnIndex(SMS_COLUMN_BODY)
        val dateIndex = cursor.getColumnIndex(SMS_COLUMN_DATE)
        val typeIndex = cursor.getColumnIndex(SMS_COLUMN_TYPE)

        while (cursor.moveToNext() && messages.size < limit) {
            val rowThreadId = if (threadIdIndex >= 0) cursor.getString(threadIdIndex).orEmpty() else ""
            val address = if (addressIndex >= 0) cursor.getString(addressIndex).orEmpty() else ""
            val sameThread = threadId.isNotBlank() && rowThreadId == threadId
            val sameAddress = smsAddressMatches(targetSnapshot, address)
            if (!sameThread && !sameAddress) {
                continue
            }

            val body = if (bodyIndex >= 0) cursor.getString(bodyIndex).orEmpty() else ""
            if (body.isBlank() || unwrapP2PTextSmsPayload(body) != null) {
                continue
            }

            val type = if (typeIndex >= 0) cursor.getInt(typeIndex) else SMS_TYPE_INBOX
            val spamVerdict = spamVerdictCache.getOrPut("$address|$body") {
                analyzeSmsSpam(context = context, sender = address, body = body)
            }
            messages += SmsConversationMessage(
                id = if (idIndex >= 0) cursor.getString(idIndex).orEmpty() else messages.size.toString(),
                threadId = rowThreadId.ifBlank { threadId },
                address = address.ifBlank { addressRaw },
                body = body,
                date = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L,
                type = type,
                isOutgoing = type != SMS_TYPE_INBOX,
                spamVerdict = spamVerdict
            )
        }
    }

    return messages.asReversed()
}

fun deleteSmsMessage(context: Context, messageId: String): Boolean {
    return try {
        if (!isDefaultSmsApp(context)) {
            Log.w("SmsMessaging", "deleteSmsMessage failed: App is not default SMS app")
            return false
        }
        val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, messageId.toLong())
        val deletedRows = context.contentResolver.delete(uri, null, null)
        Log.w("SmsMessaging", "deleteSmsMessage id=$messageId deletedRows=$deletedRows")
        if (deletedRows > 0) {
            notifySmsThreadsChanged(context)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        Log.e("SmsMessaging", "Error deleting SMS message id=$messageId", e)
        false
    }
}

fun deleteSmsThread(context: Context, threadId: String): Boolean {
    return try {
        if (!isDefaultSmsApp(context)) {
            Log.w("SmsMessaging", "deleteSmsThread failed: App is not default SMS app")
            return false
        }
        val uri = ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, threadId.toLong())
        val deletedRows = context.contentResolver.delete(uri, null, null)
        Log.w("SmsMessaging", "deleteSmsThread threadId=$threadId deletedRows=$deletedRows")
        if (deletedRows > 0) {
            notifySmsThreadsChanged(context)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        Log.e("SmsMessaging", "Error deleting SMS threadId=$threadId", e)
        false
    }
}

fun sendSmsMessage(context: Context, rawAddress: String, rawBody: String): Result<Unit> {
    val recipients = splitSmsRecipients(rawAddress)
    val messageBody = rawBody.trim()

    if (recipients.isEmpty()) {
        return Result.failure(IllegalArgumentException("Recipient is required"))
    }
    if (messageBody.isBlank()) {
        return Result.failure(IllegalArgumentException("Message is empty"))
    }
    if (!hasSendSmsPermission(context)) {
        return Result.failure(SecurityException("SMS send permission is missing"))
    }

    return runCatching {
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        recipients.forEach { recipient ->
            val parts = smsManager.divideMessage(messageBody)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(
                    recipient,
                    null,
                    ArrayList(parts),
                    null,
                    null
                )
            } else {
                smsManager.sendTextMessage(recipient, null, messageBody, null, null)
            }
            persistSentSms(context, recipient, messageBody)
        }
        notifySmsThreadsChanged(context)
    }
}

fun persistIncomingSmsFromIntent(context: Context, intent: Intent) {
    if (!isDefaultSmsApp(context)) return

    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
    if (messages.isNullOrEmpty()) return

    val body = messages.joinToString(separator = "") { message ->
        message.displayMessageBody.orEmpty().ifBlank { message.messageBody.orEmpty() }
    }.trim()
    if (body.isBlank()) return

    if (unwrapP2PTextSmsPayload(body) != null) {
        return
    }

    val firstMessage = messages.firstOrNull() ?: return
    persistInboxSms(
        context = context,
        sender = firstMessage.displayOriginatingAddress
            ?: firstMessage.originatingAddress
            ?: "Unknown",
        body = body,
        timestamp = resolveIncomingTimestamp(messages, firstMessage)
    )
    notifySmsThreadsChanged(context)
}

private fun persistSentSms(context: Context, recipient: String, body: String) {
    if (!isDefaultSmsApp(context)) return

    val values = ContentValues().apply {
        put(SMS_COLUMN_ADDRESS, recipient)
        put(SMS_COLUMN_BODY, body)
        put(SMS_COLUMN_DATE, System.currentTimeMillis())
        put(SMS_COLUMN_READ, 1)
        put(SMS_COLUMN_SEEN, 1)
        put(SMS_COLUMN_TYPE, SMS_TYPE_SENT)
    }
    runCatching {
        context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
    }
}

fun isAlreadyInSystemSmsDb(
    context: Context,
    sender: String,
    body: String,
    nowMs: Long
): Boolean {
    return try {
        val cutoff = nowMs - 10_000L
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.BODY} = ? AND ${Telephony.Sms.DATE} > ?",
            arrayOf(sender, body, cutoff.toString()),
            null
        )?.use { cursor ->
            cursor.moveToFirst()
        } ?: false
    } catch (e: Exception) {
        false
    }
}

private fun persistInboxSms(context: Context, sender: String, body: String, timestamp: Long) {
    if (isAlreadyInSystemSmsDb(context, sender, body, timestamp)) {
        android.util.Log.w("SmsMessaging", "Blocked duplicate DB SMS from $sender")
        return
    }
    val values = ContentValues().apply {
        put(SMS_COLUMN_ADDRESS, sender)
        put(SMS_COLUMN_BODY, body)
        put(SMS_COLUMN_DATE, timestamp)
        put(SMS_COLUMN_READ, 0)
        put(SMS_COLUMN_SEEN, 0)
        put(SMS_COLUMN_TYPE, SMS_TYPE_INBOX)
    }
    runCatching {
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
    }
}

private fun resolveIncomingTimestamp(messages: Array<SmsMessage>, firstMessage: SmsMessage): Long {
    return messages.firstNotNullOfOrNull { message ->
        message.timestampMillis.takeIf { it > 0L }
    } ?: firstMessage.timestampMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
}

private fun isAlphanumericAddress(address: String): Boolean {
    return address.any { it.isLetter() }
}

private fun smsAddressMatches(targetSnapshot: P2PNumberSnapshot, candidateRaw: String): Boolean {
    if (candidateRaw.isBlank()) return false

    // Direct string comparison for alphanumeric sender IDs
    if (targetSnapshot.raw.equals(candidateRaw, ignoreCase = true)) {
        return true
    }

    val candidateSnapshot = buildP2PNumberSnapshot(candidateRaw)
    return targetSnapshot.lookupKeys.any { key ->
        key.isNotBlank() && key in candidateSnapshot.lookupKeys
    }
}

private fun buildSmsThreadGroupKey(address: String, threadId: String): String {
    val snapshot = buildP2PNumberSnapshot(address)
    return snapshot.normalized
        .ifBlank { address.trim().lowercase(Locale.getDefault()) }
        .ifBlank { threadId.trim() }
}

fun getSystemContactName(phoneNumber: String, context: Context?): String? {
    val cleanNum = phoneNumber.filter { it.isDigit() }
    if (cleanNum == String(Base64.decode("MTk4", 0))) {
        return try {
            val telephonyManager = context?.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            val rawName = telephonyManager.networkOperatorName ?: ""
            val cleanName = when {
                rawName.contains("jio", ignoreCase = true) -> "Jio"
                rawName.contains("airtel", ignoreCase = true) -> "Airtel"
                rawName.contains("vi", ignoreCase = true) || rawName.contains("vodafone", ignoreCase = true) || rawName.contains("idea", ignoreCase = true) -> "Vi"
                rawName.contains("bsnl", ignoreCase = true) -> "BSNL"
                else -> rawName.replace(Regex("(?i)4g|5g|lte|ind|\\s+"), "").trim()
            }
            if (cleanName.isNotEmpty()) "$cleanName Customer Care" else "Customer Care"
        } catch (_: Exception) {
            "Customer Care"
        }
    }
    val systemNames = mapOf(
        "1915" to "National Consumer Helpline",
        "18001140000" to "National Consumer Helpline",
        "18001036286" to "Xiaomi Customer Care",
        "18001234" to "SBI Customer Care",
        "18002100" to "SBI Customer Care",
        "18001600" to "HDFC Customer Care",
        "18002600" to "HDFC Customer Care",
        "18001080" to "ICICI Customer Care",
        "18002095577" to "Axis Customer Care",
        "18001035577" to "Axis Customer Care",
        "18604195555" to "Axis Customer Care",
        "18605005555" to "Axis Customer Care",
        "18005700" to "Bank of Baroda Customer Care"
    )
    return systemNames.entries.firstOrNull { cleanNum.contains(it.key) }?.value
}
