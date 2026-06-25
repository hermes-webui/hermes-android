package com.hermeswebui.android.background

import com.hermeswebui.android.core.security.UrlOrigins
import org.json.JSONObject
import java.net.URI

internal data class ReconnectNotificationUpdate(
    val body: String,
    val targetUrl: String?,
    val isTerminal: Boolean = false,
    val approvalRequest: NotificationApprovalRequest? = null
)

internal data class NotificationApprovalRequest(
    val approvalId: String,
    val description: String,
    val choices: List<String>,
    val pendingCount: Int
)

internal object ReconnectSessionStreamSupport {
    fun sessionIdFromUrl(currentUrl: String?): String? {
        if (currentUrl.isNullOrBlank()) return null
        val segments = UrlOrigins.normalizedPath(currentUrl)
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() }
        return segments.singleOrNull()
    }

    fun notificationUpdateForEvent(
        baseUrl: String,
        fallbackTargetUrl: String?,
        eventName: String?,
        rawData: String
    ): ReconnectNotificationUpdate? {
        val normalizedEvent = eventName?.trim().orEmpty()
        val payload = runCatching { JSONObject(rawData) }.getOrNull() ?: return null
        val targetUrl = payload.optString("route")
            .takeIf { it.isNotBlank() }
            ?.let { resolveRoute(baseUrl, it) }
            ?: fallbackTargetUrl

        val summary = payload.optString("summary")
            .takeIf { it.isNotBlank() }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        val description = payload.optString("description")
            .takeIf { it.isNotBlank() }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        val error = payload.optString("error")
            .takeIf { it.isNotBlank() }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
        val toolSummary = payload.optJSONObject("tool")?.let(::toolSummary)

        return when (normalizedEvent) {
            "activity_summary" -> formatActivitySummary(summary, toolSummary)
                ?.let { ReconnectNotificationUpdate(it, targetUrl) }
            "approval_required" -> {
                val body = description ?: summary ?: "Hermes needs your approval to continue."
                ReconnectNotificationUpdate(
                    body = body,
                    targetUrl = targetUrl,
                    approvalRequest = approvalRequestFromPayload(payload, body)
                )
            }
            "bg_task_complete", "process_complete", "turn_completed" -> {
                val status = payload.optString("status").trim().lowercase()
                val body = when {
                    !summary.isNullOrBlank() && status == "error" -> "Hermes reported an error: $summary"
                    !summary.isNullOrBlank() -> summary
                    status == "error" -> "Hermes reported an error in the background task."
                    else -> "Hermes finished a background task."
                }
                ReconnectNotificationUpdate(body, targetUrl, isTerminal = true)
            }
            "turn_failed" -> {
                val body = when {
                    !error.isNullOrBlank() -> "Hermes reported an error: $error"
                    !summary.isNullOrBlank() -> "Hermes reported an error: $summary"
                    else -> "Hermes reported an error while working in the background."
                }
                ReconnectNotificationUpdate(body, targetUrl, isTerminal = true)
            }
            "server_turn_started", "turn_started" -> {
                val inputType = payload.optString("input_type").trim()
                val body = if (inputType.isNotBlank()) {
                    "Hermes started working on a $inputType request."
                } else {
                    "Hermes started working on your current session."
                }
                ReconnectNotificationUpdate(body, targetUrl)
            }
            "initial" -> formatActivitySummary(summary, toolSummary)
                ?.let { ReconnectNotificationUpdate(it, targetUrl) }
            else -> null
        }
    }

    private fun formatActivitySummary(summary: String?, toolSummary: String?): String? {
        return when {
            !summary.isNullOrBlank() && !toolSummary.isNullOrBlank() -> "$summary Latest tool: $toolSummary"
            !summary.isNullOrBlank() -> summary
            !toolSummary.isNullOrBlank() -> "Latest tool: $toolSummary"
            else -> null
        }
    }

    private fun approvalRequestFromPayload(
        payload: JSONObject,
        fallbackDescription: String
    ): NotificationApprovalRequest? {
        val approvalId = payload.optString("approval_id").trim().takeIf { it.isNotBlank() } ?: return null
        val choices = payload.optJSONArray("choices")
            ?.let(::approvalChoices)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("once", "session", "always", "deny")
        return NotificationApprovalRequest(
            approvalId = approvalId,
            description = fallbackDescription,
            choices = choices,
            pendingCount = payload.optInt("pending_count", 0)
        )
    }

    private fun approvalChoices(array: org.json.JSONArray): List<String> {
        return buildList {
            for (index in 0 until array.length()) {
                val choice = ApprovalActionSupport.normalizeChoice(array.optString(index)) ?: continue
                add(choice)
            }
        }
    }

    private fun toolSummary(tool: JSONObject): String? {
        val name = tool.optString("name").trim().takeIf { it.isNotBlank() } ?: return null
        val status = tool.optString("status").trim().takeIf { it.isNotBlank() }
        return if (status.isNullOrBlank()) name else "$name ($status)"
    }

    private fun resolveRoute(baseUrl: String, route: String): String {
        return runCatching {
            URI(baseUrl.trimEnd('/')).resolve(route).toString()
        }.getOrDefault(baseUrl)
    }
}