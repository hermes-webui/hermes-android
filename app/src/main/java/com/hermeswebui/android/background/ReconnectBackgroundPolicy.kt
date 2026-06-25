package com.hermeswebui.android.background

internal object ReconnectBackgroundPolicy {
    internal fun shouldRunForegroundService(
        backgroundReconnectEnabled: Boolean,
        activityVisible: Boolean,
        isReconnecting: Boolean,
        sseTransportEnabled: Boolean,
        hasSessionId: Boolean
    ): Boolean {
        if (!backgroundReconnectEnabled || activityVisible) return false
        return isReconnecting || (sseTransportEnabled && hasSessionId)
    }

    internal fun shouldKeepAlive(
        backgroundReconnectEnabled: Boolean,
        activityVisible: Boolean,
        isReconnecting: Boolean
    ): Boolean {
        return backgroundReconnectEnabled && !activityVisible && isReconnecting
    }

    internal fun shouldCancelAutoRetryOnStop(
        backgroundReconnectEnabled: Boolean,
        activityVisible: Boolean,
        isReconnecting: Boolean
    ): Boolean {
        return !shouldKeepAlive(
            backgroundReconnectEnabled = backgroundReconnectEnabled,
            activityVisible = activityVisible,
            isReconnecting = isReconnecting
        )
    }
}
