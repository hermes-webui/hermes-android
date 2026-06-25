package com.hermeswebui.android.ui

import com.hermeswebui.android.data.AppSettings

data class ServerValidationUiState(
    val isChecking: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false
)

data class MainUiState(
    val settings: AppSettings,
    val isLoading: Boolean = true,
    val hasLoadedContent: Boolean = false,
    val isOffline: Boolean = false,
    val isReconnecting: Boolean = false,
    val errorMessage: String? = null,
    val isSettingsVisible: Boolean = false,
    val pendingShareBanner: String? = null,
    val currentUrl: String = settings.serverUrl,
    val backgroundReconnectEnabled: Boolean = false,
    val backgroundActivityFullTextEnabled: Boolean = false,
    val reconnectPollIntervalSeconds: Int = 1,
    val sseTransportEnabled: Boolean = false,
    val sseSupportStatus: String? = null,
    val debugLoggingEnabled: Boolean = false,
    val serverValidation: ServerValidationUiState = ServerValidationUiState()
)
