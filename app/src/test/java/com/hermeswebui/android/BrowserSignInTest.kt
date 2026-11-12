package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.junit.Test

class BrowserSignInTest {
    @Test
    fun `auth tab accepts only https callbacks on the default port without credentials`() {
        assertThat(BrowserSignIn.authTabCallback("https://Auth.Example.test/api/oauth/callback/pocketid"))
            .isEqualTo(BrowserSignIn.AuthTabCallback("auth.example.test", "/api/oauth/callback/pocketid"))
        assertThat(BrowserSignIn.authTabCallback("https://hermes.example.test:443"))
            .isEqualTo(BrowserSignIn.AuthTabCallback("hermes.example.test", "/"))
        for (unsupported in listOf(
            "http://auth.example.test/cb",
            "https://auth.example.test:8443/cb",
            "https://user:pw@auth.example.test/cb",
            "com.example.app:/cb",
            "https:///cb",
            "not a url"
        )) {
            assertThat(BrowserSignIn.authTabCallback(unsupported)).isNull()
        }
    }

    @Test
    fun `assetlinks statement delegates url handling to this exact package and certificate`() {
        val fingerprint = BrowserSignIn.sha256Fingerprint(ByteArray(32) { it.toByte() })
        assertThat(fingerprint).startsWith("00:01:02:")
        assertThat(fingerprint.split(':')).hasSize(32)

        val statements = JSONArray(BrowserSignIn.assetLinksJson("com.hermeswebui.android", listOf(fingerprint)))
        assertThat(statements.length()).isEqualTo(1)
        val statement = statements.getJSONObject(0)
        assertThat(statement.getJSONArray("relation").getString(0))
            .isEqualTo("delegate_permission/common.handle_all_urls")
        val target = statement.getJSONObject("target")
        assertThat(target.getString("namespace")).isEqualTo("android_app")
        assertThat(target.getString("package_name")).isEqualTo("com.hermeswebui.android")
        assertThat(target.getJSONArray("sha256_cert_fingerprints").getString(0)).isEqualTo(fingerprint)
    }
}
