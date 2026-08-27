# Hermes-Android Roadmap

> Maintenance-focused Android wrapper for Hermes Web UI. The core wrapper is
> good as-is; product UI and workflow changes belong in Hermes WebUI.
>
> Last updated: 2026-08-13

---

## Status snapshot

| Surface | Status |
|---|---|
| Secure WebView shell | Done - HTTP/HTTPS navigation, host allowlist, hardened defaults |
| WebUI integration | Done - first-run WebUI URL setting, WebUI-owned dashboard config, session persistence, pull-to-refresh |
| WebView compatibility | Done - disables forced darkening, patches Android viewport-unit collapse, respects system-bar safe insets, uses browser-managed cache defaults, smooths reload rendering, restores touch-and-hold context-menu dispatch for conversation actions, and forces WebUI microphone input onto the Android-compatible MediaRecorder path |
| Official dashboard link | Done - Android no longer writes WebUI's Official Hermes Dashboard config, opens explicitly configured dashboard-origin requests in a Chrome Custom Tab with minimal browser UI, and avoids persisting dashboard pages as startup state |
| Android sharing | Done - share-to-app intake for text and files |
| Files | Done - WebView upload/download integration |
| Microphone | Done - allowlisted WebView audio capture with Android runtime permission plus WebUI MediaRecorder fallback |
| Local settings | Done - encrypted settings storage |
| Native navigation | Done - WebUI-owned dashboard link integration and deep links |
| Server health probing | Done - `/api/status` probe to distinguish server-down from content errors |
| Browser notifications | Done - WebUI Notification API bridge, Android runtime permission, notification channel, and trusted WebUI tap routing |
| App update alerts | Done - shared settings/notification UX with build-selected Google Play or GitHub Releases update providers; Play shows "Update now" in-app update flow, GitHub uses stateful `Check -> Download -> Install` actions |
| Native distribution polish | Done - app identity and signed GitHub APK plus Play AAB release automation are wired for local builds plus GitHub Actions |
| Google Play Production | Done - approved for production release; shipping as v1.0.0 |
| Maintenance posture | Stable - accept Android-wrapper fixes, compatibility updates, dependency updates, and release maintenance |
| Native feature expansion | Deferred - revisit only for Android-specific needs with a clear WebUI/API boundary |

---

## Feature checklist

### MVP shell

- [x] Secure WebView opens a configured Hermes WebUI URL
- [x] First-run WebUI URL prompt and settings surface
- [x] HTTP/HTTPS URL validation
- [x] Host allowlist for in-app navigation
- [x] External handoff for non-allowlisted HTTP/HTTPS links
- [x] Cleartext traffic permitted for configured HTTP deployments
- [x] Back handling and WebView history behavior
- [x] Pull-to-refresh
- [x] Default WebView HTTP/service-worker cache behavior
- [x] Loading, error, and offline states
- [x] Cookie-backed session persistence
- [x] Encrypted local settings

### Android integration

- [x] File upload support
- [x] File download support
- [x] Microphone capture support for WebUI voice input
- [x] Browser notification permission and delivery bridge for WebUI alerts
- [x] Share-to-app intake for text
- [x] Share-to-app intake for files
- [x] Native launcher identity
- [x] Splash and app theme
- [x] WebUI-owned Official Hermes Dashboard setting
- [x] Official dashboard link route
- [x] Deep links (`hermes://session/{id}`)
- [x] Server health probing
- [x] Camera capture in file chooser
- [ ] Direct share-file auto-attach flow
- [ ] Attachment progress and retry UX

### Deferred Android-only ideas

These are not active priorities. Revisit only if a specific Android platform
need justifies native work. WebUI layout, styling, animations, and product
workflow changes should be made in Hermes WebUI instead.

- [x] Deep links and verified app links to Hermes routes
- [x] Server health probing to refine offline/error states
- [x] Server profile list for multiple Hermes hosts with encrypted storage, profile CRUD, readiness validation, and session-clearing profile switches
- [ ] Optional biometric app lock before showing WebView
- [ ] FCM push notification plumbing
- [x] Notification channel strategy
- [x] Notification click routing to allowlisted WebUI routes
- [ ] Expanded native settings for theme, notifications, and profiles
- [ ] Optional native sessions list (requires authenticated API access)
- [ ] WebUI menu shortcuts for files, kanban, and status if needed
- [~] Instrumentation tests run in CI/releases and cover Settings, WebShell recovery, and WebView compatibility; deep-link and intent-routing coverage remains open
- [ ] Evaluate a Trusted Web Activity (TWA) variant rendered in real Chrome, gated on Hermes WebUI serving `/.well-known/assetlinks.json` (draft + fingerprint in `assets/twa/`); accept loss of native bridges and HTTPS-only verification before pursuing
- [x] Final package/application ID decision before first public release
- [x] Release signing automation docs and snippets
- [~] Background continuity while app is backgrounded (Issue 10): Part A is complete; Part B ongoing activity notification and initial Part C tray approvals are implemented. Remaining work is focused on B4 lifecycle/manual validation and cross-client SSE/API contract hardening.

### Native improvement proposals (2026-07-02, post code-review)

Wishlist from a line-by-line committee review. Full rationale and implementation
sketches are captured inline below.

