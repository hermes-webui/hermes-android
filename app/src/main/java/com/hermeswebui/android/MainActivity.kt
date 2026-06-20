package com.hermeswebui.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hermeswebui.android.core.security.NavigationDecision
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.domain.ServerUrlValidator
import com.hermeswebui.android.domain.ShareIntentParser
import com.hermeswebui.android.ui.MainViewModel
import com.hermeswebui.android.ui.MainViewModelFactory
import com.hermeswebui.android.ui.settings.SettingsBottomSheet
import com.hermeswebui.android.ui.web.WebShell
import org.json.JSONObject

private val HermesColorScheme = darkColorScheme(
    primary = Color(0xFFFFD700),
    onPrimary = Color(0xFF16110A),
    secondary = Color(0xFF4DD0E1),
    onSecondary = Color(0xFF061417),
    background = Color(0xFF0D0D1A),
    onBackground = Color(0xFFFFF8DC),
    surface = Color(0xFF141425),
    onSurface = Color(0xFFFFF8DC),
    surfaceVariant = Color(0xFF1A1A2E),
    onSurfaceVariant = Color(0xFFE6E0C8),
    error = Color(0xFFEF5350),
    onError = Color(0xFF1F0505)
)

private val HermesWebViewViewportFixScript = """
    (function() {
      var styleId = 'hermes-android-viewport-fix';
      var applyViewportFix = function() {
        var visualHeight = window.visualViewport && window.visualViewport.height;
        var height = Math.max(
          window.innerHeight || 0,
          document.documentElement.clientHeight || 0,
          visualHeight || 0
        );
        if (!height) return;

        var px = Math.round(height) + 'px';
        var style = document.getElementById(styleId);
        if (!style) {
          style = document.createElement('style');
          style.id = styleId;
          (document.head || document.documentElement).appendChild(style);
        }
        style.textContent = [
          'html, body { height: ' + px + ' !important; min-height: ' + px + ' !important; }',
          'body { overflow: hidden !important; }',
          '.layout, .rail, .sidebar, .main, .rightpanel, #sessionList, .messages { min-height: 0 !important; }'
        ].join('\n');
      };

      window.__hermesAndroidApplyViewportFix = applyViewportFix;
      applyViewportFix();

      if (!window.__hermesAndroidViewportFixInstalled) {
        window.__hermesAndroidViewportFixInstalled = true;
        window.addEventListener('resize', applyViewportFix, { passive: true });
        window.addEventListener('orientationchange', function() {
          window.setTimeout(applyViewportFix, 0);
          window.setTimeout(applyViewportFix, 250);
        }, { passive: true });
        if (window.visualViewport) {
          window.visualViewport.addEventListener('resize', applyViewportFix, { passive: true });
        }
      }
    })();
""".trimIndent()

