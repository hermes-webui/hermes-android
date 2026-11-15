package com.hermeswebui.android.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import java.util.UUID

/** One tray entry, shared with the reconnect foreground service. */
internal object HermesNotificationSlot {
    const val ID = 20_001
    const val ACTION_DISMISS = "com.hermeswebui.android.DISMISS_NOTIFICATION"
    const val EXTRA_TOKEN = "notification_token"
    const val EXTRA_SERVER = "notification_server"
    private var open: Notification? = null
    private var service: Notification? = null
    private var serviceHasApproval = false
    private var message: Notification? = null
    private var messageToken: String? = null
    private var selectedServer: String? = null
    private var lastPosted: Notification? = null
    private var lastOngoing = false
    private var initialized = false

    private fun restore(context: Context) {
        if (initialized) return
        initialized = true
        val retained = context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == ID && it.tag == null }?.notification ?: return
        if (retained.category != Notification.CATEGORY_MESSAGE) return
        val configured = com.hermeswebui.android.data.SettingsRepository(context).getSettings("", "").serverUrl
        if (retained.extras.getString(EXTRA_SERVER) != configured) return
        // Reply targets are deliberately process-local; preserve the unread text, not dead actions.
        message = Notification.Builder.recoverBuilder(context, retained)
            .setActions(*retained.actions.orEmpty().filter {
                it.remoteInputs.isNullOrEmpty()
            }.toTypedArray())
            .setOngoing(false)
            .build()
        messageToken = retained.extras.getString(EXTRA_TOKEN)
    }

    fun setOpen(context: Context, notification: Notification?) = synchronized(NotificationReplyActions) {
        restore(context)
        if (open === notification && notification != null) return@synchronized
        open = notification
        render(context)
    }

    fun newMessageToken(): String = UUID.randomUUID().toString()

    fun currentMessageToken(): String? = synchronized(NotificationReplyActions) { messageToken }

    fun hasPendingApproval(): Boolean = synchronized(NotificationReplyActions) { serviceHasApproval }

    fun deleteIntent(context: Context, token: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, HermesNotificationReplyReceiver::class.java)
            .setAction(ACTION_DISMISS)
            .setData(android.net.Uri.parse("hermes-notification:$token"))
            .putExtra(EXTRA_TOKEN, token),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun showMessage(context: Context, notification: Notification, token: String) =
        synchronized(NotificationReplyActions) {
            restore(context)
            message = notification
            messageToken = token
            render(context)
        }

    fun dismiss(context: Context, token: String?) = synchronized(NotificationReplyActions) {
        if (token == null || token != messageToken) return@synchronized
        clearMessage()
        render(context)
    }

    fun selectServer(context: Context, serverUrl: String) = synchronized(NotificationReplyActions) {
        if (selectedServer == null) {
            restore(context)
            selectedServer = serverUrl
            return@synchronized
        }
        if (selectedServer == serverUrl) return@synchronized
        selectedServer = serverUrl
        clearMessage()
        render(context)
    }

    // Promotion and teardown must share the reply/replacement lock: startForeground posts too.
    fun startService(
        context: Context,
        notification: Notification,
        hasApproval: Boolean = false,
        promote: (Notification) -> Unit
    ) =
        synchronized(NotificationReplyActions) {
            restore(context)
            if (hasApproval) clearMessage()
            service = notification
            serviceHasApproval = hasApproval
            try {
                promote(requireNotNull(current(context)))
                lastPosted = selected()
                lastOngoing = true
            } catch (failure: RuntimeException) {
                service = null
                serviceHasApproval = false
                throw failure
            }
        }

    fun updateService(context: Context, notification: Notification, hasApproval: Boolean) =
        synchronized(NotificationReplyActions) {
            if (hasApproval) clearMessage()
            service = notification
            serviceHasApproval = hasApproval
            render(context)
        }

    fun stopService(context: Context, detach: () -> Unit) = synchronized(NotificationReplyActions) {
        detach()
        service = null
        serviceHasApproval = false
        render(context)
    }

    fun showReplyResult(context: Context, notification: Notification) = synchronized(NotificationReplyActions) {
        // Keep the original delete token so an already queued dismiss still clears this result.
        message = notification
        render(context)
    }

    fun showTerminal(
        context: Context,
        notification: Notification,
        targetUrl: String?,
        serverUrl: String?
    ) = synchronized(NotificationReplyActions) {
        clearMessage()
        val token = newMessageToken()
        messageToken = token
        message = Notification.Builder.recoverBuilder(context, notification)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(PendingIntent.getActivity(
                context, HermesNotificationPresenter.MESSAGE_REQUEST_CODE,
                Intent(context, com.hermeswebui.android.MainActivity::class.java).apply {
                    action = HermesNotificationPresenter.ACTION_OPEN_NOTIFICATION_URL
                    data = android.net.Uri.parse("hermes-notification:$token")
                    putExtra(HermesNotificationPresenter.EXTRA_NOTIFICATION_URL, targetUrl)
                    putExtra(EXTRA_TOKEN, token)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .setDeleteIntent(deleteIntent(context, token))
            .addExtras(android.os.Bundle().apply {
                putString(EXTRA_TOKEN, token)
                putString(EXTRA_SERVER, serverUrl)
            })
            .build()
        render(context)
    }

    private fun clearMessage() {
        message = null
        messageToken = null
        NotificationReplyActions.invalidate(null, ID)
    }

    private fun selected(): Notification? =
        if (serviceHasApproval) service else message ?: service ?: open

    private fun current(context: Context): Notification? {
        val notification = selected() ?: return null
        return Notification.Builder.recoverBuilder(context, notification)
            .setOngoing(service != null || notification === open)
            .setAutoCancel(service == null && notification !== open)
            .setOnlyAlertOnce(
                notification === lastPosted || notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
            )
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun render(context: Context) {
        restore(context)
        val manager = NotificationManagerCompat.from(context)
        val selected = selected()
        val ongoing = service != null || (selected != null && selected === open)
        if (selected != null && selected === lastPosted && ongoing == lastOngoing) return
        val notification = current(context)
        if (notification == null) manager.cancel(ID) else manager.notify(ID, notification)
        lastPosted = selected
        lastOngoing = ongoing
    }
}
