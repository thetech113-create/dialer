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
import android.os.SystemClock
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
import org.json.JSONArray
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
 * The updater moves ONE release at a time: it offers the nearest release above the installed version (8.10.45 gets
 * 8.10.46, not the newest one), so every release's changes run in order.
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
    private const val KEY_VERSION_NAME = "version_name"
    // Installed version code at the time the file was chosen: a file is only installed on the version it was the next step for.
    private const val KEY_STEP_FROM = "step_from_version_code"

    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val RELEASES_PER_PAGE = 30
    // Taps on the update buttons closer together than this count as one tap, and the system installer is not opened
    // again this soon after it was opened.
    private const val TAP_DEBOUNCE_MS = 1500L
    private const val INSTALLER_REOPEN_GUARD_MS = 3000L
    private const val MAX_RELEASE_PAGES = 10
    private const val MANIFEST_ASSET = "update.json"

    sealed class DownloadState {
        object Idle : DownloadState()
        /** A manual check is running: the screen shows it the moment the button is tapped. */
        object Checking : DownloadState()
        /** A manual check found nothing newer than the installed version. */
        object UpToDate : DownloadState()
        data class Downloading(val progressPercent: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
        object Completed : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isDownloading = AtomicBoolean(false)
    private val isChecking = AtomicBoolean(false)
    @Volatile private var lastTapAtMs = 0L
    @Volatile private var lastInstallerOpenAtMs = 0L
    // The last file that passed every check as it is on disk (path, size, time, versions): it is not hashed again.
    @Volatile private var lastVerifiedFileKey: String? = null
    private val lifecycleRegistered = AtomicBoolean(false)

    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private data class Release(val tag: String, val versionName: String, val versionCode: Long, val apkUrl: String, val sha256: String?)

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

    /** True when this tap repeats the one just before it (a double tap): it must do nothing. */
    @Synchronized
    private fun isRepeatedTap(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (isDuplicateTap(now, lastTapAtMs, TAP_DEBOUNCE_MS)) return true
        lastTapAtMs = now
        return false
    }

    fun checkForUpdate(activity: Activity, isManual: Boolean = false) {
        // Before anything else: a second tap must not start a second check or open the installer a second time.
        if (isManual && isRepeatedTap()) return
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

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!isManual && System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        if (!isChecking.compareAndSet(false, true)) return

        // The screen answers the tap right now; the network steps below take a moment.
        if (isManual) {
            _downloadState.value = DownloadState.Checking
            toast(appContext, "Checking for updates...")
        }

        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

        scope.launch {
            try {
                val result = runCatching { nextRelease(repository, installedPackageInfo(appContext).versionName.orEmpty()) }
                result.onSuccess { release ->
                    if (release == null) {
                        if (isManual) {
                            _downloadState.value = DownloadState.UpToDate
                            toast(appContext, "You are using the latest version")
                        } else {
                            _downloadState.value = DownloadState.Idle
                        }
                        return@onSuccess
                    }
                    val problem = releaseProblem(
                        release.tag, release.versionName, release.versionCode, release.sha256, installedVersionCode(appContext)
                    )
                    if (problem != null) {
                        // The next release is the only one that may be installed: a bad one is not skipped for a later one.
                        Log.w(TAG, "Release ${release.tag} rejected: $problem")
                        if (isManual) {
                            _downloadState.value = DownloadState.Error("Update ${release.tag} was not installed: $problem")
                            toast(appContext, "Update ${release.tag} was not installed: $problem")
                        } else {
                            _downloadState.value = DownloadState.Idle
                        }
                        return@onSuccess
                    }
                    toast(appContext, "Update ${release.tag} found. Downloading...")
                    enqueueDownload(appContext, release)
                }.onFailure { error ->
                    Log.w(TAG, "Update metadata check failed", error)
                    val msg = error.localizedMessage ?: "Network error"
                    val nothingPublished = msg.contains("404") || msg.contains("No releases", ignoreCase = true) ||
                        msg.contains("Not Found", ignoreCase = true)
                    if (isManual) {
                        if (nothingPublished) {
                            _downloadState.value = DownloadState.UpToDate
                            toast(appContext, "You are using the latest version")
                        } else {
                            _downloadState.value = DownloadState.Error("Unable to check for updates: $msg")
                            toast(appContext, "Unable to check for updates: $msg")
                        }
                    } else {
                        _downloadState.value = DownloadState.Idle
                    }
                }
            } finally {
                isChecking.set(false)
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

    /** The nearest published release above the installed version, or null when there is none. */
    private fun nextRelease(repository: String, installedVersionName: String): Release? {
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "Invalid GitHub repository: $repository" }
        val installed = parseVersionParts(installedVersionName)
            ?: error("The installed version \"$installedVersionName\" is not a version number")

        // One small request settles the usual case: the newest release is not newer than what is installed.
        val newest = parseVersionParts(getJson("https://api.github.com/repos/$repository/releases/latest").optString("tag_name"))
        if (newest != null && compareVersionParts(newest, installed) <= 0) return null

        // Releases come one patch step apart, so the next one is nearly always named exactly one patch above the
        // installed version: ask for that one release (a few KB) before reading any list.
        val oneStepUp = (installed.dropLast(1) + (installed.last() + 1)).joinToString(".")
        val direct = runCatching { getJson("https://api.github.com/repos/$repository/releases/tags/v$oneStepUp") }.getOrNull()
        if (direct != null && !direct.optBoolean("draft") && !direct.optBoolean("prerelease") &&
            parseVersionParts(direct.optString("tag_name"))?.let { compareVersionParts(it, installed) > 0 } == true
        ) {
            return releaseFromJson(direct)
        }

        // That name does not exist (a version was skipped). Read the list newest first, a page at a time, until a release at or below the
        // installed version has been seen: the nearest release above it is then among those read.
        val published = mutableListOf<JSONObject>()
        for (page in 1..MAX_RELEASE_PAGES) {
            val releases = getJsonArray("https://api.github.com/repos/$repository/releases?per_page=$RELEASES_PER_PAGE&page=$page")
            val items = (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
            published += items.filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
            val reachedInstalled = items.any { item ->
                parseVersionParts(item.optString("tag_name"))?.let { compareVersionParts(it, installed) <= 0 } == true
            }
            if (reachedInstalled || items.size < RELEASES_PER_PAGE) break
        }
        val nextTag = pickNextTag(installed, published.map { it.optString("tag_name") }) ?: return null
        return releaseFromJson(published.first { it.optString("tag_name") == nextTag })
    }

    /** Everything about a release comes from its own update.json; nothing is guessed from the tag. */
    private fun releaseFromJson(release: JSONObject): Release {
        val tag = release.optString("tag_name").removePrefix("v").trim()
        val assets = release.optJSONArray("assets") ?: error("Release $tag has no assets")
        val assetsByName = mutableMapOf<String, String>()
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.isNotBlank() && url.startsWith("https://")) assetsByName[name] = url
        }
        val manifestUrl = assetsByName[MANIFEST_ASSET] ?: error("Release $tag has no update.json")
        val manifest = getJson(manifestUrl)
        val apkUrl = assetsByName[manifest.optString("apkAsset")]
            ?: error("Release $tag: the APK named in update.json is not attached")
        return Release(
            tag = tag,
            versionName = manifest.optString("versionName").trim(),
            versionCode = manifest.optLong("versionCode", -1L),
            apkUrl = apkUrl,
            sha256 = manifest.optString("sha256").trim().takeIf { it.isNotEmpty() }
        )
    }

    private fun getJson(url: String): JSONObject = JSONObject(getBody(url))

    private fun getJsonArray(url: String): JSONArray = JSONArray(getBody(url))

    private fun getBody(url: String): String {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Caller-Android-Updater")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) {
                error("No GitHub releases published yet")
            }
            require(response.isSuccessful) { "GitHub returned HTTP ${response.code}" }
            return response.body?.string()?.trim()?.removePrefix("\uFEFF") ?: error("Empty GitHub response")
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
            val installedVer = installedVersionCode(context)
            if (expectedVer > installedVer) {
                // A file from before this rule, or chosen for another installed version, may skip a release: never use it.
                val fileKey = "${file.absolutePath}|${file.length()}|${file.lastModified()}|$installedVer|$expectedVer"
                val problem = when {
                    prefs.getLong(KEY_STEP_FROM, -1L) != installedVer -> "it was not chosen as the next step for this version"
                    fileKey == lastVerifiedFileKey -> null
                    else -> verifyApkIntegrity(
                        context, file, expectedVer, prefs.getString(KEY_VERSION_NAME, "").orEmpty(), prefs.getString(KEY_SHA256, null)
                    ).also { if (it == null) lastVerifiedFileKey = fileKey }
                }
                if (problem == null) return file
                Log.w(TAG, "Discarding the downloaded update: $problem")
                file.delete()
                clearDownload(context)
            }
        }
        return null
    }

    fun installDownloadedUpdate(context: Context) {
        if (isRepeatedTap()) return
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

                val verificationError = verifyApkIntegrity(context, target, release.versionCode, release.versionName, release.sha256)
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
                    .putString(KEY_VERSION_NAME, release.versionName)
                    .putLong(KEY_STEP_FROM, installedVersionCode(context))
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

    /**
     * Null when the file is exactly the update that was chosen and may be installed; otherwise the reason it must not be.
     * Every check has to pass: right checksum, this app, the expected version (and newer than the installed one), and
     * the same signing key as the installed app.
     */
    private fun verifyApkIntegrity(
        context: Context,
        file: File,
        expectedVersionCode: Long,
        expectedVersionName: String,
        expectedSha256: String?
    ): String? {
        if (!file.isFile || file.length() == 0L) return "Downloaded APK file is empty"
        if (expectedSha256.isNullOrBlank()) return "The release has no checksum, so it was not installed"
        if (!sha256(file).equals(expectedSha256.trim(), ignoreCase = true)) {
            return "The update file is damaged (checksum mismatch), so it was not installed"
        }
        val archive = packageInfoWithSigners(context, file.absolutePath)
            ?: return "Downloaded file is not a valid APK archive"
        if (archive.packageName != context.packageName) {
            return "APK package mismatch (${archive.packageName} != ${context.packageName})"
        }
        val archiveCode = archiveVersionCode(archive)
        if (archiveCode != expectedVersionCode) {
            return "The update file is version code $archiveCode, expected $expectedVersionCode, so it was not installed"
        }
        if (archiveCode <= installedVersionCode(context)) return "The update file is not newer than the installed version"
        if (expectedVersionName.isNotBlank() && archive.versionName != expectedVersionName) {
            return "The update file is version ${archive.versionName}, expected $expectedVersionName, so it was not installed"
        }
        // Android refuses a differently signed update at install time anyway; this says so before the installer opens.
        val installedSigners = packageInfoWithSigners(context, null)?.let(::signerDigests).orEmpty()
        val archiveSigners = signerDigests(archive)
        if (installedSigners.isNotEmpty() && archiveSigners.isNotEmpty() && installedSigners != archiveSigners) {
            return "The update is signed with a different key than the installed app, so it was not installed"
        }
        return null
    }

    private fun packageInfoWithSigners(context: Context, archivePath: String?): PackageInfo? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val packageFlags = PackageManager.PackageInfoFlags.of(flags.toLong())
            if (archivePath != null) context.packageManager.getPackageArchiveInfo(archivePath, packageFlags)
            else context.packageManager.getPackageInfo(context.packageName, packageFlags)
        } else {
            @Suppress("DEPRECATION")
            if (archivePath != null) context.packageManager.getPackageArchiveInfo(archivePath, flags)
            else context.packageManager.getPackageInfo(context.packageName, flags)
        }
    }.getOrNull()

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Synchronized
    private fun installerRecentlyOpened(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (isDuplicateTap(now, lastInstallerOpenAtMs, INSTALLER_REOPEN_GUARD_MS)) return true
        lastInstallerOpenAtMs = now
        return false
    }

    private fun openInstaller(context: Context, file: File) {
        if (installerRecentlyOpened()) return
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
            // One release at a time: the next step is looked for now, not after the 12 h wait.
            prefs.edit().remove(KEY_LAST_CHECK).apply()
        }
    }

    private fun clearDownload(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .remove(KEY_DOWNLOAD_ID)
        .remove(KEY_VERSION_CODE)
        .remove(KEY_FILE_PATH)
        .remove(KEY_SHA256)
        .remove(KEY_VERSION_NAME)
        .remove(KEY_STEP_FROM)
        .remove(KEY_PROMPTED_VERSION_CODE)
        .putBoolean(KEY_AWAITING_INSTALL_PERMISSION, false)
        .apply()

    private fun installedPackageInfo(context: Context): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }

    private fun installedVersionCode(context: Context): Long = archiveVersionCode(installedPackageInfo(context))

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

