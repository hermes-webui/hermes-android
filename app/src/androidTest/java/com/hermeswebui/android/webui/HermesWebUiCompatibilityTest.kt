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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
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

    // Lets a test resize the real WebView host (a deterministic stand-in for an IME with adjustResize).
    private val hostHeightDp = mutableStateOf<Int?>(null)

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

    // ---- Issue #190: the Hermes Agent Dashboard configured as the server URL ----

    // Reduction of the Dashboard shell (hermes-agent web/src/App.tsx): the root is
    // `flex h-dvh max-h-dvh min-h-0 flex-col overflow-hidden` and Android WebView computes dvh
    // as 0px, which the explicit zero heights stand in for. Only the fixed mobile header escapes
    // the clip, matching the "only the Hermes Agent header shows" report.
    private fun dashboardShellFixture(pageHeightPx: Int) = """
        <style>html, body { margin: 0; }</style>
        <div id="reactRoot">
          <div id="appRoot" style="display:flex; flex-direction:column; height:0; max-height:0; min-height:0; overflow:hidden">
            <div aria-hidden="true" style="position:fixed; inset:0; pointer-events:none"></div>
            <header style="position:fixed; top:0; left:0; right:0; height:56px; z-index:40; background:#eee">Hermes Agent</header>
            <div style="height:56px; flex-shrink:0"></div>
            <div id="contentWrapper" style="display:flex; flex-direction:column; flex:1; min-height:0; min-width:0; overflow:hidden">
              <div style="display:flex; flex:1; min-height:0">
                <div style="flex:1; min-width:0">
                  <div id="pageBody" style="position:relative; height:${pageHeightPx}px">
                    <button id="pageAction" style="position:absolute; left:16px; top:16px; height:44px"
                      onclick="window.__pageClicks = (window.__pageClicks || 0) + 1">Sessions</button>
                    <button id="bottomAction" style="position:absolute; left:16px; height:44px"
                      onclick="window.__bottomClicks = (window.__bottomClicks || 0) + 1">Bottom</button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <script>bottomAction.style.top = (innerHeight - 56 - 60) + 'px';</script>
    """.trimIndent()

    private fun isHittable(id: String) =
        "(function(e){var r=e.getBoundingClientRect();" +
            "return document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2) === e;})($id)"

    @Test
    fun dashboardShell_collapsedRootIsRestoredInASingleRepairPass() {
        loadFixture(dashboardShellFixture(pageHeightPx = 600))
        // Control: the fixture reproduces the report before any repair.
        assertThat(evaluate("appRoot.getBoundingClientRect().height")).isEqualTo("0")
        assertThat(evaluate(isHittable("pageAction"))).isEqualTo("false")

        // Install and run exactly one scan synchronously, measuring before any
        // MutationObserver-triggered rescan can mask a partial first pass.
        val metrics = org.json.JSONObject(
            org.json.JSONTokener(
                evaluate(
                    HermesWebUiScripts.viewportFixScript + "\n" +
                        "window.__hermesAndroidApplyViewportFix();\n" +
                        "JSON.stringify({ root: appRoot.getBoundingClientRect().height, " +
                        "inner: innerHeight, hit: ${isHittable("pageAction")} });"
                )
            ).nextValue() as String
        )
        assertWithMessage(metrics.toString()).that(metrics.getBoolean("hit")).isTrue()
        assertWithMessage(metrics.toString())
            .that(metrics.getDouble("root")).isWithin(1.0).of(metrics.getDouble("inner"))

        tapElement("#pageAction")
        assertThat(awaitBoolean("window.__pageClicks === 1", expected = true)).isTrue()
    }

    @Test
    fun dashboardShell_tallContentIsNotClippedAtThePanelCap() {
        loadFixture(dashboardShellFixture(pageHeightPx = 3000))
        installShim()
        // Let observer-driven rescans settle; the repaired root must stay at the full viewport.
        rescan()

        assertThat(evaluateBoolean(
            "Math.abs(appRoot.getBoundingClientRect().height - innerHeight) <= 1"
        )).isTrue()
        assertThat(evaluate(isHittable("bottomAction"))).isEqualTo("true")
        tapElement("#bottomAction")
        assertThat(awaitBoolean("window.__bottomClicks === 1", expected = true)).isTrue()
    }

    @Test
    fun dashboardShell_repairLeavesAFixedLayerPortalAlone() {
        // A zero-height portal that only hosts position:fixed layers clips nothing.
        loadFixture("""
            <style>html, body { margin: 0; }</style>
            <div id="portal" style="overflow:hidden">
              <div style="position:fixed; inset:0; pointer-events:none">
                <div style="height:600px"><button>Dialog action</button></div>
              </div>
            </div>
        """.trimIndent())
        installShim()

        assertThat(evaluate("portal.hasAttribute('data-hermes-android-vh-repaired')")).isEqualTo("false")
        assertThat(evaluate("portal.getBoundingClientRect().height")).isEqualTo("0")
    }

    @Test
    fun dashboardShell_repairNeverTreatsContentBelowAnInFlowHeaderAsThePageShell() {
        // WebUI-shaped page: an in-flow titlebar occupies the page, so a zero-height clipping
        // region below it is not the page root and must not be stretched to the viewport.
        loadFixture("""
            <style>html, body { margin: 0; }</style>
            <header style="height:48px">Titlebar</header>
            <div id="region" style="display:flex; flex-direction:column; height:0; overflow:hidden">
              <div style="height:40px"><button>Action</button></div>
            </div>
        """.trimIndent())
        installShim()

        assertThat(evaluate("region.hasAttribute('data-hermes-android-vh-repaired')")).isEqualTo("false")
        assertThat(evaluate("region.getBoundingClientRect().height")).isEqualTo("0")
    }

    @Test
    fun dashboardAsServer_getsNoWebUiOnlyEditorOrSidebarShims() {
        // The Dashboard sets neither WebUI's bundle marker nor the Webapp surface marker.
        loadFixture(body = """
            <nav><a id="settings" href="#settings">Settings</a></nav>
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
        assertThat(evaluate("document.querySelector('[data-hermes-android-app-settings-entry]') === null"))
            .isEqualTo("true")
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
            <script>window.__HERMES_WEBUI_BUNDLE_VERSION__ = 'fixture';</script>
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
        <script>window.__HERMES_WEBUI_BUNDLE_VERSION__ = 'fixture';</script>
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

    /**
     * Stands in for the Android `addJavascriptInterface`/WebMessage bridge: records every message the
     * facade posts and answers like HermesNotificationBridgeCoordinator would.
     */
    private fun notificationBridgeStub(permission: String, deliveryOk: Boolean = true) = """
        (function() {
          window.__bridgeLog = [];
          var bridge = { postMessage: function(raw) {
            var message = JSON.parse(raw);
            window.__bridgeLog.push(message);
            var reply = { id: message.id, ok: $deliveryOk, permission: '$permission' };
            setTimeout(function() { bridge.onmessage({ data: JSON.stringify(reply) }); }, 0);
          } };
          window.TestNotificationBridge = bridge;
        })();
    """.trimIndent()

    private fun notificationFacadeScripts(permission: String, deliveryOk: Boolean = true) = listOf(
        notificationBridgeStub(permission, deliveryOk),
        HermesWebUiScripts.buildNotificationBridgeScript("TestNotificationBridge", permission)
    )

    /** The `show` messages the facade posted, as a JSON array string (evaluate() JSON-encodes the string). */
    private fun shownMessages(): String = org.json.JSONTokener(
        evaluate("JSON.stringify(window.__bridgeLog.filter(function(m) { return m.type === 'show'; }))")
    ).nextValue() as String

    @Test
    fun notificationFacade_grantedConstructorPostsOnlyWhitelistedFieldsAndFiresOnshow() {
        loadFixture("<div>Hermes</div>", documentStartScripts = notificationFacadeScripts("granted"))
        assertThat(evaluate("Notification.permission")).isEqualTo("\"granted\"")

        evaluate(
            """
            window.__shown = false;
            var n = new Notification('Build done', {
              body: 'ok', tag: 't1', data: { url: '/session/abc', token: 'secret' }, vibrate: [1], image: 'x'
            });
            n.onshow = function() { window.__shown = true; };
            0
            """.trimIndent()
        )
        assertThat(awaitBoolean("window.__shown === true", expected = true)).isTrue()
        val shown = org.json.JSONArray(shownMessages())
        assertThat(shown.length()).isEqualTo(1)
        val payload = shown.getJSONObject(0).getJSONObject("payload")
        assertThat(payload.getString("title")).isEqualTo("Build done")
        val options = payload.getJSONObject("options")
        assertThat(options.getString("body")).isEqualTo("ok")
        assertThat(options.getString("tag")).isEqualTo("t1")
        // Only data.url crosses to Android; arbitrary page data and unknown options never do.
        assertThat(options.getJSONObject("data").getString("url")).isEqualTo("/session/abc")
        assertThat(options.getJSONObject("data").has("token")).isFalse()
        assertThat(options.has("vibrate")).isFalse()
        assertThat(options.has("image")).isFalse()
    }

    @Test
    fun notificationFacade_deniedPermissionThrowsAndNeverReachesAndroid() {
        loadFixture("<div>Hermes</div>", documentStartScripts = notificationFacadeScripts("denied"))
        assertThat(evaluate("Notification.permission")).isEqualTo("\"denied\"")
        assertThat(evaluate("(function() { try { new Notification('x'); return 'no throw'; } catch (e) { return e.name; } })()"))
            .isEqualTo("\"NotAllowedError\"")
        assertThat(shownMessages()).isEqualTo("[]")
    }

    @Test
    fun notificationFacade_failedDeliveryRaisesOnerrorInsteadOfShow() {
        loadFixture("<div>Hermes</div>", documentStartScripts = notificationFacadeScripts("granted", deliveryOk = false))
        evaluate(
            """
            window.__outcome = '';
            var n = new Notification('x');
            n.onshow = function() { window.__outcome = 'show'; };
            n.onerror = function(e) { window.__outcome = 'error:' + e.name; };
            0
            """.trimIndent()
        )
        assertThat(awaitBoolean("window.__outcome === 'error:AbortError'", expected = true)).isTrue()
    }

    @Test
    fun notificationFacade_reinjectionUpdatesPermissionWithoutReinstalling() {
        loadFixture("<div>Hermes</div>", documentStartScripts = notificationFacadeScripts("granted"))
        val constructorBefore = evaluate("window.__constructorProbe = Notification; 0")
        assertThat(constructorBefore).isEqualTo("0")
        // MainActivity re-runs the script when Android's permission changes; it must only update state.
        evaluate(HermesWebUiScripts.buildNotificationBridgeScript("TestNotificationBridge", "denied"))
        assertThat(evaluate("Notification.permission")).isEqualTo("\"denied\"")
        assertThat(evaluate("Notification === window.__constructorProbe")).isEqualTo("true")
    }

    @Test
    fun notificationFacade_serviceWorkerShowNotificationRoutesThroughTheBridge() {
        loadFixture("<div>Hermes</div>", documentStartScripts = notificationFacadeScripts("granted"))
        assumeTrue(
            "ServiceWorkerRegistration needs a secure context",
            evaluate("!!(window.ServiceWorkerRegistration && ServiceWorkerRegistration.prototype)") == "true"
        )
        assertThat(awaitBoolean("ServiceWorkerRegistration.prototype.__hermesAndroidNotificationsPatched === true", expected = true)).isTrue()
        evaluate(
            "window.__swDone = false; ServiceWorkerRegistration.prototype.showNotification.call({}, 'sw', { body: 'b' })" +
                ".then(function() { window.__swDone = true; }); 0"
        )
        assertThat(awaitBoolean("window.__swDone === true", expected = true)).isTrue()
        assertThat(org.json.JSONArray(shownMessages()).getJSONObject(0).getJSONObject("payload").getString("title"))
            .isEqualTo("sw")
    }

    // WebUI builds its conversation long-press menu from a ~400ms touch timer and renders it as a
    // position:fixed element capped with a viewport-unit max-height (Issues #6, #35). The real menu
    // (.session-action-menu) holds many action buttons, so its content clears the collapse-repair threshold.
    private val longPressMenuFixture = """
        <div id="row" style="height:64px; background:#ddd">Conversation</div>
        <script>
          var timer = null;
          // WebUI creates the menu element when the timer fires; it is not a pre-existing node.
          function openMenu() {
            var menu = document.createElement('div');
            menu.id = 'menu';
            menu.style.cssText = 'position:fixed; left:8px; top:80px; width:220px; max-height:calc(100vh - 16px);' +
              'overflow:auto; background:#fff';
            // Mirrors .session-action-menu: copy link, duplicate, export, rename, pin, move, archive, delete.
            var items = '';
            for (var i = 0; i < 8; i++) items += '<button style="display:block;width:100%;height:44px">Action ' + i + '</button>';
            menu.innerHTML = items;
            document.body.appendChild(menu);
            window.__menuOpened = true;
          }
          row.addEventListener('touchstart', function() { timer = setTimeout(openMenu, 400); }, { passive: true });
          ['touchend', 'touchcancel', 'touchmove'].forEach(function(type) {
            row.addEventListener(type, function() { clearTimeout(timer); }, { passive: true });
          });
        </script>
    """.trimIndent()

    private fun longPressElement(selector: String, holdMillis: Long) {
        val point = JSONArray(
            evaluate(
                """
                (function() {
                  var rect = document.querySelector(${org.json.JSONObject.quote(selector)}).getBoundingClientRect();
                  return [rect.left + rect.width / 2, rect.top + rect.height / 2, window.innerWidth];
                })()
                """.trimIndent()
            )
        )
        var downTime = 0L
        var x = 0f
        var y = 0f
        composeTestRule.runOnIdle {
            val scale = webView.width / point.getDouble(2).toFloat()
            x = point.getDouble(0).toFloat() * scale
            y = point.getDouble(1).toFloat() * scale
            downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            try { webView.dispatchTouchEvent(down) } finally { down.recycle() }
        }
        Thread.sleep(holdMillis)
        composeTestRule.runOnIdle {
            val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            try { webView.dispatchTouchEvent(up) } finally { up.recycle() }
        }
    }

    @Test
    fun longPressMenu_opensWithRealHeightOnceTheViewportRepairRuns() {
        loadFixture(longPressMenuFixture)
        evaluate(HermesWebUiScripts.viewportFixScript)

        longPressElement("#row", holdMillis = 700)

        assertThat(awaitBoolean("window.__menuOpened === true", expected = true)).isTrue()
        // A collapsed `calc(100vh - 16px)` cap renders the menu invisible (#6); repair must give it room.
        val grew = awaitBoolean("menu.getBoundingClientRect().height >= 100", expected = true)
        assertWithMessage(
            "menu diagnostics: " + evaluate(
                "JSON.stringify({ h: menu.getBoundingClientRect().height, maxH: getComputedStyle(menu).maxHeight, " +
                    "inner: innerHeight, vh: document.documentElement.style.getPropertyValue('--vh'), " +
                    "style: menu.getAttribute('style') })"
            )
        ).that(grew).isTrue()
        assertThat(evaluate("menu.getBoundingClientRect().bottom <= innerHeight")).isEqualTo("true")
    }

    @Test
    fun longPressMenu_collapsesWithoutTheViewportRepair() {
        // Control: proves the fixture really reproduces the Android WebView viewport-unit collapse.
        loadFixture(longPressMenuFixture)

        longPressElement("#row", holdMillis = 700)

        assertThat(awaitBoolean("window.__menuOpened === true", expected = true)).isTrue()
        assertThat(evaluate("menu.getBoundingClientRect().height")).isEqualTo("0")
    }

    @Test
    fun longPressMenu_doesNotOpenOnAShortTap() {
        loadFixture(longPressMenuFixture)
        evaluate(HermesWebUiScripts.viewportFixScript)

        longPressElement("#row", holdMillis = 120)
        Thread.sleep(600)

        assertThat(evaluate("window.__menuOpened === true")).isEqualTo("false")
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
        <script>window.__HERMES_WEBUI_BUNDLE_VERSION__ = 'fixture';</script>
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
        // This fixture also carries no bundle marker, so it covers the legacy .layout/main.main detection.
        loadFixture(
            """<div class="layout"><nav class="rail" style="display:flex;flex-direction:column;width:200px">
                 <button class="rail-btn nav-tab" id="helpBtn" aria-label="Help">Help</button>
               </nav><main class="main"></main></div>""",
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

    // ---- Phase 4: prompt-card reachability (issue #150) ----

    private val promptShiftProp = "--hermes-android-prompt-shift"

    private fun promptSnapshot(): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("webui-snapshot/prompts.html").bufferedReader().use { it.readText() }

    private fun loadPromptFixture(widthDp: Int? = null, heightDp: Int? = null, safeTopPx: Int = 0) {
        hostHeightDp.value = heightDp
        loadFixture(promptSnapshot(), widthDp = widthDp)
        if (safeTopPx > 0) {
            evaluate("document.documentElement.style.setProperty('--app-titlebar-safe-top','${safeTopPx}px'); 0")
        }
    }

    private fun installShim() {
        evaluate(HermesWebUiScripts.viewportFixScript)
        rescan()
    }

    /** Scans are throttled to 100ms and rAF-scheduled, so sleep past both. */
    private fun rescan() {
        Thread.sleep(150)
        evaluate("window.__hermesAndroidApplyViewportFix(); 0")
        Thread.sleep(250)
    }

    /** Mirrors WebUI's _setPromptFlyoutHidden(card,false) + .visible, then waits out the slide-in. */
    private fun showPrompt(id: String) {
        evaluate(
            """
            (function() {
              var c = document.getElementById('$id');
              c.hidden = false; c.setAttribute('aria-hidden', 'false'); c.removeAttribute('inert');
              c.classList.add('visible');
              return 0;
            })()
            """.trimIndent()
        )
        assertThat(
            awaitBoolean(
                """
                (function() {
                  var s = getComputedStyle(document.querySelector('#$id > div'));
                  return s.opacity === '1' && (s.transform === 'none' ||
                    s.transform.indexOf('matrix(1, 0, 0, 1, 0, 0)') === 0);
                })()
                """.trimIndent(),
                expected = true
            )
        ).isTrue()
    }

    private fun addClarifyChoices(total: Int) {
        evaluate(
            """
            (function() {
              var box = document.getElementById('clarifyChoices');
              var tpl = box.querySelector('.clarify-choice:not(.other)');
              var other = box.querySelector('.other');
              for (var i = 3; i <= $total; i++) {
                var c = tpl.cloneNode(true);
                c.setAttribute('data-choice', 'c' + i);
                c.querySelector('.clarify-choice-badge').textContent = String(i);
                c.querySelector('.clarify-choice-text').textContent =
                  'Option ' + i + ' with enough words to wrap on a narrow phone screen';
                box.insertBefore(c, other);
              }
              return box.children.length;
            })()
            """.trimIndent()
        )
    }

    private fun promptReportScript(selector: String): String = """
        (function() {
          var ATTR = 'data-hermes-android-vh-repaired';
          var card = document.querySelector(${org.json.JSONObject.quote(selector)});
          var inner = card.querySelector('.approval-inner, .clarify-inner');
          var tb = document.querySelector('.app-titlebar');
          var wrap = document.querySelector('.composer-wrap');
          var fly = document.querySelector('.composer-flyout');
          var vv = window.visualViewport;
          var vvH = vv && vv.height > 0 ? vv.height
            : Math.max(window.innerHeight, document.documentElement.clientHeight);
          var vvTop = vv ? Math.max(0, vv.offsetTop || 0) : 0;
          var vvBottom = vvTop + vvH;
          var tbBottom = tb.getBoundingClientRect().bottom;
          var promptTop = Math.max(tbBottom, vvTop);
          var cr = card.getBoundingClientRect();
          var shift = parseFloat(card.style.getPropertyValue('$promptShiftProp')) || 0;
          var cap = parseFloat(getComputedStyle(card).maxHeight);
          var innerCap = parseFloat(getComputedStyle(inner).maxHeight);
          var available = Math.min(cr.bottom + shift, vvBottom) - promptTop - 8;
          var ratioCap = Math.round(0.68 * vvH);
          var failures = [];
          function fail(m) { failures.push(m); }
          function desc(e) { return e ? (e.tagName + '#' + e.id + '.' + e.className) : 'null'; }

          if (available < 100) fail('fixture viewport too small to be meaningful: available=' + available);
          if (!(cr.height > 0)) fail('card has no height');
          if (cr.top < promptTop + 8 - 1.5) fail('card top ' + cr.top + ' above promptTop+8 ' + (promptTop + 8));
          if (cr.bottom > vvBottom + 1) fail('card bottom ' + cr.bottom + ' below visual viewport ' + vvBottom);
          if (!(cap > 0) || !(innerCap > 0)) fail('cap not applied: ' + cap + '/' + innerCap);
          if (cap > 420 || cap > ratioCap + 1) fail('cap above 420 / 68% of viewport: ' + cap);
          if (cap > Math.floor(available) + 1) fail('cap ' + cap + ' above available ' + available);
          var ir0 = inner.getBoundingClientRect();
          if (ir0.height < Math.min(inner.scrollHeight + 2, Math.min(cap, innerCap)) - 3) {
            fail('inner clipped to a sliver: ' + ir0.height);
          }
          [wrap, fly].forEach(function(e) {
            var s = getComputedStyle(e);
            if (s.overflowY !== 'visible' || s.overflowX !== 'visible') fail('scroll container on ' + e.className);
            if (e.hasAttribute(ATTR)) fail('generic repair on ' + e.className);
          });
          if (card.hasAttribute(ATTR) || inner.hasAttribute(ATTR)) fail('generic repair on the card');

          // The composer box paints over the bottom of the card (the card's own bottom padding exists
          // for that), so a control is only reachable once it is scrolled clear of the box.
          var box = document.querySelector('.composer-box');
          var boxTop = box ? box.getBoundingClientRect().top : vvBottom;
          var tested = 0;
          var els = card.querySelectorAll('button, input');
          for (var k = 0; k < els.length; k++) {
            var el = els[k];
            if (getComputedStyle(el).display === 'none') continue;
            var ib = inner.getBoundingClientRect();
            var r = el.getBoundingClientRect();
            var visBottom = Math.min(ib.bottom, boxTop, vvBottom);
            if (r.bottom > visBottom - 1) inner.scrollTop += r.bottom - (visBottom - 1);
            else if (r.top < ib.top) inner.scrollTop -= ib.top - r.top;
            ib = inner.getBoundingClientRect();
            r = el.getBoundingClientRect();
            visBottom = Math.min(ib.bottom, boxTop, vvBottom);
            var label = (el.id || el.getAttribute('data-choice') || el.className);
            if (!(r.width > 0 && r.height > 0)) { fail(label + ' has no size'); continue; }
            tested++;
            var ins = Math.min(6, r.width / 4, r.height / 4);
            var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
            var pts = [[cx, cy], [r.left + ins, r.top + ins], [r.right - ins, r.top + ins],
                       [r.left + ins, r.bottom - ins], [r.right - ins, r.bottom - ins]];
            for (var p = 0; p < pts.length; p++) {
              var x = pts[p][0], y = pts[p][1];
              if (x < ib.left || x > ib.right || y < ib.top || y > visBottom) {
                fail(label + ' point ' + p + ' outside the unobscured part of the card'); continue;
              }
              if (y < tbBottom || y > vvBottom) { fail(label + ' point ' + p + ' behind titlebar/IME'); continue; }
              var hit = document.elementFromPoint(x, y);
              if (!hit || !(hit === el || el.contains(hit))) fail(label + ' point ' + p + ' hit ' + desc(hit));
            }
          }
          return {
            failures: failures, tested: tested,
            m: { cap: cap, innerCap: innerCap, shift: shift, available: available, ratioCap: ratioCap,
                 top: cr.top, bottom: cr.bottom, promptTop: promptTop, vvBottom: vvBottom,
                 innerScrolls: inner.scrollHeight > inner.clientHeight + 1 &&
                   getComputedStyle(inner).overflowY === 'auto' }
          };
        })()
    """.trimIndent()

    private fun promptReport(selector: String) = org.json.JSONObject(evaluate(promptReportScript(selector)))

    private fun assertPromptReachable(selector: String, minTested: Int): org.json.JSONObject {
        val report = promptReport(selector)
        assertWithMessage(report.toString(2)).that(report.getJSONArray("failures").length()).isEqualTo(0)
        assertWithMessage(report.toString(2)).that(report.getInt("tested")).isAtLeast(minTested)
        return report
    }

    private fun awaitPromptReachable(selector: String) {
        assertWithMessage(promptReport(selector).toString(2)).that(
            awaitBoolean("(${promptReportScript(selector)}).failures.length === 0", expected = true)
        ).isTrue()
    }

    private fun assertStable(selector: String, first: org.json.JSONObject) {
        rescan()
        val second = promptReport(selector).getJSONObject("m")
        val a = first.getJSONObject("m")
        assertWithMessage("oscillation: $a vs $second").that(
            Math.abs(a.getDouble("cap") - second.getDouble("cap")) <= 1 &&
                Math.abs(a.getDouble("shift") - second.getDouble("shift")) <= 1
        ).isTrue()
    }

    @Test
    fun promptCards_approvalCardButtonsAreHitTestable() {
        loadPromptFixture()
        installShim()
        showPrompt("approvalCard")
        rescan()
        val sel = ".approval-card.visible"
        val report = assertPromptReachable(sel, minTested = 7) // 2 header buttons + 5 actions
        assertStable(sel, report)
    }

    @Test
    fun promptCards_clarifyChoicesAndOtherAreReachableByInnerScroll() {
        loadPromptFixture()
        addClarifyChoices(total = 14)
        installShim()
        showPrompt("clarifyCard")
        rescan()
        val sel = ".clarify-card.visible"
        val report = assertPromptReachable(sel, minTested = 14 + 4) // choices+Other, collapse, input, send
        assertThat(report.getJSONObject("m").getBoolean("innerScrolls")).isTrue()
        assertStable(sel, report)
    }

    @Test
    fun promptCards_shortViewportWithLargeTopInsetKeepsEightPxGapBelowTitlebar() {
        // 80px safe-top makes the titlebar term, not 68%, the binding cap constraint.
        loadPromptFixture(widthDp = 360, heightDp = 300, safeTopPx = 80)
        addClarifyChoices(total = 8)
        installShim()
        showPrompt("clarifyCard")
        rescan()
        val sel = ".clarify-card.visible"
        val report = assertPromptReachable(sel, minTested = 8)
        val m = report.getJSONObject("m")
        assertWithMessage(m.toString()).that(m.getDouble("available")).isLessThan(m.getDouble("ratioCap"))
        // Tall content fills the space exactly: top sits about 8px under the titlebar, not flush and not far below.
        assertWithMessage(m.toString()).that(m.getDouble("top")).isAtMost(m.getDouble("promptTop") + 8 + 2)
        assertStable(sel, report)
    }

    @Test
    fun promptCards_realHostResizeLikeImeStillKeepsCardReachable() {
        loadPromptFixture(widthDp = 360, heightDp = 640)
        addClarifyChoices(total = 6)
        installShim()
        showPrompt("clarifyCard")
        rescan()
        val sel = ".clarify-card.visible"
        assertPromptReachable(sel, minTested = 6)
        composeTestRule.runOnIdle { hostHeightDp.value = 330 }
        composeTestRule.waitForIdle()
        assertThat(awaitBoolean("window.innerHeight < 400", expected = true)).isTrue()
        awaitPromptReachable(sel) // no manual rescan: exercises the resize listener wiring
        assertPromptReachable(sel, minTested = 6)
    }

    @Test
    fun promptCards_shrunkenAndScrolledVisualViewportShiftsCardAboveImeGeometryOnly() {
        loadPromptFixture(widthDp = 360, heightDp = 640)
        addClarifyChoices(total = 6)
        // Stub BEFORE installing the shim so its resize listener binds to the stub.
        evaluate(
            """
            (function() {
              var t = new EventTarget();
              t.height = 330; t.width = window.innerWidth; t.offsetTop = 0; t.offsetLeft = 0; t.scale = 1;
              Object.defineProperty(window, 'visualViewport', { configurable: true, get: function() { return t; } });
              window.__fakeVv = t;
              return 0;
            })()
            """.trimIndent()
        )
        assertThat(evaluate("window.visualViewport.height")).isEqualTo("330")
        installShim()
        showPrompt("clarifyCard")
        rescan()
        val sel = ".clarify-card.visible"
        val first = assertPromptReachable(sel, minTested = 6)
        assertThat(first.getJSONObject("m").getDouble("shift")).isGreaterThan(0.0)
        assertStable(sel, first)
        // A visual viewport that only pans (offsetTop changes, no resize) fires a scroll event; the shim
        // must re-fit the card from that alone, with no manual rescan (#164).
        evaluate("window.__fakeVv.offsetTop = 120; window.__fakeVv.dispatchEvent(new Event('scroll')); 0")
        awaitPromptReachable(sel)
        val moved = assertPromptReachable(sel, minTested = 6)
        assertThat(moved.getJSONObject("m").getDouble("promptTop")).isAtLeast(120.0)
        // The visual viewport can shrink on its own (an overlay keyboard fires no window resize), so the
        // shim's visualViewport resize listener must re-fit the card without any manual rescan.
        evaluate("window.__fakeVv.offsetTop = 0; window.__fakeVv.height = 280; window.__fakeVv.dispatchEvent(new Event('resize')); 0")
        awaitPromptReachable(sel)
    }

    @Test
    fun promptCards_composerWrapScrollContainerCannotClipFloatingCard_issue80() {
        loadPromptFixture()
        evaluate(
            // Tall composer content inside a 24px wrap: enough overflow that the generic collapse repair
            // would pick the wrap (as it did in #80) if the shim stopped excluding it.
            "var w = document.querySelector('.composer-wrap'); w.style.height = '24px'; w.style.overflowY = 'auto'; " +
                "document.getElementById('msg').style.height = '120px'; 0"
        )
        assertThat(evaluate("getComputedStyle(document.querySelector('.composer-wrap')).overflowY"))
            .isEqualTo("\"auto\"")
        showPrompt("approvalCard")
        // Control: without the shim the card is clipped, so the test is sensitive.
        assertWithMessage("control must fail before the shim runs").that(
            promptReport(".approval-card.visible").getJSONArray("failures").length()
        ).isGreaterThan(0)
        installShim()
        assertPromptReachable(".approval-card.visible", minTested = 7)
        assertThat(evaluate("document.querySelector('.composer-wrap').style.height")).isEqualTo("\"24px\"")
        assertThat(evaluate("document.querySelector('.composer-wrap').style.overflowY")).isEqualTo("\"auto\"")
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
            WebViewHost(widthDp, hostHeightDp.value) { view ->
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
private fun WebViewHost(widthDp: Int? = null, heightDp: Int? = null, onCreated: (WebView) -> Unit) {
    val sized = if (widthDp == null && heightDp == null) {
        Modifier.fillMaxSize()
    } else {
        (if (widthDp != null) Modifier.width(widthDp.dp) else Modifier.fillMaxWidth())
            .let { if (heightDp != null) it.height(heightDp.dp) else it.fillMaxHeight() }
    }
    AndroidView(modifier = sized, factory = { context -> WebView(context).also(onCreated) })
}
