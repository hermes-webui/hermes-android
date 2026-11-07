package com.hermeswebui.android.webui

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.webkit.WebViewCompat
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.core.security.WebTrustPolicy
import com.hermeswebui.android.webview.HermesMediaPlaybackPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class HermesWebUiCompatibilityTest {
    private companion object {
        const val WEBVIEW_LOAD_TIMEOUT_SECONDS = 60L
        const val JAVASCRIPT_TIMEOUT_SECONDS = 30L
    }

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var webView: WebView

    @Test
    fun serverTypeProbeRequiresExplicitFrontendMarkerAndExactOrigin() {
        loadFixture("""<title>Hermes WebUI</title><div class="layout"><main class="main">Sign in</main></div>""")
        val probe = HermesWebUiScripts.buildServerTypeProbeScript("https://hermes.test")
        assertThat(evaluate(probe)).isEqualTo("\"unspecified\"")
        evaluate("window.__HERMES_WEBUI_BUNDLE_VERSION__ = 'test';")
        assertThat(evaluate(probe)).isEqualTo("\"webui\"")
        evaluate("window.__HERMES_UI_SURFACE__ = 'webapp';")
        assertThat(evaluate(probe)).isEqualTo("\"agent\"")
        assertThat(evaluate(HermesWebUiScripts.buildServerTypeProbeScript("https://hermes.test:8471")))
            .isEqualTo("\"unspecified\"")
    }

    @Test
    fun officialWebapp_keepsReactEditorAndSidebarOwnedByThePage() {
        loadFixture(body = """
            <script>window.__HERMES_UI_SURFACE__ = 'webapp';</script>
            <nav class="sidebar"><button id="settings">Settings</button></nav>
            <textarea id="composer"></textarea>
        """.trimIndent(), documentStartScripts = listOf(
            HermesWebUiScripts.enterKeyNewlineScript,
            HermesWebUiScripts.appSettingsEntryScript
        ))
        evaluate("""
            window.__editorEvents = 0;
            composer.addEventListener('keydown', function() { window.__editorEvents++; });
            composer.dispatchEvent(new KeyboardEvent('keydown', {
              key: 'Enter', bubbles: true, cancelable: true
            }));
            requestAnimationFrame(function() {
              requestAnimationFrame(function() { window.__sidebarSettled = true; });
            });
        """.trimIndent())

        assertThat(awaitBoolean("window.__sidebarSettled === true", expected = true)).isTrue()
        assertThat(evaluate("window.__editorEvents")).isEqualTo("1")
        assertThat(evaluate("composer.value")).isEqualTo("\"\"")
        assertThat(awaitBoolean(
            "document.querySelector('[data-hermes-android-app-settings-entry]') === null",
            expected = true
        )).isTrue()
    }

    @Test
    fun officialWebapp_probeRequiresExactOriginAndReadyPageRequiresRendererRoot() {
        loadFixture("""
            <script>window.__HERMES_UI_SURFACE__ = 'webapp';</script>
        """.trimIndent())
        assertThat(evaluate(HermesWebUiScripts.buildOfficialWebappProbeScript("https://hermes.test"))).isEqualTo("true")
        assertThat(evaluate(HermesWebUiScripts.buildOfficialWebappProbeScript("https://hermes.test:8471"))).isEqualTo("false")
        assertThat(evaluate(HermesWebUiScripts.hermesPageReadyScript)).isEqualTo("false")
        evaluate("var root = document.createElement('div'); root.id = 'root'; document.body.appendChild(root);")
        assertThat(evaluate(HermesWebUiScripts.hermesPageReadyScript)).isEqualTo("true")
        evaluate("window.__HERMES_UI_SURFACE__ = 'dashboard';")
        assertThat(evaluate(HermesWebUiScripts.hermesPageReadyScript)).isEqualTo("false")
    }

    @Test
    fun officialWebapp_viewportPreservesUpstreamKeyboardSizingAndSidebarStyles() {
        loadFixture("""
            <script>window.__HERMES_UI_SURFACE__ = 'webapp';</script>
            <style>
              #root[data-browser-viewport] { height: var(--browser-viewport-height); }
              .sidebar { min-height: 17px; }
            </style>
            <div id="root" data-browser-viewport style="--browser-viewport-height:300px">
              <nav class="sidebar">Workspace</nav>
            </div>
        """.trimIndent())
        evaluate(HermesWebUiScripts.viewportFixScript)
        assertThat(awaitBoolean(
            "document.getElementById('hermes-android-viewport-fix') !== null", expected = true
        )).isTrue()
        assertThat(evaluate("getComputedStyle(document.getElementById('root')).height")).isEqualTo("\"300px\"")
        assertThat(evaluate("getComputedStyle(document.querySelector('.sidebar')).minHeight")).isEqualTo("\"17px\"")
    }

    @After
    fun destroyWebView() {
        if (::webView.isInitialized) {
            composeTestRule.runOnIdle {
                webView.destroy()
            }
        }
    }

    @Test
    fun mediaPlaybackPolicy_tightensBeforeProviderOrWrongPortNavigation() {
        loadFixture(body = "<div>Hermes</div>", historyUrl = "https://hermes.test/")
        val policy = WebTrustPolicy(UrlPolicy(setOf("hermes.test")), "https://hermes.test", "")

        composeTestRule.runOnIdle {
            HermesMediaPlaybackPolicy.onDocumentReady(webView, policy)
            assertThat(webView.settings.mediaPlaybackRequiresUserGesture).isFalse()

            HermesMediaPlaybackPolicy.beforeAllowedMainFrameNavigation(webView, "https://provider.test/login", policy)
            assertThat(webView.settings.mediaPlaybackRequiresUserGesture).isTrue()
            // No commit yet: a trusted redirect must not loosen the pre-navigation restriction.
            HermesMediaPlaybackPolicy.beforeAllowedMainFrameNavigation(webView, "https://hermes.test/session/1", policy)
            assertThat(webView.settings.mediaPlaybackRequiresUserGesture).isTrue()

            HermesMediaPlaybackPolicy.onDocumentReady(webView, policy)
            HermesMediaPlaybackPolicy.beforeAllowedMainFrameNavigation(webView, "https://hermes.test:8789", policy)
            assertThat(webView.settings.mediaPlaybackRequiresUserGesture).isTrue()
        }
    }

    @Test
    fun mediaPlaybackPolicy_rechecksTheActualProviderDocument() {
        loadFixture(body = "<div>Provider</div>", baseUrl = "https://provider.test/", historyUrl = "https://provider.test/")
        val policy = WebTrustPolicy(UrlPolicy(setOf("hermes.test")), "https://hermes.test", "")

        composeTestRule.runOnIdle {
            webView.settings.mediaPlaybackRequiresUserGesture = false
            HermesMediaPlaybackPolicy.onDocumentReady(webView, policy)
            assertThat(webView.settings.mediaPlaybackRequiresUserGesture).isTrue()
        }
    }

    @Test
    fun runtimeOriginGuard_staleHermesCallbackDoesNotMutateCurrentProviderPage() {
        loadFixture(
            body = "<div id=\"provider-page\">OAuth provider</div>",
            baseUrl = "https://oauth.provider.test/"
        )
        val guardedPinchZoomScript = HermesWebUiScripts.buildOriginGuardedRuntimeScript(
            trustedOrigin = "https://hermes.test",
            script = HermesWebUiScripts.pinchZoomScript
        )

        // Models a delayed runtime fallback queued from a stale Hermes page callback: the
        // current document is already the provider page when evaluateJavascript executes.
        evaluate(guardedPinchZoomScript)

        assertThat(evaluate("document.querySelector('meta[name=\"viewport\"]').content"))
            .isEqualTo("\"width=device-width,initial-scale=1\"")
        assertThat(evaluateBoolean("window.location.origin === 'https://oauth.provider.test'"))
            .isTrue()
    }

    @Test
    fun runtimeOriginGuard_executesOnConfiguredHermesOrigin() {
        loadFixture(body = "<div>Hermes</div>")
        val guardedPinchZoomScript = HermesWebUiScripts.buildOriginGuardedRuntimeScript(
            trustedOrigin = "https://hermes.test",
            script = HermesWebUiScripts.pinchZoomScript
        )

        evaluate(guardedPinchZoomScript)

        assertThat(evaluate("document.querySelector('meta[name=\"viewport\"]').content"))
            .isEqualTo("\"width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes\"")
    }

    @Test
    fun runtimeOriginGuard_resistsProviderPageReplacingTheUrlConstructor() {
        loadFixture(
            body = "<div id=\"provider-page\">OAuth provider</div>",
            baseUrl = "https://oauth.provider.test/"
        )

        // A hostile/foreign page can replace window.URL before the delayed evaluateJavascript
        // runs. A guard that resolved its trusted origin through `new URL(...)` would get this
        // page's own origin back and execute. The guard must compare a literal instead.
        evaluate(
            """
            window.URL = function() { return { origin: window.location.origin }; };
            """.trimIndent()
        )

        val guardedPinchZoomScript = HermesWebUiScripts.buildOriginGuardedRuntimeScript(
            trustedOrigin = "https://hermes.test",
            script = HermesWebUiScripts.pinchZoomScript
        )
        evaluate(guardedPinchZoomScript)

        assertThat(evaluate("document.querySelector('meta[name=\"viewport\"]').content"))
            .isEqualTo("\"width=device-width,initial-scale=1\"")
        assertThat(evaluateBoolean("window.location.origin === 'https://oauth.provider.test'"))
            .isTrue()
    }

    @Test
    fun textInput_usesAndroidEditorWithoutWindowFocus() {
        loadFixture("""<input id="folderName">""")
        try {
            composeTestRule.runOnIdle {
                composeTestRule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
            composeTestRule.waitUntil(timeoutMillis = JAVASCRIPT_TIMEOUT_SECONDS * 1_000) {
                var unfocused = false
                composeTestRule.runOnIdle { unfocused = !webView.hasWindowFocus() }
                unfocused
            }
            evaluate("folderName.focus()")
            assertThat(awaitBoolean("document.activeElement === folderName", expected = true)).isTrue()
            typeTextInWebView("hi")
            assertThat(awaitBoolean("folderName.value === 'hi'", expected = true)).isTrue()
        } finally {
            composeTestRule.runOnIdle {
                composeTestRule.activity.window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
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
    fun viewportFix_stopsMutatingIdlePageButRepairsNewContent() {
        loadFixture("<main id=\"shell\"><button>Ready</button></main>")
        evaluate(HermesWebUiScripts.viewportFixScript)
        evaluate(
            """
            window.__hermesAndroidApplyViewportFix();
            var panel = document.createElement('section');
            panel.id = 'dynamicPanel';
            panel.style.cssText = 'width: 400px; max-height: 0; overflow: hidden';
            panel.innerHTML = '<div style="height: 700px"><button>New action</button></div>';
            document.body.appendChild(panel);
            """.trimIndent()
        )
        assertThat(
            awaitBoolean(
                "dynamicPanel.hasAttribute('data-hermes-android-vh-repaired')",
                expected = true
            )
        ).isTrue()
        evaluate("dynamicPanel.style.maxHeight = '0px'")
        assertThat(
            awaitBoolean("parseFloat(dynamicPanel.style.maxHeight) > 0", expected = true)
        ).isTrue()

        evaluate(
            """
            window.__viewportMutations = 0;
            window.__viewportMutationObserver = new MutationObserver(function(mutations) {
              var style = document.getElementById('hermes-android-viewport-fix');
              mutations.forEach(function(mutation) {
                if ((mutation.target === document.documentElement && mutation.attributeName === 'style') ||
                    (style && (mutation.target === style || style.contains(mutation.target)))) {
                  window.__viewportMutations++;
                }
              });
            });
            window.__viewportMutationObserver.observe(document.documentElement, {
              attributes: true, attributeFilter: ['style'], childList: true, subtree: true
            });
            """.trimIndent()
        )

        Thread.sleep(300)
        val idleBaseline = evaluate("window.__viewportMutations").toInt()
        Thread.sleep(400)
        assertThat(evaluate("window.__viewportMutations").toInt()).isEqualTo(idleBaseline)

        evaluate("dynamicPanel.style.display = 'none'")
        assertThat(
            awaitBoolean(
                "!dynamicPanel.hasAttribute('data-hermes-android-vh-repaired')",
                expected = true
            )
        ).isTrue()
        evaluate("window.__viewportMutationObserver.disconnect();")
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

    // --- Shim behavior (Issues #34, #83, #1/#21, #53, #59): driven through real key and DOM events,
    // not by checking that the script text contains a string. ---

    private val composerFixture = """
        <textarea id="composer"></textarea>
        <script>
          window.__submits = 0;
          // Stands in for WebUI's own handler, registered after the document-start shim.
          composer.addEventListener('keydown', function(e) {
            if (e.key === 'Enter' && !e.shiftKey) { window.__submits++; e.preventDefault(); }
          });
        </script>
    """.trimIndent()

    private fun focusComposerAndType(text: String) {
        evaluate("composer.focus()")
        assertThat(awaitBoolean("document.activeElement === composer", expected = true)).isTrue()
        typeTextInWebView(text)
        assertThat(awaitBoolean("composer.value === ${org.json.JSONObject.quote(text)}", expected = true)).isTrue()
    }

    @Test
    fun enterKey_withoutHardwareKeyboard_insertsNewlineAndDoesNotSubmit() {
        loadFixture(composerFixture, documentStartScripts = listOf(HermesWebUiScripts.enterKeyNewlineScript))
        focusComposerAndType("ab")

        pressKeysInWebView(KeyEvent.KEYCODE_ENTER)

        assertThat(awaitBoolean("composer.value === 'ab\\n'", expected = true)).isTrue()
        assertThat(evaluate("window.__submits")).isEqualTo("0")
    }

    @Test
    fun enterKey_followsTheHardwareKeyboardFlagAtEventTimeWithoutReload() {
        loadFixture(composerFixture, documentStartScripts = listOf(HermesWebUiScripts.enterKeyNewlineScript))
        focusComposerAndType("ab")

        // Keyboard attached after the page loaded: the shim must step aside so WebUI submits.
        evaluate("window.__hermesAndroidHardwareKeyboard = true")
        pressKeysInWebView(KeyEvent.KEYCODE_ENTER)
        assertThat(awaitBoolean("window.__submits === 1", expected = true)).isTrue()
        assertThat(evaluate("composer.value")).isEqualTo("\"ab\"")

        // Detached again: newline forcing resumes, still without a reload.
        evaluate("window.__hermesAndroidHardwareKeyboard = false")
        pressKeysInWebView(KeyEvent.KEYCODE_ENTER)
        assertThat(awaitBoolean("composer.value === 'ab\\n'", expected = true)).isTrue()
        assertThat(evaluate("window.__submits")).isEqualTo("1")
    }

    @Test
    fun microphoneFallback_forcesMediaRecorderAndDisablesSpeechRecognition() {
        loadFixture(
            "<div>Hermes</div>",
            documentStartScripts = listOf(HermesWebUiScripts.microphoneFallbackScript)
        )
        assertThat(evaluate("window.localStorage.getItem('mic_force_mediarecorder')")).isEqualTo("\"1\"")
        assertThat(evaluate("typeof window.SpeechRecognition")).isEqualTo("\"undefined\"")
        assertThat(evaluate("typeof window.webkitSpeechRecognition")).isEqualTo("\"undefined\"")
        assertThat(evaluate("window.__hermesAndroidMicForceMediaRecorder")).isEqualTo("true")
    }

    @Test
    fun microphoneFallback_dropsDeviceConstraintsButKeepsOtherAudioOptions() {
        // The stub runs first so the shim wraps it, exactly as it wraps the real getUserMedia.
        val stub = """
            (function() {
              if (!navigator.mediaDevices) return;
              navigator.mediaDevices.getUserMedia = function(c) { window.__gumConstraints = c; return Promise.resolve(null); };
            })();
        """.trimIndent()
        loadFixture(
            "<div>Hermes</div>",
            documentStartScripts = listOf(stub, HermesWebUiScripts.microphoneFallbackScript)
        )
        assertThat(evaluate("!!navigator.mediaDevices")).isEqualTo("true")

        evaluate("navigator.mediaDevices.getUserMedia({ audio: { deviceId: 'x', groupId: 'y', echoCancellation: true } }); 0")
        assertThat(evaluate("JSON.stringify(window.__gumConstraints)"))
            .isEqualTo("\"{\\\"audio\\\":{\\\"echoCancellation\\\":true}}\"")

        evaluate("navigator.mediaDevices.getUserMedia({ audio: { deviceId: 'only-a-device' } }); 0")
        assertThat(evaluate("JSON.stringify(window.__gumConstraints)")).isEqualTo("\"{\\\"audio\\\":true}\"")

        // Non-audio requests pass through untouched.
        evaluate("navigator.mediaDevices.getUserMedia({ video: true }); 0")
        assertThat(evaluate("JSON.stringify(window.__gumConstraints)")).isEqualTo("\"{\\\"video\\\":true}\"")
    }

    private val settingsPageFixture = """
        <div class="main showing-settings">
          <div class="main-view" id="settingsView" style="max-height:300px; overflow:hidden;">
            <div style="height:1200px">Settings content</div>
          </div>
        </div>
    """.trimIndent()

    @Test
    fun settingsPage_isNotClippedOnANarrowPhoneLayout() {
        loadFixture(settingsPageFixture, widthDp = 360)
        assertThat(evaluate("innerWidth <= 600")).isEqualTo("true")
        evaluate(HermesWebUiScripts.viewportFixScript)

        assertThat(awaitBoolean(
            "getComputedStyle(settingsView).maxHeight === 'none'", expected = true
        )).isTrue()
        assertThat(evaluate("getComputedStyle(settingsView).overflowY")).isEqualTo("\"auto\"")
    }

    @Test
    fun settingsPage_keepsItsOwnLayoutOnAWideScreen() {
        loadFixture(settingsPageFixture, widthDp = 900)
        assumeTrue("needs a viewport wider than 600 CSS px", evaluate("innerWidth > 600") == "true")
        evaluate(HermesWebUiScripts.viewportFixScript)
        assertThat(awaitBoolean(
            "document.getElementById('hermes-android-viewport-fix') !== null", expected = true
        )).isTrue()

        assertThat(evaluate("getComputedStyle(settingsView).maxHeight")).isEqualTo("\"300px\"")
    }

    private val sidebarFixture = """
        <nav class="rail" style="display:flex;flex-direction:column;width:200px">
          <button class="rail-btn nav-tab" data-panel="chat" aria-label="Chat">Chat</button>
          <button class="rail-btn nav-tab" id="settingsBtn" data-panel="settings" aria-label="Settings">Settings</button>
          <button class="rail-btn nav-tab" id="helpBtn" aria-label="Help">Help</button>
        </nav>
    """.trimIndent()

    private val entrySelector = "[data-hermes-android-app-settings-entry=\"1\"]"

    private fun entryCount(): String = evaluate("document.querySelectorAll('$entrySelector').length")

    private fun entryFollows(id: String): Boolean = evaluateBoolean(
        "(function(){var e=document.querySelector('$entrySelector');return !!e && e.previousElementSibling === document.getElementById('$id');})()"
    )

    /** Any DOM mutation schedules the injector's next pass. */
    private fun poke() {
        evaluate("document.body.appendChild(document.createElement('i')); 0")
    }

    @Test
    fun appSettingsEntry_isInjectedOnceDirectlyAfterSettings() {
        loadFixture(sidebarFixture, documentStartScripts = listOf(HermesWebUiScripts.appSettingsEntryScript))

        assertThat(awaitBoolean("document.querySelectorAll('$entrySelector').length === 1", expected = true)).isTrue()
        assertThat(entryFollows("settingsBtn")).isTrue()
        assertThat(evaluate("document.querySelector('$entrySelector').getAttribute('href')"))
            .isEqualTo("\"hermes://app/settings\"")
        // Unrelated DOM churn (streamed messages) must never duplicate it.
        repeat(5) { poke() }
        Thread.sleep(300)
        assertThat(entryCount()).isEqualTo("1")
    }

    @Test
    fun appSettingsEntry_isRestoredWhenWebUiRemovesOrMovesIt() {
        loadFixture(sidebarFixture, documentStartScripts = listOf(HermesWebUiScripts.appSettingsEntryScript))
        assertThat(awaitBoolean("document.querySelectorAll('$entrySelector').length === 1", expected = true)).isTrue()

        evaluate("document.querySelector('$entrySelector').remove(); 0")
        poke()
        assertThat(awaitBoolean("document.querySelectorAll('$entrySelector').length === 1", expected = true)).isTrue()
        assertThat(entryFollows("settingsBtn")).isTrue()

        evaluate("(function(){var e=document.querySelector('$entrySelector'); e.parentNode.insertBefore(e, e.parentNode.firstChild);})()")
        poke()
        assertThat(awaitBoolean(
            "(function(){var e=document.querySelector('$entrySelector');return !!e && e.previousElementSibling === document.getElementById('settingsBtn');})()",
            expected = true
        )).isTrue()
        assertThat(entryCount()).isEqualTo("1")
    }

    @Test
    fun appSettingsEntry_movesFromTheHelpFallbackOnceSettingsAppears() {
        // Older or changed markup may only have Help; Settings is the real anchor when it shows up.
        loadFixture(
            """<nav class="rail" style="display:flex;flex-direction:column;width:200px">
                 <button class="rail-btn nav-tab" id="helpBtn" aria-label="Help">Help</button>
               </nav>""",
            documentStartScripts = listOf(HermesWebUiScripts.appSettingsEntryScript)
        )
        assertThat(awaitBoolean("document.querySelectorAll('$entrySelector').length === 1", expected = true)).isTrue()
        assertThat(entryFollows("helpBtn")).isTrue()

        evaluate(
            "(function(){var b=document.createElement('button'); b.className='rail-btn nav-tab'; b.id='settingsBtn';" +
                "b.setAttribute('aria-label','Settings'); b.setAttribute('data-panel','settings'); b.textContent='Settings';" +
                "document.querySelector('.rail').insertBefore(b, document.getElementById('helpBtn'));})()"
        )
        poke()
        assertThat(awaitBoolean(
            "(function(){var e=document.querySelector('$entrySelector');return !!e && e.previousElementSibling === document.getElementById('settingsBtn');})()",
            expected = true
        )).isTrue()
        assertThat(entryCount()).isEqualTo("1")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadFixture(
        body: String,
        baseUrl: String = "https://hermes.test/",
        historyUrl: String? = null,
        documentStartScripts: List<String> = emptyList(),
        widthDp: Int? = null
    ) {
        val loaded = CountDownLatch(1)
        composeTestRule.setContent {
            WebViewHost(widthDp) { view ->
                webView = view
                view.settings.javaScriptEnabled = true
                // Production enables DOM storage (MainActivity); the mic shim persists a flag in it.
                view.settings.domStorageEnabled = true
                documentStartScripts.forEach { script ->
                    WebViewCompat.addDocumentStartJavaScript(view, script, setOf("https://hermes.test"))
                }
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loaded.countDown()
                    }
                }
                view.loadDataWithBaseURL(
                    baseUrl,
                    "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body>$body</body></html>",
                    "text/html",
                    "UTF-8",
                    historyUrl
                )
            }
        }
        assertThat(loaded.await(WEBVIEW_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
        composeTestRule.runOnIdle { webView.requestFocus() }
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
        var connection: InputConnection? = null
        composeTestRule.waitUntil(timeoutMillis = JAVASCRIPT_TIMEOUT_SECONDS * 1_000) {
            composeTestRule.runOnIdle {
                if (webView.onCheckIsTextEditor()) {
                    connection = webView.onCreateInputConnection(EditorInfo())
                }
            }
            connection != null
        }
        // Exercise WebView's Android IME path, not whichever window owns injected keys.
        composeTestRule.runOnIdle {
            assertThat(checkNotNull(connection).commitText(text, 1)).isTrue()
        }
    }

    private fun awaitBoolean(script: String, expected: Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (evaluateBoolean(script) == expected) return true
            Thread.sleep(25)
        }
        return false
    }
}

@Composable
private fun WebViewHost(widthDp: Int? = null, onCreated: (WebView) -> Unit) {
    AndroidView(
        modifier = if (widthDp == null) Modifier.fillMaxSize() else Modifier.width(widthDp.dp).fillMaxHeight(),
        factory = { context ->
            WebView(context).also(onCreated)
        }
    )
}
