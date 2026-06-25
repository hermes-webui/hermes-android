package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.background.ApprovalClient
import org.junit.Test

class ApprovalClientTest {
    @Test
    fun `parse pending approval supports wrapped pending payload`() {
        val pending = ApprovalClient.parsePendingApproval(
            rawJson = """
                {
                  "pending": {
                    "approval_id": "approval_456",
                    "session_id": "session_123",
                    "choices": ["once", "deny"]
                  },
                  "pending_count": 1
                }
            """.trimIndent(),
            fallbackSessionId = null
        )

        assertThat(pending).isNotNull()
        assertThat(pending?.approvalId).isEqualTo("approval_456")
        assertThat(pending?.sessionId).isEqualTo("session_123")
        assertThat(pending?.choices).containsExactly("once", "deny")
    }

    @Test
    fun `parse pending approval falls back to root payload and default choices`() {
        val pending = ApprovalClient.parsePendingApproval(
            rawJson = """
                {
                  "approval_id": "approval_789"
                }
            """.trimIndent(),
            fallbackSessionId = "session_999"
        )

        assertThat(pending).isNotNull()
        assertThat(pending?.approvalId).isEqualTo("approval_789")
        assertThat(pending?.sessionId).isEqualTo("session_999")
        assertThat(pending?.choices).containsExactly("once", "session", "always", "deny")
    }

        @Test
        fun `parse pending approval supports queued payload variants`() {
                val pending = ApprovalClient.parsePendingApproval(
                        rawJson = """
                                {
                                    "pending_queue": [
                                        {
                                            "approval_id": "approval_q1",
                                            "session_id": "session_q",
                                            "choices_offered": ["SESSION", "deny", "session"]
                                        }
                                    ]
                                }
                        """.trimIndent(),
                        fallbackSessionId = null
                )

                assertThat(pending).isNotNull()
                assertThat(pending?.approvalId).isEqualTo("approval_q1")
                assertThat(pending?.sessionId).isEqualTo("session_q")
                assertThat(pending?.choices).containsExactly("session", "deny")
        }
}