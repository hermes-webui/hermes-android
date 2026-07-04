package com.hermeswebui.android.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.R
import org.json.JSONObject

class HermesNotificationPresenter(
    private val context: Context,
    private val channelId: String,
    private val notificationIdBase: Int,
    private val isTrustedTarget: (String?) -> Boolean
) {
    fun handleIntent(intent: Intent?, onOpenTarget: (String) -> Unit): Boolean {
        val targetUrl = notificationTargetUrl(intent) ?: return false
        onOpenTarget(targetUrl)
        return true
    }

    fun notificationTargetUrl(intent: Intent?): String? {
        if (intent?.action != ACTION_OPEN_NOTIFICATION_URL) return null
        return intent.getStringExtra(EXTRA_NOTIFICATION_URL)
            ?.takeIf(isTrustedTarget)
    }

    @SuppressLint("MissingPermission")
    fun showNotification(payload: JSONObject, fallbackTargetUrl: String?): Boolean {
        val options = payload.optJSONObject("options") ?: JSONObject()
        val title = payload.optString("title")
            .takeIf { it.isNotBlank() }
            ?: context.getString(R.string.app_name)
        val body = options.optString("body").takeIf { it.isNotBlank() }
        val tag = options.optString("tag").takeIf { it.isNotBlank() }
        val data = options.optJSONObject("data")
        val targetUrl = data
            ?.optString("url")
            ?.takeIf(isTrustedTarget)
            ?: fallbackTargetUrl?.takeIf(isTrustedTarget)

        val pendingIntent = targetUrl?.let { buildNotificationPendingIntent(it, tag) }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body ?: title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body ?: title))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(ContextCompat.getColor(context, R.color.brand_sky))
            .apply {
                if (pendingIntent != null) {
                    setContentIntent(pendingIntent)
                }
            }
            .build()

        val notificationId = notificationIdBase + ((tag ?: title).hashCode() and 0x0FFFFFFF)
        NotificationManagerCompat.from(context).notify(tag, notificationId, notification)
        return true
    }

    private fun buildNotificationPendingIntent(targetUrl: String, tag: String?): PendingIntent {
        val requestCode = (tag ?: targetUrl).hashCode()
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_NOTIFICATION_URL
            putExtra(EXTRA_NOTIFICATION_URL, targetUrl)
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
        const val ACTION_OPEN_NOTIFICATION_URL = "com.hermeswebui.android.OPEN_NOTIFICATION_URL"
        const val EXTRA_NOTIFICATION_URL = "com.hermeswebui.android.extra.NOTIFICATION_URL"
    }
}
