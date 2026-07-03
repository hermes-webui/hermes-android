package com.hermeswebui.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.AlertDialog
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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hermeswebui.android.background.HermesDebugLoggingService
import com.hermeswebui.android.background.DebugLogBootstrap
import com.hermeswebui.android.background.HermesReconnectService
import com.hermeswebui.android.background.ReconnectBackgroundPolicy
import com.hermeswebui.android.background.ReconnectSessionStreamSupport
import com.hermeswebui.android.core.security.NavigationDecision
import com.hermeswebui.android.core.security.UrlOrigins
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.data.DiagnosticsLogger
import com.hermeswebui.android.data.HermesApiClient
import com.hermeswebui.android.data.ServerProfile
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.domain.ServerUrlValidator
import com.hermeswebui.android.domain.ShareIntentParser
import com.hermeswebui.android.ui.MainViewModel
import com.hermeswebui.android.ui.MainViewModelFactory
import com.hermeswebui.android.ui.DebugLogFloatingOverlay
import com.hermeswebui.android.ui.settings.SettingsScreen
import com.hermeswebui.android.ui.web.WebShell
import com.hermeswebui.android.update.AppUpdateCheckResult
import com.hermeswebui.android.update.GitHubReleaseUpdateChecker
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.Job

private const val HermesNotificationBridgeName = "HermesAndroidNotifications"
private const val HermesNotificationChannelId = "hermes_webui_notifications"
private const val HermesNotificationIdBase = 10_000
private const val ActionOpenNotificationUrl = "com.hermeswebui.android.OPEN_NOTIFICATION_URL"
private const val ActionStartPlayUpdate = "com.hermeswebui.android.START_PLAY_UPDATE"
private const val ActionDownloadAppUpdate = "com.hermeswebui.android.DOWNLOAD_APP_UPDATE"
private const val ExtraNotificationUrl = "com.hermeswebui.android.extra.NOTIFICATION_URL"
private const val ExtraAppUpdateDownloadUrl = "com.hermeswebui.android.extra.APP_UPDATE_DOWNLOAD_URL"
private const val ExtraAppUpdateFileName = "com.hermeswebui.android.extra.APP_UPDATE_FILE_NAME"
private const val HermesGithubIssuesListUrl = "https://github.com/hermes-webui/hermes-android/issues"
private const val HermesGithubNewIssueUrl = "https://github.com/hermes-webui/hermes-android/issues/new/choose"
private const val AppUpdateNotificationId = 7_001
private const val AutomaticAppUpdateCheckDelayMs = 60_000L

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

      var normalizedText = function(value) {
        return String(value || '').trim().toLowerCase();
      };

      var textMatchesRegularSettings = function(value) {
        var normalized = normalizedText(value);
        if (!normalized) return false;
        if (normalized.indexOf('application settings') !== -1 || normalized.indexOf('app settings') !== -1) {
          return false;
        }
        return normalized === 'settings' ||
          normalized === 'open settings' ||
          normalized.indexOf('settings ') === 0 ||
          normalized.indexOf(' settings') !== -1;
      };

      var isRegularSettingsControl = function(node) {
        if (!node || !node.getAttribute) return false;
        if (node.getAttribute(markerAttr)) return false;
        var panel = normalizedText(node.getAttribute('data-panel'));
        var settingsSection = normalizedText(node.getAttribute('data-settings-section'));
        if (panel === 'settings') return true;
        if (settingsSection === 'settings') return true;
        if (textMatchesRegularSettings(node.textContent)) return true;
        if (textMatchesRegularSettings(node.getAttribute('aria-label'))) return true;
        if (textMatchesRegularSettings(node.getAttribute('title'))) return true;
        if (textMatchesRegularSettings(node.getAttribute('data-tooltip'))) return true;
        if (textMatchesRegularSettings(node.getAttribute('data-i18n-title'))) return true;
        return false;
      };

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

      var findInteractiveInScope = function(scope, textMatcher) {
        if (!scope || !scope.querySelectorAll) return null;
        var nodes = scope.querySelectorAll('a, button, [role="button"], [role="menuitem"]');
        for (var i = 0; i < nodes.length; i++) {
          var node = nodes[i];
          if (node.getAttribute(markerAttr)) continue;
          if (textMatcher(node.textContent)) return node;
          var aria = node.getAttribute('aria-label');
          if (textMatcher(aria)) return node;
          var title = node.getAttribute('title');
          if (textMatcher(title)) return node;
        }
        return null;
      };

      var findSettingsInteractivesInScope = function(scope) {
        if (!scope || !scope.querySelectorAll) return [];
        var nodes = scope.querySelectorAll('a, button, [role="button"], [role="menuitem"], [data-panel], [data-settings-section]');
        var hits = [];
        for (var i = 0; i < nodes.length; i++) {
          var node = nodes[i];
          if (isRegularSettingsControl(node)) hits.push(node);
        }
        return hits;
      };

      var findSidebarInteractive = function(textMatcher) {
        var scopedSelectors = ['.sidebar', '.rail', '.leftpanel', 'aside', 'nav'];
        for (var i = 0; i < scopedSelectors.length; i++) {
          var scope = document.querySelector(scopedSelectors[i]);
          var hit = findInteractiveInScope(scope, textMatcher);
          if (hit) return hit;
        }
        return findInteractiveInScope(document, textMatcher);
      };

      var findSettingsInteractives = function() {
        var scopedSelectors = ['.rail', '.sidebar-nav', '.leftpanel'];
        var seen = [];
        var hits = [];
        var addHits = function(scopeHits) {
          for (var i = 0; i < scopeHits.length; i++) {
            if (seen.indexOf(scopeHits[i]) !== -1) continue;
            seen.push(scopeHits[i]);
            hits.push(scopeHits[i]);
          }
        };
        for (var i = 0; i < scopedSelectors.length; i++) {
          document.querySelectorAll(scopedSelectors[i]).forEach(function(scope) {
            addHits(findSettingsInteractivesInScope(scope));
          });
        }
        return hits;
      };

      var appEntryAlreadyAfter = function(anchorContainer) {
        var next = anchorContainer && anchorContainer.nextElementSibling;
        return !!(next && next.getAttribute && next.getAttribute(markerAttr) === '1');
      };

      var ensureEntryAfter = function(anchorInteractive) {
        try {
          if (!anchorInteractive) return;

          var anchorContainer = anchorInteractive.closest('li, [role="menuitem"], .menu-item, .nav-item, .sidebar-item, [data-menu-item]') || anchorInteractive;
          if (!anchorContainer || !anchorContainer.parentNode) return;
          if (appEntryAlreadyAfter(anchorContainer)) return;

          var cloned = anchorContainer.cloneNode(false);
          cloned.setAttribute(markerAttr, '1');
          cloned.removeAttribute('id');
          clearActiveState(cloned);
          stripRoutingAttributes(cloned);

          var anchorIsInteractive = anchorContainer.matches('a, button, [role="button"], [role="menuitem"]');
          var interactive = anchorIsInteractive ? cloned : anchorInteractive.cloneNode(false);
          if (!anchorIsInteractive) {
            interactive.removeAttribute('id');
            clearActiveState(interactive);
            stripRoutingAttributes(interactive);
            cloned.appendChild(interactive);
          }

          if (!interactive) return;

          interactive.textContent = '';
          applyApplicationIcon(interactive);
          if (!anchorInteractive.matches('[data-panel="settings"], .nav-tab, .rail-btn')) {
            var label = document.createElement('span');
            label.textContent = 'Application Settings';
            label.setAttribute(markerAttr, 'label');
            interactive.appendChild(label);
          }

          if (interactive.tagName && interactive.tagName.toLowerCase() === 'a') {
            interactive.setAttribute('href', appSettingsHref);
          } else {
            interactive.setAttribute('role', 'link');
            interactive.setAttribute('tabindex', '0');
          }
          bindNativeSettingsClick(interactive);
          interactive.setAttribute('aria-label', 'Application Settings');
          interactive.setAttribute('title', 'Application Settings');
          interactive.setAttribute('data-tooltip', 'Application Settings');
          interactive.removeAttribute('data-panel');
          interactive.removeAttribute('data-settings-section');
          interactive.removeAttribute('data-i18n-title');
          interactive.removeAttribute('aria-current');
          interactive.removeAttribute('aria-selected');
          interactive.classList.remove('active');
          interactive.classList.remove('selected');
          interactive.classList.remove('is-active');
          interactive.setAttribute(markerAttr, '1');
          bindNativeSettingsClick(cloned);

          anchorContainer.parentNode.insertBefore(cloned, anchorContainer.nextSibling);
        } catch (_) {}
      };

      var ensureEntry = function() {
        var settingsHits = findSettingsInteractives();
        if (settingsHits.length) {
          settingsHits.forEach(ensureEntryAfter);
          return;
        }
        ensureEntryAfter(findSidebarInteractive(textContainsHelp));
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
    private var pendingCameraCaptureUri: Uri? = null
    private var pendingAudioPermissionRequest: PermissionRequest? = null
    private val pendingNotificationPermissionReplies = mutableListOf<NotificationPermissionReply>()
    private var notificationPermissionRequestInFlight = false
    private var microphoneFallbackScriptHandler: ScriptHandler? = null
    private var notificationBridgeScriptHandler: ScriptHandler? = null
    private var routeRecoveryScriptHandler: ScriptHandler? = null
    private var appSettingsEntryScriptHandler: ScriptHandler? = null
    private var activityVisible = false
    private var reconnectServiceRunning = false
    private var debugLoggingServiceRunning = false
    private var serverValidationJob: Job? = null
    private var automaticAppUpdateCheckJob: Job? = null
    private lateinit var appUpdateManager: AppUpdateManager

    private var activeOAuthPopup: WebView? = null
    private var activeOAuthFlow: OAuthPopupFlow? = null
    private var activeMainFrameOAuthFlow: OAuthPopupFlow? = null
    private var oauthFlowTimeoutMs: Long = 0
    private val OAUTH_FLOW_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

    private val serverUrlValidator = ServerUrlValidator()

    private var lastBackPressTime: Long = 0
    private val BACK_PRESS_TIMEOUT_MS = 2000L // 2 seconds

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        filePathCallback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
        filePathCallback = null
        pendingCameraCaptureUri = null
        viewModel.dismissShareBanner()
    }

    private val cameraCaptureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val resultUri = pendingCameraCaptureUri?.takeIf { success }
        filePathCallback?.onReceiveValue(resultUri?.let { arrayOf(it) })
        filePathCallback = null
        pendingCameraCaptureUri = null
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

    private val playUpdateLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                Toast.makeText(this, "App update was not completed", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Begin logcat capture to an app-private file BEFORE any other onCreate
        // work, so a crash or permission denial during startup is still captured.
        // No-op on release builds. The foreground service (later in onCreate)
        // takes over long-term ownership and presents the Stop notification.
        DebugLogBootstrap.startIfDebuggable(applicationContext)

        val defaultUrl = getString(R.string.default_server_url)
        val defaultDashboardUrl = getString(R.string.default_dashboard_url)
        settingsRepository = SettingsRepository(applicationContext)
        appUpdateManager = AppUpdateManagerFactory.create(this)
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(settingsRepository, settingsRepository, defaultUrl, defaultDashboardUrl)
        )[MainViewModel::class.java]
        urlPolicy = UrlPolicy(viewModel.uiState.value.settings.allowedHosts)

        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)
        ensureNotificationChannel()

        // Auto-enable debug logging on debuggable builds so tester logs capture from app launch.
        // Users can still manually toggle this off from Settings → Troubleshooting.
        if (isDebuggable && !settingsRepository.isDebugLoggingEnabled()) {
            settingsRepository.setDebugLoggingEnabled(true)
            viewModel.setDebugLoggingEnabled(true)
        }
        webView = buildWebView()
        installHermesWebUiDocumentStartFixes(webView, viewModel.uiState.value.settings.serverUrl)

        if (!handleAppUpdateIntent(intent)) {
            handleShareIntent(intent)
        }

        setContent {
            AppContent(
                onReload = { webView.reload() },
                onOpenExternal = { openInExternalBrowser(viewModel.uiState.value.currentUrl) },
                onSaveSettings = { serverUrl -> saveSettings(serverUrl) },
                onResetSession = { resetWebSession() },
                onRequestExit = { finish() }
            )
        }

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                syncReconnectForegroundService(state.isReconnecting)
                syncDebugLoggingForegroundService(state.debugLoggingEnabled)
            }
        }

        val settings = viewModel.uiState.value.settings
        if (!settings.isConfigured) {
            viewModel.openSettings()
        } else {
            preflightConfiguredStartupServer(settings.serverUrl)
            scheduleAutomaticAppUpdateCheck()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleAppUpdateIntent(intent)) {
            return
        }
        if (handleNotificationIntent(intent)) {
            return
        }
        if (!handleDeepLink(intent)) {
            handleShareIntent(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            activityVisible = true
            viewModel.refreshFeatureFlagsFromRepository()
            stopReconnectForegroundService()
            viewModel.onAppForegrounded()
            updateWebNotificationPermissionState()
            viewModel.resumeAutoRetryIfNeeded()
            resumePlayUpdateIfNeeded()
            scheduleAutomaticAppUpdateCheck()
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            viewModel.onAppBackgrounded()
        }
        super.onPause()
        // If OAuth flow has timed out, clean up the popup to prevent resource leaks.
        cleanupExpiredOAuthPopup()
    }

    override fun onStop() {
        super.onStop()
        activityVisible = false
        cancelAutomaticAppUpdateCheck()
        val state = viewModel.uiState.value
        syncReconnectForegroundService(state.isReconnecting)
        if (
            ReconnectBackgroundPolicy.shouldCancelAutoRetryOnStop(
                backgroundReconnectEnabled = state.backgroundReconnectEnabled,
                activityVisible = activityVisible,
                isReconnecting = state.isReconnecting
            )
        ) {
            // Avoid background polling unless the reconnect foreground service is actively holding
            // the bounded retry loop alive for a just-backgrounded recovery attempt.
            viewModel.cancelAutoRetry()
        }
        // Clean up any lingering OAuth popup on app stop.
        cleanupExpiredOAuthPopup()
    }

    /** Handles Hermes app deep links.
     *
     * hermes://app/settings opens native Android settings. hermes://session/{id}
     * navigates the WebView to {serverUrl}/{id}, matching the Hermes WebUI
     * session route contract (sessionRoute() in apps/desktop/src/app/routes.ts).
     * Returns true if the intent was consumed, false if it should fall through.
     */
    private fun handleDeepLink(intent: Intent): Boolean {
        val data = intent.data ?: return false
        if (data.scheme != "hermes") return false

        if (data.host == "app" && data.path == "/settings") {
            viewModel.openSettings()
            return true
        }

        if (data.host != "session") return false
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
            Box(modifier = Modifier.fillMaxSize()) {
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
                    SettingsScreen(
                    initialServerUrl = uiState.settings.serverUrl,
                    isConfigured = uiState.settings.isConfigured,
                    backgroundReconnectEnabled = uiState.backgroundReconnectEnabled,
                    backgroundActivityFullTextEnabled = uiState.backgroundActivityFullTextEnabled,
                    reconnectPollIntervalSeconds = uiState.reconnectPollIntervalSeconds,
                    sseTransportEnabled = uiState.sseTransportEnabled,
                    sseSupportStatus = uiState.sseSupportStatus,
                    debugLoggingEnabled = uiState.debugLoggingEnabled,
                    appUpdateAlertsEnabled = uiState.appUpdateAlertsEnabled,
                    automaticAppUpdateChecksEnabled = uiState.automaticAppUpdateChecksEnabled,
                    appUpdateChannelLabel = appUpdateChannelLabel(),
                    appUpdateStatus = uiState.appUpdateStatus,
                    appUpdateReleaseUrl = uiState.appUpdateReleaseUrl,
                    appUpdateDownloadUrl = uiState.appUpdateDownloadUrl,
                    appUpdateReleaseNotes = uiState.appUpdateReleaseNotes,
                    serverValidation = uiState.serverValidation,
                    appVersionLabel = "Version ${appVersionName()}",
                    serverProfiles = serverProfiles,
                    onSave = onSaveSettings,
                    onResetSession = onResetSession,
                    onDismiss = { viewModel.closeSettings() },
                    onSetBackgroundReconnect = { enabled ->
                        if (enabled) {
                            requestNotificationPermissionIfNeeded()
                        }
                        viewModel.setBackgroundReconnectEnabled(enabled)
                        syncReconnectForegroundService(viewModel.uiState.value.isReconnecting)
                    },
                    onSetBackgroundActivityFullTextEnabled = { enabled ->
                        viewModel.setBackgroundActivityFullTextEnabled(enabled)
                    },
                    onSetReconnectPollIntervalSeconds = { seconds ->
                        viewModel.setReconnectPollIntervalSeconds(seconds)
                    },
                    onSetSseTransportEnabled = { enabled ->
                        setSseTransportEnabled(enabled)
                    },
                    onCheckSseSupport = {
                        checkSseSupport(
                            enableIfAvailable = false,
                            disableIfUnavailable = false
                        )
                    },
                    onCopySsePrompt = { copySseEnablePromptToClipboard() },
                    onSetDebugLoggingEnabled = { enabled ->
                        if (enabled) {
                            requestNotificationPermissionIfNeeded()
                        }
                        viewModel.setDebugLoggingEnabled(enabled)
                        syncDebugLoggingForegroundService(enabled)
                    },
                    onSetAppUpdateAlertsEnabled = { enabled ->
                        if (enabled) {
                            requestNotificationPermissionIfNeeded()
                        }
                        viewModel.setAppUpdateAlertsEnabled(enabled)
                    },
                    onSetAutomaticAppUpdateChecksEnabled = { enabled ->
                        viewModel.setAutomaticAppUpdateChecksEnabled(enabled)
                        if (enabled) {
                            scheduleAutomaticAppUpdateCheck()
                        } else {
                            cancelAutomaticAppUpdateCheck()
                        }
                    },
                    onCheckAppUpdates = { checkForAppUpdates(force = true) },
                    onDownloadAppUpdate = { downloadAvailableGitHubUpdate() },
                    onOpenAppUpdateRelease = {
                        uiState.appUpdateReleaseUrl?.takeIf { it.isNotBlank() }?.let(::openInExternalBrowser)
                    },
                    onShareDebugLog = { shareLatestDebugLog() },
                    onDownloadDebugLog = { downloadLatestDebugLog() },
                    onViewGithubIssues = { openInExternalBrowser(HermesGithubIssuesListUrl) },
                    onNewGithubIssue = { openInExternalBrowser(HermesGithubNewIssueUrl) },
                    onAddProfile = { name, url -> handleAddServerProfile(name, url) },
                    onDeleteProfile = { profileId -> handleDeleteServerProfile(profileId) },
                    onRenameProfile = { profileId, newName -> viewModel.renameServerProfile(profileId, newName) },
                    onEditProfile = { profileId, newName, newUrl -> handleEditServerProfile(profileId, newName, newUrl) },
                    onSwitchProfile = { profileId -> handleSwitchServerProfile(profileId) },
                    onClearServerValidation = { viewModel.clearServerValidationState() }
                )
            } // end if (isSettingsVisible)

            // Debug-only: draggable floating overlay that shares the latest
            // captured debug log with one tap. Auto-enabled debug logging in
            // onCreate() means a log is being captured from app start, so this
            // gives testers a frictionless way to ship the file out. Rendered
            // ABOVE the Settings sheet too, because the most common moment a
            // tester hits a connection error is exactly when Settings is open
            // (e.g. failed first-run server preflight) and they need to send
            // the log right then.
            val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (isDebuggable) {
                DebugLogFloatingOverlay(onTap = { shareLatestDebugLog() })
            }
        } // end outer Box
    } // end MaterialTheme
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
            // Allow native long-press so Android's text selection handles appear in
            // conversation messages (issue #35). Default WebView behavior already
            // routes long-press on links to the system context menu.
            isLongClickable = true

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
                    val popup = WebView(this@MainActivity).apply {
                        settings.javaScriptEnabled = true
                        configureWebViewStorageAndCache(settings)
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.loadsImagesAutomatically = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        disableWebViewDarkening(settings)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    }
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

                            val callbackFlow = activeOAuthFlow.takeIf { activeOAuthPopup === popup }
                            if (callbackFlow?.isVerifiedCallbackUrl(target) == true) {
                                clearActiveOAuthPopup()
                                loadOAuthCallbackInMainWebView(target)
                                popup.destroy()
                                return true
                            }

                            val flow = parseTrustedOAuthStart(target)
                            if (flow != null) {
                                rememberActiveOAuthPopup(popup, flow)
                                view?.loadUrl(target)
                                return true
                            }

                            if (activeOAuthPopup === popup && activeOAuthFlow != null && isHttpOrHttpsUrl(target)) {
                                refreshActiveOAuthTimeout()
                                return false
                            }

                            clearActiveOAuthPopup()
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

                            val callbackFlow = activeOAuthFlow.takeIf { activeOAuthPopup === popup }
                            if (callbackFlow?.isVerifiedCallbackUrl(url) == true) {
                                clearActiveOAuthPopup()
                                loadOAuthCallbackInMainWebView(url)
                                popup.destroy()
                                return
                            }

                            val startedFlow = parseTrustedOAuthStart(url)
                            if (startedFlow != null) {
                                rememberActiveOAuthPopup(popup, startedFlow)
                                return
                            }

                            if (activeOAuthPopup === popup && activeOAuthFlow != null && isHttpOrHttpsUrl(url)) {
                                refreshActiveOAuthTimeout()
                                return
                            }

                            clearActiveOAuthPopup()
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
                    pendingCameraCaptureUri = null
                    Toast.makeText(this@MainActivity, "Choose file(s) to upload", Toast.LENGTH_SHORT).show()
                    if (shouldDirectCaptureImage(fileChooserParams)) {
                        val captureUri = createTempCameraCaptureUri()
                        if (captureUri != null) {
                            pendingCameraCaptureUri = captureUri
                            cameraCaptureLauncher.launch(captureUri)
                            return true
                        }
                    }
                    filePickerLauncher.launch(normalizedMimeTypes(fileChooserParams))
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString() ?: return true
                    if (handleAppSettingsNavigation(target)) return true
                    val activeTopLevelFlow = activeMainFrameOAuthFlow
                    if (activeTopLevelFlow?.isVerifiedCallbackUrl(target) == true) {
                        clearActiveMainFrameOAuth()
                        return false
                    }
                    val startedTopLevelFlow = parseTrustedOAuthStart(target)
                    if (startedTopLevelFlow != null) {
                        rememberActiveMainFrameOAuth(startedTopLevelFlow)
                        return false
                    }
                    if (activeTopLevelFlow != null && isHttpOrHttpsUrl(target)) {
                        refreshActiveOAuthTimeout()
                        return false
                    }
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
                    if (!url.isNullOrBlank()) {
                        val activeTopLevelFlow = activeMainFrameOAuthFlow
                        if (activeTopLevelFlow?.isVerifiedCallbackUrl(url) == true) {
                            clearActiveMainFrameOAuth()
                        } else {
                            parseTrustedOAuthStart(url)?.let { rememberActiveMainFrameOAuth(it) }
                        }
                    }
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
                    DiagnosticsLogger.record(
                        this@MainActivity,
                        "webview_main_frame_error",
                        mapOf(
                            "origin" to DiagnosticsLogger.originOnly(request.url?.toString()),
                            "path" to DiagnosticsLogger.pathOnly(request.url?.toString()),
                            "error_code" to error?.errorCode?.toString(),
                            "offline" to offline.toString()
                        )
                    )
                    viewModel.onPageError(message, offline)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame != true) return
                    DiagnosticsLogger.record(
                        this@MainActivity,
                        "webview_main_frame_http_error",
                        mapOf(
                            "origin" to DiagnosticsLogger.originOnly(request.url?.toString()),
                            "path" to DiagnosticsLogger.pathOnly(request.url?.toString()),
                            "http_status" to errorResponse?.statusCode?.toString()
                        )
                    )
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.cancel()
                    DiagnosticsLogger.record(
                        this@MainActivity,
                        "webview_ssl_error",
                        mapOf(
                            "origin" to DiagnosticsLogger.originOnly(error?.url),
                            "path" to DiagnosticsLogger.pathOnly(error?.url),
                            "primary_error" to error?.primaryError?.toString()
                        )
                    )
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) return
        settingsRepository.markNotificationPermissionRequested()
        if (!notificationPermissionRequestInFlight) {
            notificationPermissionRequestInFlight = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setSseTransportEnabled(enabled: Boolean) {
        if (!enabled) {
            viewModel.setSseTransportEnabled(false)
            viewModel.setSseSupportStatus("SSE transport disabled.")
            return
        }

        checkSseSupport(
            enableIfAvailable = true,
            disableIfUnavailable = true
        )
    }

    private fun copySseEnablePromptToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(this, "Clipboard unavailable on this device.", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Hermes SSE enable prompt", HermesApiClient.SSE_ENABLE_HERMES_PROMPT)
        )
        Toast.makeText(this, "Copied SSE enable prompt.", Toast.LENGTH_SHORT).show()
    }

    private fun checkSseSupport(
        enableIfAvailable: Boolean,
        disableIfUnavailable: Boolean
    ) {
        val serverUrl = viewModel.uiState.value.settings.serverUrl
        if (serverUrl.isBlank()) {
            if (disableIfUnavailable) {
                viewModel.setSseTransportEnabled(false)
            }
            viewModel.setSseSupportStatus("Configure a server URL before checking SSE support.")
            Toast.makeText(this, "Configure a server URL before checking SSE", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.setSseSupportStatus("Checking server SSE support…")
        lifecycleScope.launch {
            when (HermesApiClient.detectSseCapability(serverUrl)) {
                HermesApiClient.SseCapability.SESSION_SSE_ENABLED -> {
                    if (enableIfAvailable) {
                        viewModel.setSseTransportEnabled(true)
                    }
                    viewModel.setSseSupportStatus(
                        "✅  SSE is supported and enabled on this server."
                    )
                    Toast.makeText(
                        this@MainActivity,
                        if (enableIfAvailable) "SSE enabled." else "SSE is available on this server.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                HermesApiClient.SseCapability.RECONNECT_STREAM_AVAILABLE -> {
                    if (enableIfAvailable) {
                        viewModel.setSseTransportEnabled(true)
                    }
                    viewModel.setSseSupportStatus(
                        "✅  SSE transport is supported via /api/sessions/events (reconnect stream)."
                    )
                    Toast.makeText(
                        this@MainActivity,
                        if (enableIfAvailable) "SSE enabled using reconnect stream." else "Reconnect SSE is available on this server.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                HermesApiClient.SseCapability.FEATURE_DISABLED -> {
                    if (disableIfUnavailable) {
                        viewModel.setSseTransportEnabled(false)
                    }
                    viewModel.setSseSupportStatus(
                        "🚫  SSE not supported on this server right now. Gateway/session SSE is off and the reconnect stream was not detected." +
                            if (disableIfUnavailable) " SSE transport was turned off." else ""
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "Gateway/session SSE not enabled on this server — see settings for how to turn it on.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                HermesApiClient.SseCapability.NONE -> {
                    if (disableIfUnavailable) {
                        viewModel.setSseTransportEnabled(false)
                    }
                    viewModel.setSseSupportStatus(
                        "❔  Haven't checked SSE support yet: this check could not reach SSE endpoints. Try again when the server/network is stable." +
                            if (disableIfUnavailable) " SSE transport was turned off." else ""
                    )
                    Toast.makeText(
                        this@MainActivity,
                        if (disableIfUnavailable) {
                            "SSE check failed — turning SSE transport off."
                        } else {
                            "SSE check failed — server unreachable or unexpected error."
                        },
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun postNotificationBridgeReply(
        replyProxy: JavaScriptReplyProxy,
        id: String?,
        ok: Boolean,
        permission: String,
        error: String? = null
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
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

    private fun appUpdateChannelLabel(): String {
        return when (BuildConfig.UPDATE_CHANNEL) {
            "github" -> "GitHub Releases"
            "play" -> "Google Play"
            else -> "this build channel"
        }
    }

    private fun scheduleAutomaticAppUpdateCheck() {
        if (!::settingsRepository.isInitialized) return
        val settings = viewModel.uiState.value.settings
        if (!settings.isConfigured) return
        if (!settingsRepository.shouldCheckForAppUpdates(System.currentTimeMillis(), force = false)) return
        if (automaticAppUpdateCheckJob?.isActive == true) return

        automaticAppUpdateCheckJob = lifecycleScope.launch {
            delay(AutomaticAppUpdateCheckDelayMs)
            if (!activityVisible) return@launch
            checkForAppUpdates(force = false)
            automaticAppUpdateCheckJob = null
        }
    }

    private fun cancelAutomaticAppUpdateCheck() {
        automaticAppUpdateCheckJob?.cancel()
        automaticAppUpdateCheckJob = null
    }

    private fun checkForAppUpdates(force: Boolean) {
        if (!settingsRepository.shouldCheckForAppUpdates(System.currentTimeMillis(), force)) return
        settingsRepository.markAppUpdateChecked(System.currentTimeMillis())
        viewModel.setAppUpdateStatus(if (force) "Checking ${appUpdateChannelLabel()}..." else null)

        when (BuildConfig.UPDATE_CHANNEL) {
            "github" -> checkGitHubAppUpdate(force)
            "play" -> checkPlayAppUpdate(force)
            else -> {
                if (force) {
                    viewModel.setAppUpdateStatus("This build does not have an update provider.")
                    Toast.makeText(this, "No update provider for this build", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkGitHubAppUpdate(force: Boolean) {
        lifecycleScope.launch {
            val result = GitHubReleaseUpdateChecker(
                apiUrl = BuildConfig.GITHUB_RELEASES_API_URL,
                fallbackReleaseUrl = BuildConfig.GITHUB_RELEASES_PAGE_URL
            ).check(appVersionName())

            when (result) {
                is AppUpdateCheckResult.Available -> {
                    viewModel.setAvailableAppUpdate(result)
                    maybeShowAppUpdateNotification(result, force)
                }
                AppUpdateCheckResult.Current -> {
                    if (force) {
                        viewModel.clearAvailableAppUpdate("You're on the latest GitHub build.")
                        Toast.makeText(this@MainActivity, "No GitHub update found", Toast.LENGTH_SHORT).show()
                    }
                }
                is AppUpdateCheckResult.Failed -> {
                    if (force) {
                        viewModel.clearAvailableAppUpdate(result.message)
                        Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
                AppUpdateCheckResult.Unsupported -> {
                    if (force) {
                        viewModel.clearAvailableAppUpdate("GitHub updates are not configured for this build.")
                    }
                }
            }
        }
    }

    private fun checkPlayAppUpdate(force: Boolean) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { updateInfo ->
                val isAvailable = updateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                val canUpdate = updateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                if (isAvailable && canUpdate) {
                    val version = updateInfo.availableVersionCode().toString()
                    viewModel.setAppUpdateStatus("A Google Play update is available.")
                    maybeShowPlayUpdateNotification(version, force)
                } else if (force) {
                    viewModel.setAppUpdateStatus("You're on the latest Google Play build.")
                    Toast.makeText(this, "No Google Play update found", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { error ->
                if (force) {
                    val message = error.message ?: "Could not check Google Play for updates."
                    viewModel.setAppUpdateStatus(message)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun maybeShowAppUpdateNotification(
        update: AppUpdateCheckResult.Available,
        force: Boolean
    ) {
        if (!settingsRepository.shouldNotifyAppUpdate(update.version, force)) return
        val pendingIntent = PendingIntent.getActivity(
            this,
            update.releaseUrl.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        showAppUpdateNotification(
            title = update.title,
            body = update.body,
            version = update.version,
            pendingIntent = pendingIntent,
            downloadIntent = update.downloadUrl?.let { downloadUrl ->
                buildAppUpdateDownloadPendingIntent(
                    downloadUrl = downloadUrl,
                    fileName = update.fileName ?: "hermes-webui-v${update.version}-github.apk"
                )
            },
            force = force
        )
    }

    private fun maybeShowPlayUpdateNotification(version: String, force: Boolean) {
        if (!settingsRepository.shouldNotifyAppUpdate("play-$version", force)) return
        val pendingIntent = PendingIntent.getActivity(
            this,
            version.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                action = ActionStartPlayUpdate
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        showAppUpdateNotification(
            title = "Hermes WebUI update available",
            body = "A newer Google Play build is ready to install.",
            version = "play-$version",
            pendingIntent = pendingIntent,
            downloadIntent = null,
            force = force
        )
    }

    private fun buildAppUpdateDownloadPendingIntent(
        downloadUrl: String,
        fileName: String
    ): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ActionDownloadAppUpdate
            putExtra(ExtraAppUpdateDownloadUrl, downloadUrl)
            putExtra(ExtraAppUpdateFileName, fileName)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            downloadUrl.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun downloadAvailableGitHubUpdate() {
        val state = viewModel.uiState.value
        downloadGitHubUpdate(state.appUpdateDownloadUrl, state.appUpdateFileName)
    }

    private fun downloadGitHubUpdate(downloadUrl: String?, fileName: String?) {
        val url = downloadUrl?.trim().orEmpty()
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        if (
            parsed == null ||
            parsed.scheme != "https" ||
            !url.endsWith(".apk", ignoreCase = true)
        ) {
            Toast.makeText(this, "No GitHub APK download is available", Toast.LENGTH_LONG).show()
            return
        }

        val safeFileName = fileName
            ?.trim()
            ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: URLUtil.guessFileName(url, null, "application/vnd.android.package-archive")
        val request = DownloadManager.Request(parsed).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setTitle(safeFileName)
            setDescription("Downloading Hermes WebUI GitHub APK")
            setAllowedOverMetered(true)
            setMimeType("application/vnd.android.package-archive")
            setDestinationInExternalFilesDir(
                this@MainActivity,
                Environment.DIRECTORY_DOWNLOADS,
                safeFileName
            )
        }
        getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(this, "GitHub APK download started", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun showAppUpdateNotification(
        title: String,
        body: String,
        version: String,
        pendingIntent: PendingIntent,
        downloadIntent: PendingIntent?,
        force: Boolean
    ) {
        if (!settingsRepository.isAppUpdateAlertsEnabled()) return
        if (webNotificationPermissionState() != "granted") {
            if (force) {
                requestNotificationPermissionIfNeeded()
                Toast.makeText(this, body, Toast.LENGTH_LONG).show()
            }
            return
        }

        val notification = NotificationCompat.Builder(this, HermesNotificationChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(ContextCompat.getColor(this, R.color.brand_sky))
            .setContentIntent(pendingIntent)
            .apply {
                if (downloadIntent != null) {
                    addAction(0, "Download APK", downloadIntent)
                }
            }
            .build()

        NotificationManagerCompat.from(this).notify(AppUpdateNotificationId, notification)
        settingsRepository.markAppUpdateNotified(version)
    }

    private fun handleAppUpdateIntent(intent: Intent?): Boolean {
        return when (intent?.action) {
            ActionStartPlayUpdate -> {
                startPlayUpdateFlow()
                true
            }
            ActionDownloadAppUpdate -> {
                val downloadUrl = intent.getStringExtra(ExtraAppUpdateDownloadUrl)
                val fileName = intent.getStringExtra(ExtraAppUpdateFileName)
                downloadGitHubUpdate(downloadUrl, fileName)
                true
            }
            else -> false
        }
    }

    private fun startPlayUpdateFlow() {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { updateInfo ->
                if (
                    updateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    updateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                ) {
                    launchPlayUpdate(updateInfo)
                } else {
                    Toast.makeText(this, "No Google Play update is available right now", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Could not start Google Play update", Toast.LENGTH_LONG).show()
            }
    }

    private fun resumePlayUpdateIfNeeded() {
        if (BuildConfig.UPDATE_CHANNEL != "play") return
        appUpdateManager.appUpdateInfo.addOnSuccessListener { updateInfo ->
            if (updateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                launchPlayUpdate(updateInfo)
            }
        }
    }

    private fun launchPlayUpdate(updateInfo: AppUpdateInfo) {
        appUpdateManager.startUpdateFlowForResult(
            updateInfo,
            playUpdateLauncher,
            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
        )
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            HermesNotificationChannelId,
            "Hermes updates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Task completion and attention notifications from Hermes WebUI"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun syncReconnectForegroundService(isReconnecting: Boolean) {
        val state = viewModel.uiState.value
        val sessionId = ReconnectSessionStreamSupport.sessionIdFromUrl(state.currentUrl)
        if (
            !ReconnectBackgroundPolicy.shouldRunForegroundService(
                backgroundReconnectEnabled = state.backgroundReconnectEnabled,
                activityVisible = activityVisible,
                isReconnecting = isReconnecting,
                sseTransportEnabled = state.sseTransportEnabled,
                hasSessionId = sessionId != null
            )
        ) {
            stopReconnectForegroundService()
            return
        }
        if (reconnectServiceRunning) return

        try {
            val sessionTargetUrl = state.currentUrl.takeIf { isTrustedNotificationTarget(it) }
            HermesReconnectService.start(
                this,
                pollIntervalSeconds = state.reconnectPollIntervalSeconds,
                serverUrl = state.settings.serverUrl,
                sessionId = sessionId,
                sessionTargetUrl = sessionTargetUrl,
                cookieHeader = CookieManager.getInstance().getCookie(state.settings.serverUrl),
                sseTransportEnabled = state.sseTransportEnabled,
                isReconnecting = isReconnecting,
                showFullTextOnLockScreen = state.backgroundActivityFullTextEnabled
            )
            reconnectServiceRunning = true
        } catch (_: IllegalStateException) {
            reconnectServiceRunning = false
            viewModel.cancelAutoRetry()
        } catch (_: SecurityException) {
            reconnectServiceRunning = false
            viewModel.cancelAutoRetry()
        }
    }


    private fun stopReconnectForegroundService() {
        if (!reconnectServiceRunning) return
        HermesReconnectService.stop(this)
        reconnectServiceRunning = false
    }

    private fun syncDebugLoggingForegroundService(debugLoggingEnabled: Boolean) {
        val persistedEnabled = settingsRepository.isDebugLoggingEnabled()
        if (!debugLoggingEnabled || !persistedEnabled) {
            if (debugLoggingEnabled && !persistedEnabled) {
                viewModel.setDebugLoggingEnabled(false)
            }
            stopDebugLoggingForegroundService()
            return
        }
        if (debugLoggingServiceRunning) return

        try {
            HermesDebugLoggingService.start(this)
            debugLoggingServiceRunning = true
        } catch (_: IllegalStateException) {
            debugLoggingServiceRunning = false
            viewModel.setDebugLoggingEnabled(false)
        } catch (_: SecurityException) {
            debugLoggingServiceRunning = false
            viewModel.setDebugLoggingEnabled(false)
        }
    }

    private fun stopDebugLoggingForegroundService() {
        if (!debugLoggingServiceRunning) return
        HermesDebugLoggingService.stop(this)
        debugLoggingServiceRunning = false
    }

    private fun latestDebugLogFile(): File? {
        val logDir = File(filesDir, "debug-logs")
        return logDir
            .listFiles()
            ?.filter { it.isFile && it.extension.equals("log", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun shareLatestDebugLog() {
        val source = latestDebugLogFile()
        if (source == null) {
            Toast.makeText(this, "No debug log found yet", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", source)
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(this, "Unable to share debug log", Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Hermes Android debug log")
            putExtra(Intent.EXTRA_TEXT, "Attach this log to your GitHub issue.")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, "Share debug log")
        try {
            startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to share debug logs", Toast.LENGTH_SHORT).show()
        }
    }

    private fun downloadLatestDebugLog() {
        val source = latestDebugLogFile()
        if (source == null) {
            Toast.makeText(this, "No debug log found yet", Toast.LENGTH_SHORT).show()
            return
        }

        val exportDir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "HermesLogs")
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            Toast.makeText(this, "Unable to prepare download folder", Toast.LENGTH_SHORT).show()
            return
        }

        val target = File(exportDir, source.name)
        val copied = runCatching {
            source.copyTo(target, overwrite = true)
            target
        }.getOrNull()
        if (copied == null) {
            Toast.makeText(this, "Failed to save debug log", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(
            this,
            "Saved log to Android/data/$packageName/files/Download/HermesLogs",
            Toast.LENGTH_LONG
        ).show()
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
        val parsed = runCatching { url?.toUri() }.getOrNull() ?: return false
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

    private fun loadOAuthCallbackInMainWebView(url: String) {
        webView.loadUrl(url)
    }

    private fun parseTrustedOAuthStart(url: String): OAuthPopupFlow? {
        val settings = viewModel.uiState.value.settings
        return OAuthPopupFlow.parseAuthorizationStart(url)
            ?.takeIf { it.redirectsToOrigin(settings.serverUrl) }
    }

    private fun isHttpOrHttpsUrl(url: String): Boolean {
        val scheme = runCatching { url.toUri().scheme }.getOrNull()
        return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
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
        val persist = {
            viewModel.saveAppUrls(serverUrl, dashboardUrl)
            urlPolicy = UrlPolicy(viewModel.uiState.value.settings.allowedHosts)
            installHermesWebUiDocumentStartFixes(webView, serverUrl)
            webView.loadUrl(serverUrl)
        }
        validateServerBeforePersist(
            serverUrl,
            onFailure = { result -> showServerValidationRecoveryDialog(serverUrl, result, "Save server") { persist() } }
        ) { persist() }
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

    private fun preflightConfiguredStartupServer(serverUrl: String) {
        val lastLoadedUrl = settingsRepository.getLastLoadedUrl()
        val notificationUrl = notificationTargetUrl(intent)
        val startUrl = notificationUrl ?: if (matchesConfiguredDashboardRoute(lastLoadedUrl)) {
            serverUrl
        } else {
            lastLoadedUrl ?: serverUrl
        }

        serverValidationJob?.cancel()
        DiagnosticsLogger.record(
            this,
            "startup_validation_start",
            mapOf("origin" to DiagnosticsLogger.originOnly(serverUrl))
        )
        viewModel.setServerValidationState(
            isChecking = true,
            message = "Checking Hermes server readiness...",
            isError = false
        )
        serverValidationJob = lifecycleScope.launch {
            val result = HermesApiClient.checkServerReadiness(serverUrl)
            DiagnosticsLogger.record(
                this@MainActivity,
                "startup_validation_result",
                mapOf(
                    "origin" to DiagnosticsLogger.originOnly(serverUrl),
                    "status" to result.status.name,
                    "ready" to result.isReady.toString()
                )
            )
            if (result.isReady || HermesApiClient.isServerReachable(serverUrl)) {
                DiagnosticsLogger.record(
                    this@MainActivity,
                    "startup_validation_decision",
                    mapOf(
                        "origin" to DiagnosticsLogger.originOnly(serverUrl),
                        "decision" to "continue_webview"
                    )
                )
                viewModel.clearServerValidationState()
                webView.loadUrl(startUrl)
                return@launch
            }

            DiagnosticsLogger.record(
                this@MainActivity,
                "startup_validation_decision",
                mapOf(
                    "origin" to DiagnosticsLogger.originOnly(serverUrl),
                    "decision" to "open_settings"
                )
            )
            viewModel.openSettingsWithServerValidation(result.message, details = result.diagnostics)
            Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
        }
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

        validateServerBeforePersist(
            trimmedUrl,
            onFailure = { result ->
                showServerValidationRecoveryDialog(trimmedUrl, result, "Add server") {
                    val profile = viewModel.addServerProfile(
                        name = trimmedName.ifBlank { trimmedUrl },
                        url = trimmedUrl
                    )
                    if (profile != null) {
                        Toast.makeText(this, "Server profile \"${profile.name}\" added (readiness check skipped)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Failed to add profile", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        ) {
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
    }

    private fun handleEditServerProfile(profileId: String, newName: String, newUrl: String) {
        if (!serverUrlValidator.isValid(newUrl)) {
            Toast.makeText(this, "Server URL must be a valid http:// or https:// URL", Toast.LENGTH_LONG).show()
            return
        }
        validateServerBeforePersist(
            newUrl,
            onFailure = { result ->
                showServerValidationRecoveryDialog(newUrl, result, "Save changes") {
                    viewModel.updateServerProfile(profileId, newName, newUrl)
                    Toast.makeText(this, "Profile updated (readiness check skipped)", Toast.LENGTH_LONG).show()
                }
            }
        ) {
            viewModel.updateServerProfile(profileId, newName, newUrl)
            Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDeleteServerProfile(profileId: String) {
        // Clean up the per-server silenced-auth-prompt flag, if any, so a
        // future profile with the same URL starts fresh and the saved set
        // does not slowly accumulate orphans.
        settingsRepository.getProfiles().firstOrNull { it.id == profileId }?.let { profile ->
            settingsRepository.clearSilencedAuthPromptForUrl(profile.url)
        }
        viewModel.deleteServerProfile(profileId)
        Toast.makeText(this, "Profile deleted", Toast.LENGTH_SHORT).show()
    }

    private fun handleSwitchServerProfile(profileId: String) {
        val newProfile = settingsRepository.getProfiles().firstOrNull { it.id == profileId } ?: return

        if (!serverUrlValidator.isValid(newProfile.url)) {
            DiagnosticsLogger.record(
                this,
                "server_switch_health_check_blocked",
                mapOf(
                    "origin" to DiagnosticsLogger.originOnly(newProfile.url),
                    "decision" to "invalid_url"
                )
            )
            Toast.makeText(this, "Invalid server URL: ${newProfile.url}", Toast.LENGTH_LONG).show()
            return
        }

        serverValidationJob?.cancel()
        DiagnosticsLogger.record(
            this,
            "server_switch_health_check_start",
            mapOf(
                "origin" to DiagnosticsLogger.originOnly(newProfile.url),
                "profile_id" to newProfile.id
            )
        )
        viewModel.setServerValidationState(
            isChecking = true,
            message = "Checking ${newProfile.name} before switching...",
            isError = false
        )
        serverValidationJob = lifecycleScope.launch {
            val result = HermesApiClient.checkServerReadiness(newProfile.url)
            val reachable = result.isReady || HermesApiClient.isServerReachable(newProfile.url)
            DiagnosticsLogger.record(
                this@MainActivity,
                "server_switch_health_check_result",
                mapOf(
                    "origin" to DiagnosticsLogger.originOnly(newProfile.url),
                    "profile_id" to newProfile.id,
                    "status" to result.status.name,
                    "ready" to result.isReady.toString(),
                    "reachable" to reachable.toString()
                )
            )

            if (result.isReady) {
                val message = "${newProfile.name} is reachable. Switch to this server now?"
                viewModel.setServerValidationState(
                    isChecking = false,
                    message = message,
                    isError = false
                )
                showServerSwitchConfirmation(newProfile, "Server reachable", message)
                return@launch
            }

            if (reachable && result.status == HermesApiClient.ServerReadinessStatus.AUTH_REQUIRED) {
                // Per-server opt-out: users who have ticked "Don't ask again"
                // for this URL should switch silently rather than re-prompting.
                if (settingsRepository.isAuthPromptSilencedForUrl(newProfile.url)) {
                    DiagnosticsLogger.record(
                        this@MainActivity,
                        "server_switch_auth_required_auto_proceed_silenced",
                        mapOf(
                            "origin" to DiagnosticsLogger.originOnly(newProfile.url),
                            "profile_id" to newProfile.id
                        )
                    )
                    viewModel.clearServerValidationState()
                    performServerProfileSwitch(newProfile)
                    return@launch
                }
                val message = "${newProfile.name} is reachable, but requires sign-in before Android can read /api/status. Switch and sign in?"
                viewModel.setServerValidationState(
                    isChecking = false,
                    message = message,
                    isError = false
                )
                showAuthRequiredSwitchConfirmation(newProfile, message)
                return@launch
            }

            val blockedMessage = "${newProfile.name}: ${result.message}"
            viewModel.setServerValidationState(
                isChecking = false,
                message = blockedMessage,
                isError = true
            )
            DiagnosticsLogger.record(
                this@MainActivity,
                "server_switch_health_check_blocked",
                mapOf(
                    "origin" to DiagnosticsLogger.originOnly(newProfile.url),
                    "profile_id" to newProfile.id,
                    "status" to result.status.name,
                    "decision" to "stay_current_server"
                )
            )
            showServerHealthBlockedDialog(newProfile, result)
        }
    }

    private fun showServerSwitchConfirmation(profile: ServerProfile, title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel") { dialog, _ ->
                DiagnosticsLogger.record(
                    this,
                    "server_switch_cancelled",
                    mapOf("origin" to DiagnosticsLogger.originOnly(profile.url), "profile_id" to profile.id)
                )
                dialog.dismiss()
            }
            .setPositiveButton("Switch") { _, _ ->
                performServerProfileSwitch(profile)
            }
            .show()
    }

    /**
     * Confirmation dialog for the AUTH_REQUIRED-but-reachable switch case.
     * Same Cancel/Switch buttons as [showServerSwitchConfirmation] plus an
     * inline "Don't ask again for this server" checkbox. When ticked, the URL
     * is added to the silenced set so future switches to the same server skip
     * the prompt and go straight to the sign-in page.
     */
    private fun showAuthRequiredSwitchConfirmation(profile: ServerProfile, message: String) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val checkBox = android.widget.CheckBox(this).apply {
            text = "Don't ask again for this server"
        }
        val messageView = android.widget.TextView(this).apply {
            text = message
            setPadding(0, 0, 0, padding)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(messageView)
            addView(checkBox)
        }

        AlertDialog.Builder(this)
            .setTitle("Sign-in required")
            .setView(container)
            .setNegativeButton("Cancel") { dialog, _ ->
                DiagnosticsLogger.record(
                    this,
                    "server_switch_cancelled",
                    mapOf("origin" to DiagnosticsLogger.originOnly(profile.url), "profile_id" to profile.id)
                )
                dialog.dismiss()
            }
            .setPositiveButton("Switch") { _, _ ->
                if (checkBox.isChecked) {
                    settingsRepository.silenceAuthPromptForUrl(profile.url)
                    DiagnosticsLogger.record(
                        this,
                        "server_switch_auth_prompt_silenced",
                        mapOf(
                            "origin" to DiagnosticsLogger.originOnly(profile.url),
                            "profile_id" to profile.id
                        )
                    )
                }
                performServerProfileSwitch(profile)
            }
            .show()
    }

    private fun showServerHealthBlockedDialog(
        profile: ServerProfile,
        result: HermesApiClient.ServerReadinessResult
    ) {
        showServerValidationRecoveryDialog(
            url = profile.url,
            result = result,
            positiveLabel = "Switch anyway"
        ) { performServerProfileSwitch(profile) }
    }

    /**
     * Shared error-recovery dialog shown when /api/status preflight fails for a
     * server URL the user is trying to add, edit, save, or switch to. Shows the
     * server message plus full diagnostic block (HTTP status, body snippet, etc.)
     * and exposes three escape hatches:
     *  - "Open in browser": launch the URL in the system browser so the user can
     *    complete sign-in / inspect the response directly.
     *  - [positiveLabel]: bypass the readiness check and continue (e.g. for
     *    auth-protected servers where /api/status returns 401 even when
     *    the server is healthy).
     *  - "Cancel": no-op.
     */
    private fun showServerValidationRecoveryDialog(
        url: String,
        result: HermesApiClient.ServerReadinessResult,
        positiveLabel: String,
        onProceedAnyway: () -> Unit
    ) {
        val body = buildString {
            appendLine(result.message)
            result.diagnostics?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Diagnostics:")
                append(it)
            }
        }.trim()
        DiagnosticsLogger.record(
            this,
            "server_validation_recovery_dialog_shown",
            mapOf(
                "origin" to DiagnosticsLogger.originOnly(url),
                "status" to result.status.name
            )
        )
        AlertDialog.Builder(this)
            .setTitle("Server check failed")
            .setMessage(body)
            .setNeutralButton("Open in browser") { _, _ ->
                DiagnosticsLogger.record(
                    this,
                    "server_validation_open_browser",
                    mapOf(
                        "origin" to DiagnosticsLogger.originOnly(url),
                        "status" to result.status.name
                    )
                )
                openInExternalBrowser(url)
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton(positiveLabel) { _, _ ->
                DiagnosticsLogger.record(
                    this,
                    "server_validation_proceed_anyway",
                    mapOf(
                        "origin" to DiagnosticsLogger.originOnly(url),
                        "status" to result.status.name
                    )
                )
                onProceedAnyway()
            }
            .show()
    }

    private fun performServerProfileSwitch(profile: ServerProfile) {
        DiagnosticsLogger.record(
            this,
            "server_switch_confirmed",
            mapOf("origin" to DiagnosticsLogger.originOnly(profile.url), "profile_id" to profile.id)
        )
        clearWebViewStateForServerSwitch()

        viewModel.switchServerProfile(profile.id)
        urlPolicy = UrlPolicy(viewModel.uiState.value.settings.allowedHosts)
        installHermesWebUiDocumentStartFixes(webView, profile.url)
        webView.loadUrl(profile.url)
        viewModel.closeSettings()
        Toast.makeText(this, "Switched to ${profile.name}", Toast.LENGTH_SHORT).show()
    }

    private fun clearWebViewStateForServerSwitch() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.clearCache(true)
        webView.clearFormData()
    }

    private fun validateServerBeforePersist(
        serverUrl: String,
        openSettingsOnFailure: Boolean = false,
        onFailure: ((HermesApiClient.ServerReadinessResult) -> Unit)? = null,
        onSuccess: () -> Unit
    ) {
        serverValidationJob?.cancel()
        DiagnosticsLogger.record(
            this,
            "server_validation_start",
            mapOf("origin" to DiagnosticsLogger.originOnly(serverUrl))
        )
        viewModel.setServerValidationState(
            isChecking = true,
            message = "Checking Hermes server readiness...",
            isError = false
        )
        serverValidationJob = lifecycleScope.launch {
            val result = HermesApiClient.checkServerReadiness(serverUrl)
            DiagnosticsLogger.record(
                this@MainActivity,
                "server_validation_result",
                mapOf(
                    "origin" to DiagnosticsLogger.originOnly(serverUrl),
                    "status" to result.status.name,
                    "ready" to result.isReady.toString()
                )
            )

            // Soft-pass: a 401/403 from /api/status with a reachable server is
            // a normal sign-in-required Hermes deployment, not a broken server.
            // Treat it as ready and let the WebView handle the sign-in flow,
            // matching the server-switch path that just prompts "and sign in".
            // Without this, auth-protected servers (Tailscale-served Hermes,
            // OIDC-protected deployments, etc.) get blocked from being saved
            // even though they work fine in the browser.
            val authRequiredButReachable = !result.isReady &&
                result.status == HermesApiClient.ServerReadinessStatus.AUTH_REQUIRED &&
                HermesApiClient.isServerReachable(serverUrl)
            if (authRequiredButReachable) {
                DiagnosticsLogger.record(
                    this@MainActivity,
                    "server_validation_soft_pass_auth_required",
                    mapOf("origin" to DiagnosticsLogger.originOnly(serverUrl))
                )
                viewModel.clearServerValidationState()
                Toast.makeText(
                    this@MainActivity,
                    "Server reachable — sign in on the Hermes page to finish.",
                    Toast.LENGTH_LONG
                ).show()
                onSuccess()
                return@launch
            }

            if (!result.isReady) {
                viewModel.setServerValidationState(
                    isChecking = false,
                    message = result.message,
                    isError = true,
                    details = result.diagnostics
                )
                val handled = onFailure != null
                if (handled) {
                    onFailure.invoke(result)
                } else {
                    if (openSettingsOnFailure) {
                        viewModel.openSettingsWithServerValidation(result.message, details = result.diagnostics)
                    }
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            viewModel.clearServerValidationState()
            onSuccess()
        }
    }

    private fun shouldDirectCaptureImage(fileChooserParams: WebChromeClient.FileChooserParams?): Boolean {
        if (fileChooserParams?.isCaptureEnabled != true) return false
        val acceptTypes = fileChooserParams.acceptTypes.orEmpty()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        if (acceptTypes.isEmpty()) return true
        return acceptTypes.any { it == "image/*" || it.startsWith("image/") }
    }

    private fun normalizedMimeTypes(fileChooserParams: WebChromeClient.FileChooserParams?): Array<String> {
        val acceptTypes = fileChooserParams?.acceptTypes.orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        return if (acceptTypes.isEmpty()) arrayOf("*/*") else acceptTypes.toTypedArray()
    }

    private fun createTempCameraCaptureUri(): Uri? {
        return runCatching {
            val captureDir = File(cacheDir, "upload-captures").apply { mkdirs() }
            val captureFile = File.createTempFile("hermes-upload-", ".jpg", captureDir)
            FileProvider.getUriForFile(this, "$packageName.fileprovider", captureFile)
        }.getOrNull()
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

    private fun rememberActiveOAuthPopup(popup: WebView, flow: OAuthPopupFlow) {
        activeOAuthPopup = popup
        activeOAuthFlow = flow
        refreshActiveOAuthTimeout()
    }

    private fun rememberActiveMainFrameOAuth(flow: OAuthPopupFlow) {
        activeMainFrameOAuthFlow = flow
        refreshActiveOAuthTimeout()
    }

    private fun clearActiveMainFrameOAuth() {
        activeMainFrameOAuthFlow = null
        if (activeOAuthPopup == null) {
            oauthFlowTimeoutMs = 0L
        }
    }

    private fun refreshActiveOAuthTimeout() {
        oauthFlowTimeoutMs = System.currentTimeMillis() + OAUTH_FLOW_TIMEOUT_MS
    }

    private fun clearActiveOAuthPopup() {
        activeOAuthPopup = null
        activeOAuthFlow = null
        if (activeMainFrameOAuthFlow == null) {
            oauthFlowTimeoutMs = 0L
        }
    }

    /** Cleanup OAuth state if it has timed out.
     *
     * OAuth flows should complete quickly. If a popup or top-level auth flow stays
     * active longer than the timeout, clear it to prevent resource leaks.
     */
    private fun cleanupExpiredOAuthPopup() {
        val hasActiveOAuth = activeOAuthPopup != null || activeMainFrameOAuthFlow != null
        if (hasActiveOAuth && System.currentTimeMillis() > oauthFlowTimeoutMs) {
            try {
                activeOAuthPopup?.destroy()
            } catch (_: Exception) {
                // WebView may already be destroyed; ignore errors
            }
            clearActiveOAuthPopup()
            clearActiveMainFrameOAuth()
        }
    }

    private fun normalizeServerProfileUrl(url: String): String {
        return url.trim().trimEnd('/').lowercase()
    }
}
