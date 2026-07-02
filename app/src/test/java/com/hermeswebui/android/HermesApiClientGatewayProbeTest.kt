package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.HermesApiClient
import org.junit.Test

/** Regression guard for gateway-probe `enabled` absent-vs-false classification (committee P3). */
class HermesApiClientGatewayProbeTest {
    @Test
    fun `absent enabled flag is null not false`() {
        val result = HermesApiClient.interpretGatewayProbe(200, """{"ok":true}""")
        assertThat(result.enabled).isNull()
        assertThat(result.ok).isTrue()
    }

    @Test
    fun `explicit enabled false stays false`() {
        val result = HermesApiClient.interpretGatewayProbe(200, """{"enabled":false,"ok":true}""")
        assertThat(result.enabled).isFalse()
    }

    @Test
    fun `enabled and ok true are both true`() {
        val result = HermesApiClient.interpretGatewayProbe(200, """{"enabled":true,"ok":true}""")
        assertThat(result.enabled).isTrue()
        assertThat(result.ok).isTrue()
    }

    @Test
    fun `non json body yields null flags and preserves status`() {
        val result = HermesApiClient.interpretGatewayProbe(404, "not json")
        assertThat(result.enabled).isNull()
        assertThat(result.ok).isNull()
        assertThat(result.httpStatus).isEqualTo(404)
    }
}
