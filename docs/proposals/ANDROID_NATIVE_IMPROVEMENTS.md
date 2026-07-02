# Hermes-Android — Native Improvement Proposals

> Prepared after a line-by-line committee code review (fixes landed in the
> committee-P2 and committee-P3 changes). These are **Android-native**
> enhancements that fit the "thin, secure companion" scope — none of them move
> product/UI behavior that belongs in Hermes WebUI.
>
> Each item lists **why** (the concrete gap), **scope fit**, rough **effort**,
> and an implementation **sketch** referencing real files. Items already on the
> ROADMAP wishlist are marked `[roadmap]` and are included only where this adds a
> concrete plan or a security rationale to prioritize them.

Legend — effort: S ≈ <½ day, M ≈ 1–2 days, L ≈ 3+ days.

---

## A. Security & privacy (highest value for an agent client)

An agent client renders privileged, long-lived sessions that can approve tool
calls. That raises the value of on-device confidentiality and anti-tamper beyond
a generic WebView wrapper.

### A1. Scope cleartext to the configured host via `network_security_config` — NEW · S
- **Why:** `usesCleartextTraffic` / the cleartext allowance is currently
  app-wide so a configured HTTP deployment can load. That also lets *any*
  in-app cleartext request (redirects, OAuth provider chains, sub-resources)
  ride plain HTTP. The app-level `UrlPolicy` allowlist limits *navigation*, but
  a domain-scoped `network_security_config` limits the *transport* itself.
- **Sketch:** ship a `res/xml/network_security_config.xml` with
  `cleartextTrafficPermitted="false"` as the base and a per-domain override only
  for the configured Hermes host (written at settings-save time, or a
  `<domain-config>` generated from `SettingsRepository`). Everything else becomes
  HTTPS-only. Reference it from `AndroidManifest.xml`
  (`android:networkSecurityConfig`).
- **Risk:** must keep working for HTTP-only self-hosted servers — hence
  *scoped* (allow cleartext for the configured host only), not a blanket flip.

### A2. Optional TLS/certificate pinning for the configured host — NEW · M
- **Why:** the trust boundary is a single configured host; for security-
  conscious/self-hosted deployments, optional pinning closes the residual MITM
  gap that cleartext-permitted + public-CA trust leaves open.
- **Sketch:** a native setting "Pin this server's certificate" that captures the
  leaf/SPKI hash on first successful connect (TOFU) and enforces it via an
  `okhttp` `CertificatePinner` in `HermesApiClient` and a WebView
  `onReceivedSslError` gate. Opt-in, with a clear "certificate changed" recovery
  path (servers rotate certs).
- **Risk:** pinning bricks access on legitimate cert rotation — must be opt-in +
  recoverable.

### A3. Optional `FLAG_SECURE` + recents/app-switcher redaction — NEW · S
- **Why:** agent sessions can contain secrets, approvals, and tool output.
  There is currently no way to prevent screenshots/screen-recording or to hide
  the last frame in the Android app switcher (recents) — a common expectation
  for a "secure" client.
- **Sketch:** a native setting "Block screenshots & hide in recents"; when on,
  `window.setFlags(FLAG_SECURE, FLAG_SECURE)` in `MainActivity`. Optionally
  overlay a neutral splash on `onPause` for the recents thumbnail even when
  `FLAG_SECURE` is off.
- **Risk:** none functional; purely additive and opt-in.

### A4. `[roadmap]` Biometric app-lock before `WebShell` — concrete plan · M
- **Why:** listed as a deferred idea + an `ARCHITECTURE.md` extensibility point.
  It is the single most-requested affordance for a client that stays signed in.
- **Sketch:** gate the `WebShell` composable behind `androidx.biometric`
  `BiometricPrompt`. Add a `SettingsRepository` flag + a "locked" state in
  `MainViewModel`/`MainUiState`; require auth on cold start and after a
  configurable idle/background timeout (reuse the existing
  foreground/background lifecycle hooks that already drive reconnect). On lock,
  keep the WebView non-composed (or `FLAG_SECURE` covered) so content is not
  visible behind the prompt.
- **Risk:** must not lock the user out if biometric hardware is unavailable —
  fall back to device credential (`setAllowedAuthenticators(... or DEVICE_CREDENTIAL)`).

### A5. Close the residual OAuth in-app phishing surface — NEW (from the review) · M
- **Why:** the committee-P2 fix bounded the in-app OAuth flow window, but the
  flow is still gated only by `redirect_uri` → server-origin, so a
  non-allowlisted authorize host renders in the URL-bar-less WebView. Requiring
  the authorize host to be allowlisted was rejected because it breaks legitimate
  external/self-hosted OIDC (`OAuthPopupFlowTest` uses `auth.racci.dev`).
