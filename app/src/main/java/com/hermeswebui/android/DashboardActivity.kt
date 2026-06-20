package com.hermeswebui.android

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

private val DashboardViewportFixScript = """
    (function() {
      var styleId = 'hermes-android-dashboard-viewport-fix';
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
          'html, body, #root { min-height: ' + px + ' !important; }',
          'body { overflow-y: auto !important; }',
          '.h-dvh, .h-screen { height: ' + px + ' !important; }',
          '.min-h-dvh, .min-h-screen { min-height: ' + px + ' !important; }',
          '[class*="h-dvh"], [class*="min-h-dvh"] { min-height: ' + px + ' !important; }'
        ].join('\n');
      };

      window.__hermesAndroidDashboardApplyViewportFix = applyViewportFix;
      applyViewportFix();

      if (!window.__hermesAndroidDashboardViewportFixInstalled) {
        window.__hermesAndroidDashboardViewportFixInstalled = true;
        window.addEventListener('resize', applyViewportFix, { passive: true });
        window.addEventListener('orientationchange', function() {
          window.setTimeout(applyViewportFix, 0);
          window.setTimeout(applyViewportFix, 250);
        }, { passive: true });
        if (window.visualViewport) {
          window.visualViewport.addEventListener('resize', applyViewportFix, { passive: true });
        }
        window.setTimeout(applyViewportFix, 0);
        window.setTimeout(applyViewportFix, 250);
        window.setTimeout(applyViewportFix, 1000);
      }
    })();
""".trimIndent()

class DashboardActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var dashboardUrl: String = ""
    private var currentUrl by mutableStateOf("")
    private var isLoading by mutableStateOf(true)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dashboardUrl = intent.getStringExtra(EXTRA_DASHBOARD_URL)?.trim().orEmpty()
        if (!isValidDashboardUrl(dashboardUrl)) {
            Toast.makeText(this, "Dashboard URL is invalid", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentUrl = dashboardUrl

        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)
        webView = buildDashboardWebView()

        setContent {
            DashboardContent(
                webView = webView,
                isLoading = isLoading,
                errorMessage = errorMessage,
                onRetry = { webView.loadUrl(currentUrl.ifBlank { dashboardUrl }) },
                onOpenExternal = { openInExternalBrowser(currentUrl.ifBlank { dashboardUrl }) },
                onClose = { finish() }
            )
        }

        webView.loadUrl(dashboardUrl)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildDashboardWebView(): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.useWideViewPort = true
            settings.userAgentString = settings.userAgentString + " Hermes-Android/0.1 Dashboard"
            disableWebViewDarkening(settings)

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString() ?: return true
                    return when {
                        hasSameOriginAs(target, dashboardUrl) -> false
                        isHttpsUrl(target) -> {
                            openInExternalBrowser(target)
                            true
                        }
                        else -> true
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isLoading = true
                    errorMessage = null
                    currentUrl = url ?: currentUrl
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    applyDashboardCompatibilityFixes(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    applyDashboardCompatibilityFixes(view)
                    isLoading = false
                    currentUrl = url ?: currentUrl
                    CookieManager.getInstance().flush()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame != true) return
                    isLoading = false
                    errorMessage = error?.description?.toString() ?: "Failed to load dashboard"
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.cancel()
                    isLoading = false
                    errorMessage = "SSL validation failed for this dashboard."
                }
            }
        }
    }

    private fun applyDashboardCompatibilityFixes(view: WebView?) {
        view?.evaluateJavascript(DashboardViewportFixScript, null)
    }

    private fun disableWebViewDarkening(settings: WebSettings) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
        }
    }

    @Composable
    private fun DashboardContent(
        webView: WebView,
        isLoading: Boolean,
        errorMessage: String?,
        onRetry: () -> Unit,
        onOpenExternal: () -> Unit,
        onClose: () -> Unit
    ) {
        BackHandler {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                onClose()
            }
        }

        MaterialTheme(colorScheme = DashboardColorScheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    DashboardTopBar(
                        currentUrl = currentUrl,
                        onOpenExternal = onOpenExternal,
                        onClose = onClose
                    )
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { webView }
                        )
                        if (isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x33000000)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        if (errorMessage != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Unable to open dashboard",
                                    style = MaterialTheme.typography.headlineSmall
                                )
                                Text(
                                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                                    text = errorMessage,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Button(onClick = onRetry) {
                                    Text("Retry")
                                }
                                TextButton(onClick = onOpenExternal) {
                                    Text("Open in browser")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DashboardTopBar(
        currentUrl: String,
        onOpenExternal: () -> Unit,
        onClose: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D0D1A))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hermes Dashboard",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = currentUrl.toUri().host.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onOpenExternal) {
                Text("Browser")
            }
            TextButton(onClick = onClose) {
                Text("Close")
            }
        }
    }

    private fun isValidDashboardUrl(url: String): Boolean {
        val parsed = runCatching { url.toUri() }.getOrNull() ?: return false
        return parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
    }

    private fun isHttpsUrl(url: String): Boolean {
        val parsed = runCatching { url.toUri() }.getOrNull() ?: return false
        return parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank()
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

    private fun openInExternalBrowser(url: String) {
        val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri())
        try {
            startActivity(browserIntent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser found to open link", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_DASHBOARD_URL = "com.hermeswebui.android.extra.DASHBOARD_URL"

        private val DashboardColorScheme = darkColorScheme(
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

        fun createIntent(context: Context, dashboardUrl: String): Intent {
            return Intent(context, DashboardActivity::class.java)
                .putExtra(EXTRA_DASHBOARD_URL, dashboardUrl)
        }
    }
}
