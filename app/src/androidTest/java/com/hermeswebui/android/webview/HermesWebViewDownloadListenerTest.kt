package com.hermeswebui.android.webview

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.core.security.UrlPolicy
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HermesWebViewDownloadListenerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var manager: DownloadManager
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var baseUrl: String
    private val requestHeaders = ConcurrentLinkedQueue<Map<String, String>>()
    private val payload = "Hermes download fixture\n".toByteArray()

    @Volatile
    private var responseDisposition = ""

    private companion object {
        const val SETTLE_TIMEOUT_MS = 10_000L
        const val SETTLE_STABLE_MS = 500L
        const val SETTLE_POLL_MS = 50L
    }

    @Before
    fun startServer() {
        context = instrumentation.targetContext
        manager = context.getSystemService(DownloadManager::class.java)
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${server.localPort}"
        serverThread = thread(name = "hermes-download-fixture", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (error: SocketException) {
                    if (server.isClosed) break
                    throw error
                }
                socket.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader()
                    check(reader.readLine().startsWith("GET "))
                    val headers = mutableMapOf<String, String>()
                    var line = reader.readLine()
                    while (!line.isNullOrEmpty()) {
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        line = reader.readLine()
                    }
                    requestHeaders.add(headers)
                    val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Disposition: $responseDisposition\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    it.getOutputStream().apply {
                        write(response.toByteArray())
                        write(payload)
                        flush()
                    }
                }
            }
        }
    }

    @After
    fun cleanUp() {
        if (::manager.isInitialized && ::baseUrl.isInitialized) {
            ownDownloads().forEach { download ->
                try {
                    // Never remove while DownloadProvider may still be writing (#159).
                    awaitDownloadSettled(download.title)
                } finally {
                    manager.remove(download.id)
                }
            }
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(baseUrl, "issue118_fixture=; Max-Age=0")
            }
        }
        if (::server.isInitialized) server.close()
        if (::serverThread.isInitialized) serverThread.join(5_000)
    }

    @Test
    fun chatDownload_preservesHeaderFilenameAndAuthenticatedBytes() {
        assertDownload("/api/media")
    }

    @Test
    fun filesDownload_preservesHeaderFilenameAndAuthenticatedBytes() {
        assertDownload("/api/file/raw")
    }

    @Test
    fun extendedFilename_takesPrecedenceAndPreservesUtf8Name() {
        assertThat(
            HermesWebViewDownloadListener.fileNameFor(
                "$baseUrl/api/media",
                "attachment; filename=\"fallback.csv\"; filename*=UTF-8''r%C3%A9sum%C3%A9%2B2026.csv",
                "application/octet-stream"
            )
        ).isEqualTo("r\u00e9sum\u00e9+2026.csv")
    }

    @Test
    fun inlineImageDownload_preservesFilenameWhenWebViewRequestsDownload() {
        assertThat(
            HermesWebViewDownloadListener.fileNameFor(
                "$baseUrl/api/media",
                "inline; filename=\"diagram.png\"; filename*=UTF-8''diagram.png",
                "image/png"
            )
        ).isEqualTo("diagram.png")
    }

    @Test
    fun simpleAttachment_preservesExtensionWithGenericMimeType() {
        assertThat(
            HermesWebViewDownloadListener.fileNameFor(
                "$baseUrl/api/media",
                "attachment; filename=\"notes.md\"",
                "application/octet-stream"
            )
        ).isEqualTo("notes.md")
    }

    @Test
    fun extendedFilenameOnly_preservesEncodedFilename() {
        assertThat(
            HermesWebViewDownloadListener.fileNameFor(
                "$baseUrl/api/file/raw",
                "attachment; filename*=UTF-8''quarterly%20report.xlsx",
                "application/octet-stream"
            )
        ).isEqualTo("quarterly report.xlsx")
    }

    @Test
    fun invalidExtendedFilename_usesAsciiFallback() {
        assertThat(
            HermesWebViewDownloadListener.fileNameFor(
                "$baseUrl/api/media",
                "attachment; filename=\"report.csv\"; filename*=UTF-8''bad%ZZ.csv",
                "text/csv"
            )
        ).isEqualTo("report.csv")
    }

    @Test
    fun headerFilename_cannotCreateNestedDownloadPath() {
        assertThat(
            HermesWebViewDownloadListener.fileNameFor(
                "$baseUrl/api/media",
                "attachment; filename=\"../report.csv\"",
                "text/csv"
            )
        ).isEqualTo(".._report.csv")
    }

    @Test
    fun missingHeader_usesOrdinaryUrlFilename() {
        assertThat(
            HermesWebViewDownloadListener.fileNameFor(
                "$baseUrl/report.pdf",
                null,
                "application/pdf"
            )
        ).isEqualTo("report.pdf")
    }

    @Test
    fun listener_checksCurrentAllowlistBeforeEnqueueing() {
        var allowed = true
        val listener = HermesWebViewDownloadListener(context) { allowed }
        allowed = false
        instrumentation.runOnMainSync {
            listener.onDownloadStart(
                "$baseUrl/api/media",
                null,
                "attachment; filename=\"blocked.csv\"",
                "text/csv",
                payload.size.toLong()
            )
        }
        assertThat(ownDownloads()).isEmpty()
        assertThat(requestHeaders).isEmpty()
    }

    private fun assertDownload(path: String) {
        val filename = "issue118-${UUID.randomUUID()}.md"
        responseDisposition = "attachment; filename=\"$filename\"; filename*=UTF-8''$filename"
        val url = "$baseUrl$path?path=$filename&download=1"
        val policy = UrlPolicy(setOf("127.0.0.1"))
        val listener = HermesWebViewDownloadListener(context, policy::isAllowed)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setCookie(baseUrl, "issue118_fixture=authenticated")
            listener.onDownloadStart(
                url,
                "Hermes-Issue118-Fixture",
                responseDisposition,
                "application/octet-stream",
                payload.size.toLong()
            )
        }
        val deadline = SystemClock.uptimeMillis() + 30_000
        var download = ownDownloads().single()
        assertThat(download.title).isEqualTo(filename)
        while (download.status != DownloadManager.STATUS_SUCCESSFUL &&
            download.status != DownloadManager.STATUS_FAILED &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(100)
            download = ownDownloads().single()
        }
        assertThat(download.status).isEqualTo(DownloadManager.STATUS_SUCCESSFUL)
        val file = File(URI(requireNotNull(download.localUri)))
        assertThat(file.name).isEqualTo(filename)
        assertThat(file.parentFile?.canonicalPath).isEqualTo(expectedDownloadDir().canonicalPath)
        val downloadedBytes = ParcelFileDescriptor.AutoCloseInputStream(
            manager.openDownloadedFile(download.id)
        ).use { it.readBytes() }
        assertThat(downloadedBytes).isEqualTo(payload)
        assertThat(requestHeaders.single()["cookie"]).contains("issue118_fixture=authenticated")
        assertThat(requestHeaders.single()["user-agent"]).isEqualTo("Hermes-Issue118-Fixture")
    }

    /**
     * DownloadManager publishes STATUS_SUCCESSFUL before DownloadThread's last
     * DownloadProvider.update -> MediaProvider sync. Removing the row inside that window makes
     * android.process.media throw FileNotFoundException on a background thread, which kills the
     * process and aborts the whole instrumentation run (Issue #159). Cleanup therefore waits until
     * the file is registered in MediaStore and the DownloadManager row has stopped changing.
     *
     * Both signals are heuristics (the platform offers no "fully done" callback), so this is a
     * bounded quiescence check. On timeout it fails with the last observed state instead of
     * removing early and risking the crash.
     */
    private fun awaitDownloadSettled(filename: String) {
        val deadline = SystemClock.uptimeMillis() + SETTLE_TIMEOUT_MS
        var last: List<Any?>? = null
        var stableSince = SystemClock.uptimeMillis()
        var registered = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        while (SystemClock.uptimeMillis() < deadline) {
            if (!registered) registered = isInMediaStoreDownloads(filename)
            val snapshot = downloadSnapshot()
            if (snapshot != last) {
                last = snapshot
                stableSince = SystemClock.uptimeMillis()
            }
            if (registered && snapshot != null && SystemClock.uptimeMillis() - stableSince >= SETTLE_STABLE_MS) {
                return
            }
            SystemClock.sleep(SETTLE_POLL_MS)
        }
        throw AssertionError(
            "Download $filename did not settle within ${SETTLE_TIMEOUT_MS}ms " +
                "(mediaStoreRegistered=$registered, lastDownloadState=$last); " +
                "refusing to remove it while DownloadProvider may still be writing (#159)"
        )
    }

    private fun isInMediaStoreDownloads(filename: String): Boolean =
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(filename),
            null
        )?.use { it.count > 0 } ?: false

    /** Everything DownloadProvider rewrites while it finishes a download; stable means it is done. */
    private fun downloadSnapshot(): List<Any?>? {
        manager.query(DownloadManager.Query()).use { cursor ->
            while (cursor.moveToNext()) {
                val url = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                if (!url.startsWith("$baseUrl/")) continue
                return listOf(
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                )
            }
        }
        return null
    }

    // Issue #130: Android 10+ saves to the shared Download folder listed in Files > Downloads.
    private fun expectedDownloadDir(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } else {
            requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
        }

    private data class Download(
        val id: Long,
        val title: String,
        val status: Int,
        val localUri: String?
    )

    private fun ownDownloads(): List<Download> {
        val downloads = mutableListOf<Download>()
        manager.query(DownloadManager.Query()).use { cursor ->
            while (cursor.moveToNext()) {
                val url = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                if (!url.startsWith("$baseUrl/")) continue
                downloads.add(
                    Download(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                        title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)),
                        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    )
                )
            }
        }
        return downloads
    }
}
