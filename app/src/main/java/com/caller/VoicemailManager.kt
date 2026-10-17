package com.caller

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.VoicemailContract
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VoicemailItem(
    val id: Long,
    val number: String,
    val date: Long,
    val durationSec: Long,
    val isRead: Boolean,
    val audioUri: Uri,
    /** true = message recorded by our own call screening, false = carrier Visual Voicemail. */
    val isLocal: Boolean = false,
    /** Set for Twilio voicemails from the voicemail server (audio is downloaded on play). */
    val remoteId: String? = null,
    val transcript: String? = null
)

/**
 * Reads carrier Visual Voicemail from the system VoicemailContract provider.
 * READ/WRITE_VOICEMAIL are granted automatically by the Dialer role, so there is no runtime prompt.
 * Carriers without Visual Voicemail (most Indian carriers) leave this table empty; the UI then
 * offers "Call voicemail" instead.
 */
object VoicemailManager {
    private const val TAG = "VoicemailManager"
    private const val READ_VOICEMAIL = "com.android.voicemail.permission.READ_VOICEMAIL"
    private const val WRITE_VOICEMAIL = "com.android.voicemail.permission.WRITE_VOICEMAIL"

    fun canRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, READ_VOICEMAIL) == PackageManager.PERMISSION_GRANTED

    suspend fun getVoicemails(context: Context): List<VoicemailItem> = withContext(Dispatchers.IO) {
        if (!canRead(context)) return@withContext emptyList()
        val result = mutableListOf<VoicemailItem>()
        val projection = arrayOf(
            VoicemailContract.Voicemails._ID,
            VoicemailContract.Voicemails.NUMBER,
            VoicemailContract.Voicemails.DATE,
            VoicemailContract.Voicemails.DURATION,
            VoicemailContract.Voicemails.IS_READ,
            VoicemailContract.Voicemails.HAS_CONTENT
        )
        try {
            context.contentResolver.query(
                VoicemailContract.Voicemails.CONTENT_URI,
                projection,
                "${VoicemailContract.Voicemails.DELETED} = 0",
                null,
                "${VoicemailContract.Voicemails.DATE} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(VoicemailContract.Voicemails._ID)
                val numberCol = c.getColumnIndexOrThrow(VoicemailContract.Voicemails.NUMBER)
                val dateCol = c.getColumnIndexOrThrow(VoicemailContract.Voicemails.DATE)
                val durationCol = c.getColumnIndexOrThrow(VoicemailContract.Voicemails.DURATION)
                val readCol = c.getColumnIndexOrThrow(VoicemailContract.Voicemails.IS_READ)
                val contentCol = c.getColumnIndexOrThrow(VoicemailContract.Voicemails.HAS_CONTENT)
                while (c.moveToNext()) {
                    if (c.getInt(contentCol) != 1) continue
                    val id = c.getLong(idCol)
                    result += VoicemailItem(
                        id = id,
                        number = c.getString(numberCol).orEmpty().ifBlank { "Unknown" },
                        date = c.getLong(dateCol),
                        durationSec = c.getLong(durationCol),
                        isRead = c.getInt(readCol) == 1,
                        audioUri = ContentUris.withAppendedId(VoicemailContract.Voicemails.CONTENT_URI, id)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "query failed: ${e.message}")
        }
        result
    }

    private const val LOCAL_PREFS = "local_voicemail"
    private const val LOCAL_READ_KEY = "read_ids"

    /** Messages recorded by call screening ("Voicemail_<number>_<time>.m4a" files this app saved). */
    suspend fun getLocalVoicemails(context: Context): List<VoicemailItem> = withContext(Dispatchers.IO) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return@withContext emptyList()
        val readIds = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
            .getStringSet(LOCAL_READ_KEY, emptySet()).orEmpty()
        val result = mutableListOf<VoicemailItem>()
        val collection = android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
            android.provider.MediaStore.Audio.Media.DATE_ADDED,
            android.provider.MediaStore.Audio.Media.DURATION
        )
        try {
            context.contentResolver.query(
                collection,
                projection,
                "${android.provider.MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
                arrayOf("${MyInCallService.VOICEMAIL_FILE_PREFIX}%"),
                "${android.provider.MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATE_ADDED)
                val durCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION)
                val seenNames = HashSet<String>()
                while (c.moveToNext()) {
                    val name = c.getString(nameCol).orEmpty()
                    // The recorder exports each file to two folders; show it once.
                    if (!name.startsWith("${MyInCallService.VOICEMAIL_FILE_PREFIX}_") || !seenNames.add(name)) continue
                    val id = c.getLong(idCol)
                    val number = name.split('_').getOrNull(1).orEmpty().takeIf { it != "unknown" } ?: "Unknown"
                    result += VoicemailItem(
                        id = id,
                        number = number,
                        date = c.getLong(dateCol) * 1000L,
                        durationSec = c.getLong(durCol) / 1000L,
                        isRead = readIds.contains(id.toString()),
                        audioUri = ContentUris.withAppendedId(collection, id),
                        isLocal = true
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "local voicemail query failed: ${e.message}")
        }
        result
    }

    suspend fun markLocalAsRead(context: Context, id: Long) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(LOCAL_READ_KEY, emptySet()).orEmpty() + id.toString()
        prefs.edit().putStringSet(LOCAL_READ_KEY, ids).apply()
    }

    suspend fun markAsRead(context: Context, id: Long) = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, WRITE_VOICEMAIL) != PackageManager.PERMISSION_GRANTED) return@withContext
        try {
            val values = ContentValues().apply { put(VoicemailContract.Voicemails.IS_READ, 1) }
            context.contentResolver.update(
                ContentUris.withAppendedId(VoicemailContract.Voicemails.CONTENT_URI, id), values, null, null
            )
        } catch (e: Exception) {
            Log.e(TAG, "markAsRead failed: ${e.message}")
        }
    }

    /** Dials the carrier voicemail box of the default SIM through Telecom (voicemail: scheme). */
    fun callCarrierVoicemail(context: Context): Boolean {
        return try {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.placeCall(Uri.fromParts("voicemail", "", null), null)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "voicemail call not permitted: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "voicemail call failed: ${e.message}")
            false
        }
    }
}
