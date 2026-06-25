package com.hermeswebui.android.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.R
import com.hermeswebui.android.data.DiagnosticsLogger
import com.hermeswebui.android.data.SettingsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HermesDebugLoggingService : Service() {
    private var logcatProcess: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_LOGGING) {
            // Persist the stop action so settings reflect notification action immediately.
            SettingsRepository(applicationContext).setDebugLoggingEnabled(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        ensureDebugChannel()
        val notification = buildNotification()
        startLogCaptureIfNeeded()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DEBUG_LOGGING_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(DEBUG_LOGGING_NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopLogCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startLogCaptureIfNeeded() {
        if (logcatProcess != null) return

        val logDir = File(filesDir, "debug-logs").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outFile = File(logDir, "hermes-debug-$timestamp.log")

        // Include environment + app state context at the top of each captured file so bug
        // reports are actionable even when logs are shared out-of-band.
        runCatching {
            outFile.writeText(buildLogSessionHeader())
        }

        val process = runCatching {
            ProcessBuilder(
                "logcat",
                "-v",
                "threadtime",
                "-b",
                "main",
                "-b",
                "system",
                "-b",
                "crash",
                "-T",
                "1"
            )
                .redirectOutput(ProcessBuilder.Redirect.appendTo(outFile))
                .redirectErrorStream(true)
                .start()
        }.getOrElse {
            runCatching {
                ProcessBuilder("logcat", "-v", "threadtime", "-T", "1")
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(outFile))
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull()
        }

        logcatProcess = process
    }

    private fun stopLogCapture() {
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null
    }

    private fun ensureDebugChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            DEBUG_LOGGING_CHANNEL_ID,
            getString(R.string.debug_logging_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.debug_logging_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            DEBUG_LOGGING_NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, HermesDebugLoggingService::class.java).apply {
            action = ACTION_STOP_LOGGING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            DEBUG_LOGGING_NOTIFICATION_ID + 1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, DEBUG_LOGGING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.debug_logging_notification_title))
            .setContentText(getString(R.string.debug_logging_notification_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.debug_logging_notification_body)))
            .setContentIntent(openAppIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.debug_logging_notification_stop_action), stopPendingIntent)
            .build()
    }

    private fun buildLogSessionHeader(): String {
        val settings = SettingsRepository(applicationContext)
        val recentDiagnostics = DiagnosticsLogger.recentText(applicationContext).trim()
        val appSettings = settings.getSettings(
            defaultUrl = getString(R.string.default_server_url),
            defaultDashboardUrl = getString(R.string.default_dashboard_url)
        )
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }.getOrNull()
        val appVersion = packageInfo?.versionName ?: "unknown"
        val debugBuild = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return buildString {
            appendLine("=== Hermes Android Debug Log Session ===")
            appendLine("timestamp: $now")
            appendLine("app_version: $appVersion")
            appendLine("build_mode: ${if (debugBuild) "debuggable" else "release"}")
            appendLine("package: ${applicationContext.packageName}")
            appendLine("android_api: ${Build.VERSION.SDK_INT}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("server_url: ${appSettings.serverUrl}")
            appendLine("dashboard_url: ${appSettings.dashboardUrl}")
            appendLine("background_reconnect_enabled: ${settings.isBackgroundReconnectEnabled()}")
            appendLine("background_activity_full_text_enabled: ${settings.isBackgroundActivityFullTextEnabled()}")
            appendLine("reconnect_poll_interval_seconds: ${settings.getReconnectPollIntervalSeconds()}")
            appendLine("sse_transport_enabled: ${settings.isSseTransportEnabled()}")
            appendLine("debug_logging_enabled: ${settings.isDebugLoggingEnabled()}")
            appendLine("last_loaded_url: ${settings.getLastLoadedUrl().orEmpty()}")
            appendLine("=======================================")
            if (recentDiagnostics.isNotBlank()) {
                appendLine()
                appendLine("=== Hermes Android Diagnostic Breadcrumbs ===")
                appendLine(recentDiagnostics)
                appendLine("=============================================")
            }
            appendLine()
        }
    }

    companion object {
        private const val ACTION_STOP_LOGGING = "com.hermeswebui.android.action.STOP_DEBUG_LOGGING"
        private const val DEBUG_LOGGING_CHANNEL_ID = "hermes_debug_logging"
        private const val DEBUG_LOGGING_NOTIFICATION_ID = 20_011

        fun start(context: Context) {
            val intent = Intent(context, HermesDebugLoggingService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HermesDebugLoggingService::class.java))
        }
    }
}


