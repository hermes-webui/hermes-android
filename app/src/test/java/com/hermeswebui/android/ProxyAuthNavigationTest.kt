package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.core.security.ProxyAuthNavigation
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.core.security.WebTrustPolicy
import org.junit.Test

class ProxyAuthNavigationTest {
    private val server = "https://hermes.example.test"
    private val login = "https://login.example.test"
    private var now = 1_000L
    private val navigation = ProxyAuthNavigation { now }

    @Test
    fun `all web redirect statuses resolve relative and absolute locations`() {
        for (status in listOf(301, 302, 303, 307, 308)) {
            assertThat(ProxyAuthNavigation.safeRedirectOrigin("$server/api/status", status, "/login?token=secret"))
                .isEqualTo(server)
            assertThat(ProxyAuthNavigation.safeRedirectOrigin(server, status, "$login/?rd=secret"))
                .isEqualTo(login)
        }
    }

    @Test
    fun `unsafe missing and non redirect destinations cannot become login origins`() {
        for (location in listOf(null, "", "http://login.example.test", "javascript:alert(1)",
            "intent://login", "https://user:password@login.example.test", "https://login.example.test:99999",
            "https://[broken", "file:///login")) {
            assertThat(ProxyAuthNavigation.safeRedirectOrigin(server, 302, location)).isNull()
        }
        for (status in listOf(200, 304, 401, 403, 500)) {
            assertThat(ProxyAuthNavigation.safeRedirectOrigin(server, status, login)).isNull()
        }
        assertThat(ProxyAuthNavigation.safeRedirectOrigin("https://", 302, login)).isNull()
    }

    @Test
    fun `http deployments and upgrades remain supported without allowing https downgrades`() {
        assertThat(ProxyAuthNavigation.safeRedirectOrigin("http://hermes.test", 302, "http://login.test"))
            .isEqualTo("http://login.test")
        assertThat(ProxyAuthNavigation.safeRedirectOrigin("http://hermes.test", 302, login)).isEqualTo(login)
        assertThat(ProxyAuthNavigation.safeRedirectOrigin(server.uppercase(), 302, "$login/login")).isEqualTo(login)
    }

    @Test
    fun `approval grants only the two exact origins for a fixed time`() {
        assertThat(navigation.allows(server, login)).isFalse()
        navigation.begin(server, login)
        assertThat(navigation.allows(server, "$login/login")).isTrue()
        assertThat(navigation.allows(server, "$server/auth/return")).isTrue()
        for (target in listOf("https://other.example.test", "https://sub.login.example.test",
            "$login:444/login", "http://login.example.test", "javascript:alert(1)",
            "https://user@login.example.test")) {
            assertThat(navigation.allows(server, target)).isFalse()
        }
        assertThat(navigation.allows("https://another-server.test", login)).isFalse()
        now += ProxyAuthNavigation.TIMEOUT_MS
        assertThat(navigation.allows(server, login)).isFalse()
        // Expiry removes navigation permission, not the ban on restoring auth interstitials.
        assertThat(navigation.isPending(server)).isTrue()
    }

    @Test
    fun `only a configured-origin Hermes page completes sign in`() {
        navigation.begin(server, login)
        navigation.completeIfHermesPage(server, login, true)
        navigation.completeIfHermesPage(server, "$server/auth/interstitial", false)
        assertThat(navigation.isPending(server)).isTrue()
        navigation.completeIfHermesPage(server, "$server/session/example", true)
        assertThat(navigation.isPending(server)).isFalse()
        assertThat(navigation.allows(server, login)).isFalse()
    }

    @Test
    fun `navigation approval never grants Hermes bridge notification or microphone trust`() {
        navigation.begin(server, login)
        val trust = WebTrustPolicy(UrlPolicy(setOf("hermes.example.test")), server, "")
        assertThat(navigation.allows(server, login)).isTrue()
        assertThat(trust.isConfiguredWebUiRoute(login)).isFalse()
        assertThat(trust.isTrustedNotificationTarget(login)).isFalse()
        assertThat(trust.isTrustedNotificationBridgeSource(login, true, login)).isFalse()
        assertThat(trust.isTrustedPermissionOrigin(login, login)).isFalse()
        assertThat(trust.allowsGestureFreeMediaPlayback(login)).isFalse()
        navigation.clear()
        assertThat(navigation.isPending(server)).isFalse()
    }
}
