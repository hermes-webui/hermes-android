package com.hermeswebui.android

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale

/**
 * Optional "Sign in with Chrome" support: an OAuth/OIDC provider page opens in a Chrome Auth Tab
 * (where passkeys and the browser's saved sign-ins work) and the verified callback is loaded back
 * into the app's WebView, where the flow's state cookies live.
 */
object BrowserSignIn {
    data class AuthTabCallback(val host: String, val path: String)

    /**
     * Auth Tab returns only https callbacks, matched by host and path, after Chrome verifies the
     * callback site's Digital Asset Links for this app. Anything else stays in the in-app flow.
     */
    fun authTabCallback(redirectUri: String): AuthTabCallback? {
        val uri = runCatching { URI(redirectUri) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return null
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        return AuthTabCallback(host, path)
    }

    /** The `/.well-known/assetlinks.json` statement Chrome needs before it returns a callback. */
    fun assetLinksJson(packageName: String, sha256Fingerprints: List<String>): String {
        val target = JSONObject()
            .put("namespace", "android_app")
            .put("package_name", packageName)
            .put("sha256_cert_fingerprints", JSONArray(sha256Fingerprints))
        val statement = JSONObject()
            .put("relation", JSONArray(listOf("delegate_permission/common.handle_all_urls")))
            .put("target", target)
        return JSONArray(listOf(statement)).toString(2).replace("\\/", "/")
    }

    fun sha256Fingerprint(digest: ByteArray): String =
        digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
}
