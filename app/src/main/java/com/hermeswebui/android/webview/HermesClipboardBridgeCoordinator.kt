package com.hermeswebui.android.webview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backs the document-start `navigator.clipboard.read()` fallback (Issue #180). Android WebView
 * never grants the web `clipboard-read` permission, so the page's own call rejects with
 * `NotAllowedError`. For the configured Hermes route only, the page may ask the host to read the
 * current clipboard; the host answers with bounded raster images and plain text.
 *
 * This is a request/reply `WebMessageListener` (no `addJavascriptInterface`), main frame only, and
 * the page shim additionally requires transient user activation before it asks.
 */
class HermesClipboardBridgeCoordinator(
    private val context: Context,
    private val bridgeName: String,
    private val isTrustedSource: (Uri, Boolean) -> Boolean,
    private val runOnUiThread: ((() -> Unit) -> Unit)
) {
    fun installBridge(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            WebViewCompat.addWebMessageListener(
                view,
                bridgeName,
                setOf("*")
            ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
                runOnUiThread { handleMessage(message, sourceOrigin, isMainFrame, replyProxy) }
            }
        }
    }

    private fun handleMessage(
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        val parsed = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull()
        val id = parsed?.optString("id")?.takeIf { it.isNotBlank() }.orEmpty()
        if (parsed == null || parsed.optString("type") != "readClipboard") {
            post(replyProxy, failure(id, "unsupported"))
            return
        }
        if (!isTrustedSource(sourceOrigin, isMainFrame)) {
            post(replyProxy, failure(id, "denied"))
            return
        }
        val clip = runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        }.getOrNull()
        post(replyProxy, success(id, clip))
    }

    private fun failure(id: String, error: String): JSONObject {
        return JSONObject().put("id", id).put("ok", false).put("error", error)
    }

    private fun success(id: String, clip: ClipData?): JSONObject {
        val images = JSONArray()
        var text: String? = null
        var oversized = false
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                val uri = item.uri
                if (uri != null && images.length() < ClipboardImagePolicy.MAX_IMAGES_PER_READ) {
                    val declared = listOf(
                        runCatching { context.contentResolver.getType(uri) }.getOrNull(),
                        clip.description.takeIf { index < it.mimeTypeCount }?.getMimeType(index)
                    )
                    val mime = ClipboardImagePolicy.firstAllowedMimeType(declared)
                    if (mime != null) {
                        val bytes = readImage(uri)
                        if (bytes == null) {
                            oversized = true
                        } else {
                            images.put(
                                JSONObject()
                                    .put("type", mime)
                                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            )
                        }
                    }
                }
                if (text == null) {
                    text = runCatching { item.text?.toString() }.getOrNull()?.takeIf { it.isNotEmpty() }
                }
            }
        }
        val payload = JSONObject().put("id", id).put("ok", true).put("images", images)
        if (text != null) payload.put("text", text)
        if (oversized && images.length() == 0) payload.put("error", "too_large")
        return payload
    }

    private fun readImage(uri: Uri): ByteArray? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { ClipboardImagePolicy.readBounded(it) }
        }.getOrNull()
    }

    private fun post(proxy: JavaScriptReplyProxy, payload: JSONObject) {
        runCatching { proxy.postMessage(payload.toString()) }
    }
}
