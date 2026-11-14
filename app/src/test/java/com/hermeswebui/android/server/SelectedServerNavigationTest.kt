package com.hermeswebui.android.server

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.core.security.WebTrustPolicy
import com.hermeswebui.android.data.HermesApiClient.ServerReadinessStatus
import com.hermeswebui.android.data.ServerProfile
import com.hermeswebui.android.data.SettingsRepository
import org.junit.Test

/**
 * Settings marks the active profile "Current"; the main WebView must load that same server on
 * switch, on the "Current" reconnect tap and on cold start, never a stale production page.
 */
class SelectedServerNavigationTest {
    private val production = "https://hermes.example.com"
    private val acceptance = "https://hermes.example.com:8789"

    private val productionProfile = ServerProfile(id = "prod", name = "Production", url = production)
    private val acceptanceProfile = ServerProfile(id = "voice", name = "Voice 8789", url = acceptance)

    private fun profilesWithActive(activeId: String?): List<ServerProfile> =
        SettingsRepository.parseProfiles(
            json = """
                [{"id":"prod","name":"Production","url":"$production","createdAt":1,"isActive":true},
                 {"id":"voice","name":"Voice 8789","url":"$acceptance","createdAt":2,"isActive":false}]
            """.trimIndent(),
            activeId = activeId
        )

    private fun trustFor(serverUrl: String) = WebTrustPolicy(
        urlPolicy = UrlPolicy(setOf("hermes.example.com")),
        configuredWebUiUrl = serverUrl,
        configuredDashboardUrl = ""
    )

    // 1. SERVER SWITCH ------------------------------------------------------------------------

    @Test
    fun `sign-in-required readiness still switches to the selected server`() {
        assertThat(
            SelectedServerNavigation.switchAction(
                isReady = false,
                reachable = true,
                status = ServerReadinessStatus.AUTH_REQUIRED,
                authPromptSilenced = false
            )
        ).isEqualTo(ServerSwitchAction.CONFIRM_SIGN_IN_SWITCH)
        assertThat(
            SelectedServerNavigation.switchAction(
                isReady = false,
                reachable = true,
                status = ServerReadinessStatus.AUTH_REQUIRED,
                authPromptSilenced = true
            )
        ).isEqualTo(ServerSwitchAction.SWITCH_NOW)
        assertThat(
            SelectedServerNavigation.switchAction(
                isReady = true,
                reachable = true,
                status = ServerReadinessStatus.READY,
                authPromptSilenced = false
            )
        ).isEqualTo(ServerSwitchAction.CONFIRM_SWITCH)
        assertThat(
            SelectedServerNavigation.switchAction(
                isReady = false,
                reachable = false,
                status = ServerReadinessStatus.UNREACHABLE,
                authPromptSilenced = false
            )
        ).isEqualTo(ServerSwitchAction.BLOCK)
    }

    @Test
    fun `switching from production to auth-protected 8789 navigates to 8789 root`() {
        // WebView currently shows a production page.
        val displayedBeforeSwitch = "$production/session/abc"

        val target = SelectedServerNavigation.switchTargetUrl(acceptanceProfile)

        assertThat(target).isEqualTo(acceptance)
        assertThat(trustFor(acceptance).isConfiguredWebUiRoute(target)).isTrue()
        assertThat(target).isNotEqualTo(displayedBeforeSwitch)
        assertThat(trustFor(acceptance).isConfiguredWebUiRoute(displayedBeforeSwitch)).isFalse()
    }

    @Test
    fun `after the switch the persisted selection is 8789`() {
        // switchServerProfile writes server_url = 8789 and the active id = voice.
        val selected = SelectedServerNavigation.selectedServerUrl(
            storedServerUrl = acceptance,
            profiles = profilesWithActive("voice")
        )
        assertThat(selected).isEqualTo(acceptance)
    }

