package com.hermeswebui.android.background

import android.annotation.SuppressLint
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

    // Reference to the in-flight SSE connection so cancellation can actively close the socket.
    // Cancelling sessionStreamJob alone does not interrupt the blocking readLine() in consumeSse
    // nor run the finally-disconnect, so the connection + IO thread would leak until the 45s read
    // timeout (or server close) on every reconnect/relaunch. @Volatile: written on the IO stream
    // thread, read from the main thread in cancelSessionStream().
    @Volatile
    private var activeStreamConnection: HttpURLConnection? = null
    private var activeServerUrl: String? = null
    private var activeSessionId: String? = null
    private var activeSessionTargetUrl: String? = null
    private var activeCookieHeader: String? = null
    private var activePollIntervalSeconds: Int = DEFAULT_POLL_INTERVAL_SECONDS
    private var activeSseTransportEnabled: Boolean = false
    private var activeIsReconnecting: Boolean = false
    private var activeShowFullTextOnLockScreen: Boolean = false
    private var currentNotificationBody: String = ""
    private var currentNotificationTargetUrl: String? = null
    private var currentApprovalRequest: NotificationApprovalRequest? = null
    private val approvalStateLock = Any()
    private val respondedApprovalIds = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pollIntervalSeconds = intent
            ?.getIntExtra(EXTRA_POLL_INTERVAL_SECONDS, DEFAULT_POLL_INTERVAL_SECONDS)
            ?: DEFAULT_POLL_INTERVAL_SECONDS
        val serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL)
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val sessionTargetUrl = intent?.getStringExtra(EXTRA_SESSION_TARGET_URL)
        val cookieHeader = intent?.getStringExtra(EXTRA_COOKIE_HEADER)
        val sseTransportEnabled = intent?.getBooleanExtra(EXTRA_SSE_TRANSPORT_ENABLED, false) == true
        val isReconnecting = intent?.getBooleanExtra(EXTRA_IS_RECONNECTING, false) == true
        val showFullTextOnLockScreen =
            intent?.getBooleanExtra(EXTRA_SHOW_FULL_TEXT_ON_LOCK_SCREEN, false) == true

        activeServerUrl = serverUrl
        activeSessionId = sessionId
        activeSessionTargetUrl = sessionTargetUrl
        activeCookieHeader = cookieHeader
        activePollIntervalSeconds = pollIntervalSeconds
        activeSseTransportEnabled = sseTransportEnabled
        activeIsReconnecting = isReconnecting
        activeShowFullTextOnLockScreen = showFullTextOnLockScreen

        val notification = buildNotification(
            pollIntervalSeconds = pollIntervalSeconds,
            contentText = currentNotificationBody.ifBlank {
                defaultNotificationBody(pollIntervalSeconds, isReconnecting)
            },
            targetUrl = currentNotificationTargetUrl ?: sessionTargetUrl,
            showFullTextOnLockScreen = showFullTextOnLockScreen,
            isReconnecting = isReconnecting,
            approvalRequest = currentApprovalRequest
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

        if (intent?.action == ACTION_RESPOND_APPROVAL) {
            handleApprovalAction(intent)
            return START_NOT_STICKY
        }

        cancelSessionStream()
        sessionStreamJob = serviceScope.launch {
            streamSessionUpdates(
                baseUrl = serverUrl,
                sessionId = sessionId,
                cookieHeader = cookieHeader,
                sessionTargetUrl = sessionTargetUrl,
                pollIntervalSeconds = pollIntervalSeconds,
                sseTransportEnabled = sseTransportEnabled,
                isReconnecting = isReconnecting,
                showFullTextOnLockScreen = showFullTextOnLockScreen
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelSessionStream()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Cancel the SSE stream job AND actively disconnect its socket. The disconnect unblocks the
     * coroutine's blocking readLine() so it throws, runs its finally, and stops the IO thread
     * instead of leaking it until the read timeout.
     */
    private fun cancelSessionStream() {
        sessionStreamJob?.cancel()
        sessionStreamJob = null
        runCatching { activeStreamConnection?.disconnect() }
        activeStreamConnection = null
    }

    private fun buildNotification(
        pollIntervalSeconds: Int,
        contentText: String,
        targetUrl: String?,
        showFullTextOnLockScreen: Boolean,
        isReconnecting: Boolean,
        approvalRequest: NotificationApprovalRequest?
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            RECONNECT_NOTIFICATION_ID,
            buildLaunchIntent(targetUrl),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val publicNotification = NotificationCompat.Builder(this, HERMES_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.reconnect_notification_title))
            .setContentText(publicNotificationBody(isReconnecting))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

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
            .setVisibility(
                if (showFullTextOnLockScreen) {
                    NotificationCompat.VISIBILITY_PUBLIC
                } else {
                    NotificationCompat.VISIBILITY_PRIVATE
                }
            )
            .setPublicVersion(publicNotification)
            .apply {
                addApprovalActions(this, approvalRequest, targetUrl)
            }
            .build()
    }

    private fun addApprovalActions(
        builder: NotificationCompat.Builder,
        approvalRequest: NotificationApprovalRequest?,
        targetUrl: String?
    ) {
        val approval = approvalRequest ?: return
        val serverUrl = activeServerUrl ?: return
        val sessionId = activeSessionId ?: return
        val allowChoice = ApprovalActionSupport.preferredAllowChoice(approval.choices)
        val denyChoice = ApprovalActionSupport.denyChoice(approval.choices)

        if (allowChoice != null) {
            builder.addAction(
                0,
                ApprovalActionSupport.labelForChoice(allowChoice),
                buildApprovalActionPendingIntent(
                    serverUrl = serverUrl,
                    sessionId = sessionId,
                    cookieHeader = activeCookieHeader,
                    targetUrl = targetUrl,
                    approvalId = approval.approvalId,
                    choice = allowChoice
                )
            )
        }
        if (denyChoice != null) {
            builder.addAction(
                0,
                getString(R.string.approval_action_deny),
                buildApprovalActionPendingIntent(
                    serverUrl = serverUrl,
                    sessionId = sessionId,
                    cookieHeader = activeCookieHeader,
                    targetUrl = targetUrl,
                    approvalId = approval.approvalId,
                    choice = denyChoice
                )
            )
        }
    }

    private fun buildApprovalActionPendingIntent(
        serverUrl: String,
        sessionId: String,
        cookieHeader: String?,
        targetUrl: String?,
        approvalId: String,
        choice: String
    ): PendingIntent {
        val requestCode = ("$approvalId:$choice").hashCode()
        val intent = Intent(this, HermesReconnectService::class.java).apply {
            action = ACTION_RESPOND_APPROVAL
            putExtra(EXTRA_SERVER_URL, serverUrl)
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_SESSION_TARGET_URL, targetUrl)
            putExtra(EXTRA_COOKIE_HEADER, cookieHeader)
            putExtra(EXTRA_POLL_INTERVAL_SECONDS, activePollIntervalSeconds)
            putExtra(EXTRA_SSE_TRANSPORT_ENABLED, activeSseTransportEnabled)
            putExtra(EXTRA_IS_RECONNECTING, activeIsReconnecting)
            putExtra(EXTRA_SHOW_FULL_TEXT_ON_LOCK_SCREEN, activeShowFullTextOnLockScreen)
            putExtra(EXTRA_APPROVAL_ID, approvalId)
            putExtra(EXTRA_APPROVAL_CHOICE, choice)
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

    private fun defaultNotificationBody(pollIntervalSeconds: Int, isReconnecting: Boolean): String {
        if (!isReconnecting) {
            return getString(R.string.reconnect_notification_body_activity)
        }
        val normalizedInterval = pollIntervalSeconds.coerceAtLeast(1)
        return resources.getQuantityString(
            R.plurals.reconnect_notification_body_polling_interval,
            normalizedInterval,
            normalizedInterval
        )
    }

    private fun publicNotificationBody(isReconnecting: Boolean): String {
        return if (isReconnecting) {
            getString(R.string.reconnect_notification_body_public_reconnecting)
        } else {
            getString(R.string.reconnect_notification_body_public_activity)
        }
    }

    private fun handleApprovalAction(intent: Intent) {
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID)?.trim().orEmpty()
        val requestedChoice = ApprovalActionSupport.normalizeChoice(
            intent.getStringExtra(EXTRA_APPROVAL_CHOICE)
        ).orEmpty()
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)?.trim().orEmpty()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.trim().orEmpty()
        val cookieHeader = intent.getStringExtra(EXTRA_COOKIE_HEADER)

        if (approvalId.isBlank() || requestedChoice.isBlank() || serverUrl.isBlank() || sessionId.isBlank()) {
            publishServiceState(
                body = getString(R.string.approval_notification_invalid),
                targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                approvalRequest = null
            )
            return
        }

        val currentApprovalId = currentApprovalRequest?.approvalId
        if (currentApprovalId != null && currentApprovalId != approvalId) {
            publishServiceState(
                body = getString(R.string.approval_notification_expired),
                targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                approvalRequest = null
            )
            return
        }

        if (!markApprovalInFlight(approvalId)) {
            publishServiceState(
                body = getString(R.string.approval_notification_already_sent),
                targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                approvalRequest = null
            )
            return
        }

        publishServiceState(
            body = getString(R.string.approval_notification_sending),
            targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
            approvalRequest = null
        )

        serviceScope.launch {
            val pending = ApprovalClient.fetchPendingApproval(serverUrl, sessionId, cookieHeader)
            if (pending == null || pending.approvalId != approvalId) {
                removeApprovalInFlight(approvalId)
                publishServiceState(
                    body = getString(R.string.approval_notification_expired),
                    targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                    approvalRequest = null
                )
                return@launch
            }
            val pendingChoices = pending.choices.mapNotNull(ApprovalActionSupport::normalizeChoice)
            if (requestedChoice !in pendingChoices) {
                removeApprovalInFlight(approvalId)
                publishServiceState(
                    body = getString(R.string.approval_notification_invalid_choice),
                    targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                    approvalRequest = currentApprovalRequest
                )
                return@launch
            }

            val resolvedSessionId = pending.sessionId ?: sessionId
            val success = ApprovalClient.submitApprovalResponse(
                baseUrl = serverUrl,
                sessionId = resolvedSessionId,
                approvalId = approvalId,
                choice = requestedChoice,
                cookieHeader = cookieHeader
            )

            if (!success) {
                removeApprovalInFlight(approvalId)
                publishServiceState(
                    body = getString(R.string.approval_notification_failed),
                    targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                    approvalRequest = currentApprovalRequest
                )
                return@launch
            }

            publishServiceState(
                body = getString(
                    R.string.approval_notification_sent,
                    ApprovalActionSupport.labelForChoice(requestedChoice)
                ),
                targetUrl = currentNotificationTargetUrl ?: activeSessionTargetUrl,
                approvalRequest = null
            )
        }
    }

    private fun streamSessionUpdates(
        baseUrl: String?,
        sessionId: String?,
        cookieHeader: String?,
        sessionTargetUrl: String?,
        pollIntervalSeconds: Int,
        sseTransportEnabled: Boolean,
        isReconnecting: Boolean,
        showFullTextOnLockScreen: Boolean
    ) {
        if (!sseTransportEnabled || baseUrl.isNullOrBlank() || sessionId.isNullOrBlank()) {
            if (!isReconnecting) {
                stopSelf()
            }
            return
        }

        val encodedSessionId = runCatching { URLEncoder.encode(sessionId, Charsets.UTF_8.name()) }.getOrNull() ?: return
        val url = runCatching {
            URI(baseUrl.trimEnd('/')).resolve("/api/session/stream?session_id=$encodedSessionId").toURL()
        }.getOrNull() ?: return

        val connection = (runCatching { url.openConnection() as HttpURLConnection }.getOrNull()) ?: return
        activeStreamConnection = connection
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
                if (!isReconnecting) {
                    stopSelf()
                }
                return
            }

            connection.inputStream.bufferedReader().use { reader ->
                val shouldStop = consumeSse(
                    reader = reader,
                    baseUrl = baseUrl,
                    sessionTargetUrl = sessionTargetUrl,
                    pollIntervalSeconds = pollIntervalSeconds,
                    showFullTextOnLockScreen = showFullTextOnLockScreen,
                    isReconnecting = isReconnecting
                )
                if (shouldStop || !isReconnecting) {
                    stopSelf()
                }
            }
        } catch (_: Exception) {
            if (!isReconnecting) {
                stopSelf()
            }
            return
        } finally {
            // Only clear the shared reference if it still points at this connection: a concurrent
            // relaunch may have already installed a newer one that must stay cancelable.
            if (activeStreamConnection === connection) activeStreamConnection = null
            connection.disconnect()
        }
    }

    private fun consumeSse(
        reader: BufferedReader,
        baseUrl: String,
        sessionTargetUrl: String?,
        pollIntervalSeconds: Int,
        showFullTextOnLockScreen: Boolean,
        isReconnecting: Boolean
    ): Boolean {
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
                            publishNotificationUpdate(
                                update = update,
                                pollIntervalSeconds = pollIntervalSeconds,
                                showFullTextOnLockScreen = showFullTextOnLockScreen,
                                isReconnecting = isReconnecting
                            )
                            if (update.isTerminal) {
                                return true
                            }
                        }
                    }
                    eventName = null
                    dataLines.clear()
                }
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun publishNotificationUpdate(
        update: ReconnectNotificationUpdate,
        pollIntervalSeconds: Int,
        showFullTextOnLockScreen: Boolean,
        isReconnecting: Boolean
    ) {
        if (update.approvalRequest?.approvalId != currentApprovalRequest?.approvalId) {
            clearRespondedApprovals()
        }
        currentApprovalRequest = update.approvalRequest
        currentNotificationBody = update.body
        currentNotificationTargetUrl = update.targetUrl
        val notification = buildNotification(
            pollIntervalSeconds = pollIntervalSeconds,
            contentText = update.body,
            targetUrl = update.targetUrl,
            showFullTextOnLockScreen = showFullTextOnLockScreen,
            isReconnecting = isReconnecting,
            approvalRequest = update.approvalRequest
        )
        NotificationManagerCompat.from(this).notify(RECONNECT_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun publishServiceState(
        body: String,
        targetUrl: String?,
        approvalRequest: NotificationApprovalRequest?
    ) {
        currentNotificationBody = body
        currentNotificationTargetUrl = targetUrl
        currentApprovalRequest = approvalRequest
        val notification = buildNotification(
            pollIntervalSeconds = activePollIntervalSeconds,
            contentText = body,
            targetUrl = targetUrl,
            showFullTextOnLockScreen = activeShowFullTextOnLockScreen,
            isReconnecting = activeIsReconnecting,
            approvalRequest = approvalRequest
        )
        NotificationManagerCompat.from(this).notify(RECONNECT_NOTIFICATION_ID, notification)
    }

    private fun markApprovalInFlight(approvalId: String): Boolean {
        synchronized(approvalStateLock) {
            if (approvalId in respondedApprovalIds) {
                return false
            }
            respondedApprovalIds += approvalId
            if (respondedApprovalIds.size > MAX_RESPONDED_APPROVAL_HISTORY) {
                respondedApprovalIds.firstOrNull()?.let { respondedApprovalIds.remove(it) }
            }
            return true
        }
    }

    private fun removeApprovalInFlight(approvalId: String) {
        synchronized(approvalStateLock) {
            respondedApprovalIds.remove(approvalId)
        }
    }

    private fun clearRespondedApprovals() {
        synchronized(approvalStateLock) {
            respondedApprovalIds.clear()
        }
    }

    companion object {
        private const val ACTION_RESPOND_APPROVAL = "com.hermeswebui.android.action.RESPOND_APPROVAL"
        private const val EXTRA_POLL_INTERVAL_SECONDS = "extra.POLL_INTERVAL_SECONDS"
        private const val EXTRA_SERVER_URL = "extra.SERVER_URL"
        private const val EXTRA_SESSION_ID = "extra.SESSION_ID"
        private const val EXTRA_SESSION_TARGET_URL = "extra.SESSION_TARGET_URL"
        private const val EXTRA_COOKIE_HEADER = "extra.COOKIE_HEADER"
        private const val EXTRA_SSE_TRANSPORT_ENABLED = "extra.SSE_TRANSPORT_ENABLED"
        private const val EXTRA_IS_RECONNECTING = "extra.IS_RECONNECTING"
        private const val EXTRA_SHOW_FULL_TEXT_ON_LOCK_SCREEN = "extra.SHOW_FULL_TEXT_ON_LOCK_SCREEN"
        private const val EXTRA_APPROVAL_ID = "extra.APPROVAL_ID"
        private const val EXTRA_APPROVAL_CHOICE = "extra.APPROVAL_CHOICE"
        private const val HERMES_NOTIFICATION_CHANNEL_ID = "hermes_webui_notifications"
        private const val RECONNECT_NOTIFICATION_ID = 20_001
        private const val DEFAULT_POLL_INTERVAL_SECONDS = 1
        private const val MAX_RESPONDED_APPROVAL_HISTORY = 64
        private const val ACTION_OPEN_NOTIFICATION_URL = "com.hermeswebui.android.OPEN_NOTIFICATION_URL"
        private const val EXTRA_NOTIFICATION_URL = "com.hermeswebui.android.extra.NOTIFICATION_URL"

        fun start(
            context: Context,
            pollIntervalSeconds: Int,
            serverUrl: String,
            sessionId: String?,
            sessionTargetUrl: String?,
            cookieHeader: String?,
            sseTransportEnabled: Boolean,
            isReconnecting: Boolean,
            showFullTextOnLockScreen: Boolean
        ) {
            val intent = Intent(context, HermesReconnectService::class.java).apply {
                putExtra(EXTRA_POLL_INTERVAL_SECONDS, pollIntervalSeconds.coerceAtLeast(1))
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_SESSION_TARGET_URL, sessionTargetUrl)
                putExtra(EXTRA_COOKIE_HEADER, cookieHeader)
                putExtra(EXTRA_SSE_TRANSPORT_ENABLED, sseTransportEnabled)
                putExtra(EXTRA_IS_RECONNECTING, isReconnecting)
                putExtra(EXTRA_SHOW_FULL_TEXT_ON_LOCK_SCREEN, showFullTextOnLockScreen)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HermesReconnectService::class.java))
        }
    }
}


