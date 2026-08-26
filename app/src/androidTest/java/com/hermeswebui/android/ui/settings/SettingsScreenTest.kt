package com.hermeswebui.android.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.ui.ServerValidationUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun firstRun_showsInlineValidationError() {
        composeTestRule.setContent {
            SettingsScreen(
                initialServerUrl = "",
                isConfigured = false,
                backgroundReconnectEnabled = false,
                backgroundActivityFullTextEnabled = false,
                reconnectPollIntervalSeconds = 1,
                requireVpnForTailscaleEnabled = false,
                vpnLaunchPackageName = "",
                vpnLaunchAppOptions = emptyList(),
                sseTransportEnabled = false,
                sseSupportStatus = null,
                debugLoggingEnabled = false,
                blockScreenshotsEnabled = false,
                appUpdateAlertsEnabled = false,
                automaticAppUpdateChecksEnabled = false,
                appUpdateChannelLabel = "GitHub Releases",
                appUpdateStatus = null,
                appUpdateReleaseUrl = null,
                appUpdateDownloadUrl = null,
                appUpdateInstallReady = false,
                appUpdateReleaseNotes = null,
                clientCertificateUri = null,
                clientCertificatePassword = null,
                serverValidation = ServerValidationUiState(
                    isChecking = false,
                    message = "This Hermes server is still in initial setup.",
                    isError = true
                ),
                appVersionLabel = "Version 0.0.0",
                serverProfiles = emptyList(),
                onSave = {},
                onDismiss = {},
                onSetBackgroundReconnect = {},
                onSetBackgroundActivityFullTextEnabled = {},
                onSetReconnectPollIntervalSeconds = {},
                onSetRequireVpnForTailscaleEnabled = {},
                onSetVpnLaunchPackageName = {},
                onSetSseTransportEnabled = {},
                onCheckSseSupport = {},
                onCopySsePrompt = {},
                onSetDebugLoggingEnabled = {},
                onSetBlockScreenshotsEnabled = {},
                onSetAppUpdateAlertsEnabled = {},
                onSetAutomaticAppUpdateChecksEnabled = {},
                onSetClientCertificateConfig = { _, _ -> },
                onClearClientCertificateConfig = {},
                onCheckAppUpdates = {},
                onDownloadAppUpdate = {},
                onOpenAppUpdateRelease = {},
                onShareDebugLog = {},
                onDownloadDebugLog = {},
                onViewGithubIssues = {},
                onNewGithubIssue = {},
                onAddProfile = { _, _ -> },
                onDeleteProfile = {},
                onEditProfile = { _, _, _ -> },
                onSwitchProfile = {},
                onReconnectCurrentServer = {},
                onClearServerValidation = {}
            )
        }

        composeTestRule.onNodeWithText("This Hermes server is still in initial setup.").assertIsDisplayed()
    }

    @Test
    fun firstRun_disablesConnectWhileCheckingServer() {
        composeTestRule.setContent {
            SettingsScreen(
                initialServerUrl = "https://hermes.example.com",
                isConfigured = false,
                backgroundReconnectEnabled = false,
                backgroundActivityFullTextEnabled = false,
                reconnectPollIntervalSeconds = 1,
                requireVpnForTailscaleEnabled = false,
                vpnLaunchPackageName = "",
                vpnLaunchAppOptions = emptyList(),
                sseTransportEnabled = false,
                sseSupportStatus = null,
                debugLoggingEnabled = false,
                blockScreenshotsEnabled = false,
                appUpdateAlertsEnabled = false,
                automaticAppUpdateChecksEnabled = false,
                appUpdateChannelLabel = "GitHub Releases",
                appUpdateStatus = null,
                appUpdateReleaseUrl = null,
                appUpdateDownloadUrl = null,
                appUpdateInstallReady = false,
                appUpdateReleaseNotes = null,
                clientCertificateUri = null,
                clientCertificatePassword = null,
                serverValidation = ServerValidationUiState(
                    isChecking = true,
                    message = "Checking Hermes server readiness...",
                    isError = false
                ),
                appVersionLabel = "Version 0.0.0",
                serverProfiles = emptyList(),
                onSave = {},
                onDismiss = {},
                onSetBackgroundReconnect = {},
                onSetBackgroundActivityFullTextEnabled = {},
                onSetReconnectPollIntervalSeconds = {},
                onSetRequireVpnForTailscaleEnabled = {},
                onSetVpnLaunchPackageName = {},
                onSetSseTransportEnabled = {},
                onCheckSseSupport = {},
                onCopySsePrompt = {},
                onSetDebugLoggingEnabled = {},
                onSetBlockScreenshotsEnabled = {},
                onSetAppUpdateAlertsEnabled = {},
                onSetAutomaticAppUpdateChecksEnabled = {},
                onSetClientCertificateConfig = { _, _ -> },
                onClearClientCertificateConfig = {},
                onCheckAppUpdates = {},
                onDownloadAppUpdate = {},
                onOpenAppUpdateRelease = {},
                onShareDebugLog = {},
                onDownloadDebugLog = {},
                onViewGithubIssues = {},
                onNewGithubIssue = {},
                onAddProfile = { _, _ -> },
                onDeleteProfile = {},
                onEditProfile = { _, _, _ -> },
                onSwitchProfile = {},
                onReconnectCurrentServer = {},
                onClearServerValidation = {}
            )
        }

        composeTestRule.onNodeWithText("Checking Hermes server readiness...").assertIsDisplayed()
        composeTestRule.onNodeWithText("Checking server...").assertIsNotEnabled()
    }

    @Test
    fun configuredCurrentServerWithoutProfiles_reconnectsOnTapAndCanLongPressToEdit() {
        var reconnectCount = 0
        composeTestRule.setContent {
            SettingsScreen(
                initialServerUrl = "https://hermes.example.com",
                isConfigured = true,
                backgroundReconnectEnabled = false,
                backgroundActivityFullTextEnabled = false,
                reconnectPollIntervalSeconds = 1,
                requireVpnForTailscaleEnabled = false,
                vpnLaunchPackageName = "",
                vpnLaunchAppOptions = emptyList(),
                sseTransportEnabled = false,
                sseSupportStatus = null,
                debugLoggingEnabled = false,
                blockScreenshotsEnabled = false,
                appUpdateAlertsEnabled = false,
                automaticAppUpdateChecksEnabled = false,
                appUpdateChannelLabel = "GitHub Releases",
                appUpdateStatus = null,
                appUpdateReleaseUrl = null,
                appUpdateDownloadUrl = null,
                appUpdateInstallReady = false,
                appUpdateReleaseNotes = null,
                clientCertificateUri = null,
                clientCertificatePassword = null,
                serverValidation = ServerValidationUiState(),
                appVersionLabel = "Version 0.0.0",
                serverProfiles = emptyList(),
                onSave = {},
                onDismiss = {},
                onSetBackgroundReconnect = {},
                onSetBackgroundActivityFullTextEnabled = {},
                onSetReconnectPollIntervalSeconds = {},
                onSetRequireVpnForTailscaleEnabled = {},
                onSetVpnLaunchPackageName = {},
                onSetSseTransportEnabled = {},
                onCheckSseSupport = {},
                onCopySsePrompt = {},
                onSetDebugLoggingEnabled = {},
                onSetBlockScreenshotsEnabled = {},
                onSetAppUpdateAlertsEnabled = {},
                onSetAutomaticAppUpdateChecksEnabled = {},
                onSetClientCertificateConfig = { _, _ -> },
                onClearClientCertificateConfig = {},
                onCheckAppUpdates = {},
                onDownloadAppUpdate = {},
                onOpenAppUpdateRelease = {},
                onShareDebugLog = {},
                onDownloadDebugLog = {},
                onViewGithubIssues = {},
                onNewGithubIssue = {},
                onAddProfile = { _, _ -> },
                onDeleteProfile = {},
                onEditProfile = { _, _, _ -> },
                onSwitchProfile = {},
                onReconnectCurrentServer = { reconnectCount++ },
                onClearServerValidation = {}
            )
        }

        composeTestRule.onNodeWithText("Current server").performClick()
        assertThat(reconnectCount).isEqualTo(1)

        composeTestRule.onNodeWithText("Current server")
            .performTouchInput { longClick() }

        composeTestRule.onNodeWithText("Edit server").assertIsDisplayed()
    }

    @Test
    fun reconnectPollingInterval_rendersSingularDescription() {
        // Regression for issue #81: the description used stringResource() with a plural
        // resource ID, which throws Resources.NotFoundException during composition and
        // closed the settings screen instantly. Rendering must not crash and must show
        // the singular text for a 1-second interval.
        renderConfiguredSettings(1)
        composeTestRule.onNodeWithText("Advanced connection options")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText("1 second between reconnect checks")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun reconnectPollingInterval_rendersPluralDescription() {
        // Same regression as above, for the plural form at a 2-second interval.
        renderConfiguredSettings(2)
        composeTestRule.onNodeWithText("Advanced connection options")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText("2 seconds between reconnect checks")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun configuredSettings_showsTaskBasedSectionsAndOmitsSessionReset() {
        renderConfiguredSettings(1)

        listOf(
            "SERVERS",
            "APPLICATION",
            "UPDATES",
            "CONNECTION",
            "PRIVACY",
            "TROUBLESHOOTING",
            "ADVANCED",
            "ABOUT"
        ).forEach { heading ->
            composeTestRule.onNodeWithText(heading).assertExists()
        }

        composeTestRule.onNodeWithText("Reset web session").assertDoesNotExist()
        composeTestRule.onNodeWithText("SECURITY").assertDoesNotExist()
    }

    @Test
    fun advancedConnectionOptions_areCollapsedByDefault() {
        renderConfiguredSettings(1)

        composeTestRule.onNodeWithText("1 second between reconnect checks").assertDoesNotExist()
        composeTestRule.onNodeWithText("Use SSE transport").assertDoesNotExist()

        composeTestRule.onNodeWithText("Advanced connection options")
            .performScrollTo()
            .performClick()

        composeTestRule.onNodeWithText("1 second between reconnect checks")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Use SSE transport").assertExists()
    }

    @Test
    fun clientCertificate_opensAdvancedDialogAndSavesExistingSelection() {
        var savedConfig: Pair<String?, String?>? = null
        renderConfiguredSettings(
            seconds = 1,
            clientCertificateUri = "content://certificates/hermes.p12",
            clientCertificatePassword = " secret ",
            onSetClientCertificateConfig = { uri, password -> savedConfig = uri to password }
        )

        composeTestRule.onNodeWithText("Client certificate")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText("Choose another certificate").assertIsDisplayed()
        composeTestRule.onNodeWithText("Remove certificate").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").performClick()

        assertThat(savedConfig).isEqualTo("content://certificates/hermes.p12" to " secret ")
    }

    private fun renderConfiguredSettings(
        seconds: Int,
        clientCertificateUri: String? = null,
        clientCertificatePassword: String? = null,
        onSetClientCertificateConfig: (String?, String?) -> Unit = { _, _ -> }
    ) {
        composeTestRule.setContent {
            SettingsScreen(
                initialServerUrl = "https://hermes.example.com",
                isConfigured = true,
                backgroundReconnectEnabled = true,
                backgroundActivityFullTextEnabled = false,
                reconnectPollIntervalSeconds = seconds,
                requireVpnForTailscaleEnabled = false,
                vpnLaunchPackageName = "",
                vpnLaunchAppOptions = emptyList(),
                sseTransportEnabled = false,
                sseSupportStatus = null,
                debugLoggingEnabled = false,
                blockScreenshotsEnabled = false,
                appUpdateAlertsEnabled = false,
                automaticAppUpdateChecksEnabled = false,
                appUpdateChannelLabel = "GitHub Releases",
                appUpdateStatus = null,
                appUpdateReleaseUrl = null,
                appUpdateDownloadUrl = null,
                appUpdateInstallReady = false,
                appUpdateReleaseNotes = null,
                clientCertificateUri = clientCertificateUri,
                clientCertificatePassword = clientCertificatePassword,
                serverValidation = ServerValidationUiState(),
                appVersionLabel = "Version 0.0.0",
                serverProfiles = emptyList(),
                onSave = {},
                onDismiss = {},
                onSetBackgroundReconnect = {},
                onSetBackgroundActivityFullTextEnabled = {},
                onSetReconnectPollIntervalSeconds = {},
                onSetRequireVpnForTailscaleEnabled = {},
                onSetVpnLaunchPackageName = {},
                onSetSseTransportEnabled = {},
                onCheckSseSupport = {},
                onCopySsePrompt = {},
                onSetDebugLoggingEnabled = {},
                onSetBlockScreenshotsEnabled = {},
                onSetAppUpdateAlertsEnabled = {},
                onSetAutomaticAppUpdateChecksEnabled = {},
                onSetClientCertificateConfig = onSetClientCertificateConfig,
                onClearClientCertificateConfig = {},
                onCheckAppUpdates = {},
                onDownloadAppUpdate = {},
                onOpenAppUpdateRelease = {},
                onShareDebugLog = {},
                onDownloadDebugLog = {},
                onViewGithubIssues = {},
                onNewGithubIssue = {},
                onAddProfile = { _, _ -> },
                onDeleteProfile = {},
                onEditProfile = { _, _, _ -> },
                onSwitchProfile = {},
                onReconnectCurrentServer = {},
                onClearServerValidation = {}
            )
        }
    }
}