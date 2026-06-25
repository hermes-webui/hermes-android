package com.hermeswebui.android.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class HermesReconnectService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionStreamJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pollIntervalSeconds = intent
            ?.getIntExtra(EXTRA_POLL_INTERVAL_SECONDS, DEFAULT_POLL_INTERVAL_SECONDS)
            ?: DEFAULT_POLL_INTERVAL_SECONDS
        val sessionTargetUrl = intent?.getStringExtra(EXTRA_SESSION_TARGET_URL)
        val notification = buildNotification(
            pollIntervalSeconds = pollIntervalSeconds,
            contentText = defaultNotificationBody(pollIntervalSeconds),
            targetUrl = sessionTargetUrl
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                RECONNECT_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(RECONNECT_NOTIFICATION_ID, notification)
        }

        sessionStreamJob?.cancel()
        sessionStreamJob = serviceScope.launch {
            streamSessionUpdates(
                baseUrl = intent?.getStringExtra(EXTRA_SERVER_URL),
                sessionId = intent?.getStringExtra(EXTRA_SESSION_ID),
                cookieHeader = intent?.getStringExtra(EXTRA_COOKIE_HEADER),
                sessionTargetUrl = sessionTargetUrl,
                pollIntervalSeconds = pollIntervalSeconds,
                sseTransportEnabled = intent?.getBooleanExtra(EXTRA_SSE_TRANSPORT_ENABLED, false) == true
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sessionStreamJob?.cancel()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(
        pollIntervalSeconds: Int,
        contentText: String,
        targetUrl: String?
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            RECONNECT_NOTIFICATION_ID,
            buildLaunchIntent(targetUrl),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, HERMES_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.reconnect_notification_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildLaunchIntent(targetUrl: String?): Intent {
        return Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_NOTIFICATION_URL
            if (!targetUrl.isNullOrBlank()) {
                putExtra(EXTRA_NOTIFICATION_URL, targetUrl)
            }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }

    private fun defaultNotificationBody(pollIntervalSeconds: Int): String {
        val normalizedInterval = pollIntervalSeconds.coerceAtLeast(1)
        return resources.getQuantityString(
            R.plurals.reconnect_notification_body_polling_interval,
            normalizedInterval,
            normalizedInterval
        )
    }

    private fun streamSessionUpdates(
        baseUrl: String?,
        sessionId: String?,
        cookieHeader: String?,
        sessionTargetUrl: String?,
        pollIntervalSeconds: Int,
        sseTransportEnabled: Boolean
    ) {
        if (!sseTransportEnabled || baseUrl.isNullOrBlank() || sessionId.isNullOrBlank()) return

        val encodedSessionId = runCatching { URLEncoder.encode(sessionId, Charsets.UTF_8.name()) }.getOrNull() ?: return
        val url = runCatching {
            URI(baseUrl.trimEnd('/')).resolve("/api/session/stream?session_id=$encodedSessionId").toURL()
        }.getOrNull() ?: return

        val connection = (runCatching { url.openConnection() as HttpURLConnection }.getOrNull()) ?: return
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 4_000
            connection.readTimeout = 45_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "text/event-stream")
            if (!cookieHeader.isNullOrBlank()) {
                connection.setRequestProperty("Cookie", cookieHeader)
            }

            val responseCode = connection.responseCode
            val contentType = connection.contentType.orEmpty()
            if (responseCode !in 200..299 || !contentType.contains("text/event-stream", ignoreCase = true)) {
                return
            }

            connection.inputStream.bufferedReader().use { reader ->
                consumeSse(reader, baseUrl, sessionTargetUrl, pollIntervalSeconds)
            }
        } catch (_: Exception) {
            return
        } finally {
            connection.disconnect()
        }
    }

    private fun consumeSse(
        reader: BufferedReader,
        baseUrl: String,
        sessionTargetUrl: String?,
        pollIntervalSeconds: Int
    ) {
        var eventName: String? = null
        val dataLines = mutableListOf<String>()

        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.startsWith(":") -> continue
                line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                line.startsWith("data:") -> dataLines += line.substringAfter(':').trimStart()
                line.isBlank() -> {
                    val payload = dataLines.joinToString("\n")
                    if (payload.isNotBlank()) {
                        val update = ReconnectSessionStreamSupport.notificationUpdateForEvent(
                            baseUrl = baseUrl,
                            fallbackTargetUrl = sessionTargetUrl,
                            eventName = eventName,
                            rawData = payload
                        )
                        if (update != null) {
                            publishNotificationUpdate(update, pollIntervalSeconds)
                        }
                    }
                    eventName = null
                    dataLines.clear()
                }
            }
        }
    }

    private fun publishNotificationUpdate(
        update: ReconnectNotificationUpdate,
        pollIntervalSeconds: Int
    ) {
        val notification = buildNotification(
            pollIntervalSeconds = pollIntervalSeconds,
            contentText = update.body,
            targetUrl = update.targetUrl
        )
        NotificationManagerCompat.from(this).notify(RECONNECT_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val EXTRA_POLL_INTERVAL_SECONDS = "extra.POLL_INTERVAL_SECONDS"
        private const val EXTRA_SERVER_URL = "extra.SERVER_URL"
        private const val EXTRA_SESSION_ID = "extra.SESSION_ID"
        private const val EXTRA_SESSION_TARGET_URL = "extra.SESSION_TARGET_URL"
        private const val EXTRA_COOKIE_HEADER = "extra.COOKIE_HEADER"
        private const val EXTRA_SSE_TRANSPORT_ENABLED = "extra.SSE_TRANSPORT_ENABLED"
        private const val HERMES_NOTIFICATION_CHANNEL_ID = "hermes_webui_notifications"
        private const val RECONNECT_NOTIFICATION_ID = 20_001
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 1
        private const val ACTION_OPEN_NOTIFICATION_URL = "com.hermeswebui.android.OPEN_NOTIFICATION_URL"
        private const val EXTRA_NOTIFICATION_URL = "com.hermeswebui.android.extra.NOTIFICATION_URL"

        fun start(
            context: Context,
            pollIntervalSeconds: Int,
            serverUrl: String,
            sessionId: String?,
            sessionTargetUrl: String?,
            cookieHeader: String?,
            sseTransportEnabled: Boolean
        ) {
            val intent = Intent(context, HermesReconnectService::class.java).apply {
                putExtra(EXTRA_POLL_INTERVAL_SECONDS, pollIntervalSeconds.coerceAtLeast(1))
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_SESSION_TARGET_URL, sessionTargetUrl)
                putExtra(EXTRA_COOKIE_HEADER, cookieHeader)
                putExtra(EXTRA_SSE_TRANSPORT_ENABLED, sseTransportEnabled)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HermesReconnectService::class.java))
        }
    }
}


