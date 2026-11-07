# Live QA harness

Local-only checks that drive the installed debug app against a **real Hermes WebUI server**
over `adb` and WebView DevTools. They cover what CI cannot: the WebUI's actual DOM, the injected
shims, and the layout repairs, exactly as they run on a device.

This is **not** part of public CI, because the QA server is private. CI covers the same rules
against fixtures (`app/src/androidTest`) and with source contract tests
(`WebViewSecurityContractTest`). Use this harness to detect upstream WebUI drift and to validate
a shim change before opening a PR.

## Setup (once)

1. Start an emulator or connect a device, then install the debug build:
   `.\gradlew.bat installDebug`
2. Open the app, enter your Hermes WebUI server URL, and **sign in manually**. The harness never
   handles credentials.
3. Tell the harness which server to expect. Either export it or copy the example file:

   ```text
   copy tools\qa\.env.qa.example tools\qa\.env.qa    # then edit; this file is gitignored
   ```

Requirements: Node 22+ (built-in `WebSocket`), `adb` (found via `ANDROID_HOME`, the default SDK
location, or `PATH`).

## Run

```text
node tools/qa/run-live-qa.mjs                 # every scenario
node tools/qa/run-live-qa.mjs --only s02,s03  # a subset
node tools/qa/run-live-qa.mjs --serial emulator-5554
```

Exit code `0` means every check passed, `1` means a check failed, and `2` means the setup is
wrong (no device, wrong server, signed out). Reports are written to `tools/qa/out/<timestamp>/`
(`report.md`, `report.json`, and `failure.png` on failure). That folder is gitignored.

## Scenarios

| ID | What it checks | Issues |
|---|---|---|
| s01 | The shell renders: non-zero body and `.main` size, visible text, `--vh` set | #5, #59, #110 |
| s02 | One "Application Settings" entry directly after Settings; it self-heals when removed or moved | #59, #148 |
| s03 | Selectors the shims depend on still exist in the live WebUI (drift alarm) | #92 |
| s05 | A collapsed block is repaired; `.approval-card` is not; stale repair on it is cleared; the composer ancestors never clip | #44, #80 |

Add a scenario by dropping `sNN_name.mjs` in `scenarios/` that exports
`{ id, title, run({ cdp, adb, sleep }) }` and returns `[{ name, pass, detail }]`. Scenarios must
leave the page as they found it and must not touch real conversations.

## Never commit server details

- The server URL lives only in `tools/qa/.env.qa` or `HERMES_QA_SERVER_URL`.
- Reports redact the URL, origin and host as `<server>`, so a report summary can go in a PR.
  Do not paste raw terminal output from a failed preflight into a public place without checking it.
- `tools/tests/test_qa_harness.py` fails if a tailnet hostname is tracked, the local files lose
  their gitignore entries, or the redactor stops working.

## Not automated

Microphone dictation, real OIDC providers and passkeys, OEM battery and local-network behavior,
Bluetooth keyboards, foldable fold/unfold, and the soft keyboard's true pixel overlap still need a
manual pass on a device.
