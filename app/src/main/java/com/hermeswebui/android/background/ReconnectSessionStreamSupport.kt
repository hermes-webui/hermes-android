package com.hermeswebui.android.background

import com.hermeswebui.android.core.security.UrlOrigins
import org.json.JSONObject
import java.net.URI

internal data class ReconnectNotificationUpdate(
    val body: String,
    val targetUrl: String?
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

        return when (normalizedEvent) {
            "activity_summary" -> summary?.let { ReconnectNotificationUpdate(it, targetUrl) }
            "bg_task_complete", "process_complete" -> {
                val status = payload.optString("status").trim().lowercase()
                val body = when {
                    !summary.isNullOrBlank() && status == "error" -> "Hermes reported an error: $summary"
                    !summary.isNullOrBlank() -> summary
                    status == "error" -> "Hermes reported an error in the background task."
                    else -> "Hermes finished a background task."
                }
                ReconnectNotificationUpdate(body, targetUrl)
            }
            "server_turn_started" -> {
                val inputType = payload.optString("input_type").trim()
                val body = if (inputType.isNotBlank()) {
                    "Hermes started working on a $inputType request."
                } else {
                    "Hermes started working on your current session."
                }
                ReconnectNotificationUpdate(body, targetUrl)
            }
            "initial" -> summary?.let { ReconnectNotificationUpdate(it, targetUrl) }
            else -> null
        }
    }

    private fun resolveRoute(baseUrl: String, route: String): String {
        return runCatching {
            URI(baseUrl.trimEnd('/')).resolve(route).toString()
        }.getOrDefault(baseUrl)
    }
}