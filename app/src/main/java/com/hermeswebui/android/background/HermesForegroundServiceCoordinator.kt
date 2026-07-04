package com.hermeswebui.android.background

import android.content.Context
import android.webkit.CookieManager
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.ui.MainUiState

class HermesForegroundServiceCoordinator(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val isTrustedNotificationTarget: (String) -> Boolean,
    private val onCancelAutoRetry: () -> Unit,
    private val onSetDebugLoggingEnabled: (Boolean) -> Unit
) {
    private var reconnectServiceRunning = false
    private var debugLoggingServiceRunning = false

    fun onUiStateChanged(state: MainUiState, activityVisible: Boolean) {
        syncReconnectForegroundService(state, activityVisible)
        syncDebugLoggingForegroundService(state.debugLoggingEnabled)
    }

    fun onActivityResumed() {
        stopReconnectForegroundService()
    }

    fun onActivityStopped(state: MainUiState, activityVisible: Boolean) {
        syncReconnectForegroundService(state, activityVisible)
        if (
            ReconnectBackgroundPolicy.shouldCancelAutoRetryOnStop(
                backgroundReconnectEnabled = state.backgroundReconnectEnabled,
                activityVisible = activityVisible,
                isReconnecting = state.isReconnecting
            )
        ) {
            onCancelAutoRetry()
        }
    }

    private fun syncReconnectForegroundService(state: MainUiState, activityVisible: Boolean) {
        val sessionId = ReconnectSessionStreamSupport.sessionIdFromUrl(state.currentUrl)
        if (
            !ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = state.backgroundReconnectEnabled,
                activityVisible = activityVisible,
                isReconnecting = state.isReconnecting,
                sseTransportEnabled = state.sseTransportEnabled,
                hasSessionId = sessionId != null
            )
        ) {
            stopReconnectForegroundService()
            return
        }
        if (reconnectServiceRunning) return

        try {
            val sessionTargetUrl = state.currentUrl.takeIf(isTrustedNotificationTarget)
            HermesReconnectService.start(
                context,
                pollIntervalSeconds = state.reconnectPollIntervalSeconds,
                serverUrl = state.settings.serverUrl,
                sessionId = sessionId,
                sessionTargetUrl = sessionTargetUrl,
                cookieHeader = CookieManager.getInstance().getCookie(state.settings.serverUrl),
                sseTransportEnabled = state.sseTransportEnabled,
                isReconnecting = state.isReconnecting,
                showFullTextOnLockScreen = state.backgroundActivityFullTextEnabled
            )
            reconnectServiceRunning = true
        } catch (_: IllegalStateException) {
            reconnectServiceRunning = false
            onCancelAutoRetry()
        } catch (_: SecurityException) {
            reconnectServiceRunning = false
            onCancelAutoRetry()
        }
    }

    private fun stopReconnectForegroundService() {
        if (!reconnectServiceRunning) return
        HermesReconnectService.stop(context)
        reconnectServiceRunning = false
    }

    private fun syncDebugLoggingForegroundService(debugLoggingEnabled: Boolean) {
        val persistedEnabled = settingsRepository.isDebugLoggingEnabled()
        if (!debugLoggingEnabled || !persistedEnabled) {
            if (debugLoggingEnabled && !persistedEnabled) {
                onSetDebugLoggingEnabled(false)
            }
            stopDebugLoggingForegroundService()
            return
        }
        if (debugLoggingServiceRunning) return

        try {
            HermesDebugLoggingService.start(context)
            debugLoggingServiceRunning = true
        } catch (_: IllegalStateException) {
            debugLoggingServiceRunning = false
            onSetDebugLoggingEnabled(false)
        } catch (_: SecurityException) {
            debugLoggingServiceRunning = false
            onSetDebugLoggingEnabled(false)
        }
    }

    private fun stopDebugLoggingForegroundService() {
        if (!debugLoggingServiceRunning) return
        HermesDebugLoggingService.stop(context)
        debugLoggingServiceRunning = false
    }
}
