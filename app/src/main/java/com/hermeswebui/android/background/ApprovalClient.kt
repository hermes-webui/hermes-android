package com.hermeswebui.android.background

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

internal data class PendingApprovalSnapshot(
    val approvalId: String,
    val sessionId: String?,
    val choices: List<String>
)

internal object ApprovalClient {
    fun fetchPendingApproval(
        baseUrl: String,
        sessionId: String,
        cookieHeader: String?
    ): PendingApprovalSnapshot? {
        val encodedSessionId = runCatching {
            URLEncoder.encode(sessionId, Charsets.UTF_8.name())
        }.getOrNull() ?: return null
        val url = runCatching {
            URI(baseUrl.trimEnd('/')).resolve("/api/approval/pending?session_id=$encodedSessionId").toURL()
        }.getOrNull() ?: return null

        val connection = (runCatching { url.openConnection() as HttpURLConnection }.getOrNull()) ?: return null
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 4_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (!cookieHeader.isNullOrBlank()) {
                connection.setRequestProperty("Cookie", cookieHeader)
            }

            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parsePendingApproval(body, sessionId)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    fun submitApprovalResponse(
        baseUrl: String,
        sessionId: String,
        approvalId: String,
        choice: String,
        cookieHeader: String?
    ): Boolean {
        val url = runCatching {
            URI(baseUrl.trimEnd('/')).resolve("/api/approval/respond").toURL()
        }.getOrNull() ?: return false
        val payload = JSONObject().apply {
            put("choice", choice)
            put("approval_id", approvalId)
            put("session_id", sessionId)
        }.toString()

        val connection = (runCatching { url.openConnection() as HttpURLConnection }.getOrNull()) ?: return false
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 4_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (!cookieHeader.isNullOrBlank()) {
                connection.setRequestProperty("Cookie", cookieHeader)
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
            }
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    internal fun parsePendingApproval(rawJson: String, fallbackSessionId: String?): PendingApprovalSnapshot? {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val pending = firstPendingObject(root) ?: root
        val approvalId = pending.optString("approval_id").trim().takeIf { it.isNotBlank() } ?: return null
        val sessionId = pending.optString("session_id").trim().takeIf { it.isNotBlank() } ?: fallbackSessionId
        val choices = (pending.optJSONArray("choices") ?: pending.optJSONArray("choices_offered"))
            ?.let(::parseChoices)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("once", "session", "always", "deny")
        return PendingApprovalSnapshot(
            approvalId = approvalId,
            sessionId = sessionId,
            choices = choices
        )
    }

    private fun parseChoices(array: JSONArray): List<String> {
        return linkedSetOf<String>().apply {
            for (index in 0 until array.length()) {
                val choice = ApprovalActionSupport.normalizeChoice(array.optString(index)) ?: continue
                add(choice)
            }
        }.toList()
    }

    private fun firstPendingObject(root: JSONObject): JSONObject? {
        root.optJSONObject("pending")?.let { return it }
        root.optJSONArray("pending_queue")?.optJSONObject(0)?.let { return it }
        root.optJSONArray("queue")?.optJSONObject(0)?.let { return it }
        root.optJSONArray("approvals")?.optJSONObject(0)?.let { return it }
        return null
    }
}