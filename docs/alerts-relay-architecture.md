# Hermes Alerts Relay Architecture

This doc describes the leanest practical delivery path for Hermes alerts when
the user wants the experience to "just work" on a phone without configuring a
separate push service.

## Goal

Hermes stores and evaluates the alert rule. A small self-hosted relay handles
delivery to Hermes-Android when live delivery is available. Hermes-Android
falls back to polling only when the live path cannot be used.

The Hermes instance still has to be reachable from the phone. In practice that
means the user will usually need something like Tailscale or Cloudflare Tunnel
already running, which is likely the same remote-access path they use for the
Android app today.

## Non-goals

- No second user-facing app.
- No user-managed push provider setup.
- No Android-side alert-rule editor.
- No requirement for the user to learn topics, credentials, or relay internals.

## Components

### Hermes WebUI extension

- Captures the user's alert intent.
- Creates and edits alert rules.
- Never owns delivery transport.

### Hermes server

- Evaluates rules.
- Assigns alert severity.
- Emits a compact alert event when a rule fires.
- Owns relay bootstrap and lifecycle decisions.

### Hermes-managed relay

- Small server-side binary or service.
- Downloaded or enabled by Hermes when alerts are turned on.
- Auto-provisions topic/token/credential material.
- Accepts alert events from Hermes.
- Delivers notifications to the paired Android client.
- Buffers briefly if the client is temporarily unreachable.

### Hermes-Android

- Performs a minimal one-time pairing against the user's Hermes instance.
- Receives alert events and shows native Android notifications.
- Keeps polling only as the fallback path when the relay/live delivery path is
  unavailable.

## Provisioning flow

1. User enables alerts in Hermes/WebUI.
2. Hermes determines whether the relay binary is already available.
3. If needed, Hermes downloads the relay binary from a trusted release source.
4. Hermes starts the relay and generates local credentials automatically.
5. Hermes stores the pairing material for the active Hermes instance.
6. Hermes-Android pairs once and stores the returned identity/token locally.

The user should only see one action surface for alert intent and one optional
Android fallback preference for polling interval.

## Event flow

1. Hermes evaluates a rule.
2. Hermes emits an alert payload.
3. Hermes hands the payload to the relay.
4. The relay delivers the payload to Hermes-Android.
5. Hermes-Android shows a native notification and deep-links back to Hermes.
6. If delivery fails, Android polls at the configured fallback interval.

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

## Reliability policy

- Batch or coalesce repeated alerts where practical.
- Deduplicate repeated deliveries using the dedupe key.
- Preserve severity in both the relay and Android notification.
- Keep polling as a recovery path, not the primary mode.

## Android fallback

Polling exists only to cover the case where live delivery is unavailable.
The app should expose a single user-facing poll interval setting for this
fallback path and nothing else.

## Build checklist

When implementation starts, do the work in this order:

1. Add the alert rule/event contract in Hermes/WebUI.
2. Keep the extension as the alert intent UI.
3. Add the server-side alert emitter.
4. Add the Hermes-managed relay bootstrap and auto-provisioning.
5. Add the Android pair-and-receive path.
6. Add the polling fallback and interval setting.
7. Add coalescing/deduping so repeated alerts do not spam the user.

## Rollout notes

- Hermes should hide relay details from the user.
- Pairing should be one-time and automatic after the user enables alerts.
- The same alert payload should remain usable by future clients.
- If the relay cannot be reached, Android should degrade to polling rather
  than failing the alert feature outright.