    @Test
    fun `Current 8789 profile wins over a stale production server_url`() {
        // Editing the active profile's URL used to update only the profile row, leaving
        // server_url on production while Settings showed 8789 as Current.
        val selected = SelectedServerNavigation.selectedServerUrl(
            storedServerUrl = production,
            profiles = profilesWithActive("voice")
        )
        assertThat(selected).isEqualTo(acceptance)
    }

    @Test
    fun `tapping the Current 8789 profile leaves a displayed production page`() {
        val selected = SelectedServerNavigation.selectedServerUrl(production, profilesWithActive("voice"))
        val target = SelectedServerNavigation.reconnectUrl(
            currentUrl = "$production/session/abc",
            selectedServerUrl = selected,
            trustPolicy = trustFor(selected)
        )
        assertThat(target).isEqualTo(acceptance)
    }

    @Test
    fun `reconnect keeps the current route when it already belongs to the selected server`() {
        val target = SelectedServerNavigation.reconnectUrl(
            currentUrl = "$acceptance/session/xyz",
            selectedServerUrl = acceptance,
            trustPolicy = trustFor(acceptance)
        )
        assertThat(target).isEqualTo("$acceptance/session/xyz")
    }

    @Test
    fun `no active profile keeps the stored server_url`() {
        assertThat(
            SelectedServerNavigation.selectedServerUrl(production, profilesWithActive(null))
        ).isEqualTo(production)
        assertThat(
            SelectedServerNavigation.selectedServerUrl(production, emptyList())
        ).isEqualTo(production)
    }

    @Test
    fun `a manual server save re-points the active profile to the saved URL`() {
        val profiles = listOf(productionProfile, acceptanceProfile)
        assertThat(SelectedServerNavigation.activeProfileIdFor("$acceptance/", profiles)).isEqualTo("voice")
        assertThat(SelectedServerNavigation.activeProfileIdFor(production, profiles)).isEqualTo("prod")
        assertThat(SelectedServerNavigation.activeProfileIdFor("https://other.example", profiles)).isNull()
    }

    @Test
    fun `cosmetic Current profile edits and renames preserve the session without reloading`() {
        assertThat(SelectedServerNavigation.profileEditAction(productionProfile, " HTTPS://HERMES.EXAMPLE.COM/ "))
            .isEqualTo(ServerProfileEditAction.NONE)
        assertThat(SelectedServerNavigation.profileEditAction(productionProfile, production))
            .isEqualTo(ServerProfileEditAction.NONE)
    }

    @Test
    fun `same-origin mount changes refresh without wiping cookies`() {
        assertThat(SelectedServerNavigation.profileEditAction(productionProfile, "$production/hermes"))
            .isEqualTo(ServerProfileEditAction.REFRESH)
        assertThat(
            SelectedServerNavigation.profileEditAction(
                productionProfile.copy(url = "$production/Hermes"),
                "$production/hermes"
            )
        ).isEqualTo(ServerProfileEditAction.REFRESH)
    }

    @Test
    fun `origin changes wipe the old session while non-Current edits do not navigate`() {
        assertThat(SelectedServerNavigation.profileEditAction(productionProfile, acceptance))
            .isEqualTo(ServerProfileEditAction.SWITCH)
        assertThat(SelectedServerNavigation.profileEditAction(productionProfile, "http://hermes.example.com"))
            .isEqualTo(ServerProfileEditAction.SWITCH)
        assertThat(SelectedServerNavigation.profileEditAction(productionProfile, "https://other.example.com"))
            .isEqualTo(ServerProfileEditAction.SWITCH)
        assertThat(SelectedServerNavigation.profileEditAction(null, acceptance))
            .isEqualTo(ServerProfileEditAction.NONE)
    }

    // 2. COLD START ---------------------------------------------------------------------------

