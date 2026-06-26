package com.hermeswebui.android

import com.hermeswebui.android.core.security.UrlOrigins
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class OAuthPopupFlow(
    val redirectUri: String,
    private val redirectScheme: String,
    private val redirectHost: String?,
    private val redirectPort: Int,
    private val redirectPath: String,
    private val redirectOrigin: String?
) {
    fun isVerifiedCallbackUrl(url: String): Boolean {
        val target = url.toUriOrNull() ?: return false
        if (!matchesEndpoint(target, url)) return false

        val query = parseParameters(target.rawQuery)
        val fragment = parseParameters(target.rawFragment)
        return query.containsKey("code") ||
            query.containsKey("error") ||
            fragment.containsKey("code") ||
            fragment.containsKey("error")
    }

    fun redirectsToOrigin(baseUrl: String): Boolean {
        return UrlOrigins.hasSameOrigin(redirectUri, baseUrl)
    }

    private fun matchesEndpoint(target: URI, url: String): Boolean {
        if (!target.scheme.equals(redirectScheme, ignoreCase = true)) return false

        if (redirectOrigin != null) {
            if (!UrlOrigins.hasSameOrigin(url, redirectOrigin)) return false
        } else {
            val targetHost = target.host?.lowercase(Locale.US)
            if (targetHost != redirectHost) return false
            if (target.effectivePort() != redirectPort) return false
        }

        return normalizePath(target.path) == redirectPath
    }

    companion object {
        fun parseAuthorizationStart(url: String): OAuthPopupFlow? {
            val uri = url.toUriOrNull() ?: return null
            val params = parseParameters(uri.rawQuery)
            val redirectUri = params["redirect_uri"]?.takeIf { it.isNotBlank() } ?: return null
            val responseType = params["response_type"]?.lowercase(Locale.US) ?: return null
            val clientId = params["client_id"]?.takeIf { it.isNotBlank() } ?: return null
            if (!responseType.split(' ', '+').contains("code")) return null
            if (clientId.isBlank()) return null

            val flowMarkersPresent = params.containsKey("state") ||
                params.containsKey("code_challenge") ||
                uri.path.orEmpty().contains("oauth", ignoreCase = true) ||
                uri.path.orEmpty().contains("authorize", ignoreCase = true)
            if (!flowMarkersPresent) return null

            val callback = redirectUri.toUriOrNull() ?: return null
            val callbackScheme = callback.scheme?.lowercase(Locale.US) ?: return null
            val callbackOrigin = when (callbackScheme) {
                "http", "https" -> UrlOrigins.normalizeOriginUrl(redirectUri)
                else -> null
            }

            return OAuthPopupFlow(
                redirectUri = redirectUri,
                redirectScheme = callbackScheme,
                redirectHost = callback.host?.lowercase(Locale.US),
                redirectPort = callback.effectivePort(),
                redirectPath = normalizePath(callback.path),
                redirectOrigin = callbackOrigin
            )
        }

        private fun parseParameters(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split('&')
                .mapNotNull { entry ->
                    if (entry.isBlank()) return@mapNotNull null
                    val separator = entry.indexOf('=')
                    val key = if (separator >= 0) entry.substring(0, separator) else entry
                    val value = if (separator >= 0) entry.substring(separator + 1) else ""
                    val decodedKey = key.decodeUrlComponent().trim().lowercase(Locale.US)
                    if (decodedKey.isBlank()) {
                        null
                    } else {
                        decodedKey to value.decodeUrlComponent()
                    }
                }
                .toMap()
        }

        private fun String.decodeUrlComponent(): String {
            return URLDecoder.decode(this, StandardCharsets.UTF_8)
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

        private fun normalizePath(path: String?): String {
            return path.orEmpty().trimEnd('/').ifBlank { "/" }
        }
    }
}
