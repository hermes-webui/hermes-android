package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.security.ClientCertificateRequestSupport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.Base64
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

    @Test
    fun `valid PKCS12 with the right password yields a private key and certificate chain`() {
        val result = ClientCertificateRequestSupport.loadPkcs12(
            stream = ByteArrayInputStream(testPkcs12()),
            password = TEST_PASSWORD.toCharArray()
        )

        assertThat(result).isNotNull()
        assertThat(result!!.certChain).isNotEmpty()
        assertThat(result.certChain.first().subjectX500Principal.name).isEqualTo("CN=hermes-test")
    }

    @Test
    fun `mistyped password fails closed instead of throwing`() {
        // The most likely real-world mistake: importing a valid .p12 and typing the password wrong.
        val result = ClientCertificateRequestSupport.loadPkcs12(
            stream = ByteArrayInputStream(testPkcs12()),
            password = "wrong-pass".toCharArray()
        )

        assertThat(result).isNull()
    }

    @Test
    fun `empty password on a protected store fails closed`() {
        val result = ClientCertificateRequestSupport.loadPkcs12(
            stream = ByteArrayInputStream(testPkcs12()),
            password = CharArray(0)
        )

        assertThat(result).isNull()
    }

    private fun testPkcs12(): ByteArray = Base64.getDecoder().decode(TEST_PKCS12_BASE64)

    private companion object {
        const val TEST_PASSWORD = "test-pass"

        // Throwaway self-signed RSA key (CN=hermes-test) generated only for this test; protects nothing.
        const val TEST_PKCS12_BASE64 =
        "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"
    }
}
