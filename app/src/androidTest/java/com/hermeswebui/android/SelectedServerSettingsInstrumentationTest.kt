package com.hermeswebui.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.core.security.WebTrustPolicy
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.data.ServerType
import com.hermeswebui.android.server.SelectedServerNavigation
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class SelectedServerSettingsInstrumentationTest {
    private lateinit var context: IsolatedPreferencesContext
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = IsolatedPreferencesContext(InstrumentationRegistry.getInstrumentation().targetContext)
        repository = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        context.deleteTestPreferences()
    }

    @Test
    fun addingConfiguredServerThenEditingCurrentPersistsSelectionAndNavigationTrust() {
        repository.saveAppUrls(SERVER_A, DASHBOARD)
        val profile = repository.addProfile("Current", "HTTPS://A.EXAMPLE.TEST/hermes/")
        assertThat(profile.isActive).isTrue()
        assertThat(repository.getActiveProfile()?.id).isEqualTo(profile.id)

        repository.saveLastLoadedUrl("$SERVER_A/session/old")
        repository.updateProfile(profile.id, "Moved server", SERVER_B)
        val reopened = SettingsRepository(context)
        val settings = reopened.getSettings(DEFAULT_SERVER, "")
        assertThat(settings.serverUrl).isEqualTo(SERVER_B)
        assertThat(settings.isConfigured).isTrue()
        assertThat(settings.allowedHosts).containsExactly("b.example.test", "dashboard.example.test")
        assertThat(reopened.getActiveProfile()?.url).isEqualTo(SERVER_B)
        assertThat(encryptedPreferences().getString("server_url", null)).isEqualTo(SERVER_B)

        val trust = WebTrustPolicy(UrlPolicy(settings.allowedHosts), settings.serverUrl, settings.dashboardUrl)
        assertThat(trust.isTrustedNotificationTarget("$SERVER_B/session/new")).isTrue()
        assertThat(trust.isTrustedNotificationTarget("$SERVER_A/session/old")).isFalse()
        assertThat(SelectedServerNavigation.startupUrl(
            settings.serverUrl, reopened.getLastLoadedUrl(), "$SERVER_A/session/notification", trust
        )).isEqualTo(SERVER_B)
        assertThat(SelectedServerNavigation.startupUrl(
            settings.serverUrl, "$SERVER_B/session/restored", null, trust
        )).isEqualTo("$SERVER_B/session/restored")
        assertThat(SelectedServerNavigation.reconnectUrl(
            "$SERVER_A/session/old", settings.serverUrl, trust
        )).isEqualTo(SERVER_B)
        assertThat(SelectedServerNavigation.reconnectUrl(
            "$SERVER_B/session/current", settings.serverUrl, trust
        )).isEqualTo("$SERVER_B/session/current")
    }

    @Test
    fun serverTypeRoundTripsWithoutChangingSelectionAndSurvivesLegacyEdits() {
        repository.saveAppUrls(SERVER_A, DASHBOARD)
        val agent = repository.addProfile("Agent", SERVER_A, ServerType.AGENT)
        val webui = repository.addProfile("WebUI", SERVER_B, ServerType.WEBUI)
        var reopened = SettingsRepository(context)
        assertThat(reopened.getActiveProfile()?.id).isEqualTo(agent.id)
        assertThat(reopened.getProfiles().map { it.serverType })
            .containsExactly(ServerType.AGENT, ServerType.WEBUI)
        reopened.updateProfile(agent.id, "Renamed", SERVER_A)
        reopened.updateProfile(webui.id, "Other", SERVER_B, ServerType.UNSPECIFIED)
        reopened = SettingsRepository(context)
        assertThat(reopened.getActiveProfile()?.serverType).isEqualTo(ServerType.AGENT)
        assertThat(reopened.getProfiles().first { it.id == webui.id }.serverType).isEqualTo(ServerType.UNSPECIFIED)
        assertThat(reopened.getSettings(DEFAULT_SERVER, "").serverUrl).isEqualTo(SERVER_A)
    }

    @Test
    fun additiveMigrationsPreserveLegacyProfilesAndSelectionWithoutGuessingType() {
        seedLegacyProfiles("profile-b")
        val preferences = encryptedPreferences()
        val before = preferences.getString("server_profiles", null)
        check(preferences.edit().putInt("last_migration_version", 13).commit())
        val reopened = SettingsRepository(context)
        assertThat(reopened.getProfiles().all { it.serverType == ServerType.UNSPECIFIED }).isTrue()
        assertThat(reopened.getActiveProfile()?.id).isEqualTo("profile-b")
        assertThat(preferences.getString("server_profiles", null)).isEqualTo(before)
        assertThat(preferences.getInt("last_migration_version", 0)).isEqualTo(16)
    }

    @Test
    fun automaticDetectionNeverOverwritesUnreadableProfiles() {
        repository.saveAppUrls(SERVER_A, DASHBOARD)
        val preferences = encryptedPreferences()
        for (invalid in listOf("not json", """[{"id":"broken"}]""")) {
            check(preferences.edit().putString("server_profiles", invalid).commit())
            assertThat(repository.recordDetectedServerType(SERVER_A, ServerType.AGENT)).isFalse()
            assertThat(preferences.getString("server_profiles", null)).isEqualTo(invalid)
        }
    }

    @Test
    fun automaticTypePersistsButNeverOverridesManualLabelsOrFollowsAnEditedUrl() {
        repository.saveAppUrls(SERVER_A, DASHBOARD)
        assertThat(repository.recordDetectedServerType(SERVER_A, ServerType.AGENT)).isTrue()
        var profile = SettingsRepository(context).getActiveProfile()!!
        assertThat(profile.serverType).isEqualTo(ServerType.UNSPECIFIED)
        assertThat(profile.effectiveServerType).isEqualTo(ServerType.AGENT)
        assertThat(repository.recordDetectedServerType(SERVER_A, ServerType.AGENT)).isFalse()
        repository.updateProfile(profile.id, "Manual", SERVER_A, ServerType.WEBUI)
        repository.recordDetectedServerType(SERVER_A, ServerType.AGENT)
        profile = SettingsRepository(context).getActiveProfile()!!
        assertThat(profile.effectiveServerType).isEqualTo(ServerType.WEBUI)
        repository.updateProfile(profile.id, "Automatic", SERVER_A, ServerType.UNSPECIFIED)
        assertThat(repository.getActiveProfile()?.effectiveServerType).isEqualTo(ServerType.AGENT)
        repository.updateProfile(profile.id, "Moved", SERVER_B)
        assertThat(repository.getActiveProfile()?.detectedServerType).isEqualTo(ServerType.UNSPECIFIED)
        assertThat(repository.recordDetectedServerType(SERVER_A, ServerType.AGENT)).isFalse()
        assertThat(repository.recordDetectedServerType(SERVER_B, ServerType.UNSPECIFIED)).isFalse()
        assertThat(repository.getActiveProfile()?.effectiveServerType).isEqualTo(ServerType.UNSPECIFIED)
        assertThat(repository.getProfiles()).hasSize(1)
    }

    @Test
    fun getSettingsRepairsMissingActiveIdFromExistingNormalizedMatchingRow() {
        assertMatchingRowRepaired(null)
    }

    @Test
    fun getSettingsRepairsStaleActiveIdFromExistingNormalizedMatchingRow() {
        assertMatchingRowRepaired("deleted-profile")
    }

    @Test
    fun validExplicitSelectionWinsOverMismatchedStoredServer() {
        seedLegacyProfiles("profile-b")
        val reopened = SettingsRepository(context)
        val settings = reopened.getSettings(DEFAULT_SERVER, "")
        assertThat(settings.serverUrl).isEqualTo(SERVER_B)
        assertThat(settings.allowedHosts).containsExactly("b.example.test", "dashboard.example.test")
        assertThat(reopened.getActiveProfile()?.id).isEqualTo("profile-b")
        val persisted = SettingsRepository(context).getSettings(DEFAULT_SERVER, "")
        assertThat(persisted).isEqualTo(settings)
        assertThat(encryptedPreferences().getString("server_url", null)).isEqualTo(SERVER_B)
    }

    @Test
    fun missingOrStaleSelectionWithoutMatchingUrlDoesNotChooseArbitraryProfile() {
        for (activeId in listOf(null, "deleted-profile")) {
            seedLegacyProfiles(activeId, "https://unlisted.example.test")
            val reopened = SettingsRepository(context)
            val settings = reopened.getSettings(DEFAULT_SERVER, "")
            assertThat(settings.serverUrl).isEqualTo("https://unlisted.example.test")
            assertThat(reopened.getActiveProfile()).isNull()
            assertThat(reopened.getProfiles().any { it.isActive }).isFalse()
            assertThat(SettingsRepository(context).getSettings(DEFAULT_SERVER, "").serverUrl)
                .isEqualTo(settings.serverUrl)
        }
    }

    private fun assertMatchingRowRepaired(activeId: String?) {
        seedLegacyProfiles(activeId)
        val reopened = SettingsRepository(context)
        val settings = reopened.getSettings(DEFAULT_SERVER, "")
        assertThat(settings.serverUrl).isEqualTo("HTTPS://A.EXAMPLE.TEST/hermes/")
        assertThat(settings.allowedHosts).containsExactly("a.example.test", "dashboard.example.test")
        assertThat(reopened.getActiveProfile()?.id).isEqualTo("profile-a")
        assertThat(reopened.getProfiles().filter { it.isActive }.map { it.id })
            .containsExactly("profile-a")
        assertThat(encryptedPreferences().getString("active_profile_id", null)).isEqualTo("profile-a")
        assertThat(SettingsRepository(context).getActiveProfile()?.id).isEqualTo("profile-a")
    }

    private fun seedLegacyProfiles(activeId: String?, storedUrl: String = SERVER_A) {
        val rows = JSONArray()
        for ((id, url) in listOf(
            "profile-b" to SERVER_B,
            "profile-a" to "HTTPS://A.EXAMPLE.TEST/hermes/"
        )) {
            rows.put(JSONObject().apply {
                put("id", id)
                put("name", id)
                put("url", url)
                put("createdAt", 1L)
                // A stale per-row flag must not select the first, nonmatching server.
                put("isActive", id == "profile-b")
            })
        }
        val editor = encryptedPreferences().edit()
            .putString("server_url", storedUrl)
            .putString("dashboard_terminal_url", DASHBOARD)
            .putString("allowed_hosts", "${java.net.URI(storedUrl).host},dashboard.example.test")
            .putBoolean("is_configured", true)
            .putString("server_profiles", rows.toString())
        if (activeId == null) editor.remove("active_profile_id")
        else editor.putString("active_profile_id", activeId)
        assertThat(editor.commit()).isTrue()
    }

    private fun encryptedPreferences(): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "hermes_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        private val prefix = "selected_server_test_${UUID.randomUUID()}_"
        private val preferenceNames = mutableSetOf<String>()

        // AndroidX's encrypted keyset storage obtains the application context.
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            preferenceNames.add(isolatedName)
            return baseContext.getSharedPreferences(isolatedName, mode)
        }

        fun deleteTestPreferences() {
            preferenceNames.forEach { baseContext.deleteSharedPreferences(it) }
        }
    }

    companion object {
        private const val SERVER_A = "https://a.example.test/hermes"
        private const val SERVER_B = "https://b.example.test/hermes"
        private const val DASHBOARD = "https://dashboard.example.test"
        private const val DEFAULT_SERVER = "https://default.example.test"
    }
}
