package com.hermeswebui.android.data

import android.content.Context
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DiagnosticsLogger {
    private const val MAX_LINES = 500
    private const val DIRECTORY = "diagnostics"
    private const val FILE_NAME = "hermes-events.log"
    private val lock = Any()

    fun record(context: Context, event: String, fields: Map<String, String?> = emptyMap()) {
        val enrichedFields = buildMap {
            put("app_version", appVersion(context))
            putAll(fields)
        }
        val line = buildLine(event, enrichedFields)
        synchronized(lock) {
            val file = diagnosticsFile(context)
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
            trimIfNeeded(file)
        }
    }

    fun recentText(context: Context): String {
        return synchronized(lock) {
            val file = diagnosticsFile(context)
            if (!file.isFile) return@synchronized ""
            file.readText()
        }
    }

    fun originOnly(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return ""
        return runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            if (scheme !in setOf("http", "https") || host.isBlank()) return@runCatching ""
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrDefault("")
    }

    fun pathOnly(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return ""
        return runCatching {
            val path = URI(raw).path.orEmpty()
            path.ifBlank { "/" }
        }.getOrDefault("")
    }

    private fun buildLine(event: String, fields: Map<String, String?>): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val safeEvent = sanitizeToken(event)
        val suffix = fields.entries
            .mapNotNull { (key, value) ->
                val safeValue = sanitizeValue(value)
                if (safeValue.isBlank()) null else "${sanitizeToken(key)}=$safeValue"
            }
            .joinToString(" ")
        return listOf(timestamp, safeEvent, suffix)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun sanitizeToken(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(64)
    }

    private fun sanitizeValue(value: String?): String {
        return value
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(180)
            .orEmpty()
    }

    private fun diagnosticsFile(context: Context): File {
        return File(File(context.filesDir, DIRECTORY), FILE_NAME)
    }

    private fun appVersion(context: Context): String {
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    private fun trimIfNeeded(file: File) {
        val lines = file.readLines()
        if (lines.size <= MAX_LINES) return
        file.writeText(lines.takeLast(MAX_LINES).joinToString(separator = "\n", postfix = "\n"))
    }
}
