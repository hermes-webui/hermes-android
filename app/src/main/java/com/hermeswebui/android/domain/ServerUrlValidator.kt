package com.hermeswebui.android.domain

import java.net.URI
import java.util.Locale

class ServerUrlValidator {
    fun isValid(url: String): Boolean {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return false
        val host = parsed.host ?: return false
        val scheme = parsed.scheme?.lowercase(Locale.US)
        val isSupportedScheme = scheme == "http" || scheme == "https"
        return isSupportedScheme && host.isNotBlank()
    }
}
