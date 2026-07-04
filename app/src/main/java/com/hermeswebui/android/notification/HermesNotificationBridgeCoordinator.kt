package com.hermeswebui.android.notification

import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.webkit.WebView
import com.hermeswebui.android.data.SettingsRepository
import org.json.JSONObject

class HermesNotificationBridgeCoordinator(
    private val context: Context,
    private val bridgeName: String,
    private val settingsRepository: SettingsRepository,
    private val isTrustedSource: (Uri, Boolean) -> Boolean,
    private val showNotification: (JSONObject) -> Boolean,
    private val requestNotificationPermissionLauncher: () -> Unit,
    private val runOnUiThread: ((() -> Unit) -> Unit)
) {
    private data class NotificationPermissionReply(
        val id: String?,
        val replyProxy: JavaScriptReplyProxy
    )

    private val pendingNotificationPermissionReplies = mutableListOf<NotificationPermissionReply>()
    private var notificationPermissionRequestInFlight = false

    fun installBridge(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return

        runCatching {
            WebViewCompat.addWebMessageListener(
                view,
                bridgeName,
                setOf("*")
            ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
                runOnUiThread {
                    handleBridgeMessage(
                        message = message,
                        sourceOrigin = sourceOrigin,
                        isMainFrame = isMainFrame,
                        replyProxy = replyProxy
                    )
                }
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        val permission = if (granted && areNativeNotificationsEnabled()) {
            "granted"
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasRuntimePostNotificationsPermission()) {
                settingsRepository.markNotificationPermissionRequested()
            }
            if (hasRequestedNotificationPermission()) "denied" else "default"
        }
        notificationPermissionRequestInFlight = false
        flushNotificationPermissionReplies(permission)
    }

    fun permissionState(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return when {
                hasRuntimePostNotificationsPermission() && areNativeNotificationsEnabled() -> "granted"
                hasRequestedNotificationPermission() -> "denied"
                else -> "default"
            }
        }

        return if (areNativeNotificationsEnabled()) "granted" else "denied"
    }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasRuntimePostNotificationsPermission()) return
        settingsRepository.markNotificationPermissionRequested()
        launchPermissionRequestIfNeeded()
    }

    private fun handleBridgeMessage(
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        val parsed = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull()
        val id = parsed?.optString("id")?.takeIf { it.isNotBlank() }
        if (parsed == null || !isTrustedSource(sourceOrigin, isMainFrame)) {
            postBridgeReply(replyProxy, id, ok = false, permission = "denied")
            return
        }

        when (parsed.optString("type")) {
            "permissionState" -> {
                postBridgeReply(
                    replyProxy = replyProxy,
                    id = id,
                    ok = true,
                    permission = permissionState()
                )
            }

            "requestPermission" -> requestBridgeNotificationPermission(replyProxy, id)
            "show" -> {
                val shown = showNotification(parsed.optJSONObject("payload") ?: JSONObject())
                postBridgeReply(
                    replyProxy = replyProxy,
                    id = id,
                    ok = shown,
                    permission = permissionState()
                )
            }

            else -> postBridgeReply(
                replyProxy = replyProxy,
                id = id,
                ok = false,
                permission = permissionState()
            )
        }
    }

    private fun requestBridgeNotificationPermission(replyProxy: JavaScriptReplyProxy, id: String?) {
        val currentPermission = permissionState()
        if (currentPermission == "granted") {
            postBridgeReply(replyProxy, id, ok = true, permission = "granted")
            return
        }
        if (currentPermission == "denied") {
            postBridgeReply(replyProxy, id, ok = false, permission = "denied")
            Toast.makeText(context, "Enable app notifications in Android settings", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            postBridgeReply(replyProxy, id, ok = false, permission = currentPermission)
            return
        }

        settingsRepository.markNotificationPermissionRequested()
        pendingNotificationPermissionReplies += NotificationPermissionReply(id, replyProxy)
        launchPermissionRequestIfNeeded()
    }

    private fun postBridgeReply(
        replyProxy: JavaScriptReplyProxy,
        id: String?,
        ok: Boolean,
        permission: String,
        error: String? = null
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val response = JSONObject()
            .put("id", id ?: "")
            .put("ok", ok)
            .put("permission", permission)
        if (!error.isNullOrBlank()) {
            response.put("error", error)
        }
        runCatching {
            replyProxy.postMessage(response.toString())
        }
    }

    private fun flushNotificationPermissionReplies(permission: String) {
        val replies = pendingNotificationPermissionReplies.toList()
        pendingNotificationPermissionReplies.clear()
        replies.forEach { pending ->
            postBridgeReply(
                replyProxy = pending.replyProxy,
                id = pending.id,
                ok = permission == "granted",
                permission = permission
            )
        }
    }

    private fun hasRuntimePostNotificationsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun areNativeNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun hasRequestedNotificationPermission(): Boolean {
        return settingsRepository.hasRequestedNotificationPermission()
    }

    private fun launchPermissionRequestIfNeeded() {
        if (!notificationPermissionRequestInFlight) {
            notificationPermissionRequestInFlight = true
            requestNotificationPermissionLauncher()
        }
    }
}
