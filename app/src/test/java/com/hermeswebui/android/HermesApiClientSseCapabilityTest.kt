package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.HermesApiClient
import org.junit.Test

class HermesApiClientSseCapabilityTest {
    @Test
    fun `status flag true returns session sse enabled`() {
        val result = HermesApiClient.decideSseCapability(
            statusReportsSse = true,
            statusHttpStatus = 200,
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
            statusHttpStatus = 200,
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
            statusHttpStatus = 200,
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
            statusHttpStatus = 200,
            gatewayEnabled = false,
            gatewayOk = false,
            gatewayHttpStatus = 200,
            reconnectHttpStatus = 200,
            reconnectContentType = "application/json"
        )

        assertThat(result).isEqualTo(HermesApiClient.SseCapability.FEATURE_DISABLED)
    assertThat(result.keepsSessionTransportEnabled).isTrue()
    }

    @Test
    fun `gateway 404 returns feature disabled when reconnect unavailable`() {
        val result = HermesApiClient.decideSseCapability(
            statusReportsSse = false,
            statusHttpStatus = 200,
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
            statusHttpStatus = null,
            gatewayEnabled = null,
            gatewayOk = null,
            gatewayHttpStatus = null,
            reconnectHttpStatus = null,
            reconnectContentType = null
        )

        assertThat(result).isEqualTo(HermesApiClient.SseCapability.NONE)
    assertThat(result.keepsSessionTransportEnabled).isFalse()
    }

    @Test
    fun `auth-challenged probes return auth required and keep transport enabled`() {
        // Issue #75 regression: password/OIDC servers answer unauthenticated probes with 401.
        // That must surface as "capability unverified" — never NONE, which disables transport.
        val result = HermesApiClient.decideSseCapability(
            statusReportsSse = false,
            statusHttpStatus = 401,
            gatewayEnabled = null,
            gatewayOk = null,
            gatewayHttpStatus = 401,
            reconnectHttpStatus = 401,
            reconnectContentType = null
        )

        assertThat(result).isEqualTo(HermesApiClient.SseCapability.AUTH_REQUIRED)
        assertThat(result.keepsSessionTransportEnabled).isTrue()
    }

    @Test
    fun `forbidden probes return auth required and keep transport enabled`() {
        val result = HermesApiClient.decideSseCapability(
            statusReportsSse = false,
            statusHttpStatus = 403,
            gatewayEnabled = null,
            gatewayOk = null,
            gatewayHttpStatus = 403,
            reconnectHttpStatus = 403,
            reconnectContentType = null
        )

        assertThat(result).isEqualTo(HermesApiClient.SseCapability.AUTH_REQUIRED)
        assertThat(result.keepsSessionTransportEnabled).isTrue()
    }

    @Test
    fun `authenticated reconnect stream wins when other probes were auth challenged`() {
        // Once the session cookie is attached, the reconnect probe answers 200 event-stream
        // even if an earlier unauthenticated status probe saw a 401: capability is proven.
        val result = HermesApiClient.decideSseCapability(
            statusReportsSse = false,
            statusHttpStatus = 401,
            gatewayEnabled = null,
            gatewayOk = null,
            gatewayHttpStatus = 401,
            reconnectHttpStatus = 200,
            reconnectContentType = "text/event-stream"
        )

        assertThat(result).isEqualTo(HermesApiClient.SseCapability.RECONNECT_STREAM_AVAILABLE)
    }

    @Test
    fun `explicitly disabled gateway wins over auth challenge on other probes`() {
        // An authenticated enabled=false response is a definitive server statement about the
        // feature; a 401 on another endpoint only means that endpoint needs sign-in.
        val result = HermesApiClient.decideSseCapability(
            statusReportsSse = false,
            statusHttpStatus = 401,
            gatewayEnabled = false,
            gatewayOk = false,
            gatewayHttpStatus = 200,
            reconnectHttpStatus = 401,
            reconnectContentType = null
        )

        assertThat(result).isEqualTo(HermesApiClient.SseCapability.FEATURE_DISABLED)
    }
}