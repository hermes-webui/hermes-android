package com.hermeswebui.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermeswebui.android.data.HermesApiClient
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.data.SharePayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val settingsRepository: SettingsRepository,
    private val defaultUrl: String,
    private val defaultDashboardUrl: String
) : ViewModel() {
    private val settings = settingsRepository.getSettings(defaultUrl, defaultDashboardUrl)

    private val _uiState = MutableStateFlow(MainUiState(settings = settings))
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var sharedText: String? = null
    private var sharedFileUris: List<String> = emptyList()

    fun onPageStarted(url: String?) {
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                isOffline = false,
                currentUrl = url ?: it.currentUrl
            )
        }
    }

    fun onPageFinished(url: String?, rememberLastUrl: Boolean = true) {
        val next = url ?: _uiState.value.currentUrl
        if (rememberLastUrl) {
            settingsRepository.saveLastLoadedUrl(next)
        }
        _uiState.update { it.copy(isLoading = false, currentUrl = next) }
    }

    fun onPageError(message: String, isOffline: Boolean) {
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = message,
                isOffline = isOffline
            )
        }
        // If the device appears online but the page failed, probe /api/status to
        // distinguish "server is down" from a transient content/navigation error.
        // /api/status is the public liveness endpoint on Hermes WebUI; no auth needed.
        if (!isOffline) {
            val serverUrl = _uiState.value.settings.serverUrl
            if (serverUrl.isNotBlank()) {
                viewModelScope.launch {
                    if (!HermesApiClient.isServerReachable(serverUrl)) {
                        _uiState.update { it.copy(isOffline = true) }
                    }
                }
            }
        }
    }

    fun consumeSharePayload(payload: SharePayload) {
        sharedText = payload.sharedText?.takeIf { it.isNotBlank() }
        sharedFileUris = payload.fileUris.map { it.toString() }
        val banner = when {
            sharedText != null && sharedFileUris.isNotEmpty() -> "Shared text copied to clipboard, plus ${sharedFileUris.size} file(s) ready"
            sharedText != null -> "Shared text copied to clipboard"
            sharedFileUris.isNotEmpty() -> "${sharedFileUris.size} shared file(s) ready for upload"
            else -> null
        }
        _uiState.update { it.copy(pendingShareBanner = banner) }
    }

    fun consumeSharedText(): String? {
        val value = sharedText
        sharedText = null
        return value
    }

    fun consumeSharedFileUris(): List<String> {
        val staged = sharedFileUris
        sharedFileUris = emptyList()
        return staged
    }

    fun dismissShareBanner() {
        _uiState.update { it.copy(pendingShareBanner = null) }
    }

    fun openSettings() {
        _uiState.update { it.copy(isSettingsVisible = true) }
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsVisible = false) }
    }

    fun saveAppUrls(serverUrl: String, dashboardUrl: String) {
        settingsRepository.saveAppUrls(serverUrl, dashboardUrl)
        val refreshed = settingsRepository.getSettings(defaultUrl, defaultDashboardUrl)
        _uiState.update {
            it.copy(
                settings = refreshed,
                currentUrl = refreshed.serverUrl,
                isSettingsVisible = false,
                errorMessage = null,
                isOffline = false
            )
        }
    }

    fun resetSession() {
        settingsRepository.clearWebSession()
    }
}
