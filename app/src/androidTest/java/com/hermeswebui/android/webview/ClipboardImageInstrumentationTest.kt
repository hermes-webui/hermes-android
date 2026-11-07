package com.hermeswebui.android.webview

import android.content.ClipDescription
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.webkit.WebViewCompat
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.webui.HermesWebUiScripts
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/**
 * Issue #180: image paste for the Hermes Agent Webapp and WebUI. Runs the real document-start shim in
 * a real [HermesWebView]; the native clipboard reader is replaced by a bridge stub that answers like
 * [HermesClipboardBridgeCoordinator] would.
 */
class ClipboardImageInstrumentationTest {
    private companion object {
        const val LOAD_TIMEOUT_SECONDS = 60L
        const val JS_TIMEOUT_SECONDS = 30L
        const val BRIDGE = "TestClipboardBridge"

        // 1x1 transparent PNG.
        const val PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var webView: HermesWebView

    private val rejectNativeRead = """
        (function() {
          var proto = Object.getPrototypeOf(navigator.clipboard);
          Object.defineProperty(proto, 'read', { configurable: true, writable: true, value: function() {
            return Promise.reject(new DOMException('Read permission denied.', 'NotAllowedError'));
          } });
        })();
    """.trimIndent()

    private fun bridgeStub(reply: String) = """
        (function() {
          window.__bridgeLog = [];
          var bridge = { postMessage: function(raw) {
            var message = JSON.parse(raw);
            window.__bridgeLog.push(message);
            var response = $reply;
            response.id = message.id;
            setTimeout(function() { bridge.onmessage({ data: JSON.stringify(response) }); }, 0);
          } };
          window.$BRIDGE = bridge;
        })();
    """.trimIndent()

    private val imageReply = """{ ok: true, images: [{ type: 'image/png', data: '$PNG_BASE64' }] }"""

    private val readButton = """
        <button id="paste" style="width:200px;height:80px"
          onclick="window.__result = undefined;
                   navigator.clipboard.read().then(async function(items) {
                     var out = [];
                     for (var i = 0; i < items.length; i++) {
                       var type = items[i].types[0];
                       var blob = await items[i].getType(type);
                       out.push([type, blob.size]);
                     }
                     window.__result = JSON.stringify(out);
                   }, function(e) { window.__result = 'err:' + e.name; });">Paste image</button>
    """.trimIndent()

    private fun shimFixture(reply: String) = listOf(
        rejectNativeRead,
        bridgeStub(reply),
        HermesWebUiScripts.buildClipboardImageScript(BRIDGE)
    )

    @Test
    fun clipboardRead_afterNativeRejection_returnsTheHostImageOnUserTap() {
        loadFixture(readButton, shimFixture(imageReply))

        tap("#paste")

        assertThat(awaitTrue("window.__result !== undefined")).isTrue()
        val decodedSize = android.util.Base64.decode(PNG_BASE64, android.util.Base64.DEFAULT).size
        assertThat(evaluate("window.__result")).isEqualTo("\"[[\\\"image/png\\\",$decodedSize]]\"")
        assertThat(evaluate("window.__bridgeLog.length")).isEqualTo("1")
        assertThat(evaluate("window.__bridgeLog[0].type")).isEqualTo("\"readClipboard\"")
    }

    @Test
    fun clipboardRead_withoutUserActivation_isRejectedAndNeverAsksTheHost() {
        loadFixture("<p>no tap</p>", shimFixture(imageReply))

        evaluate(
            "window.__result = undefined;" +
                "navigator.clipboard.read().then(function() { window.__result = 'ok'; }," +
                " function(e) { window.__result = 'err:' + e.name; });"
        )

        assertThat(awaitTrue("window.__result !== undefined")).isTrue()
        assertThat(evaluate("window.__result")).isEqualTo("\"err:NotAllowedError\"")
        assertThat(evaluate("window.__bridgeLog.length")).isEqualTo("0")
    }

    @Test
    fun clipboardRead_whenTheHostDenies_staysNotAllowed() {
        loadFixture(readButton, shimFixture("{ ok: false, error: 'denied' }"))

        tap("#paste")

        assertThat(awaitTrue("window.__result !== undefined")).isTrue()
        assertThat(evaluate("window.__result")).isEqualTo("\"err:NotAllowedError\"")
    }

    @Test
    fun clipboardRead_whenTheClipboardHasNoImage_resolvesEmptyLikeABrowser() {
        loadFixture(readButton, shimFixture("{ ok: true, images: [] }"))

        tap("#paste")

        assertThat(awaitTrue("window.__result !== undefined")).isTrue()
        assertThat(evaluate("window.__result")).isEqualTo("\"[]\"")
    }

    @Test
    fun clipboardRead_whenNativeReadWorks_isNotInterceptedForOtherErrors() {
        loadFixture(
            readButton,
            listOf(
                """
                Object.defineProperty(Object.getPrototypeOf(navigator.clipboard), 'read', {
                  configurable: true, writable: true,
                  value: function() { return Promise.reject(new DOMException('x', 'DataError')); }
                });
                """.trimIndent(),
                bridgeStub(imageReply),
                HermesWebUiScripts.buildClipboardImageScript(BRIDGE)
            )
        )

        tap("#paste")

        assertThat(awaitTrue("window.__result !== undefined")).isTrue()
        assertThat(evaluate("window.__result")).isEqualTo("\"err:DataError\"")
        assertThat(evaluate("window.__bridgeLog.length")).isEqualTo("0")
    }

    @Test
    fun committedKeyboardImage_reachesTheFocusedEditorAsAPasteFile() {
        loadFixture(
            """
            <textarea id="composer"></textarea>
            <script>
              window.__pasted = [];
              composer.addEventListener('paste', function(e) {
                var f = e.clipboardData.files[0];
                window.__pasted.push([f.type, f.name, f.size]);
                e.preventDefault();
              });
            </script>
            """.trimIndent(),
            shimFixture(imageReply)
        )
        evaluate("composer.focus()")

        evaluate(HermesWebUiScripts.buildDeliverImageScript("image/png", PNG_BASE64))

        val decodedSize = android.util.Base64.decode(PNG_BASE64, android.util.Base64.DEFAULT).size
        assertThat(awaitTrue("window.__pasted.length === 1")).isTrue()
        assertThat(evaluate("JSON.stringify(window.__pasted[0])"))
            .isEqualTo("\"[\\\"image/png\\\",\\\"pasted-image.png\\\",$decodedSize]\"")
    }

    @Test
    fun webViewAdvertisesImageTypesOnlyWhenAllowedAndForwardsCommittedImages() {
        loadFixture("""<textarea id="composer"></textarea>""", emptyList())
        evaluate("composer.focus()")
        assertThat(awaitTrue("document.activeElement === composer")).isTrue()

        var committed: Pair<Uri, String>? = null
        composeTestRule.runOnIdle {
            webView.onImageCommitted = { uri, mime, release ->
                committed = uri to mime
                release()
                true
            }
        }

        // Untrusted page: nothing is advertised, so Gboard keeps refusing.
        composeTestRule.runOnIdle { webView.acceptsImageInput = { false } }
        val untrusted = EditorInfo()
        composeTestRule.runOnIdle { webView.onCreateInputConnection(untrusted) }
        assertThat(EditorInfoCompat.getContentMimeTypes(untrusted)).isEmpty()

        // Configured Hermes route: image types are advertised and a commit reaches the handler.
        composeTestRule.runOnIdle { webView.acceptsImageInput = { true } }
        val trusted = EditorInfo()
        composeTestRule.runOnIdle {
            val connection = checkNotNull(webView.onCreateInputConnection(trusted))
            assertThat(EditorInfoCompat.getContentMimeTypes(trusted).toList())
                .containsExactlyElementsIn(ClipboardImagePolicy.imeMimeTypes().toList())
            val info = InputContentInfoCompat(
                Uri.parse("content://com.example.keyboard/image/1"),
                ClipDescription("image", arrayOf("image/png")),
                null
            )
            assertThat(InputConnectionCompat.commitContent(connection, trusted, info, 0, null)).isTrue()
        }
        assertThat(committed?.second).isEqualTo("image/png")
        assertThat(committed?.first.toString()).isEqualTo("content://com.example.keyboard/image/1")

        // SVG is never accepted from a keyboard.
        committed = null
        composeTestRule.runOnIdle {
            val connection = checkNotNull(webView.onCreateInputConnection(EditorInfo()))
            val info = InputContentInfoCompat(
                Uri.parse("content://com.example.keyboard/image/2"),
                ClipDescription("image", arrayOf("image/svg+xml")),
                null
            )
            assertThat(InputConnectionCompat.commitContent(connection, trusted, info, 0, null)).isFalse()
        }
        assertThat(committed).isNull()
    }

    private fun loadFixture(body: String, documentStartScripts: List<String>) {
        val loaded = CountDownLatch(1)
        composeTestRule.setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    HermesWebView(context).also { view ->
                        webView = view
                        view.settings.javaScriptEnabled = true
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
                            "https://hermes.test/",
                            "<!doctype html><html><head><meta name=\"viewport\" " +
                                "content=\"width=device-width,initial-scale=1\"></head><body>$body</body></html>",
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                }
            )
        }
        assertThat(loaded.await(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
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
        assertThat(completed.await(JS_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
        return checkNotNull(result)
    }

    private fun awaitTrue(script: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (evaluate(script) == "true") return true
            Thread.sleep(25)
        }
        return false
    }

    /** A real touch tap, so the page has transient user activation like a user pressing Paste image. */
    private fun tap(selector: String) {
        val coordinates = org.json.JSONArray(
            evaluate(
                """
                (function() {
                  var rect = document.querySelector(${JSONObject.quote(selector)}).getBoundingClientRect();
                  return [rect.left + rect.width / 2, rect.top + rect.height / 2, window.innerWidth];
                })()
                """.trimIndent()
            )
        )
        composeTestRule.runOnIdle {
            val scale = webView.width / coordinates.getDouble(2).toFloat()
            val x = coordinates.getDouble(0).toFloat() * scale
            val y = coordinates.getDouble(1).toFloat() * scale
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
}
