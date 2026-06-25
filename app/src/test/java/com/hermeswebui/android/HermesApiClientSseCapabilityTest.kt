package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.HermesApiClient
import org.junit.Test

class HermesApiClientSseCapabilityTest {
    @Test
    fun `status flag true returns session sse enabled`() {
        val result = HermesApiClient.decideSseCapability(
            statusReportsSse = true,
            gatewayEnabled = false,
            gatewayOk = false,
            gatewayHttpStatus = 404,
            reconnectHttpStatus = 200,
            reconnectContentType = "text/event-stream"
        )

        assertThat(result).isEqualTo(HermesApiClient.SseCapability.SESSION_SSE_ENABLED)
    }

    @Test
    fun `gateway enabled and ok returns session sse enabled`() {
        val result = HermesApiClient.decideSseCapability(
            statusReportsSse = false,
            gatewayEnabled = true,
            gatewayOk = true,
            gatewayHttpStatus = 200,
            reconnectHttpStatus = null,
            reconnectContentType = null
        )

        assertThat(result).isEqualTo(HermesApiClient.SseCapability.SESSION_SSE_ENABLED)
    }

    @Test
    fun `reconnect stream available returns reconnect stream available`() {
        val result = HermesApiClient.decideSseCapability(
            statusReportsSse = false,
            gatewayEnabled = true,
            gatewayOk = false,
            gatewayHttpStatus = 503,
            reconnectHttpStatus = 200,
            reconnectContentType = "text/event-stream; charset=utf-8"
        )

        assertThat(result).isEqualTo(HermesApiClient.SseCapability.RECONNECT_STREAM_AVAILABLE)
    }

    @Test
    fun `gateway disabled returns feature disabled when reconnect unavailable`() {
        val result = HermesApiClient.decideSseCapability(
            statusReportsSse = false,
            gatewayEnabled = false,
            gatewayOk = false,
            gatewayHttpStatus = 200,
            reconnectHttpStatus = 200,
            reconnectContentType = "application/json"
        )

        assertThat(result).isEqualTo(HermesApiClient.SseCapability.FEATURE_DISABLED)
    }

    @Test
    fun `gateway 404 returns feature disabled when reconnect unavailable`() {
        val result = HermesApiClient.decideSseCapability(
            statusReportsSse = false,
            gatewayEnabled = null,
            gatewayOk = null,
            gatewayHttpStatus = 404,
            reconnectHttpStatus = null,
            reconnectContentType = null
        )

        assertThat(result).isEqualTo(HermesApiClient.SseCapability.FEATURE_DISABLED)
    }

    @Test
    fun `network-like failures return none when no gateway signal`() {
        val result = HermesApiClient.decideSseCapability(
            statusReportsSse = false,
            gatewayEnabled = null,
            gatewayOk = null,
            gatewayHttpStatus = null,
            reconnectHttpStatus = null,
            reconnectContentType = null
        )

        assertThat(result).isEqualTo(HermesApiClient.SseCapability.NONE)
    }
}