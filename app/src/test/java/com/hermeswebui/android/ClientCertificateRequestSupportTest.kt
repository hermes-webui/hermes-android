package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.security.ClientCertificateRequestSupport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
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

    @Test
    fun `malformed PKCS12 input fails closed`() {
        val result = ClientCertificateRequestSupport.loadPkcs12(
            stream = ByteArrayInputStream("not a certificate".toByteArray()),
            password = "secret".toCharArray()
        )

        assertThat(result).isNull()
    }

    @Test
    fun `PKCS12 without a private key fails closed`() {
        val password = "secret".toCharArray()
        val emptyStore = KeyStore.getInstance("PKCS12").apply { load(null, password) }
        val encodedStore = ByteArrayOutputStream().also { emptyStore.store(it, password) }

        val result = ClientCertificateRequestSupport.loadPkcs12(
            stream = ByteArrayInputStream(encodedStore.toByteArray()),
            password = password
        )

        assertThat(result).isNull()
    }
}
