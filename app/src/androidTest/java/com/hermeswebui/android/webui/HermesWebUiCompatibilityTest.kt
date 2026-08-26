package com.hermeswebui.android.webui

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
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
              <div id="clarifyQuestion">First question</div>
              <div id="clarifyChoices">
                <button class="clarify-choice other" tabindex="3"
                  onclick="window.__otherClicks = (window.__otherClicks || 0) + 1; clarifyInput.focus()">
                  <span>Other</span>
                </button>
              </div>
              <input id="clarifyInput" tabindex="2">
            </div>
            <div role="dialog"><input id="folderName" tabindex="1"></div>
            """.trimIndent()
        )
        evaluate(HermesWebUiScripts.suppressClarifyAutofocusScript)

        evaluate("folderName.focus()")
        assertThat(awaitBoolean("document.activeElement === folderName", expected = true)).isTrue()
        typeTextInWebView("hi")
        assertThat(awaitBoolean("folderName.value === 'hi'", expected = true)).isTrue()

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

        // Subsequent programmatic focus in the same presentation is validation/error
        // recovery and must not be mistaken for the initial prompt autofocus.
        evaluate(
            """
            clarifyInput.focus();
            clarifyInput.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
            """.trimIndent()
        )
        assertThat(awaitBoolean("document.activeElement === clarifyInput", expected = true)).isTrue()

        evaluate("clarifyInput.blur()")
        tapElement("#clarifyInput")
        assertThat(awaitBoolean("document.activeElement === clarifyInput", expected = true)).isTrue()

        evaluate("clarifyInput.blur(); window.__otherClicks = 0")
        tapElement(".clarify-choice.other")
        assertThat(awaitBoolean("document.activeElement === clarifyInput", expected = true)).isTrue()
        assertThat(awaitBoolean("window.__otherClicks === 1", expected = true)).isTrue()

        // A new prompt may replace the visible card without a hidden transition.
        // Its first automatic focus must be suppressed independently.
        evaluate(
            """
            clarifyInput.blur();
            clarifyQuestion.textContent = 'Second question';
            clarifyInput.focus();
            clarifyInput.dispatchEvent(new FocusEvent('focusin', { bubbles: true }));
            """.trimIndent()
        )
        assertThat(awaitBoolean("document.activeElement === clarifyInput", expected = false)).isTrue()

        // Hardware Tab navigation is intentional input focus for the replacement prompt.
        evaluate("clarifyQuestion.textContent = 'Third question'; folderName.focus()")
        pressTabInWebView()
        assertThat(awaitBoolean("document.activeElement === clarifyInput", expected = true)).isTrue()
    }

    @Test
    fun viewportFix_repairsCollapsedShellAndMessagesContainerOnly() {
        loadFixture(
            """
            <style>
              html, body { width: 100%; height: 100%; margin: 0; }
              #appShell {
                width: 100%;
                max-height: 0;
                overflow: hidden;
              }
              #shellContent { height: 800px; }
              .messages {
                width: 100%;
                max-height: 0;
                overflow: visible;
              }
              #messageBody {
                width: 100%;
                max-height: 0;
                overflow: visible;
              }
              #messageBody > div { height: 500px; }
            </style>
            <main id="appShell">
              <div id="shellContent">
                <button>Shell action</button>
                <section id="messages" class="messages">
                  <div id="messageBody"><div><button>Message action</button></div></div>
                </section>
              </div>
            </main>
            """.trimIndent()
        )
        evaluate(HermesWebUiScripts.viewportFixScript)
        evaluate("window.__hermesAndroidApplyViewportFix();")

        assertThat(
            evaluateBoolean(
                """
                appShell.hasAttribute('data-hermes-android-vh-repaired') &&
                  messages.hasAttribute('data-hermes-android-vh-repaired') &&
                  !messageBody.hasAttribute('data-hermes-android-vh-repaired') &&
                  parseFloat(appShell.style.maxHeight) > 0 &&
                  parseFloat(messages.style.maxHeight) > 0
                """.trimIndent()
            )
        ).isTrue()
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
                bottom: -80px;
                max-height: 0;
                overflow: hidden;
                pointer-events: none;
                z-index: 10;
              }
              .clarify-card.visible { pointer-events: auto; }
              .clarify-inner { height: 700px; }
              .generic-panel {
                position: absolute;
                top: 120px;
                left: 20px;
                width: 400px;
                max-height: 0;
                overflow-y: visible;
                z-index: 1;
              }
              .generic-panel > div { height: 600px; }
              #scrollPanel { top: 220px; }
            </style>
            <div id="fixture">
              <div class="app-titlebar"></div>
              <div class="composer-wrap"><div class="composer-flyout"></div></div>
              <div class="clarify-card visible">
                <div class="clarify-inner">
                  <button id="promptChoice" onclick="window.__promptChoiceClicks = (window.__promptChoiceClicks || 0) + 1">Choose</button>
                </div>
              </div>
              <div id="genericPanel" class="generic-panel"><div><button>Action</button></div></div>
              <div id="scrollPanel" class="generic-panel" style="overflow-y: scroll"><div><button>Scrollable action</button></div></div>
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
                  genericPanel.style.overflowY !== 'auto' &&
                  scrollPanel.hasAttribute('data-hermes-android-vh-repaired') &&
                  scrollPanel.style.overflowY === 'scroll'
                """.trimIndent()
            )
        ).isTrue()
        val interactionMetrics = evaluate(
            """
            (function() {
              var card = document.querySelector('.clarify-card');
              var titlebar = document.querySelector('.app-titlebar');
              var rect = card.getBoundingClientRect();
              var visualBottom = window.visualViewport && window.visualViewport.height > 0
                ? window.visualViewport.offsetTop + window.visualViewport.height
                : window.innerHeight;
              return JSON.stringify({
                composerOverflowY: getComputedStyle(document.querySelector('.composer-wrap')).overflowY,
                repaired: card.hasAttribute('data-hermes-android-vh-repaired'),
                pointerEvents: getComputedStyle(card).pointerEvents,
                promptShift: card.style.getPropertyValue('--hermes-android-prompt-shift'),
                cardTop: rect.top,
                cardBottom: rect.bottom,
                cardHeight: rect.height,
                titlebarBottom: titlebar.getBoundingClientRect().bottom,
                visualBottom: visualBottom
              });
            })()
            """.trimIndent()
        )
        assertWithMessage(interactionMetrics).that(
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
                    parseFloat(card.style.getPropertyValue('--hermes-android-prompt-shift')) > 0 &&
                    rect.height > 0 &&
                    rect.top >= titlebar.getBoundingClientRect().bottom &&
                    rect.bottom <= visualBottom + 1;
                })()
                """.trimIndent()
            )
        ).isTrue()

        evaluate("window.__promptChoiceClicks = 0")
        tapElement("#promptChoice")
        assertThat(awaitBoolean("window.__promptChoiceClicks === 1", expected = true)).isTrue()

        evaluate("scrollPanel.style.display = 'none'")
        Thread.sleep(150)
        evaluate("window.__hermesAndroidApplyViewportFix();")
        assertThat(
            evaluateBoolean(
                """
                !scrollPanel.hasAttribute('data-hermes-android-vh-repaired') &&
                  scrollPanel.style.overflowY === 'scroll'
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

    private fun tapElement(selector: String) {
        val coordinates = JSONArray(
            evaluate(
                """
                (function() {
                  var element = document.querySelector(${org.json.JSONObject.quote(selector)});
                  var rect = element.getBoundingClientRect();
                  return [rect.left + rect.width / 2, rect.top + rect.height / 2, window.innerWidth];
                })()
                """.trimIndent()
            )
        )
        val cssX = coordinates.getDouble(0).toFloat()
        val cssY = coordinates.getDouble(1).toFloat()
        val cssViewportWidth = coordinates.getDouble(2).toFloat()
        composeTestRule.runOnIdle {
            val scale = webView.width / cssViewportWidth
            val x = cssX * scale
            val y = cssY * scale
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP, x, y, 0)
            try {
                webView.dispatchTouchEvent(down)
                webView.dispatchTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
        }
    }

    private fun pressTabInWebView() {
      pressKeysInWebView(KeyEvent.KEYCODE_TAB)
    }

    private fun pressKeysInWebView(vararg keyCodes: Int) {
        composeTestRule.runOnIdle {
        keyCodes.forEach { keyCode ->
          webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
          webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
      }
    }

    private fun typeTextInWebView(text: String) {
      val events = checkNotNull(
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
      )
      composeTestRule.runOnIdle {
        events.forEach(webView::dispatchKeyEvent)
        }
    }

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
