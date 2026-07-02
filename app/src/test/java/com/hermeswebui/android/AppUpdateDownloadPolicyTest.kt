package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.update.AppUpdateDownloadPolicy
import org.junit.Test

/** Regression guard for the exported-activity APK-download host confinement (committee P2). */
class AppUpdateDownloadPolicyTest {
    @Test
    fun `github release download url is trusted`() {
        assertThat(
            AppUpdateDownloadPolicy.isTrustedApkDownloadUrl(
                "https://github.com/owner/repo/releases/download/v1.0.0/hermes-webui-v1.0.0-github.apk"
            )
        ).isTrue()
    }

    @Test
    fun `githubusercontent asset host is trusted`() {
        assertThat(
            AppUpdateDownloadPolicy.isTrustedApkDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/app-github.apk"
            )
        ).isTrue()
        assertThat(AppUpdateDownloadPolicy.isTrustedApkDownloadHost("release-assets.githubusercontent.com")).isTrue()
    }

    @Test
    fun `attacker host is rejected`() {
        assertThat(
            AppUpdateDownloadPolicy.isTrustedApkDownloadUrl("https://attacker.example/hermes-update.apk")
        ).isFalse()
    }

    @Test
    fun `lookalike hosts are rejected`() {
        assertThat(AppUpdateDownloadPolicy.isTrustedApkDownloadHost("github.com.evil.com")).isFalse()
        assertThat(AppUpdateDownloadPolicy.isTrustedApkDownloadHost("notgithubusercontent.com")).isFalse()
        assertThat(AppUpdateDownloadPolicy.isTrustedApkDownloadHost(null)).isFalse()
    }

    @Test
    fun `non https or non apk urls are rejected`() {
        assertThat(AppUpdateDownloadPolicy.isTrustedApkDownloadUrl("http://github.com/x/app-github.apk")).isFalse()
        assertThat(AppUpdateDownloadPolicy.isTrustedApkDownloadUrl("https://github.com/x/malware.sh")).isFalse()
        assertThat(AppUpdateDownloadPolicy.isTrustedApkDownloadUrl(null)).isFalse()
    }
}
