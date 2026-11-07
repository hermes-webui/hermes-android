package com.hermeswebui.android.data

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hermeswebui.android.core.security.UrlOrigins
import com.hermeswebui.android.server.SelectedServerNavigation
import com.hermeswebui.android.server.ServerProfileRules
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

@Suppress("DEPRECATION")
class SettingsRepository(context: Context) : SettingsStore {
    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getClientCertificateConfig(): ClientCertificateConfig {
        val uri = sharedPreferences.getString(KEY_CLIENT_CERT_URI, null)?.trim()
        val password = sharedPreferences.getString(KEY_CLIENT_CERT_PASSWORD, null)
        return ClientCertificateConfig(uri = uri, password = password)
    }

    fun saveClientCertificateConfig(uri: String?, password: String?) {
        sharedPreferences.edit {
            if (uri.isNullOrBlank()) {
                remove(KEY_CLIENT_CERT_URI)
            } else {
                putString(KEY_CLIENT_CERT_URI, uri.trim())
            }
            if (password.isNullOrBlank()) {
                remove(KEY_CLIENT_CERT_PASSWORD)
            } else {
                putString(KEY_CLIENT_CERT_PASSWORD, password)
            }
        }
    }

    fun clearClientCertificateConfig() {
        sharedPreferences.edit {
            remove(KEY_CLIENT_CERT_URI)
            remove(KEY_CLIENT_CERT_PASSWORD)
        }
    }

    init {
        // Migrate away from storing dashboard URLs in Android preferences.
        // Versions before 0.1.5 stored a dashboard URL in SharedPreferences and injected it
        // into WebUI via /api/dashboard/config. Now WebUI owns dashboard config completely.
        // Clear any stored dashboard URLs to prevent old behavior on app upgrade.
        runMigration()
    }

