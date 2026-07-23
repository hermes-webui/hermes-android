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
- `ui/MainViewModel.kt` - app state orchestration (paired with `ui/MainViewModelFactory.kt`)
- `ui/web/WebShell.kt` - Compose WebView host and refresh/error UX
- `ui/settings/SettingsScreen.kt` / `ui/settings/SettingsBottomSheet.kt` - native settings surfaces (opened via `hermes://app/settings` and the injected WebUI sidebar entry)
- `ui/DebugLogFloatingButton.kt` - draggable overlay shown while debug logging is active
- `OAuthPopupFlow.kt` - parses authorization requests and verifies OAuth/OIDC callbacks before allowing in-app provider navigation
- `notification/HermesNotificationBridgeCoordinator.kt` - scoped WebUI Notification API bridge and Android permission reply handling
- `notification/HermesNotificationPresenter.kt` - renders Android-backed WebUI notifications and routes tapped notification intents back into the trusted WebView
- `background/HermesReconnectService.kt` + `background/ReconnectBackgroundPolicy.kt` - foreground service (manifest `foregroundServiceType="dataSync"`) that keeps the bounded reconnect probe and `/api/session/stream` subscription alive while the activity is backgrounded; policy helpers gate when the service should run/keep-alive/cancel
- `background/HermesForegroundServiceCoordinator.kt` - owns MainActivity-facing reconnect/debug foreground-service lifecycle sync and promotion/teardown rules
- `background/ApprovalClient.kt` + `background/ApprovalActionSupport.kt` + `background/ReconnectSessionStreamSupport.kt` - authenticated `/api/approval/pending`/`/api/approval/respond` client and SSE event support used by the notification approval actions
- `background/HermesDebugLoggingService.kt` + `background/DebugLogBootstrap.kt` - opt-in foreground logcat capture into `files/debug-logs/` with a persistent Stop notification
- `server/HermesServerProfileCoordinator.kt` - startup preflight plus server-profile add/edit/delete/switch validation and confirmation flows
- `webview/HermesWebViewConfigurator.kt` - shared main/popup WebView hardening and settings setup
- `webui/HermesWebUiScripts.kt` - document-start WebUI compatibility shims (hybrid viewport polyfill with generic collapse detection, microphone fallback, Enter-key newline behavior, sidebar settings injector, notification bridge script payloads); keep these scoped to the configured Hermes WebUI origin
- `update/HermesAppUpdateCoordinator.kt` - app-update checks, update notifications, Play update launch, and GitHub APK download intents
- `update/GitHubReleaseUpdateChecker.kt` + `update/AppVersionComparator.kt` + `update/AppUpdateCheckResult.kt` - GitHub-channel update check (Play channel uses Play Core in `MainActivity`)
- `update/AppUpdateDownloadPolicy.kt` - strict GitHub APK download host allowlist used by update download intents (`github.com` and `*.githubusercontent.com` only)

Known Android WebView compatibility behavior lives in `MainActivity.kt`:

