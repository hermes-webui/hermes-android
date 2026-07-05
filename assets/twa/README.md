# TWA handover: server-hosted Digital Asset Links
This folder is a **handover artifact**, not part of the Android app build. It
exists because the Hermes WebUI GitHub project controls the files every user
deploys, so it can ship a Digital Asset Links file that lets the Android app run
as a Trusted Web Activity (TWA) -- rendering in **real Chrome** instead of the
embedded Android System WebView.
## Why this is interesting
The embedded WebView occasionally diverges from Chrome (e.g. the long-press menu
compositing/z-index quirk fixed in Issue 6). A TWA renders with the user''s Chrome
engine, so those WebView-only quirks disappear.
## The catch (read before adopting)
A TWA is **not** a drop-in replacement for the current WebView app. It would lose
the native bridges this app is built around unless each is re-implemented:
- microphone permission bridging
- browser-notification bridge
- file upload / download wiring
- Android share-intent handling
- hermes:// deep links
- session reset (cookie/storage/cache clearing)
- biometric lock
Treat TWA as a **future spike / alternative variant**, not a swap. The WebView
remains the integrated app.
## How verification works (and why the app cannot self-grant it)
Verification is a two-way handshake that **Chrome** checks, not the app:
1. App -> site: the app manifest declares the trusted origin (shippable in this
   repo when/if a TWA variant is built).
2. Site -> app: the server must serve this assetlinks.json at
   https://<host>/.well-known/assetlinks.json, listing the app package name and
   the signing-cert SHA-256 fingerprint(s).
The app physically cannot place a file on a user remote server at launch, and
self-declaring proves nothing because Chrome fetches and verifies the file from
the server independently. Hosting it in Hermes WebUI static assets is the
correct, scalable path: every deployment then auto-verifies.
## Deployment notes for the Hermes WebUI repo
Serve assetlinks.json at exactly:
    https://<host>/.well-known/assetlinks.json
Requirements:
- **HTTPS only.** TWA verification does not work over plain HTTP, so HTTP
  deployments fall back to a Custom Tab (URL bar visible). This app still
  supports HTTP hosts via the WebView path.
- **Publicly accessible - no auth.** The /.well-known/assetlinks.json route must
  return the JSON **without** a login redirect or auth gate, with
  Content-Type: application/json.
- One file per origin; it may list multiple apps/fingerprints in the array.
## Fingerprints
assetlinks.json currently lists the **local release/upload key** fingerprint:
    48:8D:B7:0A:54:58:33:DF:BF:31:54:C7:CB:11:E4:23:0C:70:53:72:7D:3E:07:DB:99:3A:CA:E7:3A:96:35:54
Regenerate it any time with:
    keytool -list -v -keystore <storeFile> -alias <keyAlias>
IMPORTANT: If the app is distributed through Google Play with Play App Signing,
the on-device signing certificate is Google''s, not this upload key. In that case
add the Play App Signing SHA-256 (Play Console -> Test and release -> App
integrity -> App signing key certificate) to the sha256_cert_fingerprints array
as well, so both the GitHub-distributed APK and the Play build verify.
## Validate after hosting
Use the Google Digital Asset Links generator/tester, or fetch directly and
confirm the JSON body and content type:
    curl -s https://<host>/.well-known/assetlinks.json
