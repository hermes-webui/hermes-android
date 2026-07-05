# Project Roadmap

## Objective

Add opt-in Hermes alerts that can be delivered as Android notifications while keeping Hermes self-hosted, low-friction, battery conscious, and event-first.

## Architecture Direction

Hermes alerts should be designed as a client-neutral event system first.

Hermes owns:

- alert intent capture
- alert rule storage
- rule evaluation
- alert event creation
- alert history/state

Hermes-Android owns:

- pairing with the user’s Hermes instance
- receiving alert events
- showing native Android notifications
- deep-linking back to Hermes
- optional foreground-service instant delivery
- fallback polling

Android must not become an alert-rule editor or a second Hermes product.

## Scope

- Keep the alert intent UI in Hermes/WebUI.
- Keep Android as the first native delivery surface.
- Define a stable alert rule and alert event contract before transport work.
- Prefer direct self-hosted delivery from the user’s Hermes instance.
- Use foreground-service streaming for instant delivery when needed.
- Fall back to polling only when live delivery is unavailable.
- Keep relay support as a future optional transport, not the primary design.
- Avoid Firebase Cloud Messaging because Hermes instances are self-hosted.
- Avoid a second user-facing app and avoid user-managed push setup.

## Non-goals

- No Android-side alert-rule editor.
- No Firebase/FCM dependency.
- No centralized Hermes notification service.
- No user-managed topics, credentials, or push provider setup.
- No relay auto-provisioning in the MVP.

## Build order

1. Define the alert rule and alert event contract.
2. Define the Android notification mapping.
3. Add the server-side alert emitter.
4. Add Android pairing with the user’s Hermes instance.
5. Add Android alert receipt and native notification rendering.
6. Add deep-link routing back to Hermes.
7. Add polling fallback and the interval setting.
8. Add batching, deduping, and alert IDs.
9. Add optional foreground-service instant delivery.
10. Revisit relay support only after the direct contract works.

## Deliverables

- `project-roadmap.md`
- `alerts-extension-first.md`
- `alerts-relay-architecture.md`
- alert rule/event contract
- Android notification mapping
- updated issue #43 checklist

## Design Decisions

### DD-001: Hermes evaluates alert rules

Android only receives alert events. It does not decide whether a rule fired.

### DD-002: FCM is out of scope

Hermes is self-hosted, so the core notification path must not depend on Firebase or a centralized vendor push service.

### DD-003: Event-first, not relay-first

The alert event contract comes before relay design. Relay support is only one possible future transport.

### DD-004: Android stays thin

Hermes-Android is a delivery endpoint, not a second alert-management product.

## Notes

- Hermes must still be reachable from the phone, typically through Tailscale or Cloudflare Tunnel.
- The user should not need to configure relay topics, credentials, or push provider details.
- The Android app should stay thin and battery efficient.
- The same alert event contract should eventually support WebUI, Android, desktop, iOS-style clients, webhooks, and third-party providers.