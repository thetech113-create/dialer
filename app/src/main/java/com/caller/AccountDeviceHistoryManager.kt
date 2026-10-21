package com.caller

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Account-Based Multi-Device Call History Management.
 * Supports multiple registered phones under the same authenticated account.
 * Hides call history entries on a secondary device when the destination number
 * belongs to that device under the same account, while leaving Android native call logs 100% untouched.
 */
data class AccountDeviceRegistration(
    val accountId: String,
    val deviceId: String,
    val deviceName: String,
    val verifiedNumbers: List<String>,
    val isCurrentDevice: Boolean = false
)

object AccountDeviceHistoryManager {
    private const val TAG = "AccountDeviceHistory"
    private const val PREFS_NAME = "account_device_history_prefs"
    private const val KEY_ACCOUNT_ID = "account_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_VERIFIED_NUMBERS_JSON = "verified_numbers_json"
    private const val KEY_PEER_DEVICES_JSON = "peer_devices_json"
    private const val KEY_FILTERING_ENABLED = "multi_device_filtering_enabled"

    private fun getPrefs(context: Context) =
        p2pContext(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isFilteringEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_FILTERING_ENABLED, true)

    fun setFilteringEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FILTERING_ENABLED, enabled).apply()
    }

    fun getAccountId(context: Context): String {
        val prefs = getPrefs(context)
        var accId = prefs.getString(KEY_ACCOUNT_ID, null)
        if (accId.isNullOrBlank()) {
            accId = p2pServerVerifiedNumber(context) ?: "default_account"
            prefs.edit().putString(KEY_ACCOUNT_ID, accId).apply()
        }
        return accId
    }

    fun setAccountId(context: Context, accountId: String) {
        if (accountId.isBlank()) return
        getPrefs(context).edit().putString(KEY_ACCOUNT_ID, accountId.trim()).apply()
    }

    fun getCurrentDeviceId(context: Context): String {
        val prefs = getPrefs(context)
        var devId = prefs.getString(KEY_DEVICE_ID, null)
        if (devId.isNullOrBlank()) {
            devId = "dev_" + Build.MODEL.replace(" ", "_") + "_" + System.currentTimeMillis()
            prefs.edit().putString(KEY_DEVICE_ID, devId).apply()
        }
        return devId
    }

    fun getCurrentDeviceName(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_DEVICE_NAME, null)
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
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
        return numbers.distinct()
    }

    fun registerVerifiedNumber(context: Context, number: String) {
        if (number.isBlank()) return
        val current = getVerifiedNumbers(context).toMutableList()
        if (!current.contains(number.trim())) {
            current.add(number.trim())
            val array = JSONArray(current)
            getPrefs(context).edit().putString(KEY_VERIFIED_NUMBERS_JSON, array.toString()).apply()
        }
    }

    fun removeVerifiedNumber(context: Context, number: String) {
        val current = getVerifiedNumbers(context).filterNot { it == number.trim() }
        val array = JSONArray(current)
        getPrefs(context).edit().putString(KEY_VERIFIED_NUMBERS_JSON, array.toString()).apply()
    }

    fun registerPeerDevice(context: Context, peer: AccountDeviceRegistration) {
        val prefs = getPrefs(context)
        val peers = getPeerDevices(context).toMutableList()
        peers.removeAll { it.deviceId == peer.deviceId }
        peers.add(peer)

        val array = JSONArray()
        peers.forEach { p ->
            array.put(JSONObject().apply {
                put("accountId", p.accountId)
                put("deviceId", p.deviceId)
                put("deviceName", p.deviceName)
                put("verifiedNumbers", JSONArray(p.verifiedNumbers))
            })
        }
        prefs.edit().putString(KEY_PEER_DEVICES_JSON, array.toString()).apply()
    }

    fun removePeerDevice(context: Context, deviceId: String) {
        val prefs = getPrefs(context)
        val peers = getPeerDevices(context).filterNot { it.deviceId == deviceId }
        val array = JSONArray()
        peers.forEach { p ->
            array.put(JSONObject().apply {
                put("accountId", p.accountId)
                put("deviceId", p.deviceId)
                put("deviceName", p.deviceName)
                put("verifiedNumbers", JSONArray(p.verifiedNumbers))
            })
        }
        prefs.edit().putString(KEY_PEER_DEVICES_JSON, array.toString()).apply()
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
                            verifiedNumbers = nums
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Determines whether a call history item should be excluded/hidden from THIS viewing device's application history.
     *
     * Rule:
     * Exclude call entry if:
     * 1. Multi-device filtering is enabled.
     * 2. The remote destination number is verified as belonging to THIS viewing device.
     * 3. Native Android call logs remain 100% UNTOUCHED!
     */
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
}