- [ ] Decide and document the cleartext posture (A1, security): runtime-configured hosts cannot be injected into static `network_security_config`; retain global HTTP compatibility or add an explicit HTTPS-only mode
- [ ] Optional TLS/certificate pinning for the configured host (A2, security)
- [x] Optional `FLAG_SECURE` + hide content in the app switcher (A3, privacy)
- [ ] Biometric app-lock before `WebShell` with idle timeout (A4, security — concrete plan for the existing deferred idea)
- [~] Close the residual in-app OAuth phishing surface (A5, security): the in-flow host chip and HTTPS downgrade rejection are implemented; a configurable trusted-IdP allowlist remains a product decision
- [ ] Native "sign out & wipe" action for shared devices (A6): the duplicate partial reset was removed from native Settings; WebUI owns normal sign-out, while Android system **Clear storage** remains the complete local wipe
- [~] Instrumentation tests (deep links, exported download host, allowlist) wired into CI (B1): all current instrumentation classes now run for Android changes and releases; the listed navigation/security cases remain to be added
- [x] Unit tests covering the committee fixes: update-APK host allowlist, gateway `enabled` absent/false, profile `isActive` derivation (B2)
- [~] detekt/ktlint + Android Lint gate in CI (B3): Android Lint is required; detekt/ktlint remain open
- [ ] App shortcuts for Settings + server switch (C1)
- [ ] Direct Share targets to recent sessions (C2)
- [ ] Predictive-back + per-app language polish (C3)

---

## Maintenance work

