package com.hermeswebui.android.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.R
import com.hermeswebui.android.background.ReconnectSessionStreamSupport
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.core.security.WebTrustPolicy
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.launcher.AppShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

internal data class NotificationReplyTarget(
    val serverUrl: String,
    val targetUrl: String,
    val profile: String,
    val notificationTag: String?,
    val notificationId: Int,
    val expiresAt: Long = SystemClock.elapsedRealtime() + 30 * 60_000,
    val token: String = UUID.randomUUID().toString()
)

internal object NotificationReplyActions {
    const val INPUT_KEY = "hermes_reply"
    private var selectedServerUrl: String? = null
    private val targets = linkedMapOf<String, NotificationReplyTarget>()
    private val consumedTokens = mutableSetOf<String>()

    @Synchronized
    fun onSelectedServerChanged(serverUrl: String) {
        if (selectedServerUrl != serverUrl) {
            targets.clear()
            consumedTokens.clear()
            selectedServerUrl = serverUrl
        }
    }

    @Synchronized
    fun clear() {
        targets.clear()
        consumedTokens.clear()
        selectedServerUrl = null
    }

    @Synchronized
    fun action(context: Context, target: NotificationReplyTarget): NotificationCompat.Action {
        onSelectedServerChanged(target.serverUrl)
        targets.entries.removeAll { it.value.expiresAt <= SystemClock.elapsedRealtime() }
        targets.entries.removeAll {
            it.value.notificationId == target.notificationId && it.value.notificationTag == target.notificationTag
        }
        while (targets.size >= 32) targets.remove(targets.keys.first())
        consumedTokens.retainAll(targets.keys)
        val token = target.token
        targets[token] = target
        val intent = Intent(context, HermesNotificationReplyReceiver::class.java)
            .setData(Uri.parse("hermes-reply:$token"))
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        return NotificationCompat.Action.Builder(0, context.getString(R.string.notification_reply_action), pendingIntent)
            .addRemoteInput(RemoteInput.Builder(INPUT_KEY).setLabel(context.getString(R.string.notification_reply_hint)).build())
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    @Synchronized
    fun consume(intent: Intent): NotificationReplyTarget? {
        val target = targets[intent.data?.schemeSpecificPart] ?: return null
        if (!consumedTokens.add(target.token)) return null
        return target.takeIf { it.expiresAt > SystemClock.elapsedRealtime() && it.serverUrl == selectedServerUrl }
    }

    @Synchronized
    fun isCurrent(target: NotificationReplyTarget): Boolean =
        target.serverUrl == selectedServerUrl && targets[target.token] == target &&
            target.expiresAt > SystemClock.elapsedRealtime()
}

class HermesNotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = NotificationReplyActions.consume(intent)
        val message = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(NotificationReplyActions.INPUT_KEY)?.toString()?.trim()
        if (target == null || message.isNullOrBlank() || message.length > 8_000 || !isCurrentTarget(context, target)) {
            showResult(context, target, context.getString(R.string.notification_reply_expired))
            return
        }
        val cookie = CookieManager.getInstance().getCookie(WebUiNotificationReplyClient.endpointUrl(target.targetUrl))
        val pending = goAsync()
        scope.launch {
            try {
                val result = if (NotificationReplyActions.isCurrent(target) && isCurrentTarget(context, target)) {
                    WebUiNotificationReplyClient.send(target, message, cookie)
                } else {
                    NotificationReplyResult.Rejected
                }
                if (NotificationReplyActions.isCurrent(target) && isCurrentTarget(context, target)) {
                    showResult(context, target, context.getString(
                        when (result) {
                            NotificationReplyResult.Sent -> R.string.notification_reply_sent
                            NotificationReplyResult.Rejected -> R.string.notification_reply_failed
                            NotificationReplyResult.Unconfirmed -> R.string.notification_reply_unconfirmed
                        }
                    ))
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun isCurrentTarget(context: Context, target: NotificationReplyTarget): Boolean {
        val settings = SettingsRepository(context).getSettings("", "")
        return settings.isConfigured && settings.serverUrl == target.serverUrl &&
            ReconnectSessionStreamSupport.sessionIdFromUrl(target.targetUrl) != null &&
            WebTrustPolicy(UrlPolicy(settings.allowedHosts), settings.serverUrl, settings.dashboardUrl)
                .isTrustedNotificationTarget(target.targetUrl)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showResult(context: Context, target: NotificationReplyTarget?, body: String) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (target != null) {
                action = HermesNotificationPresenter.ACTION_OPEN_NOTIFICATION_URL
                putExtra(HermesNotificationPresenter.EXTRA_NOTIFICATION_URL, target.targetUrl)
            }
        }
        val open = PendingIntent.getActivity(
            context, target?.notificationId ?: 20_004, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationManagerCompat.from(context).notify(
            target?.notificationTag, target?.notificationId ?: 20_004,
            NotificationCompat.Builder(context, "hermes_webui_notifications")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_reply_action))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .addAction(
                    0, context.getString(R.string.reconnect_notification_action_settings),
                    PendingIntent.getActivity(
                        context, 20_003,
                        AppShortcuts.settingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
        )
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
