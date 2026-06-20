package com.hermeswebui.android.ui

import com.hermeswebui.android.data.AppSettings

data class MainUiState(
    val settings: AppSettings,
    val isLoading: Boolean = true,
    val isOffline: Boolean = false,
    val errorMessage: String? = null,
    val canRetry: Boolean = true,
    val isSettingsVisible: Boolean = false,
    val pendingShareBanner: String? = null,
    val currentUrl: String = settings.serverUrl
)
