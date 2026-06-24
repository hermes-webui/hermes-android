# Issue 10 Stage 0 Discovery (Background continuity)

Related issue: https://github.com/hermes-webui/hermes-android/issues/10

Date: 2026-06-23

## Objective

Capture implementation guardrails and unresolved API/product decisions before
starting Issue 10 Part A/B code changes.

## What is verified in this repo today

- `app/src/main/AndroidManifest.xml`
  - Has `INTERNET`, `ACCESS_NETWORK_STATE`, `MODIFY_AUDIO_SETTINGS`,
    `POST_NOTIFICATIONS`, `RECORD_AUDIO`.
  - Does **not** currently declare foreground-service permissions.
  - Does **not** currently declare a `<service>` for background execution.
- `app/src/main/java/com/hermeswebui/android/MainActivity.kt`
  - Notification bridge exists and is scoped to trusted Hermes WebUI origin:
    `installHermesNotificationWebMessageBridge()`.
  - Existing native notification channel exists:
    `HermesNotificationChannelId = "hermes_webui_notifications"`.
  - Existing deep-link handling exists for `hermes://session/{id}`.
  - `onStop()` currently cancels the auto-retry loop; there is no background
    connection-preservation mechanism.
- `app/src/main/java/com/hermeswebui/android/data/HermesApiClient.kt`
  - Native API support is currently limited to public liveness probe:
    `GET /api/status`.
  - No authenticated background activity feed client exists yet.
- `app/src/main/java/com/hermeswebui/android/data/SettingsRepository.kt`
  - Versioned migration pattern is in place and ready for new settings keys.
- `app/src/main/java/com/hermeswebui/android/ui/settings/SettingsBottomSheet.kt`
  - No user toggle yet for background activity notifications.

## Stage 0 decisions needed before Part B/C

1. **Background activity source contract**
   - Decide whether Android consumes:
     - authenticated SSE stream, or
     - lightweight polling endpoint for latest activity summary.
2. **Authentication path for native background requests**
   - Confirm whether secure cookie/session reuse is sufficient for the selected
     feed path, or whether a dedicated token flow is required.
3. **Summary payload contract**
   - Define minimum payload fields:
     - active session identifier/route
     - assistant summary text (truncated)
     - optional latest tool call summary
4. **Notification privacy policy**
   - Confirm lock-screen safe content policy (full text vs redacted summary).
5. **Product default behavior**
   - Confirm toggle default (`off` recommended for initial rollout).
6. **Service runtime policy**
   - Confirm that foreground service runs only while work is active and app is
     backgrounded (not 24/7).

## Recommendation after reviewing Hermes WebUI

### Recommended direction

- **Part A:** no new WebUI API contract needed. Keep this entirely Android-side
  and focus on lifecycle/resume behavior first.
- **Part B:** prefer a **native-consumable session-scoped SSE feed for coarse
  activity events**, not the existing per-token chat stream as the primary
  Android background source.
- **Fallback/MVP if no native-friendly summary SSE is available yet:** use a
  lightweight polling endpoint for a latest-summary snapshot, but treat that as
  a temporary compromise rather than the target design.

### Why this is the best fit for a thin web wrapper

Hermes-Android should avoid duplicating the WebUI's full in-browser streaming
renderer. The WebUI already separates:

- a **turn-scoped live chat stream** for token-by-token UI rendering, and
- a **session-scoped event stream** for coarse session/background events.

That split strongly suggests Android should consume the same kind of
session-level signals for background notifications, rather than subscribing to
the entire token stream and reconstructing WebUI behavior natively.

### Evidence from Hermes WebUI

- Hermes WebUI uses `EventSource` for the per-turn live stream in
  `static/messages.js` (`attachLiveStream(...)`), attaching to
  `api/chat/stream?stream_id=...`.
- Hermes WebUI also has a long-lived session-scoped `EventSource` in
  `static/messages.js` bound to `api/session/stream?session_id=...` that lives
  across turns and carries `bg_task_complete` plus `server_turn_started`
  lifecycle events.
- Hermes WebUI backend `api/config.py` defines `StreamChannel`, which buffers
  SSE events while no subscriber is connected and replays the buffered tail to
  reconnecting subscribers. That is a strong signal that SSE is already a
  first-class transport in WebUI, including reconnect handling.
- Hermes WebUI approval UX currently **polls** `api/approval/pending` every
  1.5 seconds in `static/messages.js`, even though approval SSE infrastructure
  exists in `api/route_approvals.py`. The explicit reason in WebUI code is
  browser HTTP/1.1 connection-pool exhaustion from too many concurrent SSE
  streams.

### Practical implication for Android

The browser's connection-pool problem does **not** automatically apply to a
single Android foreground service maintaining one trusted background
connection. Because of that:

- **Native SSE is still the preferred direction** for Part B background
  activity updates.
- But it should be a **coarse, notification-oriented session feed**, not the
  raw chat token stream.

