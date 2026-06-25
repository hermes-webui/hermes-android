package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.HermesApiClient
import org.junit.Test

class HermesApiClientStatusTest {
    @Test
    fun `ready Hermes status payload is accepted`() {
        val result = HermesApiClient.interpretServerStatusResponse(
            httpStatus = 200,
            contentType = "application/json",
            rawBody = """
                {"version":"0.9.0","release_date":"2026-06-24","gateway_running":true,"authenticated":true,"setup_mode":false}
            """.trimIndent()
        )

        assertThat(result.isReady).isTrue()
        assertThat(result.status).isEqualTo(HermesApiClient.ServerReadinessStatus.READY)
    }

    @Test
    fun `initial setup payload is rejected`() {
        val result = HermesApiClient.interpretServerStatusResponse(
            httpStatus = 200,
            contentType = "application/json",
            rawBody = """
                {"version":"0.9.0","setup_mode":true,"gateway_running":false}
            """.trimIndent()
        )

        assertThat(result.isReady).isFalse()
        assertThat(result.message).contains("initial setup")
        assertThat(result.status).isEqualTo(HermesApiClient.ServerReadinessStatus.SETUP_REQUIRED)
    }

    @Test
    fun `non json response is rejected as non Hermes`() {
        val result = HermesApiClient.interpretServerStatusResponse(
            httpStatus = 200,
            contentType = "text/html",
            rawBody = "<html><body>Welcome</body></html>"
        )

        assertThat(result.isReady).isFalse()
        assertThat(result.message).contains("does not look like a ready Hermes WebUI")
        assertThat(result.status).isEqualTo(HermesApiClient.ServerReadinessStatus.NOT_HERMES)
    }

    @Test
    fun `http 503 is treated as not ready`() {
        val result = HermesApiClient.interpretServerStatusResponse(
            httpStatus = 503,
            contentType = "application/json",
            rawBody = "{}"
        )

        assertThat(result.isReady).isFalse()
        assertThat(result.message).contains("not ready yet")
        assertThat(result.status).isEqualTo(HermesApiClient.ServerReadinessStatus.SETUP_REQUIRED)
    }

    @Test
    fun `http 401 asks user to sign in instead of reporting initialization failure`() {
        val result = HermesApiClient.interpretServerStatusResponse(
            httpStatus = 401,
            contentType = "application/json",
            rawBody = "{}"
        )

        assertThat(result.isReady).isFalse()
        assertThat(result.message).contains("sign-in")
        assertThat(result.status).isEqualTo(HermesApiClient.ServerReadinessStatus.AUTH_REQUIRED)
    }

    @Test
    fun `auth protected status can be accepted when root page fingerprints as Hermes`() {
        val result = HermesApiClient.interpretHermesRootResponse(
            httpStatus = 200,
            contentType = "text/html",
            serverHeader = null,
            rawBody = "<html><head><title>Hermes WebUI</title></head><body>Sign in</body></html>"
        )

        assertThat(result).isNotNull()
        assertThat(result?.isReady).isTrue()
        assertThat(result?.status).isEqualTo(HermesApiClient.ServerReadinessStatus.READY)
    }

    @Test
    fun `legacy Hermes status payload is still accepted`() {
        val result = HermesApiClient.interpretServerStatusResponse(
            httpStatus = 200,
            contentType = "application/json",
            rawBody = """
                {"version":"0.8.1","initialized":true}
            """.trimIndent()
        )

        assertThat(result.isReady).isTrue()
    }

    @Test
    fun `bare version payload is rejected as insufficient fingerprint`() {
        val result = HermesApiClient.interpretServerStatusResponse(
            httpStatus = 200,
            contentType = "application/json",
            rawBody = """
                {"version":"1.0.0"}
            """.trimIndent()
        )

        assertThat(result.isReady).isFalse()
        assertThat(result.message).contains("does not look like a ready Hermes WebUI")
    }

    @Test
    fun `root fallback accepts Hermes server header`() {
        val result = HermesApiClient.interpretHermesRootResponse(
            httpStatus = 200,
            contentType = "text/html; charset=utf-8",
            serverHeader = "HermesWebUI/0.51.615",
            rawBody = "<html><body>anything</body></html>"
        )

        assertThat(result).isNotNull()
        assertThat(result?.isReady).isTrue()
    }

    @Test
    fun `root fallback accepts Hermes html marker when server header missing`() {
        val result = HermesApiClient.interpretHermesRootResponse(
            httpStatus = 200,
            contentType = "text/html",
            serverHeader = null,
            rawBody = "<html><head><title>Hermes WebUI</title></head><body></body></html>"
        )

        assertThat(result).isNotNull()
        assertThat(result?.isReady).isTrue()
    }

    @Test
    fun `root fallback ignores generic html`() {
        val result = HermesApiClient.interpretHermesRootResponse(
            httpStatus = 200,
            contentType = "text/html",
            serverHeader = "nginx",
            rawBody = "<html><body>Welcome</body></html>"
        )

        assertThat(result).isNull()
    }
}
