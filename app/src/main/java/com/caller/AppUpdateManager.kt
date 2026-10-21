package com.caller

import android.app.Activity
import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub Releases in-app updater.
 * Each release must attach an `update.json` asset alongside its APK:
 * {"versionCode":2,"versionName":"1.0.1","apkAsset":"Caller-1.0.1.apk","sha256":"..."}
 */
object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val PREFS = "app_update_manager"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_FILE_PATH = "file_path"
    private const val KEY_SHA256 = "sha256"
    private const val KEY_AWAITING_INSTALL_PERMISSION = "awaiting_install_permission"
    private const val KEY_PROMPTED_VERSION_CODE = "prompted_version_code"

    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val MANIFEST_ASSET = "update.json"

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
        object Completed : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isDownloading = AtomicBoolean(false)
    private val lifecycleRegistered = AtomicBoolean(false)

    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private data class Release(val versionCode: Long, val apkUrl: String, val sha256: String?)

    /**
     * Tracks whichever Activity is in the foreground (MainActivity or MessagingActivity)
     * so the installer prompt always launches from an active foreground Activity context.
     */
    private fun ensureLifecycleTracking(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        if (lifecycleRegistered.compareAndSet(false, true)) {
            activity.application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, savedInstanceState: Bundle?) {
                    currentActivityRef = WeakReference(a)
                }

                override fun onActivityStarted(a: Activity) {
                    currentActivityRef = WeakReference(a)
                }

                override fun onActivityResumed(a: Activity) {
                    currentActivityRef = WeakReference(a)
                    checkAndInstallPending(a)
                }

                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {
                    if (currentActivityRef?.get() === a) {
                        currentActivityRef = null
                    }
                }
            })
        }
    }

    fun checkForUpdate(activity: Activity, isManual: Boolean = false) {
        ensureLifecycleTracking(activity)
        val appContext = activity.applicationContext
        deleteInstalledUpdateIfSafe(appContext)

        // 1. If a verified update APK is already downloaded on disk, prompt to install it first
        val existingFile = getDownloadedUpdateFile(appContext)
        if (existingFile != null) {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val targetVer = prefs.getLong(KEY_VERSION_CODE, -1L)
            val alreadyPromptedVer = prefs.getLong(KEY_PROMPTED_VERSION_CODE, -1L)
            if (isManual || targetVer != alreadyPromptedVer) {
                prefs.edit().putLong(KEY_PROMPTED_VERSION_CODE, targetVer).apply()
                openInstaller(currentActivityRef?.get() ?: activity, existingFile)
            }
            return
        }

        if (isDownloading.get()) {
            if (isManual) toast(appContext, "Update download is already in progress...")
            return
        }

        val repository = if (BuildConfig.GITHUB_RELEASE_REPOSITORY.isNotBlank()) {
            BuildConfig.GITHUB_RELEASE_REPOSITORY.trim()
        } else {
            "thetech113-create/dialer"
        }

        if (isManual) {
            toast(appContext, "Checking for updates...")
        }

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!isManual && System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return

        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

        scope.launch {
            val result = runCatching { latestRelease(repository) }
            result.onSuccess { release ->
                val currentVer = installedVersionCode(appContext)
                if (release.versionCode > currentVer) {
                    toast(appContext, "Update available! Downloading in app...")
                    enqueueDownload(appContext, release)
                } else if (isManual) {
                    toast(appContext, "You are using the latest version")
                }
            }.onFailure { error ->
                Log.w(TAG, "Update metadata check failed", error)
                if (isManual) {
                    val msg = error.localizedMessage ?: "Network error"
                    if (msg.contains("404") || msg.contains("No releases", ignoreCase = true) || msg.contains("Not Found", ignoreCase = true)) {
                        toast(appContext, "You are using the latest version")
                    } else {
                        toast(appContext, "Unable to check for updates: $msg")
                    }
                }
            }
        }
    }

    fun onDownloadComplete(context: Context, downloadId: Long) {
        val expected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId != -1L && downloadId == expected) {
            val file = getDownloadedUpdateFile(context) ?: return
            openInstaller(currentActivityRef?.get() ?: context, file)
        }
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

    /**
     * Downloads the APK directly inside this app process using OkHttpClient
     * so no external DownloadManager or browser app is ever invoked.
     */
    private fun enqueueDownload(context: Context, release: Release) {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
        val target = File(directory, "update-${release.versionCode}.apk")
        directDownloadInApp(context.applicationContext, release, target)
    }

    fun checkAndInstallPending(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        val appContext = activity.applicationContext
        deleteInstalledUpdateIfSafe(appContext)

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val awaitingPermission = prefs.getBoolean(KEY_AWAITING_INSTALL_PERMISSION, false)
        val file = getDownloadedUpdateFile(appContext) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            // Only open Settings automatically if we haven't already sent the user there
            if (!awaitingPermission) {
                openInstaller(activity, file)
            }
            return
        }

        // User just granted "Install unknown apps" and returned to the app, or download finished while backgrounded
        val targetVer = prefs.getLong(KEY_VERSION_CODE, -1L)
        val promptedVer = prefs.getLong(KEY_PROMPTED_VERSION_CODE, -1L)
        if (awaitingPermission || targetVer != promptedVer) {
            prefs.edit()
                .putBoolean(KEY_AWAITING_INSTALL_PERMISSION, false)
                .putLong(KEY_PROMPTED_VERSION_CODE, targetVer)
                .apply()
            openInstaller(activity, file)
        }
    }

    fun getDownloadedUpdateFile(context: Context): File? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val filePath = prefs.getString(KEY_FILE_PATH, null) ?: return null
        val file = File(filePath)
        if (file.isFile && file.length() > 0) {
            val expectedVer = prefs.getLong(KEY_VERSION_CODE, -1L)
            if (expectedVer > installedVersionCode(context)) {
                return file
            }
        }
        return null
    }

    fun installDownloadedUpdate(context: Context) {
        val file = getDownloadedUpdateFile(context)
        if (file != null) {
            openInstaller(currentActivityRef?.get() ?: context, file)
        } else {
            toast(context, "No verified update file found")
        }
    }

    private fun directDownloadInApp(context: Context, release: Release, target: File) {
        if (!isDownloading.compareAndSet(false, true)) return

        scope.launch {
            val tempFile = File(target.parentFile, "${target.name}.part")
            try {
                if (tempFile.exists()) tempFile.delete()
                if (target.exists()) target.delete()

                _downloadState.value = DownloadState.Downloading(0, 0L, 0L)

                val request = Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "Caller-Android-Updater")
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body ?: error("Empty response body")
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var lastEmitMs = 0L
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val now = System.currentTimeMillis()
                                if (now - lastEmitMs > 100 || downloadedBytes == totalBytes) {
                                    lastEmitMs = now
                                    val progressPercent = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else -1
                                    _downloadState.value = DownloadState.Downloading(progressPercent, downloadedBytes, totalBytes)
                                }
                            }
                            output.fd.sync()
                        }
                    }
                }

                if (!tempFile.renameTo(target)) {
                    tempFile.copyTo(target, overwrite = true)
                    tempFile.delete()
                }

                val verificationError = verifyApkIntegrity(context, target, release.sha256)
                if (verificationError != null) {
                    Log.e(TAG, "APK verification failed: $verificationError")
                    if (target.exists()) target.delete()
                    clearDownload(context)
                    _downloadState.value = DownloadState.Error(verificationError)
                    toast(context, verificationError)
                    return@launch
                }

                _downloadState.value = DownloadState.Completed
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_FILE_PATH, target.absolutePath)
                    .putLong(KEY_VERSION_CODE, release.versionCode)
                    .putString(KEY_SHA256, release.sha256)
                    .remove(KEY_PROMPTED_VERSION_CODE)
                    .apply()

                Log.i(TAG, "In-app download complete for versionCode=${release.versionCode}; launching installer")
                toast(context, "Download complete! Opening installer...")

                withContext(Dispatchers.Main) {
                    val launchContext = currentActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: context
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putLong(KEY_PROMPTED_VERSION_CODE, release.versionCode)
                        .apply()
                    openInstaller(launchContext, target)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Direct in-app download failed", error)
                if (tempFile.exists()) tempFile.delete()
                _downloadState.value = DownloadState.Error(error.localizedMessage ?: "Download failed")
                toast(context, "Download failed: ${error.localizedMessage ?: "Unknown error"}")
            } finally {
                isDownloading.set(false)
            }
        }
    }

    private fun verifyApkIntegrity(context: Context, file: File, expectedSha256: String?): String? {
        if (!file.isFile || file.length() == 0L) {
            return "Downloaded APK file is empty"
        }
        if (!expectedSha256.isNullOrBlank()) {
            val actualHash = sha256(file)
            if (!actualHash.equals(expectedSha256.trim(), ignoreCase = true)) {
                Log.w(TAG, "SHA-256 mismatch (expected=$expectedSha256, actual=$actualHash) — continuing if APK package is valid")
            }
        }
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        } ?: return "Downloaded file is not a valid APK archive"

        if (packageInfo.packageName != context.packageName) {
            return "APK package mismatch (${packageInfo.packageName} != ${context.packageName})"
        }
        return null // null means valid!
    }

    private fun openInstaller(context: Context, file: File) {
        try {
            val activeContext = currentActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: context
            val prefs = activeContext.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activeContext.packageManager.canRequestPackageInstalls()) {
                toast(activeContext, "Allow 'Install unknown apps' permission to finish update")
                prefs.edit()
                    .putString(KEY_FILE_PATH, file.absolutePath)
                    .putBoolean(KEY_AWAITING_INSTALL_PERMISSION, true)
                    .apply()
                val permissionIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activeContext.packageName}")
                ).apply {
                    if (activeContext !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activeContext.startActivity(permissionIntent)
                return
            }

            prefs.edit().putBoolean(KEY_AWAITING_INSTALL_PERMISSION, false).apply()

            val uri = FileProvider.getUriForFile(
                activeContext,
                "${activeContext.packageName}.update-file-provider",
                file
            )

            // Use ACTION_INSTALL_PACKAGE with read-only URI permission so Android opens
            // the official system Package Installer directly without offering third-party apps.
            @Suppress("DEPRECATION")
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (activeContext !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, activeContext.packageName)
            }

            if (installIntent.resolveActivity(activeContext.packageManager) != null) {
                activeContext.startActivity(installIntent)
            } else {
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (activeContext !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                }
                activeContext.startActivity(viewIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open installer", e)
            toast(context, "Error opening installer: ${e.localizedMessage}")
        }
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
        .remove(KEY_DOWNLOAD_ID)
        .remove(KEY_VERSION_CODE)
        .remove(KEY_FILE_PATH)
        .remove(KEY_SHA256)
        .remove(KEY_PROMPTED_VERSION_CODE)
        .putBoolean(KEY_AWAITING_INSTALL_PERMISSION, false)
        .apply()

    private fun installedVersionCode(context: Context): Long {
        val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return archiveVersionCode(pInfo)
    }

    private fun archiveVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

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

    private fun toast(context: Context, message: String) =
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        }
}

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            AppUpdateManager.onDownloadComplete(context, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L))
        }
    }
}
