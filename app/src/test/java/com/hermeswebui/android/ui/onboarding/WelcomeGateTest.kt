package com.hermeswebui.android.ui.onboarding

import com.google.common.truth.Truth.assertThat
import java.net.URI
import org.junit.Test

class WelcomeGateTest {
    @Test
    fun `a brand new user who opened the app normally sees the guide`() {
        assertThat(WelcomeGate.shouldShowOnLaunch(isConfigured = false, hasSeen = false, isPlainLaunch = true)).isTrue()
    }

    @Test
    fun `a user who already saw or skipped the guide is not shown it again`() {
        assertThat(WelcomeGate.shouldShowOnLaunch(isConfigured = false, hasSeen = true, isPlainLaunch = true)).isFalse()
    }

    @Test
    fun `an existing user with a configured server never sees the guide`() {
        assertThat(WelcomeGate.shouldShowOnLaunch(isConfigured = true, hasSeen = false, isPlainLaunch = true)).isFalse()
        assertThat(WelcomeGate.shouldShowOnLaunch(isConfigured = true, hasSeen = true, isPlainLaunch = true)).isFalse()
    }

    @Test
    fun `a deep link or share launch is never interrupted by the guide`() {
        // For example the hermes://app/settings recovery link on a fresh install.
        assertThat(WelcomeGate.shouldShowOnLaunch(isConfigured = false, hasSeen = false, isPlainLaunch = false)).isFalse()
    }

    @Test
    fun `an existing configured user is recorded as having seen the guide without showing it`() {
        assertThat(WelcomeGate.shouldMarkSeenSilently(isConfigured = true, hasSeen = false)).isTrue()
        assertThat(WelcomeGate.shouldMarkSeenSilently(isConfigured = true, hasSeen = true)).isFalse()
        assertThat(WelcomeGate.shouldMarkSeenSilently(isConfigured = false, hasSeen = false)).isFalse()
    }

    @Test
    fun `every guide link is a plain https page with no credentials, query or fragment`() {
        assertThat(WelcomeLinks.all).isNotEmpty()
        WelcomeLinks.all.forEach { url ->
            val uri = URI(url)
            assertThat(uri.scheme).isEqualTo("https")
            assertThat(uri.host).isNotEmpty()
            assertThat(uri.userInfo).isNull()
            assertThat(uri.rawQuery).isNull()
            assertThat(uri.rawFragment).isNull()
        }
    }

    @Test
    fun `the remote access list includes Tailscale and several alternatives`() {
        val names = WelcomeLinks.remoteAccessOptions.map { it.name }
        assertThat(names).contains("Tailscale")
        assertThat(names.size).isAtLeast(3)
        assertThat(names).containsNoDuplicates()
    }
}
