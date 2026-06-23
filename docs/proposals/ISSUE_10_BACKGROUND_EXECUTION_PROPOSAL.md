# Issue 10 Proposal: Background continuity + activity notifications

Related issue: https://github.com/hermes-webui/hermes-android/issues/10

## Goal

Improve Android background behavior in staged increments:

1. Reduce visible reconnect/reload friction when users return to the app.
2. Optionally surface in-progress agent activity as an ongoing Android notification.
3. Optionally allow approval actions from the notification tray.

This proposal keeps Hermes-Android as a thin, secure wrapper and avoids duplicating WebUI product logic.

## Why this is staged

Issue 10 contains three different implementation sizes and risk levels.
Bundling them as a single ship target increases schedule and regression risk.

- Part A (resume polish): low-to-medium complexity
- Part B (background activity notification): medium-to-high complexity
- Part C (tray approval actions): high complexity

## Current baseline in this repo

- `MainActivity` already keeps one `WebView` instance per activity lifetime.
- There is no Android foreground service, no `FOREGROUND_SERVICE*` permissions, and no service declaration.
- Native notifications are currently driven by the loaded WebUI page via `WebMessageListener` (foreground-context bridge).
- Native Hermes API usage is currently limited to public `/api/status` liveness checks.

## Proposed delivery plan

## Phase A: Foreground resume polish (Issue 10 Part A)

### Scope

- Minimize visible reconnect flash when returning from brief backgrounding.
- Preserve current security model and origin scoping.
- Do not add persistent background service yet.

### Candidate implementation

- Audit lifecycle transitions (`onPause`/`onStop`/`onResume`) to avoid unnecessary reload or state reset paths.
- Add lightweight telemetry/debug logging hooks (guarded by debug builds) during development only.
- Confirm `MainViewModel` retry loop interaction does not trigger avoidable user-visible loading overlays on resume.

### Acceptance criteria

- Returning from app switcher after short background period does not force a full visible hard reset of the page in normal conditions.
- Existing deep link, share intent, and notification tap routing continue to work.
- No regression in current reconnect handling on genuine offline/server-down paths.

### Estimate

- 2 to 4 engineering days including validation.

## Phase B: Optional ongoing background activity notification (Issue 10 Part B)

### Scope

- Add an opt-in setting for background activity notification.
- While active and backgrounded during agent execution, show/update an ongoing notification with concise activity summary.
- Keep notification target URLs constrained to allowlisted WebUI routes.

### Candidate implementation

- Introduce a dedicated Android foreground service for active-turn windows only.
- Add required manifest permissions and service declaration.
- Add settings persistence key + UI toggle.
- Define a native background activity feed contract:
  - Preferred: authenticated stream/endpoint explicitly supported for background native clients.
  - Fallback: periodic lightweight polling endpoint if SSE is not practical natively.
- Reuse existing notification channel where possible, with separate notification ID strategy for ongoing status.

### Acceptance criteria

- With toggle enabled and an active agent turn, backgrounding app starts/maintains ongoing notification.
- Notification text updates with latest safe summary payload.
- Tapping notification returns user to the active Hermes session route.
- Service stops and notification clears when work completes, user disables toggle, or trust checks fail.

### Estimate

- 1 to 2 weeks depending on API readiness for authenticated background consumption.

## Phase C: Optional tray approvals (Issue 10 Part C)

### Scope

- Add notification action buttons (for example: allow once / deny) when approval prompts are pending.
- Execute action securely against configured trusted Hermes server only.

### Candidate implementation

- Add notification action `PendingIntent` receiver flow.
- Add request correlation model for active approval prompt IDs.
- Add secure native API call path for approval response submission.
- Add idempotency and stale-prompt handling (ignore or fail closed on mismatch).

### Acceptance criteria

- User can action a pending approval without foregrounding app.
- Action is applied once, against the correct pending prompt, and result is reflected in subsequent status.
- Misrouted, expired, or untrusted action payloads are rejected.

### Estimate

- 3 to 5 engineering days after Phase B foundation and API contract are stable.

## Required cross-repo/API decisions before Phase B/C

1. Confirm supported authenticated background data path (SSE vs polling endpoint).
2. Confirm approval-response endpoint and payload contract for native clients.
3. Confirm expected user-visible summary format and truncation policy.
4. Decide product default for toggle (`off` recommended initially).

## Security and privacy constraints

- Preserve host allowlist and configured-origin trust boundary.
- Do not introduce secret-bearing JavaScript bridges.
- Avoid persisting sensitive conversation data beyond what notification UX needs.
- Keep notification contents minimal and user-controlled (toggle + future redaction options if needed).

## Testing strategy

- Unit tests for state transitions, settings flags, and summary formatting.
- Integration tests for notification deep-link routing and allowlist enforcement.
- Manual device matrix checks for Android 13/14+, app switch behavior, and OEM background constraints.
- Regression pass for existing WebView flows (share, uploads, microphone, notification bridge, deep links).

## Rollout strategy

1. Ship Phase A first as a low-risk UX improvement.
2. Behind setting toggle, ship Phase B to gather battery/behavior feedback.
3. Ship Phase C only after B stabilizes and approval API contract is validated.

## Out of scope

- Replacing the primary WebView UX with native activity/session screens.
- Broad WebUI product behavior redesign.
- Always-on 24/7 background service when no agent work is active.

---

## Detailed implementation blueprint

This section maps each phase to concrete code slices so implementation can be split into safe, reviewable PRs.

## Phase A blueprint (lowest-risk MVP)

Objective: improve return-to-app continuity without introducing foreground-service complexity.

### File touchpoints

