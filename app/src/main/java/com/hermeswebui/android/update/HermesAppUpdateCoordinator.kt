package com.hermeswebui.android.update

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.R
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.ui.MainViewModel
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HermesAppUpdateCoordinator(
    private val context: Context,
    private val activityScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val viewModel: MainViewModel,
    private val appUpdateManager: AppUpdateManager,
    private val playUpdateLauncher: ActivityResultLauncher<IntentSenderRequest>,
    private val updateChannel: String,
    private val githubReleasesApiUrl: String,
    private val githubReleasesPageUrl: String,
    private val notificationChannelId: String,
    private val notificationPermissionState: () -> String,
    private val requestNotificationPermissionIfNeeded: () -> Unit,
    private val isActivityVisible: () -> Boolean,
    private val appVersionName: () -> String
) {
    companion object {
        const val ACTION_START_PLAY_UPDATE = "com.hermeswebui.android.START_PLAY_UPDATE"
        const val ACTION_DOWNLOAD_APP_UPDATE = "com.hermeswebui.android.DOWNLOAD_APP_UPDATE"
        const val ACTION_INSTALL_DOWNLOADED_APP_UPDATE = "com.hermeswebui.android.INSTALL_DOWNLOADED_APP_UPDATE"
        const val EXTRA_APP_UPDATE_DOWNLOAD_URL = "com.hermeswebui.android.extra.APP_UPDATE_DOWNLOAD_URL"
        const val EXTRA_APP_UPDATE_FILE_NAME = "com.hermeswebui.android.extra.APP_UPDATE_FILE_NAME"
        const val EXTRA_APP_UPDATE_DOWNLOAD_ID = "com.hermeswebui.android.extra.APP_UPDATE_DOWNLOAD_ID"

        private const val APP_UPDATE_NOTIFICATION_ID = 7_001
        private const val APP_UPDATE_INSTALL_READY_NOTIFICATION_ID = 7_002
        private const val GITHUB_DOWNLOAD_MONITOR_INTERVAL_MS = 5_000L
    }

    private var automaticAppUpdateCheckJob: Job? = null
    private var githubDownloadMonitorJob: Job? = null

    fun appUpdateChannelLabel(): String {
        return when (updateChannel) {
            "github" -> "GitHub Releases"
            "play" -> "Google Play"
            else -> "this build channel"
        }
    }

    fun scheduleAutomaticAppUpdateCheck() {
        val settings = viewModel.uiState.value.settings
        if (!settings.isConfigured) return
        if (!settingsRepository.isAppUpdateAlertsEnabled()) return
        if (!settingsRepository.isAutomaticAppUpdateChecksEnabled()) return
        if (automaticAppUpdateCheckJob?.isActive == true) return

        automaticAppUpdateCheckJob = activityScope.launch {
            try {
                checkForAppUpdates(force = false)
            } finally {
                automaticAppUpdateCheckJob = null
            }
        }
    }

    fun cancelAutomaticAppUpdateCheck() {
        automaticAppUpdateCheckJob?.cancel()
        automaticAppUpdateCheckJob = null
    }

    fun startPendingGitHubDownloadMonitor() {
        if (githubDownloadMonitorJob?.isActive == true) return
        githubDownloadMonitorJob = activityScope.launch {
            while (isActive) {
                if (!isActivityVisible()) {
                    githubDownloadMonitorJob = null
                    return@launch
                }
                if (!checkPendingGitHubDownloadForInstall()) {
                    githubDownloadMonitorJob = null
                    return@launch
                }
                delay(GITHUB_DOWNLOAD_MONITOR_INTERVAL_MS)
            }
        }
    }

    fun stopPendingGitHubDownloadMonitor() {
        githubDownloadMonitorJob?.cancel()
        githubDownloadMonitorJob = null
    }

    fun checkForAppUpdates(force: Boolean) {
        if (!settingsRepository.shouldCheckForAppUpdates(System.currentTimeMillis(), force)) return
        settingsRepository.markAppUpdateChecked(System.currentTimeMillis())
        viewModel.setAppUpdateStatus(if (force) "Checking ${appUpdateChannelLabel()}..." else null)

        when (updateChannel) {
            "github" -> checkGitHubAppUpdate(force)
            "play" -> checkPlayAppUpdate(force)
            else -> {
                if (force) {
                    viewModel.setAppUpdateStatus("This build does not have an update provider.")
                    Toast.makeText(context, "No update provider for this build", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun handleIntent(intent: Intent?): Boolean {
        return when (intent?.action) {
            ACTION_START_PLAY_UPDATE -> {
                startPlayUpdateFlow()
                true
            }

            ACTION_DOWNLOAD_APP_UPDATE -> {
                val downloadUrl = intent.getStringExtra(EXTRA_APP_UPDATE_DOWNLOAD_URL)
                val fileName = intent.getStringExtra(EXTRA_APP_UPDATE_FILE_NAME)
                downloadGitHubUpdate(downloadUrl, fileName)
                true
            }

            ACTION_INSTALL_DOWNLOADED_APP_UPDATE -> {
                val downloadId = intent.getLongExtra(EXTRA_APP_UPDATE_DOWNLOAD_ID, -1L)
                NotificationManagerCompat.from(context).cancel(APP_UPDATE_INSTALL_READY_NOTIFICATION_ID)
                viewModel.setAppUpdateInstallReady(false)
                promptInstallDownloadedGithubUpdate(downloadId)
                true
            }

            else -> false
        }
    }

    fun downloadAvailableGitHubUpdate() {
        val pendingDownloadId = settingsRepository.pendingGitHubUpdateDownloadId()
        if (pendingDownloadId > 0L) {
            promptInstallDownloadedGithubUpdate(pendingDownloadId)
            return
        }
        viewModel.setAppUpdateInstallReady(false)
        val state = viewModel.uiState.value
        downloadGitHubUpdate(state.appUpdateDownloadUrl, state.appUpdateFileName)
    }

    fun resumePlayUpdateIfNeeded() {
        if (updateChannel != "play") return
        appUpdateManager.appUpdateInfo.addOnSuccessListener { updateInfo ->
            if (updateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                launchPlayUpdate(updateInfo)
            }
        }
    }

    fun onDownloadComplete(downloadId: Long) {
        if (downloadId <= 0L) return
        val pendingId = settingsRepository.pendingGitHubUpdateDownloadId()
        if (pendingId <= 0L || pendingId != downloadId) return
        val manager = context.getSystemService(DownloadManager::class.java)
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor.use {
            if (!it.moveToFirst()) {
                settingsRepository.clearPendingGitHubUpdateDownload()
                viewModel.setAppUpdateInstallReady(false)
                return
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                if (status == DownloadManager.STATUS_FAILED) {
                    settingsRepository.clearPendingGitHubUpdateDownload()
                    viewModel.setAppUpdateInstallReady(false)
                    Toast.makeText(context, "GitHub APK download failed", Toast.LENGTH_LONG).show()
                }
                return
            }
        }
        if (isActivityVisible()) {
            viewModel.setAppUpdateInstallReady(true)
            Toast.makeText(context, "GitHub APK downloaded. Tap Install in Settings.", Toast.LENGTH_LONG).show()
        } else {
            viewModel.setAppUpdateInstallReady(true)
            showInstallReadyNotification(downloadId)
        }
    }

    fun resumePendingGitHubInstallIfReady() {
        if (checkPendingGitHubDownloadForInstall()) {
            startPendingGitHubDownloadMonitor()
        } else {
            stopPendingGitHubDownloadMonitor()
        }
    }

    private fun checkPendingGitHubDownloadForInstall(): Boolean {
        val pendingId = settingsRepository.pendingGitHubUpdateDownloadId()
        if (pendingId <= 0L) {
            viewModel.setAppUpdateInstallReady(false)
            return false
        }
        val manager = context.getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(pendingId)
        val cursor = manager.query(query)
        cursor.use {
            if (!it.moveToFirst()) {
                settingsRepository.clearPendingGitHubUpdateDownload()
                viewModel.setAppUpdateInstallReady(false)
                return false
            }
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    if (manager.getUriForDownloadedFile(pendingId) == null) {
                        settingsRepository.clearPendingGitHubUpdateDownload()
                        viewModel.setAppUpdateInstallReady(false)
                        return false
                    }
                    if (!canRequestUnknownAppInstalls()) {
                        viewModel.setAppUpdateInstallReady(true)
                        return false
                    }
                    viewModel.setAppUpdateInstallReady(true)
                    return false
                }
                DownloadManager.STATUS_FAILED -> {
                    settingsRepository.clearPendingGitHubUpdateDownload()
                    viewModel.setAppUpdateInstallReady(false)
                    return false
                }
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> {
                    viewModel.setAppUpdateInstallReady(false)
                    return true
                }
                else -> {
                    viewModel.setAppUpdateInstallReady(false)
                    return false
                }
            }
        }
        return false
    }

    fun cleanupInstalledGitHubUpdateArtifact() {
        val downloadId = settingsRepository.pendingGitHubUpdateCleanupDownloadId()
        if (downloadId <= 0L) return
        runCatching {
            context.getSystemService(DownloadManager::class.java).remove(downloadId)
        }
        settingsRepository.clearPendingGitHubUpdateCleanupDownload()
    }

    private fun checkGitHubAppUpdate(force: Boolean) {
        activityScope.launch {
            val result = GitHubReleaseUpdateChecker(
                apiUrl = githubReleasesApiUrl,
                fallbackReleaseUrl = githubReleasesPageUrl
            ).check(appVersionName())

            when (result) {
                is AppUpdateCheckResult.Available -> {
                    viewModel.setAvailableAppUpdate(result)
                    maybeShowGitHubAppUpdateNotification(result, force)
                }

                AppUpdateCheckResult.Current -> {
                    if (force) {
                        viewModel.clearAvailableAppUpdate("You're on the latest GitHub build.")
                        Toast.makeText(context, "No GitHub update found", Toast.LENGTH_SHORT).show()
                    }
                }

                is AppUpdateCheckResult.Failed -> {
                    if (force) {
                        viewModel.clearAvailableAppUpdate(result.message)
                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                }

                AppUpdateCheckResult.Unsupported -> {
                    if (force) {
                        viewModel.clearAvailableAppUpdate("GitHub updates are not configured for this build.")
                    }
                }
            }
        }
    }

    private fun checkPlayAppUpdate(force: Boolean) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { updateInfo ->
                val isAvailable = updateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                val canUpdate = updateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                if (isAvailable && canUpdate) {
                    val version = updateInfo.availableVersionCode().toString()
                    viewModel.setPlayUpdateAvailable(version)
                    maybeShowPlayUpdateNotification(version, force)
                } else if (force) {
                    viewModel.clearAvailableAppUpdate("You're on the latest Google Play build.")
                    Toast.makeText(context, "No Google Play update found", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { error ->
                if (force) {
                    val message = error.message ?: "Could not check Google Play for updates."
                    viewModel.setAppUpdateStatus(message)
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun maybeShowGitHubAppUpdateNotification(
        update: AppUpdateCheckResult.Available,
        force: Boolean
    ) {
        if (!settingsRepository.shouldNotifyAppUpdate(update.version, force)) return
        val releaseNotesIntent = PendingIntent.getActivity(
            context,
            update.releaseUrl.hashCode(),
            Intent(Intent.ACTION_VIEW, update.releaseUrl.toUri()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val downloadIntent = update.downloadUrl?.let { downloadUrl ->
            buildAppUpdateDownloadPendingIntent(
                downloadUrl = downloadUrl,
                fileName = update.fileName ?: "hermes-webui-v${update.version}-github.apk"
            )
        }
        showAppUpdateNotification(
            title = update.title,
            body = update.body,
            version = update.version,
            pendingIntent = downloadIntent ?: releaseNotesIntent,
            secondaryActionLabel = if (downloadIntent != null) "Release notes" else null,
            secondaryActionIntent = if (downloadIntent != null) releaseNotesIntent else null,
            force = force
        )
    }

    private fun maybeShowPlayUpdateNotification(version: String, force: Boolean) {
        if (!settingsRepository.shouldNotifyAppUpdate("play-$version", force)) return
        val pendingIntent = PendingIntent.getActivity(
            context,
            version.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_START_PLAY_UPDATE
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        showAppUpdateNotification(
            title = "Hermes WebUI update available",
            body = "A newer Google Play build is ready to install.",
            version = "play-$version",
            pendingIntent = pendingIntent,
            secondaryActionLabel = null,
            secondaryActionIntent = null,
            force = force
        )
    }

    private fun buildAppUpdateDownloadPendingIntent(
        downloadUrl: String,
        fileName: String
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_DOWNLOAD_APP_UPDATE
            putExtra(EXTRA_APP_UPDATE_DOWNLOAD_URL, downloadUrl)
            putExtra(EXTRA_APP_UPDATE_FILE_NAME, fileName)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            downloadUrl.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun downloadGitHubUpdate(downloadUrl: String?, fileName: String?) {
        val url = downloadUrl?.trim().orEmpty()
        if (!AppUpdateDownloadPolicy.isTrustedApkDownloadUrl(url)) {
            Toast.makeText(context, "No GitHub APK download is available", Toast.LENGTH_LONG).show()
            return
        }
        viewModel.setAppUpdateInstallReady(false)
        promptUnknownAppInstallPermissionForUpcomingUpdateInstall()
        val parsed = url.toUri()

        val safeFileName = fileName
            ?.trim()
            ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: URLUtil.guessFileName(url, null, "application/vnd.android.package-archive")
        val request = DownloadManager.Request(parsed).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setTitle(safeFileName)
            setDescription("Downloading Hermes WebUI GitHub APK")
            setAllowedOverMetered(true)
            setMimeType("application/vnd.android.package-archive")
            setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                safeFileName
            )
        }
        val downloadId = context.getSystemService(DownloadManager::class.java).enqueue(request)
        settingsRepository.markPendingGitHubUpdateDownload(downloadId)
        startPendingGitHubDownloadMonitor()
        Toast.makeText(context, "GitHub APK download started", Toast.LENGTH_SHORT).show()
    }

    private fun promptUnknownAppInstallPermissionForUpcomingUpdateInstall() {
        if (canRequestUnknownAppInstalls()) return
        val settingsIntent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(settingsIntent) }
        Toast.makeText(
            context,
            "Allow installs from Hermes WebUI while the APK downloads.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun promptInstallDownloadedGithubUpdate(downloadId: Long) {
        if (downloadId <= 0L) {
            viewModel.setAppUpdateInstallReady(false)
            Toast.makeText(context, "No downloaded update is ready to install", Toast.LENGTH_SHORT).show()
            return
        }
        val manager = context.getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = manager.query(query)
        cursor.use {
            if (!it.moveToFirst()) {
                settingsRepository.clearPendingGitHubUpdateDownload()
                viewModel.setAppUpdateInstallReady(false)
                Toast.makeText(context, "Downloaded update is no longer available", Toast.LENGTH_SHORT).show()
                return
            }

            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    viewModel.setAppUpdateInstallReady(true)
                    val apkUri = manager.getUriForDownloadedFile(downloadId)
                    if (apkUri == null) {
                        settingsRepository.clearPendingGitHubUpdateDownload()
                        viewModel.setAppUpdateInstallReady(false)
                        Toast.makeText(context, "Unable to open downloaded APK", Toast.LENGTH_LONG).show()
                        return
                    }
                    launchPackageInstaller(downloadId, apkUri)
                }

                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> {
                    viewModel.setAppUpdateInstallReady(false)
                    Toast.makeText(context, "GitHub APK is still downloading", Toast.LENGTH_SHORT).show()
                }

                else -> {
                    settingsRepository.clearPendingGitHubUpdateDownload()
                    viewModel.setAppUpdateInstallReady(false)
                    Toast.makeText(context, "GitHub APK download failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchPackageInstaller(downloadId: Long, apkUri: Uri) {
        val canRequestInstalls = canRequestUnknownAppInstalls()
        if (!canRequestInstalls) {
            viewModel.setAppUpdateInstallReady(true)
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settingsIntent) }
            Toast.makeText(
                context,
                "Allow installs from Hermes WebUI, then install the downloaded update.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val started = runCatching { context.startActivity(installIntent) }.isSuccess
        if (started) {
            NotificationManagerCompat.from(context).cancel(APP_UPDATE_INSTALL_READY_NOTIFICATION_ID)
            settingsRepository.markPendingGitHubUpdateCleanupDownload(downloadId)
            settingsRepository.clearPendingGitHubUpdateDownload()
            viewModel.setAppUpdateInstallReady(false)
        } else {
            viewModel.setAppUpdateInstallReady(true)
            Toast.makeText(context, "No installer app was found for the downloaded APK", Toast.LENGTH_LONG).show()
        }
    }

    private fun canRequestUnknownAppInstalls(): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    @SuppressLint("MissingPermission")
    private fun showInstallReadyNotification(downloadId: Long) {
        if (notificationPermissionState() != "granted") return
        viewModel.setAppUpdateInstallReady(true)
        val installIntent = PendingIntent.getActivity(
            context,
            downloadId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_INSTALL_DOWNLOADED_APP_UPDATE
                putExtra(EXTRA_APP_UPDATE_DOWNLOAD_ID, downloadId)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, notificationChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("GitHub APK downloaded")
            .setContentText("Tap to install the update.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Tap to install the downloaded Hermes WebUI update APK."))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(ContextCompat.getColor(context, R.color.brand_sky))
            .setContentIntent(installIntent)
            .addAction(0, "Install", installIntent)
            .build()
        NotificationManagerCompat.from(context).notify(APP_UPDATE_INSTALL_READY_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun showAppUpdateNotification(
        title: String,
        body: String,
        version: String,
        pendingIntent: PendingIntent,
        secondaryActionLabel: String?,
        secondaryActionIntent: PendingIntent?,
        force: Boolean
    ) {
        if (!settingsRepository.isAppUpdateAlertsEnabled()) return
        if (notificationPermissionState() != "granted") {
            if (force) {
                requestNotificationPermissionIfNeeded()
                Toast.makeText(context, body, Toast.LENGTH_LONG).show()
            }
            return
        }

        val notification = NotificationCompat.Builder(context, notificationChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(ContextCompat.getColor(context, R.color.brand_sky))
            .setContentIntent(pendingIntent)
            .apply {
                if (!secondaryActionLabel.isNullOrBlank() && secondaryActionIntent != null) {
                    addAction(0, secondaryActionLabel, secondaryActionIntent)
                }
            }
            .build()

        NotificationManagerCompat.from(context).notify(APP_UPDATE_NOTIFICATION_ID, notification)
        settingsRepository.markAppUpdateNotified(version)
    }

    fun startPlayUpdateFlow() {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { updateInfo ->
                if (
                    updateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    updateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                ) {
                    launchPlayUpdate(updateInfo)
                } else {
                    Toast.makeText(context, "No Google Play update is available right now", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, "Could not start Google Play update", Toast.LENGTH_LONG).show()
            }
    }

    private fun launchPlayUpdate(updateInfo: AppUpdateInfo) {
        appUpdateManager.startUpdateFlowForResult(
            updateInfo,
            playUpdateLauncher,
            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
        )
    }
}
