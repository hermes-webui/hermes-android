package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.background.ReconnectSessionStreamSupport
import org.junit.Test

class ReconnectSessionStreamSupportTest {
    private val baseUrl = "https://hermes.example.com"

    @Test
    fun `session id is derived from single-segment Hermes session route`() {
        assertThat(
            ReconnectSessionStreamSupport.sessionIdFromUrl("https://hermes.example.com/session_123")
        ).isEqualTo("session_123")
    }

    @Test
    fun `session id is not derived from multi-segment route`() {
        assertThat(
            ReconnectSessionStreamSupport.sessionIdFromUrl("https://hermes.example.com/a/b")
        ).isNull()
    }

    @Test
    fun `activity summary event maps to notification summary and route`() {
        val update = ReconnectSessionStreamSupport.notificationUpdateForEvent(
            baseUrl = baseUrl,
            fallbackTargetUrl = "$baseUrl/session_123",
            eventName = "activity_summary",
            rawData = """
                {"route":"/session_456","summary":"Wrote the migration and verified the build."}
            """.trimIndent()
        )

        assertThat(update).isNotNull()
        assertThat(update?.body).isEqualTo("Wrote the migration and verified the build.")
        assertThat(update?.targetUrl).isEqualTo("https://hermes.example.com/session_456")
        assertThat(update?.isTerminal).isFalse()
    }

    @Test
    fun `task completion event formats error summaries`() {
        val update = ReconnectSessionStreamSupport.notificationUpdateForEvent(
            baseUrl = baseUrl,
            fallbackTargetUrl = "$baseUrl/session_123",
            eventName = "bg_task_complete",
            rawData = """
                {"summary":"Tests failed in app module","status":"error"}
            """.trimIndent()
        )

        assertThat(update).isNotNull()
        assertThat(update?.body).isEqualTo("Hermes reported an error: Tests failed in app module")
        assertThat(update?.targetUrl).isEqualTo("$baseUrl/session_123")
        assertThat(update?.isTerminal).isTrue()
    }

    @Test
    fun `turn started event produces generic progress copy`() {
        val update = ReconnectSessionStreamSupport.notificationUpdateForEvent(
            baseUrl = baseUrl,
            fallbackTargetUrl = "$baseUrl/session_123",
            eventName = "server_turn_started",
            rawData = """
                {"session_id":"session_123","input_type":"user_message"}
            """.trimIndent()
        )

        assertThat(update).isNotNull()
        assertThat(update?.body).isEqualTo("Hermes started working on a user_message request.")
        assertThat(update?.targetUrl).isEqualTo("$baseUrl/session_123")
        assertThat(update?.isTerminal).isFalse()
    }

    @Test
    fun `approval event maps to safe approval copy`() {
        val update = ReconnectSessionStreamSupport.notificationUpdateForEvent(
            baseUrl = baseUrl,
            fallbackTargetUrl = "$baseUrl/session_123",
            eventName = "approval_required",
            rawData = """
                {"route":"/session_123","approval_id":"approval_123","description":"Allow write access to app/src/main?","choices":["once","session","always","deny"]}
            """.trimIndent()
        )

        assertThat(update).isNotNull()
        assertThat(update?.body).isEqualTo("Allow write access to app/src/main?")
        assertThat(update?.isTerminal).isFalse()
        assertThat(update?.approvalRequest?.approvalId).isEqualTo("approval_123")
        assertThat(update?.approvalRequest?.choices).containsExactly("once", "session", "always", "deny")
    }

    @Test
    fun `turn failed event stops activity notification`() {
        val update = ReconnectSessionStreamSupport.notificationUpdateForEvent(
            baseUrl = baseUrl,
            fallbackTargetUrl = "$baseUrl/session_123",
            eventName = "turn_failed",
            rawData = """
                {"error":"Connection lost while waiting for the model."}
            """.trimIndent()
        )

        assertThat(update).isNotNull()
        assertThat(update?.body).isEqualTo("Hermes reported an error: Connection lost while waiting for the model.")
        assertThat(update?.isTerminal).isTrue()
    }
}