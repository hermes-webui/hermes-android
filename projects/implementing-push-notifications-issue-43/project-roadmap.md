# Project Roadmap

## Objective

Add opt-in Hermes alerts that show up as Android notifications while keeping
Hermes self-hosted, low-friction, and battery conscious.

## Scope

- Keep the alert intent UI in Hermes/WebUI.
- Keep Android as the delivery surface.
- Use a Hermes-managed relay path when live delivery is available.
- Fall back to polling only when live delivery is unavailable.
- Avoid a second user-facing app and avoid user-managed push setup.

## Build order

1. Define the alert rule and alert event contract.
2. Build the extension UI for creating and managing alert rules.
3. Add the server-side alert emitter.
4. Add Hermes-managed relay bootstrap and auto-provisioning.
5. Add the Android pairing and notification receipt path.
6. Add polling fallback and the interval setting.
7. Add batching and deduping.

## Deliverables

- `alerts-extension-first.md`
- `alerts-relay-architecture.md`
- Updated issue 43 docs and checklist

## Notes

- Hermes must still be reachable from the phone, typically through Tailscale
  or Cloudflare Tunnel.
- The user should not need to configure relay topics, credentials, or push
  provider details.
- The Android app should stay thin and battery efficient.
