package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.security.ClientCertificateRequestSupport
import org.junit.Test

class ClientCertificateRequestSupportTest {
    @Test
    fun `server host must match the app allowlist before prompting for a client certificate`() {
        val allowlist = setOf("hermes.example.com", "localhost")

        assertThat(ClientCertificateRequestSupport.isAllowedHost("hermes.example.com", allowlist)).isTrue()
        assertThat(ClientCertificateRequestSupport.isAllowedHost("localhost", allowlist)).isTrue()
        assertThat(ClientCertificateRequestSupport.isAllowedHost("evil.example.com", allowlist)).isFalse()
        assertThat(ClientCertificateRequestSupport.isAllowedHost("", allowlist)).isFalse()
    }
}
