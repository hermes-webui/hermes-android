package com.hermeswebui.android.server

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import com.hermeswebui.android.data.DiagnosticsLogger
import com.hermeswebui.android.data.HermesApiClient
import com.hermeswebui.android.data.ServerProfile
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.domain.ServerUrlValidator
import com.hermeswebui.android.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class HermesServerProfileCoordinator(
    private val context: Context,
    private val activityScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val viewModel: MainViewModel,
    private val serverUrlValidator: ServerUrlValidator,
    private val onOpenInExternalBrowser: (String) -> Unit,
    private val onPerformServerProfileSwitch: (ServerProfile) -> Unit
) {
    private var serverValidationJob: Job? = null

    fun preflightConfiguredStartupServer(
        serverUrl: String,
        startUrl: String,
        onContinueToWebView: (String) -> Unit
    ) {
        validateServerBeforePersist(
            serverUrl = serverUrl,
            openSettingsOnFailure = true,
            onFailure = null
        ) {
            onContinueToWebView(startUrl)
        }
    }

    fun validateServerForPersistence(
        serverUrl: String,
        openSettingsOnFailure: Boolean = false,
        onFailure: ((HermesApiClient.ServerReadinessResult) -> Unit)? = null,
        onSuccess: () -> Unit
    ) {
        validateServerBeforePersist(
            serverUrl = serverUrl,
            openSettingsOnFailure = openSettingsOnFailure,
            onFailure = onFailure,
            onSuccess = onSuccess
        )
    }

    fun handleAddServerProfile(name: String, url: String) {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()

        if (!serverUrlValidator.isValid(trimmedUrl)) {
            Toast.makeText(context, "Server URL must be a valid http:// or https:// URL", Toast.LENGTH_LONG).show()
            return
        }

        val existingProfiles = settingsRepository.getProfiles()
        if (existingProfiles.any { normalizeServerProfileUrl(it.url) == normalizeServerProfileUrl(trimmedUrl) }) {
            Toast.makeText(context, "A server with this URL already exists", Toast.LENGTH_LONG).show()
            return
        }
        if (trimmedName.isNotBlank() && existingProfiles.any { it.name.trim().equals(trimmedName, ignoreCase = true) }) {
            Toast.makeText(context, "A server with this name already exists", Toast.LENGTH_LONG).show()
            return
        }

        validateServerBeforePersist(
            trimmedUrl,
            onFailure = { result ->
                showServerValidationRecoveryDialog(trimmedUrl, result, "Add server") {
                    val profile = viewModel.addServerProfile(
                        name = trimmedName.ifBlank { trimmedUrl },
                        url = trimmedUrl
                    )
                    if (profile != null) {
                        Toast.makeText(context, "Server profile \"${profile.name}\" added (readiness check skipped)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to add profile", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        ) {
            val profile = viewModel.addServerProfile(
                name = trimmedName.ifBlank { trimmedUrl },
                url = trimmedUrl
            )
            if (profile != null) {
                Toast.makeText(context, "Server profile \"${profile.name}\" added", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Failed to add profile", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleEditServerProfile(profileId: String, newName: String, newUrl: String) {
        if (!serverUrlValidator.isValid(newUrl)) {
            Toast.makeText(context, "Server URL must be a valid http:// or https:// URL", Toast.LENGTH_LONG).show()
            return
        }
        validateServerBeforePersist(
            newUrl,
            onFailure = { result ->
                showServerValidationRecoveryDialog(newUrl, result, "Save changes") {
                    viewModel.updateServerProfile(profileId, newName, newUrl)
                    Toast.makeText(context, "Profile updated (readiness check skipped)", Toast.LENGTH_LONG).show()
                }
            }
        ) {
            viewModel.updateServerProfile(profileId, newName, newUrl)
            Toast.makeText(context, "Profile updated", Toast.LENGTH_SHORT).show()
        }
    }

    fun handleDeleteServerProfile(profileId: String) {
        settingsRepository.getProfiles().firstOrNull { it.id == profileId }?.let { profile ->
            settingsRepository.clearSilencedAuthPromptForUrl(profile.url)
        }
        viewModel.deleteServerProfile(profileId)
        Toast.makeText(context, "Profile deleted", Toast.LENGTH_SHORT).show()
    }

    fun handleSwitchServerProfile(profileId: String) {
        val newProfile = settingsRepository.getProfiles().firstOrNull { it.id == profileId } ?: return

        if (!serverUrlValidator.isValid(newProfile.url)) {
            DiagnosticsLogger.record(
                context,
                "server_switch_health_check_blocked",
                mapOf(
                    "origin" to DiagnosticsLogger.originOnly(newProfile.url),
                    "decision" to "invalid_url"
                )
            )
            Toast.makeText(context, "Invalid server URL: ${newProfile.url}", Toast.LENGTH_LONG).show()
            return
        }

        serverValidationJob?.cancel()
        DiagnosticsLogger.record(
            context,
            "server_switch_health_check_start",
            mapOf(
                "origin" to DiagnosticsLogger.originOnly(newProfile.url),
                "profile_id" to newProfile.id
            )
        )
        viewModel.setServerValidationState(
            isChecking = true,
            message = "Checking ${newProfile.name} before switching...",
            isError = false
        )
        serverValidationJob = activityScope.launch {
            val result = HermesApiClient.checkServerReadiness(newProfile.url)
            val reachable = result.isReady || HermesApiClient.isServerReachable(newProfile.url)
            DiagnosticsLogger.record(
                context,
                "server_switch_health_check_result",
                mapOf(
                    "origin" to DiagnosticsLogger.originOnly(newProfile.url),
                    "profile_id" to newProfile.id,
                    "status" to result.status.name,
                    "ready" to result.isReady.toString(),
                    "reachable" to reachable.toString()
                )
            )

            if (result.isReady) {
                val message = "${newProfile.name} is reachable. Switch to this server now?"
                viewModel.setServerValidationState(
                    isChecking = false,
                    message = message,
                    isError = false
                )
                showServerSwitchConfirmation(newProfile, "Server reachable", message)
                return@launch
            }

            if (reachable && result.status == HermesApiClient.ServerReadinessStatus.AUTH_REQUIRED) {
                if (settingsRepository.isAuthPromptSilencedForUrl(newProfile.url)) {
                    DiagnosticsLogger.record(
                        context,
                        "server_switch_auth_required_auto_proceed_silenced",
                        mapOf(
                            "origin" to DiagnosticsLogger.originOnly(newProfile.url),
                            "profile_id" to newProfile.id
                        )
                    )
                    viewModel.clearServerValidationState()
                    performServerProfileSwitch(newProfile)
                    return@launch
                }
                val message = "${newProfile.name} is reachable, but requires sign-in before Android can read /api/status. Switch and sign in?"
                viewModel.setServerValidationState(
                    isChecking = false,
                    message = message,
                    isError = false
                )
                showAuthRequiredSwitchConfirmation(newProfile, message)
                return@launch
            }

            val blockedMessage = "${newProfile.name}: ${result.message}"
            viewModel.setServerValidationState(
                isChecking = false,
                message = blockedMessage,
                isError = true
            )
            DiagnosticsLogger.record(
                context,
                "server_switch_health_check_blocked",
                mapOf(
                    "origin" to DiagnosticsLogger.originOnly(newProfile.url),
                    "profile_id" to newProfile.id,
                    "status" to result.status.name,
                    "decision" to "stay_current_server"
                )
            )
            showServerHealthBlockedDialog(newProfile, result)
        }
    }

    private fun showServerSwitchConfirmation(profile: ServerProfile, title: String, message: String) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel") { dialog, _ ->
                DiagnosticsLogger.record(
                    context,
                    "server_switch_cancelled",
                    mapOf("origin" to DiagnosticsLogger.originOnly(profile.url), "profile_id" to profile.id)
                )
                dialog.dismiss()
            }
            .setPositiveButton("Switch") { _, _ ->
                performServerProfileSwitch(profile)
            }
            .show()
    }

    private fun showAuthRequiredSwitchConfirmation(profile: ServerProfile, message: String) {
        val padding = (16 * context.resources.displayMetrics.density).toInt()
        val checkBox = android.widget.CheckBox(context).apply {
            text = "Don't ask again for this server"
        }
        val messageView = android.widget.TextView(context).apply {
            text = message
            setPadding(0, 0, 0, padding)
        }
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(messageView)
            addView(checkBox)
        }

        AlertDialog.Builder(context)
            .setTitle("Sign-in required")
            .setView(container)
            .setNegativeButton("Cancel") { dialog, _ ->
                DiagnosticsLogger.record(
                    context,
                    "server_switch_cancelled",
                    mapOf("origin" to DiagnosticsLogger.originOnly(profile.url), "profile_id" to profile.id)
                )
                dialog.dismiss()
            }
            .setPositiveButton("Switch") { _, _ ->
                if (checkBox.isChecked) {
                    settingsRepository.silenceAuthPromptForUrl(profile.url)
                    DiagnosticsLogger.record(
                        context,
                        "server_switch_auth_prompt_silenced",
                        mapOf(
                            "origin" to DiagnosticsLogger.originOnly(profile.url),
                            "profile_id" to profile.id
                        )
                    )
                }
                performServerProfileSwitch(profile)
            }
            .show()
    }

    private fun showServerHealthBlockedDialog(
        profile: ServerProfile,
        result: HermesApiClient.ServerReadinessResult
    ) {
        showServerValidationRecoveryDialog(
            url = profile.url,
            result = result,
            positiveLabel = "Switch anyway"
        ) { performServerProfileSwitch(profile) }
    }

    private fun showServerValidationRecoveryDialog(
        url: String,
        result: HermesApiClient.ServerReadinessResult,
        positiveLabel: String,
        onProceedAnyway: () -> Unit
    ) {
        val body = buildString {
            appendLine(result.message)
            result.diagnostics?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("Diagnostics:")
                append(it)
            }
        }.trim()
        DiagnosticsLogger.record(
            context,
            "server_validation_recovery_dialog_shown",
            mapOf(
                "origin" to DiagnosticsLogger.originOnly(url),
                "status" to result.status.name
            )
        )
        AlertDialog.Builder(context)
            .setTitle("Server check failed")
            .setMessage(body)
            .setNeutralButton("Open in browser") { _, _ ->
                DiagnosticsLogger.record(
                    context,
                    "server_validation_open_browser",
                    mapOf(
                        "origin" to DiagnosticsLogger.originOnly(url),
                        "status" to result.status.name
                    )
                )
                onOpenInExternalBrowser(url)
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton(positiveLabel) { _, _ ->
                DiagnosticsLogger.record(
                    context,
                    "server_validation_proceed_anyway",
                    mapOf(
                        "origin" to DiagnosticsLogger.originOnly(url),
                        "status" to result.status.name
                    )
                )
                onProceedAnyway()
            }
            .show()
    }

    private fun performServerProfileSwitch(profile: ServerProfile) {
        DiagnosticsLogger.record(
            context,
            "server_switch_confirmed",
            mapOf("origin" to DiagnosticsLogger.originOnly(profile.url), "profile_id" to profile.id)
        )
        onPerformServerProfileSwitch(profile)
    }

    private fun validateServerBeforePersist(
        serverUrl: String,
        openSettingsOnFailure: Boolean = false,
        onFailure: ((HermesApiClient.ServerReadinessResult) -> Unit)? = null,
        onSuccess: () -> Unit
    ) {
        serverValidationJob?.cancel()
        DiagnosticsLogger.record(
            context,
            "server_validation_start",
            mapOf("origin" to DiagnosticsLogger.originOnly(serverUrl))
        )
        viewModel.setServerValidationState(
            isChecking = true,
            message = "Checking Hermes server readiness...",
            isError = false
        )
        serverValidationJob = activityScope.launch {
            val result = HermesApiClient.checkServerReadiness(serverUrl)
            DiagnosticsLogger.record(
                context,
                "server_validation_result",
                mapOf(
                    "origin" to DiagnosticsLogger.originOnly(serverUrl),
                    "status" to result.status.name,
                    "ready" to result.isReady.toString()
                )
            )

            val authRequiredButReachable = !result.isReady &&
                result.status == HermesApiClient.ServerReadinessStatus.AUTH_REQUIRED &&
                HermesApiClient.isServerReachable(serverUrl)
            if (authRequiredButReachable) {
                DiagnosticsLogger.record(
                    context,
                    "server_validation_soft_pass_auth_required",
                    mapOf("origin" to DiagnosticsLogger.originOnly(serverUrl))
                )
                viewModel.clearServerValidationState()
                Toast.makeText(
                    context,
                    "Server reachable — sign in on the Hermes page to finish.",
                    Toast.LENGTH_LONG
                ).show()
                onSuccess()
                return@launch
            }

            if (!result.isReady) {
                viewModel.setServerValidationState(
                    isChecking = false,
                    message = result.message,
                    isError = true,
                    details = result.diagnostics
                )
                val handled = onFailure != null
                if (handled) {
                    onFailure.invoke(result)
                } else {
                    if (openSettingsOnFailure) {
                        viewModel.openSettingsWithServerValidation(result.message, details = result.diagnostics)
                    }
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            viewModel.clearServerValidationState()
            onSuccess()
        }
    }

    private fun normalizeServerProfileUrl(url: String): String {
        return url.trim().trimEnd('/').lowercase()
    }
}
