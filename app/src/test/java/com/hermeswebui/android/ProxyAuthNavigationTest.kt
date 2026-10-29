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
    fun `main-frame redirect consent requires the redirecting hop to be the exact server origin`() {
        val consent = ProxyAuthNavigation.Companion::mainFrameRedirectConsentOrigin
        assertThat(consent(server, "$server/session/abc", "$login/login?rd=x")).isEqualTo(login)
        assertThat(consent("$server/hermes", "https://HERMES.example.test:443/api/x", "$login/")).isEqualTo(login)
        // Allowlisted by host only, but not the configured origin: these hops cannot speak for the server.
        for (hop in listOf(
            "https://hermes.example.test:8443/r",
            "http://hermes.example.test/r",
            "https://cdn.hermes.example.test/r",
            "about:blank",
            null
        )) {
            assertThat(consent(server, hop, "https://attacker.example/")).isNull()
        }
        assertThat(consent(server, "$server/r", "http://attacker.example/")).isNull()
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
    fun `status redirects are classified by their origin relationship`() {
        val classify = ProxyAuthNavigation.Companion::classifyRedirect
        assertThat(classify("http://hermes.test/api/status", 301, "https://hermes.test/api/status"))
            .isEqualTo(ProxyAuthNavigation.StatusRedirect.HttpsUpgrade("https://hermes.test"))
        assertThat(classify("http://hermes.test:8443/api/status", 308, "https://hermes.test:8443/api/status"))
            .isEqualTo(ProxyAuthNavigation.StatusRedirect.HttpsUpgrade("https://hermes.test:8443"))
        for (location in listOf("/login?rd=secret", "$server/oauth2/sign_in", "https://HERMES.example.test:443/x")) {
            assertThat(classify("$server/api/status", 302, location))
                .isEqualTo(ProxyAuthNavigation.StatusRedirect.SameOrigin)
        }
        assertThat(classify("http://hermes.test:80/api/status", 302, "http://hermes.test/login"))
            .isEqualTo(ProxyAuthNavigation.StatusRedirect.SameOrigin)
        // A different port, a different host, or a non-standard port pair is not an upgrade.
        for ((source, location) in listOf(
            "http://hermes.test:8080" to "https://hermes.test:8443/",
            "http://hermes.test" to "https://login.hermes.test/",
            "https://hermes.test" to "https://hermes.test:8443/",
            server to "$login/login"
        )) {
            assertThat(classify(source, 302, location)).isInstanceOf(ProxyAuthNavigation.StatusRedirect.CrossOrigin::class.java)
        }
        assertThat(classify(server, 302, "http://hermes.example.test/login")).isNull()
        assertThat(classify(server, 200, "$login/login")).isNull()
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
