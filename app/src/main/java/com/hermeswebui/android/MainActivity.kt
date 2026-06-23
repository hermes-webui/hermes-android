package com.hermeswebui.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerController
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hermeswebui.android.core.security.NavigationDecision
import com.hermeswebui.android.core.security.UrlOrigins
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.domain.ServerUrlValidator
import com.hermeswebui.android.domain.ShareIntentParser
import com.hermeswebui.android.ui.MainViewModel
import com.hermeswebui.android.ui.MainViewModelFactory
import com.hermeswebui.android.ui.settings.SettingsBottomSheet
import com.hermeswebui.android.ui.web.WebShell
import org.json.JSONObject

private const val HermesNotificationBridgeName = "HermesAndroidNotifications"
private const val HermesNotificationChannelId = "hermes_webui_notifications"
private const val HermesNotificationIdBase = 10_000
private const val ActionOpenNotificationUrl = "com.hermeswebui.android.OPEN_NOTIFICATION_URL"
private const val ExtraNotificationUrl = "com.hermeswebui.android.extra.NOTIFICATION_URL"

private data class NotificationPermissionReply(
    val id: String?,
    val replyProxy: JavaScriptReplyProxy
)

private val HermesDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFD700),
    onPrimary = Color(0xFF16110A),
    primaryContainer = Color(0xFF4A3800),
    onPrimaryContainer = Color(0xFFFFDF6B),
    secondary = Color(0xFF4DD0E1),
    onSecondary = Color(0xFF061417),
    background = Color(0xFF0D0D1A),
    onBackground = Color(0xFFFFF8DC),
    surface = Color(0xFF141425),
    onSurface = Color(0xFFFFF8DC),
    surfaceVariant = Color(0xFF1A1A2E),
    onSurfaceVariant = Color(0xFFE6E0C8),
    outline = Color(0xFF5A5A7A),
    outlineVariant = Color(0xFF3A3A55),
    error = Color(0xFFEF5350),
    onError = Color(0xFF1F0505)
)

private val HermesLightColorScheme = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDF6B),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF006874),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFBF0),
    onBackground = Color(0xFF1C1B00),
    surface = Color(0xFFFFF8F2),
    onSurface = Color(0xFF1C1B00),
    surfaceVariant = Color(0xFFEEEAD8),
    onSurfaceVariant = Color(0xFF4B4737),
    outline = Color(0xFF7C7866),
    outlineVariant = Color(0xFFCFC9B6),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
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
        // Hermes WebUI floating menus cap their height with `max-height: calc(100vh - 16px)`
        // and the generated update-summary panel uses `max-height: min(34vh, 260px)`.
        // Android System WebView evaluates these viewport units as 0 here (same quirk it can
        // apply to `100dvh`), so those panels collapse to a tiny sliver with the content
        // scrolled out of view. Re-cap them with the measured viewport height instead.
        var menuMax = Math.max(120, Math.round(height) - 16) + 'px';
        var updateSummaryMax = Math.max(120, Math.min(260, Math.round(height * 0.34))) + 'px';
        var style = document.getElementById(styleId);
        if (!style) {
          style = document.createElement('style');
          style.id = styleId;
          (document.head || document.documentElement).appendChild(style);
        }
        // Keep vertical scrolling available. Some WebUI panels (for example the generated
        // update summary/details block) expand below the fold and become unusable if body
        // scrolling is locked with `overflow: hidden`.
        style.textContent = [
          'html, body { height: ' + px + ' !important; min-height: ' + px + ' !important; }',
          'body { overflow-x: hidden !important; }',
          '.layout, .rail, .sidebar, .main, .rightpanel, #sessionList, .messages { min-height: 0 !important; }',
          '.session-action-menu, .workspace-prefs-menu { max-height: ' + menuMax + ' !important; }',
          '#updateSummaryPanel { max-height: ' + updateSummaryMax + ' !important; overflow-y: auto !important; }'
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

      // Some Android WebView builds expose SpeechRecognition but fail with not-allowed.
      // Hide these constructors so Hermes WebUI consistently uses MediaRecorder/getUserMedia.
      var disableSpeechConstructor = function(name) {
        try {
          Object.defineProperty(window, name, {
            configurable: true,
            get: function() { return undefined; },
            set: function(_) {}
          });
        } catch (_) {
          try { window[name] = undefined; } catch (_) {}
        }
      };

      disableSpeechConstructor('SpeechRecognition');
      disableSpeechConstructor('webkitSpeechRecognition');
      try { window.__hermesAndroidMicForceMediaRecorder = true; } catch (_) {}

      // Android WebView can fail to open microphone streams when a specific input
      // deviceId/groupId constraint is requested. Fall back to default mic capture.
      try {
        if (navigator.mediaDevices && typeof navigator.mediaDevices.getUserMedia === 'function' &&
            !navigator.mediaDevices.__hermesAndroidWrappedGetUserMedia) {
          var originalGetUserMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
          var sanitizeAudioConstraints = function(audio) {
            if (audio === true || audio === false || audio == null) return audio;
            if (typeof audio !== 'object') return true;

            var clone = {};
            for (var key in audio) {
              if (!Object.prototype.hasOwnProperty.call(audio, key)) continue;
              if (key === 'deviceId' || key === 'groupId') continue;
              clone[key] = audio[key];
            }
            return Object.keys(clone).length ? clone : true;
          };

          navigator.mediaDevices.getUserMedia = function(constraints) {
            var next = constraints;
            try {
              if (constraints && typeof constraints === 'object' && constraints.audio) {
                next = {};
                for (var key in constraints) {
                  if (Object.prototype.hasOwnProperty.call(constraints, key)) {
                    next[key] = constraints[key];
                  }
                }
                next.audio = sanitizeAudioConstraints(constraints.audio);
              }
            } catch (_) {}
            return originalGetUserMedia(next);
          };
          navigator.mediaDevices.__hermesAndroidWrappedGetUserMedia = true;
          try { window.__hermesAndroidSanitizeAudioConstraints = true; } catch (_) {}
        }
      } catch (_) {}
     })();
