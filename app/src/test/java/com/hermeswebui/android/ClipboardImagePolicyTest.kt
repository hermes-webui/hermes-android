package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.core.security.WebTrustPolicy
import com.hermeswebui.android.webview.ClipboardImagePolicy
import java.io.ByteArrayInputStream
import org.junit.Test

class ClipboardImagePolicyTest {
    @Test
    fun `only raster image types are accepted and aliases are canonicalized`() {
        assertThat(ClipboardImagePolicy.normalizeMimeType("image/png")).isEqualTo("image/png")
        assertThat(ClipboardImagePolicy.normalizeMimeType("IMAGE/JPG")).isEqualTo("image/jpeg")
        assertThat(ClipboardImagePolicy.normalizeMimeType("image/webp; charset=binary")).isEqualTo("image/webp")
        assertThat(ClipboardImagePolicy.normalizeMimeType("image/gif")).isEqualTo("image/gif")
    }

    @Test
    fun `svg and non-image types are rejected`() {
        assertThat(ClipboardImagePolicy.normalizeMimeType("image/svg+xml")).isNull()
        assertThat(ClipboardImagePolicy.normalizeMimeType("text/html")).isNull()
        assertThat(ClipboardImagePolicy.normalizeMimeType("application/octet-stream")).isNull()
        assertThat(ClipboardImagePolicy.normalizeMimeType("")).isNull()
        assertThat(ClipboardImagePolicy.normalizeMimeType(null)).isNull()
    }

    @Test
    fun `ime advertises exactly the accepted types`() {
        assertThat(ClipboardImagePolicy.imeMimeTypes().toList())
            .containsExactly("image/png", "image/jpeg", "image/gif", "image/webp")
        assertThat(ClipboardImagePolicy.imeMimeTypes().toList()).doesNotContain("image/svg+xml")
    }

    @Test
    fun `first allowed type skips unsupported candidates`() {
        assertThat(ClipboardImagePolicy.firstAllowedMimeType(listOf(null, "text/plain", "image/png")))
            .isEqualTo("image/png")
        assertThat(ClipboardImagePolicy.firstAllowedMimeType(listOf("image/svg+xml"))).isNull()
    }

    @Test
    fun `bounded read returns bytes up to the limit and null beyond it`() {
        val data = ByteArray(100) { it.toByte() }
        assertThat(ClipboardImagePolicy.readBounded(ByteArrayInputStream(data), maxBytes = 100)).isEqualTo(data)
        assertThat(ClipboardImagePolicy.readBounded(ByteArrayInputStream(data), maxBytes = 99)).isNull()
    }

    @Test
    fun `empty stream is not an image`() {
        assertThat(ClipboardImagePolicy.readBounded(ByteArrayInputStream(ByteArray(0)))).isNull()
    }

    @Test
    fun `bridge source trust is the configured main frame only`() {
        val policy = WebTrustPolicy(
            urlPolicy = UrlPolicy(setOf("hermes.example.com")),
            configuredWebUiUrl = "https://hermes.example.com",
            configuredDashboardUrl = ""
        )
        val page = "https://hermes.example.com/session/1"
        assertThat(policy.isTrustedNotificationBridgeSource("https://hermes.example.com", true, page)).isTrue()
        assertThat(policy.isTrustedNotificationBridgeSource("https://hermes.example.com", false, page)).isFalse()
        assertThat(policy.isTrustedNotificationBridgeSource("https://evil.example.org", true, page)).isFalse()
        assertThat(
            policy.isTrustedNotificationBridgeSource("https://hermes.example.com", true, "https://evil.example.org/")
        ).isFalse()
    }
}
