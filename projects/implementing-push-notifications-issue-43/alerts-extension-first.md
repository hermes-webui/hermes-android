# Extension-First Hermes Alerts

Hermes alerts should be implemented as an extension-first feature so the core
WebUI stays lean for users who never need phone notifications.

## Summary

The user-facing alert workflow should live in a WebUI extension. The extension
captures intent such as "alert me when this cron job fails" or "remind me
tomorrow at 3 PM", then stores a durable rule through the smallest available
Hermes/WebUI persistence seam.

Hermes remains the decision-maker:

- it evaluates the trigger
- it emits the alert event
- it decides when the alert is actionable

Clients remain optional consumers:

- WebUI can render the alert in-browser
- Hermes-Android can turn the event into a native notification
- Hermex/iOS can adopt the same contract later if they have a compatible
  delivery path

## What to build first

1. Define the alert rule and alert event contract.
2. Build the extension UI for creating and managing a rule.
3. Use a self-hosted push transport as the primary delivery path.
4. Keep Android as a thin notification consumer.
5. Fall back to polling only when push cannot be used.

## Relay model

If the current stack cannot deliver a closed-app notification cleanly, add a
tiny ntfy-style relay instead of inventing a second app or a user-managed push
setup.

See [alerts-relay-architecture.md](./alerts-relay-architecture.md) for the
concrete server/relay/Android flow.

The relay should be Hermes-managed:

- Hermes downloads or enables the relay binary on the server when alerts are
  turned on
- Hermes generates the relay topic / token / credentials automatically
- Hermes stores the mapping to the active Hermes instance
- Hermes-Android performs only a minimal one-time pairing against the user’s
  Hermes instance
- Android receives notifications from that relay and still falls back to
  polling if the relay path is unavailable

The user should not configure topics, credentials, or relay internals by hand.
The only Android-side preference remains the fallback polling interval.

## Guardrails

- Do not add a polling loop to Android for alert state unless it is the
  documented fallback.
- Do not add core WebUI UI for this unless the extension cannot express a
  required persistence or trigger seam.
- Keep repeated alerts batchable or coalesced.
- Keep the contract client-neutral so desktop, Android, and iOS-style clients
  can consume it without backend churn.
- The extension owns the alert UI and intent capture, not the push transport.
- The push transport still needs a server-side component or relay.

## Suggested MVP

- failed cron job alerts
- time-based reminders

Both are enough to prove the intent capture, trigger evaluation, and delivery
path without turning Hermes WebUI into a mobile-specific product.
