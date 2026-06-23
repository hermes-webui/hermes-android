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
- `data/SettingsRepository.kt` - encrypted settings persistence; implements `SettingsStore` interface. Uses a versioned `runMigration()` pattern (`KEY_LAST_MIGRATION_VERSION`): when adding new data schema changes, increment `currentMigrationVersion` and add a corresponding migration block. Non-interface methods (`hasRequestedNotificationPermission`, `markNotificationPermissionRequested`, `getLastLoadedUrl`) are called directly by `MainActivity`.
- `domain/ServerUrlValidator.kt` - server URL validation rules
- `domain/ShareIntentParser.kt` - Android share-sheet parsing
- `ui/MainViewModel.kt` - app state orchestration
- `ui/web/WebShell.kt` - Compose WebView host and refresh/error UX

Known Android WebView compatibility behavior lives in `MainActivity.kt`:

- The Compose root applies `WindowInsets.safeDrawing` so the WebView shell and native snackbar do not overlap the Android status or navigation bars.
- Forced/algorithmic WebView darkening is disabled so Hermes WebUI keeps its own colors.
- WebView uses default browser-managed HTTP/service-worker caching and DOM storage for Hermes WebUI assets. Do not add a parallel native stale-site mirror for authenticated WebUI HTML/API responses; reset-session behavior must keep clearing cookies, WebStorage, and WebView cache.
- A measured viewport-height shim is injected because some Android WebView builds compute Hermes WebUI `100dvh` root layout height as `0px`, which hides page text/content.
- Android no longer writes WebUI `/api/dashboard/config`. WebUI owns the Official Hermes Dashboard setting, including Auto-detect and persistence. Android may normalize an explicitly configured local dashboard URL to its origin for Custom Tab matching and does not persist dashboard-origin pages as the app startup URL.
- WebView microphone access is handled in `MainActivity.kt` through Android `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, plus `WebChromeClient.onPermissionRequest`; grant only `PermissionRequest.RESOURCE_AUDIO_CAPTURE` for trusted Hermes pages (prefer explicit allowlisted HTTP/HTTPS origins, with null/opaque-origin fallback only while the active main-frame URL is the configured Hermes WebUI route).
- Android WebView can expose Web Speech API objects that fail with `not-allowed` before the WebView permission bridge is used. Keep the document-start `mic_force_mediarecorder` fallback scoped to the configured Hermes WebUI origin so WebUI voice input uses MediaRecorder/getUserMedia instead.
- WebUI browser notifications are handled in `MainActivity.kt` through Android `POST_NOTIFICATIONS`, a native notification channel, a document-start `Notification`/`ServiceWorkerRegistration.showNotification` compatibility facade, and an AndroidX WebMessageListener. Keep the bridge scoped to the configured Hermes WebUI route, reject subframes/non-WebUI origins, and validate notification tap URLs through the host allowlist before loading.
- Hermes WebUI implements its own conversation long-press menus from a touch timer (e.g. `static/sessions.js`, ~400ms) and renders them as `position:fixed` elements capped with `max-height: calc(100vh - 16px)`. Two Android WebView facts matter: (1) keep `isLongClickable = false` + `setOnLongClickListener { true }` so the native long-press does not preempt WebUI's timer — do NOT add `contextmenu` synthesis, `touchcancel` guards, or a `startActionMode` override; the touch path already works. (2) Android WebView computes CSS `100vh` as `0`, so the menu's `max-height` collapses it to a ~2px sliver; the `hermes-android-viewport-fix` style therefore re-caps `.session-action-menu`/`.workspace-prefs-menu` `max-height` with the measured viewport height. If menus ever render tiny/clipped again, check that viewport re-cap, not z-index/opacity/stacking (all verified fine via on-device DevTools inspection).
- The same `hermes-android-viewport-fix` must not lock vertical page scrolling. Keep any body overflow override scoped to horizontal overflow only; forcing `body { overflow: hidden }` clips expandable WebUI content such as generated update summaries inside Android WebView. Also keep the update-summary panel (`#updateSummaryPanel`, `max-height: min(34vh, 260px)` in WebUI) re-capped with the measured viewport height, because Android WebView can collapse that `vh` max-height to `0px` just like the long-press menus.
- Do not reintroduce a parallel native drawer or custom Android Terminal/menu button for the dashboard link.
- Hermes WebUI DOM/CSS compatibility shims must stay scoped to the configured WebUI route. Do not inject the Hermes WebUI viewport/config shim into the official dashboard origin; dashboard links should use Chrome Custom Tabs unless a future task explicitly reopens the app-WebView approach.

## AI Agent Capabilities

AI agents working in this repository have access to the GitHub CLI (GH CLI) and can:

- Fetch and analyze GitHub issues, pull requests, and discussions
- Query repository metadata, branches, and releases
- Review diffs and commit history
- Assist with issue triage, impact analysis, and prioritization

When a user references GitHub issues (e.g., by URL or issue number), agents should use GH CLI to retrieve full issue details rather than asking the user to copy-paste them.

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
- Preserve the channel identity split: Google Play builds use the official
  `release` build type and `com.hermeswebui.android`; GitHub APK builds use the
  `github` build type, `com.hermeswebui.android.github`, and a `-github`
  version name suffix so both channels can install side by side. Debug builds
  use `applicationIdSuffix = ".debug"` (`com.hermeswebui.android.debug`) and
  display as "Hermes DEBUG" so test builds are visually distinct from release
  builds on the same device.

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

The repo includes `.github/workflows/release.yml` for signed GitHub APK release
automation and `.github/workflows/play-aab.yml` for building/uploading a signed
AAB to the Google Play internal testing track. Keep both aligned with
`app/build.gradle.kts`, `keystore.properties.example`, and the documented GitHub
secrets whenever the release flow changes. Keep `play-aab.yml` aligned with the
`GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_BASE64` secret when the Play upload flow
changes. The GitHub workflow should publish only the
`hermes-webui-v<version>-github.apk` APK, tag-triggered releases should match
the Gradle `versionName`, and the release body should keep the explicit build
metadata block (version/tag, commit SHA, APK filename, SHA-256, workflow run
URL) ahead of generated notes.

## Verification

Run from the repository root:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat assembleDebug --no-daemon
```

For a local signed release build:

```powershell
.\gradlew.bat stageGithubReleaseApk --no-daemon   # builds signed github APK into build/release/
.\gradlew.bat printReleaseVersionName --no-daemon  # prints current versionName for automation
```

For docs-only changes, Gradle verification may be skipped if the final response
states that no code was changed.

Optional checks:

```powershell
.\gradlew.bat lint --no-daemon
.\gradlew.bat connectedDebugAndroidTest --no-daemon
```

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

Keep unrelated local changes out of commits. If a file is already modified and
is not part of the current task, leave it unstaged and call it out in the final
summary.

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