- `app/src/main/java/com/hermeswebui/android/MainActivity.kt`
  - Harden lifecycle behavior around `onPause`, `onStop`, and `onResume` to avoid unnecessary reload/reset paths.
  - Keep reconnect UX subtle when a fast WebUI soft-recovery is already in progress.
- `app/src/main/java/com/hermeswebui/android/ui/MainViewModel.kt`
  - Add explicit foreground/background intent signals if needed to separate true load errors from transient background throttling.
  - Ensure auto-retry loop does not produce avoidable UI churn after quick app switches.
- `app/src/test/java/com/hermeswebui/android/MainViewModelTest.kt`
  - Add tests for resume behavior and auto-retry state transitions after simulated background/foreground cycles.

### PR slices

- PR A1: add state plumbing for foreground/background-aware retry behavior.
- PR A2: refine activity lifecycle handling and reconnect UI transitions.
- PR A3: add/adjust tests and docs notes for new behavior.

### Acceptance gate

- No full-screen hard-reset flash on short app switch in common conditions.
- Existing error-screen behavior still appears for genuine offline/server-down failures.
- No regressions in deep links, share intake, or notification tap routing.

---

## Phase B blueprint (opt-in ongoing activity notification)

Objective: while app is backgrounded and agent work is active, run limited foreground service and show ongoing status notification.

### File touchpoints

- `app/src/main/AndroidManifest.xml`
  - Add `FOREGROUND_SERVICE` and Android-version-appropriate foreground-service permissions.
  - Register foreground service component.
- `app/src/main/java/com/hermeswebui/android/MainActivity.kt`
  - Start/stop service based on explicit active-turn signals and user toggle.
  - Keep allowlist and trusted-route enforcement for notification targets.
- `app/src/main/java/com/hermeswebui/android/data/SettingsRepository.kt`
  - Add migration version bump and persisted toggle for background activity notification preference.
- `app/src/main/java/com/hermeswebui/android/ui/settings/SettingsBottomSheet.kt`
  - Add opt-in switch/row for background activity notification behavior.
- `app/src/main/java/com/hermeswebui/android/data/HermesApiClient.kt`
  - Extend with background-safe activity fetch method after API contract is confirmed.
- New file candidates
  - `app/src/main/java/com/hermeswebui/android/service/AgentActivityForegroundService.kt`
  - `app/src/main/java/com/hermeswebui/android/notifications/AgentActivityNotificationFormatter.kt`
  - `app/src/main/java/com/hermeswebui/android/notifications/AgentActivityNotificationController.kt`
  - `app/src/main/java/com/hermeswebui/android/data/AgentActivityClient.kt`

### PR slices

- PR B1: manifest + service scaffold + minimal ongoing notification shell.
- PR B2: settings toggle + persistence + migration.
- PR B3: background activity feed integration and notification summary updates.
- PR B4: lifecycle integration and stop conditions.

### Acceptance gate

- Toggle off: no background service behavior change.
- Toggle on and active work: ongoing notification appears and updates while app is backgrounded.
- Tap action returns to trusted active session route.
- Service exits when no work is active, toggle disabled, or trust checks fail.

---

## Phase C blueprint (tray approvals)

Objective: allow constrained approval actions from notification tray while preserving trust boundaries.

### File touchpoints

- `app/src/main/AndroidManifest.xml`
  - Register action receiver if using broadcast action intents.
- New file candidates
  - `app/src/main/java/com/hermeswebui/android/approvals/ApprovalActionReceiver.kt`
  - `app/src/main/java/com/hermeswebui/android/approvals/ApprovalActionDispatcher.kt`
  - `app/src/main/java/com/hermeswebui/android/data/ApprovalClient.kt`
- Existing files likely touched
  - `app/src/main/java/com/hermeswebui/android/MainActivity.kt`
  - `app/src/main/java/com/hermeswebui/android/service/AgentActivityForegroundService.kt`

### PR slices

- PR C1: notification action wiring and pending-intent routing.
- PR C2: secure approval submission path and request correlation checks.
- PR C3: stale/duplicate action handling and UX confirmation.

### Acceptance gate

- Valid tray action applies once to the correct pending approval request.
- Untrusted/expired/mismatched actions fail closed.
- Resulting state reflected in follow-up notification update or app resume.

---

## API contract checklist (must confirm before B/C)

- Activity feed source for background clients
  - SSE endpoint shape and auth mechanism, or
  - polling endpoint returning latest summary payload.
- Approval API
  - request identifier format, response payload, and idempotency guarantees.
- Auth strategy
  - how native background requests authenticate without weakening security.
- Payload constraints
  - what text is safe for notification body and max expected size.

If these contracts are not finalized, Phase A should still proceed while B/C stay behind design hold.

---

## Verification matrix by phase

### Phase A

- Unit tests: reconnect state transitions after simulated background/resume.
- Manual: quick app switch, long app switch, network-drop resume.

### Phase B

- Unit tests: formatter truncation, toggle persistence behavior.
- Integration/manual: service lifecycle, notification update cadence, tap routing.
- Device checks: Android 13+ notification permission and Android 14+ foreground-service compliance.

### Phase C

- Unit tests: approval action validation and stale action rejection.
- Integration/manual: approve/deny from tray, duplicate taps, expired prompt race cases.

---

## Suggested execution order

1. Implement and ship Phase A first.
2. Hold B/C merge until API/auth contract decisions are explicitly signed off.
3. Implement B behind toggle and gather real-device feedback.
4. Add C only after B behavior is stable across device matrix.

---

## Estimated schedule (single engineer, focused)

- Phase A: 2 to 4 days
- Phase B: 1 to 2 weeks
- Phase C: 3 to 5 days

Total for A+B+C: approximately 1.5 to 3 weeks depending on API readiness and OEM behavior variance.


