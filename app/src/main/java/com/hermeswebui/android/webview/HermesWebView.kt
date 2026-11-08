package com.hermeswebui.android.webview

import android.content.Context
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat

/**
 * Main WebView that lets keyboards such as Gboard insert images into the focused editor
 * (Issue #180). Stock WebView advertises no content MIME types, so the IME refuses with
 * "does not support image pasting here". Advertising is gated per call by [acceptsImageInput]
 * (configured Hermes route only); committed images go to [onImageCommitted], which must itself
 * re-check trust before forwarding to the page and must call the supplied release callback.
 */
class HermesWebView(context: Context) : WebView(context) {
    var acceptsImageInput: () -> Boolean = { false }
    var onImageCommitted: (Uri, String, () -> Unit) -> Boolean = { _, _, release ->
        release()
        false
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        if (!acceptsImageInput()) return base
        EditorInfoCompat.setContentMimeTypes(outAttrs, ClipboardImagePolicy.imeMimeTypes())
        val listener = InputConnectionCompat.OnCommitContentListener { info, flags, _ ->
            val description = info.description
            val mime = ClipboardImagePolicy.firstAllowedMimeType(
                (0 until description.mimeTypeCount).map { description.getMimeType(it) }
            ) ?: return@OnCommitContentListener false
            val needsPermission =
                flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0
            if (needsPermission && runCatching { info.requestPermission() }.isFailure) {
                return@OnCommitContentListener false
            }
            onImageCommitted(info.contentUri, mime) {
                if (needsPermission) runCatching { info.releasePermission() }
            }
        }
        return InputConnectionCompat.createWrapper(base, outAttrs, listener)
    }
}
