package com.hermeswebui.android.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
                sseTransportEnabled = false,
                sseSupportStatus = null,
                debugLoggingEnabled = false,
                blockScreenshotsEnabled = false,
                serverValidation = ServerValidationUiState(
                    isChecking = false,
                    message = "This Hermes server is still in initial setup.",
                    isError = true
                ),
                appVersionLabel = "Version 0.0.0",
                serverProfiles = emptyList(),
                onSave = {},
                onResetSession = {},
                onDismiss = {},
                onSetBackgroundReconnect = {},
                onSetBackgroundActivityFullTextEnabled = {},
                onSetReconnectPollIntervalSeconds = {},
                onSetSseTransportEnabled = {},
                onCheckSseSupport = {},
                onCopySsePrompt = {},
                onSetDebugLoggingEnabled = {},
                onSetBlockScreenshotsEnabled = {},
                onShareDebugLog = {},
                onDownloadDebugLog = {},
                onViewGithubIssues = {},
                onNewGithubIssue = {},
                onAddProfile = { _, _ -> },
                onDeleteProfile = {},
                onRenameProfile = { _, _ -> },
                onEditProfile = { _, _, _ -> },
                onSwitchProfile = {},
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
                sseTransportEnabled = false,
                sseSupportStatus = null,
                debugLoggingEnabled = false,
                blockScreenshotsEnabled = false,
                serverValidation = ServerValidationUiState(
                    isChecking = true,
                    message = "Checking Hermes server readiness...",
                    isError = false
                ),
                appVersionLabel = "Version 0.0.0",
                serverProfiles = emptyList(),
                onSave = {},
                onResetSession = {},
                onDismiss = {},
                onSetBackgroundReconnect = {},
                onSetBackgroundActivityFullTextEnabled = {},
                onSetReconnectPollIntervalSeconds = {},
                onSetSseTransportEnabled = {},
                onCheckSseSupport = {},
                onCopySsePrompt = {},
                onSetDebugLoggingEnabled = {},
                onSetBlockScreenshotsEnabled = {},
                onShareDebugLog = {},
                onDownloadDebugLog = {},
                onViewGithubIssues = {},
                onNewGithubIssue = {},
                onAddProfile = { _, _ -> },
                onDeleteProfile = {},
                onRenameProfile = { _, _ -> },
                onEditProfile = { _, _, _ -> },
                onSwitchProfile = {},
                onClearServerValidation = {}
            )
        }

        composeTestRule.onNodeWithText("Checking Hermes server readiness...").assertIsDisplayed()
        composeTestRule.onNodeWithText("Checking server...").assertIsNotEnabled()
    }

    @Test
    fun configuredCurrentServerWithoutProfiles_canLongPressToEdit() {
        composeTestRule.setContent {
            SettingsScreen(
                initialServerUrl = "https://hermes.example.com",
                isConfigured = true,
                backgroundReconnectEnabled = false,
                backgroundActivityFullTextEnabled = false,
                reconnectPollIntervalSeconds = 1,
                sseTransportEnabled = false,
                sseSupportStatus = null,
                debugLoggingEnabled = false,
                blockScreenshotsEnabled = false,
                serverValidation = ServerValidationUiState(),
                appVersionLabel = "Version 0.0.0",
                serverProfiles = emptyList(),
                onSave = {},
                onResetSession = {},
                onDismiss = {},
                onSetBackgroundReconnect = {},
                onSetBackgroundActivityFullTextEnabled = {},
                onSetReconnectPollIntervalSeconds = {},
                onSetSseTransportEnabled = {},
                onCheckSseSupport = {},
                onCopySsePrompt = {},
                onSetDebugLoggingEnabled = {},
                onSetBlockScreenshotsEnabled = {},
                onShareDebugLog = {},
                onDownloadDebugLog = {},
                onViewGithubIssues = {},
                onNewGithubIssue = {},
                onAddProfile = { _, _ -> },
                onDeleteProfile = {},
                onRenameProfile = { _, _ -> },
                onEditProfile = { _, _, _ -> },
                onSwitchProfile = {},
                onClearServerValidation = {}
            )
        }

        composeTestRule.onNodeWithText("Current server")
            .performTouchInput { longClick() }

        composeTestRule.onNodeWithText("Edit server").assertIsDisplayed()
    }
}