| ID | Priority | Status | Area | Task | Notes |
|---|---|---|---|---|---|
| M-001 | As needed | Open | Platform | Keep Android, Gradle, Kotlin, and dependency compatibility current | Wrapper stability and Play distribution maintenance |
| M-002 | As needed | Open | Security | Keep WebView, URL policy, permissions, and encrypted settings behavior hardened | Preserve HTTP/HTTPS configured-host support and host allowlist enforcement |
| M-003 | As needed | Open | Bugfix | Fix Android-wrapper regressions | Scope to WebView hosting, permissions, share/download, notifications, deep links, settings, and release flow |
| M-004 | As needed | Open | Release | Keep signed release automation current | Maintain alignment between Gradle metadata, `keystore.properties.example`, and GitHub Actions secrets |
| M-005 | High | In progress | Platform | Triage and stage Issue 10 background-execution work (A/B/C phases) | Part A is complete, Part B ongoing activity updates are implemented (with reconnect using `/api/sessions/events` plus polling fallback), and initial Part C tray approvals are implemented with queue-head validation through `/api/approval/pending` before `/api/approval/respond`; remaining scope is B4 lifecycle/manual validation plus broader cross-client payload/API contract hardening |
| UX-002 | Medium | Done | Settings | Server health check before switching | Tapping a saved non-current server now probes readiness first, shows reachable/auth-required/setup/offline/non-Hermes status, asks for confirmation before switching, blocks unsafe switches by default, and records safe diagnostic breadcrumbs for the check result |
| A-020-P2 | Medium | Done | Settings | Multi-server profile storage (Issue #20 Phase 2) | Encrypted profile persistence, versioned migration, profile CRUD, and active-server selection are implemented. |
| A-020-P3 | Medium | Done | Navigation | Multi-server profile switching (Issue #20 Phase 3) | Switching validates readiness, clears prior WebView session state, rebuilds the active allowlist, and reloads the selected server without changing WebUI-owned dashboard config; broader instrumentation coverage remains under B1. |

---

## Completed work

| ID | Date | Area | Summary |
|---|---|---|---|
| TEST-005 | 2026-08-26 | CI / Documentation | Added documentation checks for Markdown rendering breaks, dead in-repo links, and external URLs, and gave documentation-only pull requests a fail-safe fast path that skips the Gradle jobs while keeping README release-metadata assertions running. |
| TEST-004 | 2026-08-26 | CI / Testing | Split PR CI into per-check jobs (release tooling, unit tests, Android Lint, debug APK) so a failure names the gate that broke, added job timeouts, and introduced syntax plus ESLint runtime-error gates for the JavaScript Android injects into the WebUI WebView. |
| REL-028 | 2026-08-26 | Release / CI | Reworked release orchestration to build immutable reviewed versions, pin external actions, generate linked GitHub/Play changelogs once, validate retry metadata against the originating run, and verify APK/AAB signatures before publishing. |
| TEST-003 | 2026-08-26 | CI / Testing | Expanded QA with release-tool/workflow contract tests, Android API 35/36 instrumentation gates, share/deep-link/manifest/notification contracts, duplicate-profile rules, and deterministic GitHub update parsing. |
| BUG-048 | 2026-08-26 | Settings | Prevented profile edits from creating duplicate server names or normalized URLs, matching the existing add-server invariant. |
| CLEANUP-005 | 2026-08-26 | Settings / Maintenance | Reorganized native Settings into task-based Servers, Application, Updates, Connection, Privacy, Troubleshooting, Advanced, and About sections; moved PKCS#12 client certificates into an Advanced detail dialog; removed the duplicate partial session reset and its unreachable APIs; and deleted superseded Issue 43 planning files. |
| BUG-047 | 2026-08-26 | Security | Made malformed or keyless PKCS#12 client-certificate files fail closed instead of throwing through the WebView callback, selected the first usable private-key entry, and removed the unsupported PEM claim from Settings. |
| TEST-002 | 2026-08-26 | CI / Testing | Made Android Lint a required CI gate, expanded PR instrumentation triggers to every Android source/build change, and removed hand-maintained class filters so all current and future instrumentation tests run before merge and release. |
| SEC-003 | 2026-08-26 | Security | Centralized WebView route/source/target/permission decisions in a unit-tested `WebTrustPolicy` and preserved public-IP OAuth scheme upgrades without allowing HTTPS callbacks to downgrade to HTTP. |
| BUG-046 | 2026-08-26 | WebView | Hardened Issue #92 dialog compatibility: initial autofocus is suppressed per Clarify request (including visible in-place replacements), validation refocus plus real touch/Tab input remain available, prompt geometry is shifted and capped inside the keyboard-constrained visual viewport, generic collapse repair preserves original overflow, and attached-WebView behavior tests now gate PRs and releases. |
| BUG-045 | 2026-08-26 | WebView | Fixed Issue #90 by narrowing dialog keyboard suppression to programmatic autofocus, allowing explicit taps to focus and type into editable modal fields such as the workspace new-folder name input. |
| SSE-001 | 2026-08-09 | Background continuity | Clarified the native SSE settings and notification status: Android keeps its persistent authenticated `/api/session/stream` preference when optional gateway extras are unavailable, presents that distinction clearly, and logs the safe active transport state for diagnostics. |
| A-001 | 2026-06-19 | Build | Fixed Java/Gradle setup and verified `test` plus `assembleDebug` |
| A-002 | 2026-06-19 | Security | Added URL policy validation and tests |
| A-003 | 2026-06-19 | Tooling | Aligned AGP/Gradle to avoid Gradle 10 deprecation pressure |
| A-004 | 2026-06-19 | UI | Migrated deprecated accompanist swipe refresh to Compose pull refresh |
| A-012 | 2026-06-20 | Navigation | Superseded native drawer experiment for Dashboard Terminal route |
| DOC-001 | 2026-06-20 | Docs | Cleaned README and created this roadmap as the progress and wishlist tracker |
| BRAND-001 | 2026-06-20 | Branding | Renamed APK output to `hermes-android`; replaced placeholder icon with Hermes WebUI caduceus (vector + density PNGs); icon background aligned to WebUI dark `#1a1a1a` |
| COMPAT-001 | 2026-06-20 | Android compatibility | Guarded share-intent parcelable parsing across pre- and post-Android 13 APIs |
| A-014 | 2026-06-20 | Release | Finalized package ID and namespace as `com.hermeswebui.android` before first public release |
| A-005 | 2026-06-20 | Deep links | Added `hermes://session/{id}` intent filter; navigates WebView to `{serverUrl}/{id}` per WebUI route contract |
| API-001 | 2026-06-20 | API integration | Added `HermesApiClient` probing `/api/status` (public endpoint) on WebView errors to distinguish server-down from content errors |
| NAV-001 | 2026-06-20 | Navigation | Reworked native drawer with WebUI route sections (Chat, Skills, Artifacts, Agents, Scheduler, Messaging); replaced floating button with compact hamburger-in-card trigger |
| NAV-002 | 2026-06-20 | UI integration | Added hamburger-hiding DOM shim + user toggle to avoid visual conflict between native drawer and WebUI menu button; gracefully degrades if WebUI markup changes |
| NAV-003 | 2026-06-20 | Navigation | Removed the temporary native drawer and menu-hiding shim; seeded WebUI's Official Hermes Dashboard config instead of adding a custom Android Terminal button |
| BUG-001 | 2026-06-20 | UI | Fixed unreadable text by applying an explicit native color scheme and disabling WebView algorithmic darkening |
| BUG-002 | 2026-06-20 | WebView | Fixed Hermes WebUI text/content visibility by injecting a measured viewport-height shim when Android WebView computes `100dvh` as `0px` |
| BUG-003 | 2026-06-20 | UI | Added safe-drawing system insets so WebView content and native controls do not overlap status or navigation bars |
| BUG-004 | 2026-06-20 | Navigation | Fixed dashboard redirect/blue-screen recovery by normalizing stored dashboard URLs to their origin, opening dashboard-origin new-window requests in Chrome Custom Tabs, and preventing dashboard pages from becoming app startup state |
| BUG-005 | 2026-06-20 | Permissions | Fixed Android WebView dictation false-denied behavior by normalizing permission-request origins and allowing trusted main-frame fallback for null/opaque origins while still granting audio capture only |
| BUG-006 | 2026-06-20 | Permissions | Added Android `MODIFY_AUDIO_SETTINGS` permission because WebView Chromium microphone capture on emulator/device can fail device selection without it even when `RECORD_AUDIO` is granted |
| CLEANUP-001 | 2026-06-20 | Cleanup | Removed temporary microphone diagnostic logging/hooks from `MainActivity` after validation and kept only production microphone compatibility handling |
| CLEANUP-002 | 2026-06-20 | Resources | Replaced environment-specific default endpoint strings with placeholder HTTPS origins, removed unused `strings`/`colors` resources, and merged launcher XML resources out of unnecessary `mipmap-anydpi-v26` |
| SEC-001 | 2026-06-20 | Platform | Added Android 12+ `data_extraction_rules` configuration and wired it in `AndroidManifest.xml` while preserving `allowBackup=false` |
| BUILD-002 | 2026-06-20 | Tooling | Upgraded Gradle wrapper to 9.6.0, Kotlin to 2.4.0, AndroidX/Material dependencies to latest stable set, and moved app compile/target SDK to 37; lint now reports no issues |
| REL-001 | 2026-06-20 | Release | Updated Android app version metadata to `0.1.1` and incremented `versionCode` for the `v0.1.1` release |
| BUILD-001 | 2026-06-20 | Tooling | Migrated AGP config to built-in Kotlin, removed legacy compatibility flags, and eliminated obsolete variant API plus dependency-constraints sync warnings |
| PERM-001 | 2026-06-20 | Permissions | Added Android `RECORD_AUDIO` plus an allowlisted WebView audio-capture permission bridge so WebUI microphone input can prompt and grant correctly |
| PERM-002 | 2026-06-20 | Permissions | Added a document-start WebUI microphone fallback flag for the configured Hermes origin so Android WebView skips the unreliable Web Speech API path and uses MediaRecorder/getUserMedia |
| SEC-002 | 2026-06-20 | Security | Relaxed URL policy to allow configured HTTP or HTTPS Hermes hosts while retaining host allowlist checks and non-web scheme blocking |
| UX-001 | 2026-06-20 | Settings | Changed the first-run server URL sample from prefilled text to placeholder text that disappears on focus |
| REL-002 | 2026-06-20 | Release | Renamed app to "Hermes WebUI" (Play Store branding), updated version to `0.1.2`, and built `hermes-android-v0.1.2-pre-release.apk` for GitHub release and device testing |
| NOTIF-001 | 2026-06-21 | Notifications | Added Android-backed WebUI browser notifications with `POST_NOTIFICATIONS`, a native channel, a scoped WebView Notification API bridge, service-worker notification fallback, and allowlisted notification tap routing |
| REL-003 | 2026-06-21 | Release | Updated Android app version metadata to `0.1.3-pre-release` with `versionCode` 4 for the next pre-release build |
| REL-004 | 2026-06-21 | Release | Changed distribution artifact staging to use `hermes-webui-v<version>.apk` for GitHub and `hermes-webui-v<version>.aab` for Google Play instead of repository-name filenames |
| A-011 | 2026-06-21 | Release | Added local `keystore.properties` plus GitHub Actions secret-based signing so release APK/AAB builds fail fast unless they are signed and ready for distribution |
| CLEANUP-003 | 2026-06-21 | Build | Moved staged release artifacts from root `release/` into ignored `build/release/` and ignored legacy root release outputs |
| REL-005 | 2026-06-21 | Release | Updated signed release workflow actions to Node 24-compatible majors to avoid GitHub Actions Node 20 deprecation warnings |
| REL-006 | 2026-06-21 | Release | Incremented Android app version to `0.1.4-pre-release` with `versionCode` 5 for the long-press menu fix validation build |
| PERF-001 | 2026-06-21 | WebView | Made WebView and service-worker cache defaults explicit, advertised the real app version in the user agent, and kept rendered content visible during reloads after the first successful page load |
| CLEANUP-004 | 2026-06-21 | Cleanup | Removed stale in-code phase-2 TODOs already tracked in the roadmap, dropped unused Compose test catalog/debug references, and restored `keystore.properties.example` for documented signing setup |
| BUG-007 | 2026-06-21 | WebView | Added a Hermes-origin-scoped touch-and-hold compatibility shim that dispatches `contextmenu` so conversation long-press action menus appear in Android WebView like mobile browsers |
| BUG-008 | 2026-06-21 | WebView | Fixed invisible conversation long-press menus (Issue 6): Android WebView evaluates CSS `100vh` as 0, collapsing the WebUI floating-menu `max-height: calc(100vh - 16px)` to a ~2px sliver. Re-capped `.session-action-menu`/`.workspace-prefs-menu` `max-height` with the measured viewport height in the existing viewport shim. Root-caused via on-device DevTools/CDP inspection after ruling out touch-cancel, z-index, stacking, and opacity; reverted those earlier wrong attempts |
| BUG-009 | 2026-06-22 | WebView | Fixed Issue 7 by removing Android's `/api/dashboard/config` write path and blanking the bundled dashboard default so opening WebUI from Android no longer changes WebUI's Official Hermes Dashboard setting from Auto-detect to Always show |
| REL-007 | 2026-06-22 | Release | Updated Android app version to `0.1.5` with `versionCode` 6; created debug build variant that displays app name as "Hermes DEBUG" to distinguish test builds from official releases; deployed to emulator for testing |
| BUG-010 | 2026-06-22 | Data migration | Fixed Issue 7 persistence: Added app startup migration that clears old dashboard URL from SharedPreferences on upgrade so users updating from pre-0.1.5 versions don't retain the stored dashboard URL that was previously being written to WebUI `/api/dashboard/config`; migration includes versioning for future data schema updates |
| BUG-011 | 2026-06-22 | WebView | Fixed Issue 5 cold-start workspace restore by persisting client-side route/history updates via WebView visited-history callbacks, so the app reopens the active Hermes session route after process death instead of falling back to a stale root URL that can show an empty workspace panel until manual re-selection |
| BUG-012 | 2026-06-22 | WebView | Added a resilient Issue 5 fallback: on the configured WebUI origin, if the workspace toggle is tapped from a blank root state and the panel still remains hidden, Android redirects to the last known trusted in-app session route so WebUI can rehydrate workspace state instead of no-oping |
| REL-008 | 2026-06-23 | Release | Updated Android app version metadata to `0.1.6` with `versionCode` 7; narrowed GitHub release automation to build and publish only `hermes-webui-v0.1.6-github.apk`, with tag/version validation before release upload |
| REL-009 | 2026-06-23 | Release | Added a separate manual GitHub Actions workflow (`.github/workflows/play-aab.yml`) that builds/signs a release AAB, renames it to `hermes-webui-v<version>.aab`, and uploads it as an artifact for manual Google Play Console upload until automated Play publishing is wired |
| REL-010 | 2026-06-22 | Release | Incremented Android app version metadata to `0.1.7` with `versionCode` 8 and documented release-note scoping so app releases summarize runtime/app changes only (excluding workflow-only and docs-only updates) |
| BUG-013 | 2026-06-22 | UI | Fixed Issue 8 by adding an **Edit server URL** recovery action to the native error screen so users can reopen Settings and correct a bad saved Hermes server URL without clearing app data |
| REL-011 | 2026-06-22 | Release | Updated Android app version metadata to `0.1.8` with `versionCode` 9 |
| BUG-014 | 2026-06-22 | Android compatibility | Fixed WebUI update-notification generated summaries rendering as a clipped/non-scrollable sliver in Android WebView by restoring vertical page scrolling and re-capping the update summary panel's `max-height: min(34vh, 260px)` with the measured viewport height because Android WebView was collapsing that `vh` max-height to `0px` |
| REL-012 | 2026-06-23 | Release | Wired `.github/workflows/play-aab.yml` to upload the signed `hermes-webui-v<version>.aab` artifact to the Google Play internal testing track using the configured Play service-account secret |
| REL-013 | 2026-06-23 | Release | Split GitHub APK builds into a separate `github` release build type with `applicationIdSuffix = ".github"` and `versionNameSuffix = "-github"` so sideloaded GitHub builds can install beside Google Play builds |
| BUG-015 | 2026-06-23 | WebView | Fixed Issue 9: added bounded auto-retry loop on server error — polls `/api/status` with 1 s → 2 s → 4 s → 10 s cap backoff for up to 60 s, auto-reloads when server comes back, shows "Reconnecting…" on the error screen, cancels cleanly on manual Retry / new navigation / settings save |
| REL-014 | 2026-06-23 | Release | Enhanced `.github/workflows/release.yml` GitHub Release notes: each release now includes explicit build metadata (version/tag, commit SHA, APK filename, SHA-256, workflow run URL) followed by generated GitHub notes, for both create and update paths |
| REL-015 | 2026-06-23 | Release | Consolidated release automation into numbered workflows: `1-orchestration-release.yml` builds both signed artifacts, then fans out to `2-publish-github-apk.yml` for GitHub Releases and `play-store-beta-manual.yml` for optional Google Play open testing |
| REL-016 | 2026-06-23 | Release | Added release workflow concurrency, exact-one artifact validation guards, and `RELEASE.md` operator guidance for manual publish retries |
| REL-017 | 2026-06-23 | Release | Added Play Store What's New changelog generation from the same GitHub generated release notes used for GitHub Releases |
| A-020-P1 | 2026-06-23 | Settings | Implemented Phase 1 of multi-server profile support (Issue #20): added native "Application Settings" entry point in Hermes WebUI sidebar below Help via WebView document-start shim, wired `hermes://app/settings` deep link handling to open native settings bottom sheet, injected phone-outline SVG icon for visual consistency, and validated with unit tests and emulator deployment |
| BUG-016 | 2026-06-23 | Navigation | Fixed back button closing app on first press: implemented "press back again to exit" pattern that requires two back presses within 2 seconds to close app when no WebView history is available, prevents accidental app closure from stuck states, and shows "Press back again to exit" toast on first back press |
| BUG-017 | 2026-06-23 | Settings | Tightened multi-server add flow: server profile creation now rejects duplicates by normalized URL and case-insensitive name, and the Add Server dialog explicitly prompts for an optional friendly name while preserving URL fallback when blank |
| REL-018 | 2026-06-23 | Release | Updated Android app version metadata to `0.1.9` with `versionCode` 10 |
| DOC-002 | 2026-06-23 | Docs | Added Issue 10 execution planning docs: `ISSUE_10_BACKGROUND_EXECUTION_WORKPLAN.md` for staged delivery and `ISSUE_10_STAGE0_DISCOVERY.md` for Stage 0 contract/guardrail tracking |
| A-010-P1 | 2026-06-23 | Lifecycle | Completed Issue 10 Part A resume polish: quick background/resume disconnects now keep the last rendered WebView content visible briefly while bounded reconnect probing runs, fall back to the native error screen as soon as the grace window expires, and resume reconnect polling cleanly across app background/foreground transitions |
| A-010-P2 | 2026-06-23 | Lifecycle | Extended Issue 10 Part A with a bounded background reconnect hold: if the app backgrounds while auto-reconnect is already running, Android starts a temporary `dataSync` foreground service and ongoing "Reconnecting to Hermes" notification so the 60 s retry loop is not canceled immediately on `onStop` |
| DBG-001 | 2026-06-23 | Troubleshooting | Added opt-in debug logging capture toggle in native settings that runs as a foreground service with persistent notification, one-tap Stop action, and app-private logcat file capture for troubleshooting while minimizing app-switch diagnostics gaps |
| REL-019 | 2026-06-24 | Release | Manual orchestration releases now auto-bump `appVersionName` from the latest published tag before building, and Gradle derives `versionCode` from semantic version to keep release numbering monotonic without separate manual edits |
| REL-020 | 2026-06-24 | Release | Bumped Android app version metadata to `0.1.11` with derived `versionCode` `111` for the next GitHub + Play Store release |
| REL-021 | 2026-06-24 | Release | Bumped Android app version metadata to `0.1.12` with derived `versionCode` `112` for the next device test and GitHub + Play Store release |
| REL-022 | 2026-06-25 | Release | Updated checked-in Android app version metadata to match the currently published `0.1.15` / `versionCode` `115` release |
| REL-023 | 2026-06-25 | Release | Enabled release native debug symbol table packaging so Play Console can symbolicate native crashes and ANRs from bundled native libraries |
| REL-024 | 2026-06-25 | Release | Manual orchestration releases now commit the auto-bumped Android version and README release metadata back to `main`, then publish artifacts from that version-bump commit so local builds stay aligned with the latest published release |
| REL-025 | 2026-06-25 | Release | Synced checked-in Android app version metadata to the published `0.1.16` / `versionCode` `116` release so local builds match the latest internal testing build until the next automated bump |
| REL-027 | 2026-07-22 | Release | Updated orchestration fan-out to publish Play production by default (`1 -> 2 + 3`) and moved Play beta publishing into `play-store-beta-manual.yml` so it remains manual/optional for later testing |
| A-010-P3 | 2026-06-24 | Lifecycle | Enabled native SSE-backed reconnect transport for Issue 10: Android now probes lightweight Hermes WebUI `/api/sessions/events` for reconnect detection when the SSE toggle is on, falls back to `/api/status` polling when the stream is unavailable, and updates SSE support messaging to match current WebUI probe semantics |
| A-010-P4 | 2026-06-24 | Notifications | Extended the reconnect foreground service to consume authenticated Hermes WebUI `/api/session/stream` events for the active session when available, updating the ongoing background notification with summary/progress text and trusted tap targets instead of leaving it static |
| A-010-P5 | 2026-06-24 | Notifications | Broadened Issue 10 Part B into an opt-in ongoing background activity notification: the foreground service can now stay alive for trusted session routes while the app is backgrounded, reflects approval/failure/completion SSE events in addition to summaries, and exposes a user-controlled lock-screen redaction toggle for notification body text |
| A-010-P6 | 2026-06-24 | Notifications | Implemented Issue 10 Part C tray approvals: when Hermes emits `approval_required` with an `approval_id`, Android adds allow/deny notification actions, re-checks the queue head through `/api/approval/pending`, submits `/api/approval/respond` only for the matching active request, and rejects stale or duplicate taps fail-closed |
| BUG-018 | 2026-06-24 | Settings | Added a Hermes server-readiness preflight before first-run save, profile add/edit, and profile switching: Android now probes `/api/status` and rejects unreachable servers, HTTP/HTTPS mismatches, setup-mode responses, and non-Hermes pages instead of persisting a URL that traps the app on launch |
| BUG-019 | 2026-06-24 | Settings | Added inline settings validation state plus startup recovery for persisted servers: Android now surfaces “checking server” / error copy inside settings, and if the saved Hermes URL later becomes invalid or falls back into setup mode at launch, the app reopens settings immediately instead of driving WebView into a dead-end load |
| A-006 | 2026-06-24 | Files | Added direct camera capture support for WebView file uploads when the page requests image capture, using a temporary FileProvider-backed photo URI returned to the chooser callback |
| BUG-020 | 2026-06-24 | Authentication | Fixed Issue 12 self-hosted OIDC login by tracking the authorization request `redirect_uri` and keeping popup auth flows alive until that exact callback returns a `code` or `error`, instead of guessing from provider-specific URL patterns |
| BUG-021 | 2026-06-25 | Settings | Fixed Play tester startup recovery for auth-protected Hermes servers: `/api/status` `401`/`403` responses no longer masquerade as initialization failures when the root page fingerprints as Hermes, reconnect liveness treats authenticated status responses as reachable, and already configured servers can continue into WebView sign-in instead of being trapped by native startup validation |
| BUG-022 | 2026-06-25 | Settings | Stopped the first-run / add-server / edit-server preflight from blocking auth-protected Hermes deployments: a 401/403 from `/api/status` on a reachable host is now treated as a healthy sign-in-required server and is saved immediately with a "sign in on the Hermes page to finish" toast, instead of trapping the user behind the readiness check. Also surfaces the full HTTP diagnostic block (status, content-type, server header, body snippet) under the readiness error and adds a recovery dialog with "Open in browser" + "Add/Save/Switch anyway" escape hatches for the remaining failure modes |
| BUG-023 | 2026-06-25 | Settings | Added per-server "Don't ask again for this server" opt-out on the server-switch "Sign-in required" confirmation: once ticked, future switches to that URL skip the prompt and load straight into the Hermes sign-in page; the silenced URL is cleared automatically when the server profile is deleted |
| DBG-002 | 2026-06-25 | Troubleshooting | Debug-build only: auto-start `logcat` capture in `MainActivity.onCreate` before any other startup work via a new `DebugLogBootstrap` so a crash or permission denial during launch is still captured to the same `debug-logs/` directory the foreground service manages; added a draggable floating "Save log" button overlay that one-tap shares the latest captured log via the Android share sheet. No-op on release builds |
| BUG-024 | 2026-06-26 | Authentication | Hardened Issue 12 OIDC routing: trusted authorization code-flow redirects whose `redirect_uri` returns to the configured Hermes WebUI origin now stay in-app even when the provider opens top-level pages, and verified callbacks load back into the primary WebView before dashboard Custom Tab matching can externalize them |
| BUG-025 | 2026-06-27 | Settings | Moved the injected WebUI "Application Settings" entry to anchor after the regular WebUI Settings item, with Help only as a fallback, and exported `hermes://app/settings` as a native recovery route for stuck WebView states |
| REL-026 | 2026-06-28 | Release | Added native app update alerts for both release channels: Play builds check Google Play in-app update availability, GitHub APK builds check the latest GitHub Release with What's Changed text plus direct APK download, and both alert through the existing Hermes updates notification channel |
| BUG-026 | 2026-07-03 | WebView / Settings | Fixed Issue 38 extension compatibility by replacing the aggressive Application Settings sidebar shim with a lightweight clone-based injector that preserves sidebar layout while avoiding extension-item suppression; hardened Android WebView viewport repair so Theme Creator no longer collapses to a sliver, and added a back-button safety ladder that opens native Application Settings before final app exit when no in-app history remains |
| BUG-027 | 2026-07-13 | WebView | Fixed Issue 44 by re-capping the expanded mobile approval panel's `max-height: min(60dvh, 420px)` with a measured pixel height on narrow Android WebView viewports, restoring the approval details and action buttons while preserving the intentional collapsed header-only dock |
| BUG-028 | 2026-07-20 | Permissions | Fixed Issue 49 regression on OEM Android 16 builds where `ACCESS_LOCAL_NETWORK` requests can deny without exposing a user-togglable grant path: LAN startup/save/switch flows now treat the permission prompt as best-effort and still attempt the first WebView load, while the existing `ERR_LOCAL_NETWORK_PERMISSION_MISSING` recovery path remains the enforcement fallback for platforms that do require the grant |
| BUG-029 | 2026-07-22 | WebView | Replaced the whack-a-mole viewport fix approach (20+ explicit CSS selectors) with a hybrid viewport polyfill using generic collapse detection: injects CSS custom properties (`--vh`, `--dvh`) with measured pixel values, applies baseline CSS for root/layout containers, and automatically finds/repairs ANY element collapsed by the Android WebView vh=0 bug using heuristics (tiny height + large scrollHeight + interactive content). Includes performance guards (MAX_REPAIRS=50, MIN_INTERVAL=100ms) and automatic cleanup when elements recover. Eliminates future Issue 6/44/53-style sliver UI regressions without needing new selectors |
| BUG-030 | 2026-07-24 | Settings / Connectivity | Added an opt-in VPN startup guard for Tailscale-addressed Hermes servers: when enabled, startup/save/switch loads for `*.ts.net` and Tailscale CGNAT/ULA endpoints require an active Android VPN transport, and the app attempts to open Tailscale (or VPN settings) before showing inline recovery guidance |
| BUG-031 | 2026-07-24 | Updates | Improved channel-specific update UX to avoid website/store-page detours: GitHub channel notifications now start direct APK download, prompt Android installer after completion, and clean up staged APKs after installer handoff; Play channel keeps using Google Play Core immediate in-app update flow |
| BUG-032 | 2026-07-24 | Updates / Connectivity | Fixed GitHub APK post-download install handoff by auto-launching installer only while Hermes is foregrounded and otherwise posting an install-ready notification that re-enters Hermes and starts install; also hardened VPN transport detection to check all networks and added an optional custom VPN app package fallback before Android VPN settings |
| BUG-033 | 2026-07-25 | Settings / Connectivity | Fixed VPN guard startup ordering so Tailscale server launch attempts open the VPN app before server readiness preflight can fail, and declared Android package-visibility queries so the searchable VPN app picker can find installed launcher apps on Android 11+ |
| BUG-034 | 2026-07-25 | Updates / Connectivity | Refined VPN/update recovery UX: while VPN guard blocks a Tailscale server, Hermes keeps a short-timeout `/api/status` probe running every second across the Tailscale app handoff, then auto-resumes pending server load only after VPN transport and the server are both ready. It sends Tailscale's exported `com.tailscale.ipn.CONNECT_VPN` broadcast as a best-effort auto-connect trigger before app/settings fallback. GitHub update flow now preserves an explicit in-app `Check -> Download -> Install` button progression and no longer auto-launches installer immediately on foreground download completion. |
| BUG-035 | 2026-07-25 | Connectivity | Fixed Tailscale recovery: Hermes now asks Tailscale to connect while remaining visible, retries the pending server load automatically, and only opens Tailscale/VPN settings after a 10-second auto-connect grace period. It dismisses Settings and reloads Hermes once VPN plus server are ready, posts a ready notification when the fallback app remains foregrounded, and routes pull-to-refresh, Retry, reconnect events, and current-server taps through the VPN-aware loader. |
| BUG-036 | 2026-07-28 | Android compatibility | Fixed approval gates and quoted approval context repeatedly expanding and collapsing in Android WebView by retaining generic viewport repairs while the affected panel remains visible; the repair now refreshes on viewport changes and releases only after the panel is hidden. |
| BUG-037 | 2026-08-01 | Authentication | Fixed Issue 54 PocketID SSO usability: enabled AndroidX WebKit `WEB_AUTHENTICATION_SUPPORT_FOR_APP` on main/popup WebViews for passkeys, improved touch-to-focus handling so keyboard input reliably opens on code-entry forms, and temporarily enables third-party cookies only while an OAuth flow is active to preserve federated sign-in compatibility without broadening baseline cookie policy. |
| BUG-038 | 2026-08-01 | UI | Fixed Issue 55 light-theme system bar contrast by applying theme-aware status bar icon appearance at runtime (dark icons in light mode, light icons in dark mode), so Android status bar text/icons remain readable over the WebView shell background. |
| BUG-039 | 2026-08-09 | Settings / Connectivity | Fixed Issue 61 false "UNREACHABLE" classification by hardening server readiness probing: when `/api/status` fails with a transport exception, Android now probes the root page and accepts Hermes fingerprint matches as reachable before failing closed. Also improved loopback diagnostics so `localhost`/`127.0.0.1` clearly explain that they target the Android device itself and should be replaced with a LAN host/IP. |
| BUG-040 | 2026-08-09 | WebView | Fixed Issue 59 blank main-pane text regression by refining the Android viewport polyfill chat-surface guard: the script now keeps descendant chat nodes excluded (to avoid per-message style churn) but allows the top-level `.messages` container itself to be repaired when collapsed by the WebView vh/dvh bug. This restores visible conversation content while retaining anti-flicker behavior. |
| BUG-041 | 2026-08-13 | Authentication | Replaced the incomplete Issue 66 one-navigation OAuth callback workaround with a bounded, testable return-flow state that covers popup callbacks, callback detection through either WebView callback ordering, multi-step 302/JavaScript redirects, and final-page completion. Same-origin OAuth returns stay in the primary WebView, callback URLs are never persisted, and dashboard routing resumes after the final Hermes page finishes. Installed the WebUI viewport polyfill at document start so post-login boot code cannot measure the `vh`/`dvh` root before Android repairs it. |
| BUG-042 | 2026-08-21 | Background continuity | Fixed Issue 75 SSE capability check on password/OIDC-protected servers: the `/api/status`, gateway-probe, and `/api/sessions/events` probes now attach the WebView session cookie (explicit parameter with a guarded `CookieManager` fallback, matching the authenticated `/api/session/stream` pattern), and HTTP 401/403 probe responses classify as the new `SseCapability.AUTH_REQUIRED` state — capability unverified, transport preserved — instead of `NONE` disabling the SSE transport with a failure toast |
| BUG-043 | 2026-08-23 | WebView | Fixed Issue 80 Clarify multiple-choice panel clipped behind the composer in Android WebView: the panel's `max-height: clamp(180px, min(68vh, calc(100vh - 220px)), 420px)` collapsed because WebView evaluates vh units as 0, and the generic collapse repair re-capped it to a viewport-derived height that ignored the app titlebar/composer and could also repair the intentionally zero-height `.composer-flyout` anchor, shifting the card down behind the composer. The viewport polyfill now re-caps the expanded approval/clarify panel to the measured space between the `.app-titlebar` bottom and the card's anchor-invariant bottom edge (which tracks the composer, not the panel height) minus an 8px gap, floored at the WebUI clamp's 180px minimum, via `!important` rules on the card and its inner scroller, and excludes the prompt cards plus the flyout anchor from generic repair so the measured geometry cannot oscillate. Verified on emulator via WebView DevTools/CDP injection of the real `showClarifyCard()` path: card renders at full 420px in portrait and landscape with all choices reachable and oversized content scrolling internally |
| UX-003 | 2026-08-23 | WebView | Issue 83: when a hardware keyboard is attached (Bluetooth/USB/host), a plain Enter in the composer now sends the message and Shift+Enter inserts a newline (desktop convention), instead of forcing a newline and requiring a Send tap. The Issue #34 Enter→newline shim now reads a live `window.__hermesAndroidHardwareKeyboard` flag at keydown time and defers to WebUI's native handling when set; MainActivity detects attachment via `Configuration.keyboard`/`hardKeyboardHidden` and re-syncs the flag on page load and `onConfigurationChanged`. Soft-keyboard behavior (Enter=newline) is unchanged. Verified on emulator: a trusted Enter sent the message (the agent replied), and forcing the flag off restored newline insertion |
| BUG-044 | 2026-08-23 | Settings | Fixed Issue 81 Settings screen crashing instantly on open in v1.0.24: the reconnect polling-interval description passed a `R.plurals` quantity-resource ID to `stringResource`, which throws `Resources.NotFoundException` during composition. Switched to `pluralStringResource` and added an instrumented `SettingsScreenTest` regression that renders the screen at quantities 1 and 2 and asserts both singular/plural descriptions, so the crash is caught by the androidTest lane before release |
