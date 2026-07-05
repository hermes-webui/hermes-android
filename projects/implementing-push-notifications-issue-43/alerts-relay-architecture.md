# Hermes Alerts Delivery Architecture

This doc describes how Hermes alert events should reach Hermes-Android while keeping Hermes self-hosted, client-neutral, and battery conscious.

## Goal

Hermes stores and evaluates alert rules. When a rule fires, Hermes emits a compact alert event.

Hermes-Android receives alert events, shows native Android notifications, and deep-links back to Hermes.

Delivery should be event-first, not relay-first.

## Non-goals

- No Android-side alert-rule editor.
- No Android-side rule evaluation.
- No Firebase/FCM dependency.
- No centralized Hermes notification service.
- No second user-facing app.
- No user-managed push provider setup.
- No relay auto-provisioning in the MVP.

## Components

### Hermes WebUI extension

- Captures the user's alert intent.
- Creates and edits alert rules.
- Never owns delivery transport.

### Hermes server

- Stores alert rules.
- Evaluates rules.
- Assigns alert severity.
- Emits compact alert events.
- Owns alert state and history.

### Alert delivery adapter

- Moves alert events from Hermes to clients.
- Starts with direct Hermes delivery.
- May support additional transports later.
- Hides transport details from the user.

Possible delivery adapters:

- direct Hermes stream
- Android foreground-service instant delivery
- polling fallback
- future optional relay
- future webhook-style providers

### Hermes-Android

- Pairs with the user's Hermes instance.
- Receives alert events.
- Shows native Android notifications.
- Routes notification taps back to Hermes.
- Uses polling only as a fallback when live delivery is unavailable.

## Event flow

1. The user defines an alert in Hermes/WebUI.
2. Hermes stores the alert rule.
3. Hermes evaluates the rule.
4. Hermes emits an alert event.
5. The delivery adapter sends the event to Hermes-Android.
6. Hermes-Android shows a native notification.
7. Notification taps deep-link back to Hermes.
8. If live delivery is unavailable, Android falls back to polling.

## Payload shape

Keep the payload small and client-neutral:

- alert id
- rule id
- title
- body
- severity
- timestamp
- tap target
- dedupe key
- optional expiry
- optional actions

## Reliability policy

- Every alert must have a stable alert id.
- Repeated deliveries should dedupe by alert id or dedupe key.
- Repeated related alerts should be batchable or coalesced.
- Severity should survive every delivery path.
- Polling is a recovery path, not the primary mode.
- Android should degrade gracefully if live delivery is unavailable.

## Android fallback

Polling exists only to cover the case where live delivery is unavailable.

The app should expose one user-facing fallback polling interval setting and nothing else.

## Future relay support

A Hermes-managed relay may be added later if direct delivery is not enough.

Relay support should remain a delivery adapter, not the core architecture.

If added later, the relay should:

- be optional
- be Hermes-managed
- avoid user-managed topics or credentials
- preserve the same alert event contract
- fall back to polling if unavailable

## Build checklist

When implementation starts, do the work in this order:

1. Add the alert rule/event contract in Hermes/WebUI.
2. Keep the extension as the alert intent UI.
3. Add the server-side alert emitter.
4. Add Android pairing with the user's Hermes instance.
5. Add Android alert receipt.
6. Add native notification rendering.
7. Add notification tap deep-link routing.
8. Add polling fallback and interval setting.
9. Add coalescing and deduping.
10. Revisit relay support only after the direct contract works.

## Rollout notes

- Hermes should hide delivery details from the user.
- Pairing should be minimal and one-time.
- The same alert payload should remain usable by future clients.
- FCM remains out of scope because Hermes instances are self-hosted.