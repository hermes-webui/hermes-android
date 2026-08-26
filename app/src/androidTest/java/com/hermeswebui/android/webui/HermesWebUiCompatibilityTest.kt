package com.hermeswebui.android.webui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Rule
import org.junit.Test

class HermesWebUiCompatibilityTest {
    private companion object {
        const val WEBVIEW_LOAD_TIMEOUT_SECONDS = 60L
        const val JAVASCRIPT_TIMEOUT_SECONDS = 30L
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var webView: WebView

    @After
    fun destroyWebView() {
        if (::webView.isInitialized) {
            composeTestRule.runOnIdle {
                webView.destroy()
            }
        }
    }

    @Test
    fun clarifyAutofocus_suppressesOnlyAutomaticClarifyFocus() {
        loadFixture(
            """
            <div class="clarify-card visible">
              <input id="clarifyInput">
              <button class="clarify-choice other"><span>Other</span></button>
            </div>
            <div role="dialog"><input id="folderName"></div>
            """.trimIndent()
        )
        evaluate(HermesWebUiScripts.suppressClarifyAutofocusScript)

        evaluate("folderName.focus()")
        assertThat(awaitBoolean("document.activeElement === folderName", expected = true)).isTrue()

        evaluate(
            """
            window.__clarifyBlurCalls = 0;
            window.__originalClarifyBlur = clarifyInput.blur.bind(clarifyInput);
            clarifyInput.blur = function() {
              window.__clarifyBlurCalls++;
              window.__originalClarifyBlur();
            };
            """.trimIndent()
        )
        evaluate(
            """
            clarifyInput.focus();
            clarifyInput.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
            """.trimIndent()
        )
        val automaticFocusSuppressed =
            awaitBoolean("document.activeElement === clarifyInput", expected = false)
        val focusMetrics = evaluate(
            """
            JSON.stringify({
              installed: !!window.__hermesAndroidSuppressClarifyAutofocusInstalled,
              closest: !!clarifyInput.closest('.clarify-card'),
              blurCalls: window.__clarifyBlurCalls,
              activeId: document.activeElement && document.activeElement.id
            })
            """.trimIndent()
        )
        assertWithMessage(focusMetrics).that(automaticFocusSuppressed).isTrue()

        evaluate(
            """
            clarifyInput.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
            clarifyInput.focus();
            clarifyInput.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
            """.trimIndent()
        )
        assertThat(awaitBoolean("document.activeElement === clarifyInput", expected = true)).isTrue()

        evaluate(
            """
            clarifyInput.blur();
            document.querySelector('.clarify-choice.other span')
              .dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
            clarifyInput.focus();
            clarifyInput.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
            """.trimIndent()
        )
        assertThat(awaitBoolean("document.activeElement === clarifyInput", expected = true)).isTrue()
    }

    @Test
    fun viewportFix_fitsPromptAndDoesNotCreateGenericScrollContainer() {
        loadFixture(
            """
            <style>
              html, body { width: 100%; height: 100%; margin: 0; }
              #fixture { position: fixed; inset: 0; }
              .app-titlebar { position: absolute; inset: 0 0 auto; height: 96px; }
              .composer-wrap { position: absolute; inset: auto 0 0; height: 160px; }
              .composer-flyout { position: absolute; inset: 0; }
              .clarify-card {
                position: absolute;
                left: 20px;
                right: 20px;
                bottom: 160px;
                max-height: 0;
                overflow: hidden;
                pointer-events: none;
              }
              .clarify-card.visible { pointer-events: auto; }
              .clarify-inner { height: 700px; }
              #genericPanel {
                position: absolute;
                top: 120px;
                left: 20px;
                width: 400px;
                max-height: 0;
                overflow-y: visible;
              }
              #genericPanel > div { height: 600px; }
            </style>
            <div id="fixture">
              <div class="app-titlebar"></div>
              <div class="composer-wrap"><div class="composer-flyout"></div></div>
              <div class="clarify-card visible"><div class="clarify-inner"></div></div>
              <div id="genericPanel"><div><button>Action</button></div></div>
            </div>
            """.trimIndent()
        )
        evaluate(HermesWebUiScripts.viewportFixScript)
        evaluate("window.__hermesAndroidApplyViewportFix();")

        val promptMetrics = evaluate(
            """
            (function() {
              var card = document.querySelector('.clarify-card');
              var titlebar = document.querySelector('.app-titlebar');
              var vv = window.visualViewport;
              var visualTop = vv && vv.height > 0 ? vv.offsetTop : 0;
              var visualBottom = vv && vv.height > 0
                ? vv.offsetTop + vv.height
                : window.innerHeight;
              return JSON.stringify({
                maxHeight: getComputedStyle(card).maxHeight,
                cardBottom: card.getBoundingClientRect().bottom,
                titlebarBottom: titlebar.getBoundingClientRect().bottom,
                visualTop: visualTop,
                visualBottom: visualBottom
              });
            })()
            """.trimIndent()
        )
        assertWithMessage(promptMetrics).that(
            evaluateBoolean(
                """
                (function() {
                  var card = document.querySelector('.clarify-card');
                  var titlebar = document.querySelector('.app-titlebar');
                  var vv = window.visualViewport;
                  var visualTop = vv && vv.height > 0 ? vv.offsetTop : 0;
                  var visualBottom = vv && vv.height > 0
                    ? vv.offsetTop + vv.height
                    : window.innerHeight;
                  var available = Math.min(card.getBoundingClientRect().bottom, visualBottom)
                    - Math.max(titlebar.getBoundingClientRect().bottom, visualTop)
                    - 8;
                  var maxHeight = parseFloat(getComputedStyle(card).maxHeight);
                  return maxHeight > 0 && maxHeight <= Math.floor(available) + 1;
                })()
                """.trimIndent()
            )
        ).isTrue()
        assertThat(
            evaluateBoolean(
                """
                genericPanel.hasAttribute('data-hermes-android-vh-repaired') &&
                  genericPanel.style.overflowY !== 'auto'
                """.trimIndent()
            )
        ).isTrue()
        assertThat(
            evaluateBoolean(
                """
                (function() {
                  var card = document.querySelector('.clarify-card');
                  var titlebar = document.querySelector('.app-titlebar');
                  var rect = card.getBoundingClientRect();
                  var visualBottom = window.visualViewport && window.visualViewport.height > 0
                    ? window.visualViewport.offsetTop + window.visualViewport.height
                    : window.innerHeight;
                  return getComputedStyle(document.querySelector('.composer-wrap')).overflowY === 'visible' &&
                    !card.hasAttribute('data-hermes-android-vh-repaired') &&
                    getComputedStyle(card).pointerEvents === 'auto' &&
                    rect.height > 0 &&
                    rect.top >= titlebar.getBoundingClientRect().bottom &&
                    rect.bottom <= visualBottom + 1;
                })()
                """.trimIndent()
            )
        ).isTrue()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadFixture(body: String) {
        val loaded = CountDownLatch(1)
        composeTestRule.setContent {
            WebViewHost { view ->
                webView = view
                view.settings.javaScriptEnabled = true
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.requestFocus()
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loaded.countDown()
                    }
                }
                view.loadDataWithBaseURL(
                    "https://hermes.test/",
                    "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body>$body</body></html>",
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
        assertThat(loaded.await(WEBVIEW_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
    }

    private fun evaluate(script: String): String {
        val completed = CountDownLatch(1)
        var result: String? = null
        composeTestRule.runOnIdle {
            webView.evaluateJavascript(script) {
                result = it
                completed.countDown()
            }
        }
        assertThat(completed.await(JAVASCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
        return checkNotNull(result)
    }

    private fun evaluateBoolean(script: String): Boolean = evaluate(script) == "true"

    private fun awaitBoolean(script: String, expected: Boolean): Boolean {
        repeat(20) {
            if (evaluateBoolean(script) == expected) return true
            Thread.sleep(25)
        }
        return false
    }
}

@Composable
private fun WebViewHost(onCreated: (WebView) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).also(onCreated)
        }
    )
}
