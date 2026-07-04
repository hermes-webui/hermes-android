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
import android.view.WindowManager
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
import android.webkit.WebViewDatabase
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
import com.hermeswebui.android.notification.HermesNotificationBridgeCoordinator
import com.hermeswebui.android.domain.ServerUrlValidator
import com.hermeswebui.android.server.HermesServerProfileCoordinator
import com.hermeswebui.android.domain.ShareIntentParser
import com.hermeswebui.android.ui.MainViewModel
import com.hermeswebui.android.ui.MainViewModelFactory
import com.hermeswebui.android.ui.DebugLogFloatingOverlay
import com.hermeswebui.android.ui.settings.SettingsScreen
import com.hermeswebui.android.ui.web.WebShell
import com.hermeswebui.android.webui.HermesWebUiScripts
import com.hermeswebui.android.webview.HermesWebViewConfigurator
import com.hermeswebui.android.update.AppUpdateCheckResult
import com.hermeswebui.android.update.AppUpdateDownloadPolicy
import com.hermeswebui.android.update.HermesAppUpdateCoordinator
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
private const val ExtraNotificationUrl = "com.hermeswebui.android.extra.NOTIFICATION_URL"
private const val HermesGithubIssuesListUrl = "https://github.com/hermes-webui/hermes-android/issues"
private const val HermesGithubNewIssueUrl = "https://github.com/hermes-webui/hermes-android/issues/new/choose"
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
    private lateinit var webView: WebView
    private lateinit var settingsRepository: SettingsRepository

    private var urlPolicy = UrlPolicy(emptySet())
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraCaptureUri: Uri? = null
    private var pendingAudioPermissionRequest: PermissionRequest? = null
    private var microphoneFallbackScriptHandler: ScriptHandler? = null
    private var notificationBridgeScriptHandler: ScriptHandler? = null
    private var routeRecoveryScriptHandler: ScriptHandler? = null
    private var appSettingsEntryScriptHandler: ScriptHandler? = null
    private var enterKeyNewlineScriptHandler: ScriptHandler? = null
    private var activityVisible = false
    private var reconnectServiceRunning = false
    private var debugLoggingServiceRunning = false
    private lateinit var appUpdateManager: AppUpdateManager
    private lateinit var appUpdateCoordinator: HermesAppUpdateCoordinator
    private lateinit var notificationBridgeCoordinator: HermesNotificationBridgeCoordinator
    private lateinit var serverProfileCoordinator: HermesServerProfileCoordinator

    private var activeOAuthPopup: WebView? = null
    private var activeOAuthFlow: OAuthPopupFlow? = null
    private var activeMainFrameOAuthFlow: OAuthPopupFlow? = null
    private var oauthFlowTimeoutMs: Long = 0
    private val OAUTH_FLOW_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

    // Popups created by onCreateWindow that have not yet been destroyed. A window.open('') that
    // never navigates never reaches the popup's shouldOverrideUrlLoading/onPageStarted, so it would
    // otherwise leak its WebView/renderer resources. Tracked so an orphan sweep and destroyPopup()
    // can clean them up without double-destroying.
    private val trackedPopups = mutableSetOf<WebView>()
    private val ORPHAN_POPUP_SWEEP_MS = 15 * 1000L

    private val serverUrlValidator = ServerUrlValidator()

    private var lastBackPressTime: Long = 0
    private var backPressExitStage: Int = 0
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
        appUpdateManager = AppUpdateManagerFactory.create(this)
        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(settingsRepository, settingsRepository, defaultUrl, defaultDashboardUrl)
        )[MainViewModel::class.java]
        notificationBridgeCoordinator = HermesNotificationBridgeCoordinator(
            context = this,
            bridgeName = HermesNotificationBridgeName,
            settingsRepository = settingsRepository,
            isTrustedSource = ::isTrustedNotificationBridgeSource,
            showNotification = ::showHermesNotification,
            requestNotificationPermissionLauncher = {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            runOnUiThread = { action -> runOnUiThread(action) }
        )
        appUpdateCoordinator = HermesAppUpdateCoordinator(
            context = this,
            activityScope = lifecycleScope,
            settingsRepository = settingsRepository,
            viewModel = viewModel,
            appUpdateManager = appUpdateManager,
            playUpdateLauncher = playUpdateLauncher,
            updateChannel = BuildConfig.UPDATE_CHANNEL,
            githubReleasesApiUrl = BuildConfig.GITHUB_RELEASES_API_URL,
            githubReleasesPageUrl = BuildConfig.GITHUB_RELEASES_PAGE_URL,
            notificationChannelId = HermesNotificationChannelId,
            notificationPermissionState = ::webNotificationPermissionState,
            requestNotificationPermissionIfNeeded = ::requestNotificationPermissionIfNeeded,
            isActivityVisible = { activityVisible },
            appVersionName = ::appVersionName
        )
        serverProfileCoordinator = HermesServerProfileCoordinator(
            context = this,
            activityScope = lifecycleScope,
            settingsRepository = settingsRepository,
            viewModel = viewModel,
            serverUrlValidator = serverUrlValidator,
            onOpenInExternalBrowser = ::openInExternalBrowser,
            onPerformServerProfileSwitch = ::performServerProfileSwitch
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
            appUpdateCoordinator.scheduleAutomaticAppUpdateCheck()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (appUpdateCoordinator.handleIntent(intent)) {
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
            appUpdateCoordinator.resumePlayUpdateIfNeeded()
            appUpdateCoordinator.scheduleAutomaticAppUpdateCheck()
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
        appUpdateCoordinator.cancelAutomaticAppUpdateCheck()
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
                uiState.isSettingsVisible -> {
                    backPressExitStage = 0
                    viewModel.closeSettings()
                }
                webView.canGoBack() -> {
                    backPressExitStage = 0
                    webView.goBack()
                }
                else -> {
                    val currentTime = System.currentTimeMillis()
                    val withinWindow = currentTime - lastBackPressTime < BACK_PRESS_TIMEOUT_MS

                    if (!withinWindow) {
                        backPressExitStage = 0
                    }

                    when (backPressExitStage) {
                        0 -> {
                            // First back press: arm safety flow to open native settings.
                            backPressExitStage = 1
                            lastBackPressTime = currentTime
                            Toast.makeText(this@MainActivity, "Press back again for Application Settings", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            // Second back press: open native settings before allowing exit.
                            backPressExitStage = 2
                            lastBackPressTime = currentTime
                            viewModel.openSettings()
                            Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            // Third back press within timeout: exit app.
                            onRequestExit()
                        }
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
                        uiState.appUpdateReleaseUrl?.takeIf { it.isNotBlank() }?.let(::openInExternalBrowser)
                    },
                    onShareDebugLog = { shareLatestDebugLog() },
                    onDownloadDebugLog = { downloadLatestDebugLog() },
                    onViewGithubIssues = { openInExternalBrowser(HermesGithubIssuesListUrl) },
                    onNewGithubIssue = { openInExternalBrowser(HermesGithubNewIssueUrl) },
                    onAddProfile = { name, url -> serverProfileCoordinator.handleAddServerProfile(name, url) },
                    onDeleteProfile = { profileId -> serverProfileCoordinator.handleDeleteServerProfile(profileId) },
                    onRenameProfile = { profileId, newName -> viewModel.renameServerProfile(profileId, newName) },
                    onEditProfile = { profileId, newName, newUrl -> serverProfileCoordinator.handleEditServerProfile(profileId, newName, newUrl) },
                    onSwitchProfile = { profileId -> serverProfileCoordinator.handleSwitchServerProfile(profileId) },
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
            HermesWebViewConfigurator.configureMainWebView(
                webView = this,
                appVersionName = appVersionName(),
                configureStorageAndCache = ::configureWebViewStorageAndCache,
                disableWebViewDarkening = ::disableWebViewDarkening
            )
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
                    return handleMainWebViewNavigation(target)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
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
                    sourceView = view,
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
        }
    }

    private fun handlePopupNavigation(
        popup: WebView,
        sourceView: WebView?,
        target: String
    ): Boolean {
        if (handleAppSettingsNavigation(target)) {
            destroyPopup(popup)
            return true
        }

        val callbackFlow = activeOAuthFlow.takeIf { activeOAuthPopup === popup }
        if (callbackFlow?.isVerifiedCallbackUrl(target) == true) {
            clearActiveOAuthPopup()
            loadOAuthCallbackInMainWebView(target)
            destroyPopup(popup)
            return true
        }

        val flow = parseTrustedOAuthStart(target)
        if (flow != null) {
            rememberActiveOAuthPopup(popup, flow)
            sourceView?.loadUrl(target)
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
            loadOAuthCallbackInMainWebView(url)
            destroyPopup(popup)
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
        destroyPopup(popup)
    }

    private fun handleMainWebViewNavigation(target: String): Boolean {
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

    private fun handleMainWebViewPageStarted(url: String?) {
        if (!url.isNullOrBlank()) {
            val activeTopLevelFlow = activeMainFrameOAuthFlow
            if (activeTopLevelFlow?.isVerifiedCallbackUrl(url) == true) {
                clearActiveMainFrameOAuth()
            } else {
                parseTrustedOAuthStart(url)?.let { rememberActiveMainFrameOAuth(it) }
            }
        }
        updateOAuthInFlowHost(url)
        viewModel.onPageStarted(url)
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
        notificationBridgeCoordinator.installBridge(view)
    }

    private fun isTrustedNotificationBridgeSource(sourceOrigin: Uri, isMainFrame: Boolean): Boolean {
        if (!isMainFrame) return false
        val normalizedOrigin = normalizePermissionOrigin(sourceOrigin) ?: sourceOrigin.toString()
        return matchesConfiguredWebUiRoute(normalizedOrigin) && matchesConfiguredWebUiRoute(webView.url)
    }

    private fun requestNotificationPermissionIfNeeded() {
        notificationBridgeCoordinator.requestNotificationPermissionIfNeeded()
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
        applyHermesWebUiRuntimeScripts(view)
    }

    private fun applyHermesWebUiRuntimeScripts(view: WebView) {
        view.evaluateJavascript(HermesWebUiScripts.viewportFixScript, null)
        view.evaluateJavascript(HermesWebUiScripts.microphoneFallbackScript, null)
        view.evaluateJavascript(buildHermesWebUiNotificationBridgeScript(), null)
        view.evaluateJavascript(buildHermesWebUiRouteRecoveryScript(), null)
        if (EnableAppSettingsSidebarShim) {
            view.evaluateJavascript(HermesWebUiScripts.appSettingsEntryScript, null)
        }
    }

    private fun installHermesWebUiDocumentStartFixes(view: WebView, serverUrl: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val originRule = UrlOrigins.documentStartOriginRule(serverUrl) ?: return

        removeHermesWebUiDocumentStartFixes()
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
    }

    private fun removeHermesWebUiDocumentStartFixes() {
        microphoneFallbackScriptHandler?.remove()
        notificationBridgeScriptHandler?.remove()
        routeRecoveryScriptHandler?.remove()
        appSettingsEntryScriptHandler?.remove()
        enterKeyNewlineScriptHandler?.remove()
    }

    private fun addDocumentStartScript(
        view: WebView,
        originRule: String,
        script: String
    ): ScriptHandler? {
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
        serverProfileCoordinator.validateServerForPersistence(
            serverUrl = serverUrl,
            onFailure = { result -> showServerValidationRecoveryDialog(serverUrl, result, "Save server") { persist() } }
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

    private fun resetWebSession() {
        viewModel.resetSession()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearHistory()
        webView.clearCache(true)
        webView.clearFormData()
        // Also drop stored HTTP Basic/Digest credentials — otherwise a "reset web session"
        // (sign out & wipe) still leaves saved auth behind on a shared device.
        runCatching { WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword() }
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
        serverProfileCoordinator.preflightConfiguredStartupServer(
            serverUrl = serverUrl,
            startUrl = startUrl,
            onContinueToWebView = webView::loadUrl
        )
    }

    private fun handleAddServerProfile(name: String, url: String) {
        serverProfileCoordinator.handleAddServerProfile(name, url)
    }

    private fun handleEditServerProfile(profileId: String, newName: String, newUrl: String) {
        serverProfileCoordinator.handleEditServerProfile(profileId, newName, newUrl)
    }

    private fun handleDeleteServerProfile(profileId: String) {
        serverProfileCoordinator.handleDeleteServerProfile(profileId)
    }

    private fun handleSwitchServerProfile(profileId: String) {
        serverProfileCoordinator.handleSwitchServerProfile(profileId)
    }

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
        viewModel.setOAuthInFlowHost(null)
        if (activeOAuthPopup == null) {
            oauthFlowTimeoutMs = 0L
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
        val host = if (activeMainFrameOAuthFlow != null && !url.isNullOrBlank() && !urlPolicy.isAllowed(url)) {
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
        }
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

}
