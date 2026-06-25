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
                {"version":"0.9.0","initialized":true}
            """.trimIndent()
        )

        assertThat(result.isReady).isTrue()
    }

    @Test
    fun `initial setup payload is rejected`() {
        val result = HermesApiClient.interpretServerStatusResponse(
            httpStatus = 200,
            contentType = "application/json",
            rawBody = """
                {"initialized":false,"status":"setup"}
            """.trimIndent()
        )

        assertThat(result.isReady).isFalse()
        assertThat(result.message).contains("initial setup")
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
    }
}