""".trimIndent()

private val HermesWebUiAppSettingsEntryScript = """
    (function() {
      var appSettingsHref = 'hermes://app/settings';
      var markerAttr = 'data-hermes-android-app-settings-entry';

      var textContainsHelp = function(value) {
        var normalized = String(value || '').trim().toLowerCase();
        return normalized === 'help' || normalized.indexOf('help ') === 0 || normalized.indexOf(' help') !== -1;
      };

      var clearActiveState = function(root) {
        if (!root || !root.querySelectorAll) return;
        root.querySelectorAll('[aria-current], [aria-selected], .active, .selected, .is-active').forEach(function(el) {
          el.removeAttribute('aria-current');
          el.removeAttribute('aria-selected');
          el.classList.remove('active');
          el.classList.remove('selected');
          el.classList.remove('is-active');
        });
      };

      var stripRoutingAttributes = function(root) {
        if (!root || !root.querySelectorAll) return;
        var routeAttrPattern = /(route|router|nav|href|path|url|target|rel|onclick)/i;
        root.querySelectorAll('*').forEach(function(el) {
          var names = [];
          for (var i = 0; i < el.attributes.length; i++) {
            names.push(el.attributes[i].name);
          }
          names.forEach(function(name) {
            if (routeAttrPattern.test(name)) {
              el.removeAttribute(name);
            }
          });
        });
      };

      var bindNativeSettingsClick = function(el) {
        if (!el) return;
        var openNativeSettings = function(event) {
          if (event) {
            event.preventDefault();
            event.stopPropagation();
            if (typeof event.stopImmediatePropagation === 'function') {
              event.stopImmediatePropagation();
            }
          }
          window.location.href = appSettingsHref;
          return false;
        };
        el.addEventListener('click', openNativeSettings, true);
        el.addEventListener('auxclick', openNativeSettings, true);
        el.addEventListener('keydown', function(event) {
          if (event.key === 'Enter' || event.key === ' ') {
            openNativeSettings(event);
          }
        }, true);
      };

      var createAppIconSvg = function(className) {
        var svgNs = 'http://www.w3.org/2000/svg';
        var svg = document.createElementNS(svgNs, 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '1.8');
        svg.setAttribute('stroke-linecap', 'round');
        svg.setAttribute('stroke-linejoin', 'round');
        svg.setAttribute('aria-hidden', 'true');
        if (className) svg.setAttribute('class', className);

        var frame = document.createElementNS(svgNs, 'rect');
        frame.setAttribute('x', '7');
        frame.setAttribute('y', '2');
        frame.setAttribute('width', '10');
        frame.setAttribute('height', '20');
        frame.setAttribute('rx', '2.5');
        frame.setAttribute('ry', '2.5');

        var speaker = document.createElementNS(svgNs, 'line');
        speaker.setAttribute('x1', '10');
        speaker.setAttribute('y1', '5');
        speaker.setAttribute('x2', '14');
        speaker.setAttribute('y2', '5');

        var home = document.createElementNS(svgNs, 'circle');
        home.setAttribute('cx', '12');
        home.setAttribute('cy', '18');
        home.setAttribute('r', '1');

        svg.appendChild(frame);
        svg.appendChild(speaker);
        svg.appendChild(home);
        return svg;
      };

      var applyApplicationIcon = function(interactive) {
        if (!interactive) return;
        var existing = interactive.querySelector('svg, i, [data-icon], [class*="icon"]');
        var className = '';
        if (existing && existing.getAttribute) {
          className = existing.getAttribute('class') || '';
        }
        var appIcon = createAppIconSvg(className);
        appIcon.setAttribute(markerAttr, 'icon');

        if (existing && existing.parentNode) {
          existing.parentNode.replaceChild(appIcon, existing);
        } else {
          interactive.insertBefore(appIcon, interactive.firstChild);
        }
      };

      var replaceHelpLabel = function(root) {
        var changed = false;
        var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
        var node = walker.nextNode();
        while (node) {
          if (textContainsHelp(node.nodeValue)) {
            node.nodeValue = 'Application Settings';
            changed = true;
          }
          node = walker.nextNode();
        }
        return changed;
      };

      var forceVisibleLabel = function(root) {
        if (!root || !root.querySelectorAll) return;
        var changed = false;
        root.querySelectorAll('span, p, div, strong, em').forEach(function(el) {
          if (textContainsHelp(el.textContent)) {
            el.textContent = 'Application Settings';
            changed = true;
          }
        });
        if (changed) return;
        var fallback = document.createElement('span');
        fallback.textContent = 'Application Settings';
        fallback.setAttribute(markerAttr, 'label');
        root.appendChild(fallback);
      };

      var findHelpInScope = function(scope) {
        if (!scope || !scope.querySelectorAll) return null;
        var nodes = scope.querySelectorAll('a, button, [role="button"], [role="menuitem"]');
        for (var i = 0; i < nodes.length; i++) {
          var node = nodes[i];
          if (textContainsHelp(node.textContent)) return node;
          var aria = node.getAttribute('aria-label');
          if (textContainsHelp(aria)) return node;
          var title = node.getAttribute('title');
          if (textContainsHelp(title)) return node;
        }
        return null;
      };

      var findHelpInteractive = function() {
        var scopedSelectors = ['.sidebar', '.rail', '.leftpanel', 'aside', 'nav'];
        for (var i = 0; i < scopedSelectors.length; i++) {
          var scope = document.querySelector(scopedSelectors[i]);
          var hit = findHelpInScope(scope);
          if (hit) return hit;
        }
        return findHelpInScope(document);
      };

      var ensureEntry = function() {
        try {
          if (document.querySelector('[' + markerAttr + '="1"]')) return;
          var helpInteractive = findHelpInteractive();
          if (!helpInteractive) return;

          var helpContainer = helpInteractive.closest('li, [role="menuitem"], .menu-item, .nav-item, .sidebar-item, [data-menu-item]') || helpInteractive;
          if (!helpContainer || !helpContainer.parentNode) return;

          var cloned = helpContainer.cloneNode(false);
          cloned.setAttribute(markerAttr, '1');
          cloned.removeAttribute('id');
          clearActiveState(cloned);
          stripRoutingAttributes(cloned);

          var helpIsInteractive = helpContainer.matches('a, button, [role="button"], [role="menuitem"]');
          var interactive = helpIsInteractive ? cloned : helpInteractive.cloneNode(false);
          if (!helpIsInteractive) {
            interactive.removeAttribute('id');
            clearActiveState(interactive);
            stripRoutingAttributes(interactive);
            cloned.appendChild(interactive);
          }

          if (!interactive) return;

          interactive.textContent = '';
          var label = document.createElement('span');
          label.textContent = 'Application Settings';
          label.setAttribute(markerAttr, 'label');
          interactive.appendChild(label);
          applyApplicationIcon(interactive);

          if (interactive.tagName && interactive.tagName.toLowerCase() === 'a') {
            interactive.setAttribute('href', appSettingsHref);
          } else {
            interactive.setAttribute('role', 'link');
            interactive.setAttribute('tabindex', '0');
          }
          bindNativeSettingsClick(interactive);
          interactive.setAttribute('aria-label', 'Application Settings');
          interactive.setAttribute('title', 'Application Settings');
          interactive.removeAttribute('aria-current');
          interactive.removeAttribute('aria-selected');
          interactive.classList.remove('active');
          interactive.classList.remove('selected');
          interactive.classList.remove('is-active');
          interactive.setAttribute(markerAttr, '1');
          bindNativeSettingsClick(cloned);

          helpContainer.parentNode.insertBefore(cloned, helpContainer.nextSibling);
        } catch (_) {}
      };

      ensureEntry();

      if (!window.__hermesAndroidAppSettingsEntryInstalled) {
        window.__hermesAndroidAppSettingsEntryInstalled = true;
        var observer = new MutationObserver(function() { ensureEntry(); });
        observer.observe(document.documentElement || document.body, { childList: true, subtree: true });
        window.addEventListener('pageshow', ensureEntry, { passive: true });
        window.addEventListener('focus', ensureEntry, { passive: true });
      }
    })();
