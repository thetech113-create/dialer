package com.caller

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.caller.link.Identity
import com.caller.link.LinkServer
import com.caller.link.Proto
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Keeps the link to the PC alive in the foreground: runs the [LinkServer], forwards calls,
 * texts and battery state, and carries out the PC's requests.
 */
@SuppressLint("MissingPermission")
class LinkService : Service(), LinkServer.Listener {
    private lateinit var prefs: LinkPrefs
    private val main = Handler(Looper.getMainLooper())
    private var server: LinkServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var receiversRegistered = false
    private var ringtone: Ringtone? = null
    private val callAudio by lazy {
        CallAudio(this, send = { LinkState.send(it) }, sendFrame = { LinkState.sendDroppable(it) })
    }

    private var callState = CALL_IDLE
    private var callNumber = ""
    private var battery = -1
    private var charging = false

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return) {
                TelephonyManager.EXTRA_STATE_RINGING -> CALL_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> CALL_OFFHOOK
                else -> CALL_IDLE
            }
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
            if (state == callState && number.isEmpty()) return
            if (state == CALL_IDLE) callNumber = "" else if (number.isNotEmpty()) callNumber = number
            if (state == CALL_IDLE && callState != CALL_IDLE) thread { callAudio.stop() }
            callState = state
            sendCall()
        }
    }

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (parts.isNullOrEmpty()) return
            val address = parts[0].originatingAddress.orEmpty()
            LinkState.send(
                JSONObject().put("t", "sms")
                    .put("address", address)
                    .put("name", contactName(address))
                    .put("body", parts.joinToString("") { it.messageBody.orEmpty() })
                    .put("time", parts[0].timestampMillis)
                    .put("type", "in")
            )
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (percent == battery && plugged == charging) return
            battery = percent
            charging = plugged
            sendStatus()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = LinkPrefs(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.autoStart = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAIR_ACCEPT -> LinkState.pairRequest?.answer?.complete(true)
            ACTION_PAIR_DECLINE -> LinkState.pairRequest?.answer?.complete(false)
        }
        if (server == null) startServer()
        return START_STICKY
    }

    private fun enterForeground(): Boolean {
        val plain = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val canRecord = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (canRecord) {
            try {
                ServiceCompat.startForeground(
                    this, STATUS_NOTIFICATION_ID, statusNotification(),
                    plain or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                return true
            } catch (_: RuntimeException) {
            }
        }
        return try {
            ServiceCompat.startForeground(this, STATUS_NOTIFICATION_ID, statusNotification(), plain)
            true
        } catch (e: RuntimeException) {
            LinkState.error = "Android did not allow the link to start: ${e.message}"
            false
        }
    }

    override fun onDestroy() {
        callAudio.stop()
        if (receiversRegistered) {
            try {
                unregisterReceiver(phoneReceiver)
                unregisterReceiver(smsReceiver)
                unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {}
        }
        LinkState.pairRequest?.answer?.complete(false)
        val stopping = server
        server = null
        LinkState.server = null
        LinkState.running = false
        LinkState.connectedPc = null
        LinkState.connectedPcId = null
        if (stopping != null) thread { stopping.stop() }
        multicastLock?.let { if (it.isHeld) it.release() }
        ringtone?.stop()
        super.onDestroy()
    }

    private fun startServer() {
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        val created = LinkServer(Identity(prefs.deviceId, name), prefs, this)
        try {
            created.start()
        } catch (e: IOException) {
            LinkState.error = "Could not open port ${Proto.TCP_PORT}: ${e.message}"
            stopSelf()
            return
        }
        server = created
        LinkState.server = created
        LinkState.running = true
        LinkState.error = null

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("phon-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }

        val exported = ContextCompat.RECEIVER_EXPORTED
        ContextCompat.registerReceiver(
            this, phoneReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), exported,
        )
        ContextCompat.registerReceiver(
            this, smsReceiver, IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
            Manifest.permission.BROADCAST_SMS, null, exported,
        )
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), exported,
        )
        receiversRegistered = true
    }

    override fun onPairRequest(pcName: String, sas: String): Boolean {
        val request = PcPairRequest(pcName, sas)
        synchronized(this) {
            if (LinkState.pairRequest != null) return false
            LinkState.pairRequest = request
        }
        notifications().notify(PAIR_NOTIFICATION_ID, pairNotification(request))
        return try {
            request.answer.get(PAIR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        } finally {
            LinkState.pairRequest = null
            notifications().cancel(PAIR_NOTIFICATION_ID)
        }
    }

    override fun onConnected(pcId: String, pcName: String) {
        LinkState.connectedPc = pcName
        LinkState.connectedPcId = pcId
        LinkState.pairingsVersion++
        refreshStatusNotification()
        sendStatus()
        if (callState != CALL_IDLE) sendCall()
        NotifListener.instance?.sendActive()
    }

    override fun onDisconnected() {
        callAudio.stop()
        LinkState.connectedPc = null
        LinkState.connectedPcId = null
        refreshStatusNotification()
    }

    override fun onMessage(msg: JSONObject) {
        val op = msg.optString("t")
        try {
            when (op) {
                "audio" -> callAudio.play(Base64.decode(msg.getString("d"), Base64.DEFAULT))
                "audio_start" -> {
                    val error = callAudio.start(msg.optString("source", "recognition"), msg.optString("usage"))
                    result(op, error == null, error)
                }
                "audio_stop" -> callAudio.stop()
                "get_sms" -> LinkState.send(
                    JSONObject().put("t", "sms_list").put("items", readSms(limit(msg)))
                )
                "get_contacts" -> LinkState.send(
                    JSONObject().put("t", "contacts").put("items", readContacts())
                )
                "get_call_log" -> LinkState.send(
                    JSONObject().put("t", "call_log").put("items", readCallLog(limit(msg)))
                )
                "send_sms" -> {
                    sendSms(msg.getString("address"), msg.getString("body"))
                    result(op, true)
                }
                "answer" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        @Suppress("DEPRECATION")
                        telecom().acceptRingingCall()
                        result(op, true)
                    } else {
                        result(op, false, "Answering needs Android 8 or newer")
                    }
                }
                "hangup" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        @Suppress("DEPRECATION")
                        result(op, telecom().endCall())
                    } else {
                        result(op, false, "Hanging up needs Android 9 or newer")
                    }
                }
                "dial" -> {
                    telecom().placeCall(Uri.fromParts("tel", msg.getString("number"), null), Bundle())
                    result(op, true)
                }
                "dismiss" -> NotifListener.instance?.dismiss(msg.getString("key"))
                "clip" -> {
                    val text = msg.getString("text")
                    main.post {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PC", text))
                    }
                    result(op, true)
                }
                "ring" -> main.post { ring() }
            }
        } catch (_: SecurityException) {
            result(op, false, "Permission not granted on the phone")
        } catch (e: Exception) {
            result(op, false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun result(op: String, ok: Boolean, error: String? = null) {
        val msg = JSONObject().put("t", "result").put("op", op).put("ok", ok)
        if (error != null) msg.put("error", error)
        LinkState.send(msg)
    }

    private fun limit(msg: JSONObject) = msg.optInt("limit", 50).coerceIn(1, 500)

    private fun telecom() = getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    private fun sendStatus() {
        LinkState.send(
            JSONObject().put("t", "status")
                .put("battery", battery)
                .put("charging", charging)
                .put("name", Build.MODEL)
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("android", Build.VERSION.RELEASE)
        )
    }

    private fun sendCall() {
        LinkState.send(
            JSONObject().put("t", "call")
                .put("state", callState)
                .put("number", callNumber)
                .put("name", contactName(callNumber))
        )
    }

    private fun contactName(number: String): String {
        if (number.isBlank()) return ""
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }.orEmpty()
        } catch (_: RuntimeException) {
            ""
        }
    }

    private fun readSms(limit: Int): JSONArray {
        val items = JSONArray()
        val names = HashMap<String, String>()
        val columns = arrayOf(
            Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE,
        )
        contentResolver.query(Telephony.Sms.CONTENT_URI, columns, null, null, "date DESC")?.use { c ->
            while (items.length() < limit && c.moveToNext()) {
                val address = c.getString(0).orEmpty()
                items.put(
                    JSONObject().put("address", address)
                        .put("name", names.getOrPut(address) { contactName(address) })
                        .put("body", c.getString(1).orEmpty())
                        .put("time", c.getLong(2))
                        .put("type", if (c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_INBOX) "in" else "out")
                )
            }
        }
        return items
    }

    private fun readContacts(): JSONArray {
        val items = JSONArray()
        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (items.length() < MAX_CONTACTS && c.moveToNext()) {
                items.put(
                    JSONObject().put("name", c.getString(0).orEmpty()).put("number", c.getString(1).orEmpty())
                )
            }
        }
        return items
    }

    private fun readCallLog(limit: Int): JSONArray {
        val items = JSONArray()
        val columns = arrayOf(
            CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
            CallLog.Calls.DATE, CallLog.Calls.DURATION,
        )
        contentResolver.query(CallLog.Calls.CONTENT_URI, columns, null, null, "date DESC")?.use { c ->
            while (items.length() < limit && c.moveToNext()) {
                val type = when (c.getInt(2)) {
                    CallLog.Calls.INCOMING_TYPE -> "in"
                    CallLog.Calls.OUTGOING_TYPE -> "out"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                    else -> "other"
                }
                items.put(
                    JSONObject().put("number", c.getString(0).orEmpty())
                        .put("name", c.getString(1).orEmpty())
                        .put("type", type)
                        .put("time", c.getLong(3))
                        .put("duration", c.getLong(4))
                )
            }
        }
        return items
    }

    private fun sendSms(address: String, body: String) {
        require(address.isNotBlank() && body.isNotEmpty()) { "Empty number or message" }
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        sms.sendMultipartTextMessage(address, null, sms.divideMessage(body), null, null)
    }

    private fun ring() {
        ringtone?.stop()
        val tone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ?: return
        tone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        tone.play()
        ringtone = tone
        main.postDelayed({
            if (ringtone === tone) {
                tone.stop()
                ringtone = null
            }
        }, RING_MILLIS)
    }

    private fun notifications() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications().createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "PC Link Connection Status", NotificationManager.IMPORTANCE_LOW)
            )
            notifications().createNotificationChannel(
                NotificationChannel(CHANNEL_PAIR, "PC Pairing Request", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, LinkService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun statusNotification() = NotificationCompat.Builder(this, CHANNEL_STATUS)
        .setSmallIcon(android.R.drawable.ic_menu_share)
        .setContentTitle(getString(R.string.launcher_phone_label))
        .setContentText(LinkState.connectedPc?.let { "Connected to $it" } ?: "Waiting for your PC")
        .setContentIntent(openAppIntent())
        .setOngoing(true)
        .addAction(0, "Stop", serviceIntent(ACTION_STOP, 1))
        .build()

    private fun refreshStatusNotification() {
        if (server != null) notifications().notify(STATUS_NOTIFICATION_ID, statusNotification())
    }

    private fun pairNotification(request: PcPairRequest) = NotificationCompat.Builder(this, CHANNEL_PAIR)
        .setSmallIcon(android.R.drawable.ic_menu_share)
        .setContentTitle("Pair with ${request.pcName}?")
        .setContentText("Code ${request.sas}. Accept only if the PC shows the same code.")
        .setContentIntent(openAppIntent())
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .addAction(0, "Accept", serviceIntent(ACTION_PAIR_ACCEPT, 2))
        .addAction(0, "Decline", serviceIntent(ACTION_PAIR_DECLINE, 3))
        .build()

    companion object {
        private const val ACTION_STOP = "com.caller.LINK_STOP"
        private const val ACTION_PAIR_ACCEPT = "com.caller.LINK_PAIR_ACCEPT"
        private const val ACTION_PAIR_DECLINE = "com.caller.LINK_PAIR_DECLINE"
        private const val CHANNEL_STATUS = "pc_link_status"
        private const val CHANNEL_PAIR = "pc_link_pair"
        private const val STATUS_NOTIFICATION_ID = 5001
        private const val PAIR_NOTIFICATION_ID = 5002
        private const val PAIR_TIMEOUT_SECONDS = 110L
        private const val RING_MILLIS = 10_000L
        private const val MAX_CONTACTS = 5000
        private const val CALL_IDLE = "idle"
        private const val CALL_RINGING = "ringing"
        private const val CALL_OFFHOOK = "offhook"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }
}
