package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeepLinkRoutesTest {
    @Test
    fun `session deep link targets the WebUI session route`() {
        assertThat(DeepLinkRoutes.sessionUrl("https://hermes.example.com", "abc123"))
            .isEqualTo("https://hermes.example.com/session/abc123")
    }

    @Test
    fun `trailing slash and unsafe characters are normalised`() {
        assertThat(DeepLinkRoutes.sessionUrl("https://hermes.example.com/", "a b"))
            .isEqualTo("https://hermes.example.com/session/a%20b")
    }
}