""".trimIndent()



@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel
    private lateinit var webView: WebView
    private lateinit var settingsRepository: SettingsRepository

    private var urlPolicy = UrlPolicy(emptySet())
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingAudioPermissionRequest: PermissionRequest? = null
    private val pendingNotificationPermissionReplies = mutableListOf<NotificationPermissionReply>()
    private var notificationPermissionRequestInFlight = false
    private var microphoneFallbackScriptHandler: ScriptHandler? = null
    private var notificationBridgeScriptHandler: ScriptHandler? = null
    private var routeRecoveryScriptHandler: ScriptHandler? = null
    private var appSettingsEntryScriptHandler: ScriptHandler? = null

    private var activeOAuthPopup: WebView? = null
    private var oauthFlowTimeoutMs: Long = 0
    private val OAUTH_FLOW_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

    private val serverUrlValidator = ServerUrlValidator()

    private var lastBackPressTime: Long = 0
    private val BACK_PRESS_TIMEOUT_MS = 2000L // 2 seconds

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        filePathCallback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
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

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationPermissionRequestInFlight = false
            val permission = if (granted && areNativeNotificationsEnabled()) {
                "granted"
            } else {
                "denied"
            }
            flushNotificationPermissionReplies(permission)
            updateWebNotificationPermissionState(permission)
            if (!granted) {
                Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val defaultUrl = getString(R.string.default_server_url)
        val defaultDashboardUrl = getString(R.string.default_dashboard_url)
        settingsRepository = SettingsRepository(applicationContext)
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(settingsRepository, settingsRepository, defaultUrl, defaultDashboardUrl)
        )[MainViewModel::class.java]
        urlPolicy = UrlPolicy(viewModel.uiState.value.settings.allowedHosts)

        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)
        ensureNotificationChannel()
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
            val notificationUrl = notificationTargetUrl(intent)
            val startUrl = notificationUrl ?: if (matchesConfiguredDashboardRoute(lastLoadedUrl)) {
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
        if (handleNotificationIntent(intent)) {
            return
        }
        if (!handleDeepLink(intent)) {
            handleShareIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            updateWebNotificationPermissionState()
            viewModel.resumeAutoRetryIfNeeded()
        }
    }

    override fun onPause() {
        super.onPause()
        // If OAuth flow has timed out, clean up the popup to prevent resource leaks.
        cleanupExpiredOAuthPopup()
    }

    override fun onStop() {
        super.onStop()
        // Avoid background polling; restart on resume if the error screen is still active.
        viewModel.cancelAutoRetry()
        // Clean up any lingering OAuth popup on app stop.
        cleanupExpiredOAuthPopup()
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
        if (!urlPolicy.isAllowed(sessionUrl)) {
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
        val serverProfiles by viewModel.serverProfiles.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(uiState.pendingShareBanner) {
            val banner = uiState.pendingShareBanner ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(banner)
        }

        // Auto-reload when the retry loop detects the server is back.
        LaunchedEffect(Unit) {
            viewModel.autoReloadEvent.collect {
                webView.reload()
            }
        }

        BackHandler {
            when {
                uiState.isSettingsVisible -> viewModel.closeSettings()
                webView.canGoBack() -> webView.goBack()
                else -> {
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastBackPressTime < BACK_PRESS_TIMEOUT_MS) {
                        // Second back press within timeout: exit app
                        onRequestExit()
                    } else {
                        // First back press: show toast and reset timer
                        lastBackPressTime = currentTime
                        Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        val isDark = isSystemInDarkTheme()
        val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ctx = LocalContext.current
            if (isDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            if (isDark) HermesDarkColorScheme else HermesLightColorScheme
        }
        MaterialTheme(colorScheme = colorScheme) {
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
                        hasLoadedContent = uiState.hasLoadedContent,
                        isOffline = uiState.isOffline,
                        isReconnecting = uiState.isReconnecting,
                        errorMessage = uiState.errorMessage,
                        onRefresh = onReload,
                        onRetry = {
                            viewModel.cancelAutoRetry()
                            onReload()
                        },
                        onOpenExternal = onOpenExternal,
                        onOpenSettings = { viewModel.openSettings() },
                        onBack = if (webView.canGoBack()) {{ webView.goBack() }} else null
                    )
                    SnackbarHost(hostState = snackbarHostState)
                }
            }

            if (uiState.isSettingsVisible) {
                ModalBottomSheet(
                    onDismissRequest = { viewModel.closeSettings() },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    SettingsBottomSheet(
                        initialServerUrl = uiState.settings.serverUrl,
                        isConfigured = uiState.settings.isConfigured,
                        onSave = onSaveSettings,
                        onResetSession = onResetSession,
                        onDismiss = { viewModel.closeSettings() },
                        serverProfiles = serverProfiles,
                        onAddProfile = { name, url -> handleAddServerProfile(name, url) },
                        onDeleteProfile = { profileId -> handleDeleteServerProfile(profileId) },
                        onRenameProfile = { profileId, newName -> viewModel.renameServerProfile(profileId, newName) },
                        onEditProfile = { profileId, newName, newUrl -> handleEditServerProfile(profileId, newName, newUrl) },
                        onSwitchProfile = { profileId -> handleSwitchServerProfile(profileId) }
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            configureWebViewStorageAndCache(settings)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.userAgentString = "${settings.userAgentString} Hermes-Android/${appVersionName()}"
            disableWebViewDarkening(settings)
            installHermesNotificationWebMessageBridge(this)
            isLongClickable = false
            setOnLongClickListener { true }

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
                            if (handleAppSettingsNavigation(target)) {
                                popup.destroy()
                                return true
                            }

                            // If this is an OAuth/OIDC URL, keep the popup alive to preserve PKCE state.
                            // OAuth flows require multiple redirects with persistent cookies.
                            if (isOAuthAuthorizationUrl(target)) {
                                activeOAuthPopup = popup
                                oauthFlowTimeoutMs = System.currentTimeMillis() + OAUTH_FLOW_TIMEOUT_MS
                                // Load the OAuth URL in the popup; let it complete the auth flow.
                                view?.loadUrl(target)
                                return true
                            }

                            // If this is an OAuth callback completion, destroy popup and handle result in main WebView.
                            if (isOAuthCallbackUrl(target)) {
                                activeOAuthPopup = null
                                handleNewWindowUrl(target)
                                popup.destroy()
                                return true
                            }

                            // Non-OAuth redirects: handle as before and destroy popup.
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
                            if (url.isNullOrBlank()) return

                            // Keep popup alive if this is part of OAuth flow (e.g., auth provider login page).
                            if (isOAuthRelatedUrl(url)) {
                                activeOAuthPopup = popup
                                oauthFlowTimeoutMs = System.currentTimeMillis() + OAUTH_FLOW_TIMEOUT_MS
                                return
                            }

                            // Non-OAuth flow: redirect to main WebView and destroy popup.
                            handleNewWindowUrl(url)
                            popup.destroy()
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
                    if (handleAppSettingsNavigation(target)) return true
                    if (matchesConfiguredDashboardRoute(target)) {
                        openDashboardInCustomTab(target)
                        return true
                    }
                    return when (urlPolicy.navigationDecision(target)) {
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
                    viewModel.onPageCommitVisible(url)
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

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    val visitedUrl = url?.takeIf { it.isNotBlank() } ?: return
                    // Ignore internal/non-web URLs; persist only trusted Hermes WebUI routes.
                    if (!urlPolicy.isAllowed(visitedUrl)) return
                    if (!matchesConfiguredWebUiRoute(visitedUrl)) return

                    // Persist SPA/history route changes so cold starts restore the active session route.
                    viewModel.onUrlVisited(url = visitedUrl, rememberLastUrl = true)
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
        val requestsAudio = requestedResources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val trustedOrigin = isTrustedPermissionOrigin(request.origin)
        if (!requestsAudio || !trustedOrigin) {
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

    private fun installHermesNotificationWebMessageBridge(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return

        runCatching {
            WebViewCompat.addWebMessageListener(
                view,
                HermesNotificationBridgeName,
                setOf("*")
            ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
                runOnUiThread {
                    handleHermesNotificationBridgeMessage(
                        message = message,
                        sourceOrigin = sourceOrigin,
                        isMainFrame = isMainFrame,
                        replyProxy = replyProxy
                    )
                }
            }
        }
    }

    private fun handleHermesNotificationBridgeMessage(
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        val parsed = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull()
        val id = parsed?.optString("id")?.takeIf { it.isNotBlank() }
        if (parsed == null || !isTrustedNotificationBridgeSource(sourceOrigin, isMainFrame)) {
            postNotificationBridgeReply(replyProxy, id, ok = false, permission = "denied")
            return
        }

        when (parsed.optString("type")) {
            "permissionState" -> {
                postNotificationBridgeReply(
                    replyProxy = replyProxy,
                    id = id,
                    ok = true,
                    permission = webNotificationPermissionState()
                )
            }
            "requestPermission" -> requestHermesNotificationPermission(replyProxy, id)
            "show" -> {
                val shown = showHermesNotification(parsed.optJSONObject("payload") ?: JSONObject())
                postNotificationBridgeReply(
                    replyProxy = replyProxy,
                    id = id,
                    ok = shown,
                    permission = webNotificationPermissionState()
                )
            }
            else -> postNotificationBridgeReply(
                replyProxy = replyProxy,
                id = id,
                ok = false,
                permission = webNotificationPermissionState()
            )
        }
    }

    private fun isTrustedNotificationBridgeSource(sourceOrigin: Uri, isMainFrame: Boolean): Boolean {
        if (!isMainFrame) return false
        val normalizedOrigin = normalizePermissionOrigin(sourceOrigin) ?: sourceOrigin.toString()
        return matchesConfiguredWebUiRoute(normalizedOrigin) && matchesConfiguredWebUiRoute(webView.url)
    }

    private fun requestHermesNotificationPermission(replyProxy: JavaScriptReplyProxy, id: String?) {
        val currentPermission = webNotificationPermissionState()
        if (currentPermission == "granted") {
            postNotificationBridgeReply(replyProxy, id, ok = true, permission = "granted")
            return
        }
        if (currentPermission == "denied") {
            postNotificationBridgeReply(replyProxy, id, ok = false, permission = "denied")
            Toast.makeText(this, "Enable app notifications in Android settings", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            postNotificationBridgeReply(replyProxy, id, ok = false, permission = currentPermission)
            return
        }

        settingsRepository.markNotificationPermissionRequested()
        pendingNotificationPermissionReplies += NotificationPermissionReply(id, replyProxy)
        if (!notificationPermissionRequestInFlight) {
            notificationPermissionRequestInFlight = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun postNotificationBridgeReply(
        replyProxy: JavaScriptReplyProxy,
        id: String?,
        ok: Boolean,
        permission: String,
        error: String? = null
    ) {
        val response = JSONObject()
            .put("id", id ?: "")
            .put("ok", ok)
            .put("permission", permission)
        if (!error.isNullOrBlank()) {
            response.put("error", error)
        }
        runCatching {
            replyProxy.postMessage(response.toString())
        }
    }

    private fun flushNotificationPermissionReplies(permission: String) {
        val replies = pendingNotificationPermissionReplies.toList()
        pendingNotificationPermissionReplies.clear()
        replies.forEach { pending ->
            postNotificationBridgeReply(
                replyProxy = pending.replyProxy,
                id = pending.id,
                ok = permission == "granted",
                permission = permission
            )
        }
    }

    private fun webNotificationPermissionState(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasRuntimePermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            return when {
                hasRuntimePermission && areNativeNotificationsEnabled() -> "granted"
                settingsRepository.hasRequestedNotificationPermission() -> "denied"
                else -> "default"
            }
        }

        return if (areNativeNotificationsEnabled()) "granted" else "denied"
    }

    private fun areNativeNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            HermesNotificationChannelId,
            "Hermes updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Task completion and attention notifications from Hermes WebUI"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    private fun showHermesNotification(payload: JSONObject): Boolean {
        if (webNotificationPermissionState() != "granted") return false

        val options = payload.optJSONObject("options") ?: JSONObject()
        val title = payload.optString("title")
            .takeIf { it.isNotBlank() }
            ?: getString(R.string.app_name)
        val body = options.optString("body").takeIf { it.isNotBlank() }
        val tag = options.optString("tag").takeIf { it.isNotBlank() }
        val data = options.optJSONObject("data")
        val targetUrl = data
            ?.optString("url")
            ?.takeIf { isTrustedNotificationTarget(it) }
            ?: viewModel.uiState.value.currentUrl.takeIf { isTrustedNotificationTarget(it) }

        val pendingIntent = targetUrl?.let { buildNotificationPendingIntent(it, tag) }
        val notification = NotificationCompat.Builder(this, HermesNotificationChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body ?: title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body ?: title))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(ContextCompat.getColor(this, R.color.brand_sky))
            .apply {
                if (pendingIntent != null) {
                    setContentIntent(pendingIntent)
                }
            }
            .build()

        val notificationId = HermesNotificationIdBase + ((tag ?: title).hashCode() and 0x0FFFFFFF)
        NotificationManagerCompat.from(this).notify(tag, notificationId, notification)
        return true
    }

    private fun buildNotificationPendingIntent(targetUrl: String, tag: String?): PendingIntent {
        val requestCode = (tag ?: targetUrl).hashCode()
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ActionOpenNotificationUrl
            putExtra(ExtraNotificationUrl, targetUrl)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun handleNotificationIntent(intent: Intent?): Boolean {
        val targetUrl = notificationTargetUrl(intent) ?: return false
        webView.loadUrl(targetUrl)
        return true
    }

    private fun notificationTargetUrl(intent: Intent?): String? {
        if (intent?.action != ActionOpenNotificationUrl) return null
        return intent.getStringExtra(ExtraNotificationUrl)
            ?.takeIf { isTrustedNotificationTarget(it) }
    }

    private fun isTrustedNotificationTarget(url: String?): Boolean {
        return !url.isNullOrBlank() && urlPolicy.isAllowed(url) && matchesConfiguredWebUiRoute(url)
    }

    private fun updateWebNotificationPermissionState(permission: String = webNotificationPermissionState()) {
        if (!matchesConfiguredWebUiRoute(webView.url)) return
        val quotedPermission = JSONObject.quote(permission)
        webView.evaluateJavascript(
            "window.__hermesAndroidSetNotificationPermission && window.__hermesAndroidSetNotificationPermission($quotedPermission);",
            null
        )
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isTrustedPermissionOrigin(origin: Uri?): Boolean {
        if (origin != null) {
            val raw = origin.toString()
            if (urlPolicy.isAllowed(raw)) {
                return true
            }

            val normalized = normalizePermissionOrigin(origin)
            if (normalized != null && urlPolicy.isAllowed(normalized)) {
                return true
            }

            if (origin.host.isNullOrBlank() && matchesConfiguredWebUiRoute(webView.url)) {
                // Some Android WebView builds surface opaque/null-like iframe origins for same-page mic requests.
                return true
            }

            return false
        }

        return matchesConfiguredWebUiRoute(webView.url)
    }

    private fun normalizePermissionOrigin(origin: Uri): String? {
        val scheme = origin.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = origin.host
            ?.trim()
            ?.trimEnd('.')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val portSuffix = if (origin.port != -1) ":${origin.port}" else ""
        return "$scheme://$host$portSuffix"
    }

    private fun disableWebViewDarkening(settings: WebSettings) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
        }
    }

    private fun configureWebViewStorageAndCache(settings: WebSettings) {
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        ServiceWorkerController.getInstance().serviceWorkerWebSettings.apply {
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = false
            allowFileAccess = false
            blockNetworkLoads = false
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    private fun applyHermesWebViewCompatibilityFixes(view: WebView?, url: String?) {
        if (view == null) return
        if (!matchesConfiguredWebUiRoute(url ?: viewModel.uiState.value.currentUrl)) return

        // Android WebView can report supported dynamic viewport units while computing them as 0px.
        // Hermes WebUI uses 100dvh for the root flex shell (and 100vh max-height for floating
        // menus), so force the measured viewport height for both.
        view.evaluateJavascript(HermesWebViewViewportFixScript, null)
        view.evaluateJavascript(HermesWebUiMicrophoneFallbackScript, null)
        view.evaluateJavascript(buildHermesWebUiNotificationBridgeScript(), null)
        view.evaluateJavascript(buildHermesWebUiRouteRecoveryScript(), null)
        view.evaluateJavascript(HermesWebUiAppSettingsEntryScript, null)
    }


    private fun installHermesWebUiDocumentStartFixes(view: WebView, serverUrl: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val originRule = UrlOrigins.documentStartOriginRule(serverUrl) ?: return

        microphoneFallbackScriptHandler?.remove()
        notificationBridgeScriptHandler?.remove()
        routeRecoveryScriptHandler?.remove()
        appSettingsEntryScriptHandler?.remove()
        microphoneFallbackScriptHandler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                HermesWebUiMicrophoneFallbackScript,
                setOf(originRule)
            )
        }.getOrNull()
        notificationBridgeScriptHandler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                buildHermesWebUiNotificationBridgeScript(),
                setOf(originRule)
            )
        }.getOrNull()
        routeRecoveryScriptHandler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                buildHermesWebUiRouteRecoveryScript(),
                setOf(originRule)
            )
        }.getOrNull()
        appSettingsEntryScriptHandler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                HermesWebUiAppSettingsEntryScript,
                setOf(originRule)
            )
        }.getOrNull()
    }

    private fun handleAppSettingsNavigation(url: String?): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (parsed.scheme != "hermes") return false
        if (parsed.host != "app") return false
        val path = parsed.path?.trimEnd('/')
        if (path != "/settings") return false
        viewModel.openSettings()
        return true
    }

    private fun buildHermesWebUiRouteRecoveryScript(): String {
        val lastUrl = settingsRepository.getLastLoadedUrl().orEmpty()
        val settings = viewModel.uiState.value.settings
        val recoveryUrl = if (lastUrl.isNotBlank() && UrlOrigins.hasSameOrigin(lastUrl, settings.serverUrl)) {
            val normalizedLastPath = UrlOrigins.normalizedPath(lastUrl)
            if (normalizedLastPath.isBlank()) "" else lastUrl
        } else {
            ""
        }
        val quotedLastUrl = JSONObject.quote(recoveryUrl)
        return """
            (function() {
              try {
                if (window.__hermesAndroidRouteRecoveryInstalled) return;
                window.__hermesAndroidRouteRecoveryInstalled = true;
                var recoveryUrl = $quotedLastUrl;
                var panelIsHidden = function() {
                  try {
                    var rightPanel = document.querySelector('.rightpanel');
                    if (!rightPanel) return true;
                    var style = window.getComputedStyle(rightPanel);
                    return style.display === 'none' || rightPanel.getBoundingClientRect().width === 0;
                  } catch (_) { return true; }
                };
                var fallbackOpenAttr = 'data-hermes-android-fallback-open';
                var fallbackWidthAttr = 'data-hermes-android-fallback-width';
                var forcePanelOpen = function() {
                  try {
                    var rightPanel = document.querySelector('.rightpanel');
                    if (!rightPanel) return;
                    var existingInlineWidth = (rightPanel.style && rightPanel.style.width) || '';
                    if (!rightPanel.getAttribute(fallbackWidthAttr)) {
                      rightPanel.setAttribute(fallbackWidthAttr, existingInlineWidth);
                    }
                    rightPanel.style.setProperty('display', 'block', 'important');
                    rightPanel.style.setProperty('visibility', 'visible', 'important');
                    rightPanel.style.setProperty('opacity', '1', 'important');
                    if (!rightPanel.style.width || rightPanel.getBoundingClientRect().width === 0) {
                      var width = Math.max(320, Math.min(520, Math.round(window.innerWidth * 0.42)));
                      rightPanel.style.setProperty('width', String(width) + 'px', 'important');
                      rightPanel.style.setProperty('max-width', String(width) + 'px', 'important');
                    }
                    document.body.classList.add('workspace-open', 'rightpanel-open');
                    rightPanel.setAttribute(fallbackOpenAttr, '1');
                  } catch (_) {}
                };
                var releaseFallbackPanelStyles = function() {
                  try {
                    var rightPanel = document.querySelector('.rightpanel');
                    if (!rightPanel) return;
                    if (rightPanel.getAttribute(fallbackOpenAttr) !== '1') return;
                    var previousWidth = rightPanel.getAttribute(fallbackWidthAttr);
                    rightPanel.style.removeProperty('display');
                    rightPanel.style.removeProperty('visibility');
                    rightPanel.style.removeProperty('opacity');
                    rightPanel.style.removeProperty('max-width');
                    if (previousWidth != null) {
                      rightPanel.style.width = previousWidth;
                    } else {
                      rightPanel.style.removeProperty('width');
                    }
                    rightPanel.removeAttribute(fallbackOpenAttr);
                    rightPanel.removeAttribute(fallbackWidthAttr);
                  } catch (_) {}
                };
                var scheduleFallbackRelease = function() {
                  window.setTimeout(function() {
                    releaseFallbackPanelStyles();
                  }, 0);
                };
                window.addEventListener('click', function(event) {
                  try {
                    var target = event.target;
                    var button = target && target.closest ? target.closest('#btnWorkspacePanelToggle') : null;
                    if (!button) return;
                    // If fallback opened the panel previously, release temporary styles first so
                    // WebUI's own toggle handler can close it naturally.
                    releaseFallbackPanelStyles();
                    window.setTimeout(function() {
                      try {
                        if (!panelIsHidden()) return;
                        forcePanelOpen();
                        window.setTimeout(function() {
                          if (!panelIsHidden()) return;
                          if (recoveryUrl && window.location && window.location.href !== recoveryUrl) {
                            window.location.href = recoveryUrl;
                          }
                        }, 90);
                        if (panelIsHidden() && recoveryUrl && window.location && window.location.href !== recoveryUrl) {
                          window.location.href = recoveryUrl;
                        }
                      } catch (_) {}
                    }, 75);
                  } catch (_) {}
                }, true);
                window.addEventListener('click', function(event) {
                  try {
                    var target = event.target;
                    if (!target || !target.closest) return;
                    var rightPanel = document.querySelector('.rightpanel');
                    if (!rightPanel || rightPanel.getAttribute(fallbackOpenAttr) !== '1') return;
                    var panelAction = target.closest('.rightpanel button, .rightpanel [role="button"], .rightpanel a');
                    if (!panelAction) return;
                    // After WebUI handles the click (including close), drop fallback styles
                    // so panel visibility is controlled solely by WebUI state.
                    scheduleFallbackRelease();
                  } catch (_) {}
                }, true);
              } catch (_) {}
            })();
        """.trimIndent()
    }

    private fun buildHermesWebUiNotificationBridgeScript(): String {
        val quotedBridgeName = JSONObject.quote(HermesNotificationBridgeName)
        val quotedPermission = JSONObject.quote(webNotificationPermissionState())
        return """
            (function() {
              var bridgeName = $quotedBridgeName;
              var initialPermission = $quotedPermission;
              var nativeBridge = window[bridgeName];

              var normalizePermission = function(value) {
                value = String(value || '').toLowerCase();
                return (value === 'granted' || value === 'denied' || value === 'default') ? value : 'default';
              };

              if (window.__hermesAndroidNotificationsInstalled) {
                if (typeof window.__hermesAndroidSetNotificationPermission === 'function') {
                  window.__hermesAndroidSetNotificationPermission(initialPermission);
                }
                return;
              }

              if (!nativeBridge || typeof nativeBridge.postMessage !== 'function') return;

              var permission = normalizePermission(initialPermission);
              var pending = {};
              var nextId = 1;

              var makeDomException = function(message, name) {
                try { return new DOMException(message, name); } catch (_) {
                  var error = new Error(message);
                  error.name = name;
                  return error;
                }
              };

              var safeEvent = function(type) {
                try { return new Event(type); } catch (_) { return { type: type }; }
              };

              var cloneOptions = function(options) {
                var clone = {};
                if (!options || typeof options !== 'object') return clone;
                ['body', 'tag', 'icon', 'badge'].forEach(function(key) {
                  if (options[key] != null) clone[key] = String(options[key]);
                });
                if (options.data && typeof options.data === 'object') {
                  clone.data = {};
                  if (options.data.url != null) clone.data.url = String(options.data.url);
                }
                return clone;
              };

              var postNative = function(type, payload) {
                return new Promise(function(resolve) {
                  var id = String(Date.now()) + '-' + String(nextId++);
                  pending[id] = resolve;
                  try {
                    nativeBridge.postMessage(JSON.stringify({ id: id, type: type, payload: payload || {} }));
                  } catch (_) {
                    delete pending[id];
                    resolve({ ok: false, permission: permission, error: 'post-failed' });
                    return;
                  }
                  window.setTimeout(function() {
                    if (!pending[id]) return;
                    delete pending[id];
                    resolve({ ok: false, permission: permission, error: 'timeout' });
                  }, 15000);
                });
              };

              nativeBridge.onmessage = function(event) {
                var response = null;
                try { response = JSON.parse(event && event.data ? String(event.data) : '{}'); } catch (_) {}
                if (!response) return;
                if (response.permission) permission = normalizePermission(response.permission);
                var id = String(response.id || '');
                var resolve = pending[id];
                if (resolve) {
                  delete pending[id];
                  resolve(response);
                }
              };

              window.__hermesAndroidSetNotificationPermission = function(nextPermission) {
                permission = normalizePermission(nextPermission);
              };

              var showNativeNotification = function(title, options) {
                if (permission !== 'granted') {
                  return Promise.reject(makeDomException('Notification permission denied', 'NotAllowedError'));
                }
                return postNative('show', {
                  title: String(title || ''),
                  options: cloneOptions(options)
                }).then(function(response) {
                  if (response && response.permission) permission = normalizePermission(response.permission);
                  if (response && response.ok) return undefined;
                  throw makeDomException('Notification delivery failed', 'AbortError');
                });
              };

              function HermesAndroidNotification(title, options) {
                if (!(this instanceof HermesAndroidNotification)) {
                  return new HermesAndroidNotification(title, options);
                }
                if (permission !== 'granted') {
                  throw makeDomException('Notification permission denied', 'NotAllowedError');
                }
                this.title = String(title || '');
                this.body = options && options.body != null ? String(options.body) : '';
                this.tag = options && options.tag != null ? String(options.tag) : '';
                this.data = options && options.data != null ? options.data : null;
                this.onclick = null;
                this.onshow = null;
                this.onerror = null;
                this.onclose = null;

                var notification = this;
                showNativeNotification(this.title, options)
                  .then(function() {
                    if (typeof notification.onshow === 'function') notification.onshow(safeEvent('show'));
                  })
                  .catch(function(error) {
                    if (typeof notification.onerror === 'function') notification.onerror(error);
                  });
              }

              HermesAndroidNotification.prototype.close = function() {
                if (typeof this.onclose === 'function') this.onclose(safeEvent('close'));
              };

              Object.defineProperty(HermesAndroidNotification, 'permission', {
                configurable: true,
                enumerable: true,
                get: function() { return permission; }
              });
              Object.defineProperty(HermesAndroidNotification, 'maxActions', {
                configurable: true,
                enumerable: true,
                get: function() { return 0; }
              });
              HermesAndroidNotification.requestPermission = function(callback) {
                return postNative('requestPermission', {}).then(function(response) {
                  if (response && response.permission) permission = normalizePermission(response.permission);
                  if (typeof callback === 'function') {
                    window.setTimeout(function() { callback(permission); }, 0);
                  }
                  return permission;
                });
              };

              try {
                Object.defineProperty(window, 'Notification', {
                  configurable: true,
                  writable: true,
                  value: HermesAndroidNotification
                });
              } catch (_) {
                try { window.Notification = HermesAndroidNotification; } catch (_) {}
              }

              var patchServiceWorkerNotifications = function() {
                try {
                  if (!window.ServiceWorkerRegistration || !window.ServiceWorkerRegistration.prototype) return;
                  var proto = window.ServiceWorkerRegistration.prototype;
                  if (proto.__hermesAndroidNotificationsPatched) return;
                  Object.defineProperty(proto, 'showNotification', {
                    configurable: true,
                    writable: true,
                    value: function(title, options) {
                      return showNativeNotification(title, options);
                    }
                  });
                  if (typeof proto.getNotifications !== 'function') {
                    Object.defineProperty(proto, 'getNotifications', {
                      configurable: true,
                      writable: true,
                      value: function() { return Promise.resolve([]); }
                    });
                  }
                  proto.__hermesAndroidNotificationsPatched = true;
                } catch (_) {}
              };

              window.__hermesAndroidNotificationsInstalled = true;
              patchServiceWorkerNotifications();
              window.setTimeout(patchServiceWorkerNotifications, 0);
              window.setTimeout(patchServiceWorkerNotifications, 1000);
              postNative('permissionState', {}).then(function(response) {
                if (response && response.permission) permission = normalizePermission(response.permission);
              });
            })();
        """.trimIndent()
    }

    private fun matchesConfiguredWebUiRoute(url: String?): Boolean {
        val settings = viewModel.uiState.value.settings
        return UrlOrigins.hasSameOrigin(url, settings.serverUrl) && !matchesConfiguredDashboardRoute(url)
    }

    private fun matchesConfiguredDashboardRoute(url: String?): Boolean {
        val dashboardUrl = viewModel.uiState.value.settings.dashboardUrl
        if (url.isNullOrBlank() || dashboardUrl.isBlank()) return false
        if (!UrlOrigins.hasSameOrigin(url, dashboardUrl)) return false

        val targetPath = UrlOrigins.normalizedPath(url)
        val dashboardPath = UrlOrigins.normalizedPath(dashboardUrl)
        if (dashboardPath.isBlank()) return true
        return targetPath == dashboardPath || targetPath.startsWith("$dashboardPath/")
    }

    private fun handleNewWindowUrl(url: String) {
        if (matchesConfiguredDashboardRoute(url)) {
            openDashboardInCustomTab(url)
            return
        }
        when (urlPolicy.navigationDecision(url)) {
            NavigationDecision.ALLOW_IN_WEBVIEW -> webView.loadUrl(url)
            NavigationDecision.OPEN_IN_EXTERNAL_BROWSER -> openInExternalBrowser(url)
            NavigationDecision.BLOCK -> Unit
        }
    }

    private fun buildDownloadListener(context: Context): DownloadListener {
        return DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!urlPolicy.isAllowed(url)) {
                Toast.makeText(context, "Blocked download from non-allowlisted domain", Toast.LENGTH_LONG).show()
                return@DownloadListener
            }
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
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
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val manager = context.getSystemService(DownloadManager::class.java)
            manager.enqueue(request)
            Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSettings(serverUrl: String) {
        if (!serverUrlValidator.isValid(serverUrl)) {
            Toast.makeText(this, "Server URL must be a valid http:// or https:// URL", Toast.LENGTH_LONG).show()
            return
        }
        val dashboardUrl = viewModel.uiState.value.settings.dashboardUrl
        if (dashboardUrl.isNotBlank() && !serverUrlValidator.isValid(dashboardUrl)) {
            Toast.makeText(this, "Dashboard URL must be blank or a valid http:// or https:// URL", Toast.LENGTH_LONG).show()
            return
        }
        viewModel.saveAppUrls(serverUrl, dashboardUrl)
        urlPolicy = UrlPolicy(viewModel.uiState.value.settings.allowedHosts)
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

    private fun handleAddServerProfile(name: String, url: String) {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()

        if (!serverUrlValidator.isValid(trimmedUrl)) {
            Toast.makeText(this, "Server URL must be a valid http:// or https:// URL", Toast.LENGTH_LONG).show()
            return
        }

        val existingProfiles = settingsRepository.getProfiles()
        if (existingProfiles.any { normalizeServerProfileUrl(it.url) == normalizeServerProfileUrl(trimmedUrl) }) {
            Toast.makeText(this, "A server with this URL already exists", Toast.LENGTH_LONG).show()
            return
        }
        if (trimmedName.isNotBlank() && existingProfiles.any { it.name.trim().equals(trimmedName, ignoreCase = true) }) {
            Toast.makeText(this, "A server with this name already exists", Toast.LENGTH_LONG).show()
            return
        }

        val profile = viewModel.addServerProfile(
            name = trimmedName.ifBlank { trimmedUrl },
            url = trimmedUrl
        )
        if (profile != null) {
            Toast.makeText(this, "Server profile \"${profile.name}\" added", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Failed to add profile", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleEditServerProfile(profileId: String, newName: String, newUrl: String) {
        if (!serverUrlValidator.isValid(newUrl)) {
            Toast.makeText(this, "Server URL must be a valid http:// or https:// URL", Toast.LENGTH_LONG).show()
            return
        }
        viewModel.updateServerProfile(profileId, newName, newUrl)
        Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
    }

    private fun handleDeleteServerProfile(profileId: String) {
        viewModel.deleteServerProfile(profileId)
        Toast.makeText(this, "Profile deleted", Toast.LENGTH_SHORT).show()
    }

    private fun handleSwitchServerProfile(profileId: String) {
        val newProfile = settingsRepository.getProfiles().firstOrNull { it.id == profileId } ?: return

        // Validate the server URL before switching
        if (!serverUrlValidator.isValid(newProfile.url)) {
            Toast.makeText(this, "Invalid server URL: ${newProfile.url}", Toast.LENGTH_LONG).show()
            return
        }

        // Clear old server's cookies and cache
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)

        // Switch the profile and reload
        viewModel.switchServerProfile(profileId)
        webView.loadUrl(newProfile.url)
        viewModel.closeSettings()
        Toast.makeText(this, "Switched to ${newProfile.name}", Toast.LENGTH_SHORT).show()
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

    /** Detect if a URL is an OAuth/OIDC authorization endpoint.
     *
     * These URLs initiate the OAuth authorization flow and must keep the WebView
     * alive across multiple redirects to preserve PKCE state cookies and other auth session data.
     *
     * Common patterns:
     * - https://auth.example.com/authorize?...
     * - https://login.provider.com/oauth/authorize
     * - https://accounts.google.com/o/oauth2/v2/auth
     * - https://login.microsoftonline.com/.../.../oauth2/v2.0/authorize
     */
    private fun isOAuthAuthorizationUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/authorize") ||
               lower.contains("/oauth/authorize") ||
               lower.contains("/oauth2/v2.0/authorize") ||
               lower.contains("/auth/oauth") ||
               lower.contains("/oauth2/authorize") ||
               lower.contains("/openid/authorize")
    }

    /** Detect if a URL is part of an OAuth/OIDC flow (but not the final callback).
     *
     * These URLs should keep the popup alive because they're part of the auth flow:
     * - Login pages, consent screens, MFA prompts, etc.
     *
     * Pattern: URLs from known auth providers during active flow.
     */
    private fun isOAuthRelatedUrl(url: String): Boolean {
        val lower = url.lowercase()
        // Check if URL matches known auth provider domains (common patterns)
        val isKnownAuthDomain = lower.contains("auth.") ||
                                 lower.contains("login.") ||
                                 lower.contains("accounts.") ||
                                 lower.contains("account.") ||
                                 lower.contains("idp.") ||
                                 lower.contains("sso.")

        // Exclude callback URLs — they indicate flow completion
        val isNotCallback = !lower.contains("callback") &&
                            !lower.contains("code=") &&
                            !lower.contains("state=")

        return isKnownAuthDomain && isNotCallback
    }

    /** Detect if a URL is an OAuth/OIDC callback (flow completion).
     *
     * OAuth flows end with a callback URL containing authorization code or error:
     * - https://hermes.example.com/callback?code=...&state=...
     * - https://hermes.example.com/auth/callback?error=...
     * - https://app.example.com/oauth/callback?code=abc123
     *
     * When we reach a callback URL, the popup has served its purpose and should be destroyed.
     */
    private fun isOAuthCallbackUrl(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains("callback") && (lower.contains("code=") || lower.contains("error="))) ||
               (lower.contains("code=") && lower.contains("state="))
    }

    /** Cleanup OAuth popup if it has timed out.
     *
     * OAuth flows should complete quickly (typically < 2 min). If a popup stays
     * active longer than the timeout, destroy it to prevent resource leaks.
     */
    private fun cleanupExpiredOAuthPopup() {
        if (activeOAuthPopup != null && System.currentTimeMillis() > oauthFlowTimeoutMs) {
            try {
                activeOAuthPopup?.destroy()
            } catch (_: Exception) {
                // WebView may already be destroyed; ignore errors
            }
            activeOAuthPopup = null
        }
    }

    private fun normalizeServerProfileUrl(url: String): String {
        return url.trim().trimEnd('/').lowercase()
    }
}