    @Test
    fun `cold start with 8789 selected and stale production state loads 8789 first`() {
        val selected = SelectedServerNavigation.selectedServerUrl(
            storedServerUrl = production,
            profiles = profilesWithActive("voice")
        )
        val start = SelectedServerNavigation.startupUrl(
            selectedServerUrl = selected,
            lastLoadedUrl = "$production/session/abc",
            notificationUrl = null,
            trustPolicy = trustFor(selected)
        )
        assertThat(start).isEqualTo(acceptance)
    }

    @Test
    fun `cold start ignores a notification target on another origin`() {
        val start = SelectedServerNavigation.startupUrl(
            selectedServerUrl = acceptance,
            lastLoadedUrl = null,
            notificationUrl = "$production/session/abc",
            trustPolicy = trustFor(acceptance)
        )
        assertThat(start).isEqualTo(acceptance)
    }

    @Test
    fun `cold start restores a remembered 8789 route and notification target`() {
        assertThat(
            SelectedServerNavigation.startupUrl(
                selectedServerUrl = acceptance,
                lastLoadedUrl = "$acceptance/session/xyz",
                notificationUrl = null,
                trustPolicy = trustFor(acceptance)
            )
        ).isEqualTo("$acceptance/session/xyz")
        assertThat(
            SelectedServerNavigation.startupUrl(
                selectedServerUrl = acceptance,
                lastLoadedUrl = "$acceptance/session/xyz",
                notificationUrl = "$acceptance/session/n1",
                trustPolicy = trustFor(acceptance)
            )
        ).isEqualTo("$acceptance/session/n1")
    }

    @Test
    fun `cold start rejects visited and notification URLs with outdated credentials`() {
        val oldRoute = "https://old:Secret@hermes.example.com/session/old"
        val safeRoute = "$production/session/safe"
        for (configured in listOf(production, "https://new:Secret@hermes.example.com")) {
            val trust = trustFor(configured)
            assertThat(SelectedServerNavigation.startupUrl(configured, oldRoute, null, trust))
                .isEqualTo(configured)
            assertThat(SelectedServerNavigation.startupUrl(configured, safeRoute, oldRoute, trust))
                .isEqualTo(safeRoute)
            assertThat(SelectedServerNavigation.startupUrl(configured, null, oldRoute, trust))
                .isEqualTo(configured)
        }
    }

    @Test
    fun `reconnect rejects a current route with outdated credentials`() {
        val oldRoute = "https://old:Secret@hermes.example.com/session/old"
        for (configured in listOf(production, "https://new:Secret@hermes.example.com")) {
            assertThat(SelectedServerNavigation.reconnectUrl(oldRoute, configured, trustFor(configured)))
                .isEqualTo(configured)
        }
    }

    @Test
    fun `configured credentials and credential-free routes remain restorable`() {
        val configured = "https://User:p%40ss@hermes.example.com"
        val matching = "$configured/session/current"
        val withoutCredentials = "$production/session/other"
        val trust = trustFor(configured)
        assertThat(SelectedServerNavigation.startupUrl(configured, matching, null, trust))
            .isEqualTo(matching)
        assertThat(SelectedServerNavigation.startupUrl(configured, null, withoutCredentials, trust))
            .isEqualTo(withoutCredentials)
        assertThat(SelectedServerNavigation.reconnectUrl(matching, configured, trust))
            .isEqualTo(matching)
        assertThat(SelectedServerNavigation.reconnectUrl(withoutCredentials, configured, trust))
            .isEqualTo(withoutCredentials)
        assertThat(
            SelectedServerNavigation.startupUrl(
                configured, "https://user:p%40ss@hermes.example.com/session/old", null, trust
            )
        ).isEqualTo(configured)
    }

