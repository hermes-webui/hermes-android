## What changed

<!-- Short, user-facing summary a tester installing the APK or Play internal build can understand.
Lead with the user-visible outcome, break multi-part changes into a list, and say what is
deliberately unaffected. The PR title becomes the release-note entry.
End with `Fixes #123.` to close an issue, or `Related #123.` -->

## Testing

<!-- Name each test class and what it proves, plus the commands you ran and their results.
Include: (1) a mutation check - what you deliberately broke, that the test failed, and that you
restored it; (2) any manual emulator/device check; (3) a "Not covered:" line for anything you did
not verify (for example, no emulator, no live server). Never hide a failing or skipped check. -->

- [ ] `.\gradlew.bat testDebugUnitTest --no-daemon`
- [ ] `.\gradlew.bat lintDebug --no-daemon`
- [ ] `.\gradlew.bat assembleDebug --no-daemon`
- [ ] `python -m unittest discover -s tools/tests -p "test_*.py"` and `python tools/check_markdown.py`
- [ ] `connectedDebugAndroidTest` on an emulator, or note why it was skipped (CI runs API 35 and 36 for Android changes)

## QA checklist

- [ ] Behavior changes have the cheapest test that would have caught the regression; the new test was seen to fail with the fix reverted.
- [ ] Injected WebUI JavaScript or a WebUI shim change was also checked with `tools/qa` against a real server, and the redacted `report.md` summary is pasted below (or marked not applicable).
- [ ] Security rules hold: no SSL error bypass, no dashboard-config writes, non-allowlisted HTTP/HTTPS stays externalized, no new JavaScript bridge for secrets.
- [ ] No server URL, hostname, credential, keystore, or local machine path is in the diff.
- [ ] `README.md`, `ROADMAP.md`, `ARCHITECTURE.md`, and `AGENTS.md` are updated where behavior, setup, architecture, or workflow changed.
- [ ] Labels applied from the release-note set (`enhancement`, `user-facing`, `bug`, `documentation`, `skip-changelog`).

## Live QA summary (if applicable)

<!-- Paste the redacted tools/qa report.md summary. Never paste the server URL. -->
