# Hermes-Android Architecture

## Goals

- Keep Hermes WebUI as primary UX surface for parity and maintainability.
- Treat the official Hermes Dashboard link as a WebUI-owned secondary surface.
- Add native Android affordances around security, integration, and reliability.
- Preserve clean boundaries so native enhancements can scale incrementally.

## Layered design

- `core/`: security policy primitives (URL and navigation decisions)
- `data/`: local encrypted settings, persistence, and Hermes API client
- `domain/`: intent parsing and validation rules
- `ui/`: ViewModel state and Compose screens
- `MainActivity`: Android platform boundary (WebView host, intents, activity contract, Custom Tabs dashboard handoff)

## Runtime flow

1. App starts and loads encrypted WebUI settings (`SettingsRepository`). The bundled dashboard origin default is blank so WebUI owns dashboard auto-detect and persistence.
2. WebView boots with hardened configuration, default HTTP cache behavior, DOM storage, and service-worker cache settings for WebUI-managed assets.
3. The Compose root fills the full window background, then applies `WindowInsets.safeDrawing` around the WebView shell and native snackbar so Android 15 edge-to-edge enforcement does not put content under status or navigation bars.
4. Android WebView compatibility shims stay scoped to Hermes WebUI. Android disables native long-press handling (`isLongClickable = false`, `setOnLongClickListener { true }`) so Hermes WebUI's own touch long-press timer drives its action menus, and re-caps the WebUI floating-menu `max-height` plus the update-summary panel `#updateSummaryPanel` (`max-height: min(34vh, 260px)` in WebUI) with the measured viewport height because Android WebView evaluates those CSS viewport units as `0` (the same viewport-unit quirk it applies to `100dvh`), which would otherwise collapse the panels to an unreadable sliver. That viewport shim keeps only horizontal overflow locked; vertical page scrolling must remain available so generated update summaries and other expandable WebUI content do not get clipped inside the WebView. Android also injects a document-start microphone fallback so WebUI voice input uses its MediaRecorder path instead of Web Speech API. The official dashboard is not rendered in an app WebView.
5. On the Hermes WebUI route, Android does not write `/api/dashboard/config` or overwrite WebUI's Official Hermes Dashboard setting. WebUI owns dashboard auto-detect, persistence, rendering, and behavior for the dashboard link in its rail/sidebar.
6. Official Hermes Dashboard links are treated as secondary browser surfaces. When Android has an explicitly configured local dashboard origin, it handles matching WebView new-window requests and dashboard-origin navigations by launching a Chrome Custom Tab with title/share UI minimized, instead of replacing the primary Hermes WebUI WebView.
7. Dashboard-origin pages are not saved as the app startup URL. For Hermes WebUI routes, Android persists both full page loads and client-side history updates (WebView visited-history callbacks). On the configured WebUI origin, Android also installs a narrow workspace-button recovery shim: when the workspace toggle is tapped from a blank root route and the panel still remains hidden, Android redirects to the last known trusted in-app session route so WebUI can rehydrate workspace state. If a dashboard URL is ever the last observed WebView URL, the next launch falls back to the configured Hermes WebUI URL.
8. `UrlPolicy` enforces supported web schemes (`http`/`https`) + domain allowlist for every navigation.
9. WebView microphone permission requests are accepted only for audio capture on trusted Hermes pages. Android prefers explicit allowlisted HTTP/HTTPS origins, normalizes edge-case origin formatting, and allows null/opaque-origin requests only when the active main-frame URL is the configured Hermes WebUI route. Android runtime microphone permission is requested on demand before granting the WebView request.
10. WebUI browser notifications are backed by Android notifications. Android injects a scoped `Notification`/`ServiceWorkerRegistration.showNotification` compatibility facade for the configured Hermes WebUI origin, requests `POST_NOTIFICATIONS` on Android 13+, publishes through a native notification channel, and accepts notification tap routes only when they target an allowlisted Hermes WebUI URL.
11. `MainViewModel` drives loading/error/offline/share UI state. After the first successful page render, reloads and navigations keep the existing WebView content visible so cached pages do not flicker behind a full-screen native loading veil.
12. Share intents are parsed in `domain`, staged in ViewModel, then pushed into WebView flow.
13. Settings updates rewrite encrypted preferences and reload trusted hosts. The Android setup sheet only asks for the Hermes WebUI URL; the dashboard origin is visible and editable in WebUI Settings > System.
14. On WebView load failure, `MainViewModel` probes `{serverUrl}/api/status` (Hermes WebUI public liveness endpoint) to distinguish "server is down" from a transient content/navigation error. This refines the `isOffline` state and the copy shown to the user in `WebShell`.
15. `hermes://session/{id}` deep links are handled in `MainActivity.onNewIntent`, navigating the WebView to `{serverUrl}/{id}` — the Hermes WebUI session route contract (see `apps/desktop/src/app/routes.ts: sessionRoute()` in hermes-agent).
16. An "Application Settings" entry point is injected into the Hermes WebUI sidebar below Help via a document-start WebView shim. This shim clones the Help menu item structure, strips routing attributes, injects a custom phone-outline SVG icon, and binds the click handler to the `hermes://app/settings` deep link, which triggers `MainViewModel.openSettings()` to display the native settings bottom sheet. This keeps native-owned settings (such as multi-server profiles) accessible from within the WebUI without adding a custom native drawer or menu override.

## Build and release flow

