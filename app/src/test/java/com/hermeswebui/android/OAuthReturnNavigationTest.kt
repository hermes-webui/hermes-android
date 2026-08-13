package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OAuthReturnNavigationTest {
    private var nowMs = 1_000L

    @Test
    fun `verified callback return keeps same-origin redirect chain in main WebView`() {
        val navigation = OAuthReturnNavigation(nowMs = { nowMs })
        navigation.begin(flow())

        assertThat(navigation.shouldStayInMainWebView(callbackUrl)).isTrue()
        assertThat(navigation.shouldStayInMainWebView("https://agent.example.com/")).isTrue()
        assertThat(navigation.shouldStayInMainWebView("https://agent.example.com/session/123")).isTrue()
        assertThat(navigation.shouldStayInMainWebView("https://dashboard.example.com/")).isFalse()
    }

    @Test
    fun `callback URL is not remembered but final Hermes route is`() {
        val navigation = OAuthReturnNavigation(nowMs = { nowMs })
        navigation.begin(flow())

        assertThat(navigation.shouldRememberUrl(callbackUrl)).isFalse()
        assertThat(navigation.shouldRememberUrl("https://agent.example.com/")).isFalse()

        navigation.completeIfHermesPage("https://agent.example.com/", isHermesPage = true)

        assertThat(navigation.shouldRememberUrl("https://agent.example.com/")).isTrue()
        assertThat(navigation.shouldRememberUrl(callbackUrl)).isFalse()
    }

    @Test
    fun `callback page may finish before a scripted same-origin redirect`() {
        val navigation = OAuthReturnNavigation(nowMs = { nowMs })
        navigation.begin(flow())

        navigation.completeIfHermesPage(callbackUrl, isHermesPage = false)

        assertThat(navigation.shouldStayInMainWebView("https://agent.example.com/")).isTrue()
    }

    @Test
    fun `same-origin interstitial does not end return routing window`() {
        val navigation = OAuthReturnNavigation(nowMs = { nowMs })
        navigation.begin(flow())

        navigation.completeIfHermesPage(
            "https://agent.example.com/auth/complete",
            isHermesPage = false
        )

        assertThat(navigation.shouldStayInMainWebView("https://agent.example.com/")).isTrue()
        assertThat(navigation.shouldRememberUrl("https://agent.example.com/auth/complete")).isFalse()
    }

    @Test
    fun `confirmed final Hermes page ends return routing window`() {
        val navigation = OAuthReturnNavigation(nowMs = { nowMs })
        navigation.begin(flow())

        navigation.completeIfHermesPage(
            "https://agent.example.com/",
            isHermesPage = true
        )

        assertThat(navigation.shouldStayInMainWebView("https://agent.example.com/dashboard")).isFalse()
    }

    @Test
    fun `return routing window expires closed`() {
        val navigation = OAuthReturnNavigation(timeoutMs = 500L, nowMs = { nowMs })
        navigation.begin(flow())
        nowMs += 501L

        assertThat(navigation.shouldStayInMainWebView("https://agent.example.com/")).isFalse()
    }

    private fun flow(): OAuthPopupFlow {
        return checkNotNull(
            OAuthPopupFlow.parseAuthorizationStart(
                "https://idp.example.com/authorize?response_type=code&client_id=hermes" +
                    "&redirect_uri=https%3A%2F%2Fagent.example.com%2Fapi%2Fauth%2Foidc%2Fcallback"
            )
        )
    }

    private companion object {
        const val callbackUrl =
            "https://agent.example.com/api/auth/oidc/callback?code=abc123&state=test-state"
    }
}
