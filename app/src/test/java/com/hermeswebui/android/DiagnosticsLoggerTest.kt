package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.DiagnosticsLogger
import org.junit.Test

class DiagnosticsLoggerTest {
    @Test
    fun `originOnly strips path query and fragment`() {
        val origin = DiagnosticsLogger.originOnly(
            "https://Hermes.Example.com:8787/session-123?token=secret#fragment"
        )

        assertThat(origin).isEqualTo("https://hermes.example.com:8787")
    }

    @Test
    fun `pathOnly strips query and fragment`() {
        val path = DiagnosticsLogger.pathOnly(
            "https://hermes.example.com:8787/api/status?token=secret#fragment"
        )

        assertThat(path).isEqualTo("/api/status")
    }

    @Test
    fun `diagnostic url helpers ignore non web urls`() {
        assertThat(DiagnosticsLogger.originOnly("javascript:alert(1)")).isEmpty()
        assertThat(DiagnosticsLogger.pathOnly("not a url")).isEmpty()
    }
}
