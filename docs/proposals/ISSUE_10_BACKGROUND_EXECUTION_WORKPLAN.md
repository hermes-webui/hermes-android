# Issue 10 Workplan: Background continuity execution plan

Related issue: https://github.com/hermes-webui/hermes-android/issues/10

Stage 0 tracking doc: `docs/proposals/ISSUE_10_STAGE0_DISCOVERY.md`

## Purpose

This workplan turns the high-level proposal into an execution sequence with explicit
PR slices, decision gates, and verification steps.

This plan keeps Hermes-Android a thin native wrapper: Android owns lifecycle,
foreground-service behavior, and notifications; Hermes WebUI remains the product
surface and source of conversation behavior.

## Current status

- Stage 0 is in progress (discovery + contract decisions).
- Stage 1 / Part A is complete: lifecycle signal plumbing, reconnect UX polish, a bounded background reconnect hold, tests, and docs updates have landed on `issue-10-background-continuity-plan`.
- Stage 2 / Part B is in progress:
  - B1 complete: manifest + foreground-service skeleton + baseline reconnect notification.
  - B2 complete: opt-in background reconnect toggle, encrypted persistence migration, and native settings page integration.
  - B3 partially complete: lightweight SSE-backed reconnect now uses `/api/sessions/events`, and the reconnect foreground service consumes authenticated `/api/session/stream` summaries for the active session when it has a trusted route plus WebView cookies; broader background activity coverage and approval flows still remain.
  - B4 can proceed in parallel (lifecycle start/stop hardening + regression validation).

## Outcome targets

1. Reduce app-switch resume friction without introducing risky background behavior.
2. Add an optional, battery-aware ongoing activity notification path.
3. Keep trust boundaries intact (allowlist, origin scope, no secret JS bridge).

## Delivery strategy

### Stage 0: Discovery and guardrails (1-2 days)

- Confirm exact activity payload source for native background consumption:
  - preferred: authenticated SSE stream that can be consumed natively
  - fallback: lightweight latest-activity polling endpoint
- Confirm summary payload shape:
  - assistant text snippet
  - optional latest tool-call metadata
  - active session route
- Confirm product defaults:
  - background activity notification toggle default (`off` recommended)
  - foreground service only while turn is active (not always-on)
- Define notification redaction/truncation policy for lockscreen privacy.

Exit criteria:
- API/auth contract is explicit enough for implementation.
- Open unknowns are captured as TODOs and owner-assigned.

### Stage 1: Part A (resume continuity polish) (2-4 days)

Scope:
- Improve resume behavior first, without adding a service.

Implementation focus:
- Audit lifecycle flow in `MainActivity.kt` (`onPause`, `onStop`, `onResume`).
- Ensure app switch/resume does not trigger avoidable WebView reset churn.
- Keep current error/retry semantics for true offline/server-down cases.

Acceptance:
- Short app switch does not produce a full hard-reset flash in common conditions.
- Existing deep links, share intake, and notification tap routing still work.
- No regression in offline recovery behavior.

PR slices:
- A1: lifecycle signal plumbing and resume-state refinement.
- A2: reconnect UX polish and retry-loop interaction updates.
- A3: tests and docs updates.

### Stage 2: Part B (opt-in ongoing activity notification) (1-2 weeks)

Scope:
- Add optional foreground service that runs only while active work is in flight
  and app is backgrounded.

Implementation focus:
- Manifest updates:
  - `FOREGROUND_SERVICE`
  - `FOREGROUND_SERVICE_DATA_SYNC` (Android 14+ compliance path)
  - foreground service declaration
- Service scaffold:
  - start/stop based on active-turn state and user toggle
  - consume background activity feed
  - update an ongoing notification with safe summary text
- Settings:
  - add toggle persistence in `SettingsRepository.kt` with migration bump
  - add UI control in `SettingsBottomSheet.kt`
- Navigation:
  - notification tap deep-links to `hermes://session/{id}`
  - route validation remains allowlist-gated

