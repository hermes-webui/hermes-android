package com.hermeswebui.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import javax.net.ssl.SSLException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal client for Hermes WebUI dashboard API calls made natively by the app.
 *
 * The dashboard exposes a public /api/status liveness endpoint that requires no
 * authentication (see hermes_cli/dashboard_auth/public_paths.py). The app uses
 * it to distinguish "server is down" from "content/render error" when the
 * WebView reports a load failure.
 */
object HermesApiClient {
    private const val TIMEOUT_MS = 6_000
    private const val RECONNECT_SSE_PATH = "/api/sessions/events"
    private val hermesRootMarkers = listOf(
        "Hermes WebUI",
        "HermesWebUI",
        "hermes-webui",
        "hermes webui"
    )

    enum class ServerReadinessStatus {
        READY,
        AUTH_REQUIRED,
        SETUP_REQUIRED,
        UNREACHABLE,
        NOT_HERMES,
        REDIRECTED,
        UNKNOWN_ERROR
    }

    data class ServerReadinessResult(
        val isReady: Boolean,
        val message: String,
        val status: ServerReadinessStatus = if (isReady) ServerReadinessStatus.READY else ServerReadinessStatus.UNKNOWN_ERROR
    )

    private val sseFeatureKeys = setOf(
        "session_sse",
        "session_sse_enabled",
        "session_sse_summary",
        "session_sse_summary_enabled",
        "sse",
        "sse_enabled",
        "sse_summary"
    )

    private val hermesStatusFingerprintKeys = setOf(
        "release_date",
        "hermes_home",
        "config_path",
        "gateway_running",
        "authenticated",
        "setup_mode",
        "initialized",
        "status"
    )

    /**
     * Describes the SSE capability level detected on the server.
     */
    enum class SseCapability {
        /** The WebUI gateway/session SSE probe reports the feature enabled and healthy. */
        SESSION_SSE_ENABLED,
        /** The lightweight reconnect SSE stream is available, even if gateway/session SSE is not. */
        RECONNECT_STREAM_AVAILABLE,
        /**
         * The probe reported the gateway/session SSE feature disabled on this server.
         * This is the common "agent sessions not enabled" case and should be
         * presented with a clear server-settings message rather than a generic error.
         */
        FEATURE_DISABLED,
        /** No SSE capability detected (network error or unexpected server response). */
        NONE
    }

    /** The prompt text a user can paste into Hermes chat to ask it to enable session SSE. */
    const val SSE_ENABLE_HERMES_PROMPT =
        "Please enable Hermes WebUI agent sessions / gateway SSE on this server. " +
        "Turn on the server setting that exposes /api/sessions/gateway/stream " +
        "(the probe currently reports 'agent sessions not enabled'), then restart Hermes if needed. " +
        "After that, re-run the Android app's SSE support check."

    private data class GatewayProbeResult(
        val httpStatus: Int,
        val enabled: Boolean?,
        val ok: Boolean?
    )

    private data class SseStreamProbeResult(
        val httpStatus: Int,
        val contentType: String?
    )

