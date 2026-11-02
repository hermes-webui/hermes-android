package com.hermeswebui.android.update

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

fun createPlayUpdateClient(
    context: Context,
    launcher: ActivityResultLauncher<IntentSenderRequest>
): PlayUpdateClient = GooglePlayUpdateClient(AppUpdateManagerFactory.create(context), launcher)

private class GooglePlayUpdateClient(
    private val appUpdateManager: AppUpdateManager,
    private val launcher: ActivityResultLauncher<IntentSenderRequest>
) : PlayUpdateClient {

    override fun checkForUpdate(
        onAvailable: (versionCode: String) -> Unit,
        onUpToDate: () -> Unit,
        onError: (message: String?) -> Unit
    ) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { updateInfo ->
                if (updateInfo.isImmediateUpdateAvailable()) {
                    onAvailable(updateInfo.availableVersionCode().toString())
                } else {
                    onUpToDate()
                }
            }
            .addOnFailureListener { error -> onError(error.message) }
    }

    override fun resumeUpdateInProgress() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { updateInfo ->
            if (updateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                launchUpdate(updateInfo)
            }
        }
    }

    override fun startUpdate(onUnavailable: () -> Unit, onError: () -> Unit) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { updateInfo ->
                if (updateInfo.isImmediateUpdateAvailable()) {
                    launchUpdate(updateInfo)
                } else {
                    onUnavailable()
                }
            }
            .addOnFailureListener { onError() }
    }

    private fun AppUpdateInfo.isImmediateUpdateAvailable(): Boolean =
        updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
            isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)

    private fun launchUpdate(updateInfo: AppUpdateInfo) {
        appUpdateManager.startUpdateFlowForResult(
            updateInfo,
            launcher,
            AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
        )
    }
}
