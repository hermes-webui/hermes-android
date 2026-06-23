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
        val currentMigrationVersion = 2 // Increment this when adding new migrations

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

    override fun saveLastLoadedUrl(url: String) {
        sharedPreferences.edit { putString(KEY_LAST_URL, url) }
    }

    fun getLastLoadedUrl(): String? = sharedPreferences.getString(KEY_LAST_URL, null)

    // Server Profile CRUD operations
    fun addProfile(name: String, url: String): ServerProfile {
        val profile = ServerProfile(name = name, url = url, isActive = false)
        val profiles = getProfiles().toMutableList()
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
        val json = sharedPreferences.getString(KEY_SERVER_PROFILES, "[]") ?: "[]"
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { index ->
                val obj = jsonArray.getJSONObject(index)
                ServerProfile(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    url = obj.getString("url"),
                    createdAt = obj.getLong("createdAt"),
                    isActive = obj.getBoolean("isActive")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
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

    companion object {
        private const val FILE_NAME = "hermes_secure_prefs"
        private const val KEY_SERVER_URL = "server_url"
        // Preserve the original encrypted preference key so existing installs keep their saved URL.
        private const val KEY_DASHBOARD_URL = "dashboard_terminal_url"
        private const val KEY_ALLOWED_HOSTS = "allowed_hosts"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_IS_CONFIGURED = "is_configured"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        private const val KEY_LAST_MIGRATION_VERSION = "last_migration_version"
        // Profile-related keys
        private const val KEY_SERVER_PROFILES = "server_profiles"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    }
}