    /**
     * Returns true if the Hermes WebUI server responds to its public
     * liveness endpoint at [baseUrl]/api/status.
     */
    suspend fun isServerReachable(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URI(baseUrl.trimEnd('/')).resolve("/api/status").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299 || code == 401 || code == 403
        } catch (_: Exception) {
            false
        }
    }

    suspend fun checkServerReadiness(baseUrl: String): ServerReadinessResult = withContext(Dispatchers.IO) {
        try {
            val url = URI(baseUrl.trimEnd('/')).resolve("/api/status").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")

            val code = conn.responseCode
            val contentType = conn.contentType
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            if (code in setOf(401, 403, 404)) {
                val rootFallback = probeHermesRootPage(baseUrl)
                if (rootFallback != null) {
                    return@withContext rootFallback
                }
            }

            interpretServerStatusResponse(
                httpStatus = code,
                contentType = contentType,
                rawBody = body
            )
        } catch (exception: Exception) {
            when {
                exception is UnknownHostException -> {
                    ServerReadinessResult(
                        isReady = false,
                        message = "Could not find that host. Check the server name and try again.",
                        status = ServerReadinessStatus.UNREACHABLE
                    )
                }
                exception is ConnectException -> {
                    ServerReadinessResult(
                        isReady = false,
                        message = "Could not connect to this server. Check that Hermes is running and reachable from Android.",
                        status = ServerReadinessStatus.UNREACHABLE
                    )
                }
                exception is SocketTimeoutException -> {
                    ServerReadinessResult(
                        isReady = false,
                        message = "The server took too long to respond. Check that Hermes finished starting up and try again.",
                        status = ServerReadinessStatus.UNREACHABLE
                    )
                }
                exception is SSLHandshakeException || exception is SSLProtocolException || exception is SSLException || exception.message.orEmpty().contains("ssl", ignoreCase = true) -> {
                    ServerReadinessResult(
                        isReady = false,
                        message = "Could not connect securely. Check whether this Hermes server should use http:// instead of https://.",
                        status = ServerReadinessStatus.UNREACHABLE
                    )
                }
                else -> {
                    ServerReadinessResult(
                        isReady = false,
                        message = "Could not reach this Hermes server. Check the URL, scheme, and whether the server is online.",
                        status = ServerReadinessStatus.UNREACHABLE
                    )
                }
            }
        }
    }

    private suspend fun probeHermesRootPage(baseUrl: String): ServerReadinessResult? = withContext(Dispatchers.IO) {
        try {
            val rootUrl = URI(baseUrl.trimEnd('/')).resolve("/").toURL()
            val conn = rootUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")

            val code = conn.responseCode
            val contentType = conn.contentType
            val serverHeader = conn.getHeaderField("Server")
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            interpretHermesRootResponse(
                httpStatus = code,
                contentType = contentType,
                serverHeader = serverHeader,
                rawBody = body
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun interpretServerStatusResponse(
        httpStatus: Int,
        contentType: String?,
        rawBody: String
    ): ServerReadinessResult {
        if (httpStatus !in 200..299) {
            return when (httpStatus) {
                301, 302, 307, 308 -> ServerReadinessResult(
                    isReady = false,
                    message = "This URL redirected instead of returning Hermes status directly. Use the final Hermes WebUI URL instead.",
                    status = ServerReadinessStatus.REDIRECTED
                )
                503 -> ServerReadinessResult(
                    isReady = false,
                    message = "Hermes responded, but it is not ready yet. Finish the server's initial setup and try again.",
                    status = ServerReadinessStatus.SETUP_REQUIRED
                )
                404 -> ServerReadinessResult(
                    isReady = false,
                    message = "This URL responded, but it does not expose Hermes WebUI's /api/status endpoint.",
                    status = ServerReadinessStatus.NOT_HERMES
                )
                401, 403 -> ServerReadinessResult(
                    isReady = false,
                    message = "Hermes requires sign-in before Android can verify /api/status. Open the server in the app or browser and sign in, then try again.",
                    status = ServerReadinessStatus.AUTH_REQUIRED
                )
                else -> ServerReadinessResult(
                    isReady = false,
                    message = "Hermes returned HTTP $httpStatus from /api/status. Make sure the server is fully initialized.",
                    status = ServerReadinessStatus.UNKNOWN_ERROR
                )
            }
        }

        val payload = runCatching { JSONObject(rawBody) }.getOrNull()
        if (payload == null) {
            return ServerReadinessResult(
                isReady = false,
                message = if (contentType?.contains("json", ignoreCase = true) == true) {
                    "Hermes returned an unreadable status response."
                } else {
                    "This server responded, but it does not look like a ready Hermes WebUI instance. Check the URL and finish setup first."
                },
                status = ServerReadinessStatus.NOT_HERMES
            )
        }

        val setupMode = payload.opt("setup_mode")
        if (setupMode is Boolean && setupMode) {
            return ServerReadinessResult(
                isReady = false,
                message = "This Hermes server is still in initial setup. Finish setup in the browser before adding it in Android.",
                status = ServerReadinessStatus.SETUP_REQUIRED
            )
        }

        val initialized = payload.opt("initialized")
        if (initialized is Boolean && !initialized) {
            return ServerReadinessResult(
                isReady = false,
                message = "This Hermes server is still in initial setup. Finish setup in the browser before adding it in Android.",
                status = ServerReadinessStatus.SETUP_REQUIRED
            )
        }

        val status = payload.optString("status").trim().lowercase()
        if (status in setOf("setup", "initial_setup", "not_ready", "initializing")) {
            return ServerReadinessResult(
                isReady = false,
                message = "This Hermes server is not ready yet. Finish setup and wait for startup to complete before adding it.",
                status = ServerReadinessStatus.SETUP_REQUIRED
            )
        }

        if (looksLikeHermesStatusPayload(payload)) {
            return ServerReadinessResult(
                isReady = true,
                message = "Hermes server is reachable.",
                status = ServerReadinessStatus.READY
            )
        }

        return ServerReadinessResult(
            isReady = false,
            message = "This server responded, but it does not look like a ready Hermes WebUI instance.",
            status = ServerReadinessStatus.NOT_HERMES
        )
    }

    internal fun interpretHermesRootResponse(
        httpStatus: Int,
        contentType: String?,
        serverHeader: String?,
        rawBody: String
    ): ServerReadinessResult? {
        if (httpStatus !in 200..299) return null

        val normalizedServerHeader = serverHeader.orEmpty().trim()
        if (normalizedServerHeader.startsWith("HermesWebUI/", ignoreCase = true)) {
            return ServerReadinessResult(
                isReady = true,
                message = "Hermes server is reachable.",
                status = ServerReadinessStatus.READY
            )
        }

        val normalizedContentType = contentType.orEmpty()
        val normalizedBody = rawBody.lowercase()
        val looksLikeHtml = normalizedContentType.contains("text/html", ignoreCase = true)
        val hasHermesMarker = hermesRootMarkers.any { marker ->
            normalizedBody.contains(marker.lowercase())
        }
        if (looksLikeHtml && hasHermesMarker) {
            return ServerReadinessResult(
                isReady = true,
                message = "Hermes server is reachable.",
                status = ServerReadinessStatus.READY
            )
        }

        return null
    }

    private fun looksLikeHermesStatusPayload(payload: JSONObject): Boolean {
        val version = payload.optString("version").trim()
        if (version.isBlank()) return false
        return hermesStatusFingerprintKeys.any(payload::has)
    }

    /**
     * Probes the lightweight reconnect SSE endpoint at [baseUrl]/api/sessions/events.
     * Returns response metadata when reachable, or null on network/connection error.
     */
    private suspend fun probeReconnectSseEndpoint(baseUrl: String): SseStreamProbeResult? = withContext(Dispatchers.IO) {
        try {
            val url = URI(baseUrl.trimEnd('/')).resolve(RECONNECT_SSE_PATH).toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "text/event-stream")
            val code = conn.responseCode
            val contentType = conn.contentType
            conn.disconnect()
            SseStreamProbeResult(
                httpStatus = code,
                contentType = contentType
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Probes the WebUI gateway SSE endpoint at [baseUrl]/api/sessions/gateway/stream?probe=1.
     * Returns parsed probe data when reachable, or null on network/connection error.
     *
     * Callers should interpret:
     *  - 200 with enabled=true and ok=true → SSE is enabled and healthy
     *  - 404 with enabled=false            → agent sessions / gateway SSE disabled
     *  - 503 with enabled=true and ok=false → route exists, but watcher is unhealthy
     *  - null                              → could not reach server at all
     */
    private suspend fun probeGatewayStreamEndpoint(baseUrl: String): GatewayProbeResult? = withContext(Dispatchers.IO) {
        try {
            val url = URI(baseUrl.trimEnd('/')).resolve("/api/sessions/gateway/stream?probe=1").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            val probeJson = runCatching { JSONObject(body) }.getOrNull()
            GatewayProbeResult(
                httpStatus = code,
                enabled = probeJson?.optBoolean("enabled"),
                ok = probeJson?.optBoolean("ok")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Returns the detected [SseCapability] level for the given server.
     *
     * Priority:
     * 1. If /api/status reports a truthy SSE flag → SESSION_SSE_ENABLED
     * 2. If the gateway probe reports enabled=true and ok=true → SESSION_SSE_ENABLED
     * 3. If /api/sessions/events responds with text/event-stream → RECONNECT_STREAM_AVAILABLE
     * 4. If the gateway probe reports enabled=false or HTTP 404 → FEATURE_DISABLED
     * 5. Otherwise → NONE (network error / unreachable)
     */
    suspend fun detectSseCapability(baseUrl: String): SseCapability = withContext(Dispatchers.IO) {
        var statusReportsSse = false
        try {
            val statusUrl = URI(baseUrl.trimEnd('/')).resolve("/api/status").toURL()
            val conn = statusUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                statusReportsSse = parseSseFeatureFlag(body)
            } else {
                conn.disconnect()
            }
        } catch (_: Exception) { /* fall through to gateway probe */ }

        val gatewayProbe = probeGatewayStreamEndpoint(baseUrl)
        val reconnectProbe = probeReconnectSseEndpoint(baseUrl)
        decideSseCapability(
            statusReportsSse = statusReportsSse,
            gatewayEnabled = gatewayProbe?.enabled,
            gatewayOk = gatewayProbe?.ok,
            gatewayHttpStatus = gatewayProbe?.httpStatus,
            reconnectHttpStatus = reconnectProbe?.httpStatus,
            reconnectContentType = reconnectProbe?.contentType
        )
    }

    internal fun decideSseCapability(
        statusReportsSse: Boolean,
        gatewayEnabled: Boolean?,
        gatewayOk: Boolean?,
        gatewayHttpStatus: Int?,
        reconnectHttpStatus: Int?,
        reconnectContentType: String?
    ): SseCapability {
        if (statusReportsSse) return SseCapability.SESSION_SSE_ENABLED

        if (gatewayEnabled == true && gatewayOk == true) {
            return SseCapability.SESSION_SSE_ENABLED
        }

        val reconnectIsUsable = reconnectHttpStatus in 200..299 &&
            isEventStreamContentType(reconnectContentType)
        if (reconnectIsUsable) {
            return SseCapability.RECONNECT_STREAM_AVAILABLE
        }

        return when {
            gatewayHttpStatus == null -> SseCapability.NONE
            gatewayEnabled == false || gatewayHttpStatus == 404 -> SseCapability.FEATURE_DISABLED
            else -> SseCapability.NONE
        }
    }

    /** Convenience wrapper — returns true only if SSE is actually usable (not just "disabled"). */
    suspend fun isSessionSseSupported(baseUrl: String): Boolean {
        val cap = detectSseCapability(baseUrl)
        return cap == SseCapability.SESSION_SSE_ENABLED || cap == SseCapability.RECONNECT_STREAM_AVAILABLE
    }

    /**
     * Returns true when the lightweight reconnect SSE stream is reachable.
     * Android uses this stream for native reconnect detection when SSE transport is enabled.
     */
    suspend fun isReconnectSseReachable(baseUrl: String): Boolean {
        val probe = probeReconnectSseEndpoint(baseUrl)
        return probe?.httpStatus in 200..299 && isEventStreamContentType(probe?.contentType)
    }

    private fun isEventStreamContentType(contentType: String?): Boolean {
        return contentType?.contains("text/event-stream", ignoreCase = true) == true
    }

    private fun parseSseFeatureFlag(rawJson: String): Boolean {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return false

        fun normalize(key: String): String {
            return key.trim().lowercase().replace("-", "_").replace(" ", "_")
        }

        fun parseBooleanish(value: Any?): Boolean? {
            return when (value) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> when (value.trim().lowercase()) {
                    "true", "enabled", "on", "1" -> true
                    "false", "disabled", "off", "0" -> false
                    else -> null
                }
                else -> null
            }
        }

        fun inspect(value: Any?): Boolean? {
            when (value) {
                is JSONObject -> {
                    var foundFalse = false
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        val normalized = normalize(key)
                        if (normalized in sseFeatureKeys) {
                            val parsed = parseBooleanish(child)
                            if (parsed == true) return true
                            if (parsed == false) foundFalse = true
                        }

                        val nested = inspect(child)
                        if (nested == true) return true
                        if (nested == false) foundFalse = true
                    }
                    return if (foundFalse) false else null
                }
                is JSONArray -> {
                    var foundFalse = false
                    for (index in 0 until value.length()) {
                        val child = value.opt(index)
                        if (child is String && normalize(child) in sseFeatureKeys) {
                            return true
                        }
                        val nested = inspect(child)
                        if (nested == true) return true
                        if (nested == false) foundFalse = true
                    }
                    return if (foundFalse) false else null
                }
                else -> return parseBooleanish(value)
            }
        }

        return inspect(root) == true
    }
}

