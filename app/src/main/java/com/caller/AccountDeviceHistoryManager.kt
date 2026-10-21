package com.caller

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

data class AccountDeviceRegistration(
    val accountId: String,
    val deviceId: String,
    val deviceName: String,
    val verifiedNumbers: List<String>,
    val linkStatus: String = "LINKED_VERIFIED", // "PENDING_HANDSHAKE" or "LINKED_VERIFIED"
    val lastSyncTimestamp: Long = 0L,
    val lanEndpoint: String = "",
    val isCurrentDevice: Boolean = false
)

data class SyncedCallRecord(
    val remoteNumber: String,
    val contactName: String,
    val callTypeInt: Int, // CallLog.Calls.INCOMING_TYPE, OUTGOING_TYPE, MISSED_TYPE
    val timestamp: Long,
    val durationSec: Long,
    val sourceDeviceName: String
)

object AccountDeviceHistoryManager {
    private const val TAG = "AccountDeviceHistory"
    private const val PREFS_NAME = "account_device_history_prefs"
    private const val KEY_ACCOUNT_ID = "account_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_VERIFIED_NUMBERS_JSON = "verified_numbers_json"
    private const val KEY_PEER_DEVICES_JSON = "peer_devices_json"
    private const val KEY_SYNCED_CALLS_JSON = "synced_calls_history_json"
    private const val KEY_FILTERING_ENABLED = "multi_device_filtering_enabled"
    private const val KEY_MERGE_TO_NATIVE_LOG = "merge_synced_to_native_call_log"

    private const val SYNC_PREFIX = "#CSYNC1#"
    private const val TCP_SYNC_PORT = 8902
    private const val MAX_STORED_SYNCED_CALLS = 300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serverRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val stateListeners = CopyOnWriteArrayList<() -> Unit>()

    private fun getPrefs(context: Context) =
        p2pContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addStateListener(listener: () -> Unit) {
        if (!stateListeners.contains(listener)) stateListeners.add(listener)
    }

    fun removeStateListener(listener: () -> Unit) {
        stateListeners.remove(listener)
    }

    private fun notifyStateChanged() {
        mainHandler.post {
            stateListeners.forEach { runCatching { it.invoke() } }
        }
    }

