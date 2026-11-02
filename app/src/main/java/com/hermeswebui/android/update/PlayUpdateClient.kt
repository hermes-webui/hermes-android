package com.hermeswebui.android.update

/**
 * Google Play in-app update operations.
 *
 * Only the `play` build type links Google Play Core (see `src/play`). Every other build,
 * including the F-Droid `release` build and the GitHub APK, uses the dependency-free
 * implementation in `src/foss` so those APKs contain no proprietary Google libraries.
 */
interface PlayUpdateClient {
    fun checkForUpdate(
        onAvailable: (versionCode: String) -> Unit,
        onUpToDate: () -> Unit,
        onError: (message: String?) -> Unit
    )

    fun resumeUpdateInProgress()

    fun startUpdate(onUnavailable: () -> Unit, onError: () -> Unit)
}
