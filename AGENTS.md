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

- `MainActivity.kt` - Android platform boundary, WebView, intents, downloads
- `DashboardActivity.kt` - in-app Official Hermes Dashboard host
- `core/security/UrlPolicy.kt` - URL and navigation decisions
- `data/SettingsRepository.kt` - encrypted settings persistence
- `domain/ServerUrlValidator.kt` - server URL validation rules
- `domain/ShareIntentParser.kt` - Android share-sheet parsing
- `ui/MainViewModel.kt` - app state orchestration
- `ui/web/WebShell.kt` - Compose WebView host and refresh/error UX

Known Android WebView compatibility behavior lives in `MainActivity.kt`:

- The Compose root applies `WindowInsets.safeDrawing` so the WebView shell and native snackbar do not overlap the Android status or navigation bars.
- Forced/algorithmic WebView darkening is disabled so Hermes WebUI keeps its own colors.
- A measured viewport-height shim is injected because some Android WebView builds compute Hermes WebUI `100dvh` root layout height as `0px`, which hides page text/content.
- Android normalizes the Official Hermes Dashboard URL to its origin, seeds it through WebUI `/api/dashboard/config` when WebUI has no dashboard URL, opens dashboard-origin new-window requests in `DashboardActivity`, and does not persist dashboard-origin pages as the app startup URL.
- Do not reintroduce a parallel native drawer or custom Android Terminal/menu button for the dashboard link.
- Hermes WebUI DOM/CSS compatibility shims must stay scoped to the configured WebUI route. `DashboardActivity` may apply dashboard-specific viewport fixes, but do not inject the Hermes WebUI viewport/config shim into the official dashboard origin because that can break dashboard layout and font rendering.

## Scope

This repository is the standalone Android app.

- Modify this repo only unless the human explicitly asks for sibling repo work.
- The sibling `hermes-webui` repo may be read for reference, but do not edit it
  from this workspace task without explicit instruction.
- Keep the Android app a thin, secure companion to Hermes WebUI.
- Prefer incremental changes over broad rewrites.
- Treat `applicationId` and `namespace` as release-critical identity. Do not
  change either without an explicit user decision. The finalized pre-release
  identity is `com.hermeswebui.android`.

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

- Preserve HTTPS-only behavior.
- Preserve host allowlist enforcement.
- Externalize non-allowlisted HTTPS navigation.
- Keep cleartext traffic disabled.
- Do not add JavaScript bridges for secrets.
- Keep signing keys, API keys, passwords, and local machine paths out of git.

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

## Verification

Run from the repository root:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat assembleDebug --no-daemon
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
