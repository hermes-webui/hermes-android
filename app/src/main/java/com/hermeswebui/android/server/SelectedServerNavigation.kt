package com.hermeswebui.android.server

import com.hermeswebui.android.core.security.WebTrustPolicy
import com.hermeswebui.android.data.HermesApiClient.ServerReadinessStatus
import com.hermeswebui.android.data.ServerProfile

enum class ServerSwitchAction {
    CONFIRM_SWITCH,
    CONFIRM_SIGN_IN_SWITCH,
    SWITCH_NOW,
    BLOCK
}

/**
 * Settings marks the active profile as "Current"; the main WebView must load that same server on
 * switch, on the Current-row reconnect tap and on cold start.
 */
object SelectedServerNavigation {
    /**
     * The active profile is the user's selection. A stored server_url that disagrees with it
     * (left behind by editing the active profile's URL) must not keep the WebView on the old host.
     */
    fun selectedServerUrl(storedServerUrl: String, profiles: List<ServerProfile>): String {
        return profiles.firstOrNull { it.isActive }
            ?.url
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: storedServerUrl
    }

    /** The profile a manually saved server URL corresponds to, so the selection stays in sync. */
    fun activeProfileIdFor(serverUrl: String, profiles: List<ServerProfile>): String? {
        val target = comparableUrl(serverUrl)
        return profiles.firstOrNull { comparableUrl(it.url) == target }?.id
    }

    fun switchAction(
        isReady: Boolean,
        reachable: Boolean,
        status: ServerReadinessStatus,
        authPromptSilenced: Boolean
    ): ServerSwitchAction {
        if (isReady) return ServerSwitchAction.CONFIRM_SWITCH
        if (reachable && status == ServerReadinessStatus.AUTH_REQUIRED) {
            return if (authPromptSilenced) ServerSwitchAction.SWITCH_NOW else ServerSwitchAction.CONFIRM_SIGN_IN_SWITCH
        }
        return ServerSwitchAction.BLOCK
    }

    /** A switch always opens the selected server's root (its sign-in page when protected). */
    fun switchTargetUrl(profile: ServerProfile): String = profile.url.trim()

    fun reconnectUrl(currentUrl: String, selectedServerUrl: String, trustPolicy: WebTrustPolicy): String {
        return currentUrl.takeIf(trustPolicy::isConfiguredWebUiRoute) ?: selectedServerUrl
    }

    /** Restore only routes on the selected server; anything else falls back to its root. */
    fun startupUrl(
        selectedServerUrl: String,
        lastLoadedUrl: String?,
        notificationUrl: String?,
        trustPolicy: WebTrustPolicy
    ): String {
        return notificationUrl?.takeIf(trustPolicy::isConfiguredWebUiRoute)
            ?: lastLoadedUrl?.takeIf(trustPolicy::isConfiguredWebUiRoute)
            ?: selectedServerUrl
    }

    private fun comparableUrl(url: String): String = url.trim().trimEnd('/').lowercase()
}
