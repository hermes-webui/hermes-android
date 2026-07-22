# Hermes-Android Release Runbook

Use this checklist when publishing a new Hermes-Android build.

## Before Release

1. Merge the intended fix or release PR to `main`.
2. Decide whether this is a manual or tag-triggered release:
   - Manual run: no version edit needed. The workflow auto-bumps `appVersionName` from the latest published tag, updates README release metadata, commits those changes back to `main`, then builds from that version-bump commit.
   - Tag run: update `appVersionName` in `app/build.gradle.kts` first, then push matching tag `v<versionName>`.
3. Verify the change locally when code changed:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat assembleDebug --no-daemon
```

4. Confirm release docs are current when release behavior changed.

## Normal Release

Run the GitHub Actions workflow:

```text
1 - Orchestration Release
```

That workflow:

1. For manual runs, commits the next app version to `main`.
1. Builds and signs `hermes-webui-v<version>-github.apk`.
1. Builds and signs `hermes-webui-v<version>.aab`.
1. Uploads both files as workflow artifacts.
1. Starts `2 - Publish GitHub APK` and `3 - Publish Play Store Release` in parallel.

The GitHub publish workflow attaches only the `-github.apk` to the GitHub
Release and writes human-readable generated GitHub release notes grouped by
`.github/release.yml`. Build diagnostics stay in the Actions job summary rather
than the public release body. The Play publish workflow uploads only the `.aab`
to Google Play production and writes a brief `en-US` What's New changelog
generated from those same notes. The Play text is capped below the Play limit
and ends with `Report issues through the in-app bug report tool.`

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
- `3 - Publish Play Store Release` needs the Play AAB artifact name, build run
  ID, commit SHA, tag name, and version name.

Do not rerun `1 - Orchestration Release` just to retry one failed publish
target unless the build artifacts are missing or expired.

## Safety Checks

- Release workflows use concurrency groups to avoid duplicate publishing for
  the same release ref or target version.
- Build and publish workflows fail if they find anything other than exactly one
  matching APK or AAB artifact.
- GitHub Releases use human-readable generated GitHub release notes; Play Store
  releases use a shorter `en-US` What's New changelog generated from the same
  notes.
- Tag-triggered releases must use a tag that matches the Gradle `versionName`,
  such as `v0.1.8`.
