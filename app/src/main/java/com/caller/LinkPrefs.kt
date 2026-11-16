package com.caller

import android.content.Context
import android.util.Base64
import com.caller.link.PairStore
import java.util.UUID

class PairedPc(val id: String, val name: String)

/** Device identity and the pairing keys of trusted PCs. Excluded from backups (res/xml). */
class LinkPrefs(context: Context) : PairStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    /** Whether the link should come back after a reboot; follows the user's last start/stop. */
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    override fun key(pcId: String): ByteArray? =
        prefs.getString(PREFIX_KEY + pcId, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    override fun save(pcId: String, pcName: String, key: ByteArray) {
        prefs.edit()
            .putString(PREFIX_KEY + pcId, Base64.encodeToString(key, Base64.NO_WRAP))
            .putString(PREFIX_NAME + pcId, pcName)
            .apply()
    }

    override fun remove(pcId: String) {
        prefs.edit().remove(PREFIX_KEY + pcId).remove(PREFIX_NAME + pcId).apply()
    }

    fun pairedPcs(): List<PairedPc> = prefs.all.keys
        .filter { it.startsWith(PREFIX_KEY) }
        .map { it.removePrefix(PREFIX_KEY) }
        .map { PairedPc(it, prefs.getString(PREFIX_NAME + it, null) ?: "PC") }
        .sortedBy { it.name }

    private companion object {
        const val FILE = "pc_link_store"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_AUTO_START = "auto_start"
        const val PREFIX_KEY = "key_"
        const val PREFIX_NAME = "name_"
    }
}
