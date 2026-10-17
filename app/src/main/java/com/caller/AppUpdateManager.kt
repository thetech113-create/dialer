package com.caller

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * GitHub Releases updater.
 *
 * Each release must attach an `update.json` asset alongside its APK:
 * {"versionCode":2,"versionName":"1.0.1","apkAsset":"Caller-1.0.1.apk","sha256":"..."}
 * `sha256` is optional but strongly recommended. `apkAsset` must exactly match an asset on
 * that same release. This avoids treating a mutable tag or filename as version authority.
 */
object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val PREFS = "app_update_manager"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_FILE_PATH = "file_path"
    private const val KEY_SHA256 = "sha256"
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val MANIFEST_ASSET = "update.json"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class Release(val versionCode: Long, val apkUrl: String, val sha256: String?)

    fun checkForUpdate(activity: Activity, isManual: Boolean = false) {
        deleteInstalledUpdateIfSafe(activity.applicationContext)
        val repository = if (BuildConfig.GITHUB_RELEASE_REPOSITORY.isNotBlank()) {
            BuildConfig.GITHUB_RELEASE_REPOSITORY.trim()
        } else {
            "thetech113-create/dialer"
        }

        if (isManual) {
            toast(activity, "Checking for updates...")
        }

        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!isManual && System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        if (prefs.getLong(KEY_DOWNLOAD_ID, -1L) != -1L) {
            handleCompletedDownload(activity.applicationContext, prefs.getLong(KEY_DOWNLOAD_ID, -1L))
            return
        }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        scope.launch {
            val result = runCatching { latestRelease(repository) }
            result.onSuccess { release ->
                val currentVer = installedVersionCode(activity)
                if (release.versionCode > currentVer) {
                    toast(activity, "Update available! Starting download...")
                    enqueueDownload(activity.applicationContext, release)
                } else if (isManual) {
                    toast(activity, "You are using the latest version")
                }
            }.onFailure { error ->
                Log.w(TAG, "Update metadata check failed", error)
                if (isManual) {
                    val msg = error.localizedMessage ?: "Network error"
                    if (msg.contains("404") || msg.contains("No releases", ignoreCase = true) || msg.contains("Not Found", ignoreCase = true)) {
                        toast(activity, "You are using the latest version")
                    } else {
                        toast(activity, "Unable to check for updates: $msg")
                    }
                }
            }
        }
    }

    fun onDownloadComplete(context: Context, downloadId: Long) {
        val expected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId == expected) handleCompletedDownload(context.applicationContext, downloadId)
    }

    private fun latestRelease(repositoryOverride: String? = null): Release {
        val repository = (repositoryOverride ?: BuildConfig.GITHUB_RELEASE_REPOSITORY).trim()
            .ifBlank { "thetech113-create/dialer" }
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "Invalid GitHub repository: $repository" }
        val apiUrl = "https://api.github.com/repos/$repository/releases/latest"
        val release = getJson(apiUrl)
        val assets = release.optJSONArray("assets") ?: error("Release has no assets")
        var manifestUrl: String? = null
        val assetsByName = mutableMapOf<String, String>()
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.isNotBlank() && url.startsWith("https://")) assetsByName[name] = url
            if (name == MANIFEST_ASSET) manifestUrl = url
        }
        val manifest = getJson(manifestUrl ?: error("Release is missing $MANIFEST_ASSET"))
        val versionCode = manifest.optLong("versionCode", -1L)
        require(versionCode > 0) { "Invalid update versionCode" }
        require(manifest.optString("versionName").isNotBlank()) { "Missing update versionName" }
        val apkName = manifest.optString("apkAsset")
        require(apkName.endsWith(".apk", ignoreCase = true)) { "Invalid APK asset name" }
        val apkUrl = assetsByName[apkName] ?: error("Declared APK asset is missing")
        return Release(versionCode, apkUrl, manifest.optString("sha256").takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) })
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Caller-Android-Updater")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) {
                error("No GitHub releases published yet")
            }
            require(response.isSuccessful) { "GitHub returned HTTP ${response.code}" }
            val raw = response.body?.string()?.trim()?.removePrefix("\uFEFF") ?: error("Empty GitHub response")
            return JSONObject(raw)
        }
    }

    private fun enqueueDownload(context: Context, release: Release) {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
        val target = File(directory, "update-${release.versionCode}.apk")
        if (target.exists()) target.delete()

        try {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (manager != null) {
                val request = DownloadManager.Request(Uri.parse(release.apkUrl))
                    .setTitle("Downloading app update")
                    .setDescription("Version ${release.versionCode}")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setDestinationUri(Uri.fromFile(target))
                val id = manager.enqueue(request)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_DOWNLOAD_ID, id)
                    .putLong(KEY_VERSION_CODE, release.versionCode)
                    .putString(KEY_FILE_PATH, target.absolutePath)
                    .putString(KEY_SHA256, release.sha256)
                    .apply()
                toast(context, "Downloading update")
                Log.i(TAG, "Enqueued update version ${release.versionCode}, download id=$id")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "System DownloadManager unavailable or disabled, using direct HTTP download fallback", e)
        }

        // Direct in-app HTTP download fallback if system DownloadManager is unavailable/disabled
        directDownloadInApp(context, release, target)
    }

    private fun directDownloadInApp(context: Context, release: Release, target: File) {
        scope.launch {
            toast(context, "Downloading update directly...")
            val result = runCatching {
                val request = Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "Caller-Android-Updater")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty response body")
                    body.byteStream().use { input ->
                        java.io.FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
            result.onSuccess {
                if (verifyApk(context, target, release.versionCode, release.sha256)) {
                    withContext(Dispatchers.Main) { openInstaller(context, target) }
                } else {
                    if (target.exists()) target.delete()
                    toast(context, "Downloaded update could not be verified")
                }
            }.onFailure { error ->
                Log.e(TAG, "Direct download failed", error)
                toast(context, "Download failed: ${error.localizedMessage ?: "Unknown error"}")
            }
        }
    }

    private fun handleCompletedDownload(context: Context, downloadId: Long) {
        scope.launch {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return@launch
            cursor.use {
                if (!it.moveToFirst()) return@launch clearDownload(context)
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status != DownloadManager.STATUS_SUCCESSFUL) {
                    if (status == DownloadManager.STATUS_FAILED) {
                        Log.w(TAG, "Update download failed: " + it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                        clearDownload(context)
                    }
                    return@launch
                }
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val file = File(prefs.getString(KEY_FILE_PATH, "") ?: "")
            val expectedVersion = prefs.getLong(KEY_VERSION_CODE, -1L)
            if (!verifyApk(context, file, expectedVersion, prefs.getString(KEY_SHA256, null))) {
                if (file.exists()) file.delete()
                clearDownload(context)
                toast(context, "Downloaded update could not be verified")
                return@launch
            }
            withContext(Dispatchers.Main) { openInstaller(context, file) }
        }
    }

    private fun verifyApk(context: Context, file: File, expectedVersion: Long, expectedSha256: String?): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        if (expectedSha256 != null && sha256(file) != expectedSha256.lowercase()) return false
        val packageInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
        if (packageInfo.packageName != context.packageName || archiveVersionCode(packageInfo) != expectedVersion) return false
        return true // The platform installer performs the authoritative signing-certificate verification.
    }

    private fun openInstaller(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            toast(context, "Allow installs from this app to finish the update")
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-file-provider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun deleteInstalledUpdateIfSafe(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getLong(KEY_VERSION_CODE, -1L)
        if (version > 0 && installedVersionCode(context) >= version) {
            prefs.getString(KEY_FILE_PATH, null)?.let { File(it).takeIf(File::isFile)?.delete() }
            clearDownload(context)
        }
    }

    private fun clearDownload(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .remove(KEY_DOWNLOAD_ID).remove(KEY_VERSION_CODE).remove(KEY_FILE_PATH).remove(KEY_SHA256).apply()

    private fun installedVersionCode(context: Context): Long = archiveVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    private fun archiveVersionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun toast(context: Context, message: String) = android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
}

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            AppUpdateManager.onDownloadComplete(context, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L))
        }
    }
}
