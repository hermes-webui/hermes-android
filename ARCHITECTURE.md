# Hermes-Android Architecture

## Goals

- Keep Hermes WebUI as primary UX surface for parity and maintainability.
- Treat Hermes Dashboard Terminal as a first-class secondary surface when configured.
- Add native Android affordances around security, integration, and reliability.
- Preserve clean boundaries so native enhancements can scale incrementally.

## Layered design

- `core/`: security policy primitives (URL and navigation decisions)
- `data/`: local encrypted settings and persistence concerns
- `domain/`: intent parsing and validation rules
- `ui/`: ViewModel state and Compose screens
- `MainActivity`: Android platform boundary (WebView, intents, activity contracts)

## Runtime flow

1. App starts, loads encrypted WebUI and Dashboard Terminal settings (`SettingsRepository`).
2. WebView boots with hardened configuration.
3. Android WebView compatibility shims disable forced darkening and patch Hermes WebUI root viewport height when `100dvh` collapses to `0px`.
4. `UrlPolicy` enforces HTTPS + domain allowlist for every navigation.
5. Native drawer actions switch the WebView between the configured WebUI and Terminal routes.
6. `MainViewModel` drives active surface, loading/error/offline/share UI state.
7. Share intents are parsed in `domain`, staged in ViewModel, then pushed into WebView flow.
8. Settings updates rewrite encrypted preferences and reload trusted hosts.

## Security model

- Trust boundary is the configured Hermes WebUI and Dashboard Terminal host set.
- In-app navigation remains inside trust boundary only.
- Everything else is blocked or externalized.
- Cleartext disabled at network config level.
- Sensitive app-side config is encrypted with Android Keystore-backed keys.

## Extensibility points

- Add deep-link route handling in `MainActivity.onNewIntent`.
- Add push notification handlers that map to Hermes URLs/session IDs.
- Add biometric gate before `WebShell` composable rendering.
- Add advanced native settings in `ui/settings` + `data/SettingsRepository`.
- Add more drawer destinations for stable Hermes routes such as files, kanban, sessions, or status.
