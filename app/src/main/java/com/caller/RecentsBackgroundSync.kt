package com.caller

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CallLog.Calls
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the persisted Recents cache current after a call ends, without MainActivity.
 *
 * Data only: it reads Android's call log (never writes it), rebuilds the grouped list with the same
 * code MainActivity uses (buildRecentCallsSnapshot) and saves it to the same cache. It never touches
 * UI state or navigation, so the user's selected tab is unaffected.
 *
 * Android adds the call-log row after the call is disconnected, so the row is waited for (bounded
 * retries) instead of assumed to exist when onCallRemoved runs.
 */
internal object RecentsBackgroundSync {
    private const val TAG = P2P_FORENSIC_TAG
    private const val PREFS_NAME = "recent_calls"
    private const val KEY_LAST_SYNCED_ROW = "bg_sync_last_call_log_row"
    private const val CALL_START_SLACK_MS = 5_000L
    private val CHECK_DELAYS_MS = longArrayOf(600L, 1_400L, 3_000L, 6_000L, 10_000L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One sync at a time, so close-together calls cannot write the cache concurrently.
    private val mutex = Mutex()
    // Calls already queued, so a repeated onCallRemoved for the same call does not queue a second job.
    private val queuedCalls = ConcurrentHashMap.newKeySet<Long>()

    fun schedule(context: Context, callStartedAtMs: Long, source: String) {
        val app = context.applicationContext
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "recents_bg_sync action=skip reason=missing_READ_CALL_LOG source=$source")
            return
        }
        if (!queuedCalls.add(callStartedAtMs)) {
            Log.w(TAG, "recents_bg_sync action=skip reason=already_queued source=$source")
            return
        }
        scope.launch {
            try {
                mutex.withLock { syncWhenCallLogRowAvailable(app, callStartedAtMs, source) }
            } catch (e: Exception) {
                Log.e(TAG, "recents_bg_sync action=failure source=$source error=${e.message}", e)
            } finally {
                queuedCalls.remove(callStartedAtMs)
            }
        }
    }

    private suspend fun syncWhenCallLogRowAvailable(app: Context, callStartedAtMs: Long, source: String) {
        val prefs = recentsPrefs(app)
        for (waitMs in CHECK_DELAYS_MS) {
            delay(waitMs)
            val newest = queryNewestRow(app) ?: continue
            if (newest.dateMs < callStartedAtMs - CALL_START_SLACK_MS) continue // this call's row is not inserted yet
            if (prefs.getString(KEY_LAST_SYNCED_ROW, null) == newest.token) {
                Log.w(TAG, "recents_bg_sync action=skip reason=row_already_synced source=$source")
                return
            }
            val snapshot = buildRecentCallsSnapshot(app, loadContactsFromRecentsPrefs(prefs))
            if (snapshot.queryFailed || snapshot.calls.isEmpty()) continue // never replace the cache with an empty read
            withContext(NonCancellable) {
                saveGroupedRecentCallsCache(prefs, snapshot.grouped)
                prefs.edit().putString(KEY_LAST_SYNCED_ROW, newest.token).apply()
            }
            Log.w(TAG, "recents_bg_sync action=saved source=$source grouped=${snapshot.grouped.size}")
            return
        }
        Log.w(TAG, "recents_bg_sync action=gave_up reason=call_log_row_not_found source=$source")
    }

    private class NewestRow(val token: String, val dateMs: Long)

    private fun queryNewestRow(app: Context): NewestRow? = try {
        app.contentResolver.query(
            Calls.CONTENT_URI,
            arrayOf(Calls._ID, Calls.DATE),
            null,
            null,
            Calls.DATE + " DESC"
        )?.use { c ->
            if (c.moveToFirst()) NewestRow("${c.getLong(0)}:${c.getLong(1)}", c.getLong(1)) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "recents_bg_sync action=query_failed error=${e.message}")
        null
    }

    private fun recentsPrefs(app: Context): SharedPreferences {
        val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            app.createDeviceProtectedStorageContext()
        } else {
            app
        }
        return storage.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}