- Local signed release builds load upload-key credentials from an untracked repo-root `keystore.properties` file.
- CI signed release builds load the same values from `ANDROID_KEYSTORE_*` environment variables, with the keystore file decoded from the `ANDROID_KEYSTORE_BASE64` GitHub Actions secret.
- The signed release workflow uses Node 24-compatible GitHub Actions majors to avoid deprecated Node 20 runner execution.
- `:app:assembleRelease`, `:app:stageGithubReleaseApk`, and `:app:stageReleaseArtifacts` fail fast when signing credentials are missing or the keystore file path is invalid, preventing unsigned distribution artifacts from being staged as release-ready output.
- `:app:stageGithubReleaseApk` builds the signed `github` build type and copies it into the ignored root `build/release/` output folder as `hermes-webui-v<version>-github.apk` so generated binaries do not live beside source files.
- The release workflow (`.github/workflows/1-orchestration-release.yml`) builds both signed artifacts in one run: the `github` APK and the official Play AAB. The GitHub build type uses `applicationIdSuffix = ".github"` and `versionNameSuffix = "-github"`, so it installs beside the Play build as `com.hermeswebui.android.github` and reports a channel-specific version such as `0.1.8-github`.
- Manual release runs create or update release `v<versionName>` from the checked-out commit; tag-triggered releases require the tag to match the Gradle Android `versionName` exactly, such as `v0.1.8`, before upload.
- After artifacts are uploaded, `.github/workflows/1-orchestration-release.yml` fans out to reusable publish workflows in parallel: `.github/workflows/2-publish-github-apk.yml` publishes only `hermes-webui-v<version>-github.apk` to GitHub Releases, and `.github/workflows/3-publish-play-store-release.yml` submits only `hermes-webui-v<version>.aab` to the Google Play internal testing track with the configured Play service account.
- GitHub APK publishing writes full generated GitHub release notes after the build metadata block. Play publishing generates a short `whatsnew-en-US` changelog from the same GitHub release notes and passes it to the Play upload action as `whatsNewDirectory`.
- Release workflows use concurrency groups so duplicate runs for the same ref or target version do not publish over each other.
- The build and publish workflows validate that exactly one matching APK or AAB exists before upload or publication.
- The publish workflows also support manual dispatch with the build run ID and artifact metadata so a failed GitHub or Play publish can be retried without rebuilding both release artifacts.

## Security model

- Trust boundary is the configured Hermes WebUI and dashboard URL host set.
- In-app navigation remains inside trust boundary only.
- Everything else is blocked or externalized.
- The official dashboard is browser-rendered through Custom Tabs so Chrome handles dashboard compatibility, cookies, transport behavior, and same-origin navigation.
- Microphone access requires Android `RECORD_AUDIO` plus `MODIFY_AUDIO_SETTINGS` and a trusted WebView context (allowlisted origin, with a null/opaque-origin fallback only while the active main frame is the configured Hermes WebUI route). Android grants only `RESOURCE_AUDIO_CAPTURE`; camera/video capture remains denied. Android also seeds WebUI's `mic_force_mediarecorder` localStorage flag at document start for the configured WebUI origin only, avoiding Web Speech API false-denied errors in Android WebView.
- Hermes WebUI drives its own conversation long-press menus from a touch timer (e.g. `static/sessions.js`, ~400ms). Android only disables the native long-press path (`isLongClickable = false`, `setOnLongClickListener { true }`) so that timer is not preempted; it does not synthesize `contextmenu` or interfere with touch events. The menus themselves are positioned `fixed` and capped with `max-height: calc(100vh - 16px)`; because Android WebView evaluates CSS `100vh` as `0`, that cap collapses the menu to a ~2px sliver. The viewport shim therefore re-caps `.session-action-menu`/`.workspace-prefs-menu` `max-height` to the measured viewport height (recomputed on resize/orientation change), restoring full-size, scrollable menus with their native entrance animation intact, while leaving vertical page scrolling available for expandable WebUI panels such as generated update summaries.
- Browser notification access requires Android app notification permission and a trusted WebView context. The bridge uses AndroidX WebMessageListener rather than a secret-bearing JavaScript interface, rejects subframes and non-WebUI origins, limits payload handling to notification title/body/tag/target URL, and validates notification tap URLs against the same allowlist before loading them.
- Android cleartext traffic is permitted so configured HTTP deployments can load. App-level URL policy still limits trusted in-app navigation and downloads to the configured host allowlist.
- WebView uses browser-managed HTTP and service-worker cache semantics. Android does not maintain a separate stale copy of Hermes WebUI HTML or authenticated API responses.
- Sensitive app-side config is encrypted with Android Keystore-backed keys.

## Extensibility points

- Add more deep-link hosts/paths in the `hermes://` scheme (see `AndroidManifest.xml` + `handleDeepLink` in `MainActivity`).
- FCM push notification handlers can map server-originated events to allowlisted Hermes URLs/session IDs.
- Add biometric gate before `WebShell` composable rendering.
- Add advanced native settings in `ui/settings` + `data/SettingsRepository`.
- **Multi-server profile support** (Issue #20, in progress): Phase 1 complete (native "Application Settings" entry point injected into WebUI sidebar). Phase 2 will add encrypted multi-server profile storage in `SettingsRepository` with versioned migrations, and extend `SettingsBottomSheet` with profile list, add/edit/delete dialogs, and active-server selector. Phase 3 will implement server-switching logic: validate new server against `UrlPolicy` allowlist, reload WebView, clear cookies and session data from prior server, and persist new active server choice. This keeps multi-server support entirely native (app-owned) and does not modify WebUI UI/behavior.
- Prefer WebUI-owned navigation/settings for dashboard links; do not write WebUI dashboard config from Android. Open explicitly configured dashboard origins through Custom Tabs rather than an app-owned dashboard WebView unless there is a tested reason to revisit the WebView path.
- Extend `HermesApiClient` with authenticated calls (e.g. `/api/sessions`) once an API key storage strategy is decided.
