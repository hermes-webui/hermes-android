# Hermes-Android 🤖📱

Hermes-Android is the native Android companion for
[Hermes Web UI](https://github.com/nesquena/hermes-webui). It keeps the
Hermes web app as the primary interface and adds the Android pieces that should
live on-device: secure WebView hosting, native navigation, sharing, downloads,
and encrypted local settings.

> 🔒 HTTP/HTTPS URL policy · 🌐 host allowlist · 📂 sharing + downloads · 🧊 encrypted settings

The app is intentionally thin. Hermes behavior stays server-delivered through
WebUI, while this repo owns Android integration and device safety.

---

## 🧪 Looking for Internal Testers!

We're looking for internal testers for the first pre-release build on Google Play.

To get added, **message [@Paladin173](https://github.com/Paladin173) your Gmail address** and we'll send you a Play Store invite.

Once added, the app will appear in the Play Store for you to install and receive automatic updates.

> Requires a running [Hermes WebUI](https://github.com/nesquena/hermes-webui) instance. Enter your server URL on first launch.

---

## 🧭 Contents

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
- System-bar inset handling so WebView content and native controls avoid status and navigation bars
- Android WebView microphone compatibility that forces WebUI voice input through its MediaRecorder path
- WebUI-owned navigation with Android seeding the WebUI Official Hermes Dashboard origin when needed
- Official Hermes Dashboard links open in a Chrome Custom Tab with minimal browser UI instead of the full default browser
- Deep link support: `hermes://session/{id}` navigates to Hermes sessions
- Server health probing on WebView errors to distinguish server-down from content errors
- First-run settings flow for the Hermes WebUI URL; the dashboard URL is managed by WebUI Settings > System after Android seeds it
- Back handling, pull-to-refresh, loading, offline, and error states

### 🔌 Android integration

- File upload and download support
- Share-to-app intake for text and files
- Microphone capture support for trusted Hermes WebUI pages
- Cookie-backed WebView session persistence
- Encrypted local settings storage
- Native app identity, launcher icon, splash, and settings surface

### 🛡️ Security

- HTTP/HTTPS URL validation
- Host allowlist for in-app navigation
- External browser handoff for non-allowlisted HTTP/HTTPS links
- WebView microphone grants are limited to trusted Hermes WebUI pages and audio capture only (with Android `RECORD_AUDIO` + `MODIFY_AUDIO_SETTINGS` permissions)
- Android seeds WebUI's MediaRecorder microphone fallback for the configured Hermes origin only
- Cleartext traffic permitted so configured HTTP deployments can load; HTTPS remains recommended outside trusted local networks
- Hardened WebView defaults and SSL-error cancellation

---

<a id="configuration"></a>
## ⚙️ Configuration

Default endpoints live in:

- `app/src/main/res/values/strings.xml`

Important values:

- `default_server_url` - default Hermes WebUI URL
- `default_dashboard_url` - default Official Hermes Dashboard origin URL seeded into WebUI config when WebUI has no dashboard URL; path/query fragments are stripped before Android storage
- `app_name` - Android launcher label

The shipped defaults are placeholder HTTPS origins. Configure your real Hermes WebUI URL in app settings on first run; both `http://` and `https://` URLs are accepted.

Android identity lives in:

- `app/build.gradle.kts` - `namespace` and `applicationId`
- `settings.gradle.kts` - Gradle project name
- `app/src/main/AndroidManifest.xml` - launcher, permissions, intent filters

Release signing is not wired yet. Track that work in [ROADMAP.md](./ROADMAP.md)
before adding signing config, and keep secrets out of the repository.

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
| 🔴 | Tests | `app/src/test/java/com/hermeswebui/android/` | Unit coverage for URL and validation logic |

See [ARCHITECTURE.md](./ARCHITECTURE.md) for the design notes and extension
points.

---

<a id="docs"></a>
## 📚 Docs

- [ROADMAP.md](./ROADMAP.md) - status, wishlist, forward work, and progress
- [ARCHITECTURE.md](./ARCHITECTURE.md) - runtime flow and security model
- [AGENTS.md](./AGENTS.md) - instructions for AI assistants working in this repo
