package com.hermeswebui.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermeswebui.android.data.HermesApiClient
import com.hermeswebui.android.data.ServerProfile
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.data.SettingsStore
import com.hermeswebui.android.data.SharePayload
import com.hermeswebui.android.update.AppUpdateCheckResult
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
import kotlin.time.Duration.Companion.milliseconds

class MainViewModel(
    private val settingsRepository: SettingsStore,
    private val settingsRepositoryImpl: SettingsRepository? = null,
    private val defaultUrl: String,
    private val defaultDashboardUrl: String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val sseReconnectChecker: suspend (String) -> Boolean = HermesApiClient::isReconnectSseReachable,
    private val serverReachabilityChecker: suspend (String) -> Boolean = HermesApiClient::isServerReachable
) : ViewModel() {
    private data class DeferredPageError(
        val message: String,
        val isOffline: Boolean,
        val visibleAtMs: Long
    )

    private val settings = settingsRepository.getSettings(defaultUrl, defaultDashboardUrl)

    private val _uiState = MutableStateFlow(MainUiState(
        settings = settings,
        backgroundReconnectEnabled = settingsRepositoryImpl?.isBackgroundReconnectEnabled() ?: false,
        backgroundActivityFullTextEnabled = settingsRepositoryImpl?.isBackgroundActivityFullTextEnabled() ?: false,
        reconnectPollIntervalSeconds = settingsRepositoryImpl?.getReconnectPollIntervalSeconds() ?: 1,
        sseTransportEnabled = settingsRepositoryImpl?.isSseTransportEnabled() ?: false,
        debugLoggingEnabled = settingsRepositoryImpl?.isDebugLoggingEnabled() ?: false,
        appUpdateAlertsEnabled = settingsRepositoryImpl?.isAppUpdateAlertsEnabled() ?: false,
        automaticAppUpdateChecksEnabled = settingsRepositoryImpl?.isAutomaticAppUpdateChecksEnabled() ?: false
    ))
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Server profiles state
    private val _serverProfiles = MutableStateFlow<List<ServerProfile>>(
        settingsRepositoryImpl?.getProfiles() ?: emptyList()
    )
    val serverProfiles: StateFlow<List<ServerProfile>> = _serverProfiles.asStateFlow()

    /** Emits a single Unit when the auto-retry loop detects the server is back.
     *  The UI layer (MainActivity) should call webView.reload() on each emission. */
    private val _autoReloadEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val autoReloadEvent: SharedFlow<Unit> = _autoReloadEvent.asSharedFlow()

    private var autoRetryJob: Job? = null
    private var currentLoadHasMainFrameError = false
    private var deferredPageError: DeferredPageError? = null
    private var appIsBackgrounded = false
    private var lastForegroundedAfterBackgroundAtMs: Long? = null

    companion object {
        private const val QUICK_RESUME_ERROR_GRACE_MS = 2_000L
    }

    fun onPageStarted(url: String?) {
        cancelAutoRetry(clearDeferredPageError = true)
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
        deferredPageError = null
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
        deferredPageError = null
        onUrlVisited(next, rememberLastUrl)
        _uiState.update { it.copy(isLoading = false, currentUrl = next) }
    }

    fun onPageError(message: String, isOffline: Boolean) {
        currentLoadHasMainFrameError = true
        val state = _uiState.value
        val shouldDeferErrorUi = shouldDeferErrorUi(state)
        deferredPageError = if (shouldDeferErrorUi) {
            DeferredPageError(
                message = message,
                isOffline = isOffline,
                visibleAtMs = nowMs() + QUICK_RESUME_ERROR_GRACE_MS
            )
        } else {
            null
        }

        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = if (shouldDeferErrorUi) null else message,
                isOffline = isOffline,
                isReconnecting = shouldDeferErrorUi
            )
        }
        startAutoRetry()
    }

    fun onAppBackgrounded() {
        appIsBackgrounded = true
    }

    fun onAppForegrounded() {
        if (appIsBackgrounded) {
            lastForegroundedAfterBackgroundAtMs = nowMs()
        }
        appIsBackgrounded = false
    }

    /** Starts the bounded auto-retry loop.
     *
     * When SSE transport is enabled, the loop first tries the lightweight native
     * reconnect SSE stream and falls back to the plain reachability check if the
     * stream handshake fails. Otherwise it uses fixed-interval polling only.
     * The loop runs for up to 60 s total. On the first successful reconnect signal
     * it emits [autoReloadEvent] so the UI can trigger a WebView reload.
     * Sets [MainUiState.isReconnecting] while the loop is active.
     */
    private fun startAutoRetry() {
        autoRetryJob?.cancel()
        val serverUrl = _uiState.value.settings.serverUrl
        if (serverUrl.isBlank()) return
        val deferredErrorSnapshot = deferredPageError
        val useSseTransport = _uiState.value.sseTransportEnabled

        autoRetryJob = viewModelScope.launch {
            val maxRetryDurationMs = 60_000L
            // Bound the retry budget on WALL-CLOCK, not just accumulated delay(): the per-iteration
            // probe in isReconnectSignalAvailable() can block up to the socket timeout (~6s) and was
            // never counted, so a hard-down server stretched the documented ~60s budget into minutes
            // of spinner + network/battery churn.
            val startedAt = nowMs()
            val pollIntervalMs = (_uiState.value.reconnectPollIntervalSeconds.coerceAtLeast(1) * 1_000L)
            var msUntilNextProbe = if (useSseTransport) 0L else pollIntervalMs
            _uiState.update { it.copy(isReconnecting = true) }
            promoteDeferredErrorIfReady(deferredErrorSnapshot)

            while (nowMs() - startedAt < maxRetryDurationMs) {
                val remainingRetryBudgetMs = maxRetryDurationMs - (nowMs() - startedAt)
                val untilDeferredVisibleMs = deferredErrorSnapshot?.let {
                    val remainingMs = it.visibleAtMs - nowMs()
                    if (remainingMs > 0L && _uiState.value.errorMessage == null) {
                        remainingMs
                    } else {
                        Long.MAX_VALUE
                    }
                } ?: Long.MAX_VALUE
                val waitMs = minOf(msUntilNextProbe, untilDeferredVisibleMs, remainingRetryBudgetMs)

                if (waitMs > 0L) {
                    delay(waitMs.milliseconds)
                    msUntilNextProbe -= waitMs
                }

                promoteDeferredErrorIfReady(deferredErrorSnapshot)

                if (msUntilNextProbe > 0L) continue

                if (isReconnectSignalAvailable(serverUrl, useSseTransport)) {
                    deferredPageError = null
                    _uiState.update { it.copy(isReconnecting = false) }
                    _autoReloadEvent.tryEmit(Unit)
                    return@launch
                }

                // Server still unreachable — confirm offline state and back off.
                _uiState.update { it.copy(isOffline = true) }
                msUntilNextProbe = pollIntervalMs
            }

            // Timed out after ~60 s: give up, leave error screen, stop spinning.
            promoteDeferredErrorIfReady(deferredErrorSnapshot, force = true)
            _uiState.update { it.copy(isReconnecting = false) }
        }
    }

    private suspend fun isReconnectSignalAvailable(serverUrl: String, useSseTransport: Boolean): Boolean {
        if (useSseTransport && sseReconnectChecker(serverUrl)) {
            return true
        }
        return serverReachabilityChecker(serverUrl)
    }

    /** Cancels any active auto-retry loop and clears the reconnecting indicator. */
    fun cancelAutoRetry(clearDeferredPageError: Boolean = false) {
        autoRetryJob?.cancel()
        autoRetryJob = null
        if (clearDeferredPageError) {
            deferredPageError = null
        }
        _uiState.update { it.copy(isReconnecting = false) }
    }

    /**
     * Restarts bounded auto-retry when returning to foreground and the app is
     * still showing a load error.
     */
    fun resumeAutoRetryIfNeeded() {
        val state = _uiState.value
        if ((state.errorMessage == null && deferredPageError == null) || state.isLoading) return
        if (autoRetryJob?.isActive == true) return
        startAutoRetry()
    }

    private fun shouldDeferErrorUi(state: MainUiState): Boolean {
        val lastForegrounded = lastForegroundedAfterBackgroundAtMs ?: return false
        if (appIsBackgrounded) return false
        if (!state.hasLoadedContent) return false
        if (state.errorMessage != null) return false
        return nowMs() - lastForegrounded <= QUICK_RESUME_ERROR_GRACE_MS
    }

    private fun promoteDeferredErrorIfReady(
        pending: DeferredPageError?,
        force: Boolean = false
    ) {
        if (pending == null) return
        if (!force && nowMs() < pending.visibleAtMs) return
        deferredPageError = null
        _uiState.update { state ->
            if (state.errorMessage != null) {
                state
            } else {
                state.copy(
                    errorMessage = pending.message,
                    isOffline = pending.isOffline
                )
            }
        }
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

     // Server profile management
     fun addServerProfile(name: String, url: String): ServerProfile? {
         return settingsRepositoryImpl?.let {
             val profile = it.addProfile(name, url)
             refreshProfiles()
             profile
         }
     }

     fun deleteServerProfile(profileId: String) {
         settingsRepositoryImpl?.deleteProfile(profileId)
         refreshProfiles()
     }

     fun renameServerProfile(profileId: String, newName: String) {
         settingsRepositoryImpl?.renameProfile(profileId, newName)
         refreshProfiles()
     }

     fun updateServerProfile(profileId: String, newName: String, newUrl: String) {
         settingsRepositoryImpl?.updateProfile(profileId, newName, newUrl)
         refreshProfiles()
     }

     fun switchServerProfile(profileId: String) {
         val repo = settingsRepositoryImpl ?: return
         val profile = repo.getProfiles().firstOrNull { it.id == profileId } ?: return
         repo.setActiveProfile(profileId)
         val dashboardUrl = _uiState.value.settings.dashboardUrl
         settingsRepository.saveAppUrls(profile.url, dashboardUrl)
         val refreshed = settingsRepository.getSettings(defaultUrl, defaultDashboardUrl)
         refreshProfiles()
         // Trigger a reload with the new server URL
         _uiState.update {
             it.copy(
                 settings = refreshed,
                 currentUrl = profile.url,
                 isLoading = true,
                 hasLoadedContent = false,
                 errorMessage = null,
                 isOffline = false
             )
         }
     }

     private fun refreshProfiles() {
         settingsRepositoryImpl?.let { repo ->
             _serverProfiles.update { repo.getProfiles() }
         }
     }

     fun openSettings() {
        _uiState.update { it.copy(isSettingsVisible = true) }
    }

    fun openSettingsWithServerValidation(message: String, isError: Boolean = true, details: String? = null) {
        _uiState.update {
            it.copy(
                isSettingsVisible = true,
                isLoading = false,
                serverValidation = ServerValidationUiState(
                    isChecking = false,
                    message = message,
                    isError = isError,
                    details = details
                )
            )
        }
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsVisible = false) }
    }

    fun setServerValidationState(isChecking: Boolean, message: String?, isError: Boolean, details: String? = null) {
        _uiState.update {
            it.copy(
                serverValidation = ServerValidationUiState(
                    isChecking = isChecking,
                    message = message,
                    isError = isError,
                    details = details
                )
            )
        }
    }

    fun clearServerValidationState() {
        _uiState.update { it.copy(serverValidation = ServerValidationUiState()) }
    }

    fun setBackgroundReconnectEnabled(enabled: Boolean) {
        settingsRepositoryImpl?.setBackgroundReconnectEnabled(enabled)
        _uiState.update { it.copy(backgroundReconnectEnabled = enabled) }
    }

    fun setBackgroundActivityFullTextEnabled(enabled: Boolean) {
        settingsRepositoryImpl?.setBackgroundActivityFullTextEnabled(enabled)
        _uiState.update { it.copy(backgroundActivityFullTextEnabled = enabled) }
    }

    fun setReconnectPollIntervalSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(1, 10)
        settingsRepositoryImpl?.setReconnectPollIntervalSeconds(clamped)
        _uiState.update { it.copy(reconnectPollIntervalSeconds = clamped) }
    }

    fun setDebugLoggingEnabled(enabled: Boolean) {
        settingsRepositoryImpl?.setDebugLoggingEnabled(enabled)
        _uiState.update { it.copy(debugLoggingEnabled = enabled) }
    }

    fun setAppUpdateAlertsEnabled(enabled: Boolean) {
        settingsRepositoryImpl?.setAppUpdateAlertsEnabled(enabled)
        _uiState.update { it.copy(appUpdateAlertsEnabled = enabled) }
    }

    fun setAutomaticAppUpdateChecksEnabled(enabled: Boolean) {
        settingsRepositoryImpl?.setAutomaticAppUpdateChecksEnabled(enabled)
        _uiState.update { it.copy(automaticAppUpdateChecksEnabled = enabled) }
    }

    fun setAppUpdateStatus(status: String?) {
        _uiState.update { it.copy(appUpdateStatus = status) }
    }

    fun setAvailableAppUpdate(update: AppUpdateCheckResult.Available) {
        _uiState.update {
            it.copy(
                appUpdateStatus = "Version ${update.version} is available.",
                appUpdateVersion = update.version,
                appUpdateReleaseUrl = update.releaseUrl,
                appUpdateDownloadUrl = update.downloadUrl,
                appUpdateFileName = update.fileName,
                appUpdateReleaseNotes = update.releaseNotes
            )
        }
    }

    fun clearAvailableAppUpdate(status: String?) {
        _uiState.update {
            it.copy(
                appUpdateStatus = status,
                appUpdateVersion = null,
                appUpdateReleaseUrl = null,
                appUpdateDownloadUrl = null,
                appUpdateFileName = null,
                appUpdateReleaseNotes = null
            )
        }
    }

    fun setSseTransportEnabled(enabled: Boolean) {
        settingsRepositoryImpl?.setSseTransportEnabled(enabled)
        _uiState.update {
            it.copy(
                sseTransportEnabled = enabled,
                sseSupportStatus = if (enabled) it.sseSupportStatus else null
            )
        }
    }

    fun setSseSupportStatus(status: String?) {
        _uiState.update { it.copy(sseSupportStatus = status) }
    }

    fun refreshFeatureFlagsFromRepository() {
        val repo = settingsRepositoryImpl ?: return
        _uiState.update {
            it.copy(
                backgroundReconnectEnabled = repo.isBackgroundReconnectEnabled(),
                backgroundActivityFullTextEnabled = repo.isBackgroundActivityFullTextEnabled(),
                reconnectPollIntervalSeconds = repo.getReconnectPollIntervalSeconds(),
                sseTransportEnabled = repo.isSseTransportEnabled(),
                debugLoggingEnabled = repo.isDebugLoggingEnabled(),
                appUpdateAlertsEnabled = repo.isAppUpdateAlertsEnabled(),
                automaticAppUpdateChecksEnabled = repo.isAutomaticAppUpdateChecksEnabled()
            )
        }
    }

    fun saveAppUrls(serverUrl: String, dashboardUrl: String) {
        cancelAutoRetry(clearDeferredPageError = true)
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
                isOffline = false,
                serverValidation = ServerValidationUiState()
            )
        }
    }

    fun resetSession() {
        cancelAutoRetry(clearDeferredPageError = true)
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

    override fun onCleared() {
        cancelAutoRetry(clearDeferredPageError = true)
    }
}
