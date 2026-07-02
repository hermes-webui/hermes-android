package com.hermeswebui.android.update

import com.hermeswebui.android.core.security.UrlOrigins
import java.util.Locale

/**
 * Policy for app-update APK downloads, kept as a pure object so it is unit-testable and reused by
 * the exported update path in [com.hermeswebui.android.MainActivity].
 *
 * GitHub Release APK assets are served only from `github.com` (the release `browser_download_url`)
 * and its `*.githubusercontent.com` asset CDN. Confining the download host to that set keeps the
 * exported `DOWNLOAD_APP_UPDATE` action from being coerced (by any installed app sending an
 * explicit intent) into fetching an attacker-hosted APK — `https` + `.apk` alone was not enough.
 */
object AppUpdateDownloadPolicy {
    fun isTrustedApkDownloadHost(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return false
        return normalized == "github.com" || normalized.endsWith(".githubusercontent.com")
    }

    /** True only for an `https` URL that ends in `.apk` and is hosted on a trusted GitHub host. */
    fun isTrustedApkDownloadUrl(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (!raw.startsWith("https://", ignoreCase = true)) return false
        if (!raw.endsWith(".apk", ignoreCase = true)) return false
        return isTrustedApkDownloadHost(UrlOrigins.hostFrom(raw))
    }
}
