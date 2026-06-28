package com.hermeswebui.android.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GitHubReleaseUpdateChecker(
    private val apiUrl: String,
    private val fallbackReleaseUrl: String
) {
    suspend fun check(currentVersion: String): AppUpdateCheckResult = withContext(Dispatchers.IO) {
        if (apiUrl.isBlank()) return@withContext AppUpdateCheckResult.Unsupported

        runCatching {
            val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Hermes-Android")
            }
            connection.use {
                val status = it.responseCode
                if (status !in 200..299) {
                    return@withContext AppUpdateCheckResult.Failed("GitHub returned HTTP $status.")
                }

                val payload = it.inputStream.bufferedReader().use { reader -> reader.readText() }
                val release = JSONObject(payload)
                val tagName = release.optString("tag_name").trim()
                if (tagName.isBlank()) {
                    return@withContext AppUpdateCheckResult.Failed("GitHub release response did not include a tag.")
                }

                if (!AppVersionComparator.isNewer(tagName, currentVersion)) {
                    return@withContext AppUpdateCheckResult.Current
                }

                val version = AppVersionComparator.normalize(tagName)
                val releaseUrl = release.optString("html_url")
                    .takeIf { url -> url.isNotBlank() }
                    ?: fallbackReleaseUrl
                val name = release.optString("name")
                    .takeIf { value -> value.isNotBlank() }
                    ?: "Hermes WebUI Android v$version"
                val apkAsset = release.optJSONArray("assets")?.findGitHubApkAsset()
                val notes = release.optString("body")
                    .takeIf { value -> value.isNotBlank() }
                    ?.summarizeReleaseNotes()

                AppUpdateCheckResult.Available(
                    version = version,
                    releaseUrl = releaseUrl,
                    downloadUrl = apkAsset?.downloadUrl,
                    fileName = apkAsset?.name,
                    releaseNotes = notes,
                    title = "Hermes WebUI update available",
                    body = buildString {
                        append("$name is available for the GitHub APK channel.")
                        if (!notes.isNullOrBlank()) {
                            append("\n\nWhat's changed:\n")
                            append(notes)
                        }
                    }
                )
            }
        }.getOrElse { error ->
            AppUpdateCheckResult.Failed(error.message ?: "Could not check GitHub releases.")
        }
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }

    private data class GitHubAsset(val name: String, val downloadUrl: String)

    private fun JSONArray.findGitHubApkAsset(): GitHubAsset? {
        for (index in 0 until length()) {
            val asset = optJSONObject(index) ?: continue
            val name = asset.optString("name").trim()
            val downloadUrl = asset.optString("browser_download_url").trim()
            if (
                name.endsWith("-github.apk", ignoreCase = true) &&
                downloadUrl.startsWith("https://", ignoreCase = true)
            ) {
                return GitHubAsset(name = name, downloadUrl = downloadUrl)
            }
        }
        return null
    }

    private fun String.summarizeReleaseNotes(): String {
        return lineSequence()
            .map { line -> line.trim() }
            .filter { line ->
                line.isNotBlank() &&
                    !line.startsWith("#") &&
                    !line.startsWith("**Full Changelog", ignoreCase = true)
            }
            .take(6)
            .joinToString("\n")
            .take(700)
    }
}
