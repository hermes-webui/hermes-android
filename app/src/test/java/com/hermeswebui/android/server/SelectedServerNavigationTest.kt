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
    private val production = "https://calinux.tail96d6ee.ts.net"
    private val acceptance = "https://calinux.tail96d6ee.ts.net:8789"

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
        urlPolicy = UrlPolicy(setOf("calinux.tail96d6ee.ts.net")),
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
}
