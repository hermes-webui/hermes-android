package com.hermeswebui.android.core.security

import java.net.URI
import java.util.Locale

enum class NavigationDecision {
    ALLOW_IN_WEBVIEW,
    OPEN_IN_EXTERNAL_BROWSER,
    BLOCK
}

class UrlPolicy(private val allowedHosts: Set<String>) {
    private val normalizedAllowedHosts = allowedHosts
        .asSequence()
        .map { it.trim().lowercase(Locale.US) }
        .filter { it.isNotEmpty() }
        .toSet()

    fun isAllowed(url: String): Boolean {
        val uri = url.toUriOrNull() ?: return false
        if (!uri.isHttpOrHttpsScheme()) return false
        return uri.hasAllowedHost()
    }

    fun navigationDecision(url: String): NavigationDecision {
        val uri = url.toUriOrNull() ?: return NavigationDecision.BLOCK
        if (!uri.isHttpOrHttpsScheme()) return NavigationDecision.BLOCK
        return if (uri.hasAllowedHost()) {
            NavigationDecision.ALLOW_IN_WEBVIEW
        } else {
            NavigationDecision.OPEN_IN_EXTERNAL_BROWSER
        }
    }

    private fun String.toUriOrNull(): URI? = runCatching { URI(this) }.getOrNull()

    private fun URI.hasAllowedHost(): Boolean {
        val host = normalizedHost() ?: return false
        return host in normalizedAllowedHosts || normalizedAllowedHosts.any { host.endsWith(".$it") }
    }

    private fun URI.isHttpOrHttpsScheme(): Boolean {
        return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
    }

    private fun URI.normalizedHost(): String? = host?.lowercase(Locale.US)
}

object UrlOrigins {
    fun hostFrom(url: String): String? {
        return url.toUriOrNull()?.normalizedHost()?.takeIf { it.isNotBlank() }
    }

    fun hasSameOrigin(url: String?, baseUrl: String): Boolean {
        if (url.isNullOrBlank() || baseUrl.isBlank()) return false
        val target = url.toUriOrNull() ?: return false
        val base = baseUrl.toUriOrNull() ?: return false
        val targetScheme = target.scheme?.lowercase(Locale.US) ?: return false
        val baseScheme = base.scheme?.lowercase(Locale.US) ?: return false
        val targetHost = target.normalizedHost() ?: return false
        val baseHost = base.normalizedHost() ?: return false
        return targetScheme == baseScheme &&
            targetHost == baseHost &&
            target.effectivePort() == base.effectivePort()
    }

    fun documentStartOriginRule(url: String): String? {
        val uri = url.toUriOrNull() ?: return null
        val scheme = uri.scheme
            ?.lowercase(Locale.US)
            ?.takeIf { it == "http" || it == "https" }
            ?: return null
        val host = uri.normalizedHost()?.takeIf { it.isNotBlank() } ?: return null
        val hostRule = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        val portRule = if (uri.port != -1) ":${uri.port}" else ""
        return "$scheme://$hostRule$portRule"
    }

    fun normalizeOriginUrl(url: String): String {
        val trimmed = url.trim()
        val parsed = trimmed.toUriOrNull() ?: return trimmed
        val scheme = parsed.scheme ?: return trimmed
        val host = parsed.host ?: return trimmed
        return runCatching {
            URI(scheme, null, host, parsed.port, null, null, null)
                .toString()
                .trimEnd('/')
        }.getOrDefault(trimmed)
    }

    fun normalizedPath(url: String): String {
        return url.toUriOrNull()?.path.orEmpty().trimEnd('/')
    }

    private fun String.toUriOrNull(): URI? = runCatching { URI(this) }.getOrNull()

    private fun URI.effectivePort(): Int {
        if (port != -1) return port
        return when (scheme?.lowercase(Locale.US)) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    private fun URI.normalizedHost(): String? = host?.lowercase(Locale.US)
}
