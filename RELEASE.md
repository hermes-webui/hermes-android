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

F-Droid needs no step here. The orchestrator creates the `v<version>` tag, and
F-Droid's update checker finds it, builds the `release` build type from source,
signs it, and publishes it, typically within a few days. This starts only after
the one-time fdroiddata submission below has been merged.

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

## F-Droid (one-time setup)

1. Release a version that contains the Play-free `release` build (Issue #141).
   Earlier tags still contain Google Play Core and fail F-Droid's scanner.
2. Sign in to GitLab and complete the privacy checks below before publishing.
3. Use **Fork** on [fdroiddata](https://gitlab.com/fdroid/fdroiddata), not
   **New project**. Wait for the import to finish and verify that the fork
   points back to `fdroid/fdroiddata`.
4. Create a submission branch from the fork's current default branch. Copy
   `fdroid/com.hermeswebui.android.fdroid.yml` into it as
   `metadata/com.hermeswebui.android.fdroid.yml`. The first supported release
   is `v1.1.8`: `versionName` / `CurrentVersion` are `1.1.8`,
   `versionCode` / `CurrentVersionCode` are `10108`, and `commit` is `v1.1.8`.
   Submit only this metadata file, not the APK or the Android source tree.
5. Validate with F-Droid's tooling in the fdroiddata checkout:

   ```text
   fdroid lint com.hermeswebui.android.fdroid
   fdroid checkupdates com.hermeswebui.android.fdroid
   ```

   Read the actual results, not only the exit code. Confirm that tag checking
   finds the expected version/code. Local checks do not prove that F-Droid's
   build servers support the required SDK and Gradle versions.
6. Review the commit's author and committer, push the submission branch, and
   open a merge request targeting `fdroid/fdroiddata`'s default branch. Link
   the source release and Android PR #142, describe the Play-free `release`
   build, and report which checks were actually run. Read back the published
   commit identity and merge request. Respond to pipeline and maintainer
   feedback; F-Droid review can take several weeks.
7. After the merge, F-Droid tracks new `v<version>` tags automatically
   (`AutoUpdateMode: Version`, `UpdateCheckMode: Tags`).
   Publication still depends on successful F-Droid builds and indexing; do
   not promise a fixed turnaround. Keep Android issue #141 open until the
   listing is live, and link the submission merge request there.

Keep the recipe, `fastlane/metadata/android/`, the README `Version code` line,
and `val appVersionName` in `app/build.gradle.kts` aligned;
`tools/tests/test_fdroid_metadata.py` checks them.

### Public identity and privacy checks

- In GitLab's **Edit profile**, use a public handle as the display name, keep
  the public email empty, and select GitLab's private commit email. Do not
  change the account's login email or publish it.
- For a local fdroiddata checkout, set `git config --local user.name` to the
  public handle and `git config --local user.email` to the exact private
  commit email shown by GitLab. Do not rely on global Git configuration.
  Browser/API-created commits can use GitLab profile defaults instead.
- Before pushing, inspect `git show --no-patch --format=fuller HEAD` and the
  staged diff. Both author and committer must use the intended public
  identity. Check commit trailers, MR text, metadata, and attachments too.
- Preserve existing upstream contributors' attribution. These checks cover
  new contributions; do not rewrite upstream history to remove names.
- Never include personal names, personal email addresses, local machine
  paths, credentials, or device authorization codes in submission content.
  Authenticate with `glab auth login --hostname gitlab.com --device` if using
  the CLI; keep credentials in the OS keyring, not in Git remotes or files.
- After publication, verify the live profile and commit metadata. A local
  Git setting alone does not prove that a hosted commit is pseudonymous.
  Stop before publishing if identity cannot be verified.

## Device Acceptance: GitHub APK (Phone + Tablet)

Run this checklist on one phone and one tablet after every GitHub APK release.
It documents exact commands and artifacts; it never installs anything by
itself — Android updates always hand off to the system installer, so no silent
install is promised or expected.

### Human Approval Gate (stop point)

Before pushing, tagging, creating a release, or touching any secret:

1. Stop and get explicit human approval naming the exact version, tag, and
   publish target(s).
2. Never read, print, paste, or request secret values. Names only:
   `ANDROID_KEYSTORE_BASE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
   `ANDROID_KEY_PASSWORD`, `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_BASE`.

### Release Artifacts to Collect (before device testing)

- APK artifact name: `hermes-webui-v<version>-github.apk`
- Version name (must end in `-github`) and version code from the workflow summary
- APK file SHA-256 and signing-cert SHA-256 digests printed by the release
  verifier (`tools/verify_release_apk.py`); only digests are ever printed

```powershell
python tools/verify_release_apk.py --apk <staged-apk> `
  --expected-package com.hermeswebui.android.github `
  --expected-version-name <version>-github `
  --expected-version-code <code>
./gradlew.bat -q :app:printReleaseVersionName --no-daemon
```

The APK verifier locates `aapt` and `apksigner` in Android SDK build-tools when
they are not on `PATH`; set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to the SDK
directory if needed. A missing tool still fails verification.

### Per-Device Checklist (repeat on phone and tablet)

1. Package check: installed app id is `com.hermeswebui.android.github`
   (`adb shell pm list packages | findstr hermes`).
2. Version check: Settings shows the released version name;
   `adb shell dumpsys package com.hermeswebui.android.github | findstr versionName`.
3. Cert/SHA-256 check: downloaded APK file hash and signing-cert digest match
   the release verifier output from the step above.
4. Download/install prompt: in-app update flow shows `Check` -> `Download` ->
   `Install`; tapping Install opens the Android system installer prompt (not a
   silent install); an install-ready notification appears when Hermes is
   backgrounded mid-download.
5. No-update behavior: with the latest version already installed, `Check`
   reports no update available and shows no download/install action.
6. Rollback/manual fallback: if the new build misbehaves, uninstall it and
   reinstall the previous release APK from the GitHub Releases page by hand;
   confirm Settings shows the previous version name afterward.

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