/**
 * Drop-in Settings Card for configuring Multi-Device Account Sync and Verified Numbers.
 */
@Composable
fun MultiDeviceSettingsCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var filteringEnabled by remember {
        mutableStateOf(AccountDeviceHistoryManager.isFilteringEnabled(context))
    }
    var accountId by remember {
        mutableStateOf(AccountDeviceHistoryManager.getAccountId(context))
    }
    var verifiedNumbers by remember {
        mutableStateOf(AccountDeviceHistoryManager.getVerifiedNumbers(context))
    }
    var peerDevices by remember {
        mutableStateOf(AccountDeviceHistoryManager.getPeerDevices(context))
    }

    var showEditAccountDialog by remember { mutableStateOf(false) }
    var accountInput by remember { mutableStateOf(accountId) }

    var showAddNumberDialog by remember { mutableStateOf(false) }
    var newNumberInput by remember { mutableStateOf("") }

    var showAddPeerDialog by remember { mutableStateOf(false) }
    var peerNameInput by remember { mutableStateOf("") }
    var peerNumberInput by remember { mutableStateOf("") }

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
            Text(
                text = "Multi-Device Account Sync",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

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
                            text = "Account ID / Primary Number",
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

            // 2. Hide Self-Calls Toggle
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
                            "Hides calls made to this phone from another device on the same account (Native Android logs remain untouched)"
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
                        filteringEnabled = enabled
                        AccountDeviceHistoryManager.setFilteringEnabled(context, enabled)
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(14.dp))

            // 3. This Device's Numbers
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
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
                            "No verified number set for this device"
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
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Number",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Number", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(14.dp))

            // 4. Linked Peer Devices
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Linked Account Devices",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Link your other phone (e.g. OPPO or Redmi) so calls between them are managed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { showAddPeerDialog = true },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Linked Device",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Link Device", fontSize = 13.sp)
                }
            }

            if (peerDevices.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    peerDevices.forEach { peer ->
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
                                    imageVector = Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
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
                                }
                                IconButton(
                                    onClick = {
                                        AccountDeviceHistoryManager.removePeerDevice(context, peer.deviceId)
                                        peerDevices = AccountDeviceHistoryManager.getPeerDevices(context)
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
                        text = "Enter your primary account phone number. Devices with numbers linked to this Account ID share multi-device call history controls.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = accountInput,
                        onValueChange = { accountInput = it },
                        label = { Text("Account Phone Number (e.g. +91 98765 43210)") },
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
                        accountId = AccountDeviceHistoryManager.getAccountId(context)
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
            title = { Text("Add Number for This Device", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter the phone number assigned to this phone's SIM so calls to this number from your other account devices are identified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newNumberInput,
                        onValueChange = { newNumberInput = it },
                        label = { Text("Phone Number (e.g. +91 98765 43210)") },
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
                        verifiedNumbers = AccountDeviceHistoryManager.getVerifiedNumbers(context)
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

    // Dialog 3: Add Linked Peer Device
    if (showAddPeerDialog) {
        AlertDialog(
            onDismissRequest = { showAddPeerDialog = false },
            title = { Text("Link Other Device on Account", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter your other phone's device name (e.g. OPPO A6 Pro 5G) and its phone number.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = peerNameInput,
                        onValueChange = { peerNameInput = it },
                        label = { Text("Device Name (e.g. OPPO A6 Pro 5G)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = peerNumberInput,
                        onValueChange = { peerNumberInput = it },
                        label = { Text("Peer Phone Number (e.g. +91 91234 56789)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = peerNameInput.isNotBlank() && peerNumberInput.isNotBlank(),
                    onClick = {
                        val newPeer = AccountDeviceRegistration(
                            accountId = accountId,
                            deviceId = "peer_" + System.currentTimeMillis(),
                            deviceName = peerNameInput,
                            verifiedNumbers = listOf(peerNumberInput)
                        )
                        AccountDeviceHistoryManager.registerPeerDevice(context, newPeer)
                        peerDevices = AccountDeviceHistoryManager.getPeerDevices(context)
                        peerNameInput = ""
                        peerNumberInput = ""
                        showAddPeerDialog = false
                    }
                ) {
                    Text("Save & Link")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPeerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