/** True when [nowMs] is within [windowMs] after [lastMs]: the same tap or action repeated. */
internal fun isDuplicateTap(nowMs: Long, lastMs: Long, windowMs: Long): Boolean = nowMs - lastMs in 0 until windowMs

/** "v8.10.46" -> [8, 10, 46]; null when the text is not a dotted number. */
internal fun parseVersionParts(text: String): List<Long>? {
    val parts = text.trim().removePrefix("v").split(".").map { it.toLongOrNull() ?: return null }
    return parts.takeIf { it.isNotEmpty() }
}

internal fun compareVersionParts(a: List<Long>, b: List<Long>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val c = a.getOrElse(i) { 0L }.compareTo(b.getOrElse(i) { 0L })
        if (c != 0) return c
    }
    return 0
}

/**
 * Why a release must not be installed, or null when it may be. The release's own update.json has to agree with its tag,
 * carry a real checksum, and be newer than what is installed.
 */
internal fun releaseProblem(tag: String, versionName: String, versionCode: Long, sha256: String?, installedVersionCode: Long): String? {
    val tagParts = parseVersionParts(tag)
    val nameParts = parseVersionParts(versionName)
    return when {
        tagParts == null || nameParts == null || compareVersionParts(tagParts, nameParts) != 0 ->
            "update.json says version \"$versionName\" but the release is $tag"
        sha256 == null || !sha256.matches(Regex("[0-9a-fA-F]{64}")) -> "the release has no valid checksum"
        versionCode <= installedVersionCode -> "it is not newer than the installed version"
        else -> null
    }
}

/** The tag of the nearest version above [installed]: one step up, never a jump to the newest release. */
internal fun pickNextTag(installed: List<Long>, tags: List<String>): String? =
    tags.mapNotNull { tag -> parseVersionParts(tag)?.let { tag to it } }
        .filter { (_, version) -> compareVersionParts(version, installed) > 0 }
        .minWithOrNull { x, y -> compareVersionParts(x.second, y.second) }
        ?.first

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            AppUpdateManager.onDownloadComplete(context, intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L))
        }
    }
}
