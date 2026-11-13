package com.hermeswebui.android.ui.web

import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
                isReconnecting = false,
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
    fun errorScreen_showsRetryAndOpenInBrowserButtons() {
        composeTestRule.setContent {
            val context = LocalContext.current
            val fakeWebView = remember { WebView(context) }
            WebShell(
                webView = fakeWebView,
                isLoading = false,
                hasLoadedContent = false,
                isOffline = false,
                isReconnecting = false,
                errorMessage = "Connection refused",
                onRefresh = {},
                onRetry = {},
                onOpenExternal = {},
                onOpenSettings = {}
            )
        }

        composeTestRule.onNodeWithText("Retry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open in browser").assertIsDisplayed()
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
                isReconnecting = false,
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
    fun errorScreen_clickRetry_invokesOnRetry() {
        var retryCalled = false

        composeTestRule.setContent {
            val context = LocalContext.current
            val fakeWebView = remember { WebView(context) }
            WebShell(
                webView = fakeWebView,
                isLoading = false,
                hasLoadedContent = false,
                isOffline = false,
                isReconnecting = false,
                errorMessage = "Connection refused",
                onRefresh = {},
                onRetry = { retryCalled = true },
                onOpenExternal = {},
                onOpenSettings = {}
            )
        }

        composeTestRule.onNodeWithText("Retry").performClick()

        assertThat(retryCalled).isTrue()
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
                isReconnecting = false,
                errorMessage = null,
                onRefresh = {},
                onRetry = {},
                onOpenExternal = {},
                onOpenSettings = {}
            )
        }

        composeTestRule.onAllNodesWithText("Edit server URL").assertCountEquals(0)
    }

    /**
     * Issue #184: the pre-built WebView must be hosted MATCH_PARENT. As WRAP_CONTENT, Chromium
     * lays the main frame out at zero height and 100vh/100dvh/html{height:100%} all measure 0
     * even though the view itself is full size (which also collapsed Pocket ID's 12dvh QR canvas).
     */
    @Test
    fun hostedWebView_hasRealViewportUnits() {
        lateinit var view: WebView
        composeTestRule.setContent {
            val context = LocalContext.current
            view = remember { WebView(context).also { it.settings.javaScriptEnabled = true } }
            WebShell(
                webView = view,
                isLoading = false,
                hasLoadedContent = true,
                isOffline = false,
                isReconnecting = false,
                errorMessage = null,
                onRefresh = {},
                onRetry = {},
                onOpenExternal = {},
                onOpenSettings = {}
            )
        }
        val loaded = CountDownLatch(1)
        composeTestRule.runOnUiThread {
            view.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(v: WebView, url: String) { loaded.countDown() }
            }
            view.loadDataWithBaseURL(
                "https://example.test/", PROBE_HTML, "text/html", "utf-8", null
            )
        }
        assertThat(loaded.await(30, TimeUnit.SECONDS)).isTrue()
        var result = ""
        val done = CountDownLatch(1)
        composeTestRule.runOnUiThread {
            view.evaluateJavascript(
                "(function(){var a=document.getElementById('a').offsetHeight," +
                    "b=document.getElementById('b').offsetHeight;" +
                    "return a+','+b+','+window.innerHeight;})()"
            ) { result = it.trim('"'); done.countDown() }
        }
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
        val (vh, dvh, inner) = result.split(",").map { it.toInt() }
        assertThat(inner).isGreaterThan(0)
        assertThat(vh).isEqualTo(inner)
        assertThat(dvh).isEqualTo(inner)
    }

    private companion object {
        const val PROBE_HTML =
            "<!doctype html><meta name=viewport content='width=device-width'>" +
                "<body style='margin:0'><div id=a style='height:100vh'></div>" +
                "<div id=b style='height:100dvh'></div>"
    }
}
