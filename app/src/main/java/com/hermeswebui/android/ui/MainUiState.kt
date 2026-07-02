package com.hermeswebui.android.ui

import com.hermeswebui.android.data.AppSettings

data class ServerValidationUiState(
    val isChecking: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    /** Optional multi-line diagnostic block (HTTP status, snippet, etc.) shown verbatim under the message. */
    val details: String? = null
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
    val blockScreenshotsEnabled: Boolean = false,
    val appUpdateAlertsEnabled: Boolean = false,
    val automaticAppUpdateChecksEnabled: Boolean = false,
    val appUpdateStatus: String? = null,
    val appUpdateVersion: String? = null,
    val appUpdateReleaseUrl: String? = null,
    val appUpdateDownloadUrl: String? = null,
    val appUpdateFileName: String? = null,
    val appUpdateReleaseNotes: String? = null,
    val serverValidation: ServerValidationUiState = ServerValidationUiState()
)
