package com.hermeswebui.android.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.R
import com.hermeswebui.android.launcher.AppShortcuts
import com.hermeswebui.android.background.ReconnectSessionStreamSupport
import org.json.JSONObject

class HermesNotificationPresenter(
    private val context: Context,
    private val channelId: String,
    private val isTrustedTarget: (String?) -> Boolean
) {
    private var ongoingNotificationShown = false

    @SuppressLint("MissingPermission")
    fun syncOngoingNotification(enabled: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!enabled || !manager.areNotificationsEnabled()) {
            cancelOngoingNotification()
            return
        }
        if (ongoingNotificationShown) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    ONGOING_CHANNEL_ID,
                    context.getString(R.string.ongoing_notification_channel),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = context.getString(R.string.ongoing_notification_channel_description)
                    setShowBadge(false)
                }
            )
        }
        val openIntent = PendingIntent.getActivity(
            context,
            ONGOING_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        HermesNotificationSlot.setOpen(
            context,
            NotificationCompat.Builder(context, ONGOING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.ongoing_notification_title))
                .setContentText(context.getString(R.string.ongoing_notification_body))
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .addAction(0, context.getString(R.string.reconnect_notification_action_settings), settingsPendingIntent())
                .build()
        )
        ongoingNotificationShown = true
    }

    fun cancelOngoingNotification() {
        HermesNotificationSlot.setOpen(context, null)
        ongoingNotificationShown = false
    }

    private fun settingsPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        SETTINGS_REQUEST_CODE,
        AppShortcuts.settingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun handleIntent(intent: Intent?, onOpenTarget: (String) -> Unit): Boolean {
        val targetUrl = notificationTargetUrl(intent) ?: return false
        onOpenTarget(targetUrl)
        return true
    }

    fun notificationTargetUrl(intent: Intent?): String? {
        if (intent?.action != ACTION_OPEN_NOTIFICATION_URL) return null
        HermesNotificationSlot.dismiss(context, intent.getStringExtra(HermesNotificationSlot.EXTRA_TOKEN))
        return intent.getStringExtra(EXTRA_NOTIFICATION_URL)
            ?.takeIf(isTrustedTarget)
    }

    @SuppressLint("MissingPermission")
    fun showNotification(
        payload: JSONObject,
        fallbackTargetUrl: String?,
        configuredServerUrl: String? = null
    ): Boolean = synchronized(NotificationReplyActions) {
        val options = payload.optJSONObject("options") ?: JSONObject()
        val title = payload.optString("title")
            .takeIf { it.isNotBlank() }
            ?: context.getString(R.string.app_name)
        val body = options.optString("body").takeIf { it.isNotBlank() }
        val data = options.optJSONObject("data")
        val targetUrl = data
            ?.optString("url")
            ?.takeIf(isTrustedTarget)
            ?: fallbackTargetUrl?.takeIf(isTrustedTarget)

        val token = HermesNotificationSlot.newMessageToken()
        val pendingIntent = targetUrl?.let { buildNotificationPendingIntent(it, token) }
        val notificationId = HermesNotificationSlot.ID
        NotificationReplyActions.invalidate(null, notificationId)
        val replyProfile = payload.optString("replyProfile").takeIf { it.isNotBlank() && it.length <= 128 }
        val canReply = targetUrl != null && configuredServerUrl != null && replyProfile != null &&
            !HermesNotificationSlot.hasPendingApproval() &&
            ReconnectSessionStreamSupport.sessionIdFromUrl(targetUrl) == payload.optString("replySessionId") &&
            payload.optString("replySessionId").isNotBlank()
        val notification = NotificationCompat.Builder(context, channelId)
            .addExtras(android.os.Bundle().apply {
                putString(HermesNotificationSlot.EXTRA_TOKEN, token)
                putString(HermesNotificationSlot.EXTRA_SERVER, configuredServerUrl)
            })
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body ?: title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body ?: title))
            .setAutoCancel(true)
            .setDeleteIntent(HermesNotificationSlot.deleteIntent(context, token))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(ContextCompat.getColor(context, R.color.brand_sky))
            .apply {
                if (canReply) {
                    addAction(NotificationReplyActions.action(context, NotificationReplyTarget(
                        configuredServerUrl, targetUrl, replyProfile, null, notificationId
                    )))
                }
                addAction(
                    R.drawable.ic_settings,
                    if (canReply) android.text.SpannableString(context.getString(R.string.notification_settings_compact)).apply {
                        setSpan(
                            android.text.style.TtsSpan.TextBuilder(
                                context.getString(R.string.reconnect_notification_action_settings)
                            ).build(), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    else context.getString(R.string.reconnect_notification_action_settings),
                    settingsPendingIntent()
                )
                if (pendingIntent != null) {
                    setContentIntent(pendingIntent)
                }
            }
            .build()

        HermesNotificationSlot.showMessage(context, notification, token)
        true
    }

    private fun buildNotificationPendingIntent(targetUrl: String, token: String): PendingIntent {
        val requestCode = MESSAGE_REQUEST_CODE
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_NOTIFICATION_URL
            data = android.net.Uri.parse("hermes-notification:$token")
            putExtra(EXTRA_NOTIFICATION_URL, targetUrl)
            putExtra(HermesNotificationSlot.EXTRA_TOKEN, token)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        internal const val ONGOING_NOTIFICATION_ID = HermesNotificationSlot.ID
        internal const val ONGOING_CHANNEL_ID = "hermes_open"
        internal const val MESSAGE_REQUEST_CODE = 20_010
        private const val SETTINGS_REQUEST_CODE = 20_003
        const val ACTION_OPEN_NOTIFICATION_URL = "com.hermeswebui.android.OPEN_NOTIFICATION_URL"
        const val EXTRA_NOTIFICATION_URL = "com.hermeswebui.android.extra.NOTIFICATION_URL"
    }
}
