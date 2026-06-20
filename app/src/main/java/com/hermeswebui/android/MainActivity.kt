package com.hermeswebui.android

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.hermeswebui.android.core.security.NavigationDecision
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.domain.ServerUrlValidator
import com.hermeswebui.android.domain.ShareIntentParser
import com.hermeswebui.android.ui.MainViewModel
import com.hermeswebui.android.ui.MainViewModelFactory
import com.hermeswebui.android.ui.MainSurface
import com.hermeswebui.android.ui.settings.SettingsBottomSheet
import com.hermeswebui.android.ui.web.WebShell
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel
    private lateinit var webView: WebView

    private var allowedHosts: Set<String> = emptySet()
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val serverUrlValidator = ServerUrlValidator()

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
        viewModel.dismissShareBanner()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val defaultUrl = getString(R.string.default_server_url)
        val defaultDashboardTerminalUrl = getString(R.string.default_dashboard_terminal_url)
        val settingsRepository = SettingsRepository(applicationContext)
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(settingsRepository, defaultUrl, defaultDashboardTerminalUrl)
        )[MainViewModel::class.java]
        allowedHosts = viewModel.uiState.value.settings.allowedHosts

        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)
        webView = buildWebView()

        handleShareIntent(intent)

        setContent {
            AppContent(
                onReload = { webView.reload() },
                onOpenExternal = { openInExternalBrowser(viewModel.uiState.value.currentUrl) },
                onSaveSettings = { serverUrl, terminalUrl -> saveSettings(serverUrl, terminalUrl) },
                onResetSession = { resetWebSession() },
                onSelectSurface = { surface -> selectSurface(surface) },
                onRequestExit = { finish() }
            )
        }

        val settings = viewModel.uiState.value.settings
        if (!settings.isConfigured) {
            viewModel.openSettings()
        } else {
            val startUrl = settingsRepository.getLastLoadedUrl() ?: settings.serverUrl
            webView.loadUrl(startUrl)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    @Composable
    private fun AppContent(
        onReload: () -> Unit,
        onOpenExternal: () -> Unit,
        onSaveSettings: (String, String) -> Unit,
        onResetSession: () -> Unit,
        onSelectSurface: (MainSurface) -> Unit,
        onRequestExit: () -> Unit
    ) {
        val uiState by viewModel.uiState.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        LaunchedEffect(uiState.pendingShareBanner) {
            val banner = uiState.pendingShareBanner ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(banner)
        }

        BackHandler {
            when {
                uiState.isSettingsVisible -> viewModel.closeSettings()
                drawerState.isOpen -> scope.launch { drawerState.close() }
                webView.canGoBack() -> webView.goBack()
                else -> onRequestExit()
            }
        }

        MaterialTheme(colorScheme = HermesColorScheme) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    AppDrawer(
                        activeSurface = uiState.activeSurface,
                        webUiUrl = uiState.settings.serverUrl,
                        terminalUrl = uiState.settings.dashboardTerminalUrl,
                        onSelectSurface = { surface ->
                            scope.launch { drawerState.close() }
                            onSelectSurface(surface)
                        },
                        onOpenSettings = {
                            scope.launch { drawerState.close() }
                            viewModel.openSettings()
                        },
                        onResetSession = {
                            scope.launch { drawerState.close() }
                            onResetSession()
                        }
                    )
                }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    WebShell(
                        webView = webView,
                        isLoading = uiState.isLoading,
                        isOffline = uiState.isOffline,
                        errorMessage = uiState.errorMessage,
                        onRefresh = onReload,
                        onRetry = onReload,
                        onOpenExternal = onOpenExternal
                    )
                    Button(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                        onClick = { scope.launch { drawerState.open() } }
                    ) {
                        Text("Menu")
                    }
                    SnackbarHost(hostState = snackbarHostState)
                }
            }

            if (uiState.isSettingsVisible) {
                ModalBottomSheet(onDismissRequest = { viewModel.closeSettings() }) {
                    SettingsBottomSheet(
                        initialServerUrl = uiState.settings.serverUrl,
                        initialDashboardTerminalUrl = uiState.settings.dashboardTerminalUrl,
                        onSave = onSaveSettings,
                        onResetSession = onResetSession,
                        onDismiss = { viewModel.closeSettings() }
                    )
                }
            }
        }
    }

    @Composable
    private fun AppDrawer(
        activeSurface: MainSurface,
        webUiUrl: String,
        terminalUrl: String,
        onSelectSurface: (MainSurface) -> Unit,
        onOpenSettings: () -> Unit,
        onResetSession: () -> Unit
    ) {
        ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "Hermes", style = MaterialTheme.typography.headlineSmall)
                Text(text = webUiUrl, style = MaterialTheme.typography.bodySmall)
                DrawerButton(
                    text = "Chat",
                    selected = activeSurface == MainSurface.WEB_UI,
                    enabled = webUiUrl.isNotBlank(),
                    onClick = { onSelectSurface(MainSurface.WEB_UI) }
                )
                DrawerButton(
                    text = "Terminal",
                    selected = activeSurface == MainSurface.TERMINAL,
                    enabled = terminalUrl.isNotBlank(),
                    onClick = { onSelectSurface(MainSurface.TERMINAL) }
                )
                TextButton(onClick = onOpenSettings) {
                    Text("Settings")
                }
                TextButton(onClick = onResetSession) {
                    Text("Reset web session")
                }
            }
        }
    }

    @Composable
    private fun DrawerButton(
        text: String,
        selected: Boolean,
        enabled: Boolean,
        onClick: () -> Unit
    ) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = onClick
        ) {
            Text(if (selected) "$text *" else text)
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
            settings.userAgentString = settings.userAgentString + " Hermes-Android/0.1"
            disableWebViewDarkening(settings)

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            webChromeClient = object : WebChromeClient() {
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
                    applyHermesWebViewCompatibilityFixes(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    applyHermesWebViewCompatibilityFixes(view)
                    viewModel.onPageFinished(url)
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

    private fun disableWebViewDarkening(settings: WebSettings) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
        }
    }

    private fun applyHermesWebViewCompatibilityFixes(view: WebView?) {
        // Android WebView can report supported dynamic viewport units while computing them as 0px.
        // Hermes WebUI uses 100dvh for the root flex shell, so force the measured viewport height.
        view?.evaluateJavascript(HermesWebViewViewportFixScript, null)
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

    private fun saveSettings(serverUrl: String, dashboardTerminalUrl: String) {
        if (!serverUrlValidator.isValid(serverUrl)) {
            Toast.makeText(this, "Server URL must be a valid https:// URL", Toast.LENGTH_LONG).show()
            return
        }
        if (dashboardTerminalUrl.isNotBlank() && !serverUrlValidator.isValid(dashboardTerminalUrl)) {
            Toast.makeText(this, "Terminal URL must be blank or a valid https:// URL", Toast.LENGTH_LONG).show()
            return
        }
        viewModel.saveAppUrls(serverUrl, dashboardTerminalUrl)
        allowedHosts = viewModel.uiState.value.settings.allowedHosts
        webView.loadUrl(serverUrl)
    }

    private fun selectSurface(surface: MainSurface) {
        val settings = viewModel.uiState.value.settings
        val targetUrl = when (surface) {
            MainSurface.WEB_UI -> settings.serverUrl
            MainSurface.TERMINAL -> settings.dashboardTerminalUrl
        }
        if (targetUrl.isBlank()) {
            Toast.makeText(this, "Configure a terminal URL in Settings first", Toast.LENGTH_LONG).show()
            viewModel.openSettings()
            return
        }
        if (!UrlPolicy(settings.allowedHosts).isAllowed(targetUrl)) {
            Toast.makeText(this, "Target URL is not allowlisted", Toast.LENGTH_LONG).show()
            return
        }
        viewModel.selectSurface(surface, targetUrl)
        webView.loadUrl(targetUrl)
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
