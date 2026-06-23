package com.hermeswebui.android.ui.web

import android.webkit.WebView
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebShellTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun errorScreen_showsEditServerUrlButton() {
        composeTestRule.setContent {
            val context = LocalContext.current
            val fakeWebView = remember { WebView(context) }
            WebShell(
                webView = fakeWebView,
                isLoading = false,
                hasLoadedContent = false,
                isOffline = false,
                errorMessage = "Connection refused",
                onRefresh = {},
                onRetry = {},
                onOpenExternal = {},
                onOpenSettings = {}
            )
        }

        composeTestRule.onNodeWithText("Edit server URL").assertIsDisplayed()
    }

    @Test
    fun errorScreen_clickEditServerUrl_invokesOnOpenSettings() {
        var settingsOpened = false

        composeTestRule.setContent {
            val context = LocalContext.current
            val fakeWebView = remember { WebView(context) }
            WebShell(
                webView = fakeWebView,
                isLoading = false,
                hasLoadedContent = false,
                isOffline = false,
                errorMessage = "Connection refused",
                onRefresh = {},
                onRetry = {},
                onOpenExternal = {},
                onOpenSettings = { settingsOpened = true }
            )
        }

        composeTestRule.onNodeWithText("Edit server URL").performClick()

        assertThat(settingsOpened).isTrue()
    }

    @Test
    fun noError_editServerUrlButton_isNotDisplayed() {
        composeTestRule.setContent {
            val context = LocalContext.current
            val fakeWebView = remember { WebView(context) }
            WebShell(
                webView = fakeWebView,
                isLoading = false,
                hasLoadedContent = true,
                isOffline = false,
                errorMessage = null,
                onRefresh = {},
                onRetry = {},
                onOpenExternal = {},
                onOpenSettings = {}
            )
        }

        composeTestRule.onAllNodesWithText("Edit server URL").assertCountEquals(0)
    }
}

