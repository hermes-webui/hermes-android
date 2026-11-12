package com.hermeswebui.android.server

import com.hermeswebui.android.core.security.WebTrustPolicy
import com.hermeswebui.android.core.security.UrlOrigins
import com.hermeswebui.android.data.HermesApiClient.ServerReadinessStatus
import com.hermeswebui.android.data.ServerProfile

enum class ServerSwitchAction {
    CONFIRM_SWITCH,
    CONFIRM_SIGN_IN_SWITCH,
    SWITCH_NOW,
    BLOCK
}

enum class ServerProfileEditAction {
    NONE,
    REFRESH,
    SWITCH
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
        return profiles.firstOrNull { ServerProfileRules.urlsMatch(it.url, serverUrl) }?.id
    }

    fun profileEditAction(previous: ServerProfile?, newUrl: String): ServerProfileEditAction {
        if (previous == null) return ServerProfileEditAction.NONE
        if (ServerProfileRules.urlsMatch(previous.url, newUrl) &&
            UrlOrigins.normalizedPath(previous.url) == UrlOrigins.normalizedPath(newUrl)
        ) {
            return ServerProfileEditAction.NONE
        }
        return if (UrlOrigins.hasSameOrigin(previous.url, newUrl)) {
            ServerProfileEditAction.REFRESH
        } else {
            ServerProfileEditAction.SWITCH
        }
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
        return currentUrl.takeIf {
            trustPolicy.isConfiguredWebUiRoute(it) && UrlOrigins.hasCompatibleUserInfo(it, selectedServerUrl)
        } ?: selectedServerUrl
    }

    fun routeRecoveryUrl(lastLoadedUrl: String?, selectedServerUrl: String): String {
        if (lastLoadedUrl.isNullOrBlank() ||
            !UrlOrigins.hasSameOrigin(lastLoadedUrl, selectedServerUrl) ||
            !UrlOrigins.hasCompatibleUserInfo(lastLoadedUrl, selectedServerUrl) ||
            UrlOrigins.normalizedPath(lastLoadedUrl).isBlank()
        ) return ""
        return lastLoadedUrl
    }

    fun previousHistoryOffset(history: List<String?>, currentIndex: Int, selectedServerUrl: String): Int? {
        if (currentIndex !in history.indices) return null
        for (index in currentIndex - 1 downTo 0) {
            val url = history[index] ?: continue
            if (!UrlOrigins.hasSameOrigin(url, selectedServerUrl) ||
                UrlOrigins.hasCompatibleUserInfo(url, selectedServerUrl)
            ) return index - currentIndex
        }
        return null
    }

    /** Restore only routes on the selected server; anything else falls back to its root. */
    fun startupUrl(
        selectedServerUrl: String,
        lastLoadedUrl: String?,
        notificationUrl: String?,
        trustPolicy: WebTrustPolicy
    ): String {
        fun canRestore(url: String): Boolean = trustPolicy.isConfiguredWebUiRoute(url) &&
            UrlOrigins.hasCompatibleUserInfo(url, selectedServerUrl)

        return notificationUrl?.takeIf(::canRestore)
            ?: lastLoadedUrl?.takeIf(::canRestore)
            ?: selectedServerUrl
    }

}
