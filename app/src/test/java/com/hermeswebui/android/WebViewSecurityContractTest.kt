package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Source-level guards for invariants that otherwise live only in AGENTS.md prose. These are
 * deliberately blunt: they fail on the shape of the code, not on behavior, so a future change
 * that reintroduces a documented regression is stopped before it reaches a device.
 *
 * - Issue #127: certificate validation is never bypassed in the WebView.
 * - Issue #7: Android never writes the WebUI dashboard configuration.
 * - Issues #6 / #35: no long-press interception that would break text selection.
 * - Declared permissions that WebUI voice, notifications and LAN access depend on.
 */
class WebViewSecurityContractTest {
    private val mainDir: File = listOf(File("src/main"), File("app/src/main"))
        .firstOrNull { it.isDirectory }
        ?: error("Run from the repository root or the app module")

    private val manifest: String = File(mainDir, "AndroidManifest.xml").readText()

    private val kotlinSources: Map<String, String> = File(mainDir, "java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(mainDir).invariantSeparatorsPath to it.readText() }

    /** Sources with `//` and block comments removed so prose that mentions a rule cannot trip it. */
    private val codeOnly: Map<String, String> = kotlinSources.mapValues { (_, text) -> stripComments(text) }

    @Test
    fun `sources were found so the guards cannot pass vacuously`() {
        assertThat(kotlinSources.keys.any { it.endsWith("MainActivity.kt") }).isTrue()
        assertThat(kotlinSources.size).isGreaterThan(20)
    }

    @Test
    fun `the hosted webview is attached match_parent so viewport units are not zero`() {
        // Issue #184: a null/WRAP_CONTENT layout height makes Chromium lay out the main frame at
        // zero height, so vh/dvh/svh/lvh and html{height:100%} all resolve to 0.
        val shell = codeOnly.entries.single { it.key.endsWith("ui/web/WebShell.kt") }.value
        val factory = shell.substringAfter("AndroidView(").substringBefore("PullRefreshIndicator")
        assertThat(factory).contains("layoutParams")
        assertThat(factory).contains("MATCH_PARENT")
        assertThat(factory).doesNotContain("WRAP_CONTENT")
    }

    @Test
    fun `ssl errors are always cancelled and never proceeded`() {
        val overrides = codeOnly.flatMap { (path, text) ->
            bodiesOf(text, "override fun onReceivedSslError(").map { path to it }
        }
        assertThat(overrides).isNotEmpty()
        overrides.forEach { (path, body) ->
            assertWithMessage("$path onReceivedSslError").that(body).contains("cancel()")
            assertWithMessage("$path onReceivedSslError").that(body).doesNotContain("proceed")
        }
    }

    @Test
    fun `the ssl error handler type is only used by the cancelling override`() {
        // Any other use of SslErrorHandler could call proceed() from somewhere this guard does not see.
        val uses = codeOnly.values.sumOf { text ->
            text.lines()
                .filterNot { it.trimStart().startsWith("import ") }
                .sumOf { Regex("""\bSslErrorHandler\b""").findAll(it).count() }
        }
        val overrides = codeOnly.values.sumOf { Regex("""override fun onReceivedSslError\(""").findAll(it).count() }
        assertThat(uses).isEqualTo(overrides)
    }

    @Test
    fun `android never touches the WebUI dashboard config endpoint`() {
        codeOnly.forEach { (path, text) ->
            assertWithMessage(path).that(text).doesNotContain("dashboard/config")
        }
    }

    @Test
    fun `no long click interception or synthesized context menu`() {
        val forbidden = listOf(
            "setOnLongClickListener",
            "OnLongClickListener",
            "startActionMode",
            "onCreateActionMode",
            "contextmenu",
            "touchcancel"
        )
        codeOnly.forEach { (path, text) ->
            forbidden.forEach { token ->
                assertWithMessage("$path must not use $token").that(text).doesNotContain(token)
            }
        }
        val mainActivity = codeOnly.entries.single { it.key.endsWith("/MainActivity.kt") }.value
        assertThat(mainActivity).contains("isLongClickable = true")
    }

    @Test
    fun `main and popup WebViews handle HTTP authentication through the trusted-host prompt`() {
        val mainActivity = codeOnly.entries.single { it.key.endsWith("/MainActivity.kt") }.value
        assertThat(Regex("""override fun onReceivedHttpAuthRequest\(""").findAll(mainActivity).count())
            .isEqualTo(2)
        assertThat(Regex("""showHttpAuthPrompt\(handler, host, realm\)""").findAll(mainActivity).count())
            .isEqualTo(2)
        val popupFactory = bodiesOf(mainActivity, "private fun createPopupWindow(").single()
        assertThat(popupFactory).contains("webViewClient = buildPopupWebViewClient(this)")
        val popupClient = bodiesOf(mainActivity, "private fun buildPopupWebViewClient(").single()
        assertThat(popupClient).contains("override fun onReceivedHttpAuthRequest(")
        assertThat(popupClient).contains("showHttpAuthPrompt(handler, host, realm)")
        val prompt = bodiesOf(mainActivity, "private fun showHttpAuthPrompt(").single()
        assertThat(prompt).contains("if (!currentWebTrustPolicy().isTrustedHttpAuthHost(host)) {")
        val untrusted = bodiesOf(prompt, "if (!currentWebTrustPolicy().isTrustedHttpAuthHost(host))").single()
        assertThat(untrusted).contains("handler.cancel()")
        assertThat(untrusted).contains("return")
        assertThat(untrusted.indexOf("handler.cancel()")).isLessThan(untrusted.indexOf("return"))
        assertThat(prompt.indexOf("isTrustedHttpAuthHost(host)"))
            .isLessThan(prompt.indexOf("PendingHttpAuthPrompt(handler)"))
        assertThat(prompt).doesNotContain("handler.proceed(")
        assertThat(prompt).contains("setOnClickListener")
        assertThat(prompt.indexOf("setOnClickListener"))
            .isLessThan(prompt.indexOf("proceedHttpAuthPrompt(prompt, enteredUsername, enteredPassword)"))
        listOf(
            "getHttpAuthCredential(",
            "saveHttpAuthCredential(",
            "useHttpAuthUsernamePassword(",
            "getHttpAuthUsernamePassword(",
            "setHttpAuthUsernamePassword("
        ).forEach { forbidden ->
            codeOnly.forEach { (path, text) ->
                assertWithMessage("$path must not use $forbidden").that(text).doesNotContain(forbidden)
            }
        }
        val proceed = bodiesOf(mainActivity, "private fun proceedHttpAuthPrompt(").single()
        assertThat(proceed).contains("prompt.handler.proceed(username, password)")
        val cancel = bodiesOf(mainActivity, "private fun cancelHttpAuthPrompt(").single()
        assertThat(cancel).contains("prompt.handler.cancel()")
        val destroy = bodiesOf(mainActivity, "override fun onDestroy()").single()
        assertThat(destroy).contains("cancelHttpAuthPrompt(prompt)")
        assertThat(destroy).contains("prompt.dialog?.dismiss()")
    }

    @Test
    fun `clipboard and image input stay on scoped message bridges with no javascript interface`() {
        // Issue #180: the clipboard fallback must use the trust-checked WebMessageListener, never
        // addJavascriptInterface, and image input must be advertised only for the configured route.
        codeOnly.forEach { (path, text) ->
            assertWithMessage("$path must not use addJavascriptInterface")
                .that(text).doesNotContain("addJavascriptInterface")
        }
        val coordinator = codeOnly.entries
            .single { it.key.endsWith("HermesClipboardBridgeCoordinator.kt") }.value
        assertThat(coordinator).contains("isTrustedSource(sourceOrigin, isMainFrame)")
        assertThat(coordinator.indexOf("isTrustedSource(sourceOrigin, isMainFrame)"))
            .isLessThan(coordinator.indexOf("primaryClip"))

        val mainActivity = codeOnly.entries.single { it.key.endsWith("MainActivity.kt") }.value
        assertThat(mainActivity).contains("acceptsImageInput = { matchesConfiguredWebUiRoute(url) }")
        val delivery = bodiesOf(mainActivity, "private fun deliverCommittedImage(").single()
        assertThat(delivery).contains("matchesConfiguredWebUiRoute(webView.url)")
        assertThat(delivery).contains("buildOriginGuardedRuntimeScript(")
    }

    @Test
    fun `switching servers wipes the old session before loading the new host`() {
        // A session from one server must never carry over to another: cookies, web storage, cache,
        // form data and history are all cleared, and the wipe runs before the profile changes.
        val mainActivity = codeOnly.entries.single { it.key.endsWith("MainActivity.kt") }.value
        val wipe = bodiesOf(mainActivity, "private fun clearWebViewStateForServerSwitch(").single()
        listOf(
            "removeAllCookies(",
            "WebStorage.getInstance().deleteAllData()",
            "clearHistory()",
            "clearCache(true)",
            "clearFormData()"
        ).forEach { call -> assertWithMessage("server-switch wipe").that(wipe).contains(call) }

        val switchBody = bodiesOf(mainActivity, "private fun performServerProfileSwitch(").single()
        val wipeAt = switchBody.indexOf("clearWebViewStateForServerSwitch()")
        val switchAt = switchBody.indexOf("viewModel.switchServerProfile(")
        assertThat(wipeAt).isAtLeast(0)
        assertThat(switchAt).isGreaterThan(wipeAt)
    }

    @Test
    fun `sign in with chrome launches only a verified auth tab and returns only verified callbacks`() {
        // Issue #184: an unsupported browser silently opens a plain Custom Tab that can never return
        // the callback, so Auth Tab support is checked and the browser pinned before launching.
        val mainActivity = codeOnly.entries.single { it.key.endsWith("MainActivity.kt") }.value
        val launch = bodiesOf(mainActivity, "private fun launchBrowserSignIn(").single()
        val launchAt = launch.indexOf("authTab.launch(")
        listOf(
            "isBrowserSignInEnabled()",
            "BrowserSignIn.authTabCallback(",
            "CustomTabsClient.isAuthTabSupported(",
            "intent.setPackage(browserPackage)"
        ).forEach { guard ->
            assertWithMessage("launchBrowserSignIn guard $guard").that(launch.indexOf(guard)).isAtLeast(0)
            assertWithMessage("launchBrowserSignIn guard $guard").that(launch.indexOf(guard)).isLessThan(launchAt)
        }
        val result = bodiesOf(mainActivity, "private fun onBrowserSignInResult(").single()
        assertThat(result).contains("isBrowserSignInStillTrusted(pending)")
        assertThat(result).contains("pending.flow.isVerifiedCallbackUrl(callbackUrl)")
        assertThat(manifest).contains("android.support.customtabs.action.CustomTabsService")
    }

    @Test
    fun `manifest declares the permissions WebUI features depend on`() {
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        listOf(
            "android.permission.INTERNET",
            "android.permission.RECORD_AUDIO",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.ACCESS_LOCAL_NETWORK"
        ).forEach { assertWithMessage(it).that(declared).contains(it) }
    }

    @Test
    fun `manifest does not request broad storage access`() {
        // Downloads use the shared Download folder through DownloadManager (Issue #130).
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
        assertThat(declared).containsNoneOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE"
        )
    }

    private fun stripComments(text: String): String {
        val noBlock = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        // Skip `://` so URLs inside string literals survive.
        return Regex("""(?<!:)//[^\n]*""").replace(noBlock, "")
    }

    /** Returns the brace-balanced bodies of every function that starts with [signature]. */
    private fun bodiesOf(text: String, signature: String): List<String> {
        val bodies = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = text.indexOf(signature, from)
            if (start < 0) break
            val open = text.indexOf('{', start)
            if (open < 0) break
            var depth = 0
            var end = open
            while (end < text.length) {
                when (text[end]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                end++
            }
            bodies += text.substring(open, minOf(end + 1, text.length))
            from = end
        }
        return bodies
    }
}
