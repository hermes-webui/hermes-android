package com.hermeswebui.android.data

import android.content.Context
import androidx.core.net.toUri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsRepository(context: Context) {
    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getSettings(defaultUrl: String, defaultDashboardUrl: String): AppSettings {
        val serverUrl = sharedPreferences.getString(KEY_SERVER_URL, defaultUrl)?.trim().orEmpty()
        val rawDashboardUrl = sharedPreferences
            .getString(KEY_DASHBOARD_URL, defaultDashboardUrl)
            ?.trim()
            .orEmpty()
        val dashboardUrl = normalizeDashboardOriginUrl(rawDashboardUrl)
        val parsedHosts = setOf(serverUrl, dashboardUrl)
            .mapNotNull { url -> runCatching { url.toUri().host.orEmpty().lowercase() }.getOrNull() }
            .filter { it.isNotBlank() }
            .toSet()
        val hostCsv = sharedPreferences.getString(KEY_ALLOWED_HOSTS, parsedHosts.joinToString(",")).orEmpty()
        val isConfigured = sharedPreferences.getBoolean(KEY_IS_CONFIGURED, false)
        val allowlist = hostCsv
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        return AppSettings(
            serverUrl = serverUrl,
            dashboardUrl = dashboardUrl,
            allowedHosts = allowlist,
            isConfigured = isConfigured
        )
    }

    fun saveAppUrls(serverUrl: String, dashboardUrl: String) {
        val normalizedDashboardUrl = normalizeDashboardOriginUrl(dashboardUrl)
        val hosts = setOf(serverUrl, normalizedDashboardUrl)
            .mapNotNull { url -> runCatching { url.toUri().host.orEmpty().lowercase() }.getOrNull() }
            .filter { it.isNotBlank() }
            .toSet()
        sharedPreferences.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_DASHBOARD_URL, normalizedDashboardUrl)
            .putString(KEY_ALLOWED_HOSTS, hosts.joinToString(","))
            .putBoolean(KEY_IS_CONFIGURED, true)
            .apply()
    }

    fun clearWebSession() {
        sharedPreferences.edit().remove(KEY_LAST_URL).apply()
    }

    fun saveLastLoadedUrl(url: String) {
        sharedPreferences.edit().putString(KEY_LAST_URL, url).apply()
    }

    fun getLastLoadedUrl(): String? = sharedPreferences.getString(KEY_LAST_URL, null)

    private fun normalizeDashboardOriginUrl(url: String): String {
        val trimmed = url.trim()
        val parsed = runCatching { trimmed.toUri() }.getOrNull() ?: return trimmed
        if (parsed.scheme.isNullOrBlank() || parsed.host.isNullOrBlank()) return trimmed

        return parsed.buildUpon()
            .path("")
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
    }

    companion object {
        private const val FILE_NAME = "hermes_secure_prefs"
        private const val KEY_SERVER_URL = "server_url"
        // Preserve the original encrypted preference key so existing installs keep their saved URL.
        private const val KEY_DASHBOARD_URL = "dashboard_terminal_url"
        private const val KEY_ALLOWED_HOSTS = "allowed_hosts"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_IS_CONFIGURED = "is_configured"
    }
}