    fun isFilteringEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_FILTERING_ENABLED, true)

    fun setFilteringEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FILTERING_ENABLED, enabled).apply()
        notifyStateChanged()
    }

    fun isMergeToNativeCallLogEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_MERGE_TO_NATIVE_LOG, true)

    fun setMergeToNativeCallLogEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MERGE_TO_NATIVE_LOG, enabled).apply()
        notifyStateChanged()
    }

    fun getAccountId(context: Context): String {
        val prefs = getPrefs(context)
        var accId = prefs.getString(KEY_ACCOUNT_ID, null)
        if (accId.isNullOrBlank()) {
            accId = p2pServerVerifiedNumber(context)
                ?: detectSimPhoneNumbers(context).firstOrNull()
                ?: "acct_${getCurrentDeviceId(context).takeLast(6)}"
            prefs.edit().putString(KEY_ACCOUNT_ID, accId).apply()
        }
        return accId
    }

    fun setAccountId(context: Context, accountId: String) {
        if (accountId.isBlank()) return
        getPrefs(context).edit().putString(KEY_ACCOUNT_ID, accountId.trim()).apply()
        notifyStateChanged()
    }

    fun getCurrentDeviceId(context: Context): String {
        val prefs = getPrefs(context)
        var devId = prefs.getString(KEY_DEVICE_ID, null)
        if (devId.isNullOrBlank()) {
            devId = "dev_" + Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "") + "_" + (System.currentTimeMillis() % 100000)
            prefs.edit().putString(KEY_DEVICE_ID, devId).apply()
        }
        return devId
    }

    fun getCurrentDeviceName(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_DEVICE_NAME, null)
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    }

    @Suppress("MissingPermission")
    private fun detectSimPhoneNumbers(context: Context): List<String> {
        val detected = mutableListOf<String>()
        runCatching {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED)
            ) {
                val subMgr = context.getSystemService(SubscriptionManager::class.java)
                subMgr?.activeSubscriptionInfoList?.forEach { info ->
                    val num = info.number?.trim().orEmpty()
                    if (num.isNotBlank()) detected.add(num)
                }
            }
        }
        return detected
    }

    fun getVerifiedNumbers(context: Context): List<String> {
        val prefs = getPrefs(context)
        val rawJson = prefs.getString(KEY_VERIFIED_NUMBERS_JSON, null).orEmpty()
        val numbers = mutableListOf<String>()
        if (rawJson.isNotBlank()) {
            runCatching {
                val array = JSONArray(rawJson)
                for (i in 0 until array.length()) {
                    val num = array.optString(i).trim()
                    if (num.isNotEmpty()) numbers.add(num)
                }
            }
        }
        p2pServerVerifiedNumber(context)?.let { serverNum ->
            if (serverNum.isNotBlank() && !numbers.contains(serverNum)) {
                numbers.add(serverNum)
            }
        }
        detectSimPhoneNumbers(context).forEach { simNum ->
            if (simNum.isNotBlank() && !numbers.contains(simNum)) {
                numbers.add(simNum)
            }
        }
        return numbers.distinct()
    }

    fun registerVerifiedNumber(context: Context, number: String) {
        if (number.isBlank()) return
        val current = getVerifiedNumbers(context).toMutableList()
        if (!current.contains(number.trim())) {
            current.add(number.trim())
            val array = JSONArray(current)
            getPrefs(context).edit().putString(KEY_VERIFIED_NUMBERS_JSON, array.toString()).apply()
            notifyStateChanged()
        }
    }

    fun removeVerifiedNumber(context: Context, number: String) {
        val current = getVerifiedNumbers(context).filterNot { it == number.trim() }
        val array = JSONArray(current)
        getPrefs(context).edit().putString(KEY_VERIFIED_NUMBERS_JSON, array.toString()).apply()
        notifyStateChanged()
    }

    fun registerPeerDevice(context: Context, peer: AccountDeviceRegistration) {
        val prefs = getPrefs(context)
        val peers = getPeerDevices(context).toMutableList()
        val incomingKeys = peer.verifiedNumbers.flatMap { buildP2PNumberSnapshot(it).lookupKeys }.toSet()

        peers.removeAll { existing ->
            existing.deviceId == peer.deviceId ||
                existing.verifiedNumbers.any { num ->
                    buildP2PNumberSnapshot(num).lookupKeys.intersect(incomingKeys).isNotEmpty()
                }
        }
        peers.add(0, peer)
        savePeersList(prefs, peers)
        notifyStateChanged()
    }

    private fun savePeersList(prefs: android.content.SharedPreferences, peers: List<AccountDeviceRegistration>) {
        val array = JSONArray()
        peers.forEach { p ->
            array.put(JSONObject().apply {
                put("accountId", p.accountId)
                put("deviceId", p.deviceId)
                put("deviceName", p.deviceName)
                put("verifiedNumbers", JSONArray(p.verifiedNumbers))
                put("linkStatus", p.linkStatus)
                put("lastSyncTimestamp", p.lastSyncTimestamp)
                put("lanEndpoint", p.lanEndpoint)
            })
        }
        prefs.edit().putString(KEY_PEER_DEVICES_JSON, array.toString()).apply()
    }

    fun removePeerDevice(context: Context, deviceId: String) {
        val prefs = getPrefs(context)
        val peers = getPeerDevices(context).filterNot { it.deviceId == deviceId }
        savePeersList(prefs, peers)
        notifyStateChanged()
    }

    fun getPeerDevices(context: Context): List<AccountDeviceRegistration> {
        val prefs = getPrefs(context)
        val rawJson = prefs.getString(KEY_PEER_DEVICES_JSON, null).orEmpty()
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val numbersArray = obj.optJSONArray("verifiedNumbers")
                    val nums = buildList {
                        if (numbersArray != null) {
                            for (j in 0 until numbersArray.length()) {
                                val n = numbersArray.optString(j).trim()
                                if (n.isNotEmpty()) add(n)
                            }
                        }
                    }
                    add(
                        AccountDeviceRegistration(
                            accountId = obj.optString("accountId"),
                            deviceId = obj.optString("deviceId"),
                            deviceName = obj.optString("deviceName"),
                            verifiedNumbers = nums,
                            linkStatus = obj.optString("linkStatus", "LINKED_VERIFIED"),
                            lastSyncTimestamp = obj.optLong("lastSyncTimestamp", 0L),
                            lanEndpoint = obj.optString("lanEndpoint", "")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    // =========================================================================
    // REAL TWO-WAY P2P HANDSHAKE + LAN TCP + SMS CALL HISTORY SYNC ENGINE
    // =========================================================================

    fun startSyncEngine(context: Context) {
        val appCtx = context.applicationContext
        if (!serverRunning.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(TCP_SYNC_PORT))
                serverSocket = server
                Log.w(TAG, "Multi-Device TCP Sync Server listening on port $TCP_SYNC_PORT")
                while (serverRunning.get()) {
                    val client = server.accept()
                    scope.launch { handleTcpPeerConnection(appCtx, client) }
                }
            }.onFailure { e ->
                Log.w(TAG, "TCP Sync Server stopped or failed: ${e.message}")
                serverRunning.set(false)
            }
        }
    }

    private fun getLocalWifiOrHotspotIp(): String {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching ""
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress.orEmpty()
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return@runCatching "$ip:$TCP_SYNC_PORT"
                        }
                    }
                }
            }
            ""
        }.getOrDefault("")
    }

    /**
     * Initiates a REAL two-way link request to the target phone number via SMS + LAN discovery.
     * Phone B will intercept this in CallerIdReceiver, register Phone A, and reply with ACK + Call History.
     */
    fun initiateRealDeviceLink(
        context: Context,
        peerDeviceNameHint: String,
        peerPhoneNumberRaw: String
    ) {
        val appCtx = context.applicationContext
        startSyncEngine(appCtx)

        val cleanPeerNum = peerPhoneNumberRaw.trim()
        if (cleanPeerNum.isEmpty()) return

        val myAccountId = getAccountId(appCtx)
        val myDeviceId = getCurrentDeviceId(appCtx)
        val myDeviceName = getCurrentDeviceName(appCtx).replace("|", " ").replace(",", " ")
        val myNumbers = getVerifiedNumbers(appCtx).ifEmpty { listOf(myAccountId) }
        val myNumbersCsv = myNumbers.joinToString(",")
        val myLanEndpoint = getLocalWifiOrHotspotIp()

        // 1. Save locally as PENDING_HANDSHAKE until Phone B replies with ACK
        val pendingPeer = AccountDeviceRegistration(
            accountId = myAccountId,
            deviceId = "pending_" + buildP2PNumberSnapshot(cleanPeerNum).last10.ifBlank { cleanPeerNum },
            deviceName = peerDeviceNameHint.trim().ifBlank { "Phone ($cleanPeerNum)" },
            verifiedNumbers = listOf(cleanPeerNum),
            linkStatus = "PENDING_HANDSHAKE",
            lastSyncTimestamp = 0L,
            lanEndpoint = ""
        )
        registerPeerDevice(appCtx, pendingPeer)

        // 2. Send real P2P Link Request SMS to Phone B
        // Format: #CSYNC1#REQ|accountId|deviceId|deviceName|numbersCsv|lanEndpoint
        val packet = "$SYNC_PREFIX" + "REQ|$myAccountId|$myDeviceId|$myDeviceName|$myNumbersCsv|$myLanEndpoint"
        val sent = sendRawSyncSms(appCtx, cleanPeerNum, packet)
        toast(
            appCtx,
            if (sent) "Link request sent to $cleanPeerNum! Waiting for automatic handshake..."
            else "Grant SMS permission to send link handshake to $cleanPeerNum"
        )
    }

    /**
     * Called by CallerIdReceiver whenever an SMS arrives.
     * Returns true if the message was a #CSYNC1# device-linking or call-history sync packet.
     */
    fun handleIncomingSyncMessage(context: Context, senderNumber: String, rawBody: String): Boolean {
        val trimmed = rawBody.trim()
        if (!trimmed.startsWith(SYNC_PREFIX)) return false

        val appCtx = context.applicationContext
        startSyncEngine(appCtx)

        val payload = trimmed.removePrefix(SYNC_PREFIX)
        val parts = payload.split("|")
        val type = parts.getOrNull(0).orEmpty()

        when (type) {
            "REQ" -> {
                // Format: REQ|accountId|deviceId|deviceName|numbersCsv|lanEndpoint
                val remoteAccountId = parts.getOrNull(1).orEmpty().ifBlank { getAccountId(appCtx) }
                val remoteDeviceId = parts.getOrNull(2).orEmpty().ifBlank { "peer_${System.currentTimeMillis()}" }
                val remoteDeviceName = parts.getOrNull(3).orEmpty().ifBlank { "Linked Phone" }
                val remoteNums = parts.getOrNull(4).orEmpty().split(",")
                    .map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                if (senderNumber.isNotBlank() && remoteNums.none {
                        buildP2PNumberSnapshot(it).last10 == buildP2PNumberSnapshot(senderNumber).last10
                    }
                ) {
                    remoteNums.add(0, senderNumber)
                }
                val remoteLan = parts.getOrNull(5).orEmpty()

                // Adopt shared account ID so both devices belong to the exact same account
                setAccountId(appCtx, remoteAccountId)

                val verifiedPeer = AccountDeviceRegistration(
                    accountId = remoteAccountId,
                    deviceId = remoteDeviceId,
                    deviceName = remoteDeviceName,
                    verifiedNumbers = remoteNums.distinct(),
                    linkStatus = "LINKED_VERIFIED",
                    lastSyncTimestamp = System.currentTimeMillis(),
                    lanEndpoint = remoteLan
                )
                registerPeerDevice(appCtx, verifiedPeer)

                // Reply with ACK immediately so the requesting phone upgrades to LINKED_VERIFIED
                sendLinkAck(appCtx, senderNumber, remoteLan)

                // Push our recent call history to the newly linked peer
                syncCallHistoryWithPeer(appCtx, verifiedPeer)
                toast(appCtx, "Linked with $remoteDeviceName! Syncing call history...")
                return true
            }

            "ACK" -> {
                // Format: ACK|accountId|deviceId|deviceName|numbersCsv|lanEndpoint
                val remoteAccountId = parts.getOrNull(1).orEmpty().ifBlank { getAccountId(appCtx) }
                val remoteDeviceId = parts.getOrNull(2).orEmpty().ifBlank { "peer_${System.currentTimeMillis()}" }
                val remoteDeviceName = parts.getOrNull(3).orEmpty().ifBlank { "Linked Phone" }
                val remoteNums = parts.getOrNull(4).orEmpty().split(",")
                    .map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                if (senderNumber.isNotBlank() && remoteNums.none {
                        buildP2PNumberSnapshot(it).last10 == buildP2PNumberSnapshot(senderNumber).last10
                    }
                ) {
                    remoteNums.add(0, senderNumber)
                }
                val remoteLan = parts.getOrNull(5).orEmpty()

                setAccountId(appCtx, remoteAccountId)

                val verifiedPeer = AccountDeviceRegistration(
                    accountId = remoteAccountId,
                    deviceId = remoteDeviceId,
                    deviceName = remoteDeviceName,
                    verifiedNumbers = remoteNums.distinct(),
                    linkStatus = "LINKED_VERIFIED",
                    lastSyncTimestamp = System.currentTimeMillis(),
                    lanEndpoint = remoteLan
                )
                registerPeerDevice(appCtx, verifiedPeer)

                // Now push our call history to the peer
                syncCallHistoryWithPeer(appCtx, verifiedPeer)
                toast(appCtx, "$remoteDeviceName verified & linked! Syncing call history...")
                return true
            }

            "HIST" -> {
                // Format: HIST|sourceDeviceName|entry1;entry2;entry3...
                // Each entry: number~base64Name~callTypeInt~timestampSec~durationSec
                val sourceDevice = parts.getOrNull(1).orEmpty().ifBlank { "Linked Phone" }
                val encodedEntries = parts.getOrNull(2).orEmpty()
                val parsedCalls = parseCompactCallEntries(sourceDevice, encodedEntries)
                if (parsedCalls.isNotEmpty()) {
                    ingestSyncedCalls(appCtx, senderNumber, sourceDevice, parsedCalls)
                }
                return true
            }

            "PULL" -> {
                // Peer requested our latest call history
                val peer = getPeerDevices(appCtx).firstOrNull { p ->
                    p.verifiedNumbers.any {
                        buildP2PNumberSnapshot(it).last10 == buildP2PNumberSnapshot(senderNumber).last10
                    }
                }
                if (peer != null) {
                    syncCallHistoryWithPeer(appCtx, peer)
                } else {
                    sendSmsCallHistoryBatch(appCtx, senderNumber, limit = 8)
                }
                return true
            }
        }
        return false
    }

    private fun sendLinkAck(context: Context, destinationNumber: String, remoteLanEndpoint: String) {
        val myAccountId = getAccountId(context)
        val myDeviceId = getCurrentDeviceId(context)
        val myDeviceName = getCurrentDeviceName(context).replace("|", " ").replace(",", " ")
        val myNumbers = getVerifiedNumbers(context).ifEmpty { listOf(myAccountId) }
        val myNumbersCsv = myNumbers.joinToString(",")
        val myLan = getLocalWifiOrHotspotIp()

        val ackPacket = "$SYNC_PREFIX" + "ACK|$myAccountId|$myDeviceId|$myDeviceName|$myNumbersCsv|$myLan"
        sendRawSyncSms(context, destinationNumber, ackPacket)
    }

    /**
     * Syncs call history with all linked devices (tries instant LAN TCP first, then SMS batch).
     */
    fun syncAllPeerDevicesNow(context: Context) {
        val appCtx = context.applicationContext
        startSyncEngine(appCtx)
        val peers = getPeerDevices(appCtx)
        if (peers.isEmpty()) {
            toast(appCtx, "No linked devices yet. Tap 'Link Device' first.")
            return
        }
        toast(appCtx, "Syncing call history with ${peers.size} linked device(s)...")
        peers.forEach { peer ->
            if (peer.linkStatus == "PENDING_HANDSHAKE" && peer.verifiedNumbers.isNotEmpty()) {
                initiateRealDeviceLink(appCtx, peer.deviceName, peer.verifiedNumbers.first())
            } else {
                syncCallHistoryWithPeer(appCtx, peer)
                // Also ask the peer to push its latest calls back
                peer.verifiedNumbers.firstOrNull()?.let { targetNum ->
                    sendRawSyncSms(appCtx, targetNum, "${SYNC_PREFIX}PULL|${getCurrentDeviceId(appCtx)}")
                }
            }
        }
    }

    fun syncCallHistoryWithPeer(context: Context, peer: AccountDeviceRegistration) {
        val appCtx = context.applicationContext
        scope.launch {
            // 1. Try high-speed LAN / Hotspot TCP sync (transfers up to 150 calls instantly)
            val lanSuccess = if (peer.lanEndpoint.contains(":")) {
                pushAndPullViaTcpLan(appCtx, peer)
            } else {
                false
            }

            // 2. If not on the same Wi-Fi/Hotspot, send compact SMS call history batch
            if (!lanSuccess) {
                val targetNumber = peer.verifiedNumbers.firstOrNull().orEmpty()
                if (targetNumber.isNotBlank()) {
                    sendSmsCallHistoryBatch(appCtx, targetNumber, limit = 8)
                }
            }
        }
    }

    private fun pushAndPullViaTcpLan(context: Context, peer: AccountDeviceRegistration): Boolean {
        return runCatching {
            val hostPort = peer.lanEndpoint.split(":")
            val host = hostPort.getOrNull(0).orEmpty()
            val port = hostPort.getOrNull(1)?.toIntOrNull() ?: TCP_SYNC_PORT
            if (host.isBlank()) return@runCatching false

            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2500)
                socket.soTimeout = 4000
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

                val myCallsJson = exportLocalCallHistoryJson(context, maxCount = 150)
                val envelope = JSONObject().apply {
                    put("accountId", getAccountId(context))
                    put("deviceId", getCurrentDeviceId(context))
                    put("deviceName", getCurrentDeviceName(context))
                    put("verifiedNumbers", JSONArray(getVerifiedNumbers(context)))
                    put("lanEndpoint", getLocalWifiOrHotspotIp())
                    put("calls", myCallsJson)
                }
                writer.println(envelope.toString())

                val responseLine = reader.readLine()
                if (!responseLine.isNullOrBlank()) {
                    processTcpSyncPayload(context, responseLine)
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun handleTcpPeerConnection(context: Context, socket: Socket) {
        runCatching {
            socket.use { client ->
                client.soTimeout = 4000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8), true)

                val incomingLine = reader.readLine()
                if (!incomingLine.isNullOrBlank()) {
                    processTcpSyncPayload(context, incomingLine)
                }

                val myCallsJson = exportLocalCallHistoryJson(context, maxCount = 150)
                val replyEnvelope = JSONObject().apply {
                    put("accountId", getAccountId(context))
                    put("deviceId", getCurrentDeviceId(context))
                    put("deviceName", getCurrentDeviceName(context))
                    put("verifiedNumbers", JSONArray(getVerifiedNumbers(context)))
                    put("lanEndpoint", getLocalWifiOrHotspotIp())
                    put("calls", myCallsJson)
                }
                writer.println(replyEnvelope.toString())
            }
        }
    }

    private fun processTcpSyncPayload(context: Context, rawJson: String) {
        runCatching {
            val obj = JSONObject(rawJson)
            val remoteAccountId = obj.optString("accountId").ifBlank { getAccountId(context) }
            val remoteDeviceId = obj.optString("deviceId")
            val remoteDeviceName = obj.optString("deviceName").ifBlank { "Linked Phone" }
            val remoteLan = obj.optString("lanEndpoint")
            val numsArr = obj.optJSONArray("verifiedNumbers")
            val remoteNums = buildList {
                if (numsArr != null) {
                    for (i in 0 until numsArr.length()) {
                        val n = numsArr.optString(i).trim()
                        if (n.isNotEmpty()) add(n)
                    }
                }
            }

            if (remoteDeviceId.isNotBlank()) {
                registerPeerDevice(
                    context,
                    AccountDeviceRegistration(
                        accountId = remoteAccountId,
                        deviceId = remoteDeviceId,
                        deviceName = remoteDeviceName,
                        verifiedNumbers = remoteNums,
                        linkStatus = "LINKED_VERIFIED",
                        lastSyncTimestamp = System.currentTimeMillis(),
                        lanEndpoint = remoteLan
                    )
                )
            }

            val callsArr = obj.optJSONArray("calls") ?: return@runCatching
            val parsed = mutableListOf<SyncedCallRecord>()
            for (i in 0 until callsArr.length()) {
                val c = callsArr.optJSONObject(i) ?: continue
                val num = c.optString("number").trim()
                if (num.isEmpty()) continue
                parsed.add(
                    SyncedCallRecord(
                        remoteNumber = num,
                        contactName = c.optString("name"),
                        callTypeInt = c.optInt("type", CallLog.Calls.INCOMING_TYPE),
                        timestamp = c.optLong("date", System.currentTimeMillis()),
                        durationSec = c.optLong("duration", 0L),
                        sourceDeviceName = remoteDeviceName
                    )
                )
            }
            if (parsed.isNotEmpty()) {
                ingestSyncedCalls(context, remoteNums.firstOrNull().orEmpty(), remoteDeviceName, parsed)
            }
        }
    }

    private fun sendSmsCallHistoryBatch(context: Context, destinationNumber: String, limit: Int = 8) {
        val localCalls = queryLocalDeviceCallLog(context, maxCount = limit)
        if (localCalls.isEmpty()) return

        val myDeviceName = getCurrentDeviceName(context).replace("|", " ").replace("~", " ")
        val encodedEntries = localCalls.joinToString(";") { call ->
            val safeNum = call.remoteNumber.replace("~", "").replace(";", "").replace("|", "")
            val nameB64 = Base64.encodeToString(
                call.contactName.take(20).toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            val tsSec = call.timestamp / 1000L
            "$safeNum~$nameB64~${call.callTypeInt}~$tsSec~${call.durationSec}"
        }

        val packet = "$SYNC_PREFIX" + "HIST|$myDeviceName|$encodedEntries"
        sendRawSyncSms(context, destinationNumber, packet)
    }

    private fun parseCompactCallEntries(sourceDeviceName: String, rawEntries: String): List<SyncedCallRecord> {
        if (rawEntries.isBlank()) return emptyList()
        return rawEntries.split(";").mapNotNull { item ->
            val fields = item.split("~")
            if (fields.size < 5) return@mapNotNull null
            val number = fields[0].trim()
            if (number.isEmpty()) return@mapNotNull null
            val name = runCatching {
                String(Base64.decode(fields[1], Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrDefault("")
            val typeInt = fields[2].toIntOrNull() ?: CallLog.Calls.INCOMING_TYPE
            val timestampMs = (fields[3].toLongOrNull() ?: 0L) * 1000L
            val durationSec = fields[4].toLongOrNull() ?: 0L
            if (timestampMs <= 0L) return@mapNotNull null

            SyncedCallRecord(
                remoteNumber = number,
                contactName = name,
                callTypeInt = typeInt,
                timestamp = timestampMs,
                durationSec = durationSec,
                sourceDeviceName = sourceDeviceName
            )
        }
    }

    private fun ingestSyncedCalls(
        context: Context,
        senderNumber: String,
        sourceDeviceName: String,
        incomingCalls: List<SyncedCallRecord>
    ) {
        val prefs = getPrefs(context)
        val existing = getSyncedCallHistory(context).toMutableList()

        var addedCount = 0
        for (call in incomingCalls) {
            val isDuplicate = existing.any {
                buildP2PNumberSnapshot(it.remoteNumber).last10 == buildP2PNumberSnapshot(call.remoteNumber).last10 &&
                    kotlin.math.abs(it.timestamp - call.timestamp) < 5000L
            }
            if (!isDuplicate) {
                existing.add(call)
                addedCount++
                if (isMergeToNativeCallLogEnabled(context)) {
                    insertIntoNativeCallLogIfMissing(context, call)
                }
            }
        }

        val sorted = existing.sortedByDescending { it.timestamp }.take(MAX_STORED_SYNCED_CALLS)
        val array = JSONArray()
        sorted.forEach { c ->
            array.put(JSONObject().apply {
                put("remoteNumber", c.remoteNumber)
                put("contactName", c.contactName)
                put("callTypeInt", c.callTypeInt)
                put("timestamp", c.timestamp)
                put("durationSec", c.durationSec)
                put("sourceDeviceName", c.sourceDeviceName)
            })
        }
        prefs.edit().putString(KEY_SYNCED_CALLS_JSON, array.toString()).apply()

        val peers = getPeerDevices(context).map { p ->
            val matchesSender = senderNumber.isNotBlank() && p.verifiedNumbers.any {
                buildP2PNumberSnapshot(it).last10 == buildP2PNumberSnapshot(senderNumber).last10
            }
            if (matchesSender || p.deviceName.equals(sourceDeviceName, ignoreCase = true)) {
                p.copy(
                    linkStatus = "LINKED_VERIFIED",
                    lastSyncTimestamp = System.currentTimeMillis()
                )
            } else {
                p
            }
        }
        savePeersList(prefs, peers)
        notifyStateChanged()

        if (addedCount > 0) {
            toast(context, "Synced $addedCount call history record(s) from $sourceDeviceName")
        }
    }

    fun getSyncedCallHistory(context: Context): List<SyncedCallRecord> {
        val rawJson = getPrefs(context).getString(KEY_SYNCED_CALLS_JSON, null).orEmpty()
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(
                        SyncedCallRecord(
                            remoteNumber = obj.optString("remoteNumber"),
                            contactName = obj.optString("contactName"),
                            callTypeInt = obj.optInt("callTypeInt", CallLog.Calls.INCOMING_TYPE),
                            timestamp = obj.optLong("timestamp", 0L),
                            durationSec = obj.optLong("durationSec", 0L),
                            sourceDeviceName = obj.optString("sourceDeviceName", "Linked Device")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun queryLocalDeviceCallLog(context: Context, maxCount: Int): List<SyncedCallRecord> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val list = mutableListOf<SyncedCallRecord>()
        val myDeviceName = getCurrentDeviceName(context)
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)

                while (cursor.moveToNext() && list.size < maxCount) {
                    val number = if (numIdx >= 0) cursor.getString(numIdx).orEmpty().trim() else ""
                    if (number.isBlank()) continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty().trim() else ""
                    val type = if (typeIdx >= 0) cursor.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                    val date = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                    val duration = if (durIdx >= 0) cursor.getLong(durIdx) else 0L
                    if (date > 0L) {
                        list.add(
                            SyncedCallRecord(
                                remoteNumber = number,
                                contactName = name,
                                callTypeInt = type,
                                timestamp = date,
                                durationSec = duration,
                                sourceDeviceName = myDeviceName
                            )
                        )
                    }
                }
            }
        }
        return list
    }

    private fun exportLocalCallHistoryJson(context: Context, maxCount: Int): JSONArray {
        val records = queryLocalDeviceCallLog(context, maxCount)
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("number", r.remoteNumber)
                put("name", r.contactName)
                put("type", r.callTypeInt)
                put("date", r.timestamp)
                put("duration", r.durationSec)
            })
        }
        return arr
    }

    private fun insertIntoNativeCallLogIfMissing(context: Context, record: SyncedCallRecord) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val ownNumbers = getVerifiedNumbers(context)
        val recordKeys = buildP2PNumberSnapshot(record.remoteNumber).lookupKeys.toSet()
        val isOwnNumber = ownNumbers.any {
            buildP2PNumberSnapshot(it).lookupKeys.intersect(recordKeys).isNotEmpty()
        }
        if (isFilteringEnabled(context) && isOwnNumber) return

        runCatching {
            val minTs = record.timestamp - 10_000L
            val maxTs = record.timestamp + 10_000L
            var alreadyExists = false
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} BETWEEN ? AND ?",
                arrayOf(minTs.toString(), maxTs.toString()),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val existingNum = cursor.getString(0).orEmpty()
                    if (buildP2PNumberSnapshot(existingNum).last10 == buildP2PNumberSnapshot(record.remoteNumber).last10) {
                        alreadyExists = true
                        break
                    }
                }
            }

            if (!alreadyExists) {
                val values = ContentValues().apply {
                    put(CallLog.Calls.NUMBER, record.remoteNumber)
                    if (record.contactName.isNotBlank()) {
                        put(CallLog.Calls.CACHED_NAME, record.contactName)
                    }
                    put(CallLog.Calls.TYPE, record.callTypeInt)
                    put(CallLog.Calls.DATE, record.timestamp)
                    put(CallLog.Calls.DURATION, record.durationSec)
                    put(CallLog.Calls.NEW, 0)
                    put(CallLog.Calls.IS_READ, 1)
                }
                context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sendRawSyncSms(context: Context, destinationNumber: String, messageText: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing SEND_SMS permission for device link handshake")
            return false
        }
        return runCatching {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            } else {
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(messageText)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(destinationNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(destinationNumber, null, messageText, null, null)
            }
            Log.w(TAG, "Sent P2P sync packet to ${maskP2PNumberForLog(destinationNumber)}")
            true
        }.onFailure { e ->
            Log.e(TAG, "Failed to send P2P sync SMS: ${e.message}", e)
        }.getOrDefault(false)
    }

    fun shouldFilterCallHistoryItem(
        context: Context,
        remoteNumber: String,
        callType: CallType
    ): Boolean {
        if (!isFilteringEnabled(context)) return false
        if (remoteNumber.isBlank()) return false

        val ownVerifiedNumbers = getVerifiedNumbers(context)
        if (ownVerifiedNumbers.isEmpty()) return false

        val remoteSnapshot = buildP2PNumberSnapshot(remoteNumber)
        val remoteKeys = remoteSnapshot.lookupKeys.toSet()

        val matchesOwnNumber = ownVerifiedNumbers.any { ownNum ->
            val ownKeys = buildP2PNumberSnapshot(ownNum).lookupKeys.toSet()
            ownKeys.intersect(remoteKeys).isNotEmpty()
        }
        if (!matchesOwnNumber) return false

        Log.w(
            P2P_FORENSIC_TAG,
            "account_device_filtering action=hide_entry remoteNumber=${maskP2PNumberForLog(remoteNumber)} device=${getCurrentDeviceName(context)}"
        )
        return true
    }

    private fun toast(context: Context, msg: String) {
        mainHandler.post {
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun MultiDeviceSettingsCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        AccountDeviceHistoryManager.startSyncEngine(context)
    }

    DisposableEffect(Unit) {
        val listener = { refreshTick++; Unit }
        AccountDeviceHistoryManager.addStateListener(listener)
        onDispose { AccountDeviceHistoryManager.removeStateListener(listener) }
    }

    val filteringEnabled = remember(refreshTick) {
        AccountDeviceHistoryManager.isFilteringEnabled(context)
    }
    val mergeToNativeEnabled = remember(refreshTick) {
        AccountDeviceHistoryManager.isMergeToNativeCallLogEnabled(context)
    }
    val accountId = remember(refreshTick) {
        AccountDeviceHistoryManager.getAccountId(context)
    }
    val verifiedNumbers = remember(refreshTick) {
        AccountDeviceHistoryManager.getVerifiedNumbers(context)
    }
    val peerDevices = remember(refreshTick) {
        AccountDeviceHistoryManager.getPeerDevices(context)
    }
    val syncedCalls = remember(refreshTick) {
        AccountDeviceHistoryManager.getSyncedCallHistory(context)
    }

    var showEditAccountDialog by remember { mutableStateOf(false) }
    var accountInput by remember { mutableStateOf(accountId) }

    var showAddNumberDialog by remember { mutableStateOf(false) }
    var newNumberInput by remember { mutableStateOf("") }

    var showAddPeerDialog by remember { mutableStateOf(false) }
    var peerNameInput by remember { mutableStateOf("") }
    var peerNumberInput by remember { mutableStateOf("") }

    var showSyncedHistoryDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Multi-Device Account & Call Sync",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = { AccountDeviceHistoryManager.syncAllPeerDevicesNow(context) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Sync Now",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 1. Account ID / Sign-In Status
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Shared Account ID",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = formatPhoneNumberForDisplay(accountId),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    TextButton(onClick = {
                        accountInput = accountId
                        showEditAccountDialog = true
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Edit ID")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Merge Synced Calls into Native Recents Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Sync Peer Calls into Recents Tab",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Automatically merges call history from your linked phones into this phone's Recents list",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = mergeToNativeEnabled,
                    onCheckedChange = { enabled ->
                        AccountDeviceHistoryManager.setMergeToNativeCallLogEnabled(context, enabled)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Hide Self-Calls Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Hide Self-Calls Across Devices",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (filteringEnabled) {
                            "Hides calls made to this phone's own SIM number"
                        } else {
                            "Disabled — all calls appear in Recents"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = filteringEnabled,
                    onCheckedChange = { enabled ->
                        AccountDeviceHistoryManager.setFilteringEnabled(context, enabled)
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(14.dp))

            // 4. This Device's Numbers
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "This Device (${AccountDeviceHistoryManager.getCurrentDeviceName(context)})",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (verifiedNumbers.isNotEmpty()) {
                            verifiedNumbers.joinToString(", ") { formatPhoneNumberForDisplay(it) }
                        } else {
                            "Add this phone's SIM number so peer devices recognize it"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Button(
                    onClick = { showAddNumberDialog = true },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Number", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("SIM #", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(14.dp))

            // 5. Linked Peer Devices + Real Two-Way Handshake Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Linked Account Devices",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Sends a real P2P handshake to your other phone and syncs call history both ways",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { showAddPeerDialog = true },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Link Device", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Link Device", fontSize = 13.sp)
                }
            }

            if (peerDevices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    peerDevices.forEach { peer ->
                        val isVerified = peer.linkStatus == "LINKED_VERIFIED"
                        val syncTimeFmt = if (peer.lastSyncTimestamp > 0L) {
                            SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                                .format(Date(peer.lastSyncTimestamp))
                        } else {
                            "Waiting for reply..."
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isVerified) Icons.Default.CheckCircle else Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint = if (isVerified) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = peer.deviceName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = peer.verifiedNumbers.joinToString(", ") { formatPhoneNumberForDisplay(it) },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = if (isVerified) "Verified & Linked • Synced: $syncTimeFmt" else "Pending Handshake ($syncTimeFmt)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isVerified) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (isVerified) {
                                            AccountDeviceHistoryManager.syncCallHistoryWithPeer(context, peer)
                                        } else if (peer.verifiedNumbers.isNotEmpty()) {
                                            AccountDeviceHistoryManager.initiateRealDeviceLink(
                                                context,
                                                peer.deviceName,
                                                peer.verifiedNumbers.first()
                                            )
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Sync with this device",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        AccountDeviceHistoryManager.removePeerDevice(context, peer.deviceId)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Unlink Device",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 6. Action Buttons: Sync Call History Now & View Synced Call History
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { AccountDeviceHistoryManager.syncAllPeerDevicesNow(context) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sync History Now", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = { showSyncedHistoryDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Synced Calls (${syncedCalls.size})", fontSize = 13.sp)
                }
            }
        }
    }

    // Dialog 1: Edit Account ID
    if (showEditAccountDialog) {
        AlertDialog(
            onDismissRequest = { showEditAccountDialog = false },
            title = { Text("Account ID / Primary Number", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter your primary account phone number. When you link another device, both phones automatically adopt this shared Account ID.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = accountInput,
                        onValueChange = { accountInput = it },
                        label = { Text("Account Phone Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = accountInput.isNotBlank(),
                    onClick = {
                        AccountDeviceHistoryManager.setAccountId(context, accountInput)
                        showEditAccountDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditAccountDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog 2: Add Verified Number for This Device
    if (showAddNumberDialog) {
        AlertDialog(
            onDismissRequest = { showAddNumberDialog = false },
            title = { Text("Add SIM Number for This Device", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter the phone number of the SIM inside THIS phone so your other linked devices recognize it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newNumberInput,
                        onValueChange = { newNumberInput = it },
                        label = { Text("This Phone's SIM Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = newNumberInput.isNotBlank(),
                    onClick = {
                        AccountDeviceHistoryManager.registerVerifiedNumber(context, newNumberInput)
                        newNumberInput = ""
                        showAddNumberDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNumberDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog 3: Send Real Two-Way Handshake to Link Another Phone
    if (showAddPeerDialog) {
        AlertDialog(
            onDismissRequest = { showAddPeerDialog = false },
            title = { Text("Link Other Phone (Two-Way Sync)", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter your other phone's number. This phone will send an automatic P2P handshake SMS to your other phone, link both devices under the same Account ID, and sync call history between them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = peerNameInput,
                        onValueChange = { peerNameInput = it },
                        label = { Text("Device Label (e.g. Second Phone)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = peerNumberInput,
                        onValueChange = { peerNumberInput = it },
                        label = { Text("Other Phone's SIM Number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = peerNumberInput.isNotBlank(),
                    onClick = {
                        AccountDeviceHistoryManager.initiateRealDeviceLink(
                            context = context,
                            peerDeviceNameHint = peerNameInput.ifBlank { "Linked Phone" },
                            peerPhoneNumberRaw = peerNumberInput
                        )
                        peerNameInput = ""
                        peerNumberInput = ""
                        showAddPeerDialog = false
                    }
                ) {
                    Text("Send Handshake & Link")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPeerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Dialog 4: View Call History Synced from Linked Devices
    if (showSyncedHistoryDialog) {
        val dateFormat = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { showSyncedHistoryDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Synced Call History", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { AccountDeviceHistoryManager.syncAllPeerDevicesNow(context) }) {
                        Icon(Icons.Default.Sync, contentDescription = "Refresh")
                    }
                }
            },
            text = {
                if (syncedCalls.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No synced calls yet. Tap 'Sync History Now' after linking your other device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(syncedCalls) { record ->
                            val typeIcon = when (record.callTypeInt) {
                                CallLog.Calls.OUTGOING_TYPE -> Icons.Default.CallMade
                                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> Icons.Default.CallMissed
                                else -> Icons.Default.CallReceived
                            }
                            val typeTint = when (record.callTypeInt) {
                                CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(typeTint.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = typeIcon,
                                            contentDescription = null,
                                            tint = typeTint,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = record.contactName.ifBlank {
                                                formatPhoneNumberForDisplay(record.remoteNumber)
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${formatPhoneNumberForDisplay(record.remoteNumber)} • ${record.sourceDeviceName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${dateFormat.format(Date(record.timestamp))} (${record.durationSec}s)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSyncedHistoryDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
