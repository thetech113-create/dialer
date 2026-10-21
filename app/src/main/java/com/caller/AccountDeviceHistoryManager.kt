package com.caller

import android.content.Context
import android.os.Build
import android.util.Log
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
