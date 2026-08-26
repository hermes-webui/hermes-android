# Hermes-Android Release Runbook

Use this checklist when publishing a new Hermes-Android build.

## Before Release

1. Merge the intended fix or release PR to `main`.
2. Update `appVersionName` in `app/build.gradle.kts` and the matching README version metadata in the release PR. The orchestration workflow builds that reviewed version and never edits or pushes source.
3. Choose the trigger after the release PR reaches `main`:
  - Manual run: run `1 - Orchestration Release` from `main`; it rejects an already-published version.
  - Tag run: push a tag that exactly matches Gradle, such as `v1.1.0`.
4. Verify the change locally when code changed:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat lintDebug --no-daemon
.\gradlew.bat assembleDebug --no-daemon
.\gradlew.bat connectedDebugAndroidTest --no-daemon
python -m unittest discover -s tools/tests -p "test_*.py" -v
```

CI runs the complete Android instrumentation suite on API 35 and 36 for every Android-changing pull request and direct `main` push. Orchestration runs the same unfiltered suite on API 36 before signed artifacts are built.

5. Confirm release docs are current when release behavior changed.

## Normal Release

Run the GitHub Actions workflow:

```text
1 - Orchestration Release
```

That workflow:

1. Validates the checked-in version and release secrets.
2. Runs release-tool, unit, Lint, and API 36 instrumentation gates.
3. Generates GitHub and Play release metadata once.
4. Builds, signs, and verifies `hermes-webui-v<version>-github.apk` and `hermes-webui-v<version>.aab`.
5. Uploads both files plus their release metadata as workflow artifacts.
6. Publishes the GitHub APK and Play production AAB in the same orchestration run.

The GitHub publish workflow attaches only the `-github.apk` to the GitHub
Release and writes human-readable generated GitHub release notes grouped by
`.github/release.yml`. Build diagnostics stay in the Actions job summary rather
than the public release body. The Play publish workflow uploads only the `.aab`
to Google Play production and writes a brief `en-US` What's New changelog
generated from those same notes. GitHub keeps clickable PR links; Play keeps
compact PR/issue URLs. The Play text is capped below the Play limit and ends
with `Report issues through the in-app bug report tool.`

## Retry One Publish Target

If the orchestration build succeeds but one publish target fails, open the
orchestration run summary and copy:

- Build run ID
- Commit SHA
- Version name
- Tag name
- GitHub APK artifact name
- Play AAB artifact name

Then manually rerun only the failed workflow:

- `2 - Publish GitHub APK` needs the GitHub APK artifact name, build run ID,
  commit SHA, tag name, and version name.
- `3 - Publish Play Store Production` needs the Play AAB artifact name, build run
  ID, commit SHA, tag name, and version name.

If you want an open-testing/beta release later, run `Play Store Beta (Manual)`
manually with the same Play AAB artifact metadata.

Do not rerun `1 - Orchestration Release` just to retry one failed publish
target unless the build artifacts are missing or expired.

## Safety Checks

- Release workflows use concurrency groups to avoid duplicate publishing for
  the same release ref or target version.
- Build and publish workflows fail if they find anything other than exactly one
  matching APK or AAB artifact.
- Build orchestration verifies APK and AAB signatures before upload.
- Retry publishers reject mismatched version, tag, commit, build run, artifact
  name, or bundled release metadata.
- GitHub Releases use human-readable generated GitHub release notes; Play Store
  releases use a shorter `en-US` What's New changelog generated from the same
  notes.
- Tag-triggered releases must use a tag that matches the Gradle `versionName`,
  such as `v1.1.0`.
