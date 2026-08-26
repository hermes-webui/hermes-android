package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OAuthPopupFlowTest {
    @Test
    fun `parseAuthorizationStart recognizes self hosted oidc authorization url`() {
        val flow = OAuthPopupFlow.parseAuthorizationStart(
            "https://auth.racci.dev/ui/oauth2?response_type=code&client_id=hermes&redirect_uri=https%3A%2F%2Fagent.racci.dev%2Fauth%2Fcallback&scope=openid+profile+email&state=test-state&code_challenge=test-challenge&code_challenge_method=S256"
        )

        assertThat(flow).isNotNull()
        assertThat(flow?.redirectUri).isEqualTo("https://agent.racci.dev/auth/callback")
    }

    @Test
    fun `parseAuthorizationStart recognizes oauth flow by core parameters only`() {
        val flow = OAuthPopupFlow.parseAuthorizationStart(
            "https://custom-auth.internal/login?response_type=code&client_id=hermes&redirect_uri=http%3A%2F%2F192.168.1.50%3A8080%2Fcallback"
        )

        assertThat(flow).isNotNull()
        assertThat(flow?.redirectUri).isEqualTo("http://192.168.1.50:8080/callback")
    }

    @Test
    fun `parseAuthorizationStart recognizes additional path markers`() {
        assertThat(OAuthPopupFlow.parseAuthorizationStart(
            "https://auth.internal/auth?response_type=code&client_id=x&redirect_uri=http%3A%2F%2Flocalhost%2F"
        )).isNotNull()
        assertThat(OAuthPopupFlow.parseAuthorizationStart(
            "https://auth.internal/login?response_type=code&client_id=x&redirect_uri=http%3A%2F%2Flocalhost%2F"
        )).isNotNull()
        assertThat(OAuthPopupFlow.parseAuthorizationStart(
            "https://auth.internal/sso?response_type=code&client_id=x&redirect_uri=http%3A%2F%2Flocalhost%2F"
        )).isNotNull()
    }

    @Test
    fun `parseAuthorizationStart rejects urls without oauth code flow indicators`() {
  val flow = OAuthPopupFlow.parseAuthorizationStart(
            "https://agent.racci.dev/settings?redirect_uri=https%3A%2F%2Fagent.racci.dev%2Fauth%2Fcallback&client_id=hermes"
        )

        assertThat(flow).isNull()
    }

    @Test
    fun `verified callback requires matching redirect path and auth result`() {
        val flow = OAuthPopupFlow.parseAuthorizationStart(
            "https://auth.racci.dev/ui/oauth2?response_type=code&client_id=hermes&redirect_uri=https%3A%2F%2Fagent.racci.dev%2Fauth%2Fcallback&scope=openid&state=test-state"
        )

        assertThat(flow?.isVerifiedCallbackUrl("https://agent.racci.dev/auth/callback?code=abc123&state=test-state")).isTrue()
        assertThat(flow?.isVerifiedCallbackUrl("https://agent.racci.dev/auth/callback?error=access_denied&state=test-state")).isTrue()
        assertThat(flow?.isCallbackEndpointUrl("https://agent.racci.dev/auth/callback?state=test-state")).isTrue()
        assertThat(flow?.isRedirectOriginUrl("https://agent.racci.dev/")).isTrue()
        assertThat(flow?.isRedirectOriginUrl("http://agent.racci.dev/")).isFalse()
        assertThat(flow?.isRedirectOriginUrl("https://evil.racci.dev/")).isFalse()
        assertThat(flow?.isVerifiedCallbackUrl("https://agent.racci.dev/auth/other?code=abc123&state=test-state")).isFalse()
        assertThat(flow?.isVerifiedCallbackUrl("https://evil.racci.dev/auth/callback?code=abc123&state=test-state")).isFalse()
        assertThat(flow?.isVerifiedCallbackUrl("http://agent.racci.dev/auth/callback?code=abc123&state=test-state")).isFalse()
        assertThat(flow?.isVerifiedCallbackUrl("https://agent.racci.dev/auth/callback?state=test-state")).isFalse()
    }

    @Test
    fun `authorization start allows scheme upgrades but rejects downgrades`() {
        val flow = OAuthPopupFlow.parseAuthorizationStart(
            "https://auth.racci.dev/ui/oauth2?response_type=code&client_id=hermes&redirect_uri=https%3A%2F%2Fagent.racci.dev%2Fauth%2Fcallback&scope=openid&state=test-state"
        )

        assertThat(flow?.redirectsToOrigin("https://agent.racci.dev")).isTrue()
        assertThat(flow?.redirectsToOrigin("https://agent.racci.dev/")).isTrue()
        assertThat(flow?.redirectsToOrigin("http://agent.racci.dev")).isTrue()
        assertThat(flow?.redirectsToOrigin("https://other.racci.dev")).isFalse()
    }
}