    private fun runMigration() {
        val lastMigrationVersion = sharedPreferences.getInt(KEY_LAST_MIGRATION_VERSION, 0)
        val currentMigrationVersion = 15 // Increment this when adding new migrations

        if (lastMigrationVersion < 1) {
            // Migration 1: Clear dashboard URLs from pre-0.1.5 versions
            sharedPreferences.edit {
                remove(KEY_DASHBOARD_URL)
            }
        }

        if (lastMigrationVersion < 2) {
            // Migration 2: Migrate single server URL to profiles list
            val oldUrl = sharedPreferences.getString(KEY_SERVER_URL, null)
            if (oldUrl != null && getProfiles().isEmpty()) {
                val profile = ServerProfile(name = "Default", url = oldUrl, isActive = true)
                saveProfiles(listOf(profile))
                setActiveProfile(profile.id)
            }
        }

        if (lastMigrationVersion < 3) {
            // Migration 3: Default background reconnect notification to opt-in (disabled)
            if (!sharedPreferences.contains(KEY_BACKGROUND_RECONNECT_ENABLED)) {
                sharedPreferences.edit { putBoolean(KEY_BACKGROUND_RECONNECT_ENABLED, false) }
            }
        }

        if (lastMigrationVersion < 4) {
            // Migration 4: Seed reconnect polling interval preference.
            if (!sharedPreferences.contains(KEY_RECONNECT_POLL_INTERVAL_SECONDS)) {
                sharedPreferences.edit {
                    putInt(KEY_RECONNECT_POLL_INTERVAL_SECONDS, DEFAULT_RECONNECT_POLL_INTERVAL_SECONDS)
                }
            }
        }

        if (lastMigrationVersion < 5) {
            // Migration 5: Debug logging capture defaults to disabled.
            if (!sharedPreferences.contains(KEY_DEBUG_LOGGING_ENABLED)) {
                sharedPreferences.edit { putBoolean(KEY_DEBUG_LOGGING_ENABLED, false) }
            }
        }

        if (lastMigrationVersion < 6) {
            // Migration 6: SSE transport defaults to disabled until server support is verified.
            if (!sharedPreferences.contains(KEY_SSE_TRANSPORT_ENABLED)) {
                sharedPreferences.edit { putBoolean(KEY_SSE_TRANSPORT_ENABLED, false) }
            }
        }

        if (lastMigrationVersion < 7) {
            // Migration 7: lock-screen activity preview defaults to redacted.
            if (!sharedPreferences.contains(KEY_BACKGROUND_ACTIVITY_FULL_TEXT_ENABLED)) {
                sharedPreferences.edit { putBoolean(KEY_BACKGROUND_ACTIVITY_FULL_TEXT_ENABLED, false) }
            }
        }

        if (lastMigrationVersion < 8) {
            // Migration 8: app update alerts default to enabled for managed release channels.
            if (!sharedPreferences.contains(KEY_APP_UPDATE_ALERTS_ENABLED)) {
                sharedPreferences.edit { putBoolean(KEY_APP_UPDATE_ALERTS_ENABLED, true) }
            }
        }

        if (lastMigrationVersion < 9) {
            // Migration 9: automatic app update checks default to enabled, with daily throttling.
            if (!sharedPreferences.contains(KEY_AUTOMATIC_APP_UPDATE_CHECKS_ENABLED)) {
                sharedPreferences.edit { putBoolean(KEY_AUTOMATIC_APP_UPDATE_CHECKS_ENABLED, true) }
            }
        }

        if (lastMigrationVersion < 10) {
            // Migration 10: VPN/Tailscale guard defaults to opt-in (disabled).
            if (!sharedPreferences.contains(KEY_REQUIRE_VPN_FOR_TAILSCALE_ENABLED)) {
                sharedPreferences.edit { putBoolean(KEY_REQUIRE_VPN_FOR_TAILSCALE_ENABLED, false) }
            }
        }

        if (lastMigrationVersion < 11) {
            // Migration 11: optional VPN launch package defaults to blank.
            if (!sharedPreferences.contains(KEY_VPN_LAUNCH_PACKAGE)) {
                sharedPreferences.edit { putString(KEY_VPN_LAUNCH_PACKAGE, "") }
            }
        }

        if (lastMigrationVersion < 12) {
            // Migration 12: default VPN launch package to Tailscale when unset or blank.
            val vpnPackage = sharedPreferences.getString(KEY_VPN_LAUNCH_PACKAGE, null)?.trim().orEmpty()
            if (vpnPackage.isBlank()) {
                sharedPreferences.edit { putString(KEY_VPN_LAUNCH_PACKAGE, DEFAULT_VPN_LAUNCH_PACKAGE) }
            }
        }

        if (lastMigrationVersion < 13) {
            // Migration 13: clear any legacy client-certificate settings so app-level
            // mTLS handling starts from a known default before the first explicit setup.
            sharedPreferences.edit {
                remove(KEY_CLIENT_CERT_URI)
                remove(KEY_CLIENT_CERT_PASSWORD)
            }
        }

        // Migration 14 is additive: missing serverType reads as UNSPECIFIED. Do not rewrite
        // legacy JSON (including an unreadable document) merely to add a cosmetic label.
        // Migration 15 is additive: absent detectedServerType remains unknown until a
        // configured frontend supplies its runtime marker. Preserve manual overrides.
        // Neither needs data changes, so only the version marker is written once at the end,
        // and not at all when the stored version is already current.
        if (lastMigrationVersion < currentMigrationVersion) {
            sharedPreferences.edit {
                putInt(KEY_LAST_MIGRATION_VERSION, currentMigrationVersion)
            }
        }
    }

    override fun getSettings(defaultUrl: String, defaultDashboardUrl: String): AppSettings {
        val storedServerUrl = sharedPreferences.getString(KEY_SERVER_URL, defaultUrl)?.trim().orEmpty()
        val profiles = getProfiles()
        if (profiles.none { it.isActive }) {
            SelectedServerNavigation.activeProfileIdFor(storedServerUrl, profiles)?.let(::setActiveProfile)
        }
        // Settings shows the active profile as Current; repair a server_url left on another host.
        val serverUrl = SelectedServerNavigation.selectedServerUrl(storedServerUrl, getProfiles())
        if (serverUrl != storedServerUrl) {
            val storedDashboardUrl = sharedPreferences.getString(KEY_DASHBOARD_URL, defaultDashboardUrl).orEmpty()
            saveAppUrls(serverUrl, storedDashboardUrl)
        }
        val rawDashboardUrl = sharedPreferences
            .getString(KEY_DASHBOARD_URL, defaultDashboardUrl)
            ?.trim()
            .orEmpty()
        val dashboardUrl = UrlOrigins.normalizeOriginUrl(rawDashboardUrl)
        val parsedHosts = setOf(serverUrl, dashboardUrl)
            .mapNotNull(UrlOrigins::hostFrom)
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

    override fun saveAppUrls(serverUrl: String, dashboardUrl: String) {
        val normalizedDashboardUrl = UrlOrigins.normalizeOriginUrl(dashboardUrl)
        val hosts = setOf(serverUrl, normalizedDashboardUrl)
            .mapNotNull(UrlOrigins::hostFrom)
            .toSet()
        sharedPreferences.edit {
            putString(KEY_SERVER_URL, serverUrl)
            putString(KEY_DASHBOARD_URL, normalizedDashboardUrl)
            putString(KEY_ALLOWED_HOSTS, hosts.joinToString(","))
            putBoolean(KEY_IS_CONFIGURED, true)
        }
        // Keep the Current profile in step with a manually saved server URL.
        val profiles = getProfiles()
        if (profiles.isNotEmpty()) {
            val activeId = SelectedServerNavigation.activeProfileIdFor(serverUrl, profiles)
            sharedPreferences.edit {
                if (activeId != null) putString(KEY_ACTIVE_PROFILE_ID, activeId) else remove(KEY_ACTIVE_PROFILE_ID)
            }
        }
    }

    fun hasRequestedNotificationPermission(): Boolean {
        return sharedPreferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
    }

    fun markNotificationPermissionRequested() {
        sharedPreferences.edit { putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true) }
    }