private val HermesWebUiMicrophoneFallbackScript = """
    (function() {
      try {
        window.localStorage.setItem('mic_force_mediarecorder', '1');
      } catch (_) {}
    })();
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel
    private lateinit var webView: WebView
    private lateinit var settingsRepository: SettingsRepository

    private var allowedHosts: Set<String> = emptySet()
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingAudioPermissionRequest: PermissionRequest? = null
    private var microphoneFallbackScriptHandler: ScriptHandler? = null
    private val serverUrlValidator = ServerUrlValidator()

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
        viewModel.dismissShareBanner()
    }

    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingAudioPermissionRequest ?: return@registerForActivityResult
        pendingAudioPermissionRequest = null
        if (granted && isTrustedPermissionOrigin(request.origin)) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            request.deny()
            if (!granted) {
                Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val defaultUrl = getString(R.string.default_server_url)
        val defaultDashboardUrl = getString(R.string.default_dashboard_url)
        settingsRepository = SettingsRepository(applicationContext)
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(settingsRepository, defaultUrl, defaultDashboardUrl)
        )[MainViewModel::class.java]
        allowedHosts = viewModel.uiState.value.settings.allowedHosts

        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)
        webView = buildWebView()
        installHermesWebUiDocumentStartFixes(webView, viewModel.uiState.value.settings.serverUrl)

        handleShareIntent(intent)

        setContent {
            AppContent(
                onReload = { webView.reload() },
                onOpenExternal = { openInExternalBrowser(viewModel.uiState.value.currentUrl) },
                onSaveSettings = { serverUrl -> saveSettings(serverUrl) },
                onResetSession = { resetWebSession() },
                onRequestExit = { finish() }
            )
        }

        val settings = viewModel.uiState.value.settings
        if (!settings.isConfigured) {
            viewModel.openSettings()
        } else {
            val lastLoadedUrl = settingsRepository.getLastLoadedUrl()
            val startUrl = if (matchesConfiguredDashboardRoute(lastLoadedUrl)) {
                settings.serverUrl
            } else {
                lastLoadedUrl ?: settings.serverUrl
            }
            webView.loadUrl(startUrl)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleDeepLink(intent)) {
            handleShareIntent(intent)
        }
    }

    /** Handles hermes://session/{session_id} deep links.
     *
     * Navigates the WebView to {serverUrl}/{session_id}, matching the Hermes
     * WebUI session route contract (sessionRoute() in apps/desktop/src/app/routes.ts).
     * Returns true if the intent was consumed, false if it should fall through.
     */
    private fun handleDeepLink(intent: Intent): Boolean {
        val data = intent.data ?: return false
        if (data.scheme != "hermes" || data.host != "session") return false
        val sessionId = data.lastPathSegment?.takeIf { it.isNotBlank() } ?: return false
        val serverUrl = viewModel.uiState.value.settings.serverUrl
        if (serverUrl.isBlank()) {
            Toast.makeText(this, "Configure a server URL in Settings first", Toast.LENGTH_LONG).show()
            viewModel.openSettings()
            return true
        }
        val sessionUrl = "${serverUrl.trimEnd('/')}/${Uri.encode(sessionId)}"
        if (!UrlPolicy(viewModel.uiState.value.settings.allowedHosts).isAllowed(sessionUrl)) {
            Toast.makeText(this, "Session URL is not allowlisted", Toast.LENGTH_LONG).show()
            return true
        }
        webView.loadUrl(sessionUrl)
        return true
    }

    @Composable
    private fun AppContent(
        onReload: () -> Unit,
        onOpenExternal: () -> Unit,
        onSaveSettings: (String) -> Unit,
        onResetSession: () -> Unit,
        onRequestExit: () -> Unit
    ) {
        val uiState by viewModel.uiState.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(uiState.pendingShareBanner) {
            val banner = uiState.pendingShareBanner ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(banner)
        }

        BackHandler {
            when {
                uiState.isSettingsVisible -> viewModel.closeSettings()
                webView.canGoBack() -> webView.goBack()
                else -> onRequestExit()
            }
        }

        MaterialTheme(colorScheme = HermesColorScheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    WebShell(
                        webView = webView,
                        isLoading = uiState.isLoading,
                        isOffline = uiState.isOffline,
                        errorMessage = uiState.errorMessage,
                        onRefresh = onReload,
                        onRetry = onReload,
                        onOpenExternal = onOpenExternal
                    )
                    SnackbarHost(hostState = snackbarHostState)
                }
            }

            if (uiState.isSettingsVisible) {
                ModalBottomSheet(onDismissRequest = { viewModel.closeSettings() }) {
                    SettingsBottomSheet(
                        initialServerUrl = uiState.settings.serverUrl,
                        onSave = onSaveSettings,
                        onResetSession = onResetSession,
                        onDismiss = { viewModel.closeSettings() }
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.userAgentString = settings.userAgentString + " Hermes-Android/0.1"
            disableWebViewDarkening(settings)

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    val popup = WebView(this@MainActivity)
                    popup.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val target = request?.url?.toString() ?: return true
                            handleNewWindowUrl(target)
                            popup.destroy()
                            return true
                        }

                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: android.graphics.Bitmap?
                        ) {
                            super.onPageStarted(view, url, favicon)
                            if (!url.isNullOrBlank()) {
                                handleNewWindowUrl(url)
                                popup.destroy()
                            }
                        }
                    }
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) return
                    runOnUiThread {
                        handleWebViewPermissionRequest(request)
                    }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                    super.onPermissionRequestCanceled(request)
                    if (pendingAudioPermissionRequest == request) {
                        pendingAudioPermissionRequest = null
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    if (filePathCallback == null) return false
                    val staged = viewModel.consumeSharedFileUris().map(Uri::parse)
                    if (staged.isNotEmpty()) {
                        filePathCallback.onReceiveValue(staged.toTypedArray())
                        viewModel.dismissShareBanner()
                        return true
                    }
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = filePathCallback
                    filePickerLauncher.launch(arrayOf("*/*"))
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString() ?: return true
                    if (matchesConfiguredDashboardRoute(target)) {
                        openDashboardInCustomTab(target)
                        return true
                    }
                    return when (UrlPolicy(allowedHosts).navigationDecision(target)) {
                        NavigationDecision.ALLOW_IN_WEBVIEW -> false
                        NavigationDecision.OPEN_IN_EXTERNAL_BROWSER -> {
                            openInExternalBrowser(target)
                            true
                        }
                        NavigationDecision.BLOCK -> true
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    viewModel.onPageStarted(url)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    applyHermesWebViewCompatibilityFixes(view, url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    applyHermesWebViewCompatibilityFixes(view, url)
                    viewModel.onPageFinished(
                        url = url,
                        rememberLastUrl = !matchesConfiguredDashboardRoute(url)
                    )
                    CookieManager.getInstance().flush()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame != true) return
                    val offline = isOfflineError(error?.errorCode)
                    val message = error?.description?.toString() ?: "Failed to load page"
                    viewModel.onPageError(message, offline)
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.cancel()
                    viewModel.onPageError("SSL validation failed for this page.", false)
                }
            }

            setDownloadListener(buildDownloadListener(this@MainActivity))
        }
    }

    private fun handleWebViewPermissionRequest(request: PermissionRequest) {
        val requestedResources = request.resources?.toSet().orEmpty()
        val audioOnly = requestedResources == setOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        if (!audioOnly || !isTrustedPermissionOrigin(request.origin)) {
            request.deny()
            return
        }

        if (hasRecordAudioPermission()) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            return
        }

        pendingAudioPermissionRequest?.deny()
        pendingAudioPermissionRequest = request
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isTrustedPermissionOrigin(origin: Uri?): Boolean {
        return origin != null && UrlPolicy(allowedHosts).isAllowed(origin.toString())
    }

    private fun disableWebViewDarkening(settings: WebSettings) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
        }
    }

    private fun applyHermesWebViewCompatibilityFixes(view: WebView?, url: String?) {
        if (view == null) return
        if (!matchesConfiguredWebUiRoute(url ?: viewModel.uiState.value.currentUrl)) return

        // Android WebView can report supported dynamic viewport units while computing them as 0px.
        // Hermes WebUI uses 100dvh for the root flex shell, so force the measured viewport height.
        view.evaluateJavascript(HermesWebViewViewportFixScript, null)
        view.evaluateJavascript(HermesWebUiMicrophoneFallbackScript, null)

        val dashboardUrl = viewModel.uiState.value.settings.dashboardUrl
        if (dashboardUrl.isNotBlank()) {
            view.evaluateJavascript(buildHermesDashboardConfigScript(dashboardUrl), null)
        }
    }

    private fun installHermesWebUiDocumentStartFixes(view: WebView, serverUrl: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val originRule = buildDocumentStartOriginRule(serverUrl) ?: return

        microphoneFallbackScriptHandler?.remove()
        microphoneFallbackScriptHandler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                HermesWebUiMicrophoneFallbackScript,
                setOf(originRule)
            )
        }.getOrNull()
    }

    private fun buildDocumentStartOriginRule(serverUrl: String): String? {
        val uri = runCatching { serverUrl.toUri() }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val hostRule = if (host.contains(":") && !host.startsWith("[")) {
            "[$host]"
        } else {
            host
        }
        val portRule = if (uri.port != -1) ":${uri.port}" else ""
        return "$scheme://$hostRule$portRule"
    }

    private fun buildHermesDashboardConfigScript(dashboardUrl: String): String {
        val quotedDashboardUrl = JSONObject.quote(dashboardUrl)
        return """
            (function() {
              var dashboardUrl = $quotedDashboardUrl;
              if (!dashboardUrl || !window.fetch) return;

              var normalize = function(value) {
                return String(value || '').trim().replace(/\/+${'$'}/, '');
              };
              var dashboardConfigUrl = '';
              try {
                var parsedDashboardUrl = new URL(dashboardUrl, window.location.href);
                parsedDashboardUrl.pathname = '';
                parsedDashboardUrl.search = '';
                parsedDashboardUrl.hash = '';
                dashboardConfigUrl = normalize(parsedDashboardUrl.toString());
              } catch (_) {
                return;
              }
              var targetUrl = dashboardConfigUrl;
              if (!targetUrl) return;

              var applyControls = function(config) {
                var modeEl = document.getElementById('settingsDashboardMode');
                var urlEl = document.getElementById('settingsDashboardUrl');
                if (modeEl && config && config.enabled) modeEl.value = config.enabled;
                if (urlEl) {
                  urlEl.value = (config && config.url) || dashboardConfigUrl;
                }
              };
              var refreshDashboardLink = function(config) {
                applyControls(config);
                if (typeof window.refreshDashboardStatus === 'function') {
                  try { window.refreshDashboardStatus(true); } catch (_) {}
                }
              };

              if (window.__hermesAndroidDashboardSeedComplete === targetUrl ||
                  window.__hermesAndroidDashboardSeedInFlight === targetUrl) {
                return;
              }
              window.__hermesAndroidDashboardSeedInFlight = targetUrl;

              var clearInFlight = function() {
                if (window.__hermesAndroidDashboardSeedInFlight === targetUrl) {
                  window.__hermesAndroidDashboardSeedInFlight = '';
                }
              };
              var markComplete = function() {
                window.__hermesAndroidDashboardSeedComplete = targetUrl;
                clearInFlight();
              };

              fetch('/api/dashboard/config', { credentials: 'same-origin' })
                .then(function(response) {
                  if (!response || !response.ok) throw new Error('dashboard config unavailable');
                  return response.json();
                })
                .then(function(config) {
                  var currentUrl = normalize(config && config.url);
                  if (currentUrl) {
                    refreshDashboardLink(config);
                    markComplete();
                    return null;
                  }
                  return fetch('/api/dashboard/config', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ enabled: 'always', url: dashboardConfigUrl })
                  }).then(function(response) {
                    if (!response || !response.ok) throw new Error('dashboard config save failed');
                    return response.json();
                  });
                })
                .then(function(saved) {
                  if (saved) {
                    refreshDashboardLink(saved);
                    markComplete();
                  }
                })
                .catch(function() {
                  clearInFlight();
                  applyControls({ enabled: 'always', url: dashboardConfigUrl });
                });
            })();
        """.trimIndent()
    }

    private fun matchesConfiguredWebUiRoute(url: String?): Boolean {
        val settings = viewModel.uiState.value.settings
        return hasSameOriginAs(url, settings.serverUrl) && !matchesConfiguredDashboardRoute(url)
    }

    private fun matchesConfiguredDashboardRoute(url: String?): Boolean {
        val dashboardUrl = viewModel.uiState.value.settings.dashboardUrl
        if (url.isNullOrBlank() || dashboardUrl.isBlank()) return false
        if (!hasSameOriginAs(url, dashboardUrl)) return false

        val targetPath = runCatching { url.toUri().path.orEmpty().trimEnd('/') }.getOrDefault("")
        val dashboardPath = runCatching { dashboardUrl.toUri().path.orEmpty().trimEnd('/') }.getOrDefault("")
        if (dashboardPath.isBlank()) return true
        return targetPath == dashboardPath || targetPath.startsWith("$dashboardPath/")
    }

    private fun handleNewWindowUrl(url: String) {
        if (matchesConfiguredDashboardRoute(url)) {
            openDashboardInCustomTab(url)
            return
        }
        when (UrlPolicy(allowedHosts).navigationDecision(url)) {
            NavigationDecision.ALLOW_IN_WEBVIEW -> webView.loadUrl(url)
            NavigationDecision.OPEN_IN_EXTERNAL_BROWSER -> openInExternalBrowser(url)
            NavigationDecision.BLOCK -> Unit
        }
    }

    private fun hasSameOriginAs(url: String?, baseUrl: String): Boolean {
        if (url.isNullOrBlank() || baseUrl.isBlank()) return false
        val target = runCatching { url.toUri() }.getOrNull() ?: return false
        val base = runCatching { baseUrl.toUri() }.getOrNull() ?: return false
        val targetScheme = target.scheme?.lowercase() ?: return false
        val baseScheme = base.scheme?.lowercase() ?: return false
        val targetHost = target.host?.lowercase() ?: return false
        val baseHost = base.host?.lowercase() ?: return false
        return targetScheme == baseScheme &&
            targetHost == baseHost &&
            effectivePort(target) == effectivePort(base)
    }

    private fun effectivePort(uri: Uri): Int {
        if (uri.port != -1) return uri.port
        return when (uri.scheme?.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
    }

    private fun buildDownloadListener(context: Context): DownloadListener {
        return DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val policy = UrlPolicy(allowedHosts)
            if (!policy.isAllowed(url)) {
                Toast.makeText(context, "Blocked download from non-allowlisted domain", Toast.LENGTH_LONG).show()
                return@DownloadListener
            }
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                setDescription("Downloading from Hermes")
                setAllowedOverMetered(true)
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                addRequestHeader("User-Agent", userAgent)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType))
            }
            val manager = context.getSystemService(DownloadManager::class.java)
            manager.enqueue(request)
            Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSettings(serverUrl: String) {
        if (!serverUrlValidator.isValid(serverUrl)) {
            Toast.makeText(this, "Server URL must be a valid https:// URL", Toast.LENGTH_LONG).show()
            return
        }
        val dashboardUrl = viewModel.uiState.value.settings.dashboardUrl
        if (dashboardUrl.isNotBlank() && !serverUrlValidator.isValid(dashboardUrl)) {
            Toast.makeText(this, "Dashboard URL must be blank or a valid https:// URL", Toast.LENGTH_LONG).show()
            return
        }
        viewModel.saveAppUrls(serverUrl, dashboardUrl)
        allowedHosts = viewModel.uiState.value.settings.allowedHosts
        installHermesWebUiDocumentStartFixes(webView, serverUrl)
        webView.loadUrl(serverUrl)
    }

    private fun resetWebSession() {
        viewModel.resetSession()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearHistory()
        webView.clearCache(true)
        webView.clearFormData()
        webView.loadUrl(viewModel.uiState.value.settings.serverUrl)
    }

    private fun openInExternalBrowser(url: String) {
        val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri())
        try {
            startActivity(browserIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser found to open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDashboardInCustomTab(url: String) {
        val colorParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(android.graphics.Color.rgb(13, 13, 26))
            .build()
        val customTabsIntent = CustomTabsIntent.Builder()
            .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
            .setDefaultColorSchemeParams(colorParams)
            .setShowTitle(false)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setUrlBarHidingEnabled(true)
            .build()
        // Keep the browser-rendered dashboard out of MainActivity's singleTask stack.
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            customTabsIntent.launchUrl(this, url.toUri())
        } catch (_: ActivityNotFoundException) {
            openInExternalBrowser(url)
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        val payload = ShareIntentParser().parse(intent) ?: return
        payload.fileUris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Ignore if provider does not support persistable permissions.
            }
        }
        payload.sharedText?.let { text ->
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@let
            clipboard.setPrimaryClip(ClipData.newPlainText("Hermes shared text", text))
        }
        viewModel.consumeSharePayload(payload)
    }


    private fun isOfflineError(errorCode: Int?): Boolean {
        if (errorCode == null) return !hasNetworkConnectivity()
        return errorCode == WebViewClient.ERROR_HOST_LOOKUP ||
            errorCode == WebViewClient.ERROR_CONNECT ||
            errorCode == WebViewClient.ERROR_TIMEOUT ||
            !hasNetworkConnectivity()
    }

    private fun hasNetworkConnectivity(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // TODO(phase-2): Add biometric gate before rendering WebView when app lock is enabled.
    // TODO(phase-2): Route FCM push notifications to deep links targeting Hermes sessions.
    // TODO(phase-2): Add camera capture support in file chooser and share pipeline.
}
