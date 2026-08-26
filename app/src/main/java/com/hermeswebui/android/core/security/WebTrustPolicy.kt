package com.hermeswebui.android.core.security

import java.net.URI
import java.util.Locale

class WebTrustPolicy(
    private val urlPolicy: UrlPolicy,
    private val configuredWebUiUrl: String,
    private val configuredDashboardUrl: String
) {
    fun isConfiguredWebUiRoute(url: String?): Boolean {
        return UrlOrigins.hasSameOrigin(url, configuredWebUiUrl) &&
            !isConfiguredDashboardRoute(url)
    }

    fun isConfiguredDashboardRoute(url: String?): Boolean {
        if (url.isNullOrBlank() || configuredDashboardUrl.isBlank()) return false
        if (!UrlOrigins.hasSameOrigin(url, configuredDashboardUrl)) return false

        val targetPath = UrlOrigins.normalizedPath(url)
        val dashboardPath = UrlOrigins.normalizedPath(configuredDashboardUrl)
        return dashboardPath.isBlank() ||
            targetPath == dashboardPath ||
            targetPath.startsWith("$dashboardPath/")
    }

    fun isTrustedNotificationTarget(url: String?): Boolean {
        return !url.isNullOrBlank() && urlPolicy.isAllowed(url) && isConfiguredWebUiRoute(url)
    }

    fun isTrustedNotificationBridgeSource(
        sourceOrigin: String,
        isMainFrame: Boolean,
        currentMainFrameUrl: String?
    ): Boolean {
        if (!isMainFrame) return false
        val normalizedOrigin = normalizeWebOrigin(sourceOrigin) ?: sourceOrigin
        return isConfiguredWebUiRoute(normalizedOrigin) &&
            isConfiguredWebUiRoute(currentMainFrameUrl)
    }

    fun isTrustedPermissionOrigin(origin: String?, currentMainFrameUrl: String?): Boolean {
        if (origin == null) return isConfiguredWebUiRoute(currentMainFrameUrl)
        if (urlPolicy.isAllowed(origin)) return true

        val normalizedOrigin = normalizeWebOrigin(origin)
        if (normalizedOrigin != null && urlPolicy.isAllowed(normalizedOrigin)) return true

        return normalizedOrigin == null && isConfiguredWebUiRoute(currentMainFrameUrl)
    }

    private fun normalizeWebOrigin(origin: String): String? {
        val parsed = runCatching { URI(origin) }.getOrNull() ?: return null
        val scheme = parsed.scheme
            ?.lowercase(Locale.US)
            ?.takeIf { it == "http" || it == "https" }
            ?: return null
        val host = parsed.host
            ?.trim()
            ?.trimEnd('.')
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            URI(scheme, null, host, parsed.port, null, null, null).toString().trimEnd('/')
        }.getOrNull()
    }
}