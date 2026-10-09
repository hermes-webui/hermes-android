package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.background.ReconnectBackgroundPolicy
import com.hermeswebui.android.background.ReconnectSessionStreamSupport
import org.junit.Test

class ReconnectSessionStreamSupportTest {
    private val baseUrl = "https://hermes.example.com"

    @Test
    fun `session id is derived from WebUI session route`() {
        assertThat(
            ReconnectSessionStreamSupport.sessionIdFromUrl("$baseUrl/session/session_123")
        ).isEqualTo("session_123")
    }

    @Test
    fun `session deep links round trip with mount prefixes and encoded ids`() {
        val servers = listOf(baseUrl, "$baseUrl/", "$baseUrl/hermes", "$baseUrl/apps/hermes/")
        val ids = listOf("abc123", "a b", "a+b", "a/b", "literal%2F", "caf\u00e9")
        servers.forEach { server ->
            ids.forEach { id ->
                assertThat(
                    ReconnectSessionStreamSupport.sessionIdFromUrl(DeepLinkRoutes.sessionUrl(server, id))
                ).isEqualTo(id)
            }
        }
    }

    @Test
    fun `query and fragment are not part of the session id`() {
        assertThat(
            ReconnectSessionStreamSupport.sessionIdFromUrl("$baseUrl/hermes/session/a%2Bb?view=chat#latest")
        ).isEqualTo("a+b")
    }

    @Test
    fun `unrelated incomplete and malformed routes have no session id`() {
        val urls = listOf(
            null, "", baseUrl, "$baseUrl/", "$baseUrl/session", "$baseUrl/session/",
            "$baseUrl/session_123", "$baseUrl/settings", "$baseUrl/a/b",
            "$baseUrl/hermes/session/", "$baseUrl/session/abc/extra",
            "$baseUrl/session//", "$baseUrl/session/%20", "$baseUrl/session/%ZZ"
        )
        urls.forEach { url ->
            assertThat(ReconnectSessionStreamSupport.sessionIdFromUrl(url)).isNull()
        }
    }

    @Test
    fun `real WebUI session enables background monitoring without reconnecting`() {
        val sessionId = ReconnectSessionStreamSupport.sessionIdFromUrl(
            DeepLinkRoutes.sessionUrl("$baseUrl/hermes", "session_123")
        )
        assertThat(
            ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = true,
                activityVisible = false,
                isReconnecting = false,
                sseTransportEnabled = true,
                hasSessionId = sessionId != null
            )
        ).isTrue()
    }

    @Test
    fun `activity summary event maps to notification summary and route`() {
        val update = ReconnectSessionStreamSupport.notificationUpdateForEvent(
            baseUrl = baseUrl,
            fallbackTargetUrl = "$baseUrl/session/session_123",
            eventName = "activity_summary",
            rawData = """
                {"route":"/session/session_456","summary":"Wrote the migration and verified the build."}
            """.trimIndent()
        )

        assertThat(update).isNotNull()
        assertThat(update?.body).isEqualTo("Wrote the migration and verified the build.")
        assertThat(update?.targetUrl).isEqualTo("$baseUrl/session/session_456")
        assertThat(update?.isTerminal).isFalse()
    }

    @Test
    fun `task completion event formats error summaries`() {
        val update = ReconnectSessionStreamSupport.notificationUpdateForEvent(
            baseUrl = baseUrl,
            fallbackTargetUrl = "$baseUrl/session/session_123",
            eventName = "bg_task_complete",
            rawData = """
                {"summary":"Tests failed in app module","status":"error"}
            """.trimIndent()
        )

        assertThat(update).isNotNull()
        assertThat(update?.body).isEqualTo("Hermes reported an error: Tests failed in app module")
        assertThat(update?.targetUrl).isEqualTo("$baseUrl/session/session_123")
        assertThat(update?.isTerminal).isTrue()
    }

    @Test
    fun `turn started event produces generic progress copy`() {
        val update = ReconnectSessionStreamSupport.notificationUpdateForEvent(
            baseUrl = baseUrl,
            fallbackTargetUrl = "$baseUrl/session/session_123",
            eventName = "server_turn_started",
            rawData = """
                {"session_id":"session_123","input_type":"user_message"}
            """.trimIndent()
        )

        assertThat(update).isNotNull()
        assertThat(update?.body).isEqualTo("Hermes started working on a user_message request.")
        assertThat(update?.targetUrl).isEqualTo("$baseUrl/session/session_123")
        assertThat(update?.isTerminal).isFalse()
    }

    @Test
    fun `approval event maps to safe approval copy`() {
        val update = ReconnectSessionStreamSupport.notificationUpdateForEvent(
            baseUrl = baseUrl,
            fallbackTargetUrl = "$baseUrl/session/session_123",
            eventName = "approval_required",
            rawData = """
                {"route":"/session/session_123","approval_id":"approval_123","description":"Allow write access to app/src/main?","choices":["once","session","always","deny"]}
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
            fallbackTargetUrl = "$baseUrl/session/session_123",
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