- The Compose root applies `WindowInsets.safeDrawing` so the WebView shell and native snackbar do not overlap the Android status or navigation bars.
- Forced/algorithmic WebView darkening is disabled so Hermes WebUI keeps its own colors.
- WebView uses default browser-managed HTTP/service-worker caching and DOM storage for Hermes WebUI assets. Do not add a parallel native stale-site mirror for authenticated WebUI HTML/API responses; reset-session behavior must keep clearing cookies, WebStorage, and WebView cache.
- A hybrid viewport polyfill is injected because Android WebView computes CSS viewport units (`vh`, `dvh`, etc.) as `0px`. The polyfill injects CSS custom properties (`--vh`, `--dvh`) with measured values, applies baseline CSS for root/layout containers, and uses generic collapse detection to find and repair ANY element that appears collapsed—without needing explicit selectors for each WebUI component.
- Android no longer writes WebUI `/api/dashboard/config`. WebUI owns the Official Hermes Dashboard setting, including Auto-detect and persistence. Android may normalize an explicitly configured local dashboard URL to its origin for Custom Tab matching and does not persist dashboard-origin pages as the app startup URL. OAuth/OIDC callback URLs for the configured Hermes WebUI origin must bypass dashboard Custom Tab matching and return to the primary WebView.
- WebView microphone access is handled in `MainActivity.kt` through Android `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, plus `WebChromeClient.onPermissionRequest`; grant only `PermissionRequest.RESOURCE_AUDIO_CAPTURE` for trusted Hermes pages (prefer explicit allowlisted HTTP/HTTPS origins, with null/opaque-origin fallback only while the active main-frame URL is the configured Hermes WebUI route).
- Android WebView can expose Web Speech API objects that fail with `not-allowed` before the WebView permission bridge is used. Keep the document-start `mic_force_mediarecorder` fallback scoped to the configured Hermes WebUI origin so WebUI voice input uses MediaRecorder/getUserMedia instead.
- The injected Hermes WebUI "Application Settings" entry should anchor immediately after the WebUI Settings item when present, with Help only as a fallback anchor for older or changed sidebar markup. Do not add a persistent native overlay button for this entry; it should appear only with the WebUI sidebar.
- `hermes://app/settings` is exported as a native recovery deep link and opens `SettingsScreen` without relying on the current WebView route.
- WebUI browser notifications are handled through `HermesNotificationBridgeCoordinator` + `HermesNotificationPresenter`, with `MainActivity.kt` wiring Android `POST_NOTIFICATIONS`, the native notification channel, the document-start `Notification`/`ServiceWorkerRegistration.showNotification` compatibility facade, and trusted notification tap routing. Keep the bridge scoped to the configured Hermes WebUI route, reject subframes/non-WebUI origins, and validate notification tap URLs through the host allowlist before loading.
- Native app update alerts share the existing `Hermes updates` notification channel but are selected by build channel. Automatic checks should stay delayed until the app has been foregrounded for about one minute and throttled to once per day, while manual Settings checks run immediately. Keep the shared settings/notification UX common, with `BuildConfig.UPDATE_CHANNEL = "play"` using Google Play Core in-app updates, `"github"` checking GitHub Releases plus the `*-github.apk` asset for direct downloads and release-note excerpts, and `"none"` avoiding production update prompts in debug builds.
- Hermes WebUI implements its own conversation long-press menus from a touch timer (e.g. `static/sessions.js`, ~400ms) and renders them as `position:fixed` elements capped with `max-height: calc(100vh - 16px)`. Two Android WebView facts matter: (1) keep `isLongClickable = false` + `setOnLongClickListener { true }` so the native long-press does not preempt WebUI's timer — do NOT add `contextmenu` synthesis, `touchcancel` guards, or a `startActionMode` override; the touch path already works. (2) Android WebView computes CSS `100vh` as `0`, so the hybrid viewport polyfill's generic collapse detection automatically finds and repairs any element (menus, dialogs, panels) that appears collapsed. If menus ever render tiny/clipped again, check the polyfill's collapse detection heuristics, not z-index/opacity/stacking (all verified fine via on-device DevTools inspection).
- The `hermes-android-viewport-fix` style must not lock vertical page scrolling. Keep body overflow override scoped to horizontal overflow only; forcing `body { overflow: hidden }` clips expandable WebUI content such as generated update summaries inside Android WebView. The generic collapse detection handles update-summary panels, approval panels, and other viewport-unit-based elements automatically without explicit selectors.
- Do not reintroduce a parallel native drawer or custom Android Terminal/menu button for the dashboard link.
- Hermes WebUI DOM/CSS compatibility shims must stay scoped to the configured WebUI route. Do not inject the viewport polyfill into the official dashboard origin; dashboard links should use Chrome Custom Tabs unless a future task explicitly reopens the app-WebView approach.
- OAuth/OIDC code-flow navigations may temporarily load non-allowlisted HTTP/HTTPS provider pages in-app only after Android has parsed an authorization URL whose `redirect_uri` returns to the configured Hermes WebUI origin. Do not broaden this to arbitrary external links or non-web schemes.
- Android 16/17 local-network permission handling is best-effort for obvious LAN hosts (`localhost`, `.local`, private/link-local IPs): request `ACCESS_LOCAL_NETWORK` before first load, but continue startup/save/switch flows when that preflight prompt is denied on OEM builds that expose no grant toggle. Keep the hard-fail recovery path keyed to real WebView `ERR_LOCAL_NETWORK_PERMISSION_MISSING` failures.

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
- Apply labels that help generated release notes group the change:
  `feature`, `enhancement`, `user-facing`, `bug`, `bugfix`, `fix`,
  `testing-notes`, `needs-testing`, `maintenance`, `release`, or `docs`.
- Use `skip-changelog` only for changes that should not appear in user-facing
  release notes.
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

The repo uses four workflows for building and releasing:
- `.github/workflows/1-orchestration-release.yml` — single signed release entry point; builds both the GitHub APK and Play AAB, then fans out to workflows 2 and 3.
- `.github/workflows/2-publish-github-apk.yml` — publishes the signed APK to GitHub Releases.
- `.github/workflows/3-publish-play-store-release.yml` — submits the AAB to the Play **open testing (beta)** track after every orchestrated build.
- `.github/workflows/4-promote-play-production.yml` — **manual only**; promotes a beta build to the Play **production** track. Run this after validating open testing. Inputs come from the "Manual Publish Retry Details" summary of the orchestration run.

Keep all four workflows aligned with `app/build.gradle.kts`,
`keystore.properties.example`, and the documented GitHub secrets whenever the
release flow changes. The GitHub publish workflow should publish only the
`hermes-webui-v<version>-github.apk` APK, the Play beta workflow should
submit only the `hermes-webui-v<version>.aab` AAB to the beta track,
tag-triggered releases should match the Gradle `versionName`, and the public
GitHub release body should contain human-readable What's New notes rather than
build metadata.
Manual orchestration runs auto-bump `appVersionName` from the latest published
`vX.Y.Z` tag, update README release metadata, commit those changes back to
`main`, and then build from that version-bump commit; Gradle derives
`versionCode` from semantic version (`major*10000 + minor*100 + patch`) so
release numbering remains monotonic.
GitHub releases should use generated GitHub release notes configured by
`.github/release.yml`. Play Store uploads should include a brief `en-US` What's
New changelog generated from the same notes through `whatsNewDirectory`, capped
below the Play text limit, and ending with:
`Report issues through the in-app bug report tool.`
Keep `RELEASE.md` aligned with the workflow operator path whenever release
automation changes.

Separate from release publishing, CI uses `.github/workflows/ci.yml` to run `testDebugUnitTest` + `assembleDebug` on pushes/PRs without signing secrets. Keep contributor verification steps aligned with this gate when changing build/test flow.

## Verification

Run from the repository root:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat testDebugUnitTest --no-daemon
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

Branch hygiene after merges:

- After a feature or fix branch is merged into `main`, delete the merged branch locally and on GitHub unless the human explicitly wants to keep it.
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
