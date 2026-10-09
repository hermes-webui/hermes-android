package com.hermeswebui.android.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.R
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.core.security.WebTrustPolicy
import com.hermeswebui.android.data.AppSettings
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.ui.MainUiState
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the production coordinator and Android service, not a mocked start intent.
 * Uses a debug test activity for real lifecycle transitions plus explicit coordinator events.
 * No real WebUI or MainActivity settings are changed.
 */
@RunWith(AndroidJUnit4::class)
class HermesSessionMonitorInstrumentationTest {
    @get:Rule
    val notificationPermission: GrantPermissionRule =
        GrantPermissionRule.grant("android.permission.POST_NOTIFICATIONS")

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var fixture: SessionServer
    private lateinit var coordinator: HermesForegroundServiceCoordinator
    private lateinit var state: MainUiState
    private var createdChannel = false
    private var canceledRetries = 0
    private val cookieName = "issue123_instrumentation"

    @Before
    fun setUp() {
        // Android 12+ otherwise disallows a test process starting an FGS without a visible activity.
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND"
        )
        createdChannel = notifications.getNotificationChannel(CHANNEL_ID) == null
        if (createdChannel) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hermes instrumentation", NotificationManager.IMPORTANCE_LOW)
            )
        }
        fixture = SessionServer()
        val settings = AppSettings(
            serverUrl = fixture.baseUrl,
            dashboardUrl = "${fixture.baseUrl}/dashboard",
            allowedHosts = setOf("127.0.0.1"),
            isConfigured = true
        )
        val trust = WebTrustPolicy(
            UrlPolicy(settings.allowedHosts), settings.serverUrl, settings.dashboardUrl
        )
        coordinator = HermesForegroundServiceCoordinator(
            context, SettingsRepository(context), trust::isTrustedNotificationTarget,
            { canceledRetries++ }, { error("Debug logging must remain disabled") }
        )
        state = MainUiState(
            settings = settings,
            currentUrl = "${fixture.baseUrl}/session/regression-123",
            backgroundReconnectEnabled = true,
            sseTransportEnabled = true
        )
        onMain {
            CookieManager.getInstance().setCookie(
                fixture.baseUrl, "$cookieName=synthetic-only; Path=/"
            )
            CookieManager.getInstance().flush()
        }
        HermesReconnectService.stop(context)
        eventually("Previous monitor removed") { notification() == null }
        // Keep separate fixtures out of Android's per-package notification update rate window.
        SystemClock.sleep(1_100)
    }

    @After
    fun tearDown() {
        try {
            if (::coordinator.isInitialized) onMain { coordinator.onActivityResumed() }
            HermesReconnectService.stop(context)
            eventually("Monitor notification removed during cleanup") { notification() == null }
            if (::fixture.isInitialized) {
                onMain {
                    CookieManager.getInstance().setCookie(
                        fixture.baseUrl, "$cookieName=; Max-Age=0; Path=/"
                    )
                    CookieManager.getInstance().flush()
                }
                fixture.close()
            }
            if (createdChannel) notifications.deleteNotificationChannel(CHANNEL_ID)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun trustedSession_backgroundStartsAuthenticatedStreamAndRealApprovalActions_resumeClosesSocket() {
        onMain { coordinator.onActivityStopped(state, activityVisible = false) }
        val stream = fixture.awaitStream(0)
        assertStream(stream, "regression-123")
        val initial = awaitNotification()
        assertThat(initial.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(initial.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
        assertThat(initial.publicVersion).isNotNull()

        stream.event("activity_summary", """{"summary":"Synthetic agent is examining files"}""")
        awaitNotification("Synthetic agent is examining files")
        stream.event(
            "approval_required",
            """{"approval_id":"approval-123","description":"Allow synthetic tool?","choices":["once","deny"]}"""
        )
        val approval = awaitNotification("Allow synthetic tool?")
        assertThat(approval.actions.map { it.title.toString() }).containsExactly("Allow once", "Deny")
        approval.actions.first().actionIntent.send()
        val pending = fixture.awaitRequest("/api/approval/pending")
        val response = fixture.awaitRequest("/api/approval/respond")
        assertThat(pending.target).isEqualTo("/api/approval/pending?session_id=regression-123")
        assertThat(pending.headers["cookie"]).contains("$cookieName=synthetic-only")
        assertThat(response.method).isEqualTo("POST")
        assertThat(response.headers["cookie"]).contains("$cookieName=synthetic-only")
        val payload = JSONObject(response.body)
        assertThat(payload.getString("session_id")).isEqualTo("regression-123")
        assertThat(payload.getString("approval_id")).isEqualTo("approval-123")
        assertThat(payload.getString("choice")).isEqualTo("once")
        assertThat(fixture.requests.indexOf(pending)).isLessThan(fixture.requests.indexOf(response))
        eventually("Approval confirmation has no stale action") {
            val current = notification()
            current?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ==
                context.getString(R.string.approval_notification_sent, "Allow once") &&
                current.actions.isNullOrEmpty()
        }
        onMain { coordinator.onActivityResumed() }
        eventually("Resume removes the ongoing notification") { notification() == null }
        assertThat(stream.closed.await(8, TimeUnit.SECONDS)).isTrue()
        assertThat(canceledRetries).isEqualTo(1)
    }

    @Test
    fun mountedEncodedSession_isDecodedOnceAndUsesExistingRootApiContract() {
        state = state.copy(
            settings = state.settings.copy(serverUrl = "${fixture.baseUrl}/mounted/hermes"),
            currentUrl = "${fixture.baseUrl}/mounted/hermes/session/id%2Fpart%252Ftail%2Bplus"
        )
        onMain { coordinator.onActivityStopped(state, activityVisible = false) }
        assertStream(fixture.awaitStream(0), "id/part%2Ftail+plus")
    }

    @Test
    fun foregroundDisabledTransportAndUntrustedRoutes_doNotStartSessionMonitor() {
        val rejected = listOf(
            state to true,
            state.copy(backgroundReconnectEnabled = false) to false,
            state.copy(sseTransportEnabled = false) to false,
            state.copy(currentUrl = "${fixture.baseUrl}/settings") to false,
            state.copy(currentUrl = "${fixture.baseUrl}/regression-123") to false,
            state.copy(currentUrl = "${fixture.baseUrl}/session/id/details") to false,
            state.copy(currentUrl = "https://provider.invalid/session/provider-id") to false,
            state.copy(currentUrl = "${fixture.baseUrl}/dashboard/session/dashboard-id") to false,
            state.copy(currentUrl = "http://127.0.0.1:1/session/wrong-origin") to false
        )
        rejected.forEach { (candidate, visible) ->
            onMain { coordinator.onUiStateChanged(candidate, visible) }
            SystemClock.sleep(300)
            assertThat(notification()).isNull()
            assertThat(fixture.requests).isEmpty()
        }
    }

    @Test
    fun repeatedBackgroundResumeCycles_reopenStreamAndApplySettingsTeardown() {
        repeat(3) { index ->
            onMain { coordinator.onActivityStopped(state, activityVisible = false) }
            val stream = fixture.awaitStream(index)
            assertStream(stream, "regression-123")
            awaitNotification()
            if (index == 1) {
                onMain {
                    coordinator.onUiStateChanged(state.copy(backgroundReconnectEnabled = false), false)
                }
            } else if (index == 2) {
                onMain {
                    coordinator.onUiStateChanged(state.copy(sseTransportEnabled = false), false)
                }
            } else {
                onMain { coordinator.onActivityResumed() }
            }
            eventually("Cycle $index removes notification") { notification() == null }
            assertThat(stream.closed.await(8, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun terminalAndEof_selfStopThenResumeAllowsAnotherBackgroundMonitor() {
        repeat(3) { index ->
            onMain { coordinator.onActivityStopped(state, activityVisible = false) }
            val stream = fixture.awaitStream(index)
            awaitNotification()
            if (index == 0) {
                stream.event("turn_completed", """{"summary":"Synthetic turn finished"}""")
            } else {
                stream.socket.close()
            }
            eventually("Terminal or EOF removes notification") { notification() == null }
            assertThat(stream.closed.await(8, TimeUnit.SECONDS)).isTrue()
            onMain { coordinator.onActivityResumed() }
        }
    }

    @Test
    fun reconnectWithoutSession_transitionsToTrustedSessionWithoutActivityResume() {
        onMain {
            coordinator.onActivityStopped(
                state.copy(currentUrl = fixture.baseUrl, isReconnecting = true), false
            )
        }
        awaitNotification()
        assertThat(fixture.streams).isEmpty()
        onMain { coordinator.onUiStateChanged(state, activityVisible = false) }
        assertStream(fixture.awaitStream(0), "regression-123")
    }

    @Test
    fun realActivityStopAndResume_startAndTearDownMonitorWithoutBackgroundStartExemption() {
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onResume(owner: LifecycleOwner) {
                        coordinator.onActivityResumed()
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        coordinator.onActivityStopped(state, activityVisible = false)
                    }
                })
                coordinator.onUiStateChanged(state, activityVisible = true)
            }
            assertThat(fixture.streams).isEmpty()
            scenario.moveToState(Lifecycle.State.CREATED)
            val stream = fixture.awaitStream(0)
            assertStream(stream, "regression-123")
            awaitNotification()
            scenario.moveToState(Lifecycle.State.RESUMED)
            eventually("Actual resume tears down notification") { notification() == null }
            assertThat(stream.closed.await(8, TimeUnit.SECONDS)).isTrue()
            // Prevent the host's destruction from starting another monitor in onStop.
            scenario.onActivity { state = state.copy(backgroundReconnectEnabled = false) }
        }
    }

    @Test
    fun backgroundSessionChange_replacesStreamAndClearsPreviousApprovalAndPrivacy() {
        onMain { coordinator.onActivityStopped(state.copy(backgroundActivityFullTextEnabled = true), false) }
        val previous = fixture.awaitStream(0)
        previous.event(
            "approval_required",
            """{"approval_id":"approval-123","description":"Old session approval","choices":["once","deny"]}"""
        )
        assertThat(awaitNotification("Old session approval").visibility)
            .isEqualTo(Notification.VISIBILITY_PUBLIC)
        state = state.copy(currentUrl = "${fixture.baseUrl}/session/next-session")
        onMain { coordinator.onUiStateChanged(state, false) }
        val replacement = fixture.awaitStream(1)
        assertStream(replacement, "next-session")
        assertThat(previous.closed.await(8, TimeUnit.SECONDS)).isTrue()
        eventually("New session clears old approval and honors privacy") {
            val current = notification()
            current != null && current.actions.isNullOrEmpty() &&
                current.visibility == Notification.VISIBILITY_PRIVATE &&
                current.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() != "Old session approval"
        }
        replacement.event("activity_summary", """{"summary":"Replacement session remains monitored"}""")
        awaitNotification("Replacement session remains monitored")
        onMain { coordinator.onUiStateChanged(state, false) }
        SystemClock.sleep(500)
        assertThat(fixture.streams).hasSize(2)
        assertThat(notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            .isEqualTo("Replacement session remains monitored")
    }

    @Test
    fun inFlightApprovalFromPreviousSession_cannotOverwriteReplacementNotification() {
        val releasePending = CountDownLatch(1)
        fixture.pendingResponseGate = releasePending
        try {
            onMain { coordinator.onActivityStopped(state, false) }
            fixture.awaitStream(0).event(
                "approval_required",
                """{"approval_id":"approval-123","description":"Previous session approval","choices":["once","deny"]}"""
            )
            awaitNotification("Previous session approval").actions.first().actionIntent.send()
            fixture.awaitRequest("/api/approval/pending")
            state = state.copy(currentUrl = "${fixture.baseUrl}/session/replacement")
            onMain { coordinator.onUiStateChanged(state, false) }
            val replacement = fixture.awaitStream(1)
            replacement.event("activity_summary", """{"summary":"Replacement must retain its notification"}""")
            awaitNotification("Replacement must retain its notification")
            releasePending.countDown()
            assertThat(fixture.pendingResponseSent.await(8, TimeUnit.SECONDS)).isTrue()
            SystemClock.sleep(1_000)
            assertThat(notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                .isEqualTo("Replacement must retain its notification")
            assertThat(fixture.requests.none { it.target == "/api/approval/respond" }).isTrue()
        } finally {
            releasePending.countDown()
        }
    }

    @Test
    fun delayedPreviousSessionPendingIntent_cannotRestoreOldMonitorOrClearNewApproval() {
        onMain { coordinator.onActivityStopped(state, false) }
        fixture.awaitStream(0).event(
            "approval_required",
            """{"approval_id":"approval-123","description":"Old approval action","choices":["once","deny"]}"""
        )
        val oldAction = awaitNotification("Old approval action").actions.first().actionIntent
        state = state.copy(currentUrl = "${fixture.baseUrl}/session/new-session")
        onMain { coordinator.onUiStateChanged(state, false) }
        fixture.awaitStream(1).event(
            "approval_required",
            """{"approval_id":"new-approval","description":"New session approval remains actionable","choices":["once","deny"]}"""
        )
        awaitNotification("New session approval remains actionable")
        oldAction.send()
        SystemClock.sleep(1_000)
        assertThat(notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            .isEqualTo("New session approval remains actionable")
        assertThat(notification()?.actions?.size).isEqualTo(2)
        assertThat(fixture.requests.none { it.target.startsWith("/api/approval/") }).isTrue()
    }

    @Test
    fun delayedPreviousApprovalInSameSession_cannotClearNewApproval() {
        onMain { coordinator.onActivityStopped(state, false) }
        val stream = fixture.awaitStream(0)
        stream.event(
            "approval_required",
            """{"approval_id":"approval-123","description":"Old same-session approval","choices":["once","deny"]}"""
        )
        val oldAction = awaitNotification("Old same-session approval").actions.first().actionIntent
        stream.event(
            "approval_required",
            """{"approval_id":"new-approval","description":"New same-session approval","choices":["once","deny"]}"""
        )
        awaitNotification("New same-session approval")
        oldAction.send()
        SystemClock.sleep(1_000)
        assertThat(notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            .isEqualTo("New same-session approval")
        assertThat(notification()?.actions?.size).isEqualTo(2)
        assertThat(fixture.requests.none { it.target.startsWith("/api/approval/") }).isTrue()
    }

    @Test
    fun inFlightPreviousApprovalInSameSession_cannotOverwriteNewApproval() {
        val releasePending = CountDownLatch(1)
        fixture.pendingResponseGate = releasePending
        try {
            onMain { coordinator.onActivityStopped(state, false) }
            val stream = fixture.awaitStream(0)
            stream.event(
                "approval_required",
                """{"approval_id":"approval-123","description":"Old in-flight approval","choices":["once","deny"]}"""
            )
            awaitNotification("Old in-flight approval").actions.first().actionIntent.send()
            fixture.awaitRequest("/api/approval/pending")
            stream.event(
                "approval_required",
                """{"approval_id":"new-approval","description":"New approval stays actionable","choices":["once","deny"]}"""
            )
            awaitNotification("New approval stays actionable")
            releasePending.countDown()
            assertThat(fixture.pendingResponseSent.await(8, TimeUnit.SECONDS)).isTrue()
            SystemClock.sleep(1_000)
            assertThat(notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                .isEqualTo("New approval stays actionable")
            assertThat(notification()?.actions?.size).isEqualTo(2)
            assertThat(fixture.requests.none { it.target == "/api/approval/respond" }).isTrue()
        } finally {
            releasePending.countDown()
        }
    }

    private fun assertStream(stream: Stream, sessionId: String) {
        val encoded = java.net.URLEncoder.encode(sessionId, "UTF-8")
        assertThat(stream.request.target).isEqualTo("/api/session/stream?session_id=$encoded")
        assertThat(stream.request.headers["cookie"]).contains("$cookieName=synthetic-only")
        assertThat(stream.request.headers["accept"]).isEqualTo("text/event-stream")
    }

    private fun notification(): Notification? = notifications.activeNotifications
        .firstOrNull { it.id == NOTIFICATION_ID }?.notification

    private fun awaitNotification(text: String? = null): Notification {
        try {
            eventually("Notification ${text ?: "posted"}") {
                val current = notification()
                current != null && (text == null ||
                    current.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == text)
            }
        } catch (error: AssertionError) {
            throw AssertionError(
                "${error.message}; actual=${notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)}; " +
                    "streams=${fixture.streams.size}, closed=${fixture.streams.map { it.closed.count }}",
                error
            )
        }
        return checkNotNull(notification())
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private data class Request(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: String
    )

    private class Stream(val request: Request, val socket: Socket) {
        val closed = CountDownLatch(1)

        fun event(name: String, json: String) {
            synchronized(socket) {
                socket.getOutputStream().apply {
                    write("event: $name\ndata: $json\n\n".toByteArray())
                    flush()
                }
            }
        }
    }

    private class SessionServer : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val baseUrl = "http://127.0.0.1:${listener.localPort}"
        val requests = CopyOnWriteArrayList<Request>()
        val streams = CopyOnWriteArrayList<Stream>()
        @Volatile
        var pendingResponseGate: CountDownLatch? = null
        val pendingResponseSent = CountDownLatch(1)
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val acceptor = thread(name = "issue123-http", isDaemon = true) {
            while (!listener.isClosed) {
                val socket = try {
                    listener.accept()
                } catch (_: SocketException) {
                    break
                }
                sockets += socket
                workers += thread(name = "issue123-http-client", isDaemon = true) {
                    handle(socket)
                }
            }
        }

        fun awaitStream(index: Int): Stream {
            eventually("Authenticated SSE connection $index") { streams.size > index }
            return streams[index]
        }

        fun awaitRequest(path: String): Request {
            eventually("HTTP request $path") { requests.any { it.target.substringBefore('?') == path } }
            return requests.first { it.target.substringBefore('?') == path }
        }

        private fun handle(socket: Socket) {
            var stream: Stream? = null
            try {
                socket.use {
                    socket.soTimeout = 15_000
                    val reader = socket.getInputStream().bufferedReader()
                    val first = checkNotNull(reader.readLine()).split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                    }
                    val chars = CharArray(headers["content-length"]?.toInt() ?: 0)
                    var offset = 0
                    while (offset < chars.size) {
                        val count = reader.read(chars, offset, chars.size - offset)
                        check(count > 0)
                        offset += count
                    }
                    val request = Request(first[0], first[1], headers, String(chars))
                    requests += request
                    val output = socket.getOutputStream()
                    if (request.target.startsWith("/api/session/stream?")) {
                        output.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                                "Connection: close\r\n\r\n: fixture ready\n\n").toByteArray()
                        )
                        output.flush()
                        stream = Stream(request, socket)
                        streams += checkNotNull(stream)
                        // Observe peer closure, rather than treating notification removal as socket proof.
                        socket.soTimeout = 0
                        while (reader.read() != -1) { /* Wait for client EOF. */ }
                    } else {
                        if (request.target.startsWith("/api/approval/pending?")) {
                            check(pendingResponseGate?.await(8, TimeUnit.SECONDS) != false)
                        }
                        val body = if (request.target.startsWith("/api/approval/pending?")) {
                            """{"approval_id":"approval-123","session_id":"regression-123","choices":["once","deny"]}"""
                        } else {
                            """{"ok":true}"""
                        }.toByteArray()
                        output.write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
                        )
                        output.write(body)
                        output.flush()
                        if (request.target.startsWith("/api/approval/pending?")) {
                            pendingResponseSent.countDown()
                        }
                    }
                }
            } catch (_: SocketException) {
                // Service cancellation and fixture EOF intentionally close sockets.
            } finally {
                stream?.closed?.countDown()
                sockets.remove(socket)
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            acceptor.join(2_000)
            workers.forEach { it.join(2_000) }
        }
    }

    companion object {
        private const val CHANNEL_ID = "hermes_webui_notifications"
        private const val NOTIFICATION_ID = 20_001

        private fun eventually(description: String, predicate: () -> Boolean) {
            // Android can defer the first FGS notification for ten seconds.
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (predicate()) return
                SystemClock.sleep(50)
            }
            throw AssertionError("Timed out: $description")
        }
    }
}
