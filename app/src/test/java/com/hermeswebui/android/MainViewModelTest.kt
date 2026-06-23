package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.AppSettings
import com.hermeswebui.android.data.SettingsStore
import com.hermeswebui.android.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val defaultServerUrl = "https://hermes.example.com"
    private val defaultDashboardUrl = ""

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `visible commit marks loaded content`() {
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(store, defaultServerUrl, defaultDashboardUrl)

        viewModel.onPageStarted(defaultServerUrl)
        viewModel.onPageCommitVisible(defaultServerUrl)
        viewModel.onPageFinished(defaultServerUrl)

        val state = viewModel.uiState.value
        assertThat(state.hasLoadedContent).isTrue()
        assertThat(state.isLoading).isFalse()
        assertThat(store.lastLoadedUrl).isEqualTo(defaultServerUrl)
    }

    @Test
    fun `page finished without visible commit does not mark loaded content`() {
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(store, defaultServerUrl, defaultDashboardUrl)

        viewModel.onPageStarted(defaultServerUrl)
        viewModel.onPageFinished(defaultServerUrl)

        val state = viewModel.uiState.value
        assertThat(state.hasLoadedContent).isFalse()
        assertThat(state.isLoading).isFalse()
        assertThat(store.lastLoadedUrl).isEqualTo(defaultServerUrl)
    }

    @Test
    fun `main frame error prevents later page finished from marking loaded content or saving last url`() {
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(store, defaultServerUrl, defaultDashboardUrl)
        val failedUrl = "$defaultServerUrl/unavailable"

        viewModel.onPageStarted(failedUrl)
        viewModel.onPageError("Failed to load page", isOffline = true)
        viewModel.onPageFinished(failedUrl)

        val state = viewModel.uiState.value
        assertThat(state.hasLoadedContent).isFalse()
        assertThat(state.isLoading).isFalse()
        assertThat(state.errorMessage).isEqualTo("Failed to load page")
        assertThat(store.lastLoadedUrl).isNull()
    }

    @Test
    fun `server url switch resets loaded content state`() {
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(store, defaultServerUrl, defaultDashboardUrl)

        viewModel.onPageStarted(defaultServerUrl)
        viewModel.onPageCommitVisible(defaultServerUrl)
        viewModel.onPageFinished(defaultServerUrl)
        viewModel.saveAppUrls("https://next.example.com", defaultDashboardUrl)

        val state = viewModel.uiState.value
        assertThat(state.settings.serverUrl).isEqualTo("https://next.example.com")
        assertThat(state.currentUrl).isEqualTo("https://next.example.com")
        assertThat(state.hasLoadedContent).isFalse()
        assertThat(state.isLoading).isTrue()
        assertThat(state.errorMessage).isNull()
    }

    @Test
    fun `openSettings sets isSettingsVisible`() {
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(store, defaultServerUrl, defaultDashboardUrl)

        viewModel.openSettings()

        assertThat(viewModel.uiState.value.isSettingsVisible).isTrue()
    }

    @Test
    fun `openSettings from error state shows settings without clearing error`() {
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(store, defaultServerUrl, defaultDashboardUrl)

        viewModel.onPageStarted(defaultServerUrl)
        viewModel.onPageError("Connection refused", isOffline = true)
        viewModel.openSettings()

        val state = viewModel.uiState.value
        assertThat(state.isSettingsVisible).isTrue()
        assertThat(state.errorMessage).isEqualTo("Connection refused")
    }

    @Test
    fun `saveAppUrls from error state clears error and starts loading new url`() {
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(store, defaultServerUrl, defaultDashboardUrl)

        viewModel.onPageStarted(defaultServerUrl)
        viewModel.onPageError("Connection refused", isOffline = true)
        viewModel.saveAppUrls("https://next.example.com", defaultDashboardUrl)

        val state = viewModel.uiState.value
        assertThat(state.errorMessage).isNull()
        assertThat(state.isSettingsVisible).isFalse()
        assertThat(state.isLoading).isTrue()
        assertThat(state.currentUrl).isEqualTo("https://next.example.com")
    }

    @Test
    fun `reset session resets loaded content state`() {
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(store, defaultServerUrl, defaultDashboardUrl)

        viewModel.onPageStarted(defaultServerUrl)
        viewModel.onPageCommitVisible(defaultServerUrl)
        viewModel.onPageFinished(defaultServerUrl)
        viewModel.resetSession()

        val state = viewModel.uiState.value
        assertThat(state.currentUrl).isEqualTo(defaultServerUrl)
        assertThat(state.hasLoadedContent).isFalse()
        assertThat(state.isLoading).isTrue()
        assertThat(state.errorMessage).isNull()
        assertThat(store.clearSessionCalled).isTrue()
    }

    @Test
    fun `url visited persists last url for client-side navigation`() {
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(store, defaultServerUrl, defaultDashboardUrl)
        val sessionUrl = "$defaultServerUrl/session-123"

        viewModel.onPageStarted(defaultServerUrl)
        viewModel.onPageCommitVisible(defaultServerUrl)
        viewModel.onPageFinished(defaultServerUrl)
        viewModel.onUrlVisited(sessionUrl)

        val state = viewModel.uiState.value
        assertThat(state.currentUrl).isEqualTo(sessionUrl)
        assertThat(store.lastLoadedUrl).isEqualTo(sessionUrl)
    }

    @Test
    fun `url visited does not persist when remember last url is false`() {
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(store, defaultServerUrl, defaultDashboardUrl)
        val dashboardUrl = "https://dashboard.example.com"

        viewModel.onPageStarted(defaultServerUrl)
        viewModel.onPageCommitVisible(defaultServerUrl)
        viewModel.onPageFinished(defaultServerUrl)
        viewModel.onUrlVisited(dashboardUrl, rememberLastUrl = false)

        val state = viewModel.uiState.value
        assertThat(state.currentUrl).isEqualTo(dashboardUrl)
        assertThat(store.lastLoadedUrl).isEqualTo(defaultServerUrl)
    }

    // --- Auto-retry tests ---

    @Test
    fun `auto-retry sets isReconnecting and auto-reload fires when server becomes reachable`() = runTest(testDispatcher) {
        var probeCount = 0
        val viewModel = MainViewModel(FakeSettingsStore(), defaultServerUrl, defaultDashboardUrl) { _ ->
            probeCount++
            probeCount >= 2 // fail first probe, succeed on second
        }

        val autoReloads = mutableListOf<Unit>()
        backgroundScope.launch { viewModel.autoReloadEvent.collect { autoReloads += it } }

        viewModel.onPageError("net::ERR_CONNECTION_REFUSED", isOffline = false)
        runCurrent() // let the retry coroutine start and set isReconnecting

        assertThat(viewModel.uiState.value.isReconnecting).isTrue()
        assertThat(viewModel.uiState.value.errorMessage).isNotNull()

        // Advance past first 1 s interval — probe fails, isOffline confirmed
        advanceTimeBy(1_001L)
        runCurrent()
        assertThat(autoReloads).isEmpty()
        assertThat(viewModel.uiState.value.isOffline).isTrue()
        assertThat(viewModel.uiState.value.isReconnecting).isTrue()

        // Advance past second 2 s interval — probe succeeds, auto-reload event fires
        advanceTimeBy(2_001L)
        runCurrent()
        assertThat(autoReloads).hasSize(1)
        assertThat(viewModel.uiState.value.isReconnecting).isFalse()
    }

    @Test
    fun `cancelAutoRetry stops polling and clears isReconnecting`() = runTest(testDispatcher) {
        var probeCount = 0
        val viewModel = MainViewModel(FakeSettingsStore(), defaultServerUrl, defaultDashboardUrl) { _ ->
            probeCount++
            false // never succeed
        }

        viewModel.onPageError("net::ERR_CONNECTION_REFUSED", isOffline = false)
        runCurrent()
        assertThat(viewModel.uiState.value.isReconnecting).isTrue()

        viewModel.cancelAutoRetry()
        assertThat(viewModel.uiState.value.isReconnecting).isFalse()

        // Advance well past first interval — no probes should fire after cancel
        advanceTimeBy(5_000L)
        runCurrent()
        assertThat(probeCount).isEqualTo(0)
    }

    @Test
    fun `onPageStarted cancels auto-retry and clears error`() = runTest(testDispatcher) {
        val viewModel = MainViewModel(FakeSettingsStore(), defaultServerUrl, defaultDashboardUrl) { _ -> false }

        viewModel.onPageError("error", isOffline = false)
        runCurrent()
        assertThat(viewModel.uiState.value.isReconnecting).isTrue()

        viewModel.onPageStarted(defaultServerUrl)
        assertThat(viewModel.uiState.value.isReconnecting).isFalse()
        assertThat(viewModel.uiState.value.errorMessage).isNull()
        assertThat(viewModel.uiState.value.isLoading).isTrue()
    }

    @Test
    fun `resumeAutoRetryIfNeeded restarts polling after cancel when still in error state`() = runTest(testDispatcher) {
        var probeCount = 0
        val viewModel = MainViewModel(FakeSettingsStore(), defaultServerUrl, defaultDashboardUrl) { _ ->
            probeCount++
            false
        }

        viewModel.onPageError("error", isOffline = false)
        runCurrent()
        assertThat(viewModel.uiState.value.isReconnecting).isTrue()

        viewModel.cancelAutoRetry()
        assertThat(viewModel.uiState.value.isReconnecting).isFalse()

        viewModel.resumeAutoRetryIfNeeded()
        runCurrent()
        assertThat(viewModel.uiState.value.isReconnecting).isTrue()

        advanceTimeBy(1_001L)
        runCurrent()
        assertThat(probeCount).isEqualTo(1)
    }

    @Test
    fun `resumeAutoRetryIfNeeded does nothing without error state`() = runTest(testDispatcher) {
        var probeCount = 0
        val viewModel = MainViewModel(FakeSettingsStore(), defaultServerUrl, defaultDashboardUrl) { _ ->
            probeCount++
            false
        }

        viewModel.resumeAutoRetryIfNeeded()
        runCurrent()

        advanceTimeBy(2_000L)
        runCurrent()
        assertThat(probeCount).isEqualTo(0)
        assertThat(viewModel.uiState.value.isReconnecting).isFalse()
    }

    private class FakeSettingsStore : SettingsStore {
        private var settings = AppSettings(
            serverUrl = "https://hermes.example.com",
            dashboardUrl = "",
            allowedHosts = setOf("hermes.example.com"),
            isConfigured = true
        )

        var lastLoadedUrl: String? = null
            private set
        var clearSessionCalled: Boolean = false
            private set

        override fun getSettings(defaultUrl: String, defaultDashboardUrl: String): AppSettings = settings

        override fun saveAppUrls(serverUrl: String, dashboardUrl: String) {
            settings = settings.copy(
                serverUrl = serverUrl,
                dashboardUrl = dashboardUrl,
                allowedHosts = setOf("next.example.com"),
                isConfigured = true
            )
        }

        override fun clearWebSession() {
            clearSessionCalled = true
            lastLoadedUrl = null
        }

        override fun saveLastLoadedUrl(url: String) {
            lastLoadedUrl = url
        }
    }
}
