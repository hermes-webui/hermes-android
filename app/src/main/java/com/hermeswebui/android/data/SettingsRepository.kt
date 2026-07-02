package com.hermeswebui.android.data

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hermeswebui.android.core.security.UrlOrigins
import org.json.JSONArray
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

    init {
        // Migrate away from storing dashboard URLs in Android preferences.
        // Versions before 0.1.5 stored a dashboard URL in SharedPreferences and injected it
        // into WebUI via /api/dashboard/config. Now WebUI owns dashboard config completely.
        // Clear any stored dashboard URLs to prevent old behavior on app upgrade.
        runMigration()
    }

    private fun runMigration() {
        val lastMigrationVersion = sharedPreferences.getInt(KEY_LAST_MIGRATION_VERSION, 0)
        val currentMigrationVersion = 9 // Increment this when adding new migrations

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

        sharedPreferences.edit {
            putInt(KEY_LAST_MIGRATION_VERSION, currentMigrationVersion)
        }
    }

    override fun getSettings(defaultUrl: String, defaultDashboardUrl: String): AppSettings {
        val serverUrl = sharedPreferences.getString(KEY_SERVER_URL, defaultUrl)?.trim().orEmpty()
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
    }

    override fun clearWebSession() {
        sharedPreferences.edit { remove(KEY_LAST_URL) }
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

    fun shouldCheckForAppUpdates(nowMs: Long, force: Boolean = false): Boolean {
        if (force) return true
        if (!isAppUpdateAlertsEnabled()) return false
        if (!isAutomaticAppUpdateChecksEnabled()) return false
        val lastCheckMs = sharedPreferences.getLong(KEY_APP_UPDATE_LAST_CHECK_MS, 0L)
        return nowMs - lastCheckMs >= APP_UPDATE_CHECK_INTERVAL_MS
    }

    fun markAppUpdateChecked(nowMs: Long) {
        sharedPreferences.edit { putLong(KEY_APP_UPDATE_LAST_CHECK_MS, nowMs) }
    }

    fun shouldNotifyAppUpdate(version: String, force: Boolean = false): Boolean {
        if (force) return true
        return sharedPreferences.getString(KEY_APP_UPDATE_LAST_NOTIFIED_VERSION, null) != version
    }

    fun markAppUpdateNotified(version: String) {
        sharedPreferences.edit { putString(KEY_APP_UPDATE_LAST_NOTIFIED_VERSION, version) }
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

    override fun saveLastLoadedUrl(url: String) {
        sharedPreferences.edit { putString(KEY_LAST_URL, url) }
    }

    fun getLastLoadedUrl(): String? = sharedPreferences.getString(KEY_LAST_URL, null)

    // Server Profile CRUD operations
    fun addProfile(name: String, url: String): ServerProfile {
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

        val profile = ServerProfile(name = name, url = url, isActive = false)
        profiles.add(profile)
        saveProfiles(profiles)
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

    fun renameProfile(profileId: String, newName: String) {
        val profiles = getProfiles().map { profile ->
            if (profile.id == profileId) profile.copy(name = newName.trim().ifBlank { profile.url })
            else profile
        }
        saveProfiles(profiles)
    }

    fun updateProfile(profileId: String, newName: String, newUrl: String) {
        val profiles = getProfiles().map { profile ->
            if (profile.id == profileId) profile.copy(
                name = newName.trim().ifBlank { newUrl },
                url = newUrl.trim()
            )
            else profile
        }
        saveProfiles(profiles)
    }

    fun getProfiles(): List<ServerProfile> {
        // KEY_ACTIVE_PROFILE_ID is the single source of truth for which profile is active.
        // setActiveProfile() only updates that key (not the per-row isActive boolean in the JSON),
        // so deriving isActive from the persisted boolean let a stale profile look active after a
        // switch. parseProfiles() computes isActive from the key. (Both creation paths already set
        // the key alongside the profile, so nothing that is active can be missing from it.)
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

    private fun saveProfiles(profiles: List<ServerProfile>) {
        val jsonArray = JSONArray()
        profiles.forEach { profile ->
            val obj = JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("url", profile.url)
                put("createdAt", profile.createdAt)
                put("isActive", profile.isActive)
            }
            jsonArray.put(obj)
        }
        sharedPreferences.edit { putString(KEY_SERVER_PROFILES, jsonArray.toString()) }
    }

    private fun normalizeProfileUrl(url: String): String {
        return url.trim().trimEnd('/').lowercase()
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
        private const val KEY_DEBUG_LOGGING_ENABLED = "debug_logging_enabled"
        private const val KEY_SSE_TRANSPORT_ENABLED = "sse_transport_enabled"
        private const val KEY_BLOCK_SCREENSHOTS_ENABLED = "block_screenshots_enabled"
        private const val KEY_BACKGROUND_ACTIVITY_FULL_TEXT_ENABLED = "background_activity_full_text_enabled"
        private const val KEY_APP_UPDATE_ALERTS_ENABLED = "app_update_alerts_enabled"
        private const val KEY_AUTOMATIC_APP_UPDATE_CHECKS_ENABLED = "automatic_app_update_checks_enabled"
        private const val KEY_APP_UPDATE_LAST_CHECK_MS = "app_update_last_check_ms"
        private const val KEY_APP_UPDATE_LAST_NOTIFIED_VERSION = "app_update_last_notified_version"
        private const val KEY_AUTH_PROMPT_SILENCED_URLS = "auth_prompt_silenced_urls"
        private const val KEY_LAST_MIGRATION_VERSION = "last_migration_version"
        private const val DEFAULT_RECONNECT_POLL_INTERVAL_SECONDS = 1
        private const val MIN_RECONNECT_POLL_INTERVAL_SECONDS = 1
        private const val MAX_RECONNECT_POLL_INTERVAL_SECONDS = 10
        private const val APP_UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
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
                        isActive = id == activeId
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
