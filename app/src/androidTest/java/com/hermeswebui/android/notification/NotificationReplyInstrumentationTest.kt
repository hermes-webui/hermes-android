package com.hermeswebui.android.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.webkit.CookieManager
import androidx.core.app.RemoteInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.SettingsRepository
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationReplyInstrumentationTest {
    @get:Rule
    val permission = GrantPermissionRule.grant("android.permission.POST_NOTIFICATIONS")
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val preferences = context.getSharedPreferences("hermes_secure_prefs", Context.MODE_PRIVATE)
    private var original: Map<String, *> = emptyMap<String, Any>()
    private lateinit var server: ServerSocket
    private lateinit var serverUrl: String
    private lateinit var targetUrl: String
    private lateinit var presenter: HermesNotificationPresenter
    private val notificationTags = mutableSetOf<String>()
    private lateinit var currentTag: String
    private var receivedPath: String? = null
    private var receivedCookie: String? = null
    private var receivedBody: String? = null

    @Before
    fun setUp() {
        original = preferences.all.toMap()
        check(preferences.edit().clear().commit())
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10_000
        serverUrl = "http://127.0.0.1:${server.localPort}/mount%20name/"
        targetUrl = "${serverUrl}session/team%2Fnotes"
        SettingsRepository(context).saveAppUrls(serverUrl, "")
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Reply test", NotificationManager.IMPORTANCE_LOW))
        NotificationReplyActions.clear()
        presenter = HermesNotificationPresenter(context, CHANNEL, 90_000) { it == targetUrl }
        val cookieSet = CountDownLatch(1)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setCookie(serverUrl, "reply_fixture=synthetic; Path=/mount%20name/") {
                cookieSet.countDown()
            }
        }
        check(cookieSet.await(5, TimeUnit.SECONDS))
        // Keep separate fixtures out of Android's per-package notification update rate window.
        SystemClock.sleep(1_100)
    }

    @After
    fun tearDown() {
        awaitBroadcastIdle()
        NotificationReplyActions.clear()
        server.close()
        manager.activeNotifications.filter { it.tag in notificationTags || it.id == 20_004 }
            .forEach { manager.cancel(it.tag, it.id) }
        instrumentation.runOnMainSync {
            CookieManager.getInstance().setCookie(serverUrl, "reply_fixture=; Max-Age=0; Path=/mount%20name/")
        }
        val editor = preferences.edit().clear()
        original.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unexpected preference type")
            }
        }
        check(editor.commit())
    }

    @Test
    fun remoteInputSendsOneAuthenticatedReplyToTheMountedSessionWithoutOpeningTheApp() {
        val requests = serve("""{"stream_id":"stream-1","session_id":"team/notes"}""")
        val action = replyAction()
        assertThat(action.remoteInputs.map { it.resultKey }).containsExactly(NotificationReplyActions.INPUT_KEY)
        send(action, "A follow-up\nwith two lines")
        check(requests.await(5, TimeUnit.SECONDS))
        assertThat(receivedPath).isEqualTo("/mount%20name/api/chat/start")
        assertThat(receivedCookie).contains("reply_fixture=synthetic")
        val payload = JSONObject(receivedBody!!)
        assertThat(payload.getString("session_id")).isEqualTo("team/notes")
        assertThat(payload.getString("profile")).isEqualTo("work")
        assertThat(payload.getString("message")).isEqualTo("A follow-up\nwith two lines")
        awaitResult("Reply accepted by Hermes.")
        send(action, "Duplicate")
        awaitResult("Reply expired or unavailable.", fallback = true)
    }

    @Test
    fun oldServerActionsCannotSendAfterASelectionChange() {
        val action = replyAction()
        SettingsRepository(context).saveAppUrls("http://127.0.0.1:9/", "")
        send(action, "Must not be sent")
        awaitResult("Reply expired or unavailable.")
        assertNoConnection()
    }

    @Test
    fun switchingAwayAndBackRevokesAnOldActionEvenAtTheSameUrl() {
        val action = replyAction()
        NotificationReplyActions.onSelectedServerChanged("http://127.0.0.1:9/")
        NotificationReplyActions.onSelectedServerChanged(serverUrl)
        send(action, "Must not be sent")
        awaitResult("Reply expired or unavailable.", fallback = true)
        assertNoConnection()
    }

    @Test
    fun expiredActionsCannotSend() {
        currentTag = newTag()
        val action = NotificationReplyActions.action(context, NotificationReplyTarget(
            serverUrl, targetUrl, "work", currentTag, 90_001, expiresAt = SystemClock.elapsedRealtime() - 1
        ))
        val intent = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(NotificationReplyActions.INPUT_KEY).build()), intent,
            android.os.Bundle().apply { putCharSequence(NotificationReplyActions.INPUT_KEY, "Must not be sent") }
        )
        requireNotNull(action.actionIntent).send(context, 0, intent)
        awaitResult("Reply expired or unavailable.", fallback = true)
        assertNoConnection()
    }

    private fun assertNoConnection() {
        server.soTimeout = 250
        try {
            server.accept().use { error("A stale reply opened a network connection") }
        } catch (_: SocketTimeoutException) {
            // No network request reached the old server.
        }
    }

    @Test
    fun redirectsNeverClaimTheReplyWasAccepted() {
        val requests = serve("<html>sign in</html>", status = "302 Found")
        send(replyAction(), "Hello")
        check(requests.await(5, TimeUnit.SECONDS))
        awaitResult("Hermes rejected the reply.")
    }

    @Test
    fun aSuccessWithoutASessionAdmissionIsUnconfirmed() {
        val requests = serve("""{"ok":true}""")
        send(replyAction(), "Hello")
        check(requests.await(5, TimeUnit.SECONDS))
        awaitResult("Could not confirm the reply.")
    }

    @Test
    fun gatewayFailuresAndTimeoutsDoNotClaimTheReplyWasRejected() {
        for (status in listOf("502 Bad Gateway", "504 Gateway Timeout", "408 Request Timeout")) {
            SystemClock.sleep(1_100)
            val requests = serve("""{"error":"gateway could not confirm upstream response"}""", status)
            send(replyAction(), "Hello")
            check(requests.await(5, TimeUnit.SECONDS))
            awaitResult("Could not confirm the reply.")
        }
    }

    @Test
    fun aDelayedReplyResultCannotReplaceANewerNonReplyNotification() {
        val releaseResponse = CountDownLatch(1)
        val requests = serve("""{"stream_id":"stream-1","session_id":"team/notes"}""") {
            check(releaseResponse.await(5, TimeUnit.SECONDS))
        }
        send(replyAction(), "Hello")
        check(requests.await(5, TimeUnit.SECONDS))
        try {
            presenter.showNotification(
                JSONObject().put("title", "Approval required")
                    .put("options", JSONObject().put("tag", currentTag).put("body", "Choose an approval action")),
                targetUrl, serverUrl
            )
            awaitResult("Choose an approval action")
        } finally {
            releaseResponse.countDown()
        }
        awaitBroadcastIdle()
        val notification = manager.activeNotifications.single { it.tag == currentTag }.notification
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            .isEqualTo("Approval required")
        assertThat(notification.actions.none { it.title.toString() == "Reply" }).isTrue()
    }

    private fun replyAction(): Notification.Action {
        currentTag = newTag()
        presenter.showNotification(
            JSONObject().put("title", "Response complete").put("replyProfile", "work")
                .put("replySessionId", "team/notes")
                .put("options", JSONObject().put("tag", currentTag).put("data", JSONObject().put("url", targetUrl))),
            targetUrl, serverUrl
        )
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            manager.activeNotifications.firstOrNull { it.tag == currentTag }?.notification?.actions
                ?.firstOrNull { it.title.toString() == "Reply" }?.let { return it }
            SystemClock.sleep(25)
        }
        error("Reply action was not posted")
    }

    private fun send(action: Notification.Action, text: String) {
        val intent = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(NotificationReplyActions.INPUT_KEY).build()),
            intent, android.os.Bundle().apply { putCharSequence(NotificationReplyActions.INPUT_KEY, text) }
        )
        action.actionIntent.send(context, 0, intent)
    }

    private fun awaitResult(prefix: String, fallback: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (manager.activeNotifications.any {
                (if (fallback) it.tag == null && it.id == 20_004 else it.tag == currentTag) &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.startsWith(prefix) == true
            }) return
            SystemClock.sleep(25)
        }
        val observed = manager.activeNotifications.filter {
            it.tag == currentTag || (it.tag == null && it.id == 20_004)
        }.map { "${it.id}: ${it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)}" }
        error("Reply result did not start with $prefix; fixture notifications: $observed")
    }

    private fun serve(body: String, status: String = "200 OK", beforeResponse: () -> Unit = {}): CountDownLatch {
        val requests = CountDownLatch(1)
        thread(isDaemon = true, name = "reply-fixture") {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader()
                receivedPath = reader.readLine().split(" ")[1]
                var length = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    if (line.startsWith("Cookie:", true)) receivedCookie = line.substringAfter(':').trim()
                }
                val buffer = CharArray(length)
                var count = 0
                while (count < length) count += reader.read(buffer, count, length - count)
                receivedBody = String(buffer)
                requests.countDown()
                beforeResponse()
                val bytes = body.toByteArray()
                socket.getOutputStream().write(
                    ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                        "Location: http://127.0.0.1:9/sign-in\r\nConnection: close\r\n\r\n").toByteArray() + bytes
                )
            }
        }
        return requests
    }

    private fun newTag(): String = "reply-fixture-${UUID.randomUUID()}".also(notificationTags::add)

    private fun awaitBroadcastIdle() {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("am wait-for-broadcast-idle")
        val output = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        assertThat(output).contains("idle")
    }

    companion object {
        private const val CHANNEL = "hermes_webui_notifications"
    }
}
