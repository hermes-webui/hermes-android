package com.hermeswebui.android.background

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Spawns a `logcat` process as early as possible during process start so the
 * debug log file is being written to disk before the foreground service
 * notification, runtime permission prompts, or any error UI can race ahead.
 *
 * The foreground service ([HermesDebugLoggingService]) still runs as the
 * long-lived owner (so the OS does not kill the process and so the user has a
 * visible Stop action). When the service starts, it sees that bootstrap is
 * already capturing and stays out of the way — both processes append into the
 * same dated log file so nothing is lost during the handoff.
 *
 * Only activated on debuggable builds. On release builds this is a no-op.
 */
object DebugLogBootstrap {
    @Volatile
    private var process: Process? = null

    @Volatile
    private var logFile: File? = null

    private val lock = Any()

    /** Returns the file currently being captured to, or `null` if bootstrap is inactive. */
    fun activeLogFile(): File? = logFile

    /** Returns `true` while bootstrap owns an active `logcat` process. */
    fun isActive(): Boolean = process?.isAlive == true

    fun startIfDebuggable(context: Context) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        synchronized(lock) {
            if (process?.isAlive == true) return
            val logDir = File(context.filesDir, "debug-logs").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(logDir, "hermes-debug-$timestamp.log")
            runCatching {
                file.writeText(bootstrapHeader(context))
            }
            val launched = runCatching {
                ProcessBuilder(
                    "logcat",
                    "-v",
                    "threadtime",
                    "-b",
                    "main",
                    "-b",
                    "system",
                    "-b",
                    "crash",
                    "-T",
                    "1"
                )
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(file))
                    .redirectErrorStream(true)
                    .start()
            }.getOrElse {
                runCatching {
                    ProcessBuilder("logcat", "-v", "threadtime", "-T", "1")
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(file))
                        .redirectErrorStream(true)
                        .start()
                }.getOrNull()
            }
            process = launched
            logFile = file
        }
    }

    fun stop() {
        synchronized(lock) {
            runCatching { process?.destroy() }
            process = null
        }
    }

    private fun bootstrapHeader(context: Context): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val appVersion = packageInfo?.versionName ?: "unknown"
        return buildString {
            appendLine("=== Hermes Android Debug Log (early bootstrap) ===")
            appendLine("captured_from: onCreate (pre-service)")
            appendLine("timestamp: $now")
            appendLine("app_version: $appVersion")
            appendLine("package: ${context.packageName}")
            appendLine("android_api: ${Build.VERSION.SDK_INT}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("==================================================")
            appendLine()
        }
    }
}

