package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.AppSettings
import com.hermeswebui.android.data.SettingsStore
import com.hermeswebui.android.ui.MainViewModel
import org.junit.Test

class MainViewModelTest {
    private val defaultServerUrl = "https://hermes.example.com"
    private val defaultDashboardUrl = ""

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
