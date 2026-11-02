package com.hermeswebui.android.update

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest

// Builds without Google Play Core (F-Droid release, GitHub APK, debug) never use the
// "play" update channel, so this client only reports that Play updates are unavailable.
@Suppress("UNUSED_PARAMETER")
fun createPlayUpdateClient(
    context: Context,
    launcher: ActivityResultLauncher<IntentSenderRequest>
): PlayUpdateClient = UnavailablePlayUpdateClient

private object UnavailablePlayUpdateClient : PlayUpdateClient {
    override fun checkForUpdate(
        onAvailable: (versionCode: String) -> Unit,
        onUpToDate: () -> Unit,
        onError: (message: String?) -> Unit
    ) {
        onError("Google Play updates are not available in this build.")
    }

    override fun resumeUpdateInProgress() = Unit

    override fun startUpdate(onUnavailable: () -> Unit, onError: () -> Unit) {
        onUnavailable()
    }
}
