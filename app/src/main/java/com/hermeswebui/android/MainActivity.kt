package com.hermeswebui.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.text.InputType
import android.util.Base64
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.ClientCertRequest
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import com.hermeswebui.android.launcher.LauncherIconManager
import com.hermeswebui.android.data.ServerType
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.content.FileProvider
import androidx.core.app.NotificationCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.hermeswebui.android.background.DebugLogBootstrap
import com.hermeswebui.android.background.HermesForegroundServiceCoordinator
import com.hermeswebui.android.core.network.HardwareKeyboardState
import com.hermeswebui.android.ui.onboarding.WelcomeGate
import com.hermeswebui.android.ui.onboarding.WelcomeScreen
import com.hermeswebui.android.core.network.LocalNetworkPolicy
import com.hermeswebui.android.core.security.NavigationDecision
import com.hermeswebui.android.core.security.ProxyAuthNavigation
import com.hermeswebui.android.core.security.UrlOrigins
import com.hermeswebui.android.core.security.UrlPolicy
import com.hermeswebui.android.core.security.WebTrustPolicy
import com.hermeswebui.android.webview.ClipboardImagePolicy
import com.hermeswebui.android.webview.HermesClipboardBridgeCoordinator
import com.hermeswebui.android.webview.HermesWebView
import com.hermeswebui.android.data.DiagnosticsLogger
import com.hermeswebui.android.data.HermesApiClient
import com.hermeswebui.android.data.HttpAuthCredential
import com.hermeswebui.android.data.ServerProfile
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.notification.HermesNotificationBridgeCoordinator
import com.hermeswebui.android.security.ClientCertificateRequestSupport
import com.hermeswebui.android.notification.HermesNotificationPresenter
import com.hermeswebui.android.domain.ServerUrlValidator
import com.hermeswebui.android.server.HermesServerProfileCoordinator
import com.hermeswebui.android.server.SelectedServerNavigation
import com.hermeswebui.android.domain.ShareIntentParser
import com.hermeswebui.android.domain.TailscaleEndpointDetector
import com.hermeswebui.android.ui.MainViewModel
import com.hermeswebui.android.ui.MainViewModelFactory
import com.hermeswebui.android.ui.DebugLogFloatingOverlay
import com.hermeswebui.android.ui.settings.SettingsScreen
import com.hermeswebui.android.ui.settings.VpnLaunchAppOption
import com.hermeswebui.android.ui.web.WebShell
import com.hermeswebui.android.webui.HermesWebUiScripts
import com.hermeswebui.android.webui.VoiceCaptureLifecycleGate
import com.hermeswebui.android.webview.HermesMediaPlaybackPolicy
import com.hermeswebui.android.webview.HermesWebViewConfigurator
import com.hermeswebui.android.webview.HermesWebViewDownloadListener
import com.hermeswebui.android.update.HermesAppUpdateCoordinator
import com.hermeswebui.android.update.createPlayUpdateClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private const val HermesNotificationBridgeName = "HermesAndroidNotifications"
private const val HermesClipboardBridgeName = "HermesAndroidClipboard"
private const val HermesNotificationChannelId = "hermes_webui_notifications"
private const val HermesNotificationIdBase = 10_000
private const val HermesVpnReconnectNotificationId = 9_900
private const val HermesGithubIssuesListUrl = "https://github.com/hermes-webui/hermes-android/issues"
private const val HermesGithubNewIssueUrl = "https://github.com/hermes-webui/hermes-android/issues/new/choose"
private const val TailscaleAndroidPackage = "com.tailscale.ipn"
private const val TailscaleConnectVpnAction = "com.tailscale.ipn.CONNECT_VPN"
private const val VpnReconnectPollIntervalMs = 1_000L
private const val TailscaleAutoConnectFallbackMs = 10_000L
private const val EnableAppSettingsSidebarShim = true

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

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel
    private lateinit var webView: HermesWebView
    private lateinit var settingsRepository: SettingsRepository

    private var urlPolicy = UrlPolicy(emptySet())
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraCaptureUri: Uri? = null
    private var pendingLocalNetworkPermissionAction: (() -> Unit)? = null
    private var pendingLocalNetworkPermissionDeniedAction: (() -> Unit)? = null
    private var viewportFixScriptHandler: ScriptHandler? = null
    private var pinchZoomScriptHandler: ScriptHandler? = null
    private var microphoneFallbackScriptHandler: ScriptHandler? = null
    private var notificationBridgeScriptHandler: ScriptHandler? = null
    private var routeRecoveryScriptHandler: ScriptHandler? = null
    private var appSettingsEntryScriptHandler: ScriptHandler? = null
    private var enterKeyNewlineScriptHandler: ScriptHandler? = null
    private var suppressClarifyAutofocusScriptHandler: ScriptHandler? = null
    private var lastVpnLaunchAttemptElapsedMs: Long = 0L
    private var pendingVpnGuardUrl: String? = null
    private var vpnReconnectWaitJob: Job? = null
    private var tailscaleLaunchFallbackJob: Job? = null
    private var activityVisible = false
    private lateinit var appUpdateCoordinator: HermesAppUpdateCoordinator
    private lateinit var notificationBridgeCoordinator: HermesNotificationBridgeCoordinator
    private lateinit var clipboardBridgeCoordinator: HermesClipboardBridgeCoordinator
    private var clipboardBridgeScriptHandler: ScriptHandler? = null
    private lateinit var notificationPresenter: HermesNotificationPresenter
    private lateinit var serverProfileCoordinator: HermesServerProfileCoordinator
    private lateinit var foregroundServiceCoordinator: HermesForegroundServiceCoordinator
    private val appUpdateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            appUpdateCoordinator.onDownloadComplete(downloadId)
        }
    }

    private var activeOAuthPopup: WebView? = null
    private var activeOAuthFlow: OAuthPopupFlow? = null
    private var activeMainFrameOAuthFlow: OAuthPopupFlow? = null
    private val proxyAuthNavigation = ProxyAuthNavigation()
    // Last main-frame URL the app loaded, allowed, or committed. A redirect only speaks for the
    // configured server when this hop is exactly that origin (Issue #134).
    private var lastMainFrameHopUrl: String? = null
    private var mainFrameNavigationGeneration = 0L
    private var serverTypeDocumentGeneration = 0L
    private var proxySignInDialog: android.app.AlertDialog? = null
    private var pendingHttpAuthPrompt: PendingHttpAuthPrompt? = null
    private var oauthFlowTimeoutMs: Long = 0
    private val OAUTH_FLOW_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    private val oauthReturnNavigation = OAuthReturnNavigation()

    // Optional Sign in with Chrome: the provider runs in an Auth Tab and only a verified callback
    // for the flow that launched it is loaded back into the main WebView.
    private data class PendingBrowserSignIn(
        val flow: OAuthPopupFlow,
        val authorizeUrl: String,
        val serverUrl: String
    )
    private var pendingBrowserSignIn: PendingBrowserSignIn? = null
    private val authTabLauncher = AuthTabIntent.registerActivityResultLauncher(this, ::onBrowserSignInResult)

    // Popups created by onCreateWindow that have not yet been destroyed. A window.open('') that
    // never navigates never reaches the popup's shouldOverrideUrlLoading/onPageStarted, so it would
    // otherwise leak its WebView/renderer resources. Tracked so an orphan sweep and destroyPopup()
    // can clean them up without double-destroying.
    private val trackedPopups = mutableSetOf<WebView>()
    private val ORPHAN_POPUP_SWEEP_MS = 15 * 1000L

    private val serverUrlValidator = ServerUrlValidator()

    private data class PendingHttpAuthPrompt(
        val handler: HttpAuthHandler,
        val host: String?,
        val realm: String?,
        var dialog: AlertDialog? = null,
        var resolved: Boolean = false
    )

    private var lastBackPressTime: Long = 0
    private var backPressExitStage: Int = 0
    private val BACK_PRESS_TIMEOUT_MS = 2000L // 2 seconds

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        filePathCallback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
        filePathCallback = null
        pendingCameraCaptureUri = null
        if (!viewModel.hasStagedSharedFiles) viewModel.dismissShareBanner()
    }

    private val cameraCaptureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val resultUri = pendingCameraCaptureUri?.takeIf { success }
        filePathCallback?.onReceiveValue(resultUri?.let { arrayOf(it) })
        filePathCallback = null
        pendingCameraCaptureUri = null
        if (!viewModel.hasStagedSharedFiles) viewModel.dismissShareBanner()
    }

    private val audioPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val userDenied = voiceCaptureGate.onRecordAudioPromptResult(granted) { request ->
                isTrustedPermissionOrigin(request.origin)
            }
            if (userDenied) {
                Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    // WebUI owns existing capture/playback. This proposed host signal requires a WebUI consumer.
    private val voiceCaptureGate: VoiceCaptureLifecycleGate<PermissionRequest> = VoiceCaptureLifecycleGate(
        grantAudioCapture = { it.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) },
        denyAudioCapture = { it.deny() },
        launchRecordAudioPrompt = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        signalAppForeground = ::signalAppForegroundToWebUi
    )

    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val onGranted = pendingLocalNetworkPermissionAction
            val onDenied = pendingLocalNetworkPermissionDeniedAction
            pendingLocalNetworkPermissionAction = null
            pendingLocalNetworkPermissionDeniedAction = null
            if (granted) {
                onGranted?.invoke()
            } else {
                // Only surface the denial toast when there is no best-effort fallback
                // (i.e. this is the hard-fail WebView error-recovery path). Pre-load
                // preflight denials on OEM builds that don't expose a real grant toggle
                // should not produce a visible error because the load will still proceed.
                if (onDenied == null) {
                    Toast.makeText(this, "Local network permission denied", Toast.LENGTH_SHORT).show()
                }
                onDenied?.invoke()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationBridgeCoordinator.onPermissionResult(granted)
            val permission = notificationBridgeCoordinator.permissionState()
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
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(settingsRepository, settingsRepository, defaultUrl, defaultDashboardUrl)
        )[MainViewModel::class.java]
        notificationPresenter = HermesNotificationPresenter(
            context = this,
            channelId = HermesNotificationChannelId,
            notificationIdBase = HermesNotificationIdBase,
            isTrustedTarget = ::isTrustedNotificationTarget
        )
        notificationBridgeCoordinator = HermesNotificationBridgeCoordinator(
            context = this,
            bridgeName = HermesNotificationBridgeName,
            settingsRepository = settingsRepository,
            isTrustedSource = ::isTrustedNotificationBridgeSource,
            showNotification = { payload ->
                if (webNotificationPermissionState() != "granted") {
                    false
                } else {
                    notificationPresenter.showNotification(
                        payload = payload,
                        fallbackTargetUrl = viewModel.uiState.value.currentUrl
                    )
                }
            },
            requestNotificationPermissionLauncher = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launchPostNotificationsPermission()
                }
            },
            runOnUiThread = { action -> runOnUiThread(action) }
        )
        clipboardBridgeCoordinator = HermesClipboardBridgeCoordinator(
            context = this,
            bridgeName = HermesClipboardBridgeName,
            isTrustedSource = ::isTrustedNotificationBridgeSource,
            runOnUiThread = { action -> runOnUiThread(action) }
        )
        appUpdateCoordinator = HermesAppUpdateCoordinator(
            context = this,
            activityScope = lifecycleScope,
            settingsRepository = settingsRepository,
            viewModel = viewModel,
            playUpdateClient = createPlayUpdateClient(this, playUpdateLauncher),
            updateChannel = BuildConfig.UPDATE_CHANNEL,
            githubReleasesApiUrl = BuildConfig.GITHUB_RELEASES_API_URL,
            githubReleasesPageUrl = BuildConfig.GITHUB_RELEASES_PAGE_URL,
            notificationChannelId = HermesNotificationChannelId,
            notificationPermissionState = ::webNotificationPermissionState,
            requestNotificationPermissionIfNeeded = ::requestNotificationPermissionIfNeeded,
            isActivityVisible = { activityVisible },
            appVersionName = ::appVersionName
        )
        appUpdateCoordinator.cleanupInstalledGitHubUpdateArtifact()
        ContextCompat.registerReceiver(
            this,
            appUpdateDownloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        serverProfileCoordinator = HermesServerProfileCoordinator(
            context = this,
            activityScope = lifecycleScope,
            settingsRepository = settingsRepository,
            viewModel = viewModel,
            serverUrlValidator = serverUrlValidator,
            onOpenInExternalBrowser = ::openInExternalBrowser,
            onPerformServerProfileSwitch = ::performServerProfileSwitch,
            onRefreshConfiguredHermes = ::loadServerUrlWithVpnGuard,
            onApproveProxySignIn = proxyAuthNavigation::begin
        )
        foregroundServiceCoordinator = HermesForegroundServiceCoordinator(
            context = this,
            settingsRepository = settingsRepository,
            isTrustedNotificationTarget = ::isTrustedNotificationTarget,
            onCancelAutoRetry = viewModel::cancelAutoRetry,
            onSetDebugLoggingEnabled = viewModel::setDebugLoggingEnabled
        )
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
        applyScreenshotSecurity(settingsRepository.isBlockScreenshotsEnabled())
        webView = buildWebView()
        installHermesWebUiDocumentStartFixes(webView, viewModel.uiState.value.settings.serverUrl)

        if (!appUpdateCoordinator.handleIntent(intent)) {
            handleShareIntent(intent)
        }

        setContent {
            AppContent(
                onReload = { refreshConfiguredHermes() },
                onOpenExternal = { openInExternalBrowser(viewModel.uiState.value.currentUrl) },
                onSaveSettings = { serverUrl -> saveSettings(serverUrl) },
                onRequestExit = { finish() }
            )
        }

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                foregroundServiceCoordinator.onUiStateChanged(
                    state = state,
                    activityVisible = activityVisible
                )
            }
        }

        val settings = viewModel.uiState.value.settings
        // A launcher open is a plain MAIN intent; deep links and shares ask for something specific
        // and must not be interrupted by the first-run welcome guide.
        val isPlainLaunch = intent?.data == null &&
            (intent?.action == null || intent?.action == Intent.ACTION_MAIN)
        val hasSeenWelcome = settingsRepository.hasSeenWelcomeGuide()
        if (WelcomeGate.shouldMarkSeenSilently(settings.isConfigured, hasSeenWelcome)) {
            settingsRepository.markWelcomeGuideSeen()
        }
        if (!settings.isConfigured) {
            if (WelcomeGate.shouldShowOnLaunch(settings.isConfigured, hasSeenWelcome, isPlainLaunch)) {
                viewModel.showWelcome()
            } else {
                viewModel.openSettings()
            }
        } else {
            preflightConfiguredStartupServer(settings.serverUrl)
            appUpdateCoordinator.scheduleAutomaticAppUpdateCheck()
        }
        // Issue #170: a cold start from the native recovery link must open Settings like onNewIntent
        // does. Skip on recreation so a rotation or process restart does not reopen it.
        if (savedInstanceState == null && isSettingsDeepLink(intent.data)) {
            viewModel.openSettings()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (appUpdateCoordinator.handleIntent(intent)) {
            return
        }
        if (notificationPresenter.handleIntent(intent, ::loadInMainWebView)) {
            return
        }
        if (!handleDeepLink(intent)) {
            handleShareIntent(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
        voiceCaptureGate.onStart()
    }

    override fun onResume() {
        super.onResume()
        voiceCaptureGate.onResume()
        if (::webView.isInitialized) {
            activityVisible = true
            viewModel.refreshFeatureFlagsFromRepository()
            foregroundServiceCoordinator.onActivityResumed()
            viewModel.onAppForegrounded()
            updateWebNotificationPermissionState()
            appUpdateCoordinator.resumePendingGitHubInstallIfReady()
            viewModel.resumeAutoRetryIfNeeded()
            startVpnReconnectWaitLoopIfNeeded()
            appUpdateCoordinator.resumePlayUpdateIfNeeded()
            appUpdateCoordinator.scheduleAutomaticAppUpdateCheck()
        }
    }

    override fun onPause() {
        // Report host state first; stopping existing tracks requires WebUI support.
        voiceCaptureGate.onPause()
        if (::webView.isInitialized) {
            viewModel.onAppBackgrounded()
            appUpdateCoordinator.stopPendingGitHubDownloadMonitor()
        }
        super.onPause()
        // If OAuth flow has timed out, clean up the popup to prevent resource leaks.
        cleanupExpiredOAuthPopup()
    }

    override fun onStop() {
        super.onStop()
        activityVisible = false
        voiceCaptureGate.onStop()
        appUpdateCoordinator.cancelAutomaticAppUpdateCheck()
        val state = viewModel.uiState.value
        foregroundServiceCoordinator.onActivityStopped(state, activityVisible)
        // Clean up any lingering OAuth popup on app stop.
        cleanupExpiredOAuthPopup()
    }

    override fun onDestroy() {
        if (::serverProfileCoordinator.isInitialized) serverProfileCoordinator.dispose()
        proxySignInDialog?.dismiss()
        pendingHttpAuthPrompt?.let { prompt ->
            cancelHttpAuthPrompt(prompt)
            prompt.dialog?.dismiss()
        }
        proxyAuthNavigation.clear()
        stopVpnReconnectWaitLoop()
        runCatching { unregisterReceiver(appUpdateDownloadReceiver) }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The manifest handles keyboard|keyboardHidden without recreating the activity,
        // so re-sync the hardware-keyboard flag here to cover attach/detach while open.
        syncHardwareKeyboardState()
    }

    /** Handles Hermes app deep links.
     *
     * hermes://app/settings opens native Android settings. hermes://session/{id}
     * navigates the WebView to {serverUrl}/session/{id}, the Hermes WebUI
     * session route (see DeepLinkRoutes).
     * Returns true if the intent was consumed, false if it should fall through.
     */
    private fun isSettingsDeepLink(data: Uri?): Boolean =
        data != null && data.scheme == "hermes" && data.host == "app" && data.path == "/settings"

    private fun sessionIdFromDeepLink(data: Uri?): String? =
        if (data != null && data.scheme == "hermes" && data.host == "session") {
            data.lastPathSegment?.takeIf { it.isNotBlank() }
        } else {
            null
        }

    private fun handleDeepLink(intent: Intent): Boolean {
        val data = intent.data ?: return false
        if (data.scheme != "hermes") return false

        if (isSettingsDeepLink(data)) {
            viewModel.openSettings()
            return true
        }

        val sessionId = sessionIdFromDeepLink(data) ?: return false
        val serverUrl = viewModel.uiState.value.settings.serverUrl
        if (serverUrl.isBlank()) {
            Toast.makeText(this, "Configure a server URL in Settings first", Toast.LENGTH_LONG).show()
            viewModel.openSettings()
            return true
        }
        val sessionUrl = DeepLinkRoutes.sessionUrl(serverUrl, sessionId)
        if (!urlPolicy.isAllowed(sessionUrl)) {
            Toast.makeText(this, "Session URL is not allowlisted", Toast.LENGTH_LONG).show()
            return true
        }
        loadServerUrlWithVpnGuard(sessionUrl)
        return true
    }

    @Composable
    private fun AppContent(
        onReload: () -> Unit,
        onOpenExternal: () -> Unit,
        onSaveSettings: (String) -> Unit,
        onRequestExit: () -> Unit
    ) {
        val uiState by viewModel.uiState.collectAsState()
        val serverProfiles by viewModel.serverProfiles.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }
        val launcherIcons = remember { LauncherIconManager(this) }
        var launcherIcon by remember(uiState.isSettingsVisible) { mutableStateOf(launcherIcons.selectedIcon()) }
        // queryIntentActivities + loadLabel for every launcher app is too slow for the frame that
        // opens Settings, so resolve it off the main thread (keyed so it refreshes on each open).
        val vpnLaunchAppOptions by produceState<List<VpnLaunchAppOption>>(
            initialValue = emptyList(),
            key1 = uiState.isSettingsVisible
        ) {
            value = if (uiState.isSettingsVisible) {
                withContext(Dispatchers.Default) { discoverLaunchableApps() }
            } else {
                emptyList()
            }
        }

        LaunchedEffect(uiState.pendingShareBanner) {
            val banner = uiState.pendingShareBanner ?: return@LaunchedEffect
            snackbarHostState.showSnackbar(banner)
        }

        LaunchedEffect(uiState.isSettingsVisible) {
            if (uiState.isSettingsVisible && ::webView.isInitialized) detectLoadedServerType(webView)
        }

        // Auto-reload when the retry loop detects the server is back.
        LaunchedEffect(Unit) {
            viewModel.autoReloadEvent.collect {
                refreshConfiguredHermes()
            }
        }

        BackHandler {
            when {
                uiState.isSettingsVisible -> {
                    backPressExitStage = 0
                    viewModel.closeSettings()
                }
                else -> handleWebBackNavigation(onRequestExit)
            }
        }

        val isDark = isSystemInDarkTheme()
        val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ctx = LocalContext.current
            if (isDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            if (isDark) HermesDarkColorScheme else HermesLightColorScheme
        }
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as Activity).window
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
            }
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
                            onBack = if (webView.canGoBack()) {{ goBackInMainWebView() }} else null
                        )
                        SnackbarHost(hostState = snackbarHostState)

                        // Anti-phishing chip: shows the current host while an in-app OAuth flow is
                        // on a non-allowlisted origin, since the WebView has no URL bar.
                        uiState.oauthInFlowHost?.let { host ->
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 8.dp),
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.errorContainer,
                                tonalElevation = 4.dp
                            ) {
                                Text(
                                    text = "🔒 Signing in on $host",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                if (uiState.isSettingsVisible) {
                    SettingsScreen(
                    initialServerUrl = uiState.settings.serverUrl,
                    isConfigured = uiState.settings.isConfigured,
                    backgroundReconnectEnabled = uiState.backgroundReconnectEnabled,
                    backgroundActivityFullTextEnabled = uiState.backgroundActivityFullTextEnabled,
                    reconnectPollIntervalSeconds = uiState.reconnectPollIntervalSeconds,
                    requireVpnForTailscaleEnabled = uiState.requireVpnForTailscaleEnabled,
                    vpnLaunchPackageName = uiState.vpnLaunchPackageName,
                    vpnLaunchAppOptions = vpnLaunchAppOptions,
                    sseTransportEnabled = uiState.sseTransportEnabled,
                    sseSupportStatus = uiState.sseSupportStatus,
                    debugLoggingEnabled = uiState.debugLoggingEnabled,
                    blockScreenshotsEnabled = uiState.blockScreenshotsEnabled,
                    appUpdateAlertsEnabled = uiState.appUpdateAlertsEnabled,
                    automaticAppUpdateChecksEnabled = uiState.automaticAppUpdateChecksEnabled,
                    appUpdateChannelLabel = appUpdateCoordinator.appUpdateChannelLabel(),
                    appUpdateStatus = uiState.appUpdateStatus,
                    appUpdateReleaseUrl = uiState.appUpdateReleaseUrl,
                    appUpdateDownloadUrl = uiState.appUpdateDownloadUrl,
                    appUpdateInstallReady = uiState.appUpdateInstallReady,
                    appUpdateReleaseNotes = uiState.appUpdateReleaseNotes,
                    clientCertificateUri = uiState.clientCertificateUri,
                    clientCertificatePassword = uiState.clientCertificatePassword,
                    serverValidation = uiState.serverValidation,
                    appVersionLabel = "Version ${appVersionName()}",
                    serverProfiles = serverProfiles,
                    onSave = onSaveSettings,
                    onDismiss = { viewModel.closeSettings() },
                    onShowWelcome = { viewModel.showWelcome() },
                    onSetBackgroundReconnect = { enabled ->
                        if (enabled) {
                            requestNotificationPermissionIfNeeded()
                        }
                        viewModel.setBackgroundReconnectEnabled(enabled)
                        foregroundServiceCoordinator.onUiStateChanged(
                            state = viewModel.uiState.value,
                            activityVisible = activityVisible
                        )
                    },
                    onSetBackgroundActivityFullTextEnabled = { enabled ->
                        viewModel.setBackgroundActivityFullTextEnabled(enabled)
                    },
                    onSetReconnectPollIntervalSeconds = { seconds ->
                        viewModel.setReconnectPollIntervalSeconds(seconds)
                    },
                    onSetRequireVpnForTailscaleEnabled = { enabled ->
                        viewModel.setRequireVpnForTailscaleEnabled(enabled)
                    },
                    rememberHttpAuthEnabled = uiState.rememberHttpAuthEnabled,
                    savedHttpAuthCredentialCount = uiState.savedHttpAuthCredentialCount,
                    onSetRememberHttpAuthEnabled = { enabled ->
                        viewModel.setRememberHttpAuthEnabled(enabled)
                    },
                    onForgetSavedHttpAuth = {
                        viewModel.forgetSavedHttpAuthCredentials()
                    },
                    browserSignInEnabled = uiState.browserSignInEnabled,
                    onSetBrowserSignInEnabled = { enabled ->
                        viewModel.setBrowserSignInEnabled(enabled)
                    },
                    onCopyBrowserSignInAssetLinks = {
                        copyBrowserSignInAssetLinks()
                    },
                    onSetVpnLaunchPackageName = { packageName ->
                        viewModel.setVpnLaunchPackageName(packageName)
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
                        foregroundServiceCoordinator.onUiStateChanged(
                            state = viewModel.uiState.value,
                            activityVisible = activityVisible
                        )
                    },
                    onSetBlockScreenshotsEnabled = { enabled ->
                        viewModel.setBlockScreenshotsEnabled(enabled)
                        applyScreenshotSecurity(enabled)
                    },
                    onSetAppUpdateAlertsEnabled = { enabled ->
                        if (enabled) {
                            requestNotificationPermissionIfNeeded()
                        }
                        viewModel.setAppUpdateAlertsEnabled(enabled)
                    },
                    onSetClientCertificateConfig = { uri, password ->
                        viewModel.setClientCertificateConfig(uri, password)
                    },
                    onClearClientCertificateConfig = {
                        viewModel.clearClientCertificateConfig()
                    },
                    onSetAutomaticAppUpdateChecksEnabled = { enabled ->
                        viewModel.setAutomaticAppUpdateChecksEnabled(enabled)
                        if (enabled) {
                            appUpdateCoordinator.scheduleAutomaticAppUpdateCheck()
                        } else {
                            appUpdateCoordinator.cancelAutomaticAppUpdateCheck()
                        }
                    },
                    onCheckAppUpdates = { appUpdateCoordinator.checkForAppUpdates(force = true) },
                    onDownloadAppUpdate = { appUpdateCoordinator.downloadAvailableGitHubUpdate() },
                    onOpenAppUpdateRelease = {
                        val releaseUrl = uiState.appUpdateReleaseUrl
                        when {
                            releaseUrl.isNullOrBlank() -> Unit
                            releaseUrl.startsWith("play://") -> appUpdateCoordinator.startPlayUpdateFlow()
                            else -> openInExternalBrowser(releaseUrl)
                        }
                    },
                    onShareDebugLog = { shareLatestDebugLog() },
                    onDownloadDebugLog = { downloadLatestDebugLog() },
                    onViewGithubIssues = { openInExternalBrowser(HermesGithubIssuesListUrl) },
                    onNewGithubIssue = { openInExternalBrowser(HermesGithubNewIssueUrl) },
                    onAddProfile = { name, url, type -> serverProfileCoordinator.handleAddServerProfile(name, url, type) },
                    onDeleteProfile = { profileId -> serverProfileCoordinator.handleDeleteServerProfile(profileId) },
                    onEditProfile = { profileId, newName, newUrl, type -> serverProfileCoordinator.handleEditServerProfile(profileId, newName, newUrl, type) },
                    onEditCurrentServer = { name, url, type ->
                        val current = settingsRepository.getActiveProfile()
                        if (current != null) {
                            serverProfileCoordinator.handleEditServerProfile(current.id, name, url, type)
                        } else {
                            saveSettings(url, name, type)
                        }
                    },
                    onSwitchProfile = { profileId -> serverProfileCoordinator.handleSwitchServerProfile(profileId) },
                    onReconnectCurrentServer = { refreshConfiguredHermes(closeSettings = true) },
                    onClearServerValidation = { viewModel.clearServerValidationState() },
                    launcherIcon = launcherIcon,
                    onSetLauncherIcon = { choice ->
                        try {
                            launcherIcons.select(choice)
                            launcherIcon = launcherIcons.selectedIcon()
                        } catch (exception: RuntimeException) {
                            android.util.Log.e("HermesLauncher", "Launcher icon selection failed", exception)
                            launcherIcon = launcherIcons.selectedIcon()
                            Toast.makeText(this@MainActivity, R.string.launcher_icon_error, Toast.LENGTH_LONG).show()
                        }
                    }
                )
            } // end if (isSettingsVisible)

            // First-run welcome guide (also reopened from Settings). Above Settings so it covers the
            // sheet it was opened from, below the debug overlay, with the same safe-area padding as the shell.
            if (uiState.isWelcomeVisible) {
                WelcomeScreen(
                    onFinish = { finishWelcomeGuide() },
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                )
            }

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

    // This listener only restores WebView focus and returns false so WebView retains
    // click handling and accessibility semantics.
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun buildWebView(): HermesWebView {
        return HermesWebView(this).apply {
            HermesWebViewConfigurator.configureMainWebView(
                webView = this,
                appVersionName = appVersionName(),
                configureStorageAndCache = ::configureWebViewStorageAndCache,
                disableWebViewDarkening = ::disableWebViewDarkening
            )
            installHermesNotificationWebMessageBridge(this)
            clipboardBridgeCoordinator.installBridge(this)
            acceptsImageInput = { matchesConfiguredWebUiRoute(url) }
            onImageCommitted = ::deliverCommittedImage
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                    if (!v.hasFocus()) {
                        v.requestFocus()
                    }
                }
                false
            }
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
                    return createPopupWindow(resultMsg)
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (request == null) return
                    runOnUiThread {
                        handleWebViewPermissionRequest(request)
                    }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                    super.onPermissionRequestCanceled(request)
                    if (request != null) {
                        voiceCaptureGate.onCaptureRequestCanceled(request)
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    if (filePathCallback == null) return false
                    // FileChooserParams carries no requester origin, so this is a top-level gate.
                    val requesterIsTrustedWebUi = matchesConfiguredWebUiRoute(webView?.url)
                    val staged = viewModel.consumeSharedFileUris(requesterIsTrustedWebUi).map(Uri::parse)
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
                    val serverUrl = viewModel.uiState.value.settings.serverUrl
                    if (request.isForMainFrame &&
                        !proxyAuthNavigation.allows(serverUrl, target) &&
                        !urlPolicy.isAllowed(target) &&
                        activeMainFrameOAuthFlow == null &&
                        parseTrustedOAuthStart(target) == null &&
                        request.isRedirect &&
                        matchesConfiguredWebUiRoute(view?.url)
                    ) {
                        val origin = ProxyAuthNavigation.mainFrameRedirectConsentOrigin(serverUrl, lastMainFrameHopUrl, target)
                        if (origin != null) {
                            confirmProxySignInRedirect(serverUrl, origin, target)
                            return true
                        }
                    }
                    val handled = if (request.isForMainFrame && proxyAuthNavigation.allows(serverUrl, target)) {
                        // Consent-allowed callbacks bypass handleMainWebViewNavigation, but must still
                        // end the in-app OAuth flow so later external links are externalized.
                        completeMainFrameOAuthIfCallback(target)
                        false
                    } else {
                        handleMainWebViewNavigation(target)
                    }
                    if (!handled && request.isForMainFrame) {
                        recordMainFrameHop(target)
                        restrictMediaPlaybackBeforeNavigation(target)
                    }
                    return handled
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    serverTypeDocumentGeneration++
                    handleMainWebViewPageStarted(url)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    applyHermesWebViewCompatibilityFixes(view, url)
                    viewModel.onPageCommitVisible(url)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    applyHermesWebViewCompatibilityFixes(view, url)
                    detectLoadedServerType(view)
                    viewModel.onPageFinished(
                        url = url,
                        // Persist only trusted Hermes WebUI routes as the cold-start URL.
                        // OAuth provider pages (non-allowlisted in-app IdP hosts) must not be
                        // restored on launch: the flow is gone and the page needs the OAuth
                        // context to render.
                        rememberLastUrl = matchesConfiguredWebUiRoute(url) &&
                            !proxyAuthNavigation.isPending(viewModel.uiState.value.settings.serverUrl) &&
                            oauthReturnNavigation.shouldRememberUrl(url)
                    )
                    completeOAuthReturnIfHermesPage(view, url)
                    CookieManager.getInstance().flush()
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    val visitedUrl = url?.takeIf { it.isNotBlank() } ?: return
                    // Ignore internal/non-web URLs; persist only trusted Hermes WebUI routes.
                    if (!urlPolicy.isAllowed(visitedUrl)) return
                    if (!matchesConfiguredWebUiRoute(visitedUrl)) return
                    if (proxyAuthNavigation.isPending(viewModel.uiState.value.settings.serverUrl)) return
                    if (!oauthReturnNavigation.shouldRememberUrl(visitedUrl)) return

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
                    val failedUrl = request.url?.toString().orEmpty()
                    if (
                        isLocalNetworkPermissionError(error) &&
                            failedUrl.isNotBlank() &&
                            !hasLocalNetworkPermission()
                    ) {
                        requestLocalNetworkPermissionIfNeeded(
                            url = failedUrl,
                            onGranted = {
                                loadServerUrlWithVpnGuard(failedUrl)
                            }
                        )
                        return
                    }
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

                override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) {
                    if (view == null || request == null) return
                    val allowedHosts = viewModel.uiState.value.settings.allowedHosts
                    if (!ClientCertificateRequestSupport.isAllowedHost(request.host, allowedHosts)) {
                        DiagnosticsLogger.record(
                            this@MainActivity,
                            "client_cert_request_blocked",
                            mapOf(
                                "host" to request.host.orEmpty(),
                                "allowlist" to allowedHosts.joinToString(",")
                            )
                        )
                        request.cancel()
                        return
                    }
                    val cert = ClientCertificateRequestSupport.resolveRequest(
                        contentResolver = contentResolver,
                        config = settingsRepository.getClientCertificateConfig(),
                        request = request,
                        allowedHosts = allowedHosts
                    )
                    if (cert == null) {
                        DiagnosticsLogger.record(
                            this@MainActivity,
                            "client_cert_request_unavailable",
                            mapOf(
                                "host" to request.host.orEmpty(),
                                "configured" to settingsRepository.getClientCertificateConfig().uri.isNullOrBlank().not().toString()
                            )
                        )
                        request.cancel()
                        return
                    }
                    request.proceed(cert.privateKey, cert.certChain)
                }

                override fun onReceivedHttpAuthRequest(
                    view: WebView?,
                    handler: HttpAuthHandler?,
                    host: String?,
                    realm: String?
                ) {
                    if (handler == null) return
                    showHttpAuthPrompt(handler, host, realm)
                }
            }

            setDownloadListener(
                HermesWebViewDownloadListener(this@MainActivity) { url -> urlPolicy.isAllowed(url) }
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPopupWindow(resultMsg: Message?): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val popup = WebView(this).apply {
            HermesWebViewConfigurator.configurePopupWebView(
                webView = this,
                configureStorageAndCache = ::configureWebViewStorageAndCache,
                disableWebViewDarkening = ::disableWebViewDarkening
            )
            webViewClient = buildPopupWebViewClient(this)
        }

        trackPopupWindow(popup)
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    private fun trackPopupWindow(popup: WebView) {
        trackedPopups.add(popup)
        popup.postDelayed({
            // Never-navigated window.open('') orphan: destroy it unless it became the
            // active OAuth popup (that path is cleaned up by the OAuth timeout instead).
            if (activeOAuthPopup !== popup) destroyPopup(popup)
        }, ORPHAN_POPUP_SWEEP_MS)
    }

    private fun buildPopupWebViewClient(popup: WebView): WebViewClient {
        return object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val target = request?.url?.toString() ?: return true
                return handlePopupNavigation(
                    popup = popup,
                    target = target
                )
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrBlank()) {
                    handlePopupPageStarted(popup, url)
                }
            }

            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?
            ) {
                if (handler == null) return
                showHttpAuthPrompt(handler, host, realm)
            }
        }
    }

    private fun handlePopupNavigation(
        popup: WebView,
        target: String
    ): Boolean {
        if (handleAppSettingsNavigation(target)) {
            destroyPopup(popup)
            return true
        }

        val callbackFlow = activeOAuthFlow.takeIf { activeOAuthPopup === popup }
        if (callbackFlow?.isVerifiedCallbackUrl(target) == true) {
            clearActiveOAuthPopup()
            loadOAuthCallbackInMainWebView(callbackFlow, target)
            destroyPopup(popup)
            return true
        }

        val flow = parseTrustedOAuthStart(target)
        if (flow != null) {
            if (launchBrowserSignIn(flow, target)) {
                destroyPopup(popup)
                return true
            }
            // Move popup OAuth flows to the main frame so they are visible and interactive.
            rememberActiveMainFrameOAuth(flow)
            restrictMediaPlaybackBeforeNavigation(target)
            loadInMainWebView(target)
            destroyPopup(popup)
            return true
        }

        if (activeOAuthPopup === popup && activeOAuthFlow != null && isHttpOrHttpsUrl(target)) {
            refreshActiveOAuthTimeout()
            return false
        }

        clearActiveOAuthPopup()
        handleNewWindowUrl(target)
        destroyPopup(popup)
        return true
    }

    private fun handlePopupPageStarted(popup: WebView, url: String) {
        val callbackFlow = activeOAuthFlow.takeIf { activeOAuthPopup === popup }
        if (callbackFlow?.isVerifiedCallbackUrl(url) == true) {
            clearActiveOAuthPopup()
            loadOAuthCallbackInMainWebView(callbackFlow, url)
            destroyPopup(popup)
            return
        }

        val startedFlow = parseTrustedOAuthStart(url)
        if (startedFlow != null) {
            if (launchBrowserSignIn(startedFlow, url)) {
                destroyPopup(popup)
                return
            }
            // Move popup OAuth flows to the main frame so they are visible and interactive.
            rememberActiveMainFrameOAuth(startedFlow)
            restrictMediaPlaybackBeforeNavigation(url)
            loadInMainWebView(url)
            destroyPopup(popup)
            return
        }

        if (activeOAuthPopup === popup && activeOAuthFlow != null && isHttpOrHttpsUrl(url)) {
            refreshActiveOAuthTimeout()
            return
        }

        clearActiveOAuthPopup()
        handleNewWindowUrl(url)
        destroyPopup(popup)
    }

    private fun handleMainWebViewNavigation(target: String): Boolean {
        if (handleAppSettingsNavigation(target)) return true

        if (completeMainFrameOAuthIfCallback(target)) return false
        val activeTopLevelFlow = activeMainFrameOAuthFlow

        val startedTopLevelFlow = parseTrustedOAuthStart(target)
        if (startedTopLevelFlow != null) {
            if (launchBrowserSignIn(startedTopLevelFlow, target)) return true
            rememberActiveMainFrameOAuth(startedTopLevelFlow)
            return false
        }

        if (activeTopLevelFlow != null && isHttpOrHttpsUrl(target)) {
            // Keep loading provider/redirect hosts in-app only while the flow window
            // (set once at flow start) is still open. Enforce the timeout INLINE and do
            // NOT refresh it per navigation: refreshing on every http(s) load let a
            // stale/hijacked flow hold the host allowlist open indefinitely. Once the
            // window closes, clear the flow and fall through so non-allowlisted hosts are
            // externalized again.
            // Residual (P2): the flow is still gated only by the redirect_uri->server-origin
            // check because a non-allowlisted authorize host is intentionally allowed in-app
            // for external/self-hosted OIDC providers (see OAuthPopupFlowTest). Requiring the
            // authorize host to be allowlisted would break legitimate provider logins; fully
            // closing the in-app phishing surface needs a product decision (configurable
            // trusted-IdP allowlist or an in-flow URL indicator).
            if (System.currentTimeMillis() <= oauthFlowTimeoutMs) {
                return false
            }
            clearActiveMainFrameOAuth()
        }

        // Keep the verified callback and every same-origin return redirect in the main
        // WebView until the final Hermes page finishes. A single-navigation flag is not
        // sufficient because WebView callback ordering and IdP redirect chains vary.
        if (oauthReturnNavigation.shouldStayInMainWebView(target)) {
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

    private fun completeMainFrameOAuthIfCallback(url: String): Boolean {
        val flow = activeMainFrameOAuthFlow ?: return false
        if (!flow.isVerifiedCallbackUrl(url)) return false
        oauthReturnNavigation.begin(flow)
        clearActiveMainFrameOAuth()
        return true
    }

    private fun handleMainWebViewPageStarted(url: String?) {
        recordMainFrameHop(url?.takeIf { it.isNotBlank() })
        if (!url.isNullOrBlank() && !completeMainFrameOAuthIfCallback(url)) {
            parseTrustedOAuthStart(url)?.let { rememberActiveMainFrameOAuth(it) }
        }
        updateOAuthInFlowHost(url)
        webView.settings.mediaPlaybackRequiresUserGesture =
            !currentWebTrustPolicy().allowsGestureFreeMediaPlayback(url)
        viewModel.onPageStarted(url)
    }

    private fun restrictMediaPlaybackBeforeNavigation(target: String) {
        HermesMediaPlaybackPolicy.beforeAllowedMainFrameNavigation(webView, target, currentWebTrustPolicy())
    }

    private fun showHttpAuthPrompt(handler: HttpAuthHandler, host: String?, realm: String?) {
        if (!currentWebTrustPolicy().isTrustedHttpAuthHost(host)) {
            handler.cancel()
            return
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                handler.cancel()
                return@runOnUiThread
            }
            if (pendingHttpAuthPrompt != null) {
                handler.cancel()
                return@runOnUiThread
            }

            // A saved sign-in is offered once per request; WebView reports a rejected attempt
            // through useHttpAuthUsernamePassword(), which then falls back to the prompt.
            val saved = settingsRepository.getHttpAuthCredential(host, realm)
            val savedUsable = saved != null && handler.useHttpAuthUsernamePassword()
            if (savedUsable) {
                DiagnosticsLogger.record(this, "http_auth_saved_sign_in", mapOf("used" to "true"))
                handler.proceed(saved!!.username, saved.password)
                return@runOnUiThread
            }

            val prompt = PendingHttpAuthPrompt(handler, host, realm)
            pendingHttpAuthPrompt = prompt
            val username = EditText(this).apply {
                hint = "Username"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setSingleLine()
                saved?.let { setText(it.username) }
            }
            val password = EditText(this).apply {
                hint = "Password"
                // setSingleLine replaces the password transformation.
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val remember = if (settingsRepository.isRememberHttpAuthEnabled()) {
                CheckBox(this).apply {
                    text = "Remember on this device"
                    isChecked = true
                }
            } else {
                null
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
                val verticalPadding = (8 * resources.displayMetrics.density).toInt()
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, 0)
                addView(username, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(password, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                remember?.let {
                    addView(it, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }

            val challenge = realm?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            val message = if (saved != null) {
                "The saved sign-in was not accepted. Enter the username and password$challenge."
            } else {
                "This server requires a username and password$challenge."
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("Sign in to $host")
                .setMessage(message)
                .setView(content)
                .setPositiveButton("Sign in", null)
                .setNegativeButton("Cancel") { _, _ -> cancelHttpAuthPrompt(prompt) }
                .setOnCancelListener { cancelHttpAuthPrompt(prompt) }
                .create()
            prompt.dialog = dialog
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val enteredUsername = username.text.toString()
                    val enteredPassword = password.text.toString()
                    if (remember?.isChecked == true && !prompt.resolved) {
                        settingsRepository.saveHttpAuthCredential(
                            prompt.host,
                            prompt.realm,
                            HttpAuthCredential(enteredUsername, enteredPassword)
                        )
                        viewModel.refreshSavedHttpAuthState()
                    }
                    proceedHttpAuthPrompt(prompt, enteredUsername, enteredPassword)
                    dialog.dismiss()
                }
                if (saved != null) password.requestFocus() else username.requestFocus()
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            }
            dialog.show()
        }
    }

    private fun cancelHttpAuthPrompt(prompt: PendingHttpAuthPrompt) {
        if (prompt.resolved) return
        prompt.resolved = true
        if (pendingHttpAuthPrompt === prompt) pendingHttpAuthPrompt = null
        prompt.handler.cancel()
    }

    private fun proceedHttpAuthPrompt(prompt: PendingHttpAuthPrompt, username: String, password: String) {
        if (prompt.resolved) return
        prompt.resolved = true
        if (pendingHttpAuthPrompt === prompt) pendingHttpAuthPrompt = null
        prompt.handler.proceed(username, password)
    }

    private fun confirmProxySignInRedirect(serverUrl: String, origin: String, target: String) {
        if (proxySignInDialog?.isShowing == true) return
        val navigationGeneration = mainFrameNavigationGeneration
        DiagnosticsLogger.record(this, "proxy_sign_in_redirect", mapOf(
            "origin" to DiagnosticsLogger.originOnly(serverUrl),
            "login_origin" to origin
        ))
        proxySignInDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Continue to server sign-in?")
            .setMessage(
                "${UrlOrigins.pageOrigin(serverUrl)} redirected to $origin. " +
                    "Continue only if this is your server's sign-in service. " +
                    "Access lasts five minutes and is limited to these two origins."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                // An intercepted initial redirect can leave WebView.url null even without a new navigation.
                val stillConfigured = viewModel.uiState.value.settings.serverUrl == serverUrl &&
                    mainFrameNavigationGeneration == navigationGeneration &&
                    matchesConfiguredWebUiRoute(lastMainFrameHopUrl) &&
                    ProxyAuthNavigation.mainFrameRedirectConsentOrigin(serverUrl, lastMainFrameHopUrl, target) == origin
                DiagnosticsLogger.record(this, "proxy_sign_in_approval", mapOf(
                    "origin" to DiagnosticsLogger.originOnly(serverUrl),
                    "current_origin" to DiagnosticsLogger.originOnly(webView.url),
                    "approved" to stillConfigured.toString()
                ))
                if (stillConfigured) {
                    proxyAuthNavigation.begin(serverUrl, origin)
                    restrictMediaPlaybackBeforeNavigation(target)
                    loadInMainWebView(target)
                } else {
                    Toast.makeText(this, "Server changed. Please retry sign-in.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun completeOAuthReturnIfHermesPage(view: WebView?, url: String?) {
        if (view == null || url.isNullOrBlank()) return
        val serverUrl = viewModel.uiState.value.settings.serverUrl
        if (!oauthReturnNavigation.shouldStayInMainWebView(url) &&
            !(proxyAuthNavigation.isPending(serverUrl) && matchesConfiguredWebUiRoute(url))
        ) return

        view.evaluateJavascript(HermesWebUiScripts.hermesPageReadyScript) { result ->
            // Ignore a delayed result from a page that navigated again while JavaScript ran.
            if (view.url == url) {
                val proxyWasActive = proxyAuthNavigation.isPending(serverUrl)
                proxyAuthNavigation.completeIfHermesPage(serverUrl, url, result == "true")
                val completed = oauthReturnNavigation.completeIfHermesPage(
                    url = url,
                    isHermesPage = result == "true"
                )
                if ((completed || (proxyWasActive && !proxyAuthNavigation.isPending(serverUrl))) &&
                    matchesConfiguredWebUiRoute(url)
                ) {
                    viewModel.onUrlVisited(url = url, rememberLastUrl = true)
                }
            }
        }
    }

    private fun handleWebViewPermissionRequest(request: PermissionRequest) {
        val requestedResources = request.resources?.toSet().orEmpty()
        val requestsAudio = requestedResources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        voiceCaptureGate.onCaptureRequested(
            request = request,
            trustedAudioRequest = requestsAudio && isTrustedPermissionOrigin(request.origin),
            hasRecordAudioPermission = hasRecordAudioPermission()
        )
    }

    private fun requestLocalNetworkPermissionIfNeeded(
        url: String,
        onGranted: () -> Unit,
        onDenied: (() -> Unit)? = null
    ) {
        val decision = LocalNetworkPolicy.preflightDecision(
            isLocalHost = shouldRequestLocalNetworkPermission(url),
            hasPermission = hasLocalNetworkPermission()
        )
        if (decision == LocalNetworkPolicy.PreflightDecision.PROCEED) {
            onGranted()
            return
        }

        pendingLocalNetworkPermissionAction = onGranted
        pendingLocalNetworkPermissionDeniedAction = onDenied
        localNetworkPermissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
    }

    private fun hasLocalNetworkPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            "android.permission.ACCESS_LOCAL_NETWORK"
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun shouldRequestLocalNetworkPermission(url: String): Boolean {
        val host = runCatching { url.toUri().host?.trim().orEmpty() }.getOrDefault("")
        if (host.isBlank()) return false
        return LocalNetworkPolicy.isLikelyLocalNetworkHost(host)
    }

    private fun isLocalNetworkPermissionError(error: WebResourceError?): Boolean {
        return error?.description?.contains(
            "ERR_LOCAL_NETWORK_PERMISSION_MISSING",
            ignoreCase = true
        ) == true
    }

    private fun installHermesNotificationWebMessageBridge(view: WebView) {
        notificationBridgeCoordinator.installBridge(view)
    }

    private fun isTrustedNotificationBridgeSource(sourceOrigin: Uri, isMainFrame: Boolean): Boolean {
        return currentWebTrustPolicy().isTrustedNotificationBridgeSource(
            sourceOrigin = sourceOrigin.toString(),
            isMainFrame = isMainFrame,
            currentMainFrameUrl = webView.url
        )
    }

    /**
     * Gboard (and other IMEs) commit images into the focused editor. Re-check trust at delivery time,
     * read at most [ClipboardImagePolicy.MAX_IMAGE_BYTES] off the main thread, then hand the image to
     * the page as a paste event (Issue #180). Always releases the IME's URI permission.
     */
    private fun deliverCommittedImage(uri: Uri, mimeType: String, release: () -> Unit): Boolean {
        if (!matchesConfiguredWebUiRoute(webView.url)) {
            release()
            return false
        }
        val expectedUrl = webView.url
        Thread {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { ClipboardImagePolicy.readBounded(it) }
            }.getOrNull()
            release()
            runOnUiThread {
                val trustedOrigin = UrlOrigins.pageOrigin(viewModel.uiState.value.settings.serverUrl)
                if (bytes == null) {
                    Toast.makeText(this, "That image is too large or could not be read", Toast.LENGTH_SHORT).show()
                } else if (trustedOrigin != null && webView.url == expectedUrl) {
                    webView.evaluateJavascript(
                        HermesWebUiScripts.buildOriginGuardedRuntimeScript(
                            trustedOrigin,
                            HermesWebUiScripts.buildDeliverImageScript(
                                mimeType,
                                Base64.encodeToString(bytes, Base64.NO_WRAP)
                            )
                        ),
                        null
                    )
                }
            }
        }.start()
        return true
    }

    private fun requestNotificationPermissionIfNeeded() {
        notificationBridgeCoordinator.requestNotificationPermissionIfNeeded()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun launchPostNotificationsPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            // Pass the WebView session cookie so probes authenticate on password/OIDC servers
            // the same way the real /api/session/stream connection does (issue #75).
            val sessionCookie = CookieManager.getInstance().getCookie(serverUrl)
            when (HermesApiClient.detectSseCapability(serverUrl, sessionCookie = sessionCookie)) {
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
                    if (enableIfAvailable) {
                        viewModel.setSseTransportEnabled(true)
                    }
                    viewModel.setSseSupportStatus(
                        "Info: Gateway SSE extras are unavailable. Android will still try the authenticated " +
                            "/api/session/stream for the active session; the lightweight reconnect probe was not detected."
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "Gateway SSE extras unavailable; session streaming remains enabled.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                HermesApiClient.SseCapability.AUTH_REQUIRED -> {
                    // Sign-in wall, not a missing feature: never disable the transport here.
                    if (enableIfAvailable) {
                        viewModel.setSseTransportEnabled(true)
                    }
                    viewModel.setSseSupportStatus(
                        "🔒  Sign-in required: SSE capability could not be verified on this server. " +
                            "SSE transport stays enabled; Android will try the authenticated /api/session/stream for the active session. " +
                            "Sign in inside the app, then re-run the check."
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "Sign-in required to verify SSE — transport left enabled.",
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

    private fun webNotificationPermissionState(): String {
        return notificationBridgeCoordinator.permissionState()
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

    private fun isTrustedNotificationTarget(url: String?): Boolean {
        return currentWebTrustPolicy().isTrustedNotificationTarget(url)
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
        return currentWebTrustPolicy().isTrustedPermissionOrigin(
            origin = origin?.toString(),
            currentMainFrameUrl = webView.url
        )
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
        HermesMediaPlaybackPolicy.onDocumentReady(view, currentWebTrustPolicy())
        val effectiveUrl = url ?: viewModel.uiState.value.currentUrl
        if (matchesConfiguredWebUiRoute(effectiveUrl)) {
            // Android WebView can report supported dynamic viewport units while computing them as 0px.
            // Hermes WebUI uses 100dvh for the root flex shell (and 100vh max-height for floating
            // menus), so force the measured viewport height for both.
            applyHermesWebUiRuntimeScripts(view)
            return
        }

        // In-app OAuth provider pages render in this same WebView, so they hit the same
        // viewport-unit (vh/dvh = 0px) collapse, but the WebUI-origin document-start shims do
        // not cover provider origins. Apply only the generic viewport polyfill while a verified
        // OAuth flow is active (e.g. the Nous portal login root uses h-screen and would
        // otherwise collapse to a blank page).
        if ((activeMainFrameOAuthFlow != null ||
                proxyAuthNavigation.allows(viewModel.uiState.value.settings.serverUrl, effectiveUrl)) &&
            isHttpOrHttpsUrl(effectiveUrl)
        ) {
            view.evaluateJavascript(HermesWebUiScripts.viewportFixScript, null)
        }
    }

    private fun applyHermesWebUiRuntimeScripts(view: WebView) {
        val settings = viewModel.uiState.value.settings
        val trustedOrigin = UrlOrigins.pageOrigin(settings.serverUrl) ?: return
        val scripts = buildList {
            add(HermesWebUiScripts.viewportFixScript)
            add(HermesWebUiScripts.pinchZoomScript)
            add(HermesWebUiScripts.microphoneFallbackScript)
            add(HermesWebUiScripts.suppressClarifyAutofocusScript)
            add(buildHermesWebUiNotificationBridgeScript())
            add(HermesWebUiScripts.buildClipboardImageScript(HermesClipboardBridgeName))
            add(buildHermesWebUiRouteRecoveryScript())
            if (EnableAppSettingsSidebarShim) add(HermesWebUiScripts.appSettingsEntryScript)
            add("window.__hermesAndroidHardwareKeyboard = ${isHardwareKeyboardAttached()};")
        }
        scripts.forEach { script ->
            view.evaluateJavascript(
                HermesWebUiScripts.buildOriginGuardedRuntimeScript(trustedOrigin, script),
                null
            )
        }
        voiceCaptureGate.onPageReady()
    }

    private fun isHardwareKeyboardAttached(): Boolean {
        val config = resources.configuration
        return HardwareKeyboardState.isAttached(config.keyboard, config.hardKeyboardHidden)
    }

    /**
     * Pushes the current hardware-keyboard state into the WebUI page so the document-start
     * Enter-key shim can defer to WebUI's native Enter-to-submit handling (desktop
     * convention) while a physical keyboard is attached, and keep forcing a newline on a
     * soft keyboard. The flag is read at keydown time, so updates apply without a reload.
     */
    private fun syncHardwareKeyboardState(view: WebView? = null) {
        val target = view ?: if (::webView.isInitialized) webView else null
        if (target == null) return
        target.evaluateJavascript(
            "window.__hermesAndroidHardwareKeyboard = ${isHardwareKeyboardAttached()};",
            null
        )
    }

    /** Dispatches the proposed host event; it does not itself stop existing capture or playback. */
    private fun signalAppForegroundToWebUi(active: Boolean) {
        if (!::webView.isInitialized || !matchesConfiguredWebUiRoute(webView.url)) return
        val trustedOrigin = UrlOrigins.pageOrigin(viewModel.uiState.value.settings.serverUrl) ?: return
        webView.evaluateJavascript(
            HermesWebUiScripts.buildOriginGuardedRuntimeScript(
                trustedOrigin,
                HermesWebUiScripts.buildAppForegroundScript(active)
            ),
            null
        )
    }

    private fun installHermesWebUiDocumentStartFixes(view: WebView, serverUrl: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val originRule = UrlOrigins.documentStartOriginRule(serverUrl) ?: return

        removeHermesWebUiDocumentStartFixes()
        viewportFixScriptHandler = addDocumentStartScript(
            view,
            originRule,
            HermesWebUiScripts.viewportFixScript
        )
        pinchZoomScriptHandler = addDocumentStartScript(
            view,
            originRule,
            HermesWebUiScripts.pinchZoomScript
        )
        microphoneFallbackScriptHandler = addDocumentStartScript(
            view,
            originRule,
            HermesWebUiScripts.microphoneFallbackScript
        )
        notificationBridgeScriptHandler = addDocumentStartScript(
            view,
            originRule,
            buildHermesWebUiNotificationBridgeScript()
        )
        clipboardBridgeScriptHandler = addDocumentStartScript(
            view,
            originRule,
            HermesWebUiScripts.buildClipboardImageScript(HermesClipboardBridgeName)
        )
        routeRecoveryScriptHandler = addDocumentStartScript(
            view,
            originRule,
            buildHermesWebUiRouteRecoveryScript()
        )
        if (EnableAppSettingsSidebarShim) {
            appSettingsEntryScriptHandler = addDocumentStartScript(
                view,
                originRule,
                HermesWebUiScripts.appSettingsEntryScript
            )
        }
        enterKeyNewlineScriptHandler = addDocumentStartScript(
            view,
            originRule,
            HermesWebUiScripts.enterKeyNewlineScript
        )
        suppressClarifyAutofocusScriptHandler = addDocumentStartScript(
            view,
            originRule,
            HermesWebUiScripts.suppressClarifyAutofocusScript
        )
    }

    private fun removeHermesWebUiDocumentStartFixes() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        viewportFixScriptHandler?.remove()
        pinchZoomScriptHandler?.remove()
        microphoneFallbackScriptHandler?.remove()
        notificationBridgeScriptHandler?.remove()
        clipboardBridgeScriptHandler?.remove()
        routeRecoveryScriptHandler?.remove()
        appSettingsEntryScriptHandler?.remove()
        enterKeyNewlineScriptHandler?.remove()
        suppressClarifyAutofocusScriptHandler?.remove()
        viewportFixScriptHandler = null
        pinchZoomScriptHandler = null
        microphoneFallbackScriptHandler = null
        notificationBridgeScriptHandler = null
        clipboardBridgeScriptHandler = null
        routeRecoveryScriptHandler = null
        appSettingsEntryScriptHandler = null
        enterKeyNewlineScriptHandler = null
        suppressClarifyAutofocusScriptHandler = null
    }

    private fun addDocumentStartScript(
        view: WebView,
        originRule: String,
        script: String
    ): ScriptHandler? {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return null
        return runCatching {
            WebViewCompat.addDocumentStartJavaScript(view, script, setOf(originRule))
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
        return HermesWebUiScripts.buildRouteRecoveryScript(currentRouteRecoveryUrl())
    }

    private fun currentRouteRecoveryUrl(): String {
        val lastUrl = settingsRepository.getLastLoadedUrl().orEmpty()
        val settings = viewModel.uiState.value.settings
        if (lastUrl.isBlank() || !UrlOrigins.hasSameOrigin(lastUrl, settings.serverUrl)) return ""

        val normalizedLastPath = UrlOrigins.normalizedPath(lastUrl)
        return if (normalizedLastPath.isBlank()) "" else lastUrl
    }

    private fun buildHermesWebUiNotificationBridgeScript(): String {
        return HermesWebUiScripts.buildNotificationBridgeScript(
            bridgeName = HermesNotificationBridgeName,
            initialPermission = webNotificationPermissionState()
        )
    }

    private fun matchesConfiguredWebUiRoute(url: String?): Boolean {
        return currentWebTrustPolicy().isConfiguredWebUiRoute(url)
    }

    private fun detectLoadedServerType(view: WebView?) {
        if (view == null || view !== webView || !matchesConfiguredWebUiRoute(view.url)) return
        val serverUrl = viewModel.uiState.value.settings.serverUrl
        val origin = UrlOrigins.pageOrigin(serverUrl) ?: return
        val generation = serverTypeDocumentGeneration
        fun isCurrentDocument() = !isDestroyed &&
            generation == serverTypeDocumentGeneration &&
            viewModel.uiState.value.settings.serverUrl == serverUrl &&
            matchesConfiguredWebUiRoute(view.url)

        fun probe(attempt: Int) {
            if (!isCurrentDocument()) return
            view.evaluateJavascript(HermesWebUiScripts.buildServerTypeProbeScript(origin)) { result ->
                if (!isCurrentDocument()) return@evaluateJavascript
                val type = when (result) {
                    "\"agent\"" -> ServerType.AGENT
                    "\"webui\"" -> ServerType.WEBUI
                    else -> ServerType.UNSPECIFIED
                }
                if (type != ServerType.UNSPECIFIED) {
                    viewModel.recordDetectedServerType(serverUrl, type)
                } else if (attempt < 3) {
                    // React/bundle markers may appear after onPageFinished; never guess from login HTML.
                    view.postDelayed({ probe(attempt + 1) }, 1_000L)
                }
            }
        }
        probe(0)
    }

    private fun matchesConfiguredDashboardRoute(url: String?): Boolean {
        return currentWebTrustPolicy().isConfiguredDashboardRoute(url)
    }

    private fun currentWebTrustPolicy(): WebTrustPolicy {
        val settings = viewModel.uiState.value.settings
        return WebTrustPolicy(
            urlPolicy = urlPolicy,
            configuredWebUiUrl = settings.serverUrl,
            configuredDashboardUrl = settings.dashboardUrl
        )
    }

    private fun recordMainFrameHop(url: String?) {
        lastMainFrameHopUrl = url
        mainFrameNavigationGeneration++
    }

    private fun loadInMainWebView(url: String) {
        recordMainFrameHop(url)
        webView.loadUrl(url)
    }

    private fun handleWebBackNavigation(onRequestExit: () -> Unit) {
        val url = webView.url
        val serverUrl = viewModel.uiState.value.settings.serverUrl
        val origin = UrlOrigins.pageOrigin(serverUrl)
        if (!matchesConfiguredWebUiRoute(url) || origin == null) {
            handleWebUiBackNavigation(onRequestExit)
            return
        }
        webView.evaluateJavascript(HermesWebUiScripts.buildOfficialWebappProbeScript(origin)) { result ->
            if (webView.url != url || viewModel.uiState.value.settings.serverUrl != serverUrl ||
                viewModel.uiState.value.isSettingsVisible
            ) return@evaluateJavascript
            if (result == "true") {
                // The official workspace owns its tabs/hash routes. Never back into sign-in.
                backPressExitStage = 0
                viewModel.openSettings()
            } else {
                handleWebUiBackNavigation(onRequestExit)
            }
        }
    }

    private fun handleWebUiBackNavigation(onRequestExit: () -> Unit) {
        if (webView.canGoBack()) {
            backPressExitStage = 0
            goBackInMainWebView()
            return
        }
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime >= BACK_PRESS_TIMEOUT_MS) backPressExitStage = 0
        when (backPressExitStage) {
            0 -> {
                backPressExitStage = 1
                lastBackPressTime = currentTime
                Toast.makeText(this, "Press back again for Application Settings", Toast.LENGTH_SHORT).show()
            }
            1 -> {
                backPressExitStage = 2
                lastBackPressTime = currentTime
                viewModel.openSettings()
                Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
            else -> onRequestExit()
        }
    }

    private fun goBackInMainWebView() {
        // History navigation skips shouldOverrideUrlLoading, so record the entry being restored.
        val history = webView.copyBackForwardList()
        recordMainFrameHop(history.getItemAtIndex(history.currentIndex - 1)?.url)
        webView.goBack()
    }

    private fun handleNewWindowUrl(url: String) {
        if (matchesConfiguredDashboardRoute(url)) {
            openDashboardInCustomTab(url)
            return
        }
        when (urlPolicy.navigationDecision(url)) {
            NavigationDecision.ALLOW_IN_WEBVIEW -> {
                restrictMediaPlaybackBeforeNavigation(url)
                loadInMainWebView(url)
            }
            NavigationDecision.OPEN_IN_EXTERNAL_BROWSER -> openInExternalBrowser(url)
            NavigationDecision.BLOCK -> Unit
        }
    }

    private fun loadOAuthCallbackInMainWebView(flow: OAuthPopupFlow, url: String) {
        oauthReturnNavigation.begin(flow)
        restrictMediaPlaybackBeforeNavigation(url)
        loadInMainWebView(url)
    }

    private fun parseTrustedOAuthStart(url: String): OAuthPopupFlow? {
        val serverUrl = viewModel.uiState.value.settings.serverUrl
        val flow = OAuthPopupFlow.parseAuthorizationStart(url) ?: return null
        if (flow.redirectsToOrigin(serverUrl)) return flow
        return flow.takeIf {
            proxyAuthNavigation.allowsGatewayOAuthCallback(serverUrl, lastMainFrameHopUrl, it.redirectUri)
        }
    }

    private fun isBrowserSignInStillTrusted(pending: PendingBrowserSignIn): Boolean {
        val serverUrl = viewModel.uiState.value.settings.serverUrl
        return serverUrl == pending.serverUrl &&
            (pending.flow.redirectsToOrigin(serverUrl) ||
                proxyAuthNavigation.isActiveLoginOrigin(serverUrl, pending.flow.redirectUri))
    }

    /** Opens a trusted OAuth start in a Chrome Auth Tab when the user opted in and Chrome supports it. */
    private fun launchBrowserSignIn(flow: OAuthPopupFlow, authorizeUrl: String): Boolean {
        if (!settingsRepository.isBrowserSignInEnabled()) return false
        // Nested authorizations inside an in-app flow, including after "Continue in app", stay in the app.
        if (activeMainFrameOAuthFlow != null) return false
        val callback = BrowserSignIn.authTabCallback(flow.redirectUri) ?: return false
        val browserPackage = CustomTabsClient.getPackageName(this, null) ?: return false
        // An unsupported browser would silently open a plain Custom Tab that can never return the callback.
        if (!CustomTabsClient.isAuthTabSupported(this, browserPackage)) return false
        val authTab = AuthTabIntent.Builder().build()
        authTab.intent.setPackage(browserPackage)
        pendingBrowserSignIn = PendingBrowserSignIn(flow, authorizeUrl, viewModel.uiState.value.settings.serverUrl)
        return try {
            authTab.launch(authTabLauncher, authorizeUrl.toUri(), callback.host, callback.path)
            DiagnosticsLogger.record(this, "browser_sign_in", mapOf(
                "event" to "launched",
                "callback_origin" to DiagnosticsLogger.originOnly(flow.redirectUri)
            ))
            true
        } catch (_: ActivityNotFoundException) {
            pendingBrowserSignIn = null
            false
        }
    }

    private fun onBrowserSignInResult(result: AuthTabIntent.AuthResult) {
        val pending = pendingBrowserSignIn
        if (pending == null) {
            // The activity was recreated while Chrome was open; the in-memory flow and consent are gone.
            if (result.resultCode == AuthTabIntent.RESULT_OK) {
                Toast.makeText(this, "Sign-in was interrupted. Please try again.", Toast.LENGTH_LONG).show()
            }
            return
        }
        pendingBrowserSignIn = null
        val trusted = isBrowserSignInStillTrusted(pending)
        DiagnosticsLogger.record(this, "browser_sign_in", mapOf(
            "event" to "result",
            "code" to result.resultCode.toString(),
            "trusted" to trusted.toString()
        ))
        when (result.resultCode) {
            AuthTabIntent.RESULT_OK -> {
                val callbackUrl = result.resultUri?.toString()
                if (trusted && callbackUrl != null && pending.flow.isVerifiedCallbackUrl(callbackUrl)) {
                    loadOAuthCallbackInMainWebView(pending.flow, callbackUrl)
                } else {
                    Toast.makeText(this, "Sign-in could not be completed. Please try again.", Toast.LENGTH_LONG).show()
                }
            }
            AuthTabIntent.RESULT_VERIFICATION_FAILED, AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT -> {
                if (trusted) {
                    showBrowserSignInVerificationDialog(pending)
                } else {
                    Toast.makeText(this, "Server changed. Please retry sign-in.", Toast.LENGTH_LONG).show()
                }
            }
            else -> Unit
        }
    }

    private fun showBrowserSignInVerificationDialog(pending: PendingBrowserSignIn) {
        val host = UrlOrigins.hostFrom(pending.flow.redirectUri) ?: "the sign-in site"
        AlertDialog.Builder(this)
            .setTitle("Chrome couldn't confirm this app")
            .setMessage(
                "Chrome returns a sign-in to Hermes only when $host publishes this app's " +
                    "/.well-known/assetlinks.json file. Copy it from Settings > Connection > " +
                    "Sign in with Chrome, or continue signing in inside the app."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue in app") { _, _ ->
                if (!isBrowserSignInStillTrusted(pending)) {
                    Toast.makeText(this, "Server changed. Please retry sign-in.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                rememberActiveMainFrameOAuth(pending.flow)
                restrictMediaPlaybackBeforeNavigation(pending.authorizeUrl)
                loadInMainWebView(pending.authorizeUrl)
            }
            .show()
    }

    private fun copyBrowserSignInAssetLinks() {
        val fingerprints = signingCertificateFingerprints()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (fingerprints.isEmpty() || clipboard == null) {
            Toast.makeText(this, "Couldn't prepare assetlinks.json on this device.", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(
            ClipData.newPlainText("Hermes assetlinks.json", BrowserSignIn.assetLinksJson(packageName, fingerprints))
        )
        Toast.makeText(this, "Copied assetlinks.json for $packageName.", Toast.LENGTH_SHORT).show()
    }

    /** SHA-256 fingerprints of the certificates this installed build is signed with. */
    @android.annotation.SuppressLint("PackageManagerGetSignatures")
    private fun signingCertificateFingerprints(): List<String> = runCatching {
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        certificates.orEmpty().map {
            BrowserSignIn.sha256Fingerprint(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }.distinct()
    }.getOrDefault(emptyList())

    private fun isHttpOrHttpsUrl(url: String): Boolean {
        val scheme = runCatching { url.toUri().scheme }.getOrNull()
        return scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
    }

    private fun saveSettings(serverUrl: String) {
        saveSettings(serverUrl, null, null)
    }

    private fun saveSettings(serverUrl: String, profileName: String?, serverType: ServerType?) {
        if (!serverUrlValidator.isValid(serverUrl)) {
            Toast.makeText(this, "Server URL must be a valid http:// or https:// URL", Toast.LENGTH_LONG).show()
            return
        }
        if (profileName != null && serverType != null &&
            settingsRepository.getProfiles().isEmpty() &&
            serverUrl.trim() == viewModel.uiState.value.settings.serverUrl.trim()
        ) {
            viewModel.addServerProfile(profileName.trim(), serverUrl.trim(), serverType)
            return
        }
        val dashboardUrl = viewModel.uiState.value.settings.dashboardUrl
        if (dashboardUrl.isNotBlank() && !serverUrlValidator.isValid(dashboardUrl)) {
            Toast.makeText(this, "Dashboard URL must be blank or a valid http:// or https:// URL", Toast.LENGTH_LONG).show()
            return
        }
        val persist = {
            viewModel.saveAppUrls(serverUrl, dashboardUrl)
            if (profileName != null && serverType != null) {
                viewModel.addServerProfile(profileName, serverUrl, serverType)
            }
            urlPolicy = UrlPolicy(viewModel.uiState.value.settings.allowedHosts)
            installHermesWebUiDocumentStartFixes(webView, serverUrl)
            requestLocalNetworkPermissionIfNeeded(
                url = serverUrl,
                onGranted = {
                    loadServerUrlWithVpnGuard(serverUrl)
                },
                onDenied = {
                    loadServerUrlWithVpnGuard(serverUrl)
                }
            )
        }
        serverProfileCoordinator.validateServerForPersistence(
            serverUrl = serverUrl,
            onFailure = { result ->
                showServerValidationRecoveryDialog(
                    serverUrl, result, "Save server",
                    onUseHttpsUrl = { saveSettings(it, profileName, serverType) }
                ) { persist() }
            }
        ) { persist() }
    }

    /**
     * When enabled, mark the window FLAG_SECURE so the OS blocks screenshots/screen recording and
     * shows a blank thumbnail in the app switcher — the last frame of an agent session (secrets,
     * approvals, tool output) is not exposed. Opt-in via native settings.
     */
    private fun applyScreenshotSecurity(enabled: Boolean) {
        if (enabled) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun preflightConfiguredStartupServer(serverUrl: String) {
        val lastLoadedUrl = settingsRepository.getLastLoadedUrl()
        // A cold-start session deep link (#170) shares the notification-tap override slot, so
        // startupUrl still requires a trusted configured WebUI route before honoring it.
        val notificationUrl = notificationPresenter.notificationTargetUrl(intent)
            ?: sessionIdFromDeepLink(intent.data)?.let { DeepLinkRoutes.sessionUrl(serverUrl, it) }
        // Restore only trusted Hermes WebUI routes; fall back to the server root for
        // dashboard-origin pages and for stale non-WebUI URLs (e.g. OAuth provider pages
        // persisted by older builds mid-flow).
        val startUrl = SelectedServerNavigation.startupUrl(
            selectedServerUrl = serverUrl,
            lastLoadedUrl = lastLoadedUrl,
            notificationUrl = notificationUrl,
            trustPolicy = currentWebTrustPolicy()
        )
        if (shouldRequireVpnForServerUrl(serverUrl) && !isVpnTransportActive()) {
            queueVpnGuardedLoad(
                serverUrl = serverUrl,
                details = "Trying to connect Tailscale automatically. Hermes retries every second while waiting for VPN."
            )
            return
        }
        serverProfileCoordinator.preflightConfiguredStartupServer(
            serverUrl = serverUrl,
            startUrl = startUrl,
            onContinueToWebView = { targetUrl ->
                requestLocalNetworkPermissionIfNeeded(
                    url = targetUrl,
                    onGranted = {
                        loadServerUrlWithVpnGuard(targetUrl)
                    },
                    onDenied = {
                        loadServerUrlWithVpnGuard(targetUrl)
                    }
                )
            }
        )
    }

    private fun showServerValidationRecoveryDialog(
        url: String,
        result: HermesApiClient.ServerReadinessResult,
        positiveLabel: String,
        onUseHttpsUrl: ((String) -> Unit)? = null,
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
        val httpsUrl = result.httpsUpgradeUrl?.takeIf { onUseHttpsUrl != null }
        val builder = AlertDialog.Builder(this)
            .setTitle("Server check failed")
            .setMessage(body)
        if (httpsUrl != null) {
            builder.setNeutralButton("Use HTTPS") { _, _ ->
                DiagnosticsLogger.record(
                    this,
                    "server_validation_use_https",
                    mapOf("origin" to DiagnosticsLogger.originOnly(httpsUrl))
                )
                onUseHttpsUrl?.invoke(httpsUrl)
            }
        } else {
            builder.setNeutralButton("Open in browser") { _, _ ->
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
        }
        builder
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
        if (!proxyAuthNavigation.isActive(profile.url)) proxyAuthNavigation.clear()
        urlPolicy = UrlPolicy(viewModel.uiState.value.settings.allowedHosts)
        val targetUrl = SelectedServerNavigation.switchTargetUrl(profile)
        installHermesWebUiDocumentStartFixes(webView, targetUrl)
        requestLocalNetworkPermissionIfNeeded(
            url = targetUrl,
            onGranted = {
                loadServerUrlWithVpnGuard(targetUrl)
            },
            onDenied = {
                loadServerUrlWithVpnGuard(targetUrl)
            }
        )
        viewModel.closeSettings()
        Toast.makeText(this, "Switched to ${profile.name}", Toast.LENGTH_SHORT).show()
    }

    private fun clearWebViewStateForServerSwitch() {
        proxySignInDialog?.dismiss()
        clearActiveMainFrameOAuth()
        activeOAuthPopup?.let { destroyPopup(it) }
        clearActiveOAuthPopup()
        oauthReturnNavigation.clear()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.stopLoading()
        loadInMainWebView("about:blank")
        webView.clearHistory()
        webView.clearCache(true)
        webView.clearFormData()
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

    private fun loadServerUrlWithVpnGuard(url: String) {
        if (!shouldRequireVpnForServerUrl(url) || isVpnTransportActive()) {
            pendingVpnGuardUrl = null
            stopVpnReconnectWaitLoop()
            restrictMediaPlaybackBeforeNavigation(url)
            loadInMainWebView(url)
            return
        }

        queueVpnGuardedLoad(
            serverUrl = url,
            details = "Trying to connect Tailscale automatically. Hermes retries every second while waiting for VPN."
        )
    }

    /** Ends the welcome guide (Get started, Skip or Back): remembered once, then on to server setup if needed. */
    private fun finishWelcomeGuide() {
        settingsRepository.markWelcomeGuideSeen()
        viewModel.dismissWelcome()
        if (!viewModel.uiState.value.settings.isConfigured) {
            viewModel.openSettings()
        }
    }

    private fun refreshConfiguredHermes(closeSettings: Boolean = false) {
        val state = viewModel.uiState.value
        val targetUrl = SelectedServerNavigation.reconnectUrl(
            currentUrl = state.currentUrl,
            selectedServerUrl = state.settings.serverUrl,
            trustPolicy = currentWebTrustPolicy()
        )
        if (targetUrl.isBlank()) {
            viewModel.openSettings()
            return
        }
        if (closeSettings) {
            viewModel.closeSettings()
        }
        loadServerUrlWithVpnGuard(targetUrl)
    }

    private fun shouldRequireVpnForServerUrl(url: String): Boolean {
        return viewModel.uiState.value.requireVpnForTailscaleEnabled &&
            TailscaleEndpointDetector.isTailscaleUrl(url)
    }

    @Suppress("DEPRECATION")
    private fun isVpnTransportActive(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        return manager.getAllNetworks().any { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@any false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun maybeLaunchVpnForServer(url: String, showPrompt: Boolean) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastVpnLaunchAttemptElapsedMs < 5_000L) return
        lastVpnLaunchAttemptElapsedMs = nowElapsed

        val preferredVpnPackage = viewModel.uiState.value.vpnLaunchPackageName
        if (TailscaleEndpointDetector.isTailscaleUrl(url) && requestTailscaleConnect()) {
            scheduleTailscaleLaunchFallback(url, preferredVpnPackage, showPrompt)
            return
        }

        val launchedVpnApp = if (TailscaleEndpointDetector.isTailscaleUrl(url)) {
            launchVpnApp(TailscaleAndroidPackage) || launchVpnApp(preferredVpnPackage)
        } else {
            launchVpnApp(preferredVpnPackage)
        }

        if (!launchedVpnApp) {
            val vpnIntent = Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(vpnIntent) }
        }

        if (showPrompt) {
            Toast.makeText(this, "Start your VPN, then reconnect Hermes.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestTailscaleConnect(): Boolean {
        val connectIntent = Intent(TailscaleConnectVpnAction).apply {
            `package` = TailscaleAndroidPackage
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        return runCatching {
            packageManager.getApplicationInfo(TailscaleAndroidPackage, 0)
            sendBroadcast(connectIntent)
            true
        }.getOrElse { false }
    }

    private fun scheduleTailscaleLaunchFallback(
        serverUrl: String,
        preferredVpnPackage: String,
        showPrompt: Boolean
    ) {
        tailscaleLaunchFallbackJob?.cancel()
        tailscaleLaunchFallbackJob = lifecycleScope.launch {
            delay(TailscaleAutoConnectFallbackMs)
            if (pendingVpnGuardUrl != serverUrl || isVpnTransportActive()) return@launch

            val launchedVpnApp = launchVpnApp(TailscaleAndroidPackage) ||
                launchVpnApp(preferredVpnPackage)
            if (!launchedVpnApp) {
                val vpnIntent = Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(vpnIntent) }
            }
            if (showPrompt) {
                Toast.makeText(
                    this@MainActivity,
                    "Tailscale is taking longer than expected. Complete its connection, and Hermes will continue automatically.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun launchVpnApp(packageName: String?): Boolean {
        val normalizedPackageName = packageName?.trim().orEmpty()
        if (normalizedPackageName.isBlank()) return false
        val launchIntent = packageManager.getLaunchIntentForPackage(normalizedPackageName) ?: return false
        return runCatching {
            startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun discoverLaunchableApps(): List<VpnLaunchAppOption> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchables = packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        val optionsByPackage = linkedMapOf<String, VpnLaunchAppOption>()
        launchables.forEach { resolveInfo ->
            val packageName = resolveInfo.activityInfo?.packageName?.trim().orEmpty()
            if (packageName.isBlank()) return@forEach
            val displayName = resolveInfo
                .loadLabel(packageManager)
                .toString()
                .trim()
                .ifBlank { packageName }
            val existing = optionsByPackage[packageName]
            if (existing == null || displayName.length < existing.displayName.length) {
                optionsByPackage[packageName] = VpnLaunchAppOption(
                    displayName = displayName,
                    packageName = packageName
                )
            }
        }
        return optionsByPackage.values.sortedWith(
            compareBy<VpnLaunchAppOption>(
                { it.displayName.lowercase() },
                { it.packageName.lowercase() }
            )
        )
    }

    private fun rememberActiveMainFrameOAuth(flow: OAuthPopupFlow) {
        activeMainFrameOAuthFlow = flow
        setOAuthThirdPartyCookiesEnabled(true)
        refreshActiveOAuthTimeout()
    }

    private fun clearActiveMainFrameOAuth() {
        activeMainFrameOAuthFlow = null
        viewModel.setOAuthInFlowHost(null)
        if (activeOAuthPopup == null) {
            oauthFlowTimeoutMs = 0L
            setOAuthThirdPartyCookiesEnabled(false)
        }
    }

    /**
     * Anti-phishing chip source: while an in-app OAuth flow is active and the current page is a
     * non-allowlisted origin (an external/self-hosted IdP rendered in the URL-bar-less WebView),
     * surface the host so the user can see they left the Hermes origin. The residual in-app OAuth
     * phishing surface (a non-allowlisted authorize host is intentionally allowed in-app for OIDC)
     * is otherwise invisible without a URL bar.
     */
    private fun updateOAuthInFlowHost(url: String?) {
        val proxyActive = proxyAuthNavigation.isPending(viewModel.uiState.value.settings.serverUrl)
        val host = if (!url.isNullOrBlank() &&
            ((activeMainFrameOAuthFlow != null && !urlPolicy.isAllowed(url)) ||
                (proxyActive && !matchesConfiguredWebUiRoute(url)))
        ) {
            UrlOrigins.hostFrom(url)
        } else {
            null
        }
        viewModel.setOAuthInFlowHost(host)
    }

    private fun refreshActiveOAuthTimeout() {
        oauthFlowTimeoutMs = System.currentTimeMillis() + OAUTH_FLOW_TIMEOUT_MS
    }

    private fun clearActiveOAuthPopup() {
        activeOAuthPopup = null
        activeOAuthFlow = null
        if (activeMainFrameOAuthFlow == null) {
            oauthFlowTimeoutMs = 0L
            setOAuthThirdPartyCookiesEnabled(false)
        }
    }

    private fun setOAuthThirdPartyCookiesEnabled(enabled: Boolean) {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptThirdPartyCookies(webView, enabled)
        activeOAuthPopup?.let { cookies.setAcceptThirdPartyCookies(it, enabled) }
    }

    private fun destroyPopup(popup: WebView) {
        // Idempotent: only destroy a popup still tracked as live, so the delayed orphan sweep and
        // the navigation/cleanup paths cannot double-destroy the same WebView.
        if (trackedPopups.remove(popup)) {
            runCatching { popup.destroy() }
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
            activeOAuthPopup?.let { destroyPopup(it) }
            clearActiveOAuthPopup()
            clearActiveMainFrameOAuth()
        }
    }

    private fun queueVpnGuardedLoad(serverUrl: String, details: String) {
        pendingVpnGuardUrl = serverUrl
        maybeLaunchVpnForServer(serverUrl, showPrompt = true)
        startVpnReconnectWaitLoopIfNeeded()
        viewModel.openSettingsWithServerValidation(
            message = "VPN is required before connecting to this Tailscale Hermes server.",
            isError = true,
            details = details
        )
    }

    private fun startVpnReconnectWaitLoopIfNeeded() {
        val targetUrl = pendingVpnGuardUrl ?: return
        if (!shouldRequireVpnForServerUrl(targetUrl)) {
            pendingVpnGuardUrl = null
            return
        }
        if (vpnReconnectWaitJob?.isActive == true) return
        vpnReconnectWaitJob = lifecycleScope.launch {
            while (isActive) {
                val probeStartedAt = SystemClock.elapsedRealtime()
                val pendingUrl = pendingVpnGuardUrl ?: return@launch
                if (!shouldRequireVpnForServerUrl(pendingUrl)) {
                    pendingVpnGuardUrl = null
                    return@launch
                }
                if (
                    isVpnTransportActive() &&
                    HermesApiClient.isServerReachable(
                        baseUrl = pendingUrl,
                        timeoutMs = VpnReconnectPollIntervalMs.toInt() - 100
                    )
                ) {
                    pendingVpnGuardUrl = null
                    viewModel.closeSettings()
                    loadServerUrlWithVpnGuard(pendingUrl)
                    notifyVpnRecoveryReadyIfBackgrounded()
                    return@launch
                }
                val elapsedMs = SystemClock.elapsedRealtime() - probeStartedAt
                delay((VpnReconnectPollIntervalMs - elapsedMs).coerceAtLeast(0L))
            }
        }
    }

    private fun stopVpnReconnectWaitLoop() {
        vpnReconnectWaitJob?.cancel()
        vpnReconnectWaitJob = null
        tailscaleLaunchFallbackJob?.cancel()
        tailscaleLaunchFallbackJob = null
    }

    private fun notifyVpnRecoveryReadyIfBackgrounded() {
        if (activityVisible) return
        val openHermesIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openHermesPendingIntent = PendingIntent.getActivity(
            this,
            HermesVpnReconnectNotificationId,
            openHermesIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, HermesNotificationChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hermes reconnected")
            .setContentText("Tailscale and your Hermes server are ready.")
            .setContentIntent(openHermesPendingIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            ?.notify(HermesVpnReconnectNotificationId, notification)
    }

}
