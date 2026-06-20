# Hermes-Android

Hermes-Android is the native Android companion for
[Hermes Web UI](https://github.com/nesquena/hermes-webui). It keeps the
Hermes web app as the primary interface and adds the Android pieces that should
live on-device: secure WebView hosting, native navigation, sharing, downloads,
and encrypted local settings.

The app is intentionally thin. Hermes behavior stays server-delivered through
WebUI, while this repo owns Android integration and device safety.

---

## Contents

- [Quick start](#quick-start)
- [Features](#features)
- [Configuration](#configuration)
- [Running tests](#running-tests)
- [Architecture](#architecture)
- [Docs](#docs)

---

## Quick start

```powershell
git clone https://github.com/hermes-webui/hermes-android.git
cd hermes-android
.\gradlew.bat assembleDebug --no-daemon
```

Open the repo root in Android Studio for emulator/device runs.

Requirements:

- Android Studio with Android SDK 35
- JDK 17 or newer runtime compatible with Gradle
- A reachable HTTPS Hermes WebUI URL

---

## Features

### Native shell

- Kotlin + Jetpack Compose Android app
- Hardened WebView for Hermes WebUI
- Native drawer with WebUI and Dashboard Terminal destinations
- First-run settings flow for WebUI and terminal URLs
- Back handling, pull-to-refresh, loading, offline, and error states

### Android integration

- File upload and download support
- Share-to-app intake for text and files
- Cookie-backed WebView session persistence
- Encrypted local settings storage
- Native app identity, launcher icon, splash, and settings surface

### Security

- HTTPS-only URL validation
- Host allowlist for in-app navigation
- External browser handoff for non-allowlisted HTTPS links
- Cleartext traffic disabled
- Hardened WebView defaults and SSL-error cancellation

---

## Configuration

Default endpoints live in:

- `app/src/main/res/values/strings.xml`

Important values:

- `default_server_url` - default Hermes WebUI URL
- `default_dashboard_terminal_url` - default Dashboard Terminal route
- `app_name` - Android launcher label

Android identity lives in:

- `app/build.gradle.kts` - `namespace` and `applicationId`
- `settings.gradle.kts` - Gradle project name
- `app/src/main/AndroidManifest.xml` - launcher, permissions, intent filters

Release signing is not wired yet. Track that work in [ROADMAP.md](./ROADMAP.md)
before adding signing config, and keep secrets out of the repository.

---

## Running tests

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

## Architecture

| Layer | Files | Purpose |
|---|---|---|
| Platform boundary | `app/src/main/java/com/hermes/wrapper/MainActivity.kt` | WebView setup, intents, file chooser, downloads, navigation hooks |
| Security | `app/src/main/java/com/hermes/wrapper/core/security/UrlPolicy.kt` | HTTPS and allowlist decisions |
| Data | `app/src/main/java/com/hermes/wrapper/data/` | Encrypted app settings and staged share payloads |
| Domain | `app/src/main/java/com/hermes/wrapper/domain/` | URL validation and Android share intent parsing |
| UI | `app/src/main/java/com/hermes/wrapper/ui/` | Compose screens and ViewModel state |
| Tests | `app/src/test/java/com/hermes/wrapper/` | Unit coverage for URL and validation logic |

See [ARCHITECTURE.md](./ARCHITECTURE.md) for the design notes and extension
points.

---

## Docs

- [ROADMAP.md](./ROADMAP.md) - status, wishlist, forward work, and progress
- [ARCHITECTURE.md](./ARCHITECTURE.md) - runtime flow and security model
- [AGENTS.md](./AGENTS.md) - instructions for AI assistants working in this repo
