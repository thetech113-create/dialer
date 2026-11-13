package com.caller

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One sender's reports from one phone: [sender] is a hash from [senderReportKey], never the sender itself. */
data class SenderReport(val sender: String, val spam: Int, val ham: Int)

/** One sender's reports from another phone, as the server returns them. */
data class RemoteSenderReport(val deviceId: String, val sender: String, val spam: Int, val ham: Int, val updatedAt: Long)

/** The server's answer to a share: HTTP status (200 = done, -1 = nothing sent) and the other phones' reports. */
data class SpamSyncResult(val status: Int, val reports: List<RemoteSenderReport>)

private const val MAX_REMOTE_NET_PER_PHONE = 3

/**
 * What the paired phones reported, as sender key -> net reports (spam minus "Not spam").
 * - This phone's own rows are left out: its own counters are read directly.
 * - A phone that appears more than once (paired twice, so it is in two links) counts once: the newest row wins.
 * - One phone can add at most [MAX_REMOTE_NET_PER_PHONE] either way, so it cannot drown out this phone's own answers.
 */
internal fun remoteSenderNets(reports: List<RemoteSenderReport>, ownDeviceId: String): Map<String, Int> {
    val newest = HashMap<Pair<String, String>, RemoteSenderReport>()
    for (report in reports) {
        if (report.deviceId.isBlank() || report.deviceId == ownDeviceId || report.sender.isBlank()) continue
        val key = report.deviceId to report.sender
        val known = newest[key]
        if (known == null || report.updatedAt > known.updatedAt) newest[key] = report
    }
    val nets = HashMap<String, Int>()
    for (report in newest.values) {
        val net = (report.spam - report.ham).coerceIn(-MAX_REMOTE_NET_PER_PHONE, MAX_REMOTE_NET_PER_PHONE)
        nets[report.sender] = (nets[report.sender] ?: 0) + net
    }
    return nets
}

/**
 * Shares the senders this phone reported as spam with the phones it is paired with (one private key per pairing), and
 * collects theirs. Only hashed sender ids and counts travel, never a message. Nothing is forwarded: a phone shares its
 * own reports only, so A+B and B+C never give A+C. Runs on its own thread; failures are ignored and retried later.
 */
object SpamReportSharing {
    private const val TAG = "SpamReportSharing"
    private const val PREFS = "spam_report_sharing"
    private const val KEY_LAST_SYNC_MS = "last_sync_ms"
    private const val KEY_BACKOFF_UNTIL_MS = "backoff_until_ms"
    private const val SYNC_INTERVAL_MS = 30 * 60 * 1000L
    private const val BACKOFF_MS = 60 * 60 * 1000L
    private const val MAX_REPORTS_PER_SHARE = 200

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val askedAgain = AtomicBoolean(false)

    /** From the app's start screen: shares and collects at most every 30 minutes, and not while the server said no. */
    fun syncIfDue(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now < prefs.getLong(KEY_BACKOFF_UNTIL_MS, 0L)) return
        if (now - prefs.getLong(KEY_LAST_SYNC_MS, 0L) < SYNC_INTERVAL_MS) return
        start(context.applicationContext)
    }

    /** After the user reports a message (or says "Not spam"): share right away. */
    fun syncNow(context: Context) = start(context.applicationContext)

    private fun start(appContext: Context) {
        if (!isP2PServerEnabled()) return
        askedAgain.set(true)
        if (!running.compareAndSet(false, true)) return // the running share will go once more
        executor.execute {
            try {
                while (askedAgain.getAndSet(false)) {
                    runCatching { share(appContext) }.onFailure { Log.w(TAG, "Sharing spam reports failed: ${it.message}") }
                }
            } finally {
                running.set(false)
            }
        }
    }

    private fun share(appContext: Context) {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keys = AccountDeviceHistoryManager.getLinkSpamShareKeys(appContext)
        if (keys.isEmpty()) {
            // Not paired with any phone: the phones' own reports still count locally, there is nobody to share with.
            storeRemoteSenderNets(appContext, emptyMap())
            return
        }
        val deviceId = AccountDeviceHistoryManager.getCurrentDeviceId(appContext)
        val own = ownSenderReports(appContext).take(MAX_REPORTS_PER_SHARE)
        val remote = ArrayList<RemoteSenderReport>()
        var everyPairingAnswered = true
        for (key in keys) {
            val answer = syncSpamReportsWithServer(appContext, key, deviceId, own)
            Log.w(TAG, "spam reports shared: status=${answer.status} sent=${own.size} received=${answer.reports.size}")
            when (answer.status) {
                200 -> remote += answer.reports
                // The server has no such route yet (404) or is rate limiting (429): leave it alone for a while.
                404, 429 -> {
                    everyPairingAnswered = false
                    prefs.edit().putLong(KEY_BACKOFF_UNTIL_MS, System.currentTimeMillis() + BACKOFF_MS).apply()
                }
                else -> everyPairingAnswered = false
            }
        }
        // A pairing that did not answer must not wipe what its phone reported earlier: only replace when all answered.
        if (everyPairingAnswered) {
            storeRemoteSenderNets(appContext, remoteSenderNets(remote, deviceId))
            prefs.edit().putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis()).apply()
        }
    }
}
