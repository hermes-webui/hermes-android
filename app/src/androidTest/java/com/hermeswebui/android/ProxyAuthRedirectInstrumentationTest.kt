package com.hermeswebui.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.SystemClock
import android.text.method.PasswordTransformationMethod
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.filters.SdkSuppress
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.HermesApiClient
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.data.ServerType
import com.hermeswebui.android.data.DiagnosticsLogger
import com.hermeswebui.android.background.HermesDebugLoggingService
import com.hermeswebui.android.notification.HermesNotificationPresenter
import com.hermeswebui.android.ui.MainViewModel
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.json.JSONObject

/** Real HTTP, setup coordinator, consent UI and main WebView; no upstream Authelia is implied. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ProxyAuthRedirectInstrumentationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant("android.permission.POST_NOTIFICATIONS")
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun officialWebapp_signInReturnPersistsAndBackOpensSettingsWithoutNavigating() {
        withConfiguredServer(officialWebapp = true) { server, repository ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("Continue to server sign-in?")
                clickDialogButton("Continue")
                awaitLoginForm(scenario, server)
                assertThat(repository.getProfiles()).isEmpty()
                evaluateWebView(scenario, "document.getElementById('signin').click();")
                eventually("Official workspace proves sign-in return and persists") {
                    repository.getLastLoadedUrl() == "${server.baseUrl}/"
                }
                assertOfficialBackPreservesWorkspace(scenario)
            }
        }
    }

    @Test
    fun httpBasicChallenge_showsNativePromptAndLoadsServerAfterCredentialsAreEntered() {
        withConfiguredServer(basicAuth = true) { server, _ ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("Sign in to 127.0.0.1")
                enterHttpAuthCredentials("test-user", "test-pass")
                clickDialogButton("Sign in")
                awaitWebView(scenario) {
                    it.url == "${server.baseUrl}/" && it.title == "Hermes HTTP authenticated"
                }
                assertThat(server.requests.any {
                    it.path == "/" && it.authorization == "Basic dGVzdC11c2VyOnRlc3QtcGFzcw=="
                }).isTrue()
            }
        }
    }

    @Test
    fun configuredCredentialsWithDelimitersRemainUsableForPageNavigation() {
        withConfiguredServer(officialWebapp = true) { server, repository ->
            val configured = server.baseUrl.replace("http://", "http://test-user:p=;:ass@")
            repository.saveAppUrls(configured, "")
            repository.saveLastLoadedUrl(configured)
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                fun blockedNavigations(): Int = DiagnosticsLogger.recentText(context)
                    .lineSequence().count { it.contains("stale_credential_navigation_blocked") }
                val blockedBefore = blockedNavigations()
                evaluateWebView(
                    scenario,
                    "window.location.href = ${JSONObject.quote("$configured/session/delimiters")};",
                    requireCallback = false
                )
                eventually("Configured credentialed route was loaded or blocked") {
                    blockedNavigations() > blockedBefore ||
                        server.requests.any { it.path == "/session/delimiters" }
                }
                assertThat(server.requests.any { it.path == "/session/delimiters" }).isTrue()
                assertThat(blockedNavigations()).isEqualTo(blockedBefore)
            }
        }
    }

    @Test
    fun equivalentPercentEncodedCredentialsRemainUsableForPageNavigation() {
        withConfiguredServer(officialWebapp = true) { server, repository ->
            val configured = server.baseUrl.replace("http://", "http://test-user:p%3bss@")
            val target = server.baseUrl.replace("http://", "http://test-user:p%3Bss@") + "/session/encoded"
            repository.saveAppUrls(configured, "")
            repository.saveLastLoadedUrl(configured)
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                fun blockedNavigations(): Int = DiagnosticsLogger.recentText(context)
                    .lineSequence().count { it.contains("stale_credential_navigation_blocked") }
                val blockedBefore = blockedNavigations()
                evaluateWebView(scenario, "window.location.href = ${JSONObject.quote(target)};", requireCallback = false)
                eventually("Equivalent credentialed route was loaded or blocked") {
                    blockedNavigations() > blockedBefore || server.requests.any { it.path == "/session/encoded" }
                }
                assertThat(server.requests.any { it.path == "/session/encoded" }).isTrue()
                assertThat(blockedNavigations()).isEqualTo(blockedBefore)
            }
        }
    }

    @Test
    fun coldStart_rejectsLegacyVisitedAndNotificationUrlsWithRemovedCredentials() {
        withConfiguredServer(officialWebapp = true) { server, repository ->
            val credentialed = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            val staleRoute = "$credentialed/session/stale"
            repository.saveAppUrls(credentialed, "")
            repository.saveLastLoadedUrl(staleRoute)
            // Model a persisted URL from a version that did not clear it when credentials changed.
            check(encryptedPreferences().edit().putString("server_url", server.baseUrl).commit())
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                action = HermesNotificationPresenter.ACTION_OPEN_NOTIFICATION_URL
                putExtra(HermesNotificationPresenter.EXTRA_NOTIFICATION_URL, staleRoute)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                awaitWebView(scenario) {
                    it.title == "Hermes signed in" || it.title == "Hermes session stale"
                }
                assertThat(evaluateWebView(scenario, "location.href"))
                    .isEqualTo("\"${server.baseUrl}/\"")
                assertThat(server.requests.none { it.path == "/session/stale" }).isTrue()
            }
        }
    }

    @Test
    fun warmNotification_rejectsAUrlWithRemovedCredentials() {
        withConfiguredServer(officialWebapp = true) { server, _ ->
            val staleRoute = server.baseUrl.replace("http://", "http://test-user:test-pass@") +
                "/session/stale"
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                evaluateWebView(scenario, "window.setTimeout(function(){window.__notificationCheck=true;}, 600);")
                scenario.onActivity { activity ->
                    val original = activity.intent
                    instrumentation.callActivityOnNewIntent(
                        activity,
                        Intent(HermesNotificationPresenter.ACTION_OPEN_NOTIFICATION_URL)
                            .putExtra(HermesNotificationPresenter.EXTRA_NOTIFICATION_URL, staleRoute)
                    )
                    activity.intent = original
                }
                eventually("Notification check completed or stale route was requested") {
                    server.requests.any { it.path == "/session/stale" } ||
                        evaluateWebView(scenario, "window.__notificationCheck === true") == "true"
                }
                assertThat(server.requests.none { it.path == "/session/stale" }).isTrue()
                assertThat(evaluateWebView(scenario, "location.href"))
                    .isEqualTo("\"${server.baseUrl}/\"")
            }
        }
    }

    @Test
    fun editingCurrentCredentials_refreshesDocumentStartRouteRecovery() {
        withConfiguredServer(basicAuth = true) { server, repository ->
            val credentialed = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            val oldRoute = "$credentialed/session/old"
            repository.saveAppUrls(credentialed, "")
            repository.addProfile("Primary", credentialed)
            repository.saveLastLoadedUrl(oldRoute)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes session old" }
                evaluateWebView(scenario, "sessionStorage.setItem('test-draft', 'keep');")
                sendDeepLink(scenario, "hermes://app/settings")
                compose.onNodeWithText("Primary").performTouchInput { longClick() }
                compose.onNodeWithText("Server URL").performTextReplacement(server.baseUrl)
                compose.onNodeWithText("Save").performClick()
                eventually("Current server URL updated") {
                    repository.getSettings(server.baseUrl, "").serverUrl == server.baseUrl
                }
                awaitWebView(scenario) {
                    it.url == "${server.baseUrl}/" && it.title == "Hermes HTTP authenticated"
                }
                assertThat(evaluateWebView(scenario, "sessionStorage.getItem('test-draft')")).isEqualTo("\"keep\"")
                assertWorkspaceRecoveryDoesNotNavigateToOldRoute(scenario, server)
            }
        }
    }

    @Test
    fun editingCurrentCredentials_backDoesNotRestoreTheOldCredentialedHistoryEntry() {
        withConfiguredServer(basicAuth = true) { server, repository ->
            val credentialed = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            repository.saveAppUrls(credentialed, "")
            repository.addProfile("Primary", credentialed)
            repository.saveLastLoadedUrl("$credentialed/session/old")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes session old" }
                sendDeepLink(scenario, "hermes://app/settings")
                compose.onNodeWithText("Primary").performTouchInput { longClick() }
                compose.onNodeWithText("Server URL").performTextReplacement(server.baseUrl)
                compose.onNodeWithText("Save").performClick()
                awaitWebView(scenario) {
                    val history = it.copyBackForwardList()
                    it.url == "${server.baseUrl}/" && it.title == "Hermes HTTP authenticated" &&
                        (0 until history.size).none { index ->
                            history.getItemAtIndex(index)?.url == "$credentialed/session/old"
                        }
                }
                val oldRequests = server.requests.count { it.path == "/session/old" }
                scenario.onActivity { activity ->
                    val webView = checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                    val history = webView.copyBackForwardList()
                    assertThat((0 until history.size).map { history.getItemAtIndex(it)?.url })
                        .doesNotContain("$credentialed/session/old")
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Primary").fetchSemanticsNodes().isEmpty()
                }
                evaluateWebView(scenario, "window.setTimeout(function(){window.__backCheck=true;}, 600);")
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                var restoredUrl: String? = null
                eventually("Back handling completed or stale route was requested") {
                    scenario.onActivity { activity ->
                        restoredUrl = findWebView(activity.findViewById(android.R.id.content))?.url
                    }
                    restoredUrl?.contains("/session/old") == true ||
                        evaluateWebView(scenario, "window.__backCheck === true") == "true"
                }
                assertThat(server.requests.count { it.path == "/session/old" }).isEqualTo(oldRequests)
                assertThat(restoredUrl).isEqualTo("${server.baseUrl}/")
            }
        }
    }

    @Test
    fun editingCurrentCredentials_pageHistoryBackDoesNotRestoreTheOldRoute() {
        withConfiguredServer(basicAuth = true) { server, repository ->
            val credentialed = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            repository.saveAppUrls(credentialed, "")
            repository.addProfile("Primary", credentialed)
            repository.saveLastLoadedUrl("$credentialed/session/old")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes session old" }
                sendDeepLink(scenario, "hermes://app/settings")
                compose.onNodeWithText("Primary").performTouchInput { longClick() }
                compose.onNodeWithText("Server URL").performTextReplacement(server.baseUrl)
                compose.onNodeWithText("Save").performClick()
                awaitWebView(scenario) {
                    it.url == "${server.baseUrl}/" && it.title == "Hermes HTTP authenticated"
                }
                val oldRequests = server.requests.count { it.path == "/session/old" }
                evaluateWebView(
                    scenario,
                    "window.setTimeout(function(){window.__historyCheck=true;}, 600); window.history.back();",
                    requireCallback = false
                )
                var restoredUrl: String? = null
                eventually("Page history traversal completed or old route was restored") {
                    scenario.onActivity { activity ->
                        restoredUrl = findWebView(activity.findViewById(android.R.id.content))?.url
                    }
                    restoredUrl?.contains("/session/old") == true ||
                        evaluateWebView(scenario, "window.__historyCheck === true", false) == "true"
                }
                assertThat(restoredUrl).isEqualTo("${server.baseUrl}/")
                assertThat(server.requests.count { it.path == "/session/old" }).isEqualTo(oldRequests)
            }
        }
    }

    @Test
    fun editingCurrentCredentials_delayedReplacementCannotUseOldRecoveryClosure() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(basicAuth = true, publicStatus = true) { server, repository ->
            val credentialed = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            repository.saveAppUrls(credentialed, "")
            repository.saveLastLoadedUrl("$credentialed/session/old")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes session old" }
                scenario.onActivity { activity ->
                    checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                        .loadUrl("${server.baseUrl}/session/current")
                }
                awaitWebView(scenario) {
                    it.url == "${server.baseUrl}/session/current" && it.title == "Hermes session current"
                }
                scenario.onActivity { activity ->
                    checkNotNull(findWebView(activity.findViewById(android.R.id.content))).clearHistory()
                }
                val oldRequests = server.requests.count { it.path == "/session/old" }
                val permissionRequested = CountDownLatch(1)
                scenario.onActivity { activity ->
                    delayLocalNetworkPermission(activity) { permissionRequested.countDown() }
                    MainActivity::class.java.getDeclaredMethod("saveSettings", String::class.java).apply {
                        isAccessible = true
                        invoke(activity, server.baseUrl)
                    }
                }
                eventually("Current server URL updated") {
                    repository.getSettings(server.baseUrl, "").serverUrl == server.baseUrl
                }
                assertThat(permissionRequested.await(10, TimeUnit.SECONDS)).isTrue()
                fun blockedNavigations(): Int = DiagnosticsLogger.recentText(context)
                    .lineSequence().count { it.contains("stale_credential_navigation_blocked") }
                val blockedBefore = blockedNavigations()
                evaluateWebView(scenario, """
                    (function() {
                      var button = document.createElement('button');
                      button.id = 'btnWorkspacePanelToggle';
                      document.body.appendChild(button);
                      window.setTimeout(function() { window.__recoveryCheck = true; }, 600);
                      button.click();
                    })();
                """.trimIndent(), requireCallback = false)
                eventually("Workspace recovery completed or the old route was requested") {
                    server.requests.count { it.path == "/session/old" } > oldRequests ||
                        evaluateWebView(scenario, "window.__recoveryCheck === true", false) == "true"
                }
                assertThat(server.requests.count { it.path == "/session/old" }).isEqualTo(oldRequests)
                assertThat(evaluateWebView(scenario, "location.href"))
                    .isEqualTo("\"${server.baseUrl}/session/current\"")
                assertThat(blockedNavigations()).isEqualTo(blockedBefore)

                evaluateWebView(
                    scenario,
                    "window.location.href = ${JSONObject.quote("$credentialed/session/old")};",
                    requireCallback = false
                )
                eventually("Stale credential-bearing page navigation was rejected") {
                    blockedNavigations() > blockedBefore ||
                        server.requests.count { it.path == "/session/old" } > oldRequests
                }
                assertThat(blockedNavigations()).isGreaterThan(blockedBefore)
                assertThat(server.requests.count { it.path == "/session/old" }).isEqualTo(oldRequests)
            }
        }
    }

    @Test
    fun switchingProfilesCancelsPendingVpnWorkBeforeChangingTheSelectedServer() {
        withConfiguredServer(officialWebapp = true) { server, repository ->
            repository.addProfile("Primary", server.baseUrl)
            val next = repository.addProfile("Next", "${server.baseUrl}/other")
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                val pending = Job()
                val configuredWhenCancelled = AtomicReference<String?>()
                pending.invokeOnCompletion {
                    configuredWhenCancelled.set(repository.getSettings(server.baseUrl, "").serverUrl)
                }
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredField("pendingVpnGuardUrl").apply {
                        isAccessible = true
                        set(activity, "http://old:secret@100.64.0.1")
                    }
                    MainActivity::class.java.getDeclaredField("vpnReconnectWaitJob").apply {
                        isAccessible = true
                        set(activity, pending)
                    }
                    MainActivity::class.java.getDeclaredMethod(
                        "performServerProfileSwitch", com.hermeswebui.android.data.ServerProfile::class.java
                    ).apply {
                        isAccessible = true
                        invoke(activity, next)
                    }
                }
                assertThat(pending.isCancelled).isTrue()
                assertThat(configuredWhenCancelled.get()).isEqualTo(server.baseUrl)
                assertThat(repository.getSettings(server.baseUrl, "").serverUrl).isEqualTo(next.url)
            }
        }
    }

    @Test
    fun switchingProfilesRejectsAnOldLocalNetworkPermissionCompletion() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(officialWebapp = true) { server, repository ->
            repository.addProfile("Primary", server.baseUrl)
            val next = repository.addProfile("Next", "${server.baseUrl}/other")
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                val oldActionCalled = AtomicBoolean(false)
                scenario.onActivity { activity ->
                    delayLocalNetworkPermission(activity) {}
                    val action: () -> Unit = { oldActionCalled.set(true) }
                    MainActivity::class.java.getDeclaredMethod(
                        "requestLocalNetworkPermissionIfNeeded",
                        String::class.java,
                        Function0::class.java,
                        Function0::class.java,
                        Boolean::class.javaPrimitiveType
                    ).apply {
                        isAccessible = true
                        invoke(activity, server.baseUrl, action, action, false)
                    }
                    val callback = MainActivity::class.java.getDeclaredField("pendingLocalNetworkPermissionAction")
                        .apply { isAccessible = true }
                        .get(activity) as? Function0<*>
                    checkNotNull(callback) { "The fixture requires a pending permission request" }
                    MainActivity::class.java.getDeclaredMethod(
                        "performServerProfileSwitch", com.hermeswebui.android.data.ServerProfile::class.java
                    ).apply {
                        isAccessible = true
                        invoke(activity, next)
                    }
                    callback.invoke()
                }
                assertThat(oldActionCalled.get()).isFalse()
                assertThat(repository.getSettings(server.baseUrl, "").serverUrl).isEqualTo(next.url)
            }
        }
    }

    @Test
    fun switchingProfilesIgnoresAnOldLocalNetworkErrorAfterTheSelectionChanges() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(basicAuth = true, publicStatus = true) { server, repository ->
            val oldUrl = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            repository.saveAppUrls(oldUrl, "")
            repository.saveLastLoadedUrl("$oldUrl/session/old")
            repository.addProfile("Primary", oldUrl)
            val next = repository.addProfile("Next", server.baseUrl.replace("127.0.0.1", "localhost"))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes session old" }
                val launches = AtomicInteger()
                scenario.onActivity { activity ->
                    delayLocalNetworkPermission(activity) { launches.incrementAndGet() }
                    MainActivity::class.java.getDeclaredMethod(
                        "performServerProfileSwitch", com.hermeswebui.android.data.ServerProfile::class.java
                    ).apply {
                        isAccessible = true
                        invoke(activity, next)
                    }
                    assertThat(launches.get()).isEqualTo(1)
                    val pendingField = MainActivity::class.java.getDeclaredField("pendingLocalNetworkPermissionAction")
                        .apply { isAccessible = true }
                    val newServerCallback = pendingField.get(activity)
                    val webView = checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                    webView.webViewClient.onPageStarted(webView, "$oldUrl/session/old", null)
                    MainActivity::class.java.getDeclaredMethod(
                        "recoverFromLocalNetworkPermissionError",
                        String::class.java, CharSequence::class.java, String::class.java
                    ).apply {
                        isAccessible = true
                        invoke(activity, "$oldUrl/session/old", "net::ERR_LOCAL_NETWORK_PERMISSION_MISSING", "GET")
                        assertThat(launches.get()).isEqualTo(1)
                        assertThat(pendingField.get(activity)).isSameInstanceAs(newServerCallback)
                        invoke(activity, next.url, "net::ERR_LOCAL_NETWORK_PERMISSION_MISSING", "GET")
                    }
                    assertThat(launches.get()).isEqualTo(2)
                    assertThat(pendingField.get(activity)).isNotSameInstanceAs(newServerCallback)
                }
                assertThat(repository.getSettings(server.baseUrl, "").serverUrl).isEqualTo(next.url)
            }
        }
    }

    @Test
    fun oldHttpPermissionErrorDoesNotAutomaticallyDowngradeAnHttpsSelection() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(basicAuth = true, publicStatus = true) { server, repository ->
            val oldUrl = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            repository.saveAppUrls(oldUrl, "")
            repository.saveLastLoadedUrl("$oldUrl/session/old")
            repository.addProfile("Primary", oldUrl)
            val next = repository.addProfile("HTTPS", oldUrl.replace("http://", "https://"))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes session old" }
                val launches = AtomicInteger()
                scenario.onActivity { activity ->
                    delayLocalNetworkPermission(activity) { launches.incrementAndGet() }
                    MainActivity::class.java.getDeclaredMethod(
                        "performServerProfileSwitch", com.hermeswebui.android.data.ServerProfile::class.java
                    ).apply {
                        isAccessible = true
                        invoke(activity, next)
                    }
                    assertThat(launches.get()).isEqualTo(1)
                    val pendingField = MainActivity::class.java.getDeclaredField("pendingLocalNetworkPermissionAction")
                        .apply { isAccessible = true }
                    val newServerCallback = pendingField.get(activity)
                    val recovery = MainActivity::class.java.getDeclaredMethod(
                        "recoverFromLocalNetworkPermissionError",
                        String::class.java, CharSequence::class.java, String::class.java
                    ).apply { isAccessible = true }
                    assertThat(
                        recovery.invoke(activity, "$oldUrl/session/old", "net::ERR_LOCAL_NETWORK_PERMISSION_MISSING", "GET")
                    ).isEqualTo(false)
                    assertThat(launches.get()).isEqualTo(1)
                    assertThat(pendingField.get(activity)).isSameInstanceAs(newServerCallback)
                    recovery.invoke(activity, next.url, "net::ERR_LOCAL_NETWORK_PERMISSION_MISSING", "GET")
                    assertThat(launches.get()).isEqualTo(2)
                }
            }
        }
    }

    @Test
    fun authorizedOAuthProviderOnPreviousHttpOriginCanRecoverFromPermissionError() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(basicAuth = true, publicStatus = true) { server, repository ->
            val oldUrl = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            repository.saveAppUrls(oldUrl, "")
            repository.saveLastLoadedUrl("$oldUrl/session/old")
            repository.addProfile("Primary", oldUrl)
            val next = repository.addProfile("HTTPS", oldUrl.replace("http://", "https://"))
            repository.setBrowserSignInEnabled(false)
            val providerUrl = "$oldUrl/authorize?response_type=code&client_id=fixture" +
                "&redirect_uri=${Uri.encode("${next.url}/callback")}"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes session old" }
                val launches = AtomicInteger()
                scenario.onActivity { activity ->
                    delayLocalNetworkPermission(activity) { launches.incrementAndGet() }
                    MainActivity::class.java.getDeclaredMethod(
                        "performServerProfileSwitch", com.hermeswebui.android.data.ServerProfile::class.java
                    ).apply {
                        isAccessible = true
                        invoke(activity, next)
                    }
                    assertThat(launches.get()).isEqualTo(1)
                    val webView = checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                    assertThat(webView.webViewClient.shouldOverrideUrlLoading(webView, mainFrameRequest(providerUrl))).isFalse()
                    val recovery = MainActivity::class.java.getDeclaredMethod(
                        "recoverFromLocalNetworkPermissionError",
                        String::class.java, CharSequence::class.java, String::class.java
                    ).apply { isAccessible = true }
                    val error = "net::ERR_LOCAL_NETWORK_PERMISSION_MISSING"
                    assertThat(recovery.invoke(activity, providerUrl, error, "GET")).isEqualTo(true)
                    assertThat(launches.get()).isEqualTo(2)
                    val pendingField = MainActivity::class.java.getDeclaredField("pendingLocalNetworkPermissionAction")
                        .apply { isAccessible = true }
                    val providerCallback = checkNotNull(pendingField.get(activity) as? Function0<*>)
                    webView.webViewClient.onPageStarted(webView, "$oldUrl/session/old", null)
                    assertThat(recovery.invoke(activity, "$oldUrl/session/old", error, "GET")).isEqualTo(false)
                    assertThat(pendingField.get(activity)).isSameInstanceAs(providerCallback)
                    assertThat(launches.get()).isEqualTo(2)
                    val timeout = MainActivity::class.java.getDeclaredField("oauthFlowTimeoutMs")
                        .apply { isAccessible = true }
                    val navigationGeneration = MainActivity::class.java.getDeclaredField("mainFrameNavigationGeneration")
                        .apply { isAccessible = true }
                    val originalTimeout = timeout.getLong(activity)
                    timeout.setLong(activity, 0)
                    assertThat(recovery.invoke(activity, providerUrl, error, "GET")).isEqualTo(false)
                    assertThat(pendingField.get(activity)).isSameInstanceAs(providerCallback)
                    val beforeExpiredGrant = navigationGeneration.getLong(activity)
                    providerCallback.invoke()
                    assertThat(navigationGeneration.getLong(activity)).isEqualTo(beforeExpiredGrant)
                    webView.webViewClient.onPageStarted(webView, providerUrl, null)
                    val afterLatePageStart = navigationGeneration.getLong(activity)
                    providerCallback.invoke()
                    assertThat(navigationGeneration.getLong(activity)).isEqualTo(afterLatePageStart)
                    timeout.setLong(activity, originalTimeout)
                    assertThat(webView.webViewClient.shouldOverrideUrlLoading(
                        webView, mainFrameRequest("$oldUrl/session/provider-next")
                    )).isFalse()
                    assertThat(webView.webViewClient.shouldOverrideUrlLoading(webView, mainFrameRequest(providerUrl))).isFalse()
                    val beforeReplacedGrant = navigationGeneration.getLong(activity)
                    providerCallback.invoke()
                    assertThat(navigationGeneration.getLong(activity)).isEqualTo(beforeReplacedGrant)
                    assertThat(recovery.invoke(activity, providerUrl, error, "GET")).isEqualTo(true)
                    val currentCallback = checkNotNull(pendingField.get(activity) as? Function0<*>)
                    currentCallback.invoke()
                    assertThat(navigationGeneration.getLong(activity)).isGreaterThan(beforeReplacedGrant)
                    MainActivity::class.java.getDeclaredMethod("clearActiveMainFrameOAuth").apply {
                        isAccessible = true
                        invoke(activity)
                    }
                    assertThat(recovery.invoke(activity, providerUrl, error, "GET")).isEqualTo(false)
                    assertThat(pendingField.get(activity)).isSameInstanceAs(currentCallback)
                }
            }
        }
    }

    @Test
    fun editingCurrentServerUrlEndsThePreviousOAuthFlow() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(basicAuth = true, publicStatus = true) { server, repository ->
            val oldUrl = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            repository.saveAppUrls(oldUrl, "")
            repository.saveLastLoadedUrl("$oldUrl/session/old")
            repository.addProfile("Primary", oldUrl)
            val next = repository.addProfile("HTTPS", oldUrl.replace("http://", "https://"))
            repository.setBrowserSignInEnabled(false)
            val providerUrl = "$oldUrl/authorize?response_type=code&client_id=fixture" +
                "&redirect_uri=${Uri.encode("${next.url}/callback")}"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes session old" }
                scenario.onActivity { activity ->
                    delayLocalNetworkPermission(activity) {}
                    MainActivity::class.java.getDeclaredMethod(
                        "performServerProfileSwitch", com.hermeswebui.android.data.ServerProfile::class.java
                    ).apply {
                        isAccessible = true
                        invoke(activity, next)
                    }
                    val webView = checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                    assertThat(webView.webViewClient.shouldOverrideUrlLoading(webView, mainFrameRequest(providerUrl))).isFalse()
                    val activeFlow = MainActivity::class.java.getDeclaredField("activeMainFrameOAuthFlow")
                        .apply { isAccessible = true }
                    assertThat(activeFlow.get(activity)).isNotNull()
                    val coordinator = MainActivity::class.java.getDeclaredField("serverProfileCoordinator")
                        .apply { isAccessible = true }.get(activity)
                    coordinator.javaClass.getDeclaredMethod(
                        "updateProfileAndFollowCurrent", String::class.java, String::class.java,
                        String::class.java, ServerType::class.java
                    ).apply {
                        isAccessible = true
                        invoke(coordinator, next.id, next.name, "${next.url}/edited", ServerType.UNSPECIFIED)
                    }
                    assertThat(activeFlow.get(activity)).isNull()
                }
            }
        }
    }

    @Test
    fun replacedOrExpiredOAuthRecoveryCancelsQueuedVpnRetry() {
        withConfiguredServer(officialWebapp = true) { server, _ ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                val probes = AtomicInteger()
                scenario.onActivity { activity ->
                    val viewModel = MainActivity::class.java.getDeclaredField("viewModel")
                        .apply { isAccessible = true }.get(activity) as MainViewModel
                    viewModel.setRequireVpnForTailscaleEnabled(true)
                    val webView = checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                    val provider = "http://100.64.0.1/authorize?response_type=code&client_id=fixture" +
                        "&redirect_uri=${Uri.encode("${server.baseUrl}/callback")}"
                    assertThat(webView.webViewClient.shouldOverrideUrlLoading(webView, mainFrameRequest(provider))).isFalse()
                    val generation = MainActivity::class.java.getDeclaredField("oauthRecoveryNavigationGeneration")
                        .apply { isAccessible = true }
                    val pendingUrl = MainActivity::class.java.getDeclaredField("pendingVpnGuardUrl")
                        .apply { isAccessible = true }
                    val pendingGeneration = MainActivity::class.java.getDeclaredField("pendingVpnOAuthRecoveryGeneration")
                        .apply { isAccessible = true }
                    fun queueCurrentNavigation() {
                        pendingUrl.set(activity, provider)
                        pendingGeneration.set(activity, generation.getLong(activity))
                    }
                    queueCurrentNavigation()
                    assertThat(webView.webViewClient.shouldOverrideUrlLoading(webView, mainFrameRequest(provider))).isFalse()
                    assertThat(pendingUrl.get(activity)).isNull()
                    queueCurrentNavigation()
                    MainActivity::class.java.getDeclaredField("oauthFlowTimeoutMs").apply {
                        isAccessible = true
                        setLong(activity, 0)
                    }
                    MainActivity::class.java.getDeclaredMethod("startVpnReconnectWaitLoopIfNeeded").apply {
                        isAccessible = true
                        invoke(activity)
                    }
                    assertThat(pendingUrl.get(activity)).isNull()
                    assertThat(pendingGeneration.get(activity)).isNull()
                    assertThat(webView.webViewClient.shouldOverrideUrlLoading(webView, mainFrameRequest(provider))).isFalse()
                    queueCurrentNavigation()
                    val probe: suspend (String) -> Boolean = {
                        probes.incrementAndGet()
                        false
                    }
                    MainActivity::class.java.getDeclaredField("vpnReconnectProbe").apply {
                        isAccessible = true
                        set(activity, probe)
                    }
                    MainActivity::class.java.getDeclaredMethod("startVpnReconnectWaitLoopIfNeeded").apply {
                        isAccessible = true
                        invoke(activity)
                    }
                    assertThat(probes.get()).isEqualTo(1)
                    MainActivity::class.java.getDeclaredField("oauthFlowTimeoutMs").apply {
                        isAccessible = true
                        setLong(activity, 0)
                    }
                }
                eventually("Expired OAuth retry is canceled before the next VPN probe") {
                    var pending: Any? = null
                    scenario.onActivity { activity ->
                        pending = MainActivity::class.java.getDeclaredField("pendingVpnGuardUrl")
                            .apply { isAccessible = true }.get(activity)
                    }
                    pending == null
                }
                assertThat(probes.get()).isEqualTo(1)
            }
        }
    }

    @Test
    fun oauthExpiryDuringVpnProbeDoesNotResumeOrDismissSettings() {
        withConfiguredServer(officialWebapp = true) { server, _ ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                eventually("WebView document finished before the VPN probe") {
                    evaluateWebView(scenario, "document.readyState === 'complete'") == "true"
                }
                val probeStarted = CountDownLatch(1)
                val finishProbe = CountDownLatch(1)
                var beforeProbe = 0L
                try {
                    scenario.onActivity { activity ->
                        val viewModel = MainActivity::class.java.getDeclaredField("viewModel")
                            .apply { isAccessible = true }.get(activity) as MainViewModel
                        viewModel.setRequireVpnForTailscaleEnabled(true)
                        viewModel.openSettings()
                        val webView = checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                        val provider = "http://100.64.0.1/authorize?response_type=code&client_id=fixture" +
                            "&redirect_uri=${Uri.encode("${server.baseUrl}/callback")}"
                        assertThat(webView.webViewClient.shouldOverrideUrlLoading(webView, mainFrameRequest(provider))).isFalse()
                        val generation = MainActivity::class.java.getDeclaredField("oauthRecoveryNavigationGeneration")
                            .apply { isAccessible = true }.getLong(activity)
                        MainActivity::class.java.getDeclaredField("pendingVpnGuardUrl").apply {
                            isAccessible = true
                            set(activity, provider)
                        }
                        MainActivity::class.java.getDeclaredField("pendingVpnOAuthRecoveryGeneration").apply {
                            isAccessible = true
                            set(activity, generation)
                        }
                        val probe: suspend (String) -> Boolean = {
                            probeStarted.countDown()
                            withContext(Dispatchers.IO) {
                                check(finishProbe.await(10, TimeUnit.SECONDS)) { "VPN probe was not released" }
                            }
                            true
                        }
                        MainActivity::class.java.getDeclaredField("vpnReconnectProbe").apply {
                            isAccessible = true
                            set(activity, probe)
                        }
                        beforeProbe = MainActivity::class.java.getDeclaredField("mainFrameNavigationGeneration")
                            .apply { isAccessible = true }.getLong(activity)
                        MainActivity::class.java.getDeclaredMethod("startVpnReconnectWaitLoopIfNeeded").apply {
                            isAccessible = true
                            invoke(activity)
                        }
                    }
                    assertThat(probeStarted.await(10, TimeUnit.SECONDS)).isTrue()
                    scenario.onActivity { activity ->
                        MainActivity::class.java.getDeclaredField("oauthFlowTimeoutMs").apply {
                            isAccessible = true
                            setLong(activity, 0)
                        }
                    }
                    finishProbe.countDown()
                    eventually("Expired in-flight OAuth retry is canceled") {
                        var pending: Any? = null
                        scenario.onActivity { activity ->
                            pending = MainActivity::class.java.getDeclaredField("pendingVpnGuardUrl")
                                .apply { isAccessible = true }.get(activity)
                        }
                        pending == null
                    }
                    scenario.onActivity { activity ->
                        val viewModel = MainActivity::class.java.getDeclaredField("viewModel")
                            .apply { isAccessible = true }.get(activity) as MainViewModel
                        val afterProbe = MainActivity::class.java.getDeclaredField("mainFrameNavigationGeneration")
                            .apply { isAccessible = true }.getLong(activity)
                        assertThat(viewModel.uiState.value.isSettingsVisible).isTrue()
                        assertThat(afterProbe).isEqualTo(beforeProbe)
                    }
                } finally {
                    finishProbe.countDown()
                }
            }
        }
    }

    @Test
    fun localNetworkPermissionErrorPromptsForNonObviousGetHost() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(officialWebapp = true) { server, _ ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                scenario.onActivity { activity ->
                    val launches = AtomicInteger()
                    delayLocalNetworkPermission(activity) { launches.incrementAndGet() }
                    val recovery = MainActivity::class.java.getDeclaredMethod(
                        "recoverFromLocalNetworkPermissionError",
                        String::class.java, CharSequence::class.java, String::class.java
                    ).apply { isAccessible = true }
                    assertThat(recovery.invoke(
                        activity, "http://100.64.0.1/login", "net::ERR_LOCAL_NETWORK_PERMISSION_MISSING", "GET"
                    )).isEqualTo(true)
                    assertThat(launches.get()).isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun localNetworkPermissionErrorDoesNotReplayPostAsGet() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(officialWebapp = true) { server, _ ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                scenario.onActivity { activity ->
                    val launches = AtomicInteger()
                    delayLocalNetworkPermission(activity) { launches.incrementAndGet() }
                    val recovery = MainActivity::class.java.getDeclaredMethod(
                        "recoverFromLocalNetworkPermissionError",
                        String::class.java, CharSequence::class.java, String::class.java
                    ).apply {
                        isAccessible = true
                    }
                    val navigationGeneration = MainActivity::class.java.getDeclaredField("mainFrameNavigationGeneration")
                        .apply { isAccessible = true }
                    val beforeGrant = navigationGeneration.getLong(activity)
                    assertThat(recovery.invoke(
                        activity, "http://100.64.0.1/submit", "net::ERR_LOCAL_NETWORK_PERMISSION_MISSING", "POST"
                    )).isEqualTo(false)
                    assertThat(launches.get()).isEqualTo(1)
                    val callback = MainActivity::class.java.getDeclaredField("pendingLocalNetworkPermissionAction")
                        .apply { isAccessible = true }.get(activity) as Function0<*>
                    callback.invoke()
                    assertThat(navigationGeneration.getLong(activity)).isEqualTo(beforeGrant)
                }
            }
        }
    }

    @Test
    fun postPermissionErrorDoesNotAutoReloadAsGetOrResumeRetry() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(officialWebapp = true, publicStatus = true) { server, _ ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                eventually("WebView document finished before the POST error") {
                    evaluateWebView(scenario, "document.readyState === 'complete'") == "true"
                }
                val failedUrl = "${server.baseUrl}/session/failed-post"
                val beforeRequests = server.requests.count { it.path == "/session/failed-post" }
                val beforeRootRequests = server.requests.count { it.path == "/" }
                scenario.onActivity { activity ->
                    val launches = AtomicInteger()
                    delayLocalNetworkPermission(activity) { launches.incrementAndGet() }
                    val webView = checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                    webView.webViewClient.onPageStarted(webView, failedUrl, null)
                    val viewModel = MainActivity::class.java.getDeclaredField("viewModel")
                        .apply { isAccessible = true }.get(activity) as MainViewModel
                    viewModel.setReconnectPollIntervalSeconds(1)
                    MainActivity::class.java.getDeclaredMethod(
                        "handleMainFrameWebViewError",
                        String::class.java, String::class.java, CharSequence::class.java, Int::class.javaObjectType
                    ).apply {
                        isAccessible = true
                        invoke(activity, failedUrl, "POST", "net::ERR_LOCAL_NETWORK_PERMISSION_MISSING", WebViewClient.ERROR_UNKNOWN)
                    }
                    assertThat(launches.get()).isEqualTo(1)
                    assertThat(viewModel.uiState.value.errorMessage).contains("retry your action")
                    assertThat(viewModel.uiState.value.isReconnecting).isFalse()
                    viewModel.resumeAutoRetryIfNeeded()
                    assertThat(viewModel.uiState.value.isReconnecting).isFalse()
                }
                SystemClock.sleep(1_500)
                assertThat(server.requests.count { it.path == "/session/failed-post" }).isEqualTo(beforeRequests)
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty()
                }
                val externalUrls = CopyOnWriteArrayList<String>()
                val browser = object : Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                        if (intent.action != Intent.ACTION_VIEW) return null
                        externalUrls.add(intent.dataString.orEmpty())
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                }
                instrumentation.addMonitor(browser)
                try {
                    compose.onNodeWithText("Open in browser").performClick()
                    eventually("External recovery opens the configured server", 10_000) { externalUrls.isNotEmpty() }
                    assertThat(externalUrls.single()).isEqualTo(server.baseUrl)
                } finally {
                    instrumentation.removeMonitor(browser)
                }
                compose.onNodeWithText("Retry").performClick()
                eventually("Manual retry requests a page", 10_000) {
                    server.requests.count { it.path == "/" } > beforeRootRequests ||
                        server.requests.count { it.path == "/session/failed-post" } > beforeRequests
                }
                assertThat(server.requests.count { it.path == "/session/failed-post" }).isEqualTo(beforeRequests)
                assertThat(server.requests.count { it.path == "/" }).isGreaterThan(beforeRootRequests)
            }
        }
    }

    @Test
    fun queuedAutoReloadDoesNotReplayPostOrAReplacementNavigation() {
        withConfiguredServer(officialWebapp = true, publicStatus = true) { server, _ ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                val failedUrl = "${server.baseUrl}/session/failed-post"
                val beforeRequests = server.requests.count { it.path == "/session/failed-post" }
                lateinit var viewModel: MainViewModel
                lateinit var autoReloads: MutableSharedFlow<Long>
                var previousGeneration = 0L
                scenario.onActivity { activity ->
                    viewModel = MainActivity::class.java.getDeclaredField("viewModel")
                        .apply { isAccessible = true }.get(activity) as MainViewModel
                    @Suppress("UNCHECKED_CAST")
                    autoReloads = MainViewModel::class.java.getDeclaredField("_autoReloadEvent")
                        .apply { isAccessible = true }.get(viewModel) as MutableSharedFlow<Long>
                }
                compose.waitUntil(10_000) { autoReloads.subscriptionCount.value > 0 }
                scenario.onActivity {
                    viewModel.onPageStarted(failedUrl)
                    viewModel.onPageError("Connection failed", isOffline = false)
                    previousGeneration = MainViewModel::class.java.getDeclaredField("autoRetryGeneration")
                        .apply { isAccessible = true }.getLong(viewModel)
                    assertThat(autoReloads.tryEmit(previousGeneration)).isTrue()
                    viewModel.onPageError("Retry your action", isOffline = false, retryAutomatically = false)
                }
                compose.waitForIdle()
                SystemClock.sleep(500)
                assertThat(server.requests.count { it.path == "/session/failed-post" }).isEqualTo(beforeRequests)
                val replacementUrl = "${server.baseUrl}/session/replacement"
                scenario.onActivity { activity ->
                    viewModel.onPageError("Connection failed", isOffline = false)
                    previousGeneration = MainViewModel::class.java.getDeclaredField("autoRetryGeneration")
                        .apply { isAccessible = true }.getLong(viewModel)
                    assertThat(autoReloads.tryEmit(previousGeneration)).isTrue()
                    MainActivity::class.java.getDeclaredMethod("loadInMainWebView", String::class.java).apply {
                        isAccessible = true
                        invoke(activity, replacementUrl)
                    }
                    assertThat(viewModel.isCurrentAutoReload(previousGeneration)).isFalse()
                }
                compose.waitForIdle()
                eventually("Replacement navigation was requested", 10_000) {
                    server.requests.any { it.path == "/session/replacement" }
                }
                assertThat(server.requests.count { it.path == "/session/failed-post" }).isEqualTo(beforeRequests)
                scenario.onActivity {
                    viewModel.onPageStarted(failedUrl)
                    assertThat(autoReloads.tryEmit(previousGeneration)).isTrue()
                }
                compose.waitForIdle()
                SystemClock.sleep(500)
                assertThat(server.requests.count { it.path == "/session/failed-post" }).isEqualTo(beforeRequests)
            }
        }
    }

    @Test
    fun queuedVpnNavigationInvalidatesThePreviousAutoReload() {
        withConfiguredServer(officialWebapp = true, publicStatus = true) { server, _ ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                val oldUrl = "${server.baseUrl}/session/failed-post"
                val beforeRequests = server.requests.count { it.path == "/session/failed-post" }
                lateinit var viewModel: MainViewModel
                lateinit var autoReloads: MutableSharedFlow<Long>
                scenario.onActivity { activity ->
                    viewModel = MainActivity::class.java.getDeclaredField("viewModel")
                        .apply { isAccessible = true }.get(activity) as MainViewModel
                    @Suppress("UNCHECKED_CAST")
                    autoReloads = MainViewModel::class.java.getDeclaredField("_autoReloadEvent")
                        .apply { isAccessible = true }.get(viewModel) as MutableSharedFlow<Long>
                }
                compose.waitUntil(10_000) { autoReloads.subscriptionCount.value > 0 }
                scenario.onActivity { activity ->
                    viewModel.setRequireVpnForTailscaleEnabled(true)
                    viewModel.onPageStarted(oldUrl)
                    viewModel.onPageError("Connection failed", isOffline = false)
                    val generation = MainViewModel::class.java.getDeclaredField("autoRetryGeneration")
                        .apply { isAccessible = true }.getLong(viewModel)
                    assertThat(autoReloads.tryEmit(generation)).isTrue()
                    val probe: suspend (String) -> Boolean = { false }
                    MainActivity::class.java.getDeclaredField("vpnReconnectProbe").apply {
                        isAccessible = true
                        set(activity, probe)
                    }
                    MainActivity::class.java.getDeclaredField("lastVpnLaunchAttemptElapsedMs").apply {
                        isAccessible = true
                        setLong(activity, SystemClock.elapsedRealtime())
                    }
                    val target = "http://100.64.0.1/session/replacement"
                    MainActivity::class.java.getDeclaredMethod(
                        "loadServerUrlWithVpnGuard", String::class.java, Long::class.javaObjectType
                    ).apply {
                        isAccessible = true
                        invoke(activity, target, null)
                    }
                    assertThat(MainActivity::class.java.getDeclaredField("pendingVpnGuardUrl").apply {
                        isAccessible = true
                    }.get(activity)).isEqualTo(target)
                    assertThat(viewModel.isCurrentAutoReload(generation)).isFalse()
                    viewModel.resumeAutoRetryIfNeeded()
                    assertThat(viewModel.uiState.value.isReconnecting).isFalse()
                }
                compose.waitForIdle()
                SystemClock.sleep(500)
                assertThat(server.requests.count { it.path == "/session/failed-post" }).isEqualTo(beforeRequests)
            }
        }
    }

    @Test
    fun oauthBackPermissionErrorDoesNotScheduleAnUnfencedRetry() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(officialWebapp = true) { server, repository ->
            repository.setBrowserSignInEnabled(false)
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                pushHistoryFromTouch(scenario, "#history-entry")
                scenario.onActivity { activity ->
                    val launches = AtomicInteger()
                    delayLocalNetworkPermission(activity) { launches.incrementAndGet() }
                    val webView = checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                    val provider = server.baseUrl.replace("127.0.0.1", "localhost") +
                        "/authorize?response_type=code&client_id=fixture" +
                        "&redirect_uri=${Uri.encode("${server.baseUrl}/callback")}"
                    assertThat(webView.webViewClient.shouldOverrideUrlLoading(webView, mainFrameRequest(provider))).isFalse()
                    MainActivity::class.java.getDeclaredMethod("goBackInMainWebView").apply {
                        isAccessible = true
                        assertThat(invoke(activity)).isEqualTo(true)
                    }
                    val recovery = MainActivity::class.java.getDeclaredMethod(
                        "recoverFromLocalNetworkPermissionError",
                        String::class.java, CharSequence::class.java, String::class.java
                    ).apply { isAccessible = true }
                    assertThat(recovery.invoke(
                        activity, provider, "net::ERR_LOCAL_NETWORK_PERMISSION_MISSING", "GET"
                    )).isEqualTo(false)
                    assertThat(launches.get()).isEqualTo(0)
                }
            }
        }
    }

    @Test
    fun editingCurrentServerHostIgnoresAnOldLocalNetworkError() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(basicAuth = true, publicStatus = true) { server, repository ->
            val oldUrl = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            val nextUrl = server.baseUrl.replace("127.0.0.1", "localhost")
            repository.saveAppUrls(oldUrl, "")
            repository.saveLastLoadedUrl("$oldUrl/session/old")
            val current = repository.addProfile("Primary", oldUrl)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes session old" }
                val launches = AtomicInteger()
                scenario.onActivity { activity ->
                    delayLocalNetworkPermission(activity) { launches.incrementAndGet() }
                    val coordinator = MainActivity::class.java.getDeclaredField("serverProfileCoordinator")
                        .apply { isAccessible = true }.get(activity)
                    coordinator.javaClass.getDeclaredMethod(
                        "updateProfileAndFollowCurrent",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        ServerType::class.java
                    ).apply {
                        isAccessible = true
                        invoke(coordinator, current.id, current.name, nextUrl, ServerType.UNSPECIFIED)
                    }
                    assertThat(launches.get()).isEqualTo(1)
                    MainActivity::class.java.getDeclaredMethod(
                        "recoverFromLocalNetworkPermissionError",
                        String::class.java, CharSequence::class.java, String::class.java
                    ).apply {
                        isAccessible = true
                        invoke(activity, "$oldUrl/session/old", "net::ERR_LOCAL_NETWORK_PERMISSION_MISSING", "GET")
                    }
                    assertThat(launches.get()).isEqualTo(1)
                }
                assertThat(repository.getSettings(nextUrl, "").serverUrl).isEqualTo(nextUrl)
            }
        }
    }

    @Test
    fun oldCredentialedErrorsDoNotReplaceNewServerButCurrentErrorsStillShow() {
        withConfiguredServer(basicAuth = true, publicStatus = true) { oldServer, repository ->
            ProxyServer(basicAuth = true, publicStatus = true, basicCredentials = "test-user:new-pass").use { newServer ->
                val oldUrl = oldServer.baseUrl.replace("http://", "http://test-user:test-pass@")
                val newUrl = newServer.baseUrl.replace("http://", "http://test-user:new-pass@")
                repository.saveAppUrls(oldUrl, "")
                repository.saveLastLoadedUrl(oldUrl)
                repository.addProfile("Primary", oldUrl)
                val next = repository.addProfile("Next", newUrl)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitWebView(scenario) { it.title == "Hermes HTTP authenticated" }
                    scenario.onActivity { activity ->
                        MainActivity::class.java.getDeclaredMethod(
                            "performServerProfileSwitch", com.hermeswebui.android.data.ServerProfile::class.java
                        ).apply {
                            isAccessible = true
                            invoke(activity, next)
                        }
                    }
                    awaitWebView(scenario) {
                        it.title == "Hermes HTTP authenticated" && it.url?.contains(":${Uri.parse(newUrl).port}") == true
                    }
                    oldServer.close()
                    val oldError = AtomicReference<Pair<WebResourceRequest, WebResourceError>>()
                    val errorReady = CountDownLatch(1)
                    val helper = AtomicReference<WebView>()
                    scenario.onActivity { activity ->
                        helper.set(WebView(activity).apply {
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    if (request?.isForMainFrame == true && error != null) {
                                        oldError.set(request to error)
                                        errorReady.countDown()
                                    }
                                }
                            }
                            loadUrl("$oldUrl/session/old")
                        })
                    }
                    try {
                        assertThat(errorReady.await(10, TimeUnit.SECONDS)).isTrue()
                        scenario.onActivity { activity ->
                            val webView = checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                            val viewModel = MainActivity::class.java.getDeclaredField("viewModel").apply {
                                isAccessible = true
                            }.get(activity) as MainViewModel
                            val (oldRequest, error) = checkNotNull(oldError.get())
                            assertThat(oldRequest.url.userInfo).isNotNull()
                            assertThat(viewModel.uiState.value.errorMessage).isNull()
                            assertThat(viewModel.uiState.value.isReconnecting).isFalse()
                            webView.webViewClient.onPageStarted(webView, "$oldUrl/session/old", null)
                            webView.webViewClient.onReceivedError(webView, oldRequest, error)
                            val oldNetworkRestarted = viewModel.uiState.value.isReconnecting
                            viewModel.onPageStarted(newUrl)
                            @Suppress("DEPRECATION")
                            val certificate = SslCertificate("CN=fixture", "CN=fixture", Date(0), Date(Long.MAX_VALUE))
                            val sslError = SslError(SslError.SSL_UNTRUSTED, certificate, oldUrl)
                            webView.webViewClient.onReceivedSslError(webView, null, sslError)
                            val oldSslRestarted = viewModel.uiState.value.isReconnecting
                            viewModel.onPageStarted(newUrl)
                            val currentSslError = SslError(SslError.SSL_UNTRUSTED, certificate, newUrl)
                            webView.webViewClient.onReceivedSslError(webView, null, currentSslError)
                            val currentSslRestarted = viewModel.uiState.value.isReconnecting
                            viewModel.onPageStarted(newUrl)
                            webView.webViewClient.onReceivedError(webView, mainFrameRequest(newUrl), error)
                            assertThat(listOf(oldNetworkRestarted, oldSslRestarted)).containsExactly(false, false).inOrder()
                            assertThat(currentSslRestarted).isTrue()
                            assertThat(viewModel.uiState.value.isReconnecting).isTrue()
                        }
                    } finally {
                        scenario.onActivity { helper.get()?.destroy() }
                    }
                }
            }
        }
    }

    @Test
    fun oldPageCallbacksDoNotReplaceTheSelectedServerAfterCredentialsChange() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(basicAuth = true, publicStatus = true) { server, repository ->
            val oldUrl = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            repository.saveAppUrls(oldUrl, "")
            repository.saveLastLoadedUrl("$oldUrl/session/old")
            repository.addProfile("Primary", oldUrl)
            val next = repository.addProfile("No URL credentials", server.baseUrl)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes session old" }
                scenario.onActivity { activity ->
                    delayLocalNetworkPermission(activity) {}
                    MainActivity::class.java.getDeclaredMethod(
                        "performServerProfileSwitch", com.hermeswebui.android.data.ServerProfile::class.java
                    ).apply {
                        isAccessible = true
                        invoke(activity, next)
                    }
                    val webView = checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                    val viewModel = MainActivity::class.java.getDeclaredField("viewModel").apply {
                        isAccessible = true
                    }.get(activity) as MainViewModel
                    val stale = "$oldUrl/session/old"
                    webView.webViewClient.onPageCommitVisible(webView, stale)
                    val afterCommit = viewModel.uiState.value.currentUrl
                    viewModel.onPageStarted(next.url)
                    webView.webViewClient.onPageFinished(webView, stale)
                    val afterFinish = viewModel.uiState.value.currentUrl
                    viewModel.onPageStarted(next.url)
                    webView.webViewClient.doUpdateVisitedHistory(webView, stale, false)
                    val afterHistory = viewModel.uiState.value.currentUrl
                    assertThat(listOf(afterCommit, afterFinish, afterHistory))
                        .containsExactly(next.url, next.url, next.url).inOrder()
                }
            }
        }
    }

    @Test
    fun currentPageCanPostToAPreviouslySelectedServerAsAProvider() {
        withConfiguredServer(officialWebapp = true, publicStatus = true) { oldServer, repository ->
            ProxyServer(basicAuth = true, publicStatus = true).use { newServer ->
                instrumentation.runOnMainSync {
                    CookieManager.getInstance().setCookie(oldServer.baseUrl, "proxy_auth=ok; Path=/")
                }
                repository.addProfile("Primary", oldServer.baseUrl)
                val newUrl = newServer.baseUrl.replace("http://", "http://test-user:test-pass@")
                val next = repository.addProfile("Next", newUrl)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitWebView(scenario) { it.title == "Hermes signed in" }
                    scenario.onActivity { activity ->
                        MainActivity::class.java.getDeclaredMethod(
                            "performServerProfileSwitch", com.hermeswebui.android.data.ServerProfile::class.java
                        ).apply {
                            isAccessible = true
                            invoke(activity, next)
                        }
                    }
                    awaitWebView(scenario) {
                        it.title == "Hermes HTTP authenticated" && it.url?.contains(":${Uri.parse(newUrl).port}") == true
                    }
                    instrumentation.runOnMainSync {
                        CookieManager.getInstance().setCookie(oldServer.baseUrl, "proxy_auth=ok; Path=/")
                    }
                    val provider = "${oldServer.baseUrl}/session/provider"
                    evaluateWebView(scenario, """
                        (function() {
                          var form = document.createElement('form');
                          form.method = 'POST';
                          form.action = ${JSONObject.quote(provider)};
                          document.body.appendChild(form);
                          form.submit();
                        })();
                    """.trimIndent(), requireCallback = false)
                    awaitWebView(scenario) { it.title == "Hermes session provider" }
                    scenario.onActivity { activity ->
                        val viewModel = MainActivity::class.java.getDeclaredField("viewModel").apply {
                            isAccessible = true
                        }.get(activity) as MainViewModel
                        assertThat(viewModel.uiState.value.currentUrl).isEqualTo(provider)
                        assertThat(viewModel.uiState.value.isLoading).isFalse()
                        if (context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") !=
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            val launches = AtomicInteger()
                            delayLocalNetworkPermission(activity) { launches.incrementAndGet() }
                            MainActivity::class.java.getDeclaredMethod(
                                "recoverFromLocalNetworkPermissionError",
                                String::class.java, CharSequence::class.java, String::class.java
                            ).apply {
                                isAccessible = true
                                invoke(activity, provider, "net::ERR_LOCAL_NETWORK_PERMISSION_MISSING", "GET")
                            }
                            assertThat(launches.get()).isEqualTo(1)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun popupHandoffDoesNotLoadAnOutdatedCredentialedUrl() {
        withConfiguredServer(officialWebapp = true) { server, _ ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                val staleUrl = server.baseUrl.replace("http://", "http://test-user:test-pass@") +
                    "/session/popup-stale"
                fun blockedNavigations(): Int = DiagnosticsLogger.recentText(context)
                    .lineSequence().count { it.contains("stale_credential_navigation_blocked") }
                val blockedBefore = blockedNavigations()
                scenario.onActivity { activity ->
                    MainActivity::class.java.getDeclaredMethod("handleNewWindowUrl", String::class.java).apply {
                        isAccessible = true
                        invoke(activity, staleUrl)
                    }
                }
                eventually("Popup handoff was blocked or the stale route was requested") {
                    blockedNavigations() > blockedBefore ||
                        server.requests.any { it.path == "/session/popup-stale" }
                }
                assertThat(blockedNavigations()).isGreaterThan(blockedBefore)
                assertThat(server.requests.none { it.path == "/session/popup-stale" }).isTrue()
            }
        }
    }

    @Test
    fun popupOAuthDoesNotStartForOutdatedCredentialedUrls() {
        withConfiguredServer(officialWebapp = true) { server, repository ->
            repository.setBrowserSignInEnabled(false)
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            val staleUrl = server.baseUrl.replace("http://", "http://test-user:test-pass@") +
                "/authorize?response_type=code&client_id=fixture&redirect_uri=${Uri.encode("${server.baseUrl}/callback")}"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                scenario.onActivity { activity ->
                    val activeFlow = MainActivity::class.java.getDeclaredField("activeMainFrameOAuthFlow")
                        .apply { isAccessible = true }
                    val popup = createPopup(activity)
                    assertThat(popup.webViewClient.shouldOverrideUrlLoading(popup, mainFrameRequest(staleUrl))).isTrue()
                    assertThat(activeFlow.get(activity)).isNull()
                    val startedPopup = createPopup(activity)
                    startedPopup.webViewClient.onPageStarted(startedPopup, staleUrl, null)
                    assertThat(activeFlow.get(activity)).isNull()
                }
            }
        }
    }

    @Test
    fun popupMainFramePostWithOutdatedCredentialsIsIntercepted() {
        withConfiguredServer(officialWebapp = true) { server, _ ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            val staleUrl = server.baseUrl.replace("http://", "http://test-user:test-pass@") + "/session/post"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes signed in" }
                scenario.onActivity { activity ->
                    val popup = createPopup(activity)
                    try {
                        val response = popup.webViewClient.shouldInterceptRequest(popup, mainFrameRequest(staleUrl, "POST"))
                        assertThat(response?.statusCode).isEqualTo(403)
                    } finally {
                        MainActivity::class.java.getDeclaredMethod("destroyPopup", WebView::class.java).apply {
                            isAccessible = true
                            invoke(activity, popup)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun oldDocumentCannotPostToAStaleCredentialedRouteAfterEditingTheServer() {
        assumeLocalNetworkPermissionIsPending()
        withConfiguredServer(basicAuth = true, publicStatus = true) { server, repository ->
            val credentialed = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            repository.saveAppUrls(credentialed, "")
            repository.saveLastLoadedUrl("$credentialed/session/old")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.title == "Hermes session old" }
                scenario.onActivity { activity ->
                    checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                        .loadUrl("${server.baseUrl}/session/current")
                }
                awaitWebView(scenario) { it.title == "Hermes session current" }
                val permissionRequested = CountDownLatch(1)
                scenario.onActivity { activity ->
                    checkNotNull(findWebView(activity.findViewById(android.R.id.content))).clearHistory()
                    delayLocalNetworkPermission(activity) { permissionRequested.countDown() }
                    MainActivity::class.java.getDeclaredMethod("saveSettings", String::class.java).apply {
                        isAccessible = true
                        invoke(activity, server.baseUrl)
                    }
                }
                eventually("Current server URL updated") {
                    repository.getSettings(server.baseUrl, "").serverUrl == server.baseUrl
                }
                assertThat(permissionRequested.await(10, TimeUnit.SECONDS)).isTrue()
                fun blockedNavigations(): Int = DiagnosticsLogger.recentText(context)
                    .lineSequence().count { it.contains("stale_credential_navigation_blocked") }
                val blockedBefore = blockedNavigations()
                evaluateWebView(scenario, """
                    (function() {
                      var form = document.createElement('form');
                      form.method = 'POST';
                      form.action = ${JSONObject.quote("$credentialed/session/post-stale")};
                      document.body.appendChild(form);
                      form.submit();
                    })();
                """.trimIndent(), requireCallback = false)
                eventually("POST was blocked or the stale route was requested") {
                    blockedNavigations() > blockedBefore ||
                        server.requests.any { it.path == "/session/post-stale" }
                }
                assertThat(blockedNavigations()).isGreaterThan(blockedBefore)
                assertThat(server.requests.none { it.path == "/session/post-stale" }).isTrue()
            }
        }
    }

    @Test
    fun legacyStaleRoute_isNotEmbeddedInWorkspaceRecovery() {
        withConfiguredServer(basicAuth = true) { server, repository ->
            val credentialed = server.baseUrl.replace("http://", "http://test-user:test-pass@")
            repository.saveAppUrls(credentialed, "")
            repository.saveLastLoadedUrl("$credentialed/session/old")
            check(encryptedPreferences().edit().putString("server_url", server.baseUrl).commit())
            assertThat(repository.getLastLoadedUrl()).isEqualTo("$credentialed/session/old")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                eventually("Basic sign-in prompt or cached credentials") {
                    isTextShown("Sign in to 127.0.0.1") ||
                        server.requests.any { it.authorization == "Basic dGVzdC11c2VyOnRlc3QtcGFzcw==" }
                }
                if (isTextShown("Sign in to 127.0.0.1")) {
                    enterHttpAuthCredentials("test-user", "test-pass")
                    clickDialogButton("Sign in")
                }
                awaitWebView(scenario) {
                    it.url == "${server.baseUrl}/" && it.title == "Hermes HTTP authenticated"
                }
                assertWorkspaceRecoveryDoesNotNavigateToOldRoute(scenario, server)
            }
        }
    }

    @Test
    fun httpBasicChallenge_masksTheEnteredPassword() {
        withConfiguredServer(basicAuth = true) { _, _ ->
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitText("Sign in to 127.0.0.1")
                assertHttpAuthPasswordMasked()
            }
        }
    }

    @Test
    fun popupHttpBasicChallenge_trustedHostRequiresMaskedManualSignIn() {
        withConfiguredServer(officialWebapp = true) { server, _ ->
            seedLegacyHttpAuth(enabled = true, saved = legacyHttpAuthJson())
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ProxyServer(basicAuth = true).use { authServer ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitWebView(scenario) { it.title == "Hermes signed in" }
                    val challengeView = dispatchChallengeThroughPopup(scenario, authServer.baseUrl)
                    try {
                        eventually("Popup prompt or automatic credential submission") {
                            isTextShown("Sign in to 127.0.0.1") ||
                                authServer.requests.any { it.authorization.isNotEmpty() }
                        }
                        assertThat(authServer.requests.filter { it.authorization.isNotEmpty() }).isEmpty()
                        assertThat(isTextShown("Sign in to 127.0.0.1")).isTrue()
                        assertThat(isTextShown("Remember on this device")).isFalse()
                        assertHttpAuthPasswordMasked()
                        enterHttpAuthCredentials("test-user", "test-pass")
                        clickDialogButton("Sign in")
                        eventually("Popup-dispatched challenge accepted after confirmation") {
                            authServer.requests.any {
                                it.authorization == "Basic dGVzdC11c2VyOnRlc3QtcGFzcw=="
                            }
                        }
                    } finally {
                        scenario.onActivity { challengeView.destroy() }
                    }
                }
            }
        }
    }

    @Test
    fun popupHttpBasicChallenge_untrustedHostIsCancelled() {
        withConfiguredServer(officialWebapp = true) { server, _ ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ProxyServer(basicAuth = true).use { authServer ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitWebView(scenario) { it.title == "Hermes signed in" }
                    val navigationFinished = CountDownLatch(1)
                    val challengeView = dispatchChallengeThroughPopup(scenario, authServer.loginOrigin, navigationFinished)
                    try {
                        assertThat(navigationFinished.await(20, TimeUnit.SECONDS)).isTrue()
                        instrumentation.waitForIdleSync()
                        assertThat(isTextShown("Sign in to localhost")).isFalse()
                        assertThat(authServer.requests.filter { it.authorization.isNotEmpty() }).isEmpty()
                    } finally {
                        scenario.onActivity { challengeView.destroy() }
                    }
                }
            }
        }
    }

    @Test
    fun httpBasicChallenge_legacyCredentialsAreNotSentBeforeManualSignIn() {
        withConfiguredServer(basicAuth = true) { server, _ ->
            val saved = legacyHttpAuthJson()
            val preferences = seedLegacyHttpAuth(enabled = true, saved = saved)
            SettingsRepository(context)

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                eventually("Manual sign-in prompt or automatic credential submission") {
                    isTextShown("Sign in to 127.0.0.1") ||
                        server.requests.any { it.path == "/" && it.authorization.isNotEmpty() }
                }
                assertThat(server.requests.filter { it.path == "/" && it.authorization.isNotEmpty() }).isEmpty()
                assertThat(isTextShown("Sign in to 127.0.0.1")).isTrue()
                assertThat(isTextShown("Remember on this device")).isFalse()
                assertThat(preferences.getString("http_auth_credentials", null)).isEqualTo(saved)
                assertThat(preferences.contains("remember_http_auth_enabled")).isFalse()

                enterHttpAuthCredentials("test-user", "test-pass")
                clickDialogButton("Sign in")
                awaitWebView(scenario) { it.title == "Hermes HTTP authenticated" }
                assertThat(preferences.getString("http_auth_credentials", null)).isEqualTo(saved)
            }
        }
    }

    @Test
    fun httpBasicLegacySettings_preserveAndExplicitlyForgetInactiveData() {
        for (saved in listOf(legacyHttpAuthJson(), "malformed legacy data")) {
            withConfiguredServer(officialWebapp = true) { server, _ ->
                val preferences = seedLegacyHttpAuth(enabled = false, saved = saved)
                SettingsRepository(context)
                assertThat(preferences.getString("http_auth_credentials", null)).isEqualTo(saved)
                instrumentation.runOnMainSync {
                    CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
                }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitWebView(scenario) { it.title == "Hermes signed in" }
                    sendDeepLink(scenario, "hermes://app/settings")
                    compose.waitUntil(20_000) {
                        compose.onAllNodesWithText("Sign in with Chrome").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithText("Forget saved sign-ins").performScrollTo().performClick()
                    compose.waitForIdle()
                    assertThat(preferences.contains("http_auth_credentials")).isFalse()
                    assertThat(compose.onAllNodesWithText("Forget saved sign-ins").fetchSemanticsNodes()).isEmpty()
                    assertThat(compose.onAllNodesWithText("Remember server sign-in").fetchSemanticsNodes()).isEmpty()
                }
            }
        }
    }

    @Test
    fun httpBasicChallenge_manualSignInIsNotStoredEvenWithLegacyOptInFlag() {
        withConfiguredServer(basicAuth = true) { _, _ ->
            val preferences = seedLegacyHttpAuth(enabled = true, saved = legacyHttpAuthJson())
            check(preferences.edit()
                .putInt("last_migration_version", 16)
                .remove("http_auth_credentials")
                .commit())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("Sign in to 127.0.0.1")
                val rememberOffered = isTextShown("Remember on this device")
                enterHttpAuthCredentials("test-user", "test-pass")
                clickDialogButton("Sign in")
                awaitWebView(scenario) { it.title == "Hermes HTTP authenticated" }
                assertThat(preferences.contains("http_auth_credentials")).isFalse()
                assertThat(rememberOffered).isFalse()
            }
        }
    }

    @Test
    fun automaticallyLabelsBothFrontendsWithoutChangingManualSelectionOrReloading() {
        for (official in listOf(true, false)) {
            withConfiguredServer(officialWebapp = official) { server, repository ->
                if (!official) repository.saveLastLoadedUrl("${server.baseUrl}/session/issue132")
                instrumentation.runOnMainSync {
                    CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
                }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    eventually("Automatic profile created after frontend marker") {
                        repository.getProfiles().isNotEmpty()
                    }
                    val profile = repository.getProfiles().single()
                    assertThat(profile.serverType).isEqualTo(ServerType.UNSPECIFIED)
                    evaluateWebView(scenario, "window.__workspaceDraft = 'unsent';")
                    scenario.onActivity {
                        val originalIntent = it.intent
                        instrumentation.callActivityOnNewIntent(it, Intent(
                            Intent.ACTION_VIEW, android.net.Uri.parse("hermes://app/settings")
                        ))
                        it.intent = originalIntent
                    }
                    compose.waitForIdle()
                    compose.onNode(hasText("Current server") and
                        hasText(if (official) "Hermes Agent" else "Hermes WebUI"))
                        .assertIsDisplayed()
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    assertThat(evaluateWebView(scenario, "window.__workspaceDraft")).isEqualTo("\"unsent\"")
                    assertThat(repository.getProfiles()).hasSize(1)
                }
            }
        }
    }

    @Test
    fun officialWebapp_backOpensSettingsEvenWithWorkspaceHistory() {
        withConfiguredServer(officialWebapp = true) { server, _ ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                eventually("Official workspace ready") {
                    evaluateWebView(scenario, "window.__HERMES_UI_SURFACE__ === 'webapp'") == "true"
                }
                assertOfficialBackPreservesWorkspace(scenario)
            }
        }
    }

    @Test
    fun labelingCurrentServerPreservesTheMountedWorkspace() {
        withConfiguredServer(officialWebapp = true) { server, repository ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                eventually("Official workspace ready") {
                    evaluateWebView(scenario, "window.__HERMES_UI_SURFACE__ === 'webapp'") == "true"
                }
                evaluateWebView(scenario, "window.__workspaceDraft = 'unsent';")
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithText("Current server").performTouchInput { longClick() }
                compose.onNode(hasText("Hermes Agent") and hasAnyAncestor(isDialog()))
                    .performScrollTo().performClick()
                compose.onNodeWithText("Save").performClick()
                eventually("Current server has the chosen label") {
                    repository.getActiveProfile()?.serverType == ServerType.AGENT
                }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                assertThat(evaluateWebView(scenario, "window.__workspaceDraft")).isEqualTo("\"unsent\"")
                assertThat(repository.getActiveProfile()?.url).isEqualTo(server.baseUrl)
            }
        }
    }

    @Test
    fun webUi_backStillNavigatesHistoryBeforeOpeningSettings() {
        withConfiguredServer { server, repository ->
            repository.saveLastLoadedUrl("${server.baseUrl}/session/issue132")
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                eventually("WebUI document ready") {
                    evaluateWebView(scenario, "document.querySelector('main.main') !== null") == "true"
                }
                pushHistoryFromTouch(scenario, "#history-entry")
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                eventually("WebUI history navigated") {
                    evaluateWebView(scenario, "location.hash") == "\"\""
                }
                compose.onNodeWithText("Settings").assertDoesNotExist()
            }
        }
    }

    private fun assertOfficialBackPreservesWorkspace(scenario: ActivityScenario<MainActivity>) {
        evaluateWebView(scenario, "window.__workspaceDraft = 'unsent';")
        pushHistoryFromTouch(scenario, "#/settings")
        val url = evaluateWebView(scenario, "location.href")
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Settings"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Settings").assertIsDisplayed()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        assertThat(evaluateWebView(scenario, "location.href")).isEqualTo(url)
        assertThat(evaluateWebView(scenario, "window.__workspaceDraft")).isEqualTo("\"unsent\"")
    }

    private fun pushHistoryFromTouch(scenario: ActivityScenario<MainActivity>, hash: String) {
        // Chromium skips script-only history entries on Back until the page has user activation.
        eventually("History document ready") {
            evaluateWebView(scenario, "document.readyState === 'complete' && document.body !== null") == "true"
        }
        evaluateWebView(scenario, """
            var historyButton = document.createElement('button');
            historyButton.id = '__hermes_test_history_button';
            historyButton.style.cssText = 'position:fixed;inset:0;width:100%;height:100%;z-index:100';
            historyButton.textContent = 'Open workspace panel';
            historyButton.onclick = function() {
              history.pushState(null, '', ${JSONObject.quote(hash)});
              historyButton.remove();
            };
            document.body.appendChild(historyButton);
        """.trimIndent())
        eventually("History control is ready for touch") {
            evaluateWebView(scenario, """
                (function() {
                  var button = document.getElementById('__hermes_test_history_button');
                  return !!button && document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2) === button;
                })()
            """.trimIndent()) == "true"
        }
        tapWebViewCenter(scenario)
        awaitWebView(scenario) { it.url?.endsWith(hash) == true && it.canGoBack() }
    }

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
    fun initialMainFrameRedirect_continuesToProxySignInAfterConsent() {
        withConfiguredServer(publicStatus = true) { server, repository ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("${server.baseUrl} redirected to ${server.loginOrigin}.")
                assertThat(server.requests.none { it.host.startsWith("localhost:") }).isTrue()
                clickDialogButton("Continue")
                awaitLoginForm(scenario, server)
                assertThat(repository.getLastLoadedUrl()).doesNotContain("login")
                evaluateWebView(scenario, "document.getElementById('signin').click();")
                awaitWebView(scenario) {
                    it.url == "${server.baseUrl}/session/issue132" && it.title == "Hermes signed in"
                }
            }
        }
    }

    @Test
    fun editingCurrentServer_continuesToProxySignInAfterConsent() {
        withConfiguredServer(officialWebapp = true) { original, repository ->
            ProxyServer(publicStatus = true).use { target ->
                repository.addProfile("Primary", original.baseUrl)
                instrumentation.runOnMainSync {
                    CookieManager.getInstance().setCookie(original.baseUrl, "proxy_auth=ok; Path=/")
                }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitWebView(scenario) {
                        it.url == "${original.baseUrl}/" && it.title == "Hermes signed in"
                    }
                    scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    compose.onNodeWithText("Primary").performTouchInput { longClick() }
                    compose.onNodeWithText("Server URL").performTextReplacement(target.baseUrl)
                    compose.onNodeWithText("Save").performClick()

                    awaitText("${target.baseUrl} redirected to ${target.loginOrigin}.")
                    assertThat(target.requests.none { it.host.startsWith("localhost:") }).isTrue()
                    clickDialogButton("Continue")
                    awaitLoginForm(scenario, target)
                    evaluateWebView(scenario, "document.getElementById('signin').click();")
                    awaitWebView(scenario) {
                        it.url == "${target.baseUrl}/session/issue132" && it.title == "Hermes signed in"
                    }
                }
            }
        }
    }

    @Test
    fun mainFrameConsent_isRejectedAfterAnotherPageLoads() {
        withConfiguredServer(publicStatus = true) { server, _ ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("${server.baseUrl} redirected to ${server.loginOrigin}.")
                scenario.onActivity { activity ->
                    checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                        .loadUrl("${server.baseUrl}/api/status")
                }
                awaitWebView(scenario) {
                    it.url == "${server.baseUrl}/api/status" && it.progress == 100
                }
                clickDialogButton("Continue")
                assertProxyApproval(server, approved = false)
                assertThat(server.requests.none { it.host.startsWith("localhost:") }).isTrue()
            }
        }
    }

    @Test
    fun mainFrameConsent_isRejectedAfterTheSameUrlIsReloaded() {
        withConfiguredServer(publicStatus = true, officialWebapp = true) { server, _ ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("${server.baseUrl} redirected to ${server.loginOrigin}.")
                instrumentation.runOnMainSync {
                    CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
                }
                scenario.onActivity { activity ->
                    checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                        .loadUrl(server.baseUrl)
                }
                awaitWebView(scenario) {
                    it.url == "${server.baseUrl}/" && it.title == "Hermes signed in"
                }
                clickDialogButton("Continue")
                assertProxyApproval(server, approved = false)
                assertThat(server.requests.none { it.host.startsWith("localhost:") }).isTrue()
            }
        }
    }

    @Test
    fun sameOriginAndHttpsUpgradeRedirectsNeverRequestProxyConsent() = runBlocking {
        ProxyServer(locationOverride = "/oauth2/sign_in?rd=%2F").use { server ->
            val result = HermesApiClient.checkServerReadiness(server.baseUrl)
            assertThat(result.signInRedirectOrigin).isNull()
            assertThat(result.status).isEqualTo(HermesApiClient.ServerReadinessStatus.AUTH_REQUIRED)
            assertThat(HermesApiClient.isServerReachable(server.baseUrl)).isTrue()
        }
        ProxyServer(redirectCode = 301, locationOverride = "https://127.0.0.1:{port}/api/status").use { server ->
            val result = HermesApiClient.checkServerReadiness(server.baseUrl)
            assertThat(result.signInRedirectOrigin).isNull()
            assertThat(result.status).isEqualTo(HermesApiClient.ServerReadinessStatus.REDIRECTED)
            assertThat(result.message).contains("Use ${server.baseUrl.replace("http://", "https://")}")
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

    @Test
    fun stagedShareFilesReachOnlyTheConfiguredWebUiFileChooser() {
        val shared = java.io.File(context.cacheDir, "upload-captures/issue136-share.txt").apply {
            parentFile?.mkdirs()
            writeText("synthetic shared file")
        }
        val sharedUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shared)
        val pickerLaunches = java.util.concurrent.atomic.AtomicInteger()
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                pickerLaunches.incrementAndGet()
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(picker)
        try {
            withConfiguredServer { server, _ ->
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitText("Continue to server sign-in?")
                    clickDialogButton("Continue")
                    awaitLoginForm(scenario, server)
                    var launchIntent: Intent? = null
                    scenario.onActivity { launchIntent = it.intent }
                    context.startActivity(
                        Intent(Intent.ACTION_SEND).setClass(context, MainActivity::class.java)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_STREAM, sharedUri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                    eventually("Share intent delivered to the running activity") {
                        var delivered = false
                        scenario.onActivity { delivered = it.intent?.action == Intent.ACTION_SEND }
                        delivered
                    }
                    // ActivityScenario tracks its activity by launch intent; onNewIntent replaced it.
                    scenario.onActivity { it.intent = launchIntent }

                    tapWebViewCenter(scenario)
                    eventually({
                        "Sign-in page file chooser falls back to the system picker; page state " +
                            evaluateWebView(scenario, "JSON.stringify({clicks: window.pickerClicks || 0, url: location.href})")
                    }) {
                        pickerLaunches.get() == 1
                    }
                    SystemClock.sleep(500)
                    assertThat(evaluateWebView(scenario, "String(window.pickedCount || 0)")).isEqualTo("\"0\"")

                    evaluateWebView(scenario, "document.getElementById('signin').click();")
                    awaitWebView(scenario) { it.url == "${server.baseUrl}/session/issue132" && it.title == "Hermes signed in" }
                    eventually("Hermes file input ready") {
                        evaluateWebView(scenario, "document.getElementById('picker') !== null") == "true"
                    }
                    tapWebViewCenter(scenario)
                    eventually("Configured Hermes WebUI receives the staged share file") {
                        evaluateWebView(scenario, "String(window.pickedCount || 0)") == "\"1\""
                    }
                    assertThat(pickerLaunches.get()).isEqualTo(1)
                }
            }
        } finally {
            instrumentation.removeMonitor(picker)
            shared.delete()
        }
    }

    private fun tapWebViewCenter(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val webView = checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
            val x = webView.width / 2f
            val y = webView.height / 2f
            val downTime = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                webView.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertWorkspaceRecoveryDoesNotNavigateToOldRoute(
        scenario: ActivityScenario<MainActivity>,
        server: ProxyServer
    ) {
        eventually("Authenticated WebUI document and workspace recovery are ready") {
            evaluateWebView(scenario, """
                document.readyState === 'complete' &&
                  location.origin === ${JSONObject.quote(server.baseUrl)} &&
                  window.__HERMES_WEBUI_BUNDLE_VERSION__ === 'fixture' &&
                  window.__hermesAndroidRouteRecoveryInstalled === true
            """.trimIndent(), requireCallback = false) == "true"
        }
        val oldRequests = server.requests.count { it.path == "/session/old" }
        val blockedBefore = DiagnosticsLogger.recentText(context).lineSequence()
            .count { it.contains("stale_credential_navigation_blocked") }
        evaluateWebView(scenario, """
            (function() {
              var button = document.createElement('button');
              button.id = 'btnWorkspacePanelToggle';
              document.body.appendChild(button);
              window.setTimeout(function() { window.__recoveryCheck = true; }, 600);
              button.click();
            })();
        """.trimIndent())
        eventually({
            "Workspace recovery check completed or stale route was requested; ${webViewSummary(scenario)}; " +
                "document=" + evaluateWebView(scenario, """
                    JSON.stringify({
                      ready: document.readyState,
                      url: location.href,
                      recoveryInstalled: window.__hermesAndroidRouteRecoveryInstalled,
                      recoveryCheck: window.__recoveryCheck,
                      testButton: !!document.getElementById('btnWorkspacePanelToggle')
                    })
                """.trimIndent(), requireCallback = false) +
                "; paths=" + server.requests.map { it.path }
        }) {
            server.requests.count { it.path == "/session/old" } > oldRequests ||
                evaluateWebView(scenario, "window.__recoveryCheck === true") == "true"
        }
        assertThat(server.requests.count { it.path == "/session/old" }).isEqualTo(oldRequests)
        assertThat(DiagnosticsLogger.recentText(context).lineSequence()
            .count { it.contains("stale_credential_navigation_blocked") }).isEqualTo(blockedBefore)
        assertThat(evaluateWebView(scenario, "location.href")).isEqualTo("\"${server.baseUrl}/\"")
    }

    private fun enterHttpAuthCredentials(username: String, password: String) {
        instrumentation.runOnMainSync {
            val fields = WindowInspector.getGlobalWindowViews().asSequence()
                .flatMap { descendants(it) }
                .filterIsInstance<EditText>()
                .filter { it.isShown }
                .toList()
            fields.single { it.hint == "Username" }.setText(username)
            fields.single { it.hint == "Password" }.setText(password)
        }
    }

    private fun assertHttpAuthPasswordMasked() {
        instrumentation.runOnMainSync {
            val password = WindowInspector.getGlobalWindowViews().asSequence()
                .flatMap { root -> descendants(root) }
                .filterIsInstance<EditText>()
                .single { field -> field.isShown && field.hint == "Password" }
            password.setText("test-pass")
            assertThat(password.transformationMethod).isInstanceOf(PasswordTransformationMethod::class.java)
            val displayed = password.transformationMethod.getTransformation(password.text, password).toString()
            assertThat(displayed).hasLength(password.text.length)
            assertThat(displayed).doesNotContain("test-pass")
        }
    }

    private fun createPopup(activity: MainActivity): WebView {
        val main = checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
        val transport = main.WebViewTransport()
        val message = android.os.Handler(android.os.Looper.getMainLooper()).obtainMessage().apply {
            obj = transport
        }
        check(checkNotNull(main.webChromeClient).onCreateWindow(main, false, true, message))
        return checkNotNull(transport.webView)
    }

    private fun mainFrameRequest(url: String, method: String = "GET") = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = method
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun dispatchChallengeThroughPopup(
        scenario: ActivityScenario<MainActivity>,
        url: String,
        navigationFinished: CountDownLatch? = null
    ): WebView {
        val dispatched = CountDownLatch(1)
        lateinit var challengeView: WebView
        scenario.onActivity { activity ->
            val popup = createPopup(activity)
            challengeView = WebView(activity).apply {
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onReceivedHttpAuthRequest(
                        view: WebView?,
                        handler: android.webkit.HttpAuthHandler?,
                        host: String?,
                        realm: String?
                    ) {
                        popup.webViewClient.onReceivedHttpAuthRequest(popup, handler, host, realm)
                        dispatched.countDown()
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        if (finishedUrl?.startsWith(url) == true) navigationFinished?.countDown()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true && request.url.toString().startsWith(url)) {
                            navigationFinished?.countDown()
                        }
                    }
                }
                loadUrl(url)
            }
        }
        check(dispatched.await(20, TimeUnit.SECONDS)) { "Popup authentication callback was not dispatched" }
        return challengeView
    }

    private fun legacyHttpAuthJson(): String = JSONObject()
        .put("127.0.0.1\nHermes WebUI", JSONObject().put("username", "test-user").put("password", "test-pass"))
        .toString()

    @Suppress("DEPRECATION")
    private fun encryptedPreferences(): android.content.SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            "hermes_secure_prefs",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    private fun seedLegacyHttpAuth(enabled: Boolean, saved: String): android.content.SharedPreferences {
        val preferences = encryptedPreferences()
        check(preferences.edit()
            .putInt("last_migration_version", 15)
            .putBoolean("remember_http_auth_enabled", enabled)
            .putString("http_auth_credentials", saved)
            .commit())
        return preferences
    }

    @Test
    fun allowlistedHostOnAnotherPortCannotRequestConsentForTheServer() {
        withConfiguredServer { server, _ ->
            // Same host as the configured server, so the host-only allowlist admits it, but another port.
            ProxyServer(locationOverride = "https://attacker.invalid/phish").use { redirector ->
                redirector.rejectSession.set(true)
                val externalized = CopyOnWriteArrayList<String>()
                val monitor = object : Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                        if (intent.action != Intent.ACTION_VIEW || intent.data?.host != "attacker.invalid") return null
                        externalized.add(intent.dataString.orEmpty())
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                }
                instrumentation.addMonitor(monitor)
                try {
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        awaitText("Continue to server sign-in?")
                        clickDialogButton("Continue")
                        awaitLoginForm(scenario, server)
                        evaluateWebView(scenario, "document.getElementById('signin').click();")
                        awaitWebView(scenario) { it.url == "${server.baseUrl}/session/issue132" && it.title == "Hermes signed in" }
                        evaluateWebView(scenario, "location.href = ${JSONObject.quote("${redirector.baseUrl}/api/status")};")
                        eventually({
                            "Unrelated redirect externalized; consent shown=${isTextShown("redirected to https://attacker.invalid")}"
                        }) { externalized.isNotEmpty() }
                        instrumentation.waitForIdleSync()
                        assertThat(isTextShown("Continue to server sign-in?")).isFalse()
                        assertThat(redirector.requests.any { it.path == "/api/status" }).isTrue()
                    }
                } finally {
                    instrumentation.removeMonitor(monitor)
                }
            }
        }
    }

    // ---- Issue #150 phase 5: flows -------------------------------------------------------------

    /** Issues #12/#54/#66: the PKCE cookie set at login start must survive the in-app provider hop. */
    @Test
    fun oidcCodeFlow_keepsPkceCookieProviderInAppAndNeverPersistsProviderUrls() {
        withConfiguredServer(oidc = true) { server, repository ->
            val externalized = CopyOnWriteArrayList<String>()
            val monitor = recordExternalViews(externalized)
            instrumentation.addMonitor(monitor)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitDomControl(scenario, server.baseUrl, "sso")
                    evaluateWebView(scenario, "document.getElementById('sso').click();")

                    awaitDomControl(scenario, server.loginOrigin, "approve")
                    // #66: a 100vh provider root must not collapse to a blank 0px page.
                    eventually({
                        "Provider root height, observed ${evaluateWebView(scenario,
                            "[document.getElementById('root').getBoundingClientRect().height, innerHeight].join()")}"
                    }) {
                        evaluateWebView(
                            scenario,
                            "document.getElementById('root').getBoundingClientRect().height > 0"
                        ) == "true"
                    }
                    // Provider pages must never become the cold-start URL.
                    assertThat(repository.getLastLoadedUrl()).doesNotContain("authorize")
                    evaluateWebView(scenario, "document.getElementById('approve').click();")

                    awaitWebView(scenario) {
                        it.url == "${server.baseUrl}/session/issue132" && it.title == "Hermes signed in"
                    }
                    eventually("Final Hermes route persisted") {
                        repository.getLastLoadedUrl() == "${server.baseUrl}/session/issue132"
                    }
                }
                // #12: the callback reached the server with the PKCE cookie and was not rejected.
                val callback = server.requests.single { it.path == "/oidc/callback" }
                assertThat(callback.cookie).contains("hermes_oidc_pkce=verifier123")
                // Cookies are host-scoped: the PKCE cookie must not leak to the provider origin.
                assertThat(server.requests.none {
                    it.host.startsWith("localhost:") && it.cookie.contains("hermes_oidc_pkce")
                }).isTrue()
                // The flow stayed in the app: nothing was handed to an external browser.
                assertThat(externalized).isEmpty()
                assertThat(repository.getLastLoadedUrl()).doesNotContain("oidc/callback")
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
    }

    /** An authorization URL whose redirect_uri does not return to the server is not an in-app OAuth flow. */
    @Test
    fun oidcAuthorizationWithForeignRedirectUri_isExternalizedNotLoadedInApp() {
        withConfiguredServer(oidc = true) { server, _ ->
            val externalized = CopyOnWriteArrayList<String>()
            val monitor = recordExternalViews(externalized)
            instrumentation.addMonitor(monitor)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitDomControl(scenario, server.baseUrl, "evil")
                    evaluateWebView(scenario, "document.getElementById('evil').click();")
                    eventually("Foreign-redirect authorization URL externalized") { externalized.isNotEmpty() }
                    instrumentation.waitForIdleSync()
                    assertThat(server.requests.none { it.path == "/authorize" }).isTrue()
                    awaitWebView(scenario) { it.url == "${server.baseUrl}/" }
                }
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
    }

    /**
     * Issue #184: a proxy sign-in gateway (TinyAuth-like) starts its own OIDC flow whose callback
     * returns to the gateway. The provider must stay in the app so the gateway's state cookie is
     * present at the callback; externalizing it produced "OAuth session cookie missing".
     */
    @Test
    fun proxyGatewayOidc_keepsProviderInAppSoTheGatewayCallbackHasItsCookie() {
        withConfiguredServer(gatewayOidc = true) { server, repository ->
            assertGatewayOidcSignInStaysInApp(server, repository)
        }
    }

    /**
     * With Sign in with Chrome on, a callback Chrome cannot return (Auth Tab requires https) must
     * not swallow the flow: the gateway sign-in still completes in the app.
     */
    @Test
    fun proxyGatewayOidc_withSignInWithChromeAndHttpCallback_continuesInApp() {
        withConfiguredServer(gatewayOidc = true) { server, repository ->
            repository.setBrowserSignInEnabled(true)
            assertGatewayOidcSignInStaysInApp(server, repository)
        }
    }

    private fun assertGatewayOidcSignInStaysInApp(server: ProxyServer, repository: SettingsRepository) {
        val externalized = CopyOnWriteArrayList<String>()
        val monitor = recordExternalViews(externalized)
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitText("Continue to server sign-in?")
                clickDialogButton("Continue")
                awaitDomControl(scenario, server.loginOrigin, "provider")
                evaluateWebView(scenario, "document.getElementById('provider').click();")

                awaitDomControl(scenario, server.providerOrigin, "approve")
                assertThat(repository.getLastLoadedUrl()).doesNotContain("authorize")
                evaluateWebView(scenario, "document.getElementById('approve').click();")

                awaitWebView(scenario) {
                    it.url == "${server.baseUrl}/session/issue132" && it.title == "Hermes signed in"
                }
                eventually("Final Hermes route persisted") {
                    repository.getLastLoadedUrl() == "${server.baseUrl}/session/issue132"
                }
                assertThat(externalized).isEmpty()

                // The finished sign-in must end the in-app OAuth flow: a later non-allowlisted link
                // from Hermes is externalized instead of loading in the URL-bar-less WebView.
                evaluateWebView(scenario, "location.href = ${JSONObject.quote("${server.providerOrigin}/after-sign-in")};")
                eventually("Post-sign-in external link externalized") {
                    externalized.any { it.contains("/after-sign-in") }
                }
                assertThat(server.requests.none { it.path == "/after-sign-in" }).isTrue()
            }
            val callback = server.requests.single { it.path == "/gw/callback" }
            assertThat(callback.cookie).contains("gateway_oauth_state=gw1")
            assertThat(externalized.none { it.contains("authorize") || it.contains("gw/callback") }).isTrue()
            assertThat(repository.getLastLoadedUrl()).doesNotContain("gw/callback")
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    /** Issue #8: a saved but wrong/dead server URL must land in Settings, not a permanent error screen. */
    @Test
    fun deadSavedServerUrl_opensSettingsWithEditableUrlInsteadOfTrappingTheUser() {
        val deadPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val preferences = context.getSharedPreferences("hermes_secure_prefs", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        try {
            val repository = SettingsRepository(context)
            repository.getProfiles().forEach { repository.deleteProfile(it.id) }
            repository.saveAppUrls("http://127.0.0.1:$deadPort", "")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                eventually({ "Settings opened: ${DiagnosticsLogger.recentText(context).takeLast(3_000)}" }) {
                    compose.onAllNodesWithText("Current server").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Current server").assertIsDisplayed()
                scenario.onActivity { assertThat(it.isFinishing).isFalse() }
            }
        } finally {
            restorePreferences(preferences, original)
        }
    }

    /** Issue #9: after the server comes back the app reloads on its own, without a manual Retry or restart. */
    @Test
    fun serverRestart_autoReloadsTheErrorScreenWithoutManualRetry() {
        withConfiguredServer { first, repository ->
            val sessionUrl = "${first.baseUrl}/session/issue132"
            repository.saveLastLoadedUrl(sessionUrl)
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(first.baseUrl, "proxy_auth=ok; Path=/")
            }
            val port = first.baseUrl.substringAfterLast(':').toInt()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.url == sessionUrl && it.title == "Hermes signed in" }
                first.close()
                scenario.onActivity { activity ->
                    findWebView(activity.findViewById(android.R.id.content))!!.loadUrl(sessionUrl)
                }
                compose.waitUntil(30_000) {
                    compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty()
                }
                ProxyServer(port = port).use { restarted ->
                    // Only the native reconnect loop can reload now: no Retry tap, no relaunch.
                    eventually({
                        "Auto-retry reload; ui=${webViewSummary(scenario)} requests=${restarted.requests.map { it.path }} " +
                            DiagnosticsLogger.recentText(context).takeLast(2_000)
                    }, 60_000) {
                        compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isEmpty() &&
                            webViewSummary(scenario).startsWith("$sessionUrl (Hermes signed in)")
                    }
                    // The reconnect probe, not a user action, found the server again.
                    assertThat(restarted.requests.any { it.path == "/api/status" }).isTrue()
                }
            }
        }
    }

    /** Issue #121: hermes://session/{id} opens the WebUI /session/{id} route on the configured server. */
    @Test
    fun sessionDeepLink_loadsSessionRouteOnConfiguredServerAndIsDeclaredInManifest() {
        for (uri in listOf("hermes://session/issue121", "hermes://app/settings")) {
            val resolved = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(context.packageName), 0
            )
            assertThat(resolved.map { it.activityInfo.name }).contains(MainActivity::class.java.name)
        }
        withConfiguredServer { server, repository ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            repository.saveLastLoadedUrl("${server.baseUrl}/session/issue132")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitWebView(scenario) { it.url == "${server.baseUrl}/session/issue132" }
                sendDeepLink(scenario, "hermes://session/issue121")
                awaitWebView(scenario) {
                    it.url == "${server.baseUrl}/session/issue121" && it.title == "Hermes session issue121"
                }
                // Reserved characters are encoded into a single path segment, never a new route.
                sendDeepLink(scenario, "hermes://session/a%20b")
                awaitWebView(scenario) { it.url == "${server.baseUrl}/session/a%20b" }
                // A deep link can only reach the configured origin: no request ever left it.
                assertThat(server.requests.none { it.host.startsWith("localhost:") }).isTrue()
            }
        }
    }

    /** Issue #170: a cold start from hermes://session/{id} must open the session, not the saved route. */
    @Test
    fun sessionDeepLink_onColdStart_opensSessionInsteadOfSavedRoute() {
        withConfiguredServer { server, repository ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            repository.saveLastLoadedUrl("${server.baseUrl}/session/issue132")
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("hermes://session/issue170"))
                .setClass(context, MainActivity::class.java)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                awaitWebView(scenario) { it.url == "${server.baseUrl}/session/issue170" }
                // The session id is one encoded path segment on the configured origin, never a new route.
                assertThat(server.requests.none { it.host.startsWith("localhost:") }).isTrue()
            }
        }
    }

    /** Issue #170: hermes://app/settings is the native recovery link and must work from a cold start. */
    @Test
    fun settingsDeepLink_onColdStart_opensNativeSettings() {
        withConfiguredServer { server, _ ->
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("hermes://app/settings"))
                .setClass(context, MainActivity::class.java)
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithText("Advanced connection options").fetchSemanticsNodes().isNotEmpty()
                }
            }
        }
    }

    /** Issue #81: Settings must open and render, including the plural-backed polling description. */
    @Test
    fun settingsLaunchedFromDeepLink_rendersWithoutCrashingIncludingPollingPlural() {
        withConfiguredServer { server, repository ->
            repository.setReconnectPollIntervalSeconds(2)
            instrumentation.runOnMainSync {
                CookieManager.getInstance().setCookie(server.baseUrl, "proxy_auth=ok; Path=/")
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                sendDeepLink(scenario, "hermes://app/settings")
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithText("Advanced connection options").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Advanced connection options").performScrollTo().performClick()
                compose.onNode(hasText("2 seconds between reconnect checks"))
                    .performScrollTo().assertIsDisplayed()
                scenario.onActivity { assertThat(it.isFinishing).isFalse() }
            }
        }
    }

    private fun webViewSummary(scenario: ActivityScenario<MainActivity>): String {
        var summary = ""
        scenario.onActivity { a -> findWebView(a.findViewById(android.R.id.content))?.let { summary = "${it.url} (${it.title}) progress=${it.progress}" } }
        return summary
    }

    private fun sendDeepLink(scenario: ActivityScenario<MainActivity>, uri: String) {
        scenario.onActivity {
            val originalIntent = it.intent
            instrumentation.callActivityOnNewIntent(it, Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
            it.intent = originalIntent
        }
    }

    private fun recordExternalViews(sink: MutableList<String>) = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != Intent.ACTION_VIEW || intent.data?.host != "localhost") return null
            sink.add(intent.dataString.orEmpty())
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    /** Waits for a real DOM control on the expected origin; WebView.url alone is not document readiness. */
    private fun awaitDomControl(scenario: ActivityScenario<MainActivity>, origin: String, id: String) {
        eventually("DOM control #$id ready on $origin") {
            evaluateWebView(
                scenario,
                "document.readyState === 'complete' && location.origin === ${JSONObject.quote(origin)} && " +
                    "document.getElementById(${JSONObject.quote(id)}) !== null"
            ) == "true"
        }
    }

    private fun restorePreferences(preferences: android.content.SharedPreferences, original: Map<String, Any?>) {
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

    private fun withConfiguredServer(
        officialWebapp: Boolean = false,
        oidc: Boolean = false,
        basicAuth: Boolean = false,
        publicStatus: Boolean = false,
        gatewayOidc: Boolean = false,
        block: (ProxyServer, SettingsRepository) -> Unit
    ) {
        // Restore the exact encrypted backing preferences, including keysets, after the test.
        val preferences = context.getSharedPreferences("hermes_secure_prefs", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        val loggingWasEnabled = SettingsRepository(context).isDebugLoggingEnabled()
        ProxyServer(
            officialWebapp = officialWebapp,
            oidc = oidc,
            basicAuth = basicAuth,
            publicStatus = publicStatus,
            gatewayOidc = gatewayOidc
        ).use { server ->
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
                    CookieManager.getInstance().setCookie(server.loginOrigin, "gateway_oauth_state=; Max-Age=0; Path=/")
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

    private fun assumeLocalNetworkPermissionIsPending() {
        assumeTrue(
            "The delayed-permission fixture requires local-network permission to be ungranted",
            context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED
        )
    }

    private fun delayLocalNetworkPermission(activity: MainActivity, onLaunch: () -> Unit) {
        val delayedPermission = object : ActivityResultLauncher<String>() {
            override fun launch(input: String, options: ActivityOptionsCompat?) = onLaunch()
            override fun unregister() = Unit
            override val contract: ActivityResultContract<String, *> = ActivityResultContracts.RequestPermission()
        }
        MainActivity::class.java.getDeclaredField("localNetworkPermissionLauncher").apply {
            isAccessible = true
            set(activity, delayedPermission)
        }
    }

    private fun awaitText(text: String) {
        eventually({ "$text\n${DiagnosticsLogger.recentText(context).takeLast(3_000)}" }) { isTextShown(text) }
    }

    private fun isTextShown(text: String): Boolean {
        var found = false
        instrumentation.runOnMainSync {
            found = WindowInspector.getGlobalWindowViews().any { root ->
                descendants(root).filterIsInstance<TextView>().any { it.text.contains(text) && it.isShown }
            }
        }
        return found
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
        eventually({
            "Login document and sign-in button ready; ${webViewSummary(scenario)}\n" +
                DiagnosticsLogger.recentText(context).takeLast(3_000)
        }) {
            // The document can be replaced mid-call during navigation, dropping the callback; keep polling.
            evaluateWebView(
                scenario,
                """
                document.readyState === 'complete' &&
                  location.origin === ${JSONObject.quote(server.loginOrigin)} &&
                  document.getElementById('signin') !== null
                """.trimIndent(),
                requireCallback = false
            ) == "true"
        }
    }

    private fun assertProxyApproval(server: ProxyServer, approved: Boolean) {
        var result: String? = null
        eventually("Proxy sign-in decision recorded") {
            result = DiagnosticsLogger.recentText(context).lineSequence()
                .lastOrNull { it.contains("proxy_sign_in_approval") && it.contains("origin=${server.baseUrl}") }
            result != null
        }
        assertThat(result).contains("approved=$approved")
    }

    private fun evaluateWebView(
        scenario: ActivityScenario<MainActivity>,
        script: String,
        requireCallback: Boolean = true
    ): String? {
        val completed = CountDownLatch(1)
        var result: String? = null
        scenario.onActivity { activity ->
            checkNotNull(findWebView(activity.findViewById(android.R.id.content)))
                .evaluateJavascript(script) {
                    result = it
                    completed.countDown()
                }
        }
        val called = completed.await(5, TimeUnit.SECONDS)
        check(called || !requireCallback) { "WebView JavaScript callback timed out" }
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

    private fun eventually(description: String, timeoutMs: Long, condition: () -> Boolean) =
        eventuallyWithin(timeoutMs, { description }, condition)

    private fun eventually(description: () -> String, timeoutMs: Long, condition: () -> Boolean) =
        eventuallyWithin(timeoutMs, description, condition)

    private fun eventually(description: () -> String, condition: () -> Boolean) =
        eventuallyWithin(20_000, description, condition)

    private fun eventuallyWithin(timeoutMs: Long, description: () -> String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Timed out: ${description()}")
    }

    private class ProxyServer(
        private val redirectCode: Int = 302,
        private val locationOverride: String? = null,
        private val officialWebapp: Boolean = false,
        private val oidc: Boolean = false,
        private val basicAuth: Boolean = false,
        private val basicCredentials: String = "test-user:test-pass",
        private val publicStatus: Boolean = false,
        private val gatewayOidc: Boolean = false,
        port: Int = 0
    ) : AutoCloseable {
        data class Request(val path: String, val host: String, val cookie: String, val authorization: String)
        private val socket = ServerSocket(port, 20, InetAddress.getByName("127.0.0.1"))
        // Issue #184: a separate origin for an OIDC provider used by the proxy's own sign-in gateway.
        private val providerSocket = if (gatewayOidc) ServerSocket(0, 20, InetAddress.getByName("127.0.0.1")) else null
        private val running = AtomicBoolean(true)
        val baseUrl = "http://127.0.0.1:${socket.localPort}"
        val loginOrigin = "http://localhost:${socket.localPort}"
        val providerOrigin = providerSocket?.let { "http://localhost:${it.localPort}" }.orEmpty()
        val requests = CopyOnWriteArrayList<Request>()
        val rejectSession = AtomicBoolean(false)
        private val worker = thread(name = "proxy-auth-fixture") { serve(socket) }
        private val providerWorker = providerSocket?.let { listener -> thread(name = "proxy-auth-provider") { serve(listener) } }

        private fun serve(listener: ServerSocket) {
            while (running.get()) {
                val client = try { listener.accept() } catch (exception: SocketException) {
                    if (running.get()) throw exception
                    break
                }
                client.use {
                    it.soTimeout = 5_000
                    val reader = it.getInputStream().bufferedReader()
                    // Chromium opens idle speculative sockets; a read timeout there must not kill the accept loop.
                    val first = try { reader.readLine() } catch (_: java.io.IOException) { null } ?: return@use
                    // HTTPS validation can send a TLS handshake to this HTTP-only fixture.
                    val requestLine = first.split(" ", limit = 3)
                    if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/")) return@use
                    val headers = mutableListOf<String>()
                    while (true) {
                        val line = try { reader.readLine() } catch (_: java.io.IOException) { null } ?: break
                        if (line.isEmpty()) break
                        headers.add(line)
                    }
                    val path = requestLine[1].substringBefore('?')
                    fun header(name: String) = headers.firstOrNull { h -> h.startsWith("$name:", true) }
                        ?.substringAfter(':')?.trim().orEmpty()
                    val cookie = header("Cookie")
                    val authorization = header("Authorization")
                    requests.add(Request(path, header("Host"), cookie, authorization))
                    val basicAuthenticated = authorization ==
                        "Basic ${android.util.Base64.encodeToString(basicCredentials.toByteArray(), android.util.Base64.NO_WRAP)}"
                    val authenticated = (cookie.contains("proxy_auth=ok") || basicAuthenticated) &&
                        !rejectSession.get()
                    val query = requestLine[1].substringAfter('?', "")
                    val (status, extra, body) = when {
                        basicAuth && publicStatus && path == "/api/status" ->
                            Triple("200 OK", "", """{"version":"test","initialized":true}""")
                        basicAuth && !basicAuthenticated ->
                            Triple("401 Unauthorized", "WWW-Authenticate: Basic realm=\"Hermes WebUI\"\r\n", "401 Unauthorized")
                        basicAuth && path == "/api/status" ->
                            Triple("200 OK", "", """{"version":"test","initialized":true}""")
                        basicAuth && path == "/" -> Triple("200 OK", "", """
                            <title>Hermes HTTP authenticated</title>
                            <script>window.__HERMES_WEBUI_BUNDLE_VERSION__="fixture";</script>
                            <div class="layout"><main class="main">Authenticated</main></div>
                        """.trimIndent())
                        // Issue #12/#54/#66 OIDC fixture: the WebUI start page, a PKCE-cookie-setting
                        // start route, a provider on the "localhost" origin and a cookie-checking callback.
                        oidc && path == "/api/status" -> Triple("200 OK", "", """{"version":"test","initialized":true}""")
                        oidc && path == "/" && !authenticated -> Triple("200 OK", "", """
                            <title>Hermes sign in</title>
                            <script>window.__HERMES_WEBUI_BUNDLE_VERSION__="fixture";</script>
                            <div class="layout"><main class="main">
                              <button id="sso" onclick="location.href='/oidc/start'">SSO</button>
                              <button id="evil" onclick="location.href='$loginOrigin/authorize?response_type=code&client_id=hermes&redirect_uri=https%3A%2F%2Fattacker.invalid%2Fcb&state=st1'">Evil</button>
                            </main></div>
                        """.trimIndent())
                        oidc && path == "/oidc/start" -> Triple("302 Found",
                            "Set-Cookie: $PKCE_COOKIE; Path=/; HttpOnly; SameSite=Lax\r\n" +
                                "Location: $loginOrigin/authorize?response_type=code&client_id=hermes" +
                                "&redirect_uri=${java.net.URLEncoder.encode("$baseUrl/oidc/callback", "UTF-8")}" +
                                "&state=st1&code_challenge=abc&code_challenge_method=S256\r\n", "")
                        oidc && path == "/authorize" -> Triple("200 OK", "", """
                            <title>Provider sign-in</title>
                            <div id="root" style="height:100vh;background:#eee">
                              <h1>Sign in to continue</h1><p>${"Provider sign-in form content. ".repeat(40)}</p>
                              <button id="approve" onclick="location.href='$baseUrl/oidc/callback?code=abc&state=st1'">Approve</button>
                            </div>
                        """.trimIndent())
                        oidc && path == "/oidc/callback" ->
                            if (cookie.contains(PKCE_COOKIE) && query.contains("code=abc")) {
                                Triple("302 Found",
                                    "Set-Cookie: proxy_auth=ok; Path=/; HttpOnly; SameSite=Lax\r\n" +
                                        "Set-Cookie: hermes_oidc_pkce=; Max-Age=0; Path=/\r\n" +
                                        "Location: /session/issue132\r\n", "")
                            } else {
                                Triple("400 Bad Request", "", """{"detail":"Missing PKCE state cookie"}""")
                            }
                        path.startsWith("/session/") && path != "/session/issue132" && authenticated ->
                            Triple("200 OK", "", """
                                <title>Hermes session ${path.removePrefix("/session/")}</title>
                                <script>window.__HERMES_WEBUI_BUNDLE_VERSION__="fixture";</script>
                                <div class="layout"><main class="main">Authenticated</main></div>
                            """.trimIndent())
                        path == "/api/status" && (authenticated || publicStatus) ->
                            Triple("200 OK", "", """{"version":"test","initialized":true}""")
                        path == "/api/status" || (path == "/" && !authenticated) ->
                            Triple("$redirectCode Redirect", "Location: ${(locationOverride ?: "$loginOrigin/login?rd=synthetic-secret").replace("{port}", socket.localPort.toString())}\r\n", "")
                        // Issue #184 gateway fixture (TinyAuth-like): the gateway sets its own OAuth state
                        // cookie, sends the user to a provider on another origin, and rejects a callback
                        // that arrives without that cookie.
                        gatewayOidc && path == "/login" -> Triple("200 OK",
                            "Set-Cookie: $GATEWAY_COOKIE; Path=/; HttpOnly; SameSite=Lax\r\n", """
                            <title>Gateway sign-in</title>
                            <button id="provider" onclick="location.href='$providerOrigin/gw/authorize?response_type=code&client_id=gateway&redirect_uri=${java.net.URLEncoder.encode("$loginOrigin/gw/callback", "UTF-8")}&state=gw1'">Sign in with provider</button>
                        """.trimIndent())
                        gatewayOidc && path == "/gw/authorize" -> Triple("200 OK", "", """
                            <title>Provider sign-in</title>
                            <button id="approve" onclick="location.href='$loginOrigin/gw/callback?code=abc&state=gw1'">Approve</button>
                        """.trimIndent())
                        gatewayOidc && path == "/gw/callback" ->
                            if (cookie.contains(GATEWAY_COOKIE) && query.contains("code=abc")) {
                                Triple("302 Found", "Location: $baseUrl/auth/return?ticket=synthetic\r\n", "")
                            } else {
                                Triple("400 Bad Request", "", "OAuth session cookie missing")
                            }
                        path == "/login" -> Triple("200 OK", "", """
                            <title>Proxy sign-in</title>
                            $FILE_INPUT
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
                            <script>setTimeout(function(){location.href='${if (officialWebapp) "/" else "/session/issue132"}';},250);</script>
                        """.trimIndent())
                        (path == "/session/issue132" || (officialWebapp && path == "/")) && authenticated -> Triple("200 OK", "", """
                            <title>Hermes signed in</title>
                            ${if (officialWebapp) """<script>window.__HERMES_UI_SURFACE__="webapp";</script><div id="root"><main>Official workspace</main></div>"""
                              else """<script>window.__HERMES_WEBUI_BUNDLE_VERSION__="fixture";</script><div class="layout"><main class="main">Authenticated</main></div>"""}
                            $FILE_INPUT
                        """.trimIndent())
                        else -> Triple("404 Not Found", "", "")
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val type = if (path == "/api/status") "application/json" else "text/html"
                    try {
                        it.getOutputStream().apply {
                            write(("HTTP/1.1 $status\r\n${extra}Content-Type: $type; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(bytes)
                            flush()
                        }
                    } catch (_: java.io.IOException) {
                        // The WebView may abort a navigation mid-response; keep serving.
                    }
                }
            }
        }

        override fun close() {
            running.set(false)
            socket.close()
            providerSocket?.close()
            worker.join(6_000)
            providerWorker?.join(6_000)
            check(!worker.isAlive && providerWorker?.isAlive != true)
        }

        private companion object {
            // Full-viewport so a real centre tap provides the user activation file choosers need.
            const val PKCE_COOKIE = "hermes_oidc_pkce=verifier123"
            const val GATEWAY_COOKIE = "gateway_oauth_state=gw1"
            const val FILE_INPUT = """<input type="file" id="picker" style="position:fixed;top:0;left:0;right:0;bottom:0;width:100%;height:100%;opacity:0.01;z-index:10" onclick="window.pickerClicks=(window.pickerClicks||0)+1" onchange="window.pickedCount=this.files.length">"""
        }
    }
}
