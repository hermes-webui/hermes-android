# Extension-First Hermes Alerts

Hermes alerts should be extension-first so the core WebUI stays lean while still giving users a natural way to say, "alert me when this happens."

## Summary

The user-facing alert workflow should live in a WebUI extension.

The extension captures alert intent, such as:

- "alert me when this cron job fails"
- "remind me tomorrow at 3 PM"
- "tell me when this watch condition changes"

The extension stores a durable alert rule through Hermes/WebUI.

Hermes remains the decision-maker:

- it stores alert rules
- it evaluates triggers
- it emits alert events
- it owns alert state and history

Clients remain optional consumers:

- WebUI can render the alert in-browser
- Hermes-Android can turn the event into a native notification
- future clients can consume the same alert contract

## What to build first

1. Define the alert rule contract.
2. Define the alert event contract.
3. Build the extension UI for creating and managing alert rules.
4. Add the server-side alert emitter.
5. Keep Android as a thin notification consumer.
6. Fall back to polling only when live delivery is unavailable.

## Delivery boundary

The extension owns alert UI and intent capture.

The extension does not own delivery transport.

Delivery should be handled through a separate alert delivery path:

- direct Hermes alert stream
- Android foreground-service instant delivery when enabled
- polling fallback when live delivery is unavailable
- future optional relay transport

FCM is out of scope because Hermes instances are self-hosted.

## Guardrails

- Do not add an Android-side alert-rule editor.
- Do not make Android evaluate alert rules.
- Do not make the extension own push transport.
- Do not require Firebase or a centralized notification provider.
- Do not require users to configure topics, credentials, or push provider details.
- Keep repeated alerts batchable or coalesced.
- Keep the contract client-neutral.

## Suggested MVP

- failed cron job alerts
- time-based reminders

Both are enough to prove intent capture, trigger evaluation, alert event creation, Android delivery, and deep-link routing without turning Hermes WebUI or Hermes-Android into a mobile-specific product.