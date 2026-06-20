package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.core.security.NavigationDecision
import com.hermeswebui.android.core.security.UrlOrigins
import com.hermeswebui.android.core.security.UrlPolicy
import org.junit.Test

class UrlPolicyTest {
    private val policy = UrlPolicy(setOf("hermes.example.com"))

    @Test
    fun `allows allowlisted host over https`() {
        assertThat(policy.isAllowed("https://hermes.example.com")).isTrue()
    }

    @Test
    fun `allows allowlisted host over http`() {
        assertThat(policy.navigationDecision("http://hermes.example.com")).isEqualTo(NavigationDecision.ALLOW_IN_WEBVIEW)
    }

    @Test
    fun `opens non allowlisted https hosts externally`() {
        assertThat(policy.navigationDecision("https://example.org/docs")).isEqualTo(NavigationDecision.OPEN_IN_EXTERNAL_BROWSER)
    }

    @Test
    fun `opens non allowlisted http hosts externally`() {
        assertThat(policy.navigationDecision("http://example.org/docs")).isEqualTo(NavigationDecision.OPEN_IN_EXTERNAL_BROWSER)
    }

    @Test
    fun `allows allowlisted subdomains`() {
        assertThat(policy.navigationDecision("https://api.hermes.example.com")).isEqualTo(NavigationDecision.ALLOW_IN_WEBVIEW)
    }

    @Test
    fun `normalizes allowlisted host casing`() {
        val mixedCasePolicy = UrlPolicy(setOf("Hermes.Example.Com"))

        assertThat(mixedCasePolicy.isAllowed("https://HERMES.example.com")).isTrue()
    }

    @Test
    fun `rejects deceptive suffix hosts`() {
        assertThat(policy.isAllowed("https://fakehermes.example.com")).isFalse()
    }

    @Test
    fun `blocks non-web schemes`() {
        assertThat(policy.navigationDecision("ftp://hermes.example.com")).isEqualTo(NavigationDecision.BLOCK)
    }

    @Test
    fun `matches same origin with default https port`() {
        assertThat(
            UrlOrigins.hasSameOrigin(
                "https://hermes.example.com:443/session",
                "https://hermes.example.com"
            )
        ).isTrue()
    }

    @Test
    fun `does not match different origin port`() {
        assertThat(
            UrlOrigins.hasSameOrigin(
                "https://hermes.example.com:8443/session",
                "https://hermes.example.com"
            )
        ).isFalse()
    }

    @Test
    fun `does not match invalid origin values`() {
        assertThat(UrlOrigins.hasSameOrigin("hermes.example.com", "other.example.com")).isFalse()
    }

    @Test
    fun `builds document start origin rule with explicit port`() {
        assertThat(UrlOrigins.documentStartOriginRule("https://hermes.example.com:8457/path"))
            .isEqualTo("https://hermes.example.com:8457")
    }

    @Test
    fun `builds document start origin rule for http`() {
        assertThat(UrlOrigins.documentStartOriginRule("http://hermes.example.com:8457/path"))
            .isEqualTo("http://hermes.example.com:8457")
    }

    @Test
    fun `normalizes origin url by stripping path query and fragment`() {
        assertThat(UrlOrigins.normalizeOriginUrl(" https://hermes.example.com:8455/dashboard?x=1#status "))
            .isEqualTo("https://hermes.example.com:8455")
    }
}
