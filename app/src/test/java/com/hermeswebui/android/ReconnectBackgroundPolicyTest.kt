package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.background.ReconnectBackgroundPolicy
import org.junit.Test

class ReconnectBackgroundPolicyTest {
    @Test
    fun `foreground service can run for trusted session activity without reconnecting`() {
        assertThat(
            ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = true,
                activityVisible = false,
                isReconnecting = false,
                sseTransportEnabled = true,
                hasSessionId = true
            )
        ).isTrue()

        assertThat(
            ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = true,
                activityVisible = false,
                isReconnecting = false,
                sseTransportEnabled = false,
                hasSessionId = true
            )
        ).isFalse()

        assertThat(
            ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = true,
                activityVisible = true,
                isReconnecting = false,
                sseTransportEnabled = true,
                hasSessionId = true
            )
        ).isFalse()
    }

    @Test
    fun `keepAlive true only when toggle on and app backgrounded and reconnecting`() {
        assertThat(
            ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = true,
                activityVisible = false,
                isReconnecting = true
            )
        ).isTrue()

        assertThat(
            ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = false,
                activityVisible = false,
                isReconnecting = true
            )
        ).isFalse()

        assertThat(
            ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = true,
                activityVisible = true,
                isReconnecting = true
            )
        ).isFalse()

        assertThat(
            ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = true,
                activityVisible = false,
                isReconnecting = false
            )
        ).isFalse()
    }

    @Test
    fun `shouldCancelAutoRetryOnStop mirrors inverse keepAlive policy`() {
        val cases = listOf(
            Triple(true, false, true),
            Triple(false, false, true),
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(false, true, false)
        )

        cases.forEach { (enabled, visible, reconnecting) ->
            val keepAlive = ReconnectBackgroundPolicy.shouldKeepAlive(
                backgroundReconnectEnabled = enabled,
                activityVisible = visible,
                isReconnecting = reconnecting
            )
            val cancelAutoRetry = ReconnectBackgroundPolicy.shouldCancelAutoRetryOnStop(
                backgroundReconnectEnabled = enabled,
                activityVisible = visible,
                isReconnecting = reconnecting
            )
            assertThat(cancelAutoRetry).isEqualTo(!keepAlive)
        }
    }

    @Test
    fun `foreground service parity stays derived from visibility reconnect toggle and session signals`() {
        data class Case(
            val enabled: Boolean,
            val visible: Boolean,
            val reconnecting: Boolean,
            val sseTransport: Boolean,
            val hasSessionId: Boolean,
            val expectedRun: Boolean
        )

        val cases = listOf(
            Case(enabled = true, visible = false, reconnecting = true, sseTransport = false, hasSessionId = false, expectedRun = true),
            Case(enabled = true, visible = false, reconnecting = false, sseTransport = true, hasSessionId = true, expectedRun = true),
            Case(enabled = true, visible = true, reconnecting = true, sseTransport = true, hasSessionId = true, expectedRun = false),
            Case(enabled = false, visible = false, reconnecting = true, sseTransport = true, hasSessionId = true, expectedRun = false),
            Case(enabled = true, visible = false, reconnecting = false, sseTransport = true, hasSessionId = false, expectedRun = false),
            Case(enabled = true, visible = false, reconnecting = false, sseTransport = false, hasSessionId = true, expectedRun = false)
        )

        cases.forEach { c ->
            assertThat(
                ReconnectBackgroundPolicy.shouldRunForegroundService(
                    backgroundReconnectEnabled = c.enabled,
                    activityVisible = c.visible,
                    isReconnecting = c.reconnecting,
                    sseTransportEnabled = c.sseTransport,
                    hasSessionId = c.hasSessionId
                )
            ).isEqualTo(c.expectedRun)
        }
    }
}