    @Test
    fun `workspace recovery requires compatible credentials and a non-root same-origin route`() {
        val configured = "https://User:p%40ss@hermes.example.com"
        val matching = "$configured/session/current"
        val withoutCredentials = "$production/session/other"
        assertThat(SelectedServerNavigation.routeRecoveryUrl(matching, configured)).isEqualTo(matching)
        assertThat(SelectedServerNavigation.routeRecoveryUrl(withoutCredentials, configured))
            .isEqualTo(withoutCredentials)
        assertThat(SelectedServerNavigation.routeRecoveryUrl(matching, production)).isEmpty()
        assertThat(SelectedServerNavigation.routeRecoveryUrl(matching, "https://user:p%40ss@hermes.example.com"))
            .isEmpty()
        assertThat(SelectedServerNavigation.routeRecoveryUrl(configured, configured)).isEmpty()
        assertThat(SelectedServerNavigation.routeRecoveryUrl("$acceptance/session/other", configured)).isEmpty()
        assertThat(SelectedServerNavigation.routeRecoveryUrl(null, configured)).isEmpty()
    }

    @Test
    fun `back history skips stale credentials but preserves other entries`() {
        val stale = "https://User:Pass@hermes.example.com/session/old"
        val safe = "$production/session/safe"
        val current = "$production/session/current"
        assertThat(SelectedServerNavigation.previousHistoryOffset(listOf(safe, stale, current), 2, production))
            .isEqualTo(-2)
        assertThat(SelectedServerNavigation.previousHistoryOffset(listOf(stale, current), 1, production))
            .isNull()
        assertThat(
            SelectedServerNavigation.previousHistoryOffset(
                listOf(stale, current), 1, "https://User:Pass@hermes.example.com"
            )
        ).isEqualTo(-1)
        assertThat(SelectedServerNavigation.previousHistoryOffset(listOf(safe, current), 1, production))
            .isEqualTo(-1)
        assertThat(
            SelectedServerNavigation.previousHistoryOffset(
                listOf("https://provider.example.com/login", current), 1, production
            )
        ).isEqualTo(-1)
        assertThat(
            SelectedServerNavigation.previousHistoryOffset(
                listOf("https://User:Pass@hermes.example.com:8443/session/old", current),
                1,
                production
            )
        ).isNull()
        assertThat(SelectedServerNavigation.previousHistoryOffset(listOf(current), 1, production)).isNull()
    }

    @Test
    fun `official Webapp back stays inside the mounted workspace document`() {
        val root = "$production/"
        val tab = "$production/#/settings"
        assertThat(SelectedServerNavigation.sameDocumentBackOffset(listOf(root, tab), 1, production))
            .isEqualTo(-1)
        assertThat(SelectedServerNavigation.sameDocumentBackOffset(listOf("$production/#/chat", tab), 1, production))
            .isEqualTo(-1)
        // A current entry without a fragment was reached by a full load, so its predecessor is
        // another document even though the URLs differ only by fragment.
        assertThat(SelectedServerNavigation.sameDocumentBackOffset(listOf(tab, root), 1, production))
            .isNull()
        assertThat(SelectedServerNavigation.sameDocumentBackOffset(listOf("$production/login", root), 1, production))
            .isNull()
        assertThat(
            SelectedServerNavigation.sameDocumentBackOffset(
                listOf("https://auth.example.com/#/login", "https://auth.example.com/#/done", root), 2, production
            )
        ).isNull()
        assertThat(SelectedServerNavigation.sameDocumentBackOffset(listOf(root), 0, production)).isNull()
        assertThat(
            SelectedServerNavigation.sameDocumentBackOffset(
                listOf("https://User:Pass@hermes.example.com/#/a", "https://hermes.example.com/#/b"), 1, production
            )
        ).isNull()
    }

    @Test
    fun `credential case changes refresh the current profile rather than selecting its old URL`() {
        val previous = productionProfile.copy(url = "https://User:Pass@hermes.example.com")
        val changed = "https://user:Pass@hermes.example.com"
        assertThat(SelectedServerNavigation.profileEditAction(previous, changed))
            .isEqualTo(ServerProfileEditAction.REFRESH)
        assertThat(SelectedServerNavigation.activeProfileIdFor(changed, listOf(previous))).isNull()
    }
}
