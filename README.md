# Hermes-Android 🤖📱

Hermes-Android is the native Android wrapper for
[Hermes Web UI](https://github.com/nesquena/hermes-webui). It keeps the
Hermes web app as the primary interface and adds only the Android pieces that
should live on-device: secure WebView hosting, native navigation, sharing,
downloads, notifications, and encrypted local settings.

> 🔒 HTTP/HTTPS URL policy · 🌐 host allowlist · 📂 sharing + downloads · 🧊 encrypted settings
> 🔔 Android-backed WebUI notifications

The app is intentionally thin. Hermes product behavior, UI layout, styling, and
feature workflows stay server-delivered through WebUI, while this repo owns
Android integration and device safety.

## Repository Scope

Use this repository for Android-wrapper issues and PRs only:

- WebView hosting, navigation, compatibility, and Android lifecycle behavior
- Android permissions, microphone, notifications, sharing, uploads, downloads, and deep links
- Local encrypted settings, app identity, build, signing, and Play distribution

Open WebUI/product issues in
[Hermes Web UI](https://github.com/nesquena/hermes-webui) instead:

- Hermes UI layout, styling, animations, routing, and dashboard behavior
- Chat/session behavior, product features, API behavior, and WebUI bugs
- Feature requests that should work the same in browser, desktop, and Android

---

## 🧪 Looking for Internal Testers!

We're looking for internal testers for the first pre-release build on Google Play.

To get added, **message [@Paladin173](https://github.com/Paladin173) your Gmail address** and we'll send you a Play Store invite.

Once added, the app will appear in the Play Store for you to install and receive automatic updates.

Current pre-release version: `v0.1.24`.

Current Android build metadata:

- Version name: `0.1.24`
- Version code: `124` (derived from semantic version as `major*10000 + minor*100 + patch`)
- Application ID: `com.hermeswebui.android`
- Compile/target SDK: `37`

> Requires a running [Hermes WebUI](https://github.com/nesquena/hermes-webui) instance. Enter your server URL on first launch.

---

## 🧭 Contents

- Repository scope
- ⚡ Quick start
- ✨ Features
- ⚙️ Configuration
- 🧪 Running tests
- 🗺️ Architecture
- 📚 Docs

---

<a id="quick-start"></a>
## ⚡ Quick start

```powershell
git clone https://github.com/hermes-webui/hermes-android.git
cd hermes-android
.\gradlew.bat assembleDebug --no-daemon
```

Open the repo root in Android Studio for emulator/device runs.

Requirements:

- Android Studio with Android SDK 37
- JDK 17 or newer runtime compatible with Gradle
- A reachable HTTP or HTTPS Hermes WebUI URL

---

<a id="features"></a>
## ✨ Features

### 🧩 Native shell

- Kotlin + Jetpack Compose Android app
- Hardened WebView for Hermes WebUI
- Android WebView compatibility fixes for Hermes WebUI viewport rendering
- Android WebView compatibility shim that re-caps Hermes WebUI floating/long-press menu height and the generated update summary panel and scroll container so they render full-size instead of collapsing (Android WebView treats CSS `vh` units as `0` here) while preserving vertical page scrolling for expandable content
- Default WebView HTTP/service-worker caching with smoother reload rendering
- System-bar inset handling so WebView content and native controls avoid status and navigation bars
- Android WebView microphone compatibility that forces WebUI voice input through its MediaRecorder path
- WebUI-owned navigation for the Official Hermes Dashboard setting
- Explicitly configured Official Hermes Dashboard links open in a Chrome Custom Tab with minimal browser UI
- Deep link support: `hermes://session/{id}` navigates to Hermes sessions
- OAuth/OIDC sign-in keeps trusted code-flow redirects in-app until the declared `redirect_uri` callback returns to the configured Hermes WebUI origin, preserving PKCE state for self-hosted providers that bounce across login and consent pages before returning to Hermes WebUI
- Cold-start restore keeps the active Hermes session/workspace route when the app process is restarted, with a WebUI-origin-scoped workspace-button recovery fallback that reloads the last known in-app session route if the panel is tapped from a blank root state
- Short app switches now preserve the current WebView and briefly favor silent reconnect over immediately replacing the page with the native error screen; if Hermes does not recover before the grace window expires, the normal error UI appears promptly instead of waiting for a later retry probe
- If the background activity toggle is enabled, Android can keep a trusted session-scoped foreground notification alive while the app is backgrounded: reconnect windows stay alive after an app switch, and trusted `/api/session/stream` summaries can refresh the latest agent activity text with a lock-screen redaction option
- Server health probing on WebView errors to distinguish server-down from content errors
- First-run settings flow for the Hermes WebUI URL with an inline readiness check that rejects unreachable, setup-mode, or non-Hermes targets before saving them; auth-protected `/api/status` responses can still pass when the root page fingerprints as Hermes so signed-in WebView sessions are not blocked
- Saved server rows check connection health before switching, show reachable/sign-in/setup/offline/not-Hermes results, and ask for confirmation before clearing the current WebView session
- Native app settings are available from the WebUI sidebar as an injected Application Settings entry immediately after the WebUI Settings item, and from the `hermes://app/settings` recovery deep link when the WebView route is stuck
- Back handling, pull-to-refresh, loading, offline, and error states, including direct server-URL recovery from the native error screen

### SSE capability mapping

When users run **Check SSE support now** in native settings, Android classifies server capability with this precedence:

1. `/api/status` advertises a truthy SSE/session flag -> `SESSION_SSE_ENABLED`
2. `/api/sessions/gateway/stream?probe=1` returns `enabled=true` and `ok=true` -> `SESSION_SSE_ENABLED`
3. `/api/sessions/events` returns `2xx` with `Content-Type: text/event-stream` -> `RECONNECT_STREAM_AVAILABLE`
4. Gateway probe reports `enabled=false` or HTTP `404` (and reconnect stream not usable) -> `FEATURE_DISABLED`
5. Otherwise -> `NONE` (network/unexpected response)

Operational note:

- Android reconnect transport only needs the lightweight `/api/sessions/events` stream.
- Full gateway/session SSE remains preferred for richer cross-client event contracts.

### 🔌 Android integration

- File upload and download support, including direct camera capture when a page requests image capture
- Share-to-app intake for text and files
- Microphone capture support for trusted Hermes WebUI pages
- Android-backed browser notifications for Hermes WebUI completion alerts
- App update alerts use the same Hermes updates notification channel: Google Play builds check Play-managed updates, and GitHub APK builds check GitHub Releases
- Opt-in ongoing background activity notification for trusted Hermes sessions, with lock-screen redaction control
- Notification tray approval actions for trusted Hermes sessions, with queue-head validation before Android submits a response
- Optional troubleshooting debug-log capture with a persistent foreground notification and one-tap Stop action
- Safe app-owned diagnostic breadcrumbs for startup validation, server health checks, server switching, and main-frame WebView failures are included in exported debug logs without cookies, auth headers, query strings, response bodies, or token-bearing URLs
- Cookie-backed WebView session persistence
- Encrypted local settings storage
- Native app identity, launcher icon, splash, and settings surface

### 🛡️ Security

- HTTP/HTTPS URL validation
- Host allowlist for in-app navigation
- External browser handoff for non-allowlisted HTTP/HTTPS links
- WebView microphone grants are limited to trusted Hermes WebUI pages and audio capture only (with Android `RECORD_AUDIO` + `MODIFY_AUDIO_SETTINGS` permissions)
- Android seeds WebUI's MediaRecorder microphone fallback for the configured Hermes origin only
- WebUI notification grants and delivery are scoped to the configured Hermes origin, require Android notification permission when applicable, and route taps only to allowlisted Hermes WebUI URLs
- Cleartext traffic permitted so configured HTTP deployments can load; HTTPS remains recommended outside trusted local networks
- Hardened WebView defaults and SSL-error cancellation

---

<a id="configuration"></a>
## ⚙️ Configuration

Default endpoints live in:

- `app/src/main/res/values/strings.xml`

Important values:

- `default_server_url` - default Hermes WebUI URL
- `default_dashboard_url` - optional local Official Hermes Dashboard origin used only for Android-side Custom Tab matching when explicitly configured; leave blank so WebUI owns auto-detect and persistence
- `app_name` - Android launcher label

The shipped WebUI default is a placeholder HTTPS origin and the shipped dashboard default is blank. Configure your real Hermes WebUI URL in app settings on first run; both `http://` and `https://` URLs are accepted.

Android identity lives in:

- `app/build.gradle.kts` - `namespace` and `applicationId`
- `settings.gradle.kts` - Gradle project name
- `app/src/main/AndroidManifest.xml` - launcher, permissions, intent filters

Release signing is wired for both local builds and GitHub Actions without
committing secrets.

### Local signed release setup

1. Copy `keystore.properties.example` to `keystore.properties`.
2. Fill in your real keystore path and passwords.
3. Keep `keystore.properties` untracked.

Example `keystore.properties` values:

```properties
storeFile=C:/path/to/upload-keystore.jks
storePassword=replace-me
keyAlias=upload
keyPassword=replace-me
```

With that file present, release APK builds automatically sign the output.

```powershell
Copy-Item .\keystore.properties.example .\keystore.properties
.\gradlew.bat :app:stageGithubReleaseApk --no-daemon
```

If signing values are missing, `release` tasks fail fast with a clear message
instead of producing unsigned distribution artifacts.

### GitHub Actions signed release setup

Add these repository secrets before running the release workflow:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_BASE64`

To create the Base64 keystore value on Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:/path/to/upload-keystore.jks")) | Set-Clipboard
```

The workflow in `.github/workflows/1-orchestration-release.yml` can then:

- build and sign one GitHub release APK with application ID `com.hermeswebui.android.github`
- build and sign one Play release AAB with application ID `com.hermeswebui.android`
- package release native debug symbol tables for Play Console crash and ANR symbolication
- upload both files as workflow artifacts
- fan out to separate GitHub and Play publishing jobs after the build succeeds
- create or update a GitHub Release using the Gradle Android `versionName`
- attach only the GitHub APK to the GitHub Release
- upload only the Play AAB to the Google Play internal testing track
- include a human-readable GitHub Release body generated from merged PRs and grouped by `.github/release.yml`
- keep build diagnostics such as version, tag, commit, artifact name, SHA-256, and workflow run in the Actions job summary
- include a brief Play Store `en-US` What's New changelog generated from the same GitHub release notes, capped below the Play text limit, and ending with `Report issues through the in-app bug report tool.`
- fail a tag release when the tag, such as `v0.1.8`, does not match the Android `versionName`
- keep release notes focused on what users and testers need to know

The publish steps live in reusable/manual repair workflows:

- `.github/workflows/2-publish-github-apk.yml` publishes the APK artifact to GitHub Releases.
- `.github/workflows/3-publish-play-store-release.yml` uploads the AAB artifact to Google Play internal testing.

If either publish target fails after the build artifact upload succeeds, rerun
only the failed publish workflow manually with the build run ID, artifact name,
version name, and commit SHA shown in the failed release run.

See [RELEASE.md](./RELEASE.md) for the release operator checklist.

Google Play listing assets:

- `play-store/icon-512.png` - 512x512 high-res app icon for Play Console store listing (opaque PNG)
- `play-store/icon-512.svg` - editable vector source used for Play listing export
- `tools/generate_play_store_icon.py` - regenerates both files from the current Hermes WebUI brand SVG source
- `tools/requirements-play-icon.txt` - Python dependency list for the Play icon generation script

Release artifact naming:

```powershell
.\gradlew.bat :app:stageGithubReleaseApk --no-daemon
```

When signing is configured, this stages the signed GitHub distribution APK under
Gradle's ignored build output directory with the product name, version, and
release channel:

- `build/release/hermes-webui-v<version>-github.apk` - GitHub/device APK artifact
- `build/release/hermes-webui-v<version>.aab` - Play internal testing upload artifact

The GitHub APK is a separate Android app variant: it installs as
`com.hermeswebui.android.github`, displays as "Hermes WebUI GitHub", and reports
`<version>-github` from inside the app. The Play AAB keeps the official
`com.hermeswebui.android` application ID and plain `<version>` version name, so
both channels can be installed on the same device at the same time.
Both release channels share the same native app update alert setting but use
build-selected providers: the Play build checks Google Play in-app update
availability, while the GitHub build checks the latest GitHub Release, shows a
short What's Changed summary, and can download the published GitHub APK asset.
Automatic checks wait until the app has been open for about one minute and run
at most once per day, and can be disabled separately from manual checks; manual
checks in Settings run immediately. Debug builds do not advertise an update
provider.

Manual runs of `.github/workflows/1-orchestration-release.yml` auto-bump
`appVersionName` from the latest published GitHub release tag (`vX.Y.Z ->
vX.Y.(Z+1)`), update this README's current-version metadata, commit those
changes back to `main`, and then build from that version-bump commit.
`versionCode` is derived from that semantic version in Gradle, so it stays
monotonic without separate manual edits.

Tag-triggered runs still require the pushed tag to match the Gradle
`versionName` exactly, for example `v0.1.8`.

---

<a id="running-tests"></a>
## 🧪 Running tests

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat assembleDebug --no-daemon
```

Optional checks:

```powershell
.\gradlew.bat lint --no-daemon
.\gradlew.bat connectedDebugAndroidTest --no-daemon
```

---

<a id="architecture"></a>
## 🗺️ Architecture

> 🎨 Color key: 🔵 platform boundary · 🟣 security · 🟢 data · 🟠 domain · 🟡 UI · 🔴 tests

| Color | Area | Files | Purpose |
|---|---|---|---|
| 🔵 | Platform boundary | `app/src/main/java/com/hermeswebui/android/MainActivity.kt` | Main Hermes WebUI host, Android intents, WebView lifecycle, and dashboard Custom Tab handoff |
| 🟣 | Security | `app/src/main/java/com/hermeswebui/android/core/security/UrlPolicy.kt` | HTTP/HTTPS and allowlist decisions |
| 🟢 | Data | `app/src/main/java/com/hermeswebui/android/data/` | Encrypted app settings and staged share payloads |
| 🟠 | Domain | `app/src/main/java/com/hermeswebui/android/domain/` | URL validation and Android share intent parsing |
| 🟡 | UI | `app/src/main/java/com/hermeswebui/android/ui/` | Compose screens and ViewModel state |
| 🔴 | Tests | `app/src/test/java/com/hermeswebui/android/` | Unit coverage for URL policy, validation, and ViewModel load-state behavior |

See [ARCHITECTURE.md](./ARCHITECTURE.md) for the design notes and extension
points.

---

<a id="docs"></a>
## 📚 Docs

- [ROADMAP.md](./ROADMAP.md) - status, wishlist, forward work, and progress
- [ARCHITECTURE.md](./ARCHITECTURE.md) - runtime flow and security model
- [RELEASE.md](./RELEASE.md) - release workflow checklist and retry procedure
- [ISSUE_10_BACKGROUND_EXECUTION_PROPOSAL.md](./docs/proposals/ISSUE_10_BACKGROUND_EXECUTION_PROPOSAL.md) - phased implementation proposal for background continuity, ongoing activity notifications, and tray approvals (Issue 10)
- [ISSUE_10_BACKGROUND_EXECUTION_WORKPLAN.md](./docs/proposals/ISSUE_10_BACKGROUND_EXECUTION_WORKPLAN.md) - execution workplan with staged PR slices, decision gates, and verification targets for Issue 10
- [ISSUE_10_STAGE0_DISCOVERY.md](./docs/proposals/ISSUE_10_STAGE0_DISCOVERY.md) - Stage 0 discovery notes capturing verified current behavior, required contracts, and decision checklist before implementation
- [AGENTS.md](./AGENTS.md) - instructions for AI assistants working in this repo
