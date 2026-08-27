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
- `data/SettingsRepository.kt` - encrypted settings persistence; implements `SettingsStore` interface. Uses a versioned `runMigration()` pattern (`KEY_LAST_MIGRATION_VERSION`): when adding new data schema changes, increment `currentMigrationVersion` and add a corresponding migration block. Non-interface methods (`hasRequestedNotificationPermission`, `markNotificationPermissionRequested`, `getLastLoadedUrl`) are called directly by `MainActivity`.
- `domain/ServerUrlValidator.kt` - server URL validation rules
- `domain/ShareIntentParser.kt` - Android share-sheet parsing
- `ui/MainViewModel.kt` - app state orchestration (paired with `ui/MainViewModelFactory.kt`)
- `ui/web/WebShell.kt` - Compose WebView host and refresh/error UX
- `ui/settings/SettingsScreen.kt` + `ui/settings/SettingsSections.kt` - native settings surface and task-based section components (opened via `hermes://app/settings` and the injected WebUI sidebar entry)
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
- WebView uses default browser-managed HTTP/service-worker caching and DOM storage for Hermes WebUI assets. Do not add a parallel native stale-site mirror for authenticated WebUI HTML/API responses; server-profile switches must keep clearing cookies, WebStorage, WebView cache, form data, and history before loading the new host.
- A hybrid viewport polyfill is installed at document start for the configured WebUI origin because Android WebView computes CSS viewport units (`vh`, `dvh`, etc.) as `0px`. The early install is required so WebUI boot code never measures a collapsed root after OAuth or a cold load; the runtime application remains as a fallback. The polyfill injects stable layout-viewport CSS custom properties (`--vh`, `--dvh`) plus separate visual-viewport height/top values for keyboard-constrained prompts, applies baseline CSS for root/layout containers, and uses generic collapse detection to find and repair ANY element that appears collapsed—without needing explicit selectors for each WebUI component. Collapse detection must also repair fully collapsed (`rect.height <= 0`) shell containers when they hide substantial page-level content (e.g. a `100vh` root collapsing to 0px), otherwise entire pages render blank. Generic repair changes only height constraints: preserve every element's existing overflow value and priority, never create a new scroll/clipping container, and never remove an existing inline overflow declaration when the repair clears.
- The approval and clarify prompt panels (`.approval-card`, `.clarify-card`) are absolutely anchored just above the composer (`bottom:-24px` inside the zero-height `.composer-flyout`) and cap their height with viewport units that Android WebView can evaluate as 0. If the anchor falls below the visual viewport (for example behind an overlay keyboard), the polyfill first shifts the expanded card upward by that overlap using a per-card CSS property, then re-caps it to the measured visible space between the greater of the `.app-titlebar`/visual-viewport top and the shifted anchor, minus an 8px gap. The previous shift is included when measuring the invariant anchor so repeated scans cannot oscillate. The cap must be allowed below WebUI's preferred 180px floor when the keyboard or a short viewport leaves less room; internal scrolling preserves access without placing the panel behind the IME or titlebar. Keep these panels and the `.composer-flyout` anchor excluded from generic collapse repair so the measured contract cannot oscillate; other flyout children (e.g. the composer terminal) still size with vh units and remain eligible for generic repair. The `.composer-wrap` is the floating cards' clipping ancestor: a generic repair there (`overflow-y:auto`) turns it into a scroll container that re-clips the card to a sliver behind the composer, and a retained repair never recovers (#80 follow-up). Keep `.composer-wrap` excluded from generic repair too, and keep the injected `.composer-flyout, .composer-wrap { overflow: visible !important; }` rule so neither ancestor can ever clip the floating prompt surface.
- Android no longer writes WebUI `/api/dashboard/config`. WebUI owns the Official Hermes Dashboard setting, including Auto-detect and persistence. Android may normalize an explicitly configured local dashboard URL to its origin for Custom Tab matching and does not persist dashboard-origin pages as the app startup URL. OAuth/OIDC callback URLs for the configured Hermes WebUI origin must bypass dashboard Custom Tab matching and return to the primary WebView.
- WebView microphone access is handled in `MainActivity.kt` through Android `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, plus `WebChromeClient.onPermissionRequest`; grant only `PermissionRequest.RESOURCE_AUDIO_CAPTURE` for trusted Hermes pages (prefer explicit allowlisted HTTP/HTTPS origins, with null/opaque-origin fallback only while the active main-frame URL is the configured Hermes WebUI route).
- Android WebView can expose Web Speech API objects that fail with `not-allowed` before the WebView permission bridge is used. Keep the document-start `mic_force_mediarecorder` fallback scoped to the configured Hermes WebUI origin so WebUI voice input uses MediaRecorder/getUserMedia instead.
- The Issue #34 Enter-key shim forces a plain Enter in the composer to insert a newline (so multi-line messages can be composed on a soft keyboard) via capture-phase `stopImmediatePropagation` + `preventDefault` registered before WebUI's submit handler. As of Issue #83 this is hardware-keyboard-aware: `MainActivity` pushes a live `window.__hermesAndroidHardwareKeyboard` flag (detected via `Configuration.keyboard`/`hardKeyboardHidden`, re-synced on page load and on `onConfigurationChanged` — the manifest already lists `keyboard|keyboardHidden` in `configChanges`), and the shim reads it at keydown time. When a hardware keyboard is attached the shim returns early so WebUI's native Enter-to-submit / Shift+Enter-newline handling runs (desktop convention); with no hardware keyboard it keeps forcing the newline. Keep the flag read at event time (not install time) so keyboard attach/detach applies without a page reload.
- The Issue #65/#92 Clarify keyboard shim suppresses only the first automatic focus on `#clarifyInput` for each request. Key the one-shot marker and short user-intent window to WebUI's current Clarify ID/signature, with a question/choices DOM fallback, because WebUI can replace a visible prompt without hiding the card. Direct Android touch input, hardware Tab navigation, the Clarify **Other** action, and programmatic validation/error refocus after user interaction must still focus that field. Reset the marker when the card becomes hidden. Unrelated dialogs—including the workspace new-folder name input from Issue #90—must never be scanned, blurred, or have `tabindex` rewritten; do not restore blanket modal-input handling.
- The injected Hermes WebUI "Application Settings" entry should anchor immediately after the WebUI Settings item when present, with Help only as a fallback anchor for older or changed sidebar markup. Do not add a persistent native overlay button for this entry; it should appear only with the WebUI sidebar.
- `hermes://app/settings` is exported as a native recovery deep link and opens `SettingsScreen` without relying on the current WebView route.
- WebUI browser notifications are handled through `HermesNotificationBridgeCoordinator` + `HermesNotificationPresenter`, with `MainActivity.kt` wiring Android `POST_NOTIFICATIONS`, the native notification channel, the document-start `Notification`/`ServiceWorkerRegistration.showNotification` compatibility facade, and trusted notification tap routing. Keep the bridge scoped to the configured Hermes WebUI route, reject subframes/non-WebUI origins, and validate notification tap URLs through the host allowlist before loading.
- Native app update alerts share the existing `Hermes updates` notification channel but are selected by build channel. Automatic checks should run each time the app opens (while automatic checks are enabled), and manual Settings checks still run immediately. Keep the shared settings/notification UX common, with `BuildConfig.UPDATE_CHANNEL = "play"` using Google Play Core in-app updates, `"github"` checking GitHub Releases plus the `*-github.apk` asset for direct downloads and release-note excerpts, and `"none"` avoiding production update prompts in debug builds.
- Hermes WebUI implements its own conversation long-press menus from a touch timer (e.g. `static/sessions.js`, ~400ms) and renders them as `position:fixed` elements capped with `max-height: calc(100vh - 16px)`. Keep `isLongClickable = true` without a consuming long-click listener so Android text-selection handles remain available (Issue #35); do not add `contextmenu` synthesis, `touchcancel` guards, or a `startActionMode` override. Issue #6 ultimately proved the WebUI touch timer already works and the invisible menu was a viewport-unit collapse. If menus render tiny/clipped again, check the hybrid viewport repair, not gesture interception, z-index, opacity, or stacking.
- Client-certificate settings support PKCS#12 (`.pfx`/`.p12`) files only. Keep certificate requests scoped to allowlisted hosts, select a usable private-key entry, and fail closed on unreadable, malformed, password-mismatched, or keyless stores; never let certificate parsing throw through `WebViewClient.onReceivedClientCertRequest`.
- The `hermes-android-viewport-fix` style must not lock vertical page scrolling. Keep body overflow override scoped to horizontal overflow only; forcing `body { overflow: hidden }` clips expandable WebUI content such as generated update summaries inside Android WebView. The generic collapse detection handles update-summary panels and other viewport-unit-based elements automatically without explicit selectors; the approval/clarify prompt panels are the one exception, using the measured titlebar/composer-aware cap described above instead of generic repair.
- Do not reintroduce a parallel native drawer or custom Android Terminal/menu button for the dashboard link.
- Hermes WebUI DOM/CSS compatibility shims must stay scoped to the configured WebUI route. Do not inject the viewport polyfill into the official dashboard origin; dashboard links should use Chrome Custom Tabs unless a future task explicitly reopens the app-WebView approach. The one exception is OAuth/OIDC provider pages: while a main-frame OAuth flow is active, inject only the viewport polyfill (never the other WebUI shims) into those HTTP/HTTPS pages, because provider login roots that use viewport units (e.g. `h-screen`) otherwise collapse to 0px and render blank (issue #66).
- OAuth/OIDC code-flow navigations may temporarily load non-allowlisted HTTP/HTTPS provider pages in-app only after Android has parsed an authorization URL whose `redirect_uri` returns to the configured Hermes WebUI origin. Scheme compatibility may upgrade HTTP to HTTPS for public-IP/proxy deployments, but must never downgrade an HTTPS configured origin or declared callback to HTTP. After a verified callback, keep the callback and its bounded same-origin redirect chain in the primary WebView until a page with explicit Hermes WebUI runtime/DOM markers finishes; do not use a one-navigation dashboard bypass or clear on an arbitrary same-origin page because WebView callback ordering, redirect counts, and interstitial pages vary. Do not broaden this to arbitrary external links or non-web schemes. OAuth provider and callback URLs must never be persisted as `lastLoadedUrl` or restored as the cold-start URL; only final configured WebUI routes may be remembered.
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

- Always work on issues through a dedicated branch and a pull request. Never commit issue fixes directly to `main`.
- For issue work, create or reuse a branch named after the issue (for example `issue-73`) before publishing any code.
- Push the branch to GitHub with `git push --set-upstream origin <branch>` once the work is ready for review.
- Create the PR with `gh pr create --title "<user-facing release note>" --body-file <path-to-markdown> --base main --head <branch>`; do not hand-write a long inline body in `--body`.
- Attach the PR to the issue in the PR body by including a closing keyword such as `Fixes #123` or `Closes #123`; use `Related #123` when the PR contributes without fully resolving the issue.
- When an issue is resolved and closed, always add a comment to the issue explaining the fix: a short root-cause summary, what changed, and how it was verified. Post it with a file-based markdown body, such as `gh issue comment <issue-number> --body-file <path-to-markdown>` or `gh api repos/<owner>/<repo>/issues/<issue-number>/comments --input <path-to-markdown>`.
- Always verify the rendered result with `gh pr view` or `gh issue view` after posting, and fix any markdown formatting regression immediately.

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
already checked into Git; it must never edit, commit, or push source. Gradle
derives `versionCode` from semantic version (`major*10000 + minor*100 + patch`).
GitHub releases should use generated notes configured by `.github/release.yml`.
Generate release metadata once in the build job and bundle it with both signed
artifacts: GitHub notes retain clickable PR links, while Play Store `en-US`
What's New text keeps compact PR/issue URLs, stays below the Play text limit,
and ends with:
`Report issues through the in-app bug report tool.`
Keep `RELEASE.md` aligned with the workflow operator path whenever release
automation changes.

Separate from release publishing, CI uses `.github/workflows/0-ci-build-and-test.yml` to gate pull requests and direct `main` pushes without signing secrets. Each check runs as its own job so a failure names the gate that broke: `release-tooling-tests`, `webui-script-syntax`, `webui-script-lint`, `unit-tests`, `android-lint`, and `debug-build`. Keep every job's `timeout-minutes` set; an untimed job can burn the six-hour runner default. Pull requests and direct `main` pushes that change Android source or build inputs also run the complete unfiltered `connectedDebugAndroidTest` suite on Android API 35 and 36. Release builds run the full API 36 suite again, then verify APK and AAB signatures before upload. Keep contributor verification steps aligned with these gates when changing build/test flow.

The injected WebUI JavaScript in `webui/HermesWebUiScripts.kt` (and the raw-string block in `MainActivity.kt`) is invisible to kotlinc and Android Lint, so a syntax error or runtime-only mistake there ships green and bricks WebUI rendering on device. `tools/extract_webui_scripts.py` pulls each Kotlin raw string out into a standalone `.js` file — padded so JavaScript line numbers match the Kotlin source, with Kotlin string templates replaced by a placeholder literal — and CI runs `node --check` plus `eslint.runtime-guard.config.mjs` over the result. That ESLint config is deliberately not a style linter: it enables only rules that catch code which parses but throws at runtime. Add a Kotlin file to `SOURCE_FILES` in the extractor when it starts carrying injected script text, and add genuinely WebUI-owned page globals to `webUiPageGlobals` rather than disabling `no-undef`.

Run the same guards locally with:

```powershell
python tools/extract_webui_scripts.py
Get-ChildItem build/webui-scripts/*.js | ForEach-Object { node --check $_.FullName }
npm install --no-save eslint@^10
npx eslint --no-config-lookup -c eslint.runtime-guard.config.mjs "build/webui-scripts/**/*.js"
```

## Verification

Run from the repository root:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat testDebugUnitTest --no-daemon
.\gradlew.bat lintDebug --no-daemon
.\gradlew.bat assembleDebug --no-daemon
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
