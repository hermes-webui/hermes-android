package com.hermeswebui.android.webview

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.widget.Toast
import androidx.core.net.toUri
import androidx.webkit.URLUtilCompat

class HermesWebViewDownloadListener(
    private val context: Context,
    private val isAllowed: (String) -> Boolean
) : DownloadListener {
    override fun onDownloadStart(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        if (!isAllowed(url)) {
            Toast.makeText(context, "Blocked download from non-allowlisted domain", Toast.LENGTH_LONG).show()
            return
        }
        val fileName = fileNameFor(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(url.toUri()).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setTitle(fileName)
            setDescription("Downloading from Hermes")
            setAllowedOverMetered(true)
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                addRequestHeader("Cookie", it)
            }
            userAgent?.takeIf { it.isNotBlank() }?.let {
                addRequestHeader("User-Agent", it)
            }
            setHermesDestination(context, fileName)
        }
        context.getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
    }

    internal companion object {
        /**
         * Android 10+ (API 29) lets DownloadManager write to the shared Download folder without a
         * storage permission, so files show up in Files > Downloads and survive app data clears.
         * Android 8 and 9 would need WRITE_EXTERNAL_STORAGE for that, so they keep the
         * app-specific folder. If shared storage is unavailable, fall back to the same folder.
         */
        fun DownloadManager.Request.setHermesDestination(context: Context, fileName: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    return
                } catch (_: IllegalStateException) {
                    // Shared storage not mounted or Download folder unusable.
                }
            }
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        fun fileNameFor(url: String, contentDisposition: String?, mimeType: String?): String {
            val dispositionParts = contentDisposition?.split(';', limit = 2)
            // An explicit image download can carry the server's inline preview header.
            val downloadDisposition = if (dispositionParts?.size == 2 &&
                dispositionParts[0].trim().equals("inline", ignoreCase = true)
            ) {
                "attachment;${dispositionParts[1]}"
            } else {
                contentDisposition
            }
            return URLUtilCompat.guessFileName(url, downloadDisposition, mimeType)
        }
    }
}
