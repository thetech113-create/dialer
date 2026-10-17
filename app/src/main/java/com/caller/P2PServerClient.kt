package com.caller

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// Free name server (Cloudflare Worker, see /server). Paste the address printed by `npx wrangler deploy`.
// Blank = server off: everything below does nothing and P2P works by silent SMS only, as before.
const val P2P_SERVER_URL = "https://caller-p2p.vishwacaller.workers.dev"

private const val SERVER_PREFS = "p2p_server"
private const val KEY_TOKEN = "token"
private const val KEY_NUMBER = "verified_number"
private const val KEY_PENDING_CODE = "pending_code"
private const val KEY_PENDING_NUMBER = "pending_number"
const val P2P_VERIFY_PREFIX = "VERIFY|"
private const val SERVER_TIMEOUT_MS = 8000

private val serverExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

private fun serverPrefs(context: Context) =
    p2pContext(context).getSharedPreferences(SERVER_PREFS, Context.MODE_PRIVATE)

fun isP2PServerEnabled(): Boolean = P2P_SERVER_URL.isNotBlank()

/** Verified number on the server, or null if this phone hasn't been verified yet. */
fun p2pServerVerifiedNumber(context: Context): String? =
    serverPrefs(context).takeIf { !it.getString(KEY_TOKEN, null).isNullOrBlank() }?.getString(KEY_NUMBER, null)

/** Login key from the number check; the voicemail server accepts the same key. */
internal fun p2pServerToken(context: Context): String? =
    serverPrefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

/**
 * Normal DNS first; if the provider can't (or won't) resolve the server — Jio and others block
 * *.workers.dev — ask Cloudflare/Google DNS-over-HTTPS, reached by their fixed IPs.
 * Also used by the voicemail server client.
 */
internal val serverHttpClient: OkHttpClient by lazy {
    val bootstrap = OkHttpClient.Builder()
        .connectTimeout(SERVER_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(SERVER_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .build()
    fun doh(url: String, vararg ips: String) = DnsOverHttps.Builder()
        .client(bootstrap)
        .url(url.toHttpUrl())
        .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
        .build()
    val cloudflareDoh = doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1")
    val googleDoh = doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4")
    val fallbackDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            for (dns in listOf(Dns.SYSTEM, cloudflareDoh, googleDoh)) {
                val found = runCatching { dns.lookup(hostname) }.getOrNull()
                if (!found.isNullOrEmpty()) return found
            }
            throw UnknownHostException("Cannot find $hostname (normal and secure DNS both failed)")
        }
    }
    bootstrap.newBuilder().dns(fallbackDns).build()
}

/** Returns HTTP status and parsed body (empty JSON on failure). */
private fun serverRequest(method: String, path: String, body: JSONObject? = null, token: String? = null): Pair<Int, JSONObject> {
    val request = Request.Builder()
        .url(P2P_SERVER_URL.trimEnd('/') + path)
        .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
        .apply { token?.let { header("Authorization", "Bearer $it") } }
        .build()
    serverHttpClient.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        return response.code to (runCatching { JSONObject(text) }.getOrDefault(JSONObject()))
    }
}

/**
 * First-time number check: the server hands out a code, and this phone sends it to ITS OWN number by
 * silent data SMS. Only the SIM that owns the number receives it ([handleP2PVerifySms] finishes).
 */
