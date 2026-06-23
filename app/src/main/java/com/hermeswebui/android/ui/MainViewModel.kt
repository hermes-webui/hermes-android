package com.hermeswebui.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermeswebui.android.data.HermesApiClient
import com.hermeswebui.android.data.SettingsStore
import com.hermeswebui.android.data.SharePayload
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val settingsRepository: SettingsStore,
    private val defaultUrl: String,
    private val defaultDashboardUrl: String,
    private val serverReachabilityChecker: suspend (String) -> Boolean = HermesApiClient::isServerReachable
) : ViewModel() {
    private val settings = settingsRepository.getSettings(defaultUrl, defaultDashboardUrl)

    private val _uiState = MutableStateFlow(MainUiState(settings = settings))
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /** Emits a single Unit when the auto-retry loop detects the server is back.
     *  The UI layer (MainActivity) should call webView.reload() on each emission. */
    private val _autoReloadEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val autoReloadEvent: SharedFlow<Unit> = _autoReloadEvent.asSharedFlow()

    private var autoRetryJob: Job? = null
    private var currentLoadHasMainFrameError = false

    fun onPageStarted(url: String?) {
        cancelAutoRetry()
        currentLoadHasMainFrameError = false
        _uiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                isOffline = false,
                currentUrl = url ?: it.currentUrl
            )
        }
    }

    fun onPageCommitVisible(url: String?) {
        if (currentLoadHasMainFrameError) return
        val next = url ?: _uiState.value.currentUrl
        _uiState.update {
            it.copy(
                hasLoadedContent = true,
                currentUrl = next
            )
        }
    }

    fun onUrlVisited(url: String?, rememberLastUrl: Boolean = true) {
        val next = url?.takeIf { it.isNotBlank() } ?: return
        if (!currentLoadHasMainFrameError && rememberLastUrl) {
            settingsRepository.saveLastLoadedUrl(next)
        }
        _uiState.update {
            if (it.currentUrl == next) it else it.copy(currentUrl = next)
        }
    }

    fun onPageFinished(url: String?, rememberLastUrl: Boolean = true) {
        val next = url ?: _uiState.value.currentUrl
        onUrlVisited(next, rememberLastUrl)
        _uiState.update { it.copy(isLoading = false, currentUrl = next) }
    }

    fun onPageError(message: String, isOffline: Boolean) {
        currentLoadHasMainFrameError = true
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = message,
                isOffline = isOffline
            )
        }
        startAutoRetry()
    }

    /** Starts the bounded auto-retry polling loop.
     *
     * Polls [serverReachabilityChecker] with exponential backoff starting at 1 s,
     * capped at 10 s per interval, for up to 60 s total. On the first successful
     * probe it emits [autoReloadEvent] so the UI can trigger a WebView reload.
     * Sets [MainUiState.isReconnecting] while the loop is active.
     */
    private fun startAutoRetry() {
        autoRetryJob?.cancel()
        val serverUrl = _uiState.value.settings.serverUrl
        if (serverUrl.isBlank()) return

        autoRetryJob = viewModelScope.launch {
            var intervalMs = 1_000L
            val maxIntervalMs = 10_000L
            val deadlineMs = System.currentTimeMillis() + 60_000L
            _uiState.update { it.copy(isReconnecting = true) }

            while (System.currentTimeMillis() < deadlineMs) {
                delay(intervalMs)
                if (serverReachabilityChecker(serverUrl)) {
                    _uiState.update { it.copy(isReconnecting = false) }
                    _autoReloadEvent.tryEmit(Unit)
                    return@launch
                }
                // Server still unreachable — confirm offline state and back off.
                _uiState.update { it.copy(isOffline = true) }
                intervalMs = (intervalMs * 2).coerceAtMost(maxIntervalMs)
            }

            // Timed out after ~60 s: give up, leave error screen, stop spinning.
            _uiState.update { it.copy(isReconnecting = false) }
        }
    }

    /** Cancels any active auto-retry loop and clears the reconnecting indicator. */
    fun cancelAutoRetry() {
        autoRetryJob?.cancel()
        autoRetryJob = null
        _uiState.update { it.copy(isReconnecting = false) }
    }

    /**
     * Restarts bounded auto-retry when returning to foreground and the app is
     * still showing a load error.
     */
    fun resumeAutoRetryIfNeeded() {
        val state = _uiState.value
        if (state.errorMessage == null || state.isLoading) return
        if (autoRetryJob?.isActive == true) return
        startAutoRetry()
    }

    private var sharedText: String? = null
    private var sharedFileUris: List<String> = emptyList()

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
        cancelAutoRetry()
        settingsRepository.saveAppUrls(serverUrl, dashboardUrl)
        val refreshed = settingsRepository.getSettings(defaultUrl, defaultDashboardUrl)
        _uiState.update {
            it.copy(
                settings = refreshed,
                currentUrl = refreshed.serverUrl,
                isLoading = true,
                hasLoadedContent = false,
                isSettingsVisible = false,
                errorMessage = null,
                isOffline = false
            )
        }
    }

    fun resetSession() {
        cancelAutoRetry()
        settingsRepository.clearWebSession()
        _uiState.update {
            it.copy(
                isLoading = true,
                hasLoadedContent = false,
                errorMessage = null,
                isOffline = false,
                currentUrl = it.settings.serverUrl
            )
        }
    }
}
