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
        val viewModel = MainViewModel(store, null, defaultServerUrl, defaultDashboardUrl)

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
        val viewModel = MainViewModel(store, null, defaultServerUrl, defaultDashboardUrl)

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
        val viewModel = MainViewModel(store, null, defaultServerUrl, defaultDashboardUrl)
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
        val viewModel = MainViewModel(store, null, defaultServerUrl, defaultDashboardUrl)

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
        val viewModel = MainViewModel(store, null, defaultServerUrl, defaultDashboardUrl)

        viewModel.openSettings()

        assertThat(viewModel.uiState.value.isSettingsVisible).isTrue()
    }

    @Test
    fun `openSettings from error state shows settings without clearing error`() {
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(store, null, defaultServerUrl, defaultDashboardUrl)

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
        val viewModel = MainViewModel(store, null, defaultServerUrl, defaultDashboardUrl)

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
        val viewModel = MainViewModel(store, null, defaultServerUrl, defaultDashboardUrl)

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
        val viewModel = MainViewModel(store, null, defaultServerUrl, defaultDashboardUrl)
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
        val viewModel = MainViewModel(store, null, defaultServerUrl, defaultDashboardUrl)
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
        val viewModel = MainViewModel(FakeSettingsStore(), null, defaultServerUrl, defaultDashboardUrl) { _ ->
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

        // Advance past second fixed interval — probe succeeds, auto-reload event fires
        advanceTimeBy(1_001L)
        runCurrent()
        assertThat(autoReloads).hasSize(1)
        assertThat(viewModel.uiState.value.isReconnecting).isFalse()
    }

    @Test
    fun `setReconnectPollIntervalSeconds updates ui state and clamps values`() {
        val store = FakeSettingsStore()
        val viewModel = MainViewModel(store, null, defaultServerUrl, defaultDashboardUrl)

        viewModel.setReconnectPollIntervalSeconds(7)
        assertThat(viewModel.uiState.value.reconnectPollIntervalSeconds).isEqualTo(7)

        viewModel.setReconnectPollIntervalSeconds(0)
        assertThat(viewModel.uiState.value.reconnectPollIntervalSeconds).isEqualTo(1)

        viewModel.setReconnectPollIntervalSeconds(20)
        assertThat(viewModel.uiState.value.reconnectPollIntervalSeconds).isEqualTo(10)
    }

    @Test
    fun `cancelAutoRetry stops polling and clears isReconnecting`() = runTest(testDispatcher) {
        var probeCount = 0
        val viewModel = MainViewModel(FakeSettingsStore(), null, defaultServerUrl, defaultDashboardUrl) { _ ->
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
        val viewModel = MainViewModel(FakeSettingsStore(), null, defaultServerUrl, defaultDashboardUrl) { _ -> false }

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
        val viewModel = MainViewModel(FakeSettingsStore(), null, defaultServerUrl, defaultDashboardUrl) { _ ->
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
        val viewModel = MainViewModel(FakeSettingsStore(), null, defaultServerUrl, defaultDashboardUrl) { _ ->
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

    @Test
    fun `quick resume error defers native error UI while reconnecting`() = runTest(testDispatcher) {
        var now = 10_000L
        val viewModel = MainViewModel(
            FakeSettingsStore(),
            null,
            defaultServerUrl,
            defaultDashboardUrl,
            nowMs = { now },
            serverReachabilityChecker = { false }
        )

        viewModel.onPageStarted(defaultServerUrl)
        viewModel.onPageCommitVisible(defaultServerUrl)
        viewModel.onPageFinished(defaultServerUrl)

        viewModel.onAppBackgrounded()
        now += 500L
        viewModel.onAppForegrounded()
        viewModel.onPageError("net::ERR_CONNECTION_RESET", isOffline = false)
        runCurrent()

        val state = viewModel.uiState.value
        assertThat(state.hasLoadedContent).isTrue()
        assertThat(state.errorMessage).isNull()
        assertThat(state.isReconnecting).isTrue()
    }

    @Test
    fun `deferred quick resume error becomes visible after grace period when reconnect fails`() = runTest(testDispatcher) {
        var now = 20_000L
        val viewModel = MainViewModel(
            FakeSettingsStore(),
            null,
            defaultServerUrl,
            defaultDashboardUrl,
            nowMs = { now },
            serverReachabilityChecker = {
                now += 100L
                false
            }
        )

        viewModel.onPageStarted(defaultServerUrl)
        viewModel.onPageCommitVisible(defaultServerUrl)
        viewModel.onPageFinished(defaultServerUrl)

        viewModel.onAppBackgrounded()
        now += 300L
        viewModel.onAppForegrounded()
        viewModel.onPageError("net::ERR_CONNECTION_RESET", isOffline = true)
        runCurrent()
        assertThat(viewModel.uiState.value.errorMessage).isNull()

        now += 1_001L
        advanceTimeBy(1_001L)
        runCurrent()
        assertThat(viewModel.uiState.value.errorMessage).isNull()

        now += 2_001L
        advanceTimeBy(2_001L)
        runCurrent()
        assertThat(viewModel.uiState.value.errorMessage).isEqualTo("net::ERR_CONNECTION_RESET")
        assertThat(viewModel.uiState.value.isOffline).isTrue()
    }

    @Test
    fun `deferred quick resume error promotes when grace expires even before next probe interval`() = runTest(testDispatcher) {
        var now = 40_000L
        val viewModel = MainViewModel(
            FakeSettingsStore(),
            null,
            defaultServerUrl,
            defaultDashboardUrl,
            nowMs = { now },
            serverReachabilityChecker = { false }
        )

        viewModel.onPageStarted(defaultServerUrl)
        viewModel.onPageCommitVisible(defaultServerUrl)
        viewModel.onPageFinished(defaultServerUrl)

        viewModel.onAppBackgrounded()
        now += 200L
        viewModel.onAppForegrounded()
        viewModel.onPageError("net::ERR_CONNECTION_RESET", isOffline = true)
        runCurrent()
        assertThat(viewModel.uiState.value.errorMessage).isNull()

        now += 1_999L
        advanceTimeBy(1_999L)
        runCurrent()
        assertThat(viewModel.uiState.value.errorMessage).isNull()

        now += 1L
        advanceTimeBy(1L)
        runCurrent()
        assertThat(viewModel.uiState.value.errorMessage).isEqualTo("net::ERR_CONNECTION_RESET")
    }

    @Test
    fun `resume auto retry immediately shows overdue deferred error before next probe`() = runTest(testDispatcher) {
        var now = 50_000L
        var probeCount = 0
        val viewModel = MainViewModel(
            FakeSettingsStore(),
            null,
            defaultServerUrl,
            defaultDashboardUrl,
            nowMs = { now },
            serverReachabilityChecker = {
                probeCount++
                false
            }
        )

        viewModel.onPageStarted(defaultServerUrl)
        viewModel.onPageCommitVisible(defaultServerUrl)
        viewModel.onPageFinished(defaultServerUrl)

        viewModel.onAppBackgrounded()
        now += 200L
        viewModel.onAppForegrounded()
        viewModel.onPageError("net::ERR_CONNECTION_RESET", isOffline = true)
        runCurrent()
        assertThat(viewModel.uiState.value.errorMessage).isNull()

        viewModel.onAppBackgrounded()
        viewModel.cancelAutoRetry()
        now += 3_000L
        viewModel.onAppForegrounded()
        viewModel.resumeAutoRetryIfNeeded()
        runCurrent()

        assertThat(probeCount).isEqualTo(0)
        assertThat(viewModel.uiState.value.errorMessage).isEqualTo("net::ERR_CONNECTION_RESET")
        assertThat(viewModel.uiState.value.isReconnecting).isTrue()
    }

    @Test
    fun `first load error does not defer native error UI`() = runTest(testDispatcher) {
        var now = 30_000L
        val viewModel = MainViewModel(
            FakeSettingsStore(),
            null,
            defaultServerUrl,
            defaultDashboardUrl,
            nowMs = { now },
            serverReachabilityChecker = { false }
        )

        viewModel.onAppBackgrounded()
        now += 500L
        viewModel.onAppForegrounded()
        viewModel.onPageError("net::ERR_CONNECTION_REFUSED", isOffline = true)
        runCurrent()

        val state = viewModel.uiState.value
        assertThat(state.hasLoadedContent).isFalse()
        assertThat(state.errorMessage).isEqualTo("net::ERR_CONNECTION_REFUSED")
        assertThat(state.isReconnecting).isTrue()
    }

    @Test
    fun `sse transport attempts reconnect immediately before waiting for polling interval`() = runTest(testDispatcher) {
        var sseChecks = 0
        var reachabilityChecks = 0
        val viewModel = MainViewModel(
            FakeSettingsStore(),
            null,
            defaultServerUrl,
            defaultDashboardUrl,
            sseReconnectChecker = {
                sseChecks++
                true
            },
            serverReachabilityChecker = {
                reachabilityChecks++
                false
            }
        )
        viewModel.setSseTransportEnabled(true)

        val autoReloads = mutableListOf<Unit>()
        backgroundScope.launch { viewModel.autoReloadEvent.collect { autoReloads += it } }

        viewModel.onPageError("net::ERR_CONNECTION_RESET", isOffline = false)
        runCurrent()

        assertThat(sseChecks).isEqualTo(1)
        assertThat(reachabilityChecks).isEqualTo(0)
        assertThat(autoReloads).hasSize(1)
        assertThat(viewModel.uiState.value.isReconnecting).isFalse()
    }

    @Test
    fun `sse transport falls back to reachability polling when stream handshake fails`() = runTest(testDispatcher) {
        var sseChecks = 0
        var reachabilityChecks = 0
        val viewModel = MainViewModel(
            FakeSettingsStore(),
            null,
            defaultServerUrl,
            defaultDashboardUrl,
            sseReconnectChecker = {
                sseChecks++
                false
            },
            serverReachabilityChecker = {
                reachabilityChecks++
                reachabilityChecks >= 2
            }
        )
        viewModel.setSseTransportEnabled(true)

        val autoReloads = mutableListOf<Unit>()
        backgroundScope.launch { viewModel.autoReloadEvent.collect { autoReloads += it } }

        viewModel.onPageError("net::ERR_CONNECTION_RESET", isOffline = false)
        runCurrent()

        assertThat(sseChecks).isEqualTo(1)
        assertThat(reachabilityChecks).isEqualTo(1)
        assertThat(autoReloads).isEmpty()

        advanceTimeBy(1_001L)
        runCurrent()

        assertThat(sseChecks).isEqualTo(2)
        assertThat(reachabilityChecks).isEqualTo(2)
        assertThat(autoReloads).hasSize(1)
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