- **Sketch (pick one):**
  1. **Trusted-IdP allowlist** — a native setting listing additional hosts the
     user trusts to render in-app during OAuth (defaults empty; the configured
     Hermes host is always trusted). Non-listed authorize hosts open in a Custom
     Tab (real Chrome, real URL bar) instead of the in-app WebView.
  2. **In-flow host chip** — while `activeMainFrameOAuthFlow != null` and the
     current origin is not allowlisted, show a small, non-dismissable overlay
     chip with the current host (`UrlOrigins.hostFrom`) so the user can see they
     left the Hermes origin.
- **Risk:** option 1 changes OAuth routing — needs testing against a real
  external IdP; option 2 is UI-only and lower-risk.

### A6. `[roadmap]` Session reset / "sign out & wipe" native action — NEW framing · S
- **Why:** the reset path already clears cookies/WebStorage/cache; exposing it
  as an explicit native "Sign out of this server" button (Settings) gives users
  a one-tap way to drop a session on a shared device, complementing A3/A4.
- **Sketch:** a Settings action calling the existing reset routine + optionally
  clearing the active profile's silenced-URL state.

---

## B. Reliability & testing (harden what already exists)

### B1. `[roadmap]` Instrumentation tests wired into the new CI — concrete · M
- **Why:** the roadmap lists "instrumentation tests for WebView navigation and
  intent flows" and there are already Espresso/Compose UI tests under
  `androidTest/`. The CI added in the committee work runs `testDebugUnitTest +
  assembleDebug` only; the intent/deep-link/allowlist paths (the security-
  critical ones the review focused on) have no automated regression gate.
- **Sketch:** add a Gradle-managed-device (or emulator matrix) job to the CI
  running `connectedDebugAndroidTest`; add tests for: deep-link routing
  (`hermes://session/{id}`, `hermes://app/settings`), the exported
  `DOWNLOAD_APP_UPDATE` host confinement (regression for the committee-P2 fix),
  and allowlist externalization.
- **Risk:** emulator jobs are slow/flaky in CI — start nightly, not per-PR.

### B2. Instrument the committee fixes with unit tests — NEW · S
- **Why:** several committee fixes have no direct test: the update-APK host
  allowlist (`isTrustedApkDownloadHost`), the gateway `enabled`-absent vs -false
  distinction, and the profile `isActive`-derivation. These are exactly the
  regressions most likely to silently come back.
- **Sketch:** pure-JVM unit tests (no device): a host-check test, a
  `probeGatewayStreamEndpoint` classification test with `{"ok":true}`, and a
  `SettingsRepository` `setActiveProfile → getProfiles().isActive` test using a
  test `SharedPreferences`.

### B3. Static analysis in CI (detekt/ktlint + Android Lint gate) — NEW · S
- **Why:** the CI added compiles + unit-tests but does not fail on lint/style
  regressions; the codebase is clean today and worth keeping that way.
- **Sketch:** add `detekt` (or ktlint) + `./gradlew lintDebug` as a CI step
  (lint already "reports no issues" per BUILD-002, so gating is cheap).

---

## C. Native UX affordances (small, in-scope, additive)

### C1. App shortcuts (long-press launcher menu) — NEW · S
- **Why:** the app exports `hermes://app/settings` and `hermes://session/{id}`
  but surfaces neither as an Android app shortcut. A long-press on the launcher
  icon could offer "Open Settings" and, if multi-server profiles exist, "Switch
  server".
- **Sketch:** static `res/xml/shortcuts.xml` for Settings; dynamic
  `ShortcutManagerCompat` shortcuts per saved profile.

### C2. Direct Share targets to a recent session — NEW (extends `[roadmap]` direct-share) · M
- **Why:** share-to-app intake exists, but Android's Sharesheet "Direct Share"
  row (share straight to a specific recent Hermes session) is not implemented;
  it is the native half of the roadmap's "direct share-file auto-attach".
- **Sketch:** publish `ShortcutInfoCompat` share-shortcuts for recent trusted
  session routes; map the share target to `hermes://session/{id}` + staged
  attachment.

### C3. Predictive-back + per-app language polish — NEW · S
- **Why:** the app has custom back handling (press-back-twice, BUG-016). Opting
  into the Android 13+ predictive back gesture
  (`android:enableOnBackInvokedCallback="true"` + `OnBackPressedCallback`) and
  declaring `locales_config` for per-app language are cheap platform-alignment
  wins.

---

## Suggested sequencing

1. **A3 (FLAG_SECURE) + A1 (scoped cleartext) + B2 (unit tests for the fixes)** —
   small, high-value, low-risk; can ship together.
2. **A4 (biometric lock)** — the highest-demand security feature; medium effort.
3. **A5 (OAuth phishing residual)** — closes the one residual finding from the
   review; start with option 2 (host chip, UI-only) then evaluate option 1.
4. **B1/B3 (instrumentation + static analysis in CI)** — locks in the review's
   gains against regressions.
5. **C1/C2/C3** — polish, as capacity allows.

Everything above stays inside the Android-wrapper scope defined in `AGENTS.md`
(WebView hosting, permissions, notifications, settings, share/download, deep
links, build/release). Product, layout, and workflow changes remain in Hermes
WebUI.
