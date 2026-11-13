# Agent instructions for Hermes-Android

This file is the shared entry point for AI assistants working in this
repository. Keep it project-specific and safe to publish. Do not put private
machine setup, credentials, tokens, local network secrets, or personal workflow
notes here.

## Read first

Before making changes, read:

1. `README.md`
2. `ROADMAP.md`
3. `ARCHITECTURE.md`
4. `AGENTS.md`

For implementation work, also inspect the relevant source files under
`app/src/main/java/com/hermeswebui/android/`.

Useful entry points:

- `MainActivity.kt` - Android platform boundary, WebView, intents, downloads, dashboard Custom Tab launch
- `core/security/UrlPolicy.kt` - URL and navigation decisions; also contains the top-level `UrlOrigins` object with origin/URL normalization utilities (`hostFrom`, `hasSameOrigin`, `documentStartOriginRule`, `normalizeOriginUrl`, `normalizedPath`) — use these helpers rather than ad-hoc URI parsing
- `core/security/WebTrustPolicy.kt` - context-specific WebView trust decisions for configured WebUI/dashboard routes, notification bridge sources/targets, and permission origins; keep parsing and trust rules here rather than duplicating them in `MainActivity`
- `core/network/LocalNetworkPolicy.kt` + `core/network/HardwareKeyboardState.kt` - pure, JVM-tested LAN-host/preflight decision (#47, #49) and hardware-keyboard rule (#83); `MainActivity` keeps only the Android permission/Configuration calls. Cookie selection for probes (`HermesApiClient.resolveSessionCookie`, #75) and the status-failure root fallback (`shouldProbeRootAfterStatusFailure`, #61) are likewise unit-tested in `LocalNetworkPolicyTest`.
- `data/SettingsRepository.kt` - encrypted settings persistence; implements `SettingsStore` interface. Uses a versioned `runMigration()` pattern (`KEY_LAST_MIGRATION_VERSION`): when adding new data schema changes, increment `currentMigrationVersion` and add a corresponding migration block. Non-interface methods (`hasRequestedNotificationPermission`, `markNotificationPermissionRequested`, `getLastLoadedUrl`) are called directly by `MainActivity`.
- `domain/ServerUrlValidator.kt` - server URL validation rules
- `domain/ShareIntentParser.kt` - Android share-sheet parsing
- `domain/SharedFileHandoff.kt` - staged share-sheet files; `onShowFileChooser` auto-delivers them only when the main WebView's top-level URL is the configured WebUI route. Other pages (proxy sign-in, OAuth providers) get the normal picker and must not consume or receive the staged files (Issue #136); never equate navigation consent with attachment trust
- `ui/MainViewModel.kt` - app state orchestration (paired with `ui/MainViewModelFactory.kt`)
- `webview/ClipboardImagePolicy.kt` + `webview/HermesClipboardBridgeCoordinator.kt` + `webview/HermesWebView.kt` - image paste (Issue #180). Android WebView never grants `clipboard-read`, so a document-start shim wraps `navigator.clipboard.read()` and falls back to a scoped `WebMessageListener` only after a native `NotAllowedError`/`SecurityError` and with transient user activation. `HermesWebView` advertises image MIME types to the IME (Gboard) only on the configured Hermes route and `MainActivity.deliverCommittedImage` re-checks trust, enforces the 8 MB cap, and dispatches a synthetic `paste` event with a `File`. Raster types only (never SVG), no `addJavascriptInterface`, no new permission; keep the trust check ahead of the clipboard read (`WebViewSecurityContractTest` pins it). Shared by the Webapp and WebUI; do not gate on surface.
- `ui/web/WebShell.kt` - Compose WebView host and refresh/error UX
- `ui/settings/SettingsScreen.kt` + `ui/settings/SettingsSections.kt` - native settings surface and task-based section components (opened via `hermes://app/settings` and the injected WebUI sidebar entry)
- `ui/onboarding/WelcomeScreen.kt` + `ui/onboarding/WelcomeGate.kt` - first-run welcome guide (Issue #176): a swipeable pager, shown once only to a brand-new user (no server configured, not seen, plain launcher open) and reopenable from Settings (`WelcomeGuideEntry`). Never show it over a deep link, share intent or the `hermes://app/settings` recovery link, and never to an existing configured user. It is informational: external pages come only from the fixed `WelcomeLinks` list and open in the browser, with no network call, JavaScript bridge or new permission. It reuses the setup prompts via `copySetupPrompt`; do not claim clipboard copies verify host installation or phone connectivity.
- `ui/settings/ServerSetupHelp.kt` - copy-only guided Hermes-chat setup prompts. Keep `res/raw/setup_*_prompt.txt` in sync with README fenced copy blocks; never execute them, add credentials, or imply clipboard tests verify host installation or phone connectivity.
- `ui/DebugLogFloatingButton.kt` - draggable overlay shown while debug logging is active
- `OAuthPopupFlow.kt` - parses authorization requests and verifies OAuth/OIDC callbacks before allowing in-app provider navigation
- `notification/HermesNotificationBridgeCoordinator.kt` - scoped WebUI Notification API bridge and Android permission reply handling
- `notification/HermesNotificationPresenter.kt` - renders Android-backed WebUI notifications and routes tapped notification intents back into the trusted WebView
- `background/HermesReconnectService.kt` + `background/ReconnectBackgroundPolicy.kt` - foreground service (manifest `foregroundServiceType="dataSync"`) that keeps the bounded reconnect probe and `/api/session/stream` subscription alive while the activity is backgrounded; policy helpers gate when the service should run/keep-alive/cancel
- `background/HermesForegroundServiceCoordinator.kt` - owns MainActivity-facing reconnect/debug foreground-service lifecycle sync and promotion/teardown rules
- `background/ApprovalClient.kt` + `background/ApprovalActionSupport.kt` + `background/ReconnectSessionStreamSupport.kt` - authenticated `/api/approval/pending`/`/api/approval/respond` client and SSE event support used by the notification approval actions
- Background session IDs come from trusted WebUI URLs ending in `/session/{id}`, including mount prefixes. Use `UrlOrigins.pathSegments` to split before decoding once; do not restore the dashboard-only `/{id}` assumption or parse untrusted provider URLs to enable monitoring.
- Session-monitor requests must refresh when reconnect/session/cookie/privacy state changes. Fence canceled streams and stale approval completions so they cannot stop or overwrite replacement monitoring. `HermesSessionMonitorInstrumentationTest` exercises the actual Android service, notification PendingIntents, and loopback HTTP/SSE on the emulator, not upstream WebUI behavior.
- `background/HermesDebugLoggingService.kt` + `background/DebugLogBootstrap.kt` - opt-in foreground logcat capture into `files/debug-logs/` with a persistent Stop notification
- `server/HermesServerProfileCoordinator.kt` - startup preflight plus server-profile add/edit/delete/switch validation and confirmation flows
- `launcher/LauncherIconManager.kt` - PackageManager-authoritative launcher artwork selection. Keep the stable `WebUiLauncher`/`AgentLauncher` aliases and always-enabled `MainActivity`; toggle only aliases, preserving share/deep links and channel labels. Profile `serverType` is a cosmetic manual override (UNSPECIFIED means Automatic); `detectedServerType` remembers explicit runtime-marker detection from the configured main-frame route. Fence asynchronous probes across navigation/server changes, clear detection on URL edits, preserve manual overrides, and never use either label as a trust signal or change launcher artwork automatically.
- `webview/HermesWebViewConfigurator.kt` - shared main/popup WebView hardening and settings setup
- `webview/HermesWebViewDownloadListener.kt` - allowlisted DownloadManager handoff with session cookies, user agent, and AndroidX RFC-compatible filename parsing; preserve `filename*`, original extensions, and explicit downloads from inline image headers rather than using legacy `android.webkit.URLUtil`; on Android 10+ save to the shared `Download` folder (no storage permission needed) so files appear in Files > Downloads, keeping the app-specific folder only on Android 8/9 or when shared storage is unavailable (Issue #130)
- `webui/VoiceCaptureLifecycleGate.kt` - new-request WebView microphone gate and proposed `hermes-app-foreground` host signal; a pending trusted permission result can be granted while started before resume
- `webui/HermesWebUiScripts.kt` - document-start WebUI compatibility shims (hybrid viewport polyfill with generic collapse detection, microphone fallback, Enter-key newline behavior, sidebar settings injector, notification bridge script payloads); keep these scoped to the configured Hermes WebUI origin
- `update/HermesAppUpdateCoordinator.kt` - app-update checks, update notifications, Play update launch, and GitHub APK download intents
- `update/GitHubReleaseUpdateChecker.kt` + `update/AppVersionComparator.kt` + `update/AppUpdateCheckResult.kt` - GitHub-channel update check (Play channel uses Play Core through `update/PlayUpdateClient.kt`, implemented in `app/src/play/java` with a no-op FOSS twin in `app/src/foss/java`)
- `update/AppUpdateDownloadPolicy.kt` - strict GitHub APK download host allowlist used by update download intents (`github.com` and `*.githubusercontent.com` only)

Known Android WebView compatibility behavior lives in `MainActivity.kt`:

- The official Hermes Agent Webapp (v0.22.6+) can be the configured primary frontend, independently of Hermes WebUI. Identify it by `window.__HERMES_UI_SURFACE__ === 'webapp'`, never a generic title or Dashboard URL. Android Back opens native Settings without navigating workspace history; closing Settings must preserve the mounted WebView/sessionStorage. Keep WebUI-only Enter/sidebar/Clarify/workspace DOM shims and selector-specific viewport CSS out of this React workspace. Those WebUI-only shims are gated positively by each script's `isHermesWebUiPage()` (the `__HERMES_WEBUI_BUNDLE_VERSION__` marker, or the legacy `.layout` + `main.main` DOM), so a Hermes Agent Dashboard configured as the server URL also receives none of them (Issue #190); never fall back to "not the Webapp" as the WebUI test. Generic viewport repair, pinch zoom, uploads/downloads, notification facade, and microphone permission hosting are shared. Do not apply WebUI session URL/SSE/approval assumptions to the official gateway WebSocket protocol or emulate Electron. Live authenticated acceptance and remaining protocol/blob-download/notification-click gaps are tracked in `ROADMAP.md`.
- The Compose root applies `WindowInsets.safeDrawing` so the WebView shell and native snackbar do not overlap the Android status or navigation bars.
- Forced/algorithmic WebView darkening is disabled so Hermes WebUI keeps its own colors.
- `network_security_config.xml` trusts both system and user-installed Android CAs so WebView can reach deployments signed by a CA installed by the device owner. This is app-wide, not per-profile; retain certificate-chain and hostname verification and never use `onReceivedSslError` to bypass validation.
- WebView uses default browser-managed HTTP/service-worker caching and DOM storage for Hermes WebUI assets. Do not add a parallel native stale-site mirror for authenticated WebUI HTML/API responses; server-profile switches must keep clearing cookies, WebStorage, WebView cache, form data, and history before loading the new host.
- A hybrid viewport polyfill is installed at document start for the configured WebUI origin because Android WebView computes CSS viewport units (`vh`, `dvh`, etc.) as `0px`. The early install is required so WebUI boot code never measures a collapsed root after OAuth or a cold load; the runtime application remains as a fallback. The polyfill injects stable layout-viewport CSS custom properties (`--vh`, `--dvh`) plus separate visual-viewport height/top values for keyboard-constrained prompts, applies baseline CSS for root/layout containers, and uses generic collapse detection to find and repair ANY element that appears collapsed—without needing explicit selectors for each WebUI component. Collapse detection must also repair fully collapsed (`rect.height <= 0`) shell containers when they hide substantial page-level content (e.g. a `100vh` root collapsing to 0px), otherwise entire pages render blank. A zero-height clipping element (`overflow` not visible) whose ancestors up to `body` are also zero height, with no in-flow sibling at any level and at least one in-flow child, is the page shell (for example the Dashboard's `h-dvh overflow-hidden` root, Issue #190): repair it in the same pass to the full measured viewport height, not the 25%/82% panel cap, and clear nested repairs below it so they are re-measured. Never classify a portal holding only fixed/absolute layers, or content below an in-flow header, as the page shell. Generic repair changes only height constraints: preserve every element's existing overflow value and priority, never create a new scroll/clipping container, and never remove an existing inline overflow declaration when the repair clears.
- The approval and clarify prompt panels (`.approval-card`, `.clarify-card`) are absolutely anchored just above the composer (`bottom:-24px` inside the zero-height `.composer-flyout`) and cap their height with viewport units that Android WebView can evaluate as 0. If the anchor falls below the visual viewport (for example behind an overlay keyboard), the polyfill first shifts the expanded card upward by that overlap using a per-card CSS property, then re-caps it to the measured visible space between the greater of the `.app-titlebar`/visual-viewport top and the shifted anchor, minus an 8px gap. The previous shift is included when measuring the invariant anchor so repeated scans cannot oscillate. The cap must be allowed below WebUI's preferred 180px floor when the keyboard or a short viewport leaves less room; internal scrolling preserves access without placing the panel behind the IME or titlebar. The polyfill re-fits an open card on `visualViewport` `resize` and, only while an expanded card is visible, on `visualViewport` `scroll` (an overlay keyboard or pan changes `offsetTop` without resizing); keep that scroll listener scoped so ordinary pinch or pan never schedules extra scans (Issue #164). Keep these panels and the `.composer-flyout` anchor excluded from generic collapse repair so the measured contract cannot oscillate; other flyout children (e.g. the composer terminal) still size with vh units and remain eligible for generic repair. The `.composer-wrap` is the floating cards' clipping ancestor: a generic repair there (`overflow-y:auto`) turns it into a scroll container that re-clips the card to a sliver behind the composer, and a retained repair never recovers (#80 follow-up). Keep `.composer-wrap` excluded from generic repair too, and keep the injected `.composer-flyout, .composer-wrap { overflow: visible !important; }` rule so neither ancestor can ever clip the floating prompt surface.
- Android no longer writes WebUI `/api/dashboard/config`. WebUI owns the Official Hermes Dashboard setting, including Auto-detect and persistence. Android may normalize an explicitly configured local dashboard URL to its origin for Custom Tab matching and does not persist dashboard-origin pages as the app startup URL. OAuth/OIDC callback URLs for the configured Hermes WebUI origin must bypass dashboard Custom Tab matching and return to the primary WebView.
- WebView microphone access is handled in `MainActivity.kt` through Android `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, plus `WebChromeClient.onPermissionRequest`; grant only `PermissionRequest.RESOURCE_AUDIO_CAPTURE` for trusted Hermes pages (prefer explicit allowlisted HTTP/HTTPS origins, with null/opaque-origin fallback only while the active main-frame URL is the configured Hermes WebUI route).
- Voice conversation is WebUI-owned. Android must stay a thin host: never add a native `AudioRecord`/`SpeechRecognizer` listener, wake detector, native TTS, Android-side profile/session voice state, or `microphone` foreground-service type. `VoiceCaptureLifecycleGate` denies new requests while not resumed, with a pending trusted RECORD_AUDIO result allowed while started before resume. It dispatches the proposed `hermes-app-foreground` event (`active:false` on pause/stop, `active:true` on resume/page load). Current WebUI does not consume it: do not claim this ends existing capture/playback or prevents WebUI re-arm without a verified upstream consumer. Suppress the pause signal only while the Android RECORD_AUDIO prompt is pending. Lift `mediaPlaybackRequiresUserGesture` only for the configured WebUI route; tighten it before permitted non-WebUI main-frame navigation and re-check the actual `view.url` on commit/finish. Externalized/blocked links and subframe navigation must not change the loaded WebUI's playback permission.
- Android WebView can expose Web Speech API objects that fail with `not-allowed` before the WebView permission bridge is used. Keep the document-start `mic_force_mediarecorder` fallback scoped to the configured Hermes WebUI origin so WebUI voice input uses MediaRecorder/getUserMedia instead.
- The Issue #34 Enter-key shim forces a plain Enter in the composer to insert a newline (so multi-line messages can be composed on a soft keyboard) via capture-phase `stopImmediatePropagation` + `preventDefault` registered before WebUI's submit handler. As of Issue #83 this is hardware-keyboard-aware: `MainActivity` pushes a live `window.__hermesAndroidHardwareKeyboard` flag (detected via `Configuration.keyboard`/`hardKeyboardHidden`, re-synced on page load and on `onConfigurationChanged` — the manifest already lists `keyboard|keyboardHidden` in `configChanges`), and the shim reads it at keydown time. When a hardware keyboard is attached the shim returns early so WebUI's native Enter-to-submit / Shift+Enter-newline handling runs (desktop convention); with no hardware keyboard it keeps forcing the newline. Keep the flag read at event time (not install time) so keyboard attach/detach applies without a page reload.
- The Issue #65/#92 Clarify keyboard shim suppresses only the first automatic focus on `#clarifyInput` for each request. Key the one-shot marker and short user-intent window to WebUI's current Clarify ID/signature, with a question/choices DOM fallback, because WebUI can replace a visible prompt without hiding the card. Direct Android touch input, hardware Tab navigation, the Clarify **Other** action, and programmatic validation/error refocus after user interaction must still focus that field. Reset the marker when the card becomes hidden. Unrelated dialogs—including the workspace new-folder name input from Issue #90—must never be scanned, blurred, or have `tabindex` rewritten; do not restore blanket modal-input handling.
- The injected Hermes WebUI "Application Settings" entry should anchor immediately after the WebUI Settings item when present, with Help only as a fallback anchor for older or changed sidebar markup. Do not add a persistent native overlay button for this entry; it should appear only with the WebUI sidebar.
- The active server profile (shown as **Current** in Settings) is the single source of truth for which server the main WebView loads. `SettingsRepository` keeps `server_url` and the active profile id in step (editing the Current profile's URL, manual saves, and a cold-start repair of a diverged `server_url`), and `server/SelectedServerNavigation.kt` decides the switch, Current-row reconnect, and cold-start URLs so a stale page on another origin (including the same host on a different port) is never restored.
- `hermes://app/settings` is exported as a native recovery deep link and opens `SettingsScreen` without relying on the current WebView route. Both `hermes://app/settings` and `hermes://session/{id}` must work from a cold start as well as `onNewIntent` (Issue #170): a session link feeds the same trusted-route startup override slot as a notification tap, so `SelectedServerNavigation.startupUrl` still gates it, and the settings link is skipped on activity recreation.
- WebUI browser notifications are handled through `HermesNotificationBridgeCoordinator` + `HermesNotificationPresenter`, with `MainActivity.kt` wiring Android `POST_NOTIFICATIONS`, the native notification channel, the document-start `Notification`/`ServiceWorkerRegistration.showNotification` compatibility facade, and trusted notification tap routing. Keep the bridge scoped to the configured Hermes WebUI route, reject subframes/non-WebUI origins, and validate notification tap URLs through the host allowlist before loading.
- Native app update alerts share the existing `Hermes updates` notification channel but are selected by build channel. Automatic checks should run each time the app opens (while automatic checks are enabled), and manual Settings checks still run immediately. Keep the shared settings/notification UX common, with `BuildConfig.UPDATE_CHANNEL = "play"` using Google Play Core in-app updates, `"github"` checking GitHub Releases plus the `*-github.apk` asset for direct downloads and release-note excerpts, and `"none"` avoiding production update prompts in debug and F-Droid `release` builds.
- Hermes WebUI implements its own conversation long-press menus from a touch timer (e.g. `static/sessions.js`, ~400ms) and renders them as `position:fixed` elements capped with `max-height: calc(100vh - 16px)`. Keep `isLongClickable = true` without a consuming long-click listener so Android text-selection handles remain available (Issue #35); do not add `contextmenu` synthesis, `touchcancel` guards, or a `startActionMode` override. Issue #6 ultimately proved the WebUI touch timer already works and the invisible menu was a viewport-unit collapse. If menus render tiny/clipped again, check the hybrid viewport repair, not gesture interception, z-index, opacity, or stacking.
- Client-certificate settings support PKCS#12 (`.pfx`/`.p12`) files only. Keep certificate requests scoped to allowlisted hosts, select a usable private-key entry, and fail closed on unreadable, malformed, password-mismatched, or keyless stores; never let certificate parsing throw through `WebViewClient.onReceivedClientCertRequest`.
- The `hermes-android-viewport-fix` style must not lock vertical page scrolling. Keep body overflow override scoped to horizontal overflow only; forcing `body { overflow: hidden }` clips expandable WebUI content such as generated update summaries inside Android WebView. The generic collapse detection handles update-summary panels and other viewport-unit-based elements automatically without explicit selectors; the approval/clarify prompt panels are the one exception, using the measured titlebar/composer-aware cap described above instead of generic repair.
- Do not reintroduce a parallel native drawer or custom Android Terminal/menu button for the dashboard link.
- Hermes WebUI DOM/CSS compatibility shims must stay scoped to the configured WebUI route. Do not inject the viewport polyfill into the official dashboard origin; dashboard links should use Chrome Custom Tabs unless a future task explicitly reopens the app-WebView approach. The one exception is OAuth/OIDC provider pages: while a main-frame OAuth flow is active, inject only the viewport polyfill (never the other WebUI shims) into those HTTP/HTTPS pages, because provider login roots that use viewport units (e.g. `h-screen`) otherwise collapse to 0px and render blank (issue #66).
- OAuth/OIDC code-flow navigations may temporarily load non-allowlisted HTTP/HTTPS provider pages in-app only after Android has parsed an authorization URL whose `redirect_uri` returns to the configured Hermes WebUI origin. Scheme compatibility may upgrade HTTP to HTTPS for public-IP/proxy deployments, but must never downgrade an HTTPS configured origin or declared callback to HTTP. After a verified callback, keep the callback and its bounded same-origin redirect chain in the primary WebView until a page with explicit Hermes WebUI runtime/DOM markers finishes; do not use a one-navigation dashboard bypass or clear on an arbitrary same-origin page because WebView callback ordering, redirect counts, and interstitial pages vary. Do not broaden this to arbitrary external links or non-web schemes. OAuth provider and callback URLs must never be persisted as `lastLoadedUrl` or restored as the cold-start URL; only final configured WebUI routes may be remembered.
- Reverse-proxy redirects use `ProxyAuthNavigation`, not relaxed OAuth parsing or a persistent allowlist entry. Valid web redirects are reachable but remain unverified until sign-in; ask for explicit consent showing both origins, but only for cross-origin redirects: `ProxyAuthNavigation.classifyRedirect` treats same-origin redirects as `AUTH_REQUIRED` and same-host HTTP-to-HTTPS upgrades as a stale server URL with a re-validated **Use HTTPS** recovery, never as proxy consent. In-WebView consent requires the redirecting main-frame hop (tracked by `MainActivity` through `loadInMainWebView`, allowed navigations, page starts, and back navigation) to be exactly the configured server origin via `ProxyAuthNavigation.mainFrameRedirectConsentOrigin`; never attribute a redirect to the server from `view.url` or the host-only allowlist. Fence pending consent with the main-frame navigation generation and recheck the configured route and original hop before approval; `WebView.url` can be null after an intercepted initial redirect and must not be required there. A server switch commits an `about:blank` placeholder before the new server loads; treat it as no committed page (not a non-configured page), and never let its late page start count as a hop or bump the generation, or the new server's redirect is externalized or its pending consent voided. Grant only the exact server/login origins for five monotonic minutes, main frame only, with no HTTPS downgrade or credential-bearing URL. Preserve the return/persistence fence until a configured-origin page proves Hermes markers, even after permission expiry. Preflight probes reuse URL-scoped WebView cookies but never follow/forward to the login host. Keep redirect bodies and token-bearing Location values out of diagnostics. The loopback fixture exercises actual consent, WebView login/return, cookies, and session-expiry recovery, not a live Authelia deployment.
- WebView HTTP Basic challenges (Issue #185) use a native username/password dialog only when the challenge host exactly matches the configured server host. Configure the password input type after `setSingleLine()` so its password transformation remains active. Apply the same handler to main and popup WebViews, cancel challenges for other hosts, and do not restore app-managed saving or automatic reuse of prompt credentials: WebView exposes the challenged host and realm but not its scheme or port. Preserve previously remembered encrypted data as inert until explicitly removed through **Forget saved sign-ins**. WebView may reuse same-origin credentials in memory, and credentials embedded in a saved server URL remain part of that URL. An unnamed new profile copies the full URL into its name, so removing such credentials can also require editing the name. Forgetting legacy sign-ins changes neither URLs nor names and does not clear WebView's in-memory authentication cache. Keep Basic authentication separate from redirect-based proxy consent.
- Restored routes with URL userinfo must match the configured URL's browser-normalized, case-sensitive userinfo; credential-free routes remain valid. Enforce this for startup, reconnect, notification targets, workspace recovery and Back history on the server host, including other ports, without clearing WebView authentication state. Block main-frame requests exposing outdated userinfo, including POST and popup handoffs; the off-main-thread interceptor reads an immutable selection snapshot, updated before a Current-profile edit is persisted. Clear history when it contains incompatible credentials from the previous server host, revoke the old page's recovery handler, and fence pending permission and VPN work across server changes. Discard delayed page, history and error callbacks that expose outdated URL credentials, but do not infer a navigation generation from URL or callback ordering: same-credential or credential-free late callbacks remain a limitation tracked in #184. A local-network permission error must not automatically reload a previously selected HTTP origin after selecting HTTPS; surface the ordinary error instead. Retain the mounted WebView rather than resetting it to fence callbacks; navigation can still discard page state. `SettingsRepository` atomically drops an incompatible last URL when settings change and rejects late stale writes, including across repository instances. Same-origin Current-profile edits must reinstall document-start scripts before reloading, and profile matching must not lowercase userinfo. Legacy incompatible stored routes may persist until replaced; never claim this is a full sign-out.
- Proxy-gateway OAuth and Sign in with Chrome (Issue #184): an OIDC start whose `redirect_uri` is not the Hermes origin stays in-app only through `ProxyAuthNavigation.allowsGatewayOAuthCallback` (active consent window, current main-frame hop and callback both exactly the approved login origin, no userinfo); never trust gateway callbacks by host, by allowlist, or after consent expiry. A verified callback must end `activeMainFrameOAuthFlow` even when proxy consent short-circuits `handleMainWebViewNavigation` (`completeMainFrameOAuthIfCallback`), or later external links load in-app. The opt-in Auth Tab path must keep its guards ahead of `authTab.launch(...)`: setting on, no active in-app OAuth flow, https default-port callback, a default browser that reports `isAuthTabSupported`, and `setPackage` to that browser (an unsupported browser silently opens a Custom Tab that never returns). Load a returned URL only after re-checking the server/consent and `isVerifiedCallbackUrl`; fall back to in-app sign-in otherwise. Keep the manifest `<queries>` for browsable https and the Custom Tabs service. `WebViewSecurityContractTest` pins this; the emulator has no Chrome, so a real Auth Tab round trip is untested in CI.
- Android 16/17 local-network permission handling is best-effort for obvious LAN hosts (`localhost`, `.local`, private/link-local IPs): request `ACCESS_LOCAL_NETWORK` before first load, but continue startup/save/switch flows when that preflight prompt is denied on OEM builds that expose no grant toggle. Keep the hard-fail recovery path keyed to real WebView `ERR_LOCAL_NETWORK_PERMISSION_MISSING` failures.
- SSE capability (`HermesApiClient.detectSseCapability`) and reconnect-liveness (`isReconnectSseReachable`) probes must attach the WebView session cookie for the configured origin (explicit parameter, guarded `CookieManager` fallback) so password/OIDC-protected servers authenticate like `/api/session/stream`. HTTP 401/403 probe responses classify as `SseCapability.AUTH_REQUIRED` (capability unverified, transport preserved) — never as feature-unavailable — so sign-in walls cannot silently disable SSE transport (issue #75).

## AI Agent Capabilities

AI agents working in this repository have access to the GitHub CLI (GH CLI) and can:

- Fetch and analyze GitHub issues, pull requests, and discussions
- Query repository metadata, branches, and releases
- Review diffs and commit history
- Assist with issue triage, impact analysis, and prioritization

When a user references GitHub issues (e.g., by URL or issue number), agents should use GH CLI to retrieve full issue details rather than asking the user to copy-paste them.

### PR markdown formatting

- When creating or editing PR bodies from PowerShell, prefer `gh pr create --body-file <path>` or `gh pr edit --body-file <path>` with a multi-line markdown file.
- Do not pass escaped newline sequences (for example `\n`) as literal text in `--body`.
- After PR updates, verify rendering with `gh pr view` and fix immediately if markdown appears as literal escape sequences.
- Apply the same rule to issue comments and release/edit bodies: use file-based multiline markdown (`--body-file`) instead of inline escaped text.
- If you must use inline `--body`, use a true PowerShell multiline here-string and verify output immediately.
- Consider markdown rendering broken until verified in a non-JSON view (`gh pr view`, `gh issue view`, or GitHub web UI) after each update.

### GitHub issue + PR workflow

- Before GitHub operations, confirm the active `gh` account is `Paladin173` with `gh auth status --hostname github.com`; if another account is active, switch with `gh auth switch --hostname github.com --user Paladin173`. Verify the active account again before publishing, commenting, or changing issue/PR state.
- Always work on issues through a dedicated branch and a pull request. Never commit issue fixes directly to `main`.
- For issue work, create or reuse a branch named after the issue (for example `issue-73`) before publishing any code.
- Push the branch to GitHub with `git push --set-upstream origin <branch>` once the work is ready for review.
- Create the PR with `gh pr create --title "<user-facing release note>" --body-file <path-to-markdown> --base main --head <branch>`; do not hand-write a long inline body in `--body`.
- When working on an issue, assign the issue and its associated pull request to `Paladin173` using `gh issue edit <issue-number> --add-assignee Paladin173` and `gh pr edit <pr-number> --add-assignee Paladin173`.
- Attach the PR to the issue in the PR body by including a closing keyword such as `Fixes #123` or `Closes #123`; use `Related #123` when the PR contributes without fully resolving the issue.
- Before closing any issue, always add a comment documenting the outcome, even when the issue is closed by a merged PR, manually, or through an automatic closing keyword. A concise copy of the associated PR's `What changed` and `Testing` sections is acceptable; otherwise include the root cause, what changed, and how it was verified. Post it before closing with a file-based markdown body, such as `gh issue comment <issue-number> --body-file <path-to-markdown>` or `gh api repos/<owner>/<repo>/issues/<number>/comments --input <path-to-markdown>`.
- After posting the comment and closing the issue, verify both the rendered comment and final issue state with normal `gh issue view` output, and fix any markdown formatting regression immediately. If a closing PR automatically closes the issue before the comment can be posted, add the comment immediately afterward and still verify both.

### Required PR and issue-comment structure

Every agent (Claude, Codex, Copilot, or any other) must write PRs and
issue-closing comments in this shape. `.github/pull_request_template.md` is the
PR skeleton; fill it in, do not replace it.

**PR body**, in this order:

1. `## What changed` - written for someone installing the APK, not for a
   reviewer reading the diff. Lead with the user-visible outcome, then a short
   list or numbered breakdown when there are several parts. State what is
   deliberately *not* affected (for example "never interrupts the recovery
   link", "no new permission or JavaScript bridge"). End with the closing
   keyword line (`Fixes #123.` or `Related #123.`).
2. `## Testing` - name each test class and what it proves, with counts when
   useful, plus the commands run and their results (`testDebugUnitTest`,
   `lintDebug`, markdown check, emulator API level, what CI covers).
   Always include:
   - a **mutation check**: which guard or behavior was deliberately broken, that
     the matching test failed, and that the source was restored (every new guard
     or test must be shown to fail, per the QA workflow below);
   - any manual on-device or emulator check, stated plainly;
   - a **Not covered** line listing what was not verified (for example external
     link taps, no live server, no emulator). Never imply coverage you did not
     run, and never hide a failing or skipped check.
3. QA checklist and Live QA summary from the template (mark items not
   applicable rather than deleting them).
4. Last line: `🤖 Generated with [Claude Code](https://claude.com/claude-code)`
   when Claude Code authored it; other agents use their own equivalent
   attribution line.

**Issue-closing comment** (posted before closing, via `--body-file`):

```markdown
## Outcome

Shipped in #<PR>. <One sentence on the user-visible result.>

**What changed:**
- <User-facing bullets, same wording as the PR's What changed>

**Verification:** <Tests added/updated and where they ran (emulator API, CI),
the mutation check result, any manual check, and what is not tested.>
```

For a bug, add a short **Root cause** line before **What changed**. For work
declined or redirected (for example a WebUI-owned fix), say so and name the
correct repository instead of closing silently.

Both artifacts must stay free of server URLs, hostnames, credentials, and
local machine paths. Verify the rendered result with `gh pr view` /
`gh issue view` after posting.

### PR and release-note quality

- Write PR titles as user-facing release-note entries when the change may ship
  to testers. Prefer "Fix update summary scrolling in Android WebView" over
  internal-only wording like "Adjust workflow" or "Patch MainActivity."
- In PR bodies, include a short "What changed" section and a short "Testing"
  section when behavior changes. Keep both understandable to someone installing
  the APK or Play internal test build.
- When a PR completes an issue, use GitHub closing keywords such as
  `Fixes #123` or `Closes #123`. Use `Related #123` when it only contributes to
  the issue.
- Apply labels that help generated release notes group the change. The
  repository label set is deliberately small, and these are the only labels
  `.github/release.yml` reacts to:
  - `enhancement` or `user-facing` - grouped under "Features and Improvements"
  - `bug` - grouped under "Fixes"
  - `documentation` - grouped under "Documentation"
  - `skip-changelog` - omitted from user-facing release notes entirely
- Anything unlabeled falls under "Other Changes", which is a fine default for
  internal refactors and CI work.
- `needs-triage` and `help wanted` are issue-triage labels and do not affect
  release notes.
- Do not invent labels in a PR body or in these instructions. GitHub silently
  ignores a label that does not exist, so a category keyed to a made-up label
  never renders. Create the label first with `gh label create` if a new grouping
  is genuinely needed, then add it to both `.github/release.yml` and this list.
- Do not make release notes primarily about commit hashes, workflow run IDs,
  artifact names, or SHA-256 values. Keep those in workflow logs or summaries.

## Scope

This repository is the standalone Android app.

- Modify this repo only unless the human explicitly asks for sibling repo work.
- The sibling `hermes-webui` repo may be read for reference, but do not edit it
  from this workspace task without explicit instruction.
- Treat this project as a stable Android wrapper. Bugs and PRs here should be
  Android-app-specific: WebView hosting, permissions, settings, share/download,
  notifications, deep links, build, signing, and release behavior.
- Redirect WebUI layout, styling, animation, routing, API behavior, and product
  workflow changes to the Hermes WebUI repository.
- Keep the Android app a thin, secure companion to Hermes WebUI.
- Prefer incremental changes over broad rewrites.
- Treat `applicationId` and `namespace` as release-critical identity. Do not
  change either without an explicit user decision. The finalized pre-release
  identity is `com.hermeswebui.android`.
- Preserve the channel identity split: Google Play builds use the `play` build
  type and `com.hermeswebui.android`; GitHub APK builds use the
  `github` build type, `com.hermeswebui.android.github`, and a `-github`
  version name suffix so both channels can install side by side. The
  `release` build type is the F-Droid build: `com.hermeswebui.android.fdroid`,
  `UPDATE_CHANNEL = "none"`, no `versionNameSuffix` (F-Droid checks the built
  versionName against its recipe), and buildable unsigned without a keystore.
  Debug builds
  use `applicationIdSuffix = ".debug"` (`com.hermeswebui.android.debug`) and
  display as "Hermes DEBUG" so test builds are visually distinct from release
  builds on the same device.
- Keep Google Play, Play Services, and Firebase libraries out of every build
  except `play`. Declare them only as `playImplementation`, put the code that
  uses them under `app/src/play/java` behind a `main` interface (as with
  `update/PlayUpdateClient.kt`), and add a no-op twin under `app/src/foss/java`.
  `:app:verifyFossDependencies` (run in CI and by the orchestrator) and
  `tools/tests/test_fdroid_metadata.py` enforce this. Do not reintroduce the
  Foojay toolchain resolver plugin or other non-FOSS Gradle plugins; F-Droid's
  scanner rejects them.
- WebView passkeys use AndroidX Credential Manager alongside the existing
  `WEB_AUTHENTICATION_SUPPORT_FOR_APP` setting. Keep the base
  `androidx.credentials:credentials` artifact shared across all variants and
  do not add `credentials-play-services-auth` to any variant; the optional
  adapter would introduce a separate Play-only passkey dependency path and
  may improve compatibility on older devices. Passkeys remain subject to
  Android/WebView support and an installed credential provider. They also
  require the identity-provider sign-in domain to publish Digital Asset Links
  for the exact installed application ID and signing certificate. Because
  server domains and signing identities vary by operator and build channel, do
  not claim end-to-end passkey support without validating that association and
  the actual provider/device flow.
- F-Droid builds from each `v<version>` tag using
  `fdroid/com.hermeswebui.android.fdroid.yml` (submitted to fdroiddata) and the
  store text in `fastlane/metadata/android/en-US/`. Its auto-update reads
  `val appVersionName` in `app/build.gradle.kts` and the README `Version code`
  line, so keep both formats intact.

## Product direction

Hermes-Android should feel native while keeping Hermes WebUI as the source of
product behavior. Native code should focus on:

- secure WebView hosting
- WebView compatibility for Hermes WebUI rendering on Android
- Android navigation and lifecycle
- share, file, download, and notification integration
- encrypted local settings
- native security affordances such as biometric lock

Do not add native screens that duplicate large WebUI workflows unless the
roadmap or user request explicitly calls for it.

## Security rules

- Preserve HTTP and HTTPS support for configured Hermes hosts.
- Preserve host allowlist enforcement.
- Externalize non-allowlisted HTTP/HTTPS navigation.
- Keep non-web schemes blocked.
- Do not add JavaScript bridges for secrets.
- Keep signing keys, API keys, passwords, and local machine paths out of git.
- Local release signing must use the untracked repo-root `keystore.properties`; CI release signing must use `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` inputs or environment variables.

## Documentation rules

Documentation is part of done.

Update docs when behavior, setup, architecture, or workflow changes:

- `README.md` for user-facing setup, features, and quick-start guidance
- `ROADMAP.md` for progress, wishlist items, priorities, and completed work
- `ARCHITECTURE.md` for runtime flow, boundaries, and extension points
- `AGENTS.md` for coordination rules that future agents must follow

When the user states a wishlist item, add it to `ROADMAP.md` unless it is
clearly out of scope or already tracked.

When package identity, release signing, store distribution, or public release
behavior changes, update `ROADMAP.md` and `README.md` in the same change.

The repo uses four workflows for building and releasing:
- `.github/workflows/1-orchestration-release.yml` — single signed release entry point; builds both the GitHub APK and Play AAB, then publishes GitHub and Play production in one run.
- `.github/workflows/2-publish-github-apk.yml` — publishes the signed APK to GitHub Releases.
- `.github/workflows/3-publish-play-store-production.yml` — publishes the AAB to the Play **production** track.
- `.github/workflows/play-store-beta-manual.yml` — optional/manual workflow that submits the AAB to the Play **open testing (beta)** track when needed.

Keep all four workflows aligned with `app/build.gradle.kts`,
`keystore.properties.example`, and the documented GitHub secrets whenever the
release flow changes. The GitHub publish workflow should publish only the
`hermes-webui-v<version>-github.apk` APK, the Play production workflow should
submit only the `hermes-webui-v<version>.aab` AAB to the production track,
tag-triggered releases should match the Gradle `versionName`, and the public
GitHub release body should contain human-readable What's New notes rather than
build metadata.
Release orchestration builds the reviewed `appVersionName` and README metadata
already checked into Git; it must never edit, commit, or push source. Prepare
that metadata with `python tools/bump_version.py <version> --change ... --summary ...`
(Gradle, README, F-Droid changelog and the ROADMAP `REL-nnn` row in one step)
and ship it through a `release-<version>` pull request; do not hand-edit the four
files. Gradle
derives `versionCode` from semantic version (`major*10000 + minor*100 + patch`).
GitHub releases should use generated notes configured by `.github/release.yml`.
Generate release metadata once in the build job and bundle it with both signed
artifacts: GitHub notes retain clickable PR links, while Play Store `en-US`
What's New text keeps compact PR/issue URLs, stays below the Play text limit,
and ends with:
`Report issues through the in-app bug report tool.`
Keep `RELEASE.md` aligned with the workflow operator path whenever release
automation changes.
Production Play descriptions live in `store/google-play/en-US/` and are published
by `tools/publish_play_listing.py` after AAB submission, with fresh-edit API
readback. Preserve existing title/video. Keep F-Droid-specific descriptions under
`fastlane/metadata/android/en-US/`; do not publish their channel/privacy wording
to Play or claim API-committed text is already visible on the public storefront.

Separate from release publishing, CI uses `.github/workflows/0-ci-build-and-test.yml` to gate pull requests and direct `main` pushes without signing secrets. Each check runs as its own job so a failure names the gate that broke: `docs-render`, `docs-links`, `release-tooling-tests`, `webui-script-syntax`, `webui-script-lint`, `unit-tests`, `android-lint`, and `debug-build`. Keep every job's `timeout-minutes` set; an untimed job can burn the six-hour runner default. Pull requests and direct `main` pushes that change Android source or build inputs also run the complete unfiltered `connectedDebugAndroidTest` suite on Android API 35 and 36. Release builds run the full API 36 suite again, then verify APK and AAB signatures before upload. Keep contributor verification steps aligned with these gates when changing build/test flow.

The unfiltered debug unit suite covers Android voice ownership, microphone permission lifecycle, and selected-server navigation policies. `SelectedServerSettingsInstrumentationTest` exercises the actual encrypted repository with isolated preference files: normalized matching-profile selection, missing/stale active-id repair, explicit selection precedence, edited-server persistence/allowlists, and startup/reconnect trust. `tools/tests/test_pr_regression_workflow.py` extends the existing workflow contracts to reject narrowed unit/instrumentation commands and preserve the API 35/36 PR and full API 36 release suites. These are ongoing Android regression checks, not evidence that upstream WebUI consumes the foreground event or that loopback service tests verify upstream voice lifecycle.

WebView test readiness must match the interaction: DOM-only fixtures do not require window focus, text entry waits for the native editor's `InputConnection` and uses Android IME `commitText`, and navigation tests wait for their actual DOM controls rather than treating `WebView.url` as document readiness. Back-history tests must create entries inside a real touch/click handler and await `canGoBack()`; Chromium skips script-only entries without user activation. Keep the unfocused-editor and delayed-login regressions so headless CI cannot pass only by favorable timing.

A documentation-only change set (every changed path ends in `.md`) short-circuits the steps inside `unit-tests`, `android-lint`, and `debug-build`, which have nothing to compile. Those jobs still run and still report a conclusion, because they are required status checks and GitHub reports a *skipped* required check as pending forever. Never convert them back to a job-level `if` that skips them, and keep every step guarded so a docs-only run stays cheap. The detector in the `changes` job is fail-safe by construction: it always exits 0, always emits `docs_only`, and defaults to `false`, so a missing diff base, an unreadable diff, or a single non-doc path runs the full suite; an empty value from a failed detector is treated as "not docs-only". Keep `release-tooling-tests` outside the fast path — it asserts that README release metadata matches Gradle, which is exactly what a docs-only change can break.

The `main` branch requires these checks to pass before merge: `docs-render`, `release-tooling-tests`, `webui-script-syntax`, `webui-script-lint`, `unit-tests`, `android-lint`, and `debug-build`. All seven run unconditionally. `docs-links` and the two `android-ui-smoke` matrix jobs are deliberately not required — the first depends on the network, and the second is an emulator suite not yet promoted by an admin ruleset change (Issue #105). `android-ui-smoke` now always reports: the job runs under `if: ${{ always() }}` and, when `changes` reports `android_app == 'false'`, short-circuits every step instead of being skipped at job level. Every real step is guarded by `android_app != 'false'`, so an empty output from a failed detector runs the full emulator suite rather than passing vacuously. Never convert it back to a job-level `if`, keep `timeout-minutes`, and keep `ci-gate` depending on it. Do not mark it required from this repository's files; that is an admin task. Before making any job required, confirm it can never be skipped.

CI speed and gating (Issue #156): Gradle's build cache is on (`org.gradle.caching=true`), and a superseded pull-request run is canceled while runs on `main` keep queuing. The `ci-gate` job depends on every gating job, always reports, and passes only when each job succeeded or was legitimately skipped; the branch ruleset should require `ci-gate` instead of matrix job names such as `Android instrumentation (API 36)`. Any test that reads files from disk (for example `src/main`) must declare them as Gradle test inputs, as `app/build.gradle.kts` does for `src/main`, or the build cache can reuse a stale passing result. `tools/tests/test_ci_speed_contract.py` pins all of this.

Docs checks are deliberately minimal and are not a Markdown style linter. `tools/check_markdown.py` blocks only on rendering breaks (an unclosed inline link, or a destination split across a newline) and on relative links or images pointing at a file that does not exist. The `docs-links` job checks external URLs with lychee and stays `continue-on-error` because the network is not a build dependency.

The injected WebUI JavaScript in `webui/HermesWebUiScripts.kt` (and the raw-string block in `MainActivity.kt`) is invisible to kotlinc and Android Lint, so a syntax error or runtime-only mistake there ships green and bricks WebUI rendering on device. `tools/extract_webui_scripts.py` pulls each Kotlin raw string out into a standalone `.js` file — padded so JavaScript line numbers match the Kotlin source, with Kotlin string templates replaced by a placeholder literal — and CI runs `node --check` plus `eslint.runtime-guard.config.mjs` over the result. That ESLint config is deliberately not a style linter: it enables only rules that catch code which parses but throws at runtime. Add a Kotlin file to `SOURCE_FILES` in the extractor when it starts carrying injected script text, and add genuinely WebUI-owned page globals to `webUiPageGlobals` rather than disabling `no-undef`.

Run the same guards locally with:

```powershell
python tools/extract_webui_scripts.py
Get-ChildItem build/webui-scripts/*.js | ForEach-Object { node --check $_.FullName }
npm install --no-save eslint@^10
npx eslint --no-config-lookup -c eslint.runtime-guard.config.mjs "build/webui-scripts/**/*.js"
python tools/check_markdown.py
```

## Verification

Run from the repository root:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat testDebugUnitTest --no-daemon
.\gradlew.bat lintDebug --no-daemon
.\gradlew.bat assembleDebug --no-daemon
.\gradlew.bat verifyFossDependencies assembleRelease --no-daemon   # F-Droid build: Play-free, unsigned without a keystore
```

For a local signed release build:

```powershell
.\gradlew.bat stageGithubReleaseApk --no-daemon   # builds signed github APK into build/release/
.\gradlew.bat printReleaseVersionName --no-daemon  # prints current versionName for automation
```

For docs-only changes, Gradle verification may be skipped if the final response
states that no code was changed.

Optional device check:

```powershell
.\gradlew.bat connectedDebugAndroidTest --no-daemon
```

## QA and regression testing

Bugs in this app are usually a Hermes WebUI compatibility regression that only shows on a device, so add the cheapest test that would have caught it:

- Source rules that live in this file (no SSL bypass, no dashboard-config writes, no long-click interception, required permissions) are enforced by `WebViewSecurityContractTest`. Change the rule and the test together.
- Injected-script behavior belongs in an instrumentation fixture (`HermesWebUiCompatibilityTest` helpers), not only in `contains(...)` string assertions.
- Changes to injected WebUI JavaScript or a WebUI shim should also be checked against a real server with `tools/qa` (see `tools/qa/README.md`). Paste the redacted `report.md` summary in the PR.
- The approval and clarify prompt cards are covered by `promptCards_*` tests in `HermesWebUiCompatibilityTest`, which load a reduced snapshot of the real WebUI markup from `app/src/androidTest/assets/webui-snapshot/` (see its `SNAPSHOT.md` for the source version and refresh steps). Refresh the snapshot when upstream markup changes; `tools/qa` scenarios `s03` and `s04` detect that drift on a real server.
- **Never commit a server URL, hostname or credential.** The live-QA server URL lives only in the untracked `tools/qa/.env.qa` or the `HERMES_QA_SERVER_URL` variable; reports redact it. `tools/tests/test_qa_harness.py` fails on a tracked tailnet hostname.
- The server must be signed in manually; agents do not enter or store passwords.

### QA workflow summary (Issue #150)

QA hardening runs in layers; use the cheapest one that would have caught the bug:

1. JVM/tool contract guards (`WebViewSecurityContractTest`, `tools/tests/*`) run in the required `unit-tests` and `release-tooling-tests` jobs.
2. Emulator fixtures (`HermesWebUiCompatibilityTest`, `ProxyAuthRedirectInstrumentationTest`, prompt-card hit-testing against `webui-snapshot/`) run in `android-ui-smoke` on API 35 and 36 whenever Android source or build inputs change.
3. `tools/qa` is a local-only live-server harness; the server URL stays in the untracked `tools/qa/.env.qa`.
4. Every new guard or test must be shown to fail when its rule is deliberately broken, then restored.

There is no instrumented release-variant smoke in CI. The `release` build type is minified and unsigned without keystore credentials, so it is not installable on an emulator; instrumenting it would need a `testBuildType` change plus a debug-signing fallback in `app/build.gradle.kts`, which touches F-Droid and release signing identity. The `debug-build` job already runs `verifyFossDependencies compilePlayKotlin assembleRelease` to prove the exact unsigned F-Droid build compiles through R8 without Play libraries, and the signed release workflow verifies APK/AAB signatures. Do not add a release smoke that needs signing secrets or a non-FOSS dependency. Pull requests use `.github/pull_request_template.md`; keep its checklist aligned with these gates.

## Git identity and workflow

Use the Paladin173 GitHub noreply identity for commits in this repo:

```text
Paladin173 <35980893+Paladin173@users.noreply.github.com>
```

Before committing, verify:

```powershell
git config user.name
git config user.email
git status --short --branch
```

For F-Droid submissions, follow the one-time setup and public-identity checks
in `RELEASE.md`. Use a public handle and the hosting provider's private/noreply
email for new commits, including both author and committer. Verify GitLab's
display name and commit-email defaults before browser/API-created commits;
local Git identity does not control those defaults. Keep personal names,
personal emails, machine paths, credentials, and authorization codes out of
new public content. Preserve upstream attribution and do not rewrite history.
If the hosted identity cannot be verified, stop before publishing.

Keep unrelated local changes out of commits. If a file is already modified and
is not part of the current task, leave it unstaged and call it out in the final
summary.

Branch hygiene after merges:

- After a feature or fix branch is merged into `main`, clean it up locally and on GitHub when deletion is safe. Verify either that Git proves the branch is an ancestor of current `origin/main`, or that GitHub reports the pull request merged and the branch still matches that pull request's exact head ref and SHA. The latter is required for squash merges, whose head commits are not ancestors of `main`.
- Preserve branches attached to another worktree, advanced beyond the verified pull-request head, carrying unmerged work, or otherwise uncertain unless the human explicitly approves deletion.
- GitHub's automatic merged-branch deletion setting is optional, not a repository requirement. Lack of Admin access to enable it does not block completion; continue best-effort manual cleanup.
- After merges land, sync local `main` with `origin/main` before starting new work or cutting another branch.
- Before creating a new working branch, confirm `main` is current with `git fetch origin` plus an ahead/behind check such as `git status --short --branch`.
- Treat a branch that is fully merged but behind `main` as stale; recreate it from current `main` instead of reviving it with extra history.

Commit subject format:

```text
<area>: <imperative summary>
```

Examples:

```text
docs: simplify README and add roadmap
A-005: add deep-link route handling
```

Use force-push only when the user has explicitly approved history rewriting or
when correcting freshly created local metadata before others have based work on
it.
