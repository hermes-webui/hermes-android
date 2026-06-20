# Hermes-Android Roadmap

> Native Android companion for Hermes Web UI. Secure WebView shell first,
> selective Android-native features over time.
>
> Last updated: 2026-06-20

---

## Status snapshot

| Surface | Status |
|---|---|
| Secure WebView shell | Done - HTTP/HTTPS navigation, host allowlist, hardened defaults |
| WebUI integration | Done - first-run WebUI URL setting, dashboard config seeding, session persistence, pull-to-refresh |
| WebView compatibility | Done - disables forced darkening, patches Android viewport-unit collapse, respects system-bar safe insets, and forces WebUI microphone input onto the Android-compatible MediaRecorder path |
| Official dashboard link | Done - Android seeds WebUI's Official Hermes Dashboard origin when WebUI has none, opens dashboard-origin requests in a Chrome Custom Tab with minimal browser UI, and avoids persisting dashboard pages as startup state |
| Android sharing | Done - share-to-app intake for text and files |
| Files | Done - WebView upload/download integration |
| Microphone | Done - allowlisted WebView audio capture with Android runtime permission plus WebUI MediaRecorder fallback |
| Local settings | Done - encrypted settings storage |
| Native navigation | Done - WebUI-owned dashboard link integration and deep links |
| Server health probing | Done - `/api/status` probe to distinguish server-down from content errors |
| Native distribution polish | Partial - app identity exists; release signing workflow still open |
| Phase 2 native features | Planned - biometric lock, server profiles, push notifications, camera, sessions panel |

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
- [x] Loading, error, and offline states
- [x] Cookie-backed session persistence
- [x] Encrypted local settings

### Android integration

- [x] File upload support
- [x] File download support
- [x] Microphone capture support for WebUI voice input
- [x] Share-to-app intake for text
- [x] Share-to-app intake for files
- [x] Native launcher identity
- [x] Splash and app theme
- [x] WebUI Official Hermes Dashboard URL seeding
- [x] Official dashboard link route
- [x] Deep links (`hermes://session/{id}`)
- [x] Server health probing
- [ ] Camera capture in file chooser
- [ ] Direct share-file auto-attach flow
- [ ] Attachment progress and retry UX

### Platform-native wishlist

- [x] Deep links and verified app links to Hermes routes
- [x] Server health probing to refine offline/error states
- [ ] Server profile list for multiple Hermes hosts
- [ ] Optional biometric app lock before showing WebView
- [ ] FCM push notification plumbing
- [ ] Notification channel strategy
- [ ] Notification click routing to WebUI routes via deep links
- [ ] Expanded native settings for theme, notifications, and profiles
- [ ] Optional native sessions list (requires authenticated API access)
- [ ] WebUI menu shortcuts for files, kanban, and status if needed
- [ ] Instrumentation tests for WebView navigation and intent flows
- [x] Final package/application ID decision before first public release
- [ ] Release signing automation docs and snippets

---

## Forward work

| ID | Priority | Status | Area | Task | Notes |
|---|---|---|---|---|---|
| A-009 | P1 | Todo | Settings | Add server profile list | Needed before broader multi-host use |
| A-007 | P1 | Todo | Security UX | Add optional biometric app lock gate | Feature-flagged in settings |
| A-006 | P1 | Todo | Notifications | Add FCM plumbing, channels, and click routing | Requires infrastructure decision for push source |
| A-008 | P2 | Todo | Attachments | Add camera capture in file chooser flow | Include permissions and fallback behavior |
| A-010 | P2 | Todo | Tests | Add instrumentation tests for navigation, share, and deep links | Emulator-ready where practical |
| A-011 | P3 | Todo | Release | Add release signing automation docs and snippets | Keep keystore secrets out of repo |
| A-013 | P2 | Todo | Navigation | Add optional sessions/files/kanban/status shortcuts without replacing WebUI navigation | Sessions require authenticated API access (A-009 strategy) |

Recommended next order:

1. A-007 Biometric lock
2. A-009 Server profile list
3. A-006 Push notifications
4. A-008 Attachment and camera enhancements
5. A-010 Instrumentation tests

---

## Completed work

| ID | Date | Area | Summary |
|---|---|---|---|
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

---

## Update rules

- Add new user wishlist items to [Platform-native wishlist](#platform-native-wishlist).
- Track actionable work in [Forward work](#forward-work) with an ID, priority,
  status, area, task, and notes.
- Move finished work to [Completed work](#completed-work) after verification.
- Keep `README.md`, `ARCHITECTURE.md`, and `AGENTS.md` aligned when behavior,
  setup, architecture, or workflow changes.
