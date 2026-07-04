package com.hermeswebui.android.update

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
        const val EXTRA_APP_UPDATE_DOWNLOAD_URL = "com.hermeswebui.android.extra.APP_UPDATE_DOWNLOAD_URL"
        const val EXTRA_APP_UPDATE_FILE_NAME = "com.hermeswebui.android.extra.APP_UPDATE_FILE_NAME"

        private const val APP_UPDATE_NOTIFICATION_ID = 7_001
        private const val AUTOMATIC_APP_UPDATE_CHECK_DELAY_MS = 60_000L
    }

    private var automaticAppUpdateCheckJob: Job? = null

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
        if (!settingsRepository.shouldCheckForAppUpdates(System.currentTimeMillis(), force = false)) return
        if (automaticAppUpdateCheckJob?.isActive == true) return

        automaticAppUpdateCheckJob = activityScope.launch {
            delay(AUTOMATIC_APP_UPDATE_CHECK_DELAY_MS)
            if (!isActivityVisible()) return@launch
            checkForAppUpdates(force = false)
            automaticAppUpdateCheckJob = null
        }
    }

    fun cancelAutomaticAppUpdateCheck() {
        automaticAppUpdateCheckJob?.cancel()
        automaticAppUpdateCheckJob = null
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

            else -> false
        }
    }

    fun downloadAvailableGitHubUpdate() {
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
                    viewModel.setAppUpdateStatus("A Google Play update is available.")
                    maybeShowPlayUpdateNotification(version, force)
                } else if (force) {
                    viewModel.setAppUpdateStatus("You're on the latest Google Play build.")
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
        val pendingIntent = PendingIntent.getActivity(
            context,
            update.releaseUrl.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        showAppUpdateNotification(
            title = update.title,
            body = update.body,
            version = update.version,
            pendingIntent = pendingIntent,
            downloadIntent = update.downloadUrl?.let { downloadUrl ->
                buildAppUpdateDownloadPendingIntent(
                    downloadUrl = downloadUrl,
                    fileName = update.fileName ?: "hermes-webui-v${update.version}-github.apk"
                )
            },
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
            downloadIntent = null,
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
        val parsed = Uri.parse(url)

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
        context.getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(context, "GitHub APK download started", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun showAppUpdateNotification(
        title: String,
        body: String,
        version: String,
        pendingIntent: PendingIntent,
        downloadIntent: PendingIntent?,
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
                if (downloadIntent != null) {
                    addAction(0, "Download APK", downloadIntent)
                }
            }
            .build()

        NotificationManagerCompat.from(context).notify(APP_UPDATE_NOTIFICATION_ID, notification)
        settingsRepository.markAppUpdateNotified(version)
    }

    private fun startPlayUpdateFlow() {
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
