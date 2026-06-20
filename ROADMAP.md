# Hermes-Android Roadmap

> Native Android companion for Hermes Web UI. Secure WebView shell first,
> selective Android-native features over time.
>
> Last updated: 2026-06-20

---

## Status snapshot

| Surface | Status |
|---|---|
| Secure WebView shell | Done - HTTPS-only navigation, host allowlist, hardened defaults |
| WebUI integration | Done - first-run URL settings, session persistence, pull-to-refresh |
| Dashboard Terminal route | Done - native drawer destination for configured `/chat` URL |
| Android sharing | Done - share-to-app intake for text and files |
| Files | Done - WebView upload/download integration |
| Local settings | Done - encrypted settings storage |
| Native distribution polish | Partial - app identity exists; release signing workflow still open |
| Phase 2 native features | Planned - deep links, notifications, biometrics, profiles, camera |

---

## Feature checklist

### MVP shell

- [x] Secure WebView opens a configured Hermes WebUI URL
- [x] First-run URL prompt and settings surface
- [x] HTTPS-only URL validation
- [x] Host allowlist for in-app navigation
- [x] External handoff for non-allowlisted HTTPS links
- [x] Cleartext traffic disabled
- [x] Back handling and WebView history behavior
- [x] Pull-to-refresh
- [x] Loading, error, and offline states
- [x] Cookie-backed session persistence
- [x] Encrypted local settings

### Android integration

- [x] File upload support
- [x] File download support
- [x] Share-to-app intake for text
- [x] Share-to-app intake for files
- [x] Native launcher identity
- [x] Splash and app theme
- [x] Native drawer
- [x] Dashboard Terminal route
- [ ] Camera capture in file chooser
- [ ] Direct share-file auto-attach flow
- [ ] Attachment progress and retry UX

### Platform-native wishlist

- [ ] Deep links and verified app links to Hermes routes
- [ ] Server profile list for multiple Hermes hosts
- [ ] Optional biometric app lock before showing WebView
- [ ] FCM push notification plumbing
- [ ] Notification channel strategy
- [ ] Notification click routing to WebUI routes
- [ ] Expanded native settings for theme, notifications, and profiles
- [ ] Drawer destinations for files, kanban, sessions, and status
- [ ] Instrumentation tests for WebView navigation and intent flows
- [ ] Release signing automation docs and snippets

---

## Forward work

| ID | Priority | Status | Area | Task | Notes |
|---|---|---|---|---|---|
| A-005 | P1 | Todo | Deep links | Add deep-link intent filter and route mapping | Include allowlist and route-safety checks |
| A-009 | P1 | Todo | Settings | Add server profile list | Needed before broader multi-host use |
| A-007 | P1 | Todo | Security UX | Add optional biometric app lock gate | Feature-flagged in settings |
| A-006 | P1 | Todo | Notifications | Add FCM plumbing, channels, and click routing | Requires infrastructure decision for push source |
| A-008 | P2 | Todo | Attachments | Add camera capture in file chooser flow | Include permissions and fallback behavior |
| A-010 | P2 | Todo | Tests | Add instrumentation tests for navigation, share, and deep links | Emulator-ready where practical |
| A-011 | P3 | Todo | Release | Add release signing automation docs and snippets | Keep keystore secrets out of repo |
| A-013 | P2 | Todo | Navigation | Add more drawer destinations | Wait for stable WebUI routes for files, kanban, sessions, status |

Recommended next order:

1. A-005 Deep links
2. A-009 Server profile list
3. A-007 Biometric lock
4. A-006 Push notifications
5. A-008 Attachment and camera enhancements

---

## Completed work

| ID | Date | Area | Summary |
|---|---|---|---|
| A-001 | 2026-06-19 | Build | Fixed Java/Gradle setup and verified `test` plus `assembleDebug` |
| A-002 | 2026-06-19 | Security | Enforced HTTPS-only URL policy with validation and tests |
| A-003 | 2026-06-19 | Tooling | Aligned AGP/Gradle to avoid Gradle 10 deprecation pressure |
| A-004 | 2026-06-19 | UI | Migrated deprecated accompanist swipe refresh to Compose pull refresh |
| A-012 | 2026-06-20 | Navigation | Added native drawer and Dashboard Terminal route |
| DOC-001 | 2026-06-20 | Docs | Cleaned README and created this roadmap as the progress and wishlist tracker |
| BRAND-001 | 2026-06-20 | Branding | Renamed APK output to `hermes-android`; replaced placeholder icon with Hermes WebUI caduceus (vector + density PNGs); icon background aligned to WebUI dark `#1a1a1a` |

---

## Update rules

- Add new user wishlist items to [Platform-native wishlist](#platform-native-wishlist).
- Track actionable work in [Forward work](#forward-work) with an ID, priority,
  status, area, task, and notes.
- Move finished work to [Completed work](#completed-work) after verification.
- Keep `README.md`, `ARCHITECTURE.md`, and `AGENTS.md` aligned when behavior,
  setup, architecture, or workflow changes.
