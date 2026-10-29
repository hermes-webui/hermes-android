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

    /** How a safe status-check redirect relates to the server that issued it. */
    sealed interface StatusRedirect {
        /** Same origin (for example `/login`): ordinary sign-in on an already-trusted origin. */
        data object SameOrigin : StatusRedirect
        /** Same host moving from http to https: the configured URL itself is stale. */
        data class HttpsUpgrade(val origin: String) : StatusRedirect
        /** A different origin: the only case that may be a reverse-proxy sign-in service. */
        data class CrossOrigin(val origin: String) : StatusRedirect
    }

    companion object {
        const val TIMEOUT_MS = 5 * 60 * 1000L

        fun classifyRedirect(sourceUrl: String, status: Int, location: String?): StatusRedirect? {
            val origin = safeRedirectOrigin(sourceUrl, status, location) ?: return null
            return when {
                origin == UrlOrigins.pageOrigin(sourceUrl) -> StatusRedirect.SameOrigin
                UrlOrigins.hasSameOrUpgradedWebOrigin(origin, sourceUrl) -> StatusRedirect.HttpsUpgrade(origin)
                else -> StatusRedirect.CrossOrigin(origin)
            }
        }

        /**
         * Login origin a main-frame redirect may request consent for. The hop that issued the
         * redirect must be exactly the configured server origin (scheme, host and port); the
         * host-only allowlist must not let another port, subdomain, or the dashboard speak for it.
         */
        fun mainFrameRedirectConsentOrigin(serverUrl: String, redirectingHopUrl: String?, target: String): String? {
            val hop = redirectingHopUrl ?: return null
            val serverOrigin = UrlOrigins.pageOrigin(serverUrl) ?: return null
            if (UrlOrigins.pageOrigin(hop) != serverOrigin) return null
            return safeRedirectOrigin(hop, 302, target)
        }

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
