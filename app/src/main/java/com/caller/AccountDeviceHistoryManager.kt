package com.caller

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.SubscriptionManager
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class AccountDeviceRegistration(
    val accountId: String,
    val deviceId: String,
    val deviceName: String,
    val verifiedNumbers: List<String>,
    val lastSyncTimestamp: Long = 0L,
    val isCurrentDevice: Boolean = false
)

data class SyncedCallRecord(
    val remoteNumber: String,
    val contactName: String,
    val callTypeInt: Int,
    val timestamp: Long,
    val durationSec: Long,
    val sourceDeviceId: String,
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
    private const val KEY_LAST_ACCOUNT_SYNC_MS = "last_account_sync_ms"

    private const val ACCOUNT_CLOUD_BUCKET_URL = "https://kvdb.io/A9xK3mP7vR2qL8nW4jY6tB"
    private const val TCP_SYNC_PORT = 8902
    private const val UDP_DISCOVERY_PORT = 8903
    private const val MAX_STORED_SYNCED_CALLS = 300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineRunning = AtomicBoolean(false)
    private val syncInProgress = AtomicBoolean(false)
    private val stateListeners = CopyOnWriteArrayList<() -> Unit>()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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
        var accId = prefs.getString(KEY_ACCOUNT_ID, null)?.trim()
        if (accId.isNullOrBlank()) {
            accId = p2pServerVerifiedNumber(context)
                ?: detectSimPhoneNumbers(context).firstOrNull()
                ?: "default_account"
            prefs.edit().putString(KEY_ACCOUNT_ID, accId).apply()
        }
        return accId
    }

    private fun getCanonicalAccountKey(context: Context): String {
        val raw = getAccountId(context).trim()
        val snap = buildP2PNumberSnapshot(raw)
        val canonical = when {
            snap.last10.length == 10 -> snap.last10
            snap.normalized.isNotBlank() -> snap.normalized
            else -> raw.lowercase(Locale.ROOT)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("caller_acct_v2|$canonical".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun setAccountId(context: Context, accountId: String) {
        val clean = accountId.trim()
        if (clean.isBlank()) return
        getPrefs(context).edit().putString(KEY_ACCOUNT_ID, clean).apply()
        notifyStateChanged()
        syncAccountNow(context, showToast = true)
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
            syncAccountNow(context, showToast = false)
        }
    }

    fun removeVerifiedNumber(context: Context, number: String) {
        val current = getVerifiedNumbers(context).filterNot { it == number.trim() }
        val array = JSONArray(current)
        getPrefs(context).edit().putString(KEY_VERIFIED_NUMBERS_JSON, array.toString()).apply()
        notifyStateChanged()
        syncAccountNow(context, showToast = false)
    }

    fun registerPeerDevice(context: Context, peer: AccountDeviceRegistration) {
        if (peer.deviceId == getCurrentDeviceId(context)) return
        val prefs = getPrefs(context)
        val peers = getPeerDevices(context).toMutableList()
        peers.removeAll { it.deviceId == peer.deviceId || it.deviceName.equals(peer.deviceName, ignoreCase = true) }
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
                put("lastSyncTimestamp", p.lastSyncTimestamp)
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
                            lastSyncTimestamp = obj.optLong("lastSyncTimestamp", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun getLastAccountSyncTime(context: Context): Long =
        getPrefs(context).getLong(KEY_LAST_ACCOUNT_SYNC_MS, 0L)

    fun startSyncEngine(context: Context) {
        val appCtx = context.applicationContext
        if (!engineRunning.compareAndSet(false, true)) return

        purgeLegacySyncSmsIfPresent(appCtx)

        scope.launch {
            runCatching {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(TCP_SYNC_PORT))
                while (engineRunning.get()) {
                    val client = server.accept()
                    scope.launch { handleTcpAccountPeer(appCtx, client) }
                }
            }
        }

        scope.launch {
            runCatching {
                val udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                }
                val buffer = ByteArray(1024)
                while (engineRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    val senderIp = packet.address?.hostAddress.orEmpty()
                    handleUdpDiscoveryBeacon(appCtx, senderIp, msg)
                }
            }
        }

        scope.launch {
            while (engineRunning.get()) {
                syncAccountNow(appCtx, showToast = false)
                delay(45_000L)
            }
        }
    }

    fun syncAccountNow(context: Context, showToast: Boolean = true) {
        val appCtx = context.applicationContext
        startSyncEngine(appCtx)

        if (!syncInProgress.compareAndSet(false, true)) {
            if (showToast) toast(appCtx, "Account sync in progress...")
            return
        }
        if (showToast) {
            toast(appCtx, "Syncing account ${formatPhoneNumberForDisplay(getAccountId(appCtx))}...")
        }

        scope.launch {
            try {
                broadcastUdpAccountBeacon(appCtx)
                val cloudSuccess = performCloudAccountSync(appCtx)

                getPrefs(appCtx).edit().putLong(KEY_LAST_ACCOUNT_SYNC_MS, System.currentTimeMillis()).apply()
                notifyStateChanged()

                if (showToast) {
                    val peerCount = getPeerDevices(appCtx).size
                    val callCount = getSyncedCallHistory(appCtx).size
                    if (cloudSuccess || peerCount > 0) {
                        toast(appCtx, "Account synced! $peerCount linked device(s), $callCount synced calls.")
                    } else {
                        toast(appCtx, "Account state updated. Sign in with the same Account ID on your other phone.")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Account sync error: ${e.message}")
            } finally {
                syncInProgress.set(false)
            }
        }
    }

    private fun performCloudAccountSync(context: Context): Boolean {
        return runCatching {
            val accountKey = getCanonicalAccountKey(context)
            val endpoint = "$ACCOUNT_CLOUD_BUCKET_URL/$accountKey"

            val getReq = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("User-Agent", "Caller-Account-Sync")
                .get()
                .build()

            var remoteAccountObj: JSONObject? = null
            http.newCall(getReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string()?.trim().orEmpty()
                    if (bodyStr.startsWith("{")) {
                        remoteAccountObj = JSONObject(bodyStr)
                    }
                }
            }

            if (remoteAccountObj != null) {
                ingestAccountSnapshotJson(context, remoteAccountObj!!)
            }

            val mergedSnapshot = buildFullAccountSnapshotJson(context, remoteAccountObj)
            val postBody = mergedSnapshot.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val putReq = Request.Builder()
                .url(endpoint)
                .header("User-Agent", "Caller-Account-Sync")
                .post(postBody)
                .build()

            http.newCall(putReq).execute().use { resp ->
                resp.isSuccessful
            }
        }.onFailure { e ->
            Log.w(TAG, "Cloud account sync skipped/failed: ${e.message}")
        }.getOrDefault(false)
    }

    private fun buildFullAccountSnapshotJson(context: Context, previousRemote: JSONObject?): JSONObject {
        val myDeviceId = getCurrentDeviceId(context)
        val myDeviceName = getCurrentDeviceName(context)
        val myAccountId = getAccountId(context)
        val myNumbers = getVerifiedNumbers(context)
        val now = System.currentTimeMillis()

        val devicesMap = linkedMapOf<String, JSONObject>()
        previousRemote?.optJSONArray("devices")?.let { arr ->
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val id = d.optString("deviceId")
                if (id.isNotBlank()) devicesMap[id] = d
            }
        }
        getPeerDevices(context).forEach { peer ->
            if (peer.deviceId.isNotBlank()) {
                devicesMap[peer.deviceId] = JSONObject().apply {
                    put("accountId", myAccountId)
                    put("deviceId", peer.deviceId)
                    put("deviceName", peer.deviceName)
                    put("verifiedNumbers", JSONArray(peer.verifiedNumbers))
                    put("lastSyncTimestamp", peer.lastSyncTimestamp)
                }
            }
        }
        devicesMap[myDeviceId] = JSONObject().apply {
            put("accountId", myAccountId)
            put("deviceId", myDeviceId)
            put("deviceName", myDeviceName)
            put("verifiedNumbers", JSONArray(myNumbers))
            put("lastSyncTimestamp", now)
        }

        val combinedCalls = mutableListOf<SyncedCallRecord>()
        combinedCalls.addAll(queryLocalDeviceCallLog(context, maxCount = 120))
        combinedCalls.addAll(getSyncedCallHistory(context))

        val deduplicatedCalls = mutableListOf<SyncedCallRecord>()
        for (call in combinedCalls.sortedByDescending { it.timestamp }) {
            val isEmergency = call.remoteNumber.trim() in setOf("100", "112", "101", "102", "108", "911")
            if (isEmergency) continue
            val exists = deduplicatedCalls.any {
                buildP2PNumberSnapshot(it.remoteNumber).last10 == buildP2PNumberSnapshot(call.remoteNumber).last10 &&
                    kotlin.math.abs(it.timestamp - call.timestamp) < 5000L
            }
            if (!exists) {
                deduplicatedCalls.add(call)
                if (deduplicatedCalls.size >= 200) break
            }
        }

        val callsJson = JSONArray()
        deduplicatedCalls.forEach { c ->
            callsJson.put(JSONObject().apply {
                put("remoteNumber", c.remoteNumber)
                put("contactName", c.contactName)
                put("callTypeInt", c.callTypeInt)
                put("timestamp", c.timestamp)
                put("durationSec", c.durationSec)
                put("sourceDeviceId", c.sourceDeviceId)
                put("sourceDeviceName", c.sourceDeviceName)
            })
        }

        return JSONObject().apply {
            put("accountId", myAccountId)
            put("updatedAt", now)
            put("devices", JSONArray(devicesMap.values.toList()))
            put("calls", callsJson)
        }
    }

    private fun ingestAccountSnapshotJson(context: Context, root: JSONObject) {
        val myDeviceId = getCurrentDeviceId(context)
        val myAccountId = getAccountId(context)
        val prefs = getPrefs(context)

        val devicesArr = root.optJSONArray("devices")
        if (devicesArr != null) {
            val currentPeers = getPeerDevices(context).toMutableList()
            for (i in 0 until devicesArr.length()) {
                val d = devicesArr.optJSONObject(i) ?: continue
                val devId = d.optString("deviceId").trim()
                if (devId.isBlank() || devId == myDeviceId) continue
                val devName = d.optString("deviceName").ifBlank { "Account Phone" }
                val numsArr = d.optJSONArray("verifiedNumbers")
                val nums = buildList {
                    if (numsArr != null) {
                        for (j in 0 until numsArr.length()) {
                            val n = numsArr.optString(j).trim()
                            if (n.isNotEmpty()) add(n)
                        }
                    }
                }
                val peerReg = AccountDeviceRegistration(
                    accountId = myAccountId,
                    deviceId = devId,
                    deviceName = devName,
                    verifiedNumbers = nums,
                    lastSyncTimestamp = System.currentTimeMillis()
                )
                currentPeers.removeAll { it.deviceId == devId || it.deviceName.equals(devName, ignoreCase = true) }
                currentPeers.add(0, peerReg)
            }
            savePeersList(prefs, currentPeers)
        }

        val callsArr = root.optJSONArray("calls")
        if (callsArr != null) {
            val existingCalls = getSyncedCallHistory(context).toMutableList()
            var newlyMergedCount = 0

            for (i in 0 until callsArr.length()) {
                val c = callsArr.optJSONObject(i) ?: continue
                val num = c.optString("remoteNumber").ifBlank { c.optString("number") }.trim()
                if (num.isBlank()) continue
                if (num in setOf("100", "112", "101", "102", "108", "911")) continue

                val srcDevId = c.optString("sourceDeviceId")
                val srcDevName = c.optString("sourceDeviceName").ifBlank { "Linked Device" }
                val ts = c.optLong("timestamp", c.optLong("date", 0L))
                if (ts <= 0L) continue

                val record = SyncedCallRecord(
                    remoteNumber = num,
                    contactName = c.optString("contactName").ifBlank { c.optString("name") },
                    callTypeInt = c.optInt("callTypeInt", c.optInt("type", CallLog.Calls.INCOMING_TYPE)),
                    timestamp = ts,
                    durationSec = c.optLong("durationSec", c.optLong("duration", 0L)),
                    sourceDeviceId = srcDevId,
                    sourceDeviceName = srcDevName
                )

                if (srcDevId != myDeviceId) {
                    val isDup = existingCalls.any {
                        buildP2PNumberSnapshot(it.remoteNumber).last10 == buildP2PNumberSnapshot(record.remoteNumber).last10 &&
                            kotlin.math.abs(it.timestamp - record.timestamp) < 5000L
                    }
                    if (!isDup) {
                        existingCalls.add(record)
                        newlyMergedCount++
                        if (isMergeToNativeCallLogEnabled(context)) {
                            insertIntoNativeCallLogIfMissing(context, record)
                        }
                    }
                }
            }

            if (newlyMergedCount > 0) {
                val sorted = existingCalls.sortedByDescending { it.timestamp }.take(MAX_STORED_SYNCED_CALLS)
                val saveArr = JSONArray()
                sorted.forEach { c ->
                    saveArr.put(JSONObject().apply {
                        put("remoteNumber", c.remoteNumber)
                        put("contactName", c.contactName)
                        put("callTypeInt", c.callTypeInt)
                        put("timestamp", c.timestamp)
                        put("durationSec", c.durationSec)
                        put("sourceDeviceId", c.sourceDeviceId)
                        put("sourceDeviceName", c.sourceDeviceName)
                    })
                }
                prefs.edit().putString(KEY_SYNCED_CALLS_JSON, saveArr.toString()).apply()
            }
        }
        notifyStateChanged()
    }

    private fun broadcastUdpAccountBeacon(context: Context) {
        runCatching {
            val accountKey = getCanonicalAccountKey(context)
            val myDeviceId = getCurrentDeviceId(context)
            val beacon = "CALLER_ACCT_SYNC|$accountKey|$myDeviceId|$TCP_SYNC_PORT"
            val bytes = beacon.toByteArray(Charsets.UTF_8)

            DatagramSocket().use { socket ->
                socket.broadcast = true
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    if (!intf.isUp || intf.isLoopback) continue
                    for (ifaceAddr in intf.interfaceAddresses) {
                        val broadcast = ifaceAddr.broadcast ?: continue
                        runCatching {
                            socket.send(DatagramPacket(bytes, bytes.size, broadcast, UDP_DISCOVERY_PORT))
                        }
                    }
                }
                runCatching {
                    socket.send(
                        DatagramPacket(
                            bytes,
                            bytes.size,
                            InetAddress.getByName("255.255.255.255"),
                            UDP_DISCOVERY_PORT
                        )
                    )
                }
            }
        }
    }

    private fun handleUdpDiscoveryBeacon(context: Context, senderIp: String, msg: String) {
        if (!msg.startsWith("CALLER_ACCT_SYNC|")) return
        val parts = msg.split("|")
        val remoteAccountKey = parts.getOrNull(1).orEmpty()
        val remoteDeviceId = parts.getOrNull(2).orEmpty()
        val remotePort = parts.getOrNull(3)?.toIntOrNull() ?: TCP_SYNC_PORT

        if (remoteDeviceId == getCurrentDeviceId(context)) return
        if (remoteAccountKey != getCanonicalAccountKey(context)) return

        scope.launch {
            syncViaTcpWithSameAccountPeer(context, senderIp, remotePort)
        }
    }

    private fun syncViaTcpWithSameAccountPeer(context: Context, host: String, port: Int) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2500)
                socket.soTimeout = 4000
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

                val mySnapshot = buildFullAccountSnapshotJson(context, null)
                mySnapshot.put("accountKey", getCanonicalAccountKey(context))
                writer.println(mySnapshot.toString())

                val replyLine = reader.readLine()
                if (!replyLine.isNullOrBlank()) {
                    val replyObj = JSONObject(replyLine)
                    if (replyObj.optString("accountKey") == getCanonicalAccountKey(context)) {
                        ingestAccountSnapshotJson(context, replyObj)
                    }
                }
            }
        }
    }

    private fun handleTcpAccountPeer(context: Context, socket: Socket) {
        runCatching {
            socket.use { client ->
                client.soTimeout = 4000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8), true)

                val line = reader.readLine() ?: return@use
                val incomingObj = JSONObject(line)
                if (incomingObj.optString("accountKey") != getCanonicalAccountKey(context)) {
                    return@use
                }
                ingestAccountSnapshotJson(context, incomingObj)

                val replySnapshot = buildFullAccountSnapshotJson(context, incomingObj)
                replySnapshot.put("accountKey", getCanonicalAccountKey(context))
                writer.println(replySnapshot.toString())
            }
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
                    val num = obj.optString("remoteNumber")
                    if (num in setOf("100", "112", "101", "102", "108", "911")) continue
                    add(
                        SyncedCallRecord(
                            remoteNumber = num,
                            contactName = obj.optString("contactName"),
                            callTypeInt = obj.optInt("callTypeInt", CallLog.Calls.INCOMING_TYPE),
                            timestamp = obj.optLong("timestamp", 0L),
                            durationSec = obj.optLong("durationSec", 0L),
                            sourceDeviceId = obj.optString("sourceDeviceId", ""),
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
        val myDeviceId = getCurrentDeviceId(context)
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
                    if (number in setOf("100", "112", "101", "102", "108", "911")) continue
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
                                sourceDeviceId = myDeviceId,
                                sourceDeviceName = myDeviceName
                            )
                        )
                    }
                }
            }
        }
        return list
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

    private fun purgeLegacySyncSmsIfPresent(context: Context) {
        scope.launch {
            runCatching {
                context.contentResolver.delete(
                    android.provider.Telephony.Sms.CONTENT_URI,
                    "${android.provider.Telephony.Sms.BODY} LIKE ? OR ${android.provider.Telephony.Sms.BODY} LIKE ?",
                    arrayOf("#CSYNC1#%", "%~2~179%")
                )
            }
        }
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
    val lastSyncTime = remember(refreshTick) {
        AccountDeviceHistoryManager.getLastAccountSyncTime(context)
    }

    var showEditAccountDialog by remember { mutableStateOf(false) }
    var accountInput by remember { mutableStateOf(accountId) }

    var showAddNumberDialog by remember { mutableStateOf(false) }
    var newNumberInput by remember { mutableStateOf("") }

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
                    onClick = { AccountDeviceHistoryManager.syncAccountNow(context, showToast = true) }
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "Sync Account Now",
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
                            text = "Signed In Account ID",
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
                        if (lastSyncTime > 0L) {
                            val fmtTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(lastSyncTime))
                            Text(
                                text = "Cloud & Local Sync Active • Last: $fmtTime",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                    TextButton(onClick = {
                        accountInput = accountId
                        showEditAccountDialog = true
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Set ID")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Merge Synced Calls into Recents Toggle
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

            if (peerDevices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Account Linked Devices (${peerDevices.size})",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    peerDevices.forEach { peer ->
                        val syncTimeFmt = if (peer.lastSyncTimestamp > 0L) {
                            SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                                .format(Date(peer.lastSyncTimestamp))
                        } else {
                            "Synced via Account"
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
                                    imageVector = Icons.Default.CloudDone,
                                    contentDescription = null,
                                    tint = Color(0xFF4CAF50),
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
                                    if (peer.verifiedNumbers.isNotEmpty()) {
                                        Text(
                                            text = peer.verifiedNumbers.joinToString(", ") { formatPhoneNumberForDisplay(it) },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "Account Verified • $syncTimeFmt",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        AccountDeviceHistoryManager.removePeerDevice(context, peer.deviceId)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove Device",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 5. Action Buttons: Sync Account Now & View Synced Calls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { AccountDeviceHistoryManager.syncAccountNow(context, showToast = true) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sync Account Now", fontSize = 13.sp)
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

    // Dialog 1: Set Shared Account ID / Sign In
    if (showEditAccountDialog) {
        AlertDialog(
            onDismissRequest = { showEditAccountDialog = false },
            title = { Text("Sign In / Account ID", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter the same Account ID or main phone number on both of your devices (e.g. 9876543210). Both phones will automatically connect, list each other under Account Linked Devices, and sync call history securely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = accountInput,
                        onValueChange = { accountInput = it },
                        label = { Text("Shared Account ID / Number") },
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
                    Text("Save & Sync")
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
                        text = "Enter the phone number of the SIM inside THIS phone so your other account devices recognize it.",
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

    // Dialog 3: View Call History Synced from Account Devices
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
                    IconButton(onClick = { AccountDeviceHistoryManager.syncAccountNow(context, showToast = true) }) {
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
                            text = "No synced calls yet. Tap 'Sync Account Now' after signing into the same Account ID on your other phone.",
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
