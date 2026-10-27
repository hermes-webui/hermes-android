package com.hermeswebui.android.core.security

import java.net.URI

/**
 * Temporary, exact-origin permission for a user-approved reverse-proxy sign-in.
 * This does not add the login origin to Hermes' host, bridge or permission allowlists.
 */
class ProxyAuthNavigation(private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private var serverOrigin: String? = null
    private var loginOrigin: String? = null
    private var expiresAt = 0L

    fun begin(serverUrl: String, redirectOrigin: String) {
        require(safeRedirectOrigin(serverUrl, 302, redirectOrigin) != null)
        serverOrigin = UrlOrigins.pageOrigin(serverUrl)
        loginOrigin = UrlOrigins.pageOrigin(redirectOrigin)
        expiresAt = now() + TIMEOUT_MS
    }

    fun isActive(serverUrl: String): Boolean =
        isPending(serverUrl) && now() < expiresAt

    fun isPending(serverUrl: String): Boolean =
        serverOrigin != null && serverOrigin == UrlOrigins.pageOrigin(serverUrl)

    fun allows(serverUrl: String, target: String): Boolean {
        if (!isActive(serverUrl)) return false
        val uri = runCatching { URI(target) }.getOrNull() ?: return false
        if (uri.rawUserInfo != null) return false
        val origin = UrlOrigins.pageOrigin(target) ?: return false
        return origin == serverOrigin || origin == loginOrigin
    }

    fun completeIfHermesPage(serverUrl: String, url: String, isHermesPage: Boolean) {
        if (isPending(serverUrl) && UrlOrigins.hasSameOrigin(url, serverUrl) && isHermesPage) {
            clear()
        }
    }

    fun clear() {
        serverOrigin = null
        loginOrigin = null
        expiresAt = 0L
    }

    companion object {
        const val TIMEOUT_MS = 5 * 60 * 1000L

        fun safeRedirectOrigin(sourceUrl: String, status: Int, location: String?): String? {
            if (status !in setOf(301, 302, 303, 307, 308) || location.isNullOrBlank()) return null
            if (UrlOrigins.pageOrigin(sourceUrl) == null) return null
            val source = runCatching { URI(sourceUrl) }.getOrNull() ?: return null
            val target = runCatching { source.resolve(location) }.getOrNull() ?: return null
            if (!source.scheme.equals("http", true) && !source.scheme.equals("https", true)) return null
            if (source.rawUserInfo != null || target.rawUserInfo != null) return null
            if (source.scheme.equals("https", true) && !target.scheme.equals("https", true)) return null
            return UrlOrigins.pageOrigin(target.toString())
        }
    }
}
