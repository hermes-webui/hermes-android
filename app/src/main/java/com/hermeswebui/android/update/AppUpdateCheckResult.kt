package com.hermeswebui.android.update

sealed interface AppUpdateCheckResult {
    data class Current(
        val version: String,
        val releaseUrl: String,
        val releaseNotes: String? = null
    ) : AppUpdateCheckResult
    data object Unsupported : AppUpdateCheckResult
    data class Available(
        val version: String,
        val releaseUrl: String,
        val downloadUrl: String? = null,
        val fileName: String? = null,
        val releaseNotes: String? = null,
        val title: String,
        val body: String
    ) : AppUpdateCheckResult
    data class Failed(val message: String) : AppUpdateCheckResult
}
