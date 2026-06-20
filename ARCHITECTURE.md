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

1. App starts, loads encrypted WebUI settings and the stored/default dashboard origin URL (`SettingsRepository`).
2. WebView boots with hardened configuration.
3. The Compose root fills the full window background, then applies `WindowInsets.safeDrawing` around the WebView shell and native snackbar so Android 15 edge-to-edge enforcement does not put content under status or navigation bars.
4. Android WebView compatibility shims stay scoped to Hermes WebUI. Android injects a document-start microphone fallback flag for the configured Hermes WebUI origin so WebUI voice input uses its MediaRecorder path instead of Android WebView's unreliable Web Speech API path. The official dashboard is not rendered in an app WebView.
5. On the Hermes WebUI route, Android seeds the WebUI `/api/dashboard/config` origin URL when WebUI has no dashboard URL and Android has one stored/defaulted. Dashboard URLs are normalized to their origin before Android stores or matches them. WebUI then owns rendering and behavior for the Official Hermes Dashboard link in its rail/sidebar.
6. Official Hermes Dashboard links are treated as secondary browser surfaces. Android handles WebView new-window requests and dashboard-origin navigations by launching a Chrome Custom Tab with title/share UI minimized, instead of replacing the primary Hermes WebUI WebView or opening the full default browser UI.
7. Dashboard-origin pages are not saved as the app startup URL. If a dashboard URL is ever the last observed WebView URL, the next launch falls back to the configured Hermes WebUI URL.
8. `UrlPolicy` enforces HTTPS + domain allowlist for every navigation.
9. WebView microphone permission requests are accepted only for allowlisted Hermes origins and only for `RESOURCE_AUDIO_CAPTURE`. Android runtime microphone permission is requested on demand before granting the WebView request.
10. `MainViewModel` drives loading/error/offline/share UI state.
11. Share intents are parsed in `domain`, staged in ViewModel, then pushed into WebView flow.
12. Settings updates rewrite encrypted preferences and reload trusted hosts. The Android setup sheet only asks for the Hermes WebUI URL; the dashboard origin is visible and editable in WebUI Settings > System after seeding.
13. On WebView load failure, `MainViewModel` probes `{serverUrl}/api/status` (Hermes WebUI public liveness endpoint) to distinguish "server is down" from a transient content/navigation error. This refines the `isOffline` state and the copy shown to the user in `WebShell`.
14. `hermes://session/{id}` deep links are handled in `MainActivity.onNewIntent`, navigating the WebView to `{serverUrl}/{id}` — the Hermes WebUI session route contract (see `apps/desktop/src/app/routes.ts: sessionRoute()` in hermes-agent).

## Security model

- Trust boundary is the configured Hermes WebUI and dashboard URL host set.
- In-app navigation remains inside trust boundary only.
- Everything else is blocked or externalized.
- The official dashboard is browser-rendered through Custom Tabs so Chrome handles dashboard compatibility, cookies, TLS, and same-origin navigation.
- Microphone access requires Android `RECORD_AUDIO` and an allowlisted WebView origin; video/camera and unknown WebView resources are denied. Android also seeds WebUI's `mic_force_mediarecorder` localStorage flag at document start for the configured WebUI origin only, avoiding Web Speech API false-denied errors in Android WebView.
- Cleartext disabled at network config level.
- Sensitive app-side config is encrypted with Android Keystore-backed keys.

## Extensibility points

- Add more deep-link hosts/paths in the `hermes://` scheme (see `AndroidManifest.xml` + `handleDeepLink` in `MainActivity`).
- Add push notification handlers that map to Hermes URLs/session IDs via `hermes://session/{id}`.
- Add biometric gate before `WebShell` composable rendering.
- Add advanced native settings in `ui/settings` + `data/SettingsRepository`.
- Prefer WebUI-owned navigation/settings for dashboard links; open the official dashboard through Custom Tabs rather than an app-owned dashboard WebView unless there is a tested reason to revisit the WebView path.
- Extend `HermesApiClient` with authenticated calls (e.g. `/api/sessions`) once an API key storage strategy is decided.
