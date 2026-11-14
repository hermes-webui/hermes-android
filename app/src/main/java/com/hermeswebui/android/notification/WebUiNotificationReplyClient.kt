package com.hermeswebui.android.notification

import android.util.Log
import com.hermeswebui.android.background.ReconnectSessionStreamSupport
import com.hermeswebui.android.core.security.UrlOrigins
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Timer
import java.util.TimerTask

internal enum class NotificationReplyResult { Sent, Rejected, Unconfirmed }

internal object WebUiNotificationReplyClient {
    private val timeouts = Timer("HermesReplyTimeout", true)

    fun send(target: NotificationReplyTarget, message: String, cookie: String?): NotificationReplyResult {
        val sessionId = ReconnectSessionStreamSupport.sessionIdFromUrl(target.targetUrl)
            ?: return NotificationReplyResult.Rejected
        val endpoint = URI(endpointUrl(target.targetUrl)).toURL()
        val connection = endpoint.openConnection() as HttpURLConnection
        val timeout = object : TimerTask() {
            override fun run() { connection.disconnect() }
        }
        timeouts.schedule(timeout, 8_000)
        return try {
            connection.apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 3_000
                readTimeout = 4_000
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
            }
            val payload = JSONObject()
                .put("session_id", sessionId)
                .put("profile", target.profile)
                .put("message", message)
                .toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            if (status !in 200..299) {
                return if (status in 300..499 && status != 408) NotificationReplyResult.Rejected
                else NotificationReplyResult.Unconfirmed
            }
            val response = connection.inputStream.bufferedReader().use {
                val buffer = CharArray(16_385)
                var count = 0
                while (count < buffer.size) {
                    val read = it.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count == buffer.size) return NotificationReplyResult.Unconfirmed
                String(buffer, 0, count)
            }
            val result = JSONObject(response)
            if (result.optString("stream_id").isNotBlank() &&
                result.optString("session_id") == sessionId &&
                result.optString("error").isBlank()) {
                NotificationReplyResult.Sent
            } else {
                NotificationReplyResult.Unconfirmed
            }
        } catch (error: IOException) {
            Log.w("HermesReply", "Could not confirm notification reply transport (${error.javaClass.simpleName})")
            NotificationReplyResult.Unconfirmed
        } catch (error: org.json.JSONException) {
            Log.w("HermesReply", "Could not confirm notification reply response")
            NotificationReplyResult.Unconfirmed
        } finally {
            timeout.cancel()
            timeouts.purge()
            connection.disconnect()
        }
    }

    fun endpointUrl(targetUrl: String): String =
        UrlOrigins.normalizeOriginUrl(targetUrl) +
            URI(targetUrl).rawPath.split('/').dropLast(2).joinToString("/") + "/api/chat/start"
}
