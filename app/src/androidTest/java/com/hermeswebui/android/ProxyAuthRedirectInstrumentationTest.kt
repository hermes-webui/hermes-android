package com.hermeswebui.android

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.TextView
import androidx.test.filters.SdkSuppress
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.HermesApiClient
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.data.DiagnosticsLogger
import com.hermeswebui.android.background.HermesDebugLoggingService
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

/** Real HTTP, setup coordinator, consent UI and main WebView; no upstream Authelia is implied. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ProxyAuthRedirectInstrumentationTest {
    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant("android.permission.POST_NOTIFICATIONS")
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun statusRedirectRemainsReachableWithoutFollowingIt() = runBlocking {
        for (code in listOf(301, 302, 303, 307, 308)) {
            ProxyServer(redirectCode = code).use { server ->
                val result = HermesApiClient.checkServerReadiness(server.baseUrl)
                assertThat(result.isReady).isFalse()
                assertThat(result.status).isEqualTo(HermesApiClient.ServerReadinessStatus.REDIRECTED)
                assertThat(result.diagnostics).doesNotContain("synthetic-secret")
                assertThat(HermesApiClient.isServerReachable(server.baseUrl)).isTrue()
                assertThat(server.requests.none { it.host.startsWith("localhost:") }).isTrue()
            }
        }
    }

    @Test
    fun missingOrNonWebRedirectLocationsRemainBlocked() = runBlocking {
        for (location in listOf("", "javascript:alert(1)", "https://user:password@login.test")) {
            ProxyServer(locationOverride = location).use { server ->
                val result = HermesApiClient.checkServerReadiness(server.baseUrl)
                assertThat(result.signInRedirectOrigin).isNull()
                assertThat(result.isReady).isFalse()
                assertThat(HermesApiClient.isServerReachable(server.baseUrl)).isFalse()
            }
        }
    }

    @Test
    fun startupConsentKeepsProxyLoginAndCookieBackedReturnInMainWebView() {
        withConfiguredServer { server, repository ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("Continue to server sign-in?")
                assertThat(server.requests.none { it.host.startsWith("localhost:") }).isTrue()
                clickDialogButton("Continue")
                awaitLoginForm(scenario, server)
                assertThat(repository.getLastLoadedUrl()).doesNotContain("login")
                evaluateWebView(scenario, "document.getElementById('signin').click();")
                awaitWebView(scenario) { it.url == "${server.baseUrl}/session/issue132" && it.title == "Hermes signed in" }
                eventually("Final Hermes route persisted") {
                    repository.getLastLoadedUrl() == "${server.baseUrl}/session/issue132"
                }
                val ready = runBlocking { HermesApiClient.checkServerReadiness(server.baseUrl) }
                assertThat(ready.isReady).isTrue()
                assertThat(server.requests.any { it.path == "/api/status" && it.cookie.contains("proxy_auth=ok") }).isTrue()
                assertThat(server.requests.none { it.host.startsWith("localhost:") && it.cookie.contains("proxy_auth=ok") }).isTrue()
                server.rejectSession.set(true)
                scenario.onActivity { activity ->
                    findWebView(activity.findViewById(android.R.id.content))!!.loadUrl(server.baseUrl)
                }
                awaitText("${server.baseUrl} redirected to ${server.loginOrigin}.")
                clickDialogButton("Continue")
                awaitLoginForm(scenario, server)
                assertThat(repository.getLastLoadedUrl()).isEqualTo("${server.baseUrl}/session/issue132")
            }
        }
    }

    @Test
    fun cancellingStartupConsentNeverLoadsLoginHost() {
        withConfiguredServer { server, _ ->
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitText("Continue to server sign-in?")
                clickDialogButton("Cancel")
                instrumentation.waitForIdleSync()
                assertThat(server.requests.none { it.host.startsWith("localhost:") }).isTrue()
            }
        }
    }

    private fun withConfiguredServer(block: (ProxyServer, SettingsRepository) -> Unit) {
        // Restore the exact encrypted backing preferences, including keysets, after the test.
        val preferences = context.getSharedPreferences("hermes_secure_prefs", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        val loggingWasEnabled = SettingsRepository(context).isDebugLoggingEnabled()
        ProxyServer().use { server ->
            try {
                val repository = SettingsRepository(context)
                repository.getProfiles().forEach { repository.deleteProfile(it.id) }
                repository.saveAppUrls(server.baseUrl, "")
                repository.saveLastLoadedUrl(server.baseUrl)
                instrumentation.runOnMainSync {
                    CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=; Max-Age=0; Path=/")
                }
                block(server, repository)
            } finally {
                if (!loggingWasEnabled) HermesDebugLoggingService.stop(context)
                instrumentation.runOnMainSync {
                    CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=; Max-Age=0; Path=/")
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
        }
    }

    private fun awaitText(text: String) {
        eventually({ "$text\n${DiagnosticsLogger.recentText(context).takeLast(3_000)}" }) {
            var found = false
            instrumentation.runOnMainSync {
                found = WindowInspector.getGlobalWindowViews().any { root ->
                    descendants(root).filterIsInstance<TextView>().any { it.text.contains(text) && it.isShown }
                }
            }
            found
        }
    }

    private fun clickDialogButton(text: String) {
        var clicked = false
        eventually("Native $text button") {
            instrumentation.runOnMainSync {
                val button = WindowInspector.getGlobalWindowViews().asSequence()
                    .flatMap { descendants(it) }.filterIsInstance<Button>()
                    .firstOrNull { it.text.toString().equals(text, true) && it.isShown && it.isEnabled }
                if (button != null) clicked = button.performClick()
            }
            clicked
        }
        instrumentation.waitForIdleSync()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }

    private fun awaitWebView(scenario: ActivityScenario<MainActivity>, predicate: (WebView) -> Boolean) {
        var observed = ""
        eventually({ "Expected main WebView page; observed $observed\n${DiagnosticsLogger.recentText(context).takeLast(3_000)}" }) {
            var matches = false
            scenario.onActivity { activity ->
                findWebView(activity.findViewById(android.R.id.content))?.let {
                    observed = "${it.url} (${it.title})"
                    matches = predicate(it)
                }
            }
            matches
        }
    }

    private fun awaitLoginForm(scenario: ActivityScenario<MainActivity>, server: ProxyServer) {
        eventually("Login document and sign-in button ready") {
            evaluateWebView(
                scenario,
                """
                document.readyState === 'complete' &&
                  location.origin === ${JSONObject.quote(server.loginOrigin)} &&
                  document.getElementById('signin') !== null
                """.trimIndent()
            ) == "true"
        }
    }

    private fun evaluateWebView(scenario: ActivityScenario<MainActivity>, script: String): String? {
        val completed = CountDownLatch(1)
        var result: String? = null
        scenario.onActivity { activity ->
            checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                .evaluateJavascript(script) {
                    result = it
                    completed.countDown()
                }
        }
        check(completed.await(5, TimeUnit.SECONDS)) { "WebView JavaScript callback timed out" }
        return result
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun eventually(description: String, condition: () -> Boolean) = eventually({ description }, condition)

    private fun eventually(description: () -> String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Timed out: ${description()}")
    }

    private class ProxyServer(
        private val redirectCode: Int = 302,
        private val locationOverride: String? = null
    ) : AutoCloseable {
        data class Request(val path: String, val host: String, val cookie: String)
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val baseUrl = "http://127.0.0.1:${socket.localPort}"
        val loginOrigin = "http://localhost:${socket.localPort}"
        val requests = CopyOnWriteArrayList<Request>()
        val rejectSession = AtomicBoolean(false)
        private val worker = thread(name = "proxy-auth-fixture") {
            while (running.get()) {
                val client = try { socket.accept() } catch (exception: SocketException) {
                    if (running.get()) throw exception
                    break
                }
                client.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader()
                    val first = reader.readLine() ?: return@use
                    val headers = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers.add(line)
                    }
                    val path = first.split(" ")[1].substringBefore('?')
                    fun header(name: String) = headers.firstOrNull { h -> h.startsWith("$name:", true) }
                        ?.substringAfter(':')?.trim().orEmpty()
                    val cookie = header("Cookie")
                    requests.add(Request(path, header("Host"), cookie))
                    val authenticated = cookie.contains("proxy_auth=ok") && !rejectSession.get()
                    val (status, extra, body) = when {
                        path == "/api/status" && authenticated -> Triple("200 OK", "", """{"version":"test","initialized":true}""")
                        path == "/api/status" || (path == "/" && !authenticated) ->
                            Triple("$redirectCode Redirect", "Location: ${locationOverride ?: "$loginOrigin/login?rd=synthetic-secret"}\r\n", "")
                        path == "/login" -> Triple("200 OK", "", """
                            <title>Proxy sign-in</title>
                            <script>
                              setTimeout(function() {
                                var button = document.createElement('button');
                                button.id = 'signin';
                                button.textContent = 'Sign in';
                                button.onclick = function() {
                                  location.href='$baseUrl/auth/return?ticket=synthetic';
                                };
                                document.body.appendChild(button);
                              }, 3000);
                            </script>
                        """.trimIndent())
                        path == "/auth/return" -> Triple("302 Found",
                            "Set-Cookie: proxy_auth=ok; Path=/; HttpOnly; SameSite=Lax\r\nLocation: /auth/interstitial\r\n", "")
                        path == "/auth/interstitial" -> Triple("200 OK", "", """
                            <title>Completing authentication</title>
                            <script>setTimeout(function(){location.href='/session/issue132';},250);</script>
                        """.trimIndent())
                        path == "/session/issue132" && authenticated -> Triple("200 OK", "", """
                            <title>Hermes signed in</title><div class="layout"><main class="main">Authenticated</main></div>
                        """.trimIndent())
                        else -> Triple("404 Not Found", "", "")
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val type = if (path == "/api/status") "application/json" else "text/html"
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 $status\r\n${extra}Content-Type: $type; charset=utf-8\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }

        override fun close() {
            running.set(false)
            socket.close()
            worker.join(6_000)
            check(!worker.isAlive)
        }
    }
}