Acceptance:
- Toggle off: no service behavior changes.
- Toggle on + active turn + app backgrounded: ongoing notification appears and updates.
- Notification tap returns to trusted active session route.
- Service exits when turn ends, toggle is disabled, or trust checks fail.

PR slices:
- B1: manifest + service skeleton + baseline ongoing notification.
- B2: settings toggle + migration + tests.
- B3: activity feed integration + formatter + update cadence.
- B4: lifecycle stop/start hardening + regression validation.

### Stage 3: Part C (optional tray approvals) (3-5 days after Stage 2)

Scope:
- Add notification actions for approval flows only after Stage 2 is stable.

Implementation focus:
- PendingIntent action routing and secure receiver path.
- Correlate action to active approval request IDs.
- Fail closed on stale, mismatched, or untrusted payloads.

Acceptance:
- Valid tray action applies once to correct request.
- Duplicate/stale/untrusted actions are rejected safely.
- Follow-up notification/app state reflects resulting status.

## Security and reliability constraints

- Preserve HTTP/HTTPS configured-host support and host allowlist enforcement.
- Keep non-web schemes blocked.
- Do not introduce secret-bearing JavaScript interfaces.
- Keep notification bridge/service handling scoped to trusted Hermes WebUI origin.
- Scope foreground service usage to active work windows to reduce battery impact.

## Testing matrix

- Unit tests:
  - lifecycle state transitions for resume continuity
  - settings migration and toggle persistence
  - notification summary formatting/truncation
- Integration tests/manual:
  - deep-link tap routing from notification
  - allowlist rejection paths
  - service start/stop behavior across app foreground/background transitions
  - settings controls: background reconnect toggle persists across app restart
  - settings controls: reconnect polling interval slider persists and applies bounded cadence (1-10 s)
  - settings controls: SSE transport toggle verifies support, prefers `/api/sessions/events` for reconnect, and falls back to polling when the stream is unavailable
- Device checks:
  - Android 13+ notification runtime permission path
  - Android 14+ foreground service behavior
  - OEM variance for app-switch background limits

## Suggested implementation order

1. Ship Stage 1 first (fast UX win, low risk).
2. Continue Stage 2 platform-only slices (B1/B2/B4) behind opt-in setting while Stage 0 API/auth work is finalized.
3. Continue Stage 2 B3 from the current reconnect/session-stream transport toward broader activity coverage and approval-aware summaries once the fuller SSE/API contract is approved and testable.
4. Defer Stage 3 until Stage 2 telemetry/manual validation is healthy.

## In-the-meantime implementation scope (while richer SSE contract is in progress)

- B4.1 lifecycle hardening: ensure reconnect polling is canceled whenever app is backgrounded without an active foreground-service hold (toggle off, trust failure, or reconnect ended).
- B4.2 service/state parity: verify service start/stop is fully derived from (`activityVisible`, `isReconnecting`, `backgroundReconnectEnabled`) and does not drift across rapid app switches.
- B4.3 regression tests: add coverage for toggle-on/off transitions during reconnect and quick foreground/background oscillation. (Initial unit coverage for reconnect keep-alive policy is in place; expand with lifecycle-focused cases.)
- B4.4 docs/status: keep this workplan + roadmap aligned with staged delivery and explicit B3 dependency on SSE contract.
- B4.5 transport preference UX: expose SSE transport toggle in native settings as beta, verify server support on enable, prefer `/api/sessions/events` for reconnect detection, and fail closed back to polling when unsupported.

## Task checklist for this branch family

- [ ] Finalize API/auth contract notes for background activity feed.
- [x] Implement Part A lifecycle/resume polish.
- [x] Add tests for resume/retry transitions.
- [x] Add foreground service scaffold + manifest permissions for Part B.
- [x] Add settings toggle + migration for Part B.
- [~] Implement ongoing notification updates from activity feed (B3: reconnect/session-stream summaries landed for the active session during bounded reconnect; broader feed coverage still pending).
- [ ] Validate tap deep-link routing and trust checks.
- [~] Lifecycle stop/start hardening + regression validation (B4, in progress).
- [ ] Document rollout and default behavior decisions.

