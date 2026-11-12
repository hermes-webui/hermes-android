package com.hermeswebui.android.data

import java.util.Locale

/** An opt-in saved HTTP Basic sign-in for the configured server host. */
data class HttpAuthCredential(val username: String, val password: String) {
    override fun toString(): String = "HttpAuthCredential(username=<redacted>, password=<redacted>)"

    companion object {
        /** Scope a saved sign-in to the exact challenged host and realm. */
        fun scopeKey(host: String?, realm: String?): String? {
            val normalizedHost = host
                ?.trim()
                ?.trimEnd('.')
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return "$normalizedHost\n${realm.orEmpty()}"
        }
    }
}
