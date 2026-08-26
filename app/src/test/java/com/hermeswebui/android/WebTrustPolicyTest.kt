package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.core.security.WebTrustPolicy
import org.junit.Test

class WebTrustPolicyTest {
    private val policy = WebTrustPolicy(
        urlPolicy = UrlPolicy(setOf("hermes.example.com", "dashboard.example.com")),
        configuredWebUiUrl = "https://hermes.example.com",
        configuredDashboardUrl = "https://dashboard.example.com/app"
    )

    @Test
    fun `WebUI route requires the configured origin`() {
        assertThat(policy.isConfiguredWebUiRoute("https://hermes.example.com/session/123")).isTrue()
        assertThat(policy.isConfiguredWebUiRoute("https://api.hermes.example.com/session/123")).isFalse()
    }

    @Test
    fun `dashboard route matches its configured path only`() {
        assertThat(policy.isConfiguredDashboardRoute("https://dashboard.example.com/app/status")).isTrue()
        assertThat(policy.isConfiguredDashboardRoute("https://dashboard.example.com/other")).isFalse()
    }

    @Test
    fun `notification target must remain on the configured WebUI route`() {
        assertThat(policy.isTrustedNotificationTarget("https://hermes.example.com/session/123")).isTrue()
        assertThat(policy.isTrustedNotificationTarget("https://dashboard.example.com/app")).isFalse()
        assertThat(policy.isTrustedNotificationTarget("https://api.hermes.example.com/session/123")).isFalse()
    }

    @Test
    fun `notification bridge requires main frame source and destination`() {
        assertThat(
            policy.isTrustedNotificationBridgeSource(
                sourceOrigin = "https://hermes.example.com",
                isMainFrame = true,
                currentMainFrameUrl = "https://hermes.example.com/session/123"
            )
        ).isTrue()
        assertThat(
            policy.isTrustedNotificationBridgeSource(
                sourceOrigin = "https://hermes.example.com",
                isMainFrame = false,
                currentMainFrameUrl = "https://hermes.example.com/session/123"
            )
        ).isFalse()
        assertThat(
            policy.isTrustedNotificationBridgeSource(
                sourceOrigin = "https://hermes.example.com",
                isMainFrame = true,
                currentMainFrameUrl = "https://provider.example.com/login"
            )
        ).isFalse()
    }

    @Test
    fun `notification bridge normalizes a trailing origin dot`() {
        assertThat(
            policy.isTrustedNotificationBridgeSource(
                sourceOrigin = "https://HERMES.EXAMPLE.COM./",
                isMainFrame = true,
                currentMainFrameUrl = "https://hermes.example.com/"
            )
        ).isTrue()
    }

    @Test
    fun `permission accepts an explicit allowlisted web origin`() {
        assertThat(
            policy.isTrustedPermissionOrigin(
                origin = "https://api.hermes.example.com",
                currentMainFrameUrl = "https://provider.example.com/login"
            )
        ).isTrue()
    }

    @Test
    fun `permission accepts opaque origin only on the configured WebUI frame`() {
        assertThat(
            policy.isTrustedPermissionOrigin(
                origin = "null",
                currentMainFrameUrl = "https://hermes.example.com/session/123"
            )
        ).isTrue()
        assertThat(
            policy.isTrustedPermissionOrigin(
                origin = "null",
                currentMainFrameUrl = "https://provider.example.com/login"
            )
        ).isFalse()
    }

    @Test
    fun `permission with no origin requires the configured WebUI frame`() {
        assertThat(
            policy.isTrustedPermissionOrigin(
                origin = null,
                currentMainFrameUrl = "https://hermes.example.com/session/123"
            )
        ).isTrue()
        assertThat(
            policy.isTrustedPermissionOrigin(
                origin = null,
                currentMainFrameUrl = "https://provider.example.com/login"
            )
        ).isFalse()
    }
}