### Recommended contract shape for Part B

Preferred server contract for Android background notifications:

- one trusted session-scoped SSE subscription per active session
- event types such as:
  - `turn_started`
  - `activity_summary`
  - `approval_required`
  - `turn_completed`
  - `turn_failed`
- minimal payload fields:
  - `session_id`
  - `stream_id`
  - trusted deep-link target or session route
  - short assistant summary text
  - optional latest tool-call label/status
  - optional approval metadata for future Part C

This keeps Android aligned with WebUI architecture while avoiding native
reimplementation of token rendering, run-journal replay, and renderer-specific
stream semantics.

### Draft event schema proposal

Suggested session-scoped SSE payloads for Android/native notification use:

#### `turn_started`

```json
{
  "type": "turn_started",
  "session_id": "session_123",
  "stream_id": "stream_456",
  "route": "/session_123",
  "started_at": 1761177600
}
```

#### `activity_summary`

```json
{
  "type": "activity_summary",
  "session_id": "session_123",
  "stream_id": "stream_456",
  "route": "/session_123",
  "summary": "Wrote the migration, updated tests, and verified the build.",
  "tool": {
    "name": "apply_patch",
    "status": "completed"
  },
  "sequence": 12,
  "timestamp": 1761177608
}
```

#### `approval_required`

```json
{
  "type": "approval_required",
  "session_id": "session_123",
  "stream_id": "stream_456",
  "route": "/session_123",
  "approval_id": "approval_789",
  "description": "Allow write access to app/src/main/... ?",
  "choices": ["once", "session", "always", "deny"],
  "timestamp": 1761177612
}
```

#### `turn_completed`

```json
{
  "type": "turn_completed",
  "session_id": "session_123",
  "stream_id": "stream_456",
  "route": "/session_123",
  "summary": "Finished updating the roadmap and README.",
  "timestamp": 1761177618
}
```

#### `turn_failed`

```json
{
  "type": "turn_failed",
  "session_id": "session_123",
  "stream_id": "stream_456",
  "route": "/session_123",
  "error": "Connection lost while waiting for the upstream model.",
  "timestamp": 1761177618
}
```

Schema notes:

- `route` should be a trusted in-app route or enough data to construct one
  safely from the configured Hermes origin.
- `summary` should already be notification-safe and truncated server-side to a
  reasonable length.
- `sequence` is optional but recommended so Android can ignore out-of-order or
  duplicated updates.
- `tool` should stay minimal; Android only needs enough detail for a concise
  notification subtitle.

### What to avoid

- Do **not** make Android background service consume raw `api/chat/stream`
  token flow as its primary contract unless there is no other viable option.
  That path is tied closely to WebUI rendering behavior, replay semantics, and
  live pane state.
- Do **not** invent a fully separate Android-only business workflow if the
  session-scoped WebUI stream can be extended with notification-safe summary
  events.

### Authentication recommendation

- Prefer reusing the authenticated Hermes WebUI session/cookies for the trusted
  configured origin, rather than introducing a new native token scheme in the
  first iteration.
- If Android service opens its own HTTP/SSE client, it should attach the same
  trusted WebView session cookies for the configured Hermes origin and continue
  to enforce the existing allowlist boundary.

### Final Stage 0 recommendation

1. Ship Part A without waiting on API changes.
2. For Part B, request or define a **session-scoped summary SSE contract** on
   the WebUI side.
3. If that cannot land quickly, use a **temporary polling snapshot endpoint**
   for latest activity/approval state, then migrate to SSE once the summary
   stream exists.

## Guardrails for implementation

- Preserve host allowlist and configured Hermes origin trust boundary.
- Keep non-allowlisted web navigation externalized.
- Keep notification tap targets validated through existing allowlist logic.
- Do not add secret-bearing JavaScript interfaces.
- Keep foreground-service usage narrow to active-turn windows.

## Proposed first PR sequence after Stage 0 sign-off

1. Part A only: lifecycle/resume polish in `MainActivity.kt` and
   `MainViewModel.kt` with tests.
2. Part B scaffold: manifest permissions + service shell + opt-in setting key and
   UI toggle (no feed integration yet).
3. Part B feed integration: connect approved activity source and update ongoing
   notification payload.

## Open questions to resolve in issue triage

- Can Hermes WebUI extend the existing session-scoped event model with a
  notification-safe `activity_summary` event for native consumers?
- What is the exact approved payload schema for activity and tool-call summary?
- Are tray approval actions (Part C) expected in the first Part B rollout,
  or strictly staged after Part B stabilization?

## Stage 0 completion checklist

- [ ] Background activity feed contract is documented and approved.
- [ ] Authentication model for background requests is documented.
- [ ] Notification summary and privacy policy is documented.
- [ ] Toggle default and rollout policy are documented.
- [x] Part A start criteria are explicitly marked as met.