fun startP2PServerVerification(context: Context, ownNumber: String, onResult: (String) -> Unit) {
    val appContext = context.applicationContext
    val main = Handler(Looper.getMainLooper())
    serverExecutor.execute {
        val message = runCatching {
            val number = buildP2PNumberSnapshot(ownNumber).digits
            require(number.length >= 10) { "Enter your full mobile number" }
            check(
                androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.SEND_SMS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            ) { "SMS permission is off. Allow SMS for this app and try again." }
            val (status, json) = serverRequest("POST", "/verify/start", JSONObject().put("number", number))
            check(status == 200) { "Server error ($status)" }
            val code = json.getString("code")
            serverPrefs(appContext).edit()
                .putString(KEY_PENDING_CODE, code)
                .putString(KEY_PENDING_NUMBER, number)
                .apply()
            val smsManager = appContext.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            val destination = when {
                number.length == 10 -> "+91$number"
                number.length > 10 -> "+$number"
                else -> number
            }
            val verifyPayload = P2P_VERIFY_PREFIX + code
            // Report the network's send result on the card (OPPO drops this app's log lines).
            val dataSentAction = "${appContext.packageName}.VERIFY_DATA_SMS_SENT"
            val textSentAction = "${appContext.packageName}.VERIFY_TEXT_SMS_SENT"
            fun statusIntent(action: String) = android.app.PendingIntent.getBroadcast(
                appContext, action.hashCode(),
                android.content.Intent(action).setPackage(appContext.packageName),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val statusReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, intent: android.content.Intent) {
                    val ok = resultCode == android.app.Activity.RESULT_OK
                    Log.w(P2P_FORENSIC_TAG, "p2p_server_verify action=${intent.action?.substringAfterLast('.')} ok=$ok code=$resultCode")
                    val text = when {
                        ok -> "Verifying… SMS sent, waiting for it to arrive"
                        intent.action == dataSentAction -> {
                            // Jio (and others) refuse a silent DATA SMS to your own number (generic failure).
                            // Send the same code as a TEXT SMS instead; P2P texts are hidden from the inbox.
                            runCatching {
                                smsManager.sendTextMessage(
                                    destination, null, buildP2PTextSmsPayload(verifyPayload),
                                    statusIntent(textSentAction), null
                                )
                            }.onFailure { e ->
                                Log.e(P2P_FORENSIC_TAG, "p2p_server_verify action=text_send result=failed error=${e.message}")
                            }
                            "Verifying… trying a text SMS instead"
                        }
                        else -> "SMS failed to send (network error $resultCode). Check balance/SMS pack and try again."
                    }
                    main.post { onResult(text) }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                appContext, statusReceiver,
                android.content.IntentFilter().apply { addAction(dataSentAction); addAction(textSentAction) },
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            main.postDelayed({ runCatching { appContext.unregisterReceiver(statusReceiver) } }, 120_000L)
            smsManager.sendDataMessage(
                destination, null, P2P_SMS_PORT, verifyPayload.toByteArray(Charsets.UTF_8),
                statusIntent(dataSentAction), null
            )
            Log.w(P2P_FORENSIC_TAG, "p2p_server_verify action=sms_sent")
            "Verifying… SMS handed to the phone"
        }.getOrElse { e ->
            Log.e(P2P_FORENSIC_TAG, "p2p_server_verify action=start result=failed error=${e.message}")
            "Verification failed: ${e.message}"
        }
        main.post { onResult(message) }
    }
}

/** Called by CallerIdReceiver for a "VERIFY|code" SMS. Returns true if it was a verification SMS. */
fun handleP2PVerifySms(context: Context, body: String): Boolean {
    if (!body.startsWith(P2P_VERIFY_PREFIX)) return false
    val appContext = context.applicationContext
    val code = body.removePrefix(P2P_VERIFY_PREFIX).trim()
    serverExecutor.execute {
        runCatching {
            val prefs = serverPrefs(appContext)
            val pendingCode = prefs.getString(KEY_PENDING_CODE, null)
            val number = prefs.getString(KEY_PENDING_NUMBER, null)
            check(pendingCode != null && number != null && code == pendingCode) { "no_matching_request" }
            val (status, json) = serverRequest(
                "POST", "/verify/confirm", JSONObject().put("number", number).put("code", code)
            )
            check(status == 200) { "confirm_status_$status" }
            prefs.edit()
                .putString(KEY_TOKEN, json.getString("token"))
                .putString(KEY_NUMBER, number)
                .remove(KEY_PENDING_CODE)
                .remove(KEY_PENDING_NUMBER)
                .commit()
            Log.w(P2P_FORENSIC_TAG, "p2p_server_verify action=confirm result=ok")
            resolveLocalP2PProfile(appContext)?.name?.let { uploadP2PServerProfileName(appContext, it) }
        }.onFailure { e ->
            Log.e(P2P_FORENSIC_TAG, "p2p_server_verify action=confirm result=failed error=${e.message}")
        }
    }
    return true
}

/** Saves my profile name on the server (no-op until this phone is verified). */
fun uploadP2PServerProfileName(context: Context, name: String) {
    if (!isP2PServerEnabled() || name.isBlank()) return
    val appContext = context.applicationContext
    serverExecutor.execute {
        val token = serverPrefs(appContext).getString(KEY_TOKEN, null) ?: return@execute
        runCatching {
            val (status, _) = serverRequest("POST", "/profile", JSONObject().put("name", name), token)
            Log.w(P2P_FORENSIC_TAG, "p2p_server_profile action=upload status=$status")
        }.onFailure { e ->
            Log.e(P2P_FORENSIC_TAG, "p2p_server_profile action=upload result=failed error=${e.message}")
        }
    }
}

/**
 * During a call: asks the server for the other person's name and stores it in the same P2P cache the
 * silent SMS fills, so the call screen and Recents show it exactly as before. Silent SMS still runs as
 * the backup (no internet, or the other phone not on the server yet).
 */
fun requestP2PServerCallerName(context: Context, remoteNumber: String) {
    if (!isP2PServerEnabled()) return
    val appContext = context.applicationContext
    serverExecutor.execute {
        val token = serverPrefs(appContext).getString(KEY_TOKEN, null) ?: return@execute
        runCatching {
            val number = buildP2PNumberSnapshot(remoteNumber).digits
            if (number.length < 10) return@runCatching
            val (status, json) = serverRequest(
                "GET", "/name?number=" + URLEncoder.encode(number, "UTF-8"), token = token
            )
            Log.w(P2P_FORENSIC_TAG, "p2p_server_lookup status=$status")
            val name = json.optString("name")
            if (status == 200 && name.isNotBlank()) {
                val saved = storeP2PProfile(
                    context = appContext,
                    transportNumberRaw = remoteNumber,
                    declaredNumberRaw = remoteNumber,
                    profileNameRaw = name,
                    transportMode = P2PTransportMode.DATA,
                    payloadType = P2PMessageType.REPLY,
                    trigger = "P2PServer.lookup"
                )
                if (saved) {
                    Handler(Looper.getMainLooper()).post {
                        MyInCallService.getInstance()?.refreshCallerIdentityAfterP2P()
                    }
                }
            }
        }.onFailure { e ->
            Log.e(P2P_FORENSIC_TAG, "p2p_server_lookup result=failed error=${e.message}")
        }
    }
}
