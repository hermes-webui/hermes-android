package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.update.AppUpdateCheckResult
import com.hermeswebui.android.update.GitHubReleaseUpdateChecker
import org.junit.Test

class GitHubReleaseUpdateCheckerTest {
    @Test
    fun currentReleaseReturnsCurrent() {
        val result = parseRelease("""{"tag_name":"v1.1.0"}""")

        assertThat(result).isEqualTo(AppUpdateCheckResult.Current)
    }

    @Test
    fun newerReleaseSelectsOnlySecureGithubApkAndSummarizesNotes() {
        val result = parseRelease(
            """
            {
              "tag_name": "v1.1.1",
              "name": "Hermes 1.1.1",
              "html_url": "https://github.com/hermes-webui/hermes-android/releases/tag/v1.1.1",
              "body": "## What's Changed\n\n- Fixed settings\n- Hardened updates\n\n**Full Changelog**: ignored",
              "assets": [
                {"name":"hermes-webui-v1.1.1.apk","browser_download_url":"https://github.com/wrong.apk"},
                {"name":"hermes-webui-v1.1.1-github.apk","browser_download_url":"http://github.com/insecure.apk"},
                {"name":"hermes-webui-v1.1.1-github.apk","browser_download_url":"https://github.com/hermes-webui/hermes-android/releases/download/v1.1.1/hermes-webui-v1.1.1-github.apk"}
              ]
            }
            """.trimIndent()
        )

        val available = result as AppUpdateCheckResult.Available
        assertThat(available.version).isEqualTo("1.1.1")
        assertThat(available.fileName).isEqualTo("hermes-webui-v1.1.1-github.apk")
        assertThat(available.downloadUrl).startsWith("https://github.com/")
        assertThat(available.releaseNotes).isEqualTo("- Fixed settings\n- Hardened updates")
        assertThat(available.body).contains("Hermes 1.1.1 is available")
    }

    @Test
    fun missingReleaseUrlUsesConfiguredFallback() {
        val result = parseRelease("""{"tag_name":"v1.1.1","assets":[]}""")

        val available = result as AppUpdateCheckResult.Available
        assertThat(available.releaseUrl).isEqualTo(FALLBACK_URL)
        assertThat(available.downloadUrl).isNull()
    }

    @Test
    fun missingTagAndMalformedJsonFailClosed() {
        assertThat(parseRelease("{}"))
            .isEqualTo(AppUpdateCheckResult.Failed("GitHub release response did not include a tag."))
        assertThat(parseRelease("not json")).isInstanceOf(AppUpdateCheckResult.Failed::class.java)
    }

    private fun parseRelease(payload: String): AppUpdateCheckResult {
        return GitHubReleaseUpdateChecker.parseReleasePayload(
            payload = payload,
            currentVersion = "1.1.0",
            fallbackReleaseUrl = FALLBACK_URL
        )
    }

    private companion object {
        const val FALLBACK_URL = "https://github.com/hermes-webui/hermes-android/releases/latest"
    }
}