    fun isBackgroundReconnectEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_BACKGROUND_RECONNECT_ENABLED, false)
    }

    fun setBackgroundReconnectEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_BACKGROUND_RECONNECT_ENABLED, enabled) }
    }

    fun getReconnectPollIntervalSeconds(): Int {
        return sharedPreferences
            .getInt(KEY_RECONNECT_POLL_INTERVAL_SECONDS, DEFAULT_RECONNECT_POLL_INTERVAL_SECONDS)
            .coerceIn(MIN_RECONNECT_POLL_INTERVAL_SECONDS, MAX_RECONNECT_POLL_INTERVAL_SECONDS)
    }

    fun setReconnectPollIntervalSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(MIN_RECONNECT_POLL_INTERVAL_SECONDS, MAX_RECONNECT_POLL_INTERVAL_SECONDS)
        sharedPreferences.edit { putInt(KEY_RECONNECT_POLL_INTERVAL_SECONDS, clamped) }
    }

    fun isRequireVpnForTailscaleEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_REQUIRE_VPN_FOR_TAILSCALE_ENABLED, false)
    }

    fun setRequireVpnForTailscaleEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_REQUIRE_VPN_FOR_TAILSCALE_ENABLED, enabled) }
    }

    fun getVpnLaunchPackageName(): String {
        return sharedPreferences.getString(KEY_VPN_LAUNCH_PACKAGE, DEFAULT_VPN_LAUNCH_PACKAGE).orEmpty().trim()
    }

    fun setVpnLaunchPackageName(packageName: String) {
        sharedPreferences.edit { putString(KEY_VPN_LAUNCH_PACKAGE, packageName.trim()) }
    }

    fun isDebugLoggingEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_DEBUG_LOGGING_ENABLED, false)
    }

    fun setDebugLoggingEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_DEBUG_LOGGING_ENABLED, enabled) }
    }

    fun isSseTransportEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_SSE_TRANSPORT_ENABLED, false)
    }

    fun setSseTransportEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_SSE_TRANSPORT_ENABLED, enabled) }
    }

    fun isBlockScreenshotsEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_BLOCK_SCREENSHOTS_ENABLED, false)
    }

    fun setBlockScreenshotsEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_BLOCK_SCREENSHOTS_ENABLED, enabled) }
    }

    fun isBackgroundActivityFullTextEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_BACKGROUND_ACTIVITY_FULL_TEXT_ENABLED, false)
    }

    fun setBackgroundActivityFullTextEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_BACKGROUND_ACTIVITY_FULL_TEXT_ENABLED, enabled) }
    }

    fun isAppUpdateAlertsEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_APP_UPDATE_ALERTS_ENABLED, true)
    }

    fun setAppUpdateAlertsEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_APP_UPDATE_ALERTS_ENABLED, enabled) }
    }

    fun isAutomaticAppUpdateChecksEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_AUTOMATIC_APP_UPDATE_CHECKS_ENABLED, true)
    }

    fun setAutomaticAppUpdateChecksEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean(KEY_AUTOMATIC_APP_UPDATE_CHECKS_ENABLED, enabled) }
    }

    fun shouldCheckForAppUpdates(force: Boolean = false): Boolean {
        if (force) return true
        if (!isAppUpdateAlertsEnabled()) return false
        if (!isAutomaticAppUpdateChecksEnabled()) return false
        return true
    }

    fun shouldNotifyAppUpdate(version: String, force: Boolean = false): Boolean {
        if (force) return true
        return sharedPreferences.getString(KEY_APP_UPDATE_LAST_NOTIFIED_VERSION, null) != version
    }

    fun markAppUpdateNotified(version: String) {
        sharedPreferences.edit { putString(KEY_APP_UPDATE_LAST_NOTIFIED_VERSION, version) }
    }

    fun markPendingGitHubUpdateDownload(downloadId: Long) {
        sharedPreferences.edit { putLong(KEY_PENDING_GITHUB_UPDATE_DOWNLOAD_ID, downloadId) }
    }

    fun pendingGitHubUpdateDownloadId(): Long {
        return sharedPreferences.getLong(KEY_PENDING_GITHUB_UPDATE_DOWNLOAD_ID, -1L)
    }

    fun clearPendingGitHubUpdateDownload() {
        sharedPreferences.edit { remove(KEY_PENDING_GITHUB_UPDATE_DOWNLOAD_ID) }
    }

    fun markPendingGitHubUpdateCleanupDownload(downloadId: Long) {
        sharedPreferences.edit { putLong(KEY_PENDING_GITHUB_UPDATE_CLEANUP_DOWNLOAD_ID, downloadId) }
    }

    fun pendingGitHubUpdateCleanupDownloadId(): Long {
        return sharedPreferences.getLong(KEY_PENDING_GITHUB_UPDATE_CLEANUP_DOWNLOAD_ID, -1L)
    }

    fun clearPendingGitHubUpdateCleanupDownload() {
        sharedPreferences.edit { remove(KEY_PENDING_GITHUB_UPDATE_CLEANUP_DOWNLOAD_ID) }
    }

    /**
     * Sign-in-required prompt suppression keyed by normalized server URL.
     *
     * When the server-switch flow detects that a Hermes server requires sign-in
     * (HTTP 401/403 from /api/status) it normally asks the user to confirm
     * "Switch and sign in?". Users who consciously add an auth-protected server
     * can opt to silence that confirmation per-server via a "Don't ask again
     * for this server" checkbox; the silenced URL is stored here.
     */
    fun isAuthPromptSilencedForUrl(url: String): Boolean {
        val normalized = normalizeProfileUrl(url)
        if (normalized.isBlank()) return false
        val silenced = sharedPreferences.getStringSet(KEY_AUTH_PROMPT_SILENCED_URLS, emptySet()).orEmpty()
        return normalized in silenced
    }

    fun silenceAuthPromptForUrl(url: String) {
        val normalized = normalizeProfileUrl(url)
        if (normalized.isBlank()) return
        val current = sharedPreferences.getStringSet(KEY_AUTH_PROMPT_SILENCED_URLS, emptySet()).orEmpty()
        if (normalized in current) return
        sharedPreferences.edit {
            putStringSet(KEY_AUTH_PROMPT_SILENCED_URLS, current + normalized)
        }
    }

    fun clearSilencedAuthPromptForUrl(url: String) {
        val normalized = normalizeProfileUrl(url)
        if (normalized.isBlank()) return
        val current = sharedPreferences.getStringSet(KEY_AUTH_PROMPT_SILENCED_URLS, emptySet()).orEmpty()
        if (normalized !in current) return
        sharedPreferences.edit {
            putStringSet(KEY_AUTH_PROMPT_SILENCED_URLS, current - normalized)
        }
    }

    // Navigation callbacks persist the same final URL more than once per load. Each write is an
    // AES-SIV key + AES-GCM value encryption, so skip writes that cannot change the stored value.
    // Reads stay uncached because another repository instance (debug logging service) may read it.
    @Volatile private var lastSavedUrl: String? = null

    override fun saveLastLoadedUrl(url: String) {
        val known = lastSavedUrl ?: sharedPreferences.getString(KEY_LAST_URL, null)
        if (known == url) {
            lastSavedUrl = url
            return
        }
        sharedPreferences.edit { putString(KEY_LAST_URL, url) }
        lastSavedUrl = url
    }

    fun getLastLoadedUrl(): String? = sharedPreferences.getString(KEY_LAST_URL, null)

    // Server Profile CRUD operations
    fun addProfile(name: String, url: String, serverType: ServerType = ServerType.UNSPECIFIED): ServerProfile {
        val profiles = getProfiles().toMutableList()

        // Fresh installs can have a configured server URL but no profile rows yet.
        // Seed that current server as the first active profile so adding another server
        // does not make the original appear to disappear.
        if (profiles.isEmpty()) {
            val currentUrl = sharedPreferences.getString(KEY_SERVER_URL, null)?.trim().orEmpty()
            if (currentUrl.isNotBlank() && normalizeProfileUrl(currentUrl) != normalizeProfileUrl(url)) {
                val currentProfile = ServerProfile(
                    name = "Current server",
                    url = currentUrl,
                    isActive = true
                )
                profiles.add(currentProfile)
                setActiveProfile(currentProfile.id)
            }
        }

        val currentUrl = sharedPreferences.getString(KEY_SERVER_URL, null).orEmpty()
        val isCurrent = profiles.none { it.isActive } &&
            normalizeProfileUrl(currentUrl) == normalizeProfileUrl(url)
        val profile = ServerProfile(name = name, url = url, isActive = isCurrent, serverType = serverType)
        profiles.add(profile)
        saveProfiles(profiles)
        if (isCurrent) setActiveProfile(profile.id)
        return profile
    }

    fun deleteProfile(profileId: String) {
        val profiles = getProfiles().toMutableList()
        profiles.removeAll { it.id == profileId }
        saveProfiles(profiles)
        // If deleted profile was active, clear active state
        if (sharedPreferences.getString(KEY_ACTIVE_PROFILE_ID, null) == profileId) {
            sharedPreferences.edit { remove(KEY_ACTIVE_PROFILE_ID) }
        }
    }

    fun updateProfile(
        profileId: String,
        newName: String,
        newUrl: String,
        serverType: ServerType? = null
    ) {
        val profiles = getProfiles().map { profile ->
            if (profile.id == profileId) profile.copy(
                name = newName.trim().ifBlank { newUrl },
                url = newUrl.trim(),
                serverType = serverType ?: profile.serverType,
                detectedServerType = if (profile.url.trim() == newUrl.trim()) profile.detectedServerType
                    else ServerType.UNSPECIFIED
            )
            else profile
        }
        saveProfiles(profiles)
        // Editing the Current profile's URL must move the WebView's server with it.
        if (sharedPreferences.getString(KEY_ACTIVE_PROFILE_ID, null) == profileId) {
            val dashboardUrl = sharedPreferences.getString(KEY_DASHBOARD_URL, "").orEmpty()
            saveAppUrls(newUrl.trim(), dashboardUrl)
        }
    }

    fun getProfiles(): List<ServerProfile> {
        // KEY_ACTIVE_PROFILE_ID is the single source of truth for which profile is active.
        // setActiveProfile() only updates that key (not the per-row isActive boolean in the JSON),
        // so deriving isActive from the persisted boolean let a stale profile look active after a
        // switch. parseProfiles() computes isActive from the key; getSettings repairs a missing
        // or dangling selection when the configured URL matches an existing row.
        return parseProfiles(
            json = sharedPreferences.getString(KEY_SERVER_PROFILES, "[]"),
            activeId = sharedPreferences.getString(KEY_ACTIVE_PROFILE_ID, null)
        )
    }

    fun setActiveProfile(profileId: String) {
        sharedPreferences.edit { putString(KEY_ACTIVE_PROFILE_ID, profileId) }
    }

    fun getActiveProfile(): ServerProfile? {
        val activeId = sharedPreferences.getString(KEY_ACTIVE_PROFILE_ID, null) ?: return null
        return getProfiles().firstOrNull { it.id == activeId }
    }

    fun recordDetectedServerType(serverUrl: String, type: ServerType): Boolean {
        if (type == ServerType.UNSPECIFIED ||
            sharedPreferences.getString(KEY_SERVER_URL, null) != serverUrl
        ) return false
        var profiles = getProfiles()
        val storedProfileCount = try {
            JSONArray(sharedPreferences.getString(KEY_SERVER_PROFILES, "[]")).length()
        } catch (_: JSONException) {
            android.util.Log.e("HermesSettings", "Skipping automatic label: saved profiles are unreadable")
            return false
        }
        if (storedProfileCount != profiles.size) {
            android.util.Log.e("HermesSettings", "Skipping automatic label: saved profile rows are unreadable")
            return false
        }
        val profile = getActiveProfile()?.takeIf { it.url == serverUrl }
            ?: profiles.firstOrNull { it.url == serverUrl }
            ?: addProfile("Current server", serverUrl).also { profiles = getProfiles() }
        if (profile.detectedServerType == type) return false
        saveProfiles(profiles.map {
            if (it.id == profile.id) it.copy(detectedServerType = type) else it
        })
        return true
    }

    private fun saveProfiles(profiles: List<ServerProfile>) {
        val jsonArray = JSONArray()
        profiles.forEach { profile ->
            val obj = JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("url", profile.url)
                put("createdAt", profile.createdAt)
                put("isActive", profile.isActive)
                put("serverType", profile.serverType.storedValue)
                put("detectedServerType", profile.detectedServerType.storedValue)
            }
            jsonArray.put(obj)
        }
        sharedPreferences.edit { putString(KEY_SERVER_PROFILES, jsonArray.toString()) }
    }

    private fun normalizeProfileUrl(url: String): String {
        return ServerProfileRules.normalizeUrl(url)
    }

    companion object {
        private const val FILE_NAME = "hermes_secure_prefs"
        private const val KEY_SERVER_URL = "server_url"
        // Preserve the original encrypted preference key so existing installs keep their saved URL.
        private const val KEY_DASHBOARD_URL = "dashboard_terminal_url"
        private const val KEY_ALLOWED_HOSTS = "allowed_hosts"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_IS_CONFIGURED = "is_configured"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        private const val KEY_BACKGROUND_RECONNECT_ENABLED = "background_reconnect_enabled"
        private const val KEY_RECONNECT_POLL_INTERVAL_SECONDS = "reconnect_poll_interval_seconds"
        private const val KEY_REQUIRE_VPN_FOR_TAILSCALE_ENABLED = "require_vpn_for_tailscale_enabled"
        private const val KEY_VPN_LAUNCH_PACKAGE = "vpn_launch_package"
        private const val KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"
        private const val KEY_SSE_TRANSPORT_ENABLED = "sse_transport_enabled"
        private const val KEY_BLOCK_SCREENSHOTS_ENABLED = "block_screenshots_enabled"
        private const val KEY_BACKGROUND_ACTIVITY_FULL_TEXT_ENABLED = "background_activity_full_text_enabled"
        private const val KEY_APP_UPDATE_ALERTS_ENABLED = "app_update_alerts_enabled"
        private const val KEY_AUTOMATIC_APP_UPDATE_CHECKS_ENABLED = "automatic_app_update_checks_enabled"
        private const val KEY_APP_UPDATE_LAST_NOTIFIED_VERSION = "app_update_last_notified_version"
        private const val KEY_PENDING_GITHUB_UPDATE_DOWNLOAD_ID = "pending_github_update_download_id"
        private const val KEY_PENDING_GITHUB_UPDATE_CLEANUP_DOWNLOAD_ID = "pending_github_update_cleanup_download_id"
        private const val KEY_AUTH_PROMPT_SILENCED_URLS = "auth_prompt_silenced_urls"
        private const val KEY_CLIENT_CERT_URI = "client_cert_uri"
        private const val KEY_CLIENT_CERT_PASSWORD = "client_cert_password"
        private const val KEY_LAST_MIGRATION_VERSION = "last_migration_version"
        private const val DEFAULT_VPN_LAUNCH_PACKAGE = "com.tailscale.ipn"
        private const val DEFAULT_RECONNECT_POLL_INTERVAL_SECONDS = 1
        private const val MIN_RECONNECT_POLL_INTERVAL_SECONDS = 1
        private const val MAX_RECONNECT_POLL_INTERVAL_SECONDS = 10
        // Profile-related keys
        private const val KEY_SERVER_PROFILES = "server_profiles"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"

        /**
         * Parse the persisted profiles JSON, deriving `isActive` from the active-profile id (the
         * single source of truth) rather than the per-row persisted boolean. Pure so it is
         * unit-testable without EncryptedSharedPreferences.
         */
        internal fun parseProfiles(json: String?, activeId: String?): List<ServerProfile> {
            return try {
                val jsonArray = JSONArray(json ?: "[]")
                (0 until jsonArray.length()).map { index ->
                    val obj = jsonArray.getJSONObject(index)
                    val id = obj.getString("id")
                    ServerProfile(
                        id = id,
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        createdAt = obj.getLong("createdAt"),
                        isActive = id == activeId,
                        serverType = ServerType.fromStoredValue(obj.optString("serverType")),
                        detectedServerType = ServerType.fromStoredValue(obj.optString("detectedServerType"))
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
