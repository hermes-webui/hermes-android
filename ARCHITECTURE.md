# Hermes-Android Architecture

## Goals

- Keep Hermes WebUI as primary UX surface for parity and maintainability.
- Treat Hermes Dashboard Terminal as a first-class secondary surface when configured.
- Add native Android affordances around security, integration, and reliability.
- Preserve clean boundaries so native enhancements can scale incrementally.

## Layered design

- `core/`: security policy primitives (URL and navigation decisions)
- `data/`: local encrypted settings, persistence, and Hermes API client
- `domain/`: intent parsing and validation rules
- `ui/`: ViewModel state and Compose screens
- `MainActivity`: Android platform boundary (WebView, intents, activity contracts)

## Runtime flow

1. App starts, loads encrypted WebUI and Dashboard Terminal settings (`SettingsRepository`).
2. WebView boots with hardened configuration.
3. Android WebView compatibility shims disable forced darkening and patch Hermes WebUI root viewport height when `100dvh` collapses to `0px`.
4. If the user has enabled "Hide WebUI menu button" in settings (default true), a DOM shim is injected to hide the WebUI's hamburger trigger, avoiding visual conflict with the native drawer.
5. `UrlPolicy` enforces HTTPS + domain allowlist for every navigation.
6. Native drawer offers WebUI routes (Chat `/`, Skills, Artifacts, Agents, Scheduler, Messaging), Tools (Terminal), and App options (App Settings, Reset session). Each WebUI route item loads `{serverUrl}{path}` and highlights based on current URL.
7. `MainViewModel` drives active surface, loading/error/offline/share UI state.
8. Share intents are parsed in `domain`, staged in ViewModel, then pushed into WebView flow.
9. Settings updates rewrite encrypted preferences and reload trusted hosts.
10. On WebView load failure, `MainViewModel` probes `{serverUrl}/api/status` (Hermes WebUI public liveness endpoint) to distinguish "server is down" from a transient content/navigation error. This refines the `isOffline` state and the copy shown to the user in `WebShell`.
11. `hermes://session/{id}` deep links are handled in `MainActivity.onNewIntent`, navigating the WebView to `{serverUrl}/{id}` — the Hermes WebUI session route contract (see `apps/desktop/src/app/routes.ts: sessionRoute()` in hermes-agent).

## Security model

- Trust boundary is the configured Hermes WebUI and Dashboard Terminal host set.
- In-app navigation remains inside trust boundary only.
- Everything else is blocked or externalized.
- Cleartext disabled at network config level.
- Sensitive app-side config is encrypted with Android Keystore-backed keys.

## Extensibility points

- Add more deep-link hosts/paths in the `hermes://` scheme (see `AndroidManifest.xml` + `handleDeepLink` in `MainActivity`).
- Add push notification handlers that map to Hermes URLs/session IDs via `hermes://session/{id}`.
- Add biometric gate before `WebShell` composable rendering.
- Add advanced native settings in `ui/settings` + `data/SettingsRepository`.
- Add more drawer destinations for stable Hermes routes such as files, kanban, sessions, or status.
- Extend `HermesApiClient` with authenticated calls (e.g. `/api/sessions`) once an API key storage strategy is decided.
