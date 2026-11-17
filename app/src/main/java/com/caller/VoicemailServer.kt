package com.caller

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder

// Voicemail server (Cloudflare Worker, see /voicemail-server). Blank = Twilio voicemail off.
const val VOICEMAIL_SERVER_URL = "https://caller-voicemail.vishwacaller.workers.dev"

/** Twilio number that answers forwarded calls, in full international format. */
const val TWILIO_VOICEMAIL_NUMBER = "+15104910090"

private const val VOICEMAIL_PREFS = "voicemail_prefs"
private const val KEY_FORWARDING_ENABLED = "forwarding_enabled"
private const val KEY_VOICEMAIL_KEY = "voicemail_key"

/**
 * Carrier call forwarding to the Twilio number (standard GSM codes):
 *   **004*NUMBER#          no answer + busy + unreachable together (try this first)
 *   **61*NUMBER**SECONDS#  no answer (after SECONDS: 5..30)
 *   **67*NUMBER#           busy
 *   **62*NUMBER#           unreachable / switched off
 *   ##004#                 cancel all conditional forwarding
 */
object VoicemailForwarding {
    fun enableAllCode() = "**004*$TWILIO_VOICEMAIL_NUMBER#"
    fun noAnswerCode(ringSeconds: Int = 20) = "**61*$TWILIO_VOICEMAIL_NUMBER**${ringSeconds.coerceIn(5, 30) / 5 * 5}#"
    fun busyCode() = "**67*$TWILIO_VOICEMAIL_NUMBER#"
    fun unreachableCode() = "**62*$TWILIO_VOICEMAIL_NUMBER#"
    const val DISABLE_CODE = "##004#"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(VOICEMAIL_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FORWARDING_ENABLED, false)

    fun setEnabledFlag(context: Context, enabled: Boolean) {
        context.getSharedPreferences(VOICEMAIL_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FORWARDING_ENABLED, enabled).apply()
    }

    /**
     * Sends one MMI code to the carrier; the phone shows the carrier's reply in a system dialog.
     * As the default dialer the app can hand it to Telecom; otherwise it dials the code.
     */
    @SuppressLint("MissingPermission")
    fun sendCode(context: Context, code: String) {
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom?.defaultDialerPackage == context.packageName) {
            try {
                if (telecom.handleMmi(code)) return
            } catch (_: SecurityException) {
                // fall through to ACTION_CALL
            }
        }
        context.startActivity(
            Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(code))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** The voicemail key the owner chose (the voicemail server's VOICEMAIL_KEY secret). Kept on this phone only. */
internal object VoicemailKeyStore {
    fun get(context: Context): String? =
        context.getSharedPreferences(VOICEMAIL_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VOICEMAIL_KEY, null)?.takeIf { voicemailKeyProblem(it) == null }

    fun isSet(context: Context): Boolean = get(context) != null

    fun save(context: Context, key: String) {
        context.getSharedPreferences(VOICEMAIL_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_VOICEMAIL_KEY, key.trim()).apply()
    }
}

/** Twilio voicemails from the server. Signs in with the voicemail key (the server's VOICEMAIL_KEY secret). */
object VoicemailServerClient {
    fun isAvailable(context: Context): Boolean =
        VOICEMAIL_SERVER_URL.isNotBlank() && VoicemailKeyStore.isSet(context)

    suspend fun list(context: Context): List<VoicemailItem> = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) return@withContext emptyList()
        val body = request(context, "GET", "/voicemails") ?: return@withContext emptyList()
        val arr = JSONArray(body)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val remoteId = o.getString("id")
            VoicemailItem(
                id = remoteId.hashCode().toLong(),
                number = o.optString("callerName").takeIf { it.isNotBlank() && it != "null" }
                    ?: o.optString("from", "Unknown"),
                date = o.optLong("createdAt"),
                durationSec = o.optLong("durationSec"),
                isRead = o.optBoolean("heard"),
                audioUri = Uri.EMPTY,
                remoteId = remoteId,
                transcript = o.optString("transcript").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }

    suspend fun markHeard(context: Context, remoteId: String) = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) return@withContext
        runCatching { request(context, "POST", "/voicemails/${enc(remoteId)}/heard") }
    }

    suspend fun delete(context: Context, remoteId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) return@withContext false
        runCatching { request(context, "DELETE", "/voicemails/${enc(remoteId)}") != null }.getOrDefault(false)
    }

    /**
     * Downloads the audio into the cache and returns a local file Uri for the shared player.
     * (The system media player can't reach the server on Jio, which blocks *.workers.dev DNS.)
     */
    suspend fun downloadAudio(context: Context, remoteId: String): Uri? = withContext(Dispatchers.IO) {
        if (!isAvailable(context)) return@withContext null
        val file = File(context.cacheDir, "voicemail_${remoteId.filter { it.isLetterOrDigit() }}.mp3")
        if (file.length() > 0L) return@withContext Uri.fromFile(file)
        runCatching {
            val req = Request.Builder()
                .url(VOICEMAIL_SERVER_URL.trimEnd('/') + "/voicemails/${enc(remoteId)}/audio")
                .withLogin(context)
                .build()
            serverHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.byteStream()?.use { input -> file.outputStream().use { input.copyTo(it) } }
                Uri.fromFile(file)
            }
        }.getOrNull()
    }

    /** Body text on 2xx (empty for 204), null otherwise. */
    private fun request(context: Context, method: String, path: String): String? {
        val req = Request.Builder()
            .url(VOICEMAIL_SERVER_URL.trimEnd('/') + path)
            .method(method, if (method == "POST") ByteArray(0).toRequestBody(null) else null)
            .withLogin(context)
            .build()
        serverHttpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string().orEmpty()
        }
    }

    private fun Request.Builder.withLogin(context: Context): Request.Builder {
        VoicemailKeyStore.get(context)?.let { header("X-Voicemail-Key", it) }
        // A server from before the secret key still reads the P2P login key, so it is sent as well until that server is replaced.
        p2pServerToken(context)?.let { header("Authorization", "Bearer $it") }
        return this
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
