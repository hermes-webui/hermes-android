package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Hermes WebUI owns voice capture, wake detection, STT and TTS. Android stays a thin host:
 * no native listener that could run beside WebUI's, and no foreground service type that
 * would let microphone capture outlive the activity.
 */
class VoiceCaptureOwnershipContractTest {
    private val mainDir: File = listOf(File("src/main"), File("app/src/main"))
        .firstOrNull { it.isDirectory }
        ?: error("Run from the repository root or the app module")

    private val manifest: String = File(mainDir, "AndroidManifest.xml").readText()

    private val kotlinSources: Map<String, String> = File(mainDir, "java")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .associate { it.relativeTo(mainDir).path to it.readText() }

    @Test
    fun `no foreground service may hold the microphone`() {
        val serviceTypes = Regex("""android:foregroundServiceType="([^"]*)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toList()

        assertThat(serviceTypes).isNotEmpty()
        serviceTypes.forEach { assertThat(it).doesNotContain("microphone") }
        assertThat(manifest).doesNotContain("FOREGROUND_SERVICE_MICROPHONE")
    }

    @Test
    fun `android does not create a second microphone listener beside WebUI`() {
        val nativeCaptureApis = listOf(
            "android.media.AudioRecord",
            "android.media.MediaRecorder",
            "android.speech.SpeechRecognizer",
            "android.speech.RecognizerIntent"
        )

        kotlinSources.forEach { (path, source) ->
            nativeCaptureApis.forEach { api ->
                assertWithMessage(path).that(source).doesNotContain(api)
            }
        }
    }

    @Test
    fun `android does not play speech of its own that could overlap WebUI capture`() {
        kotlinSources.forEach { (path, source) ->
            assertWithMessage(path).that(source).doesNotContain("android.speech.tts")
        }
    }

    @Test
    fun `foreground signal is origin-guarded like the other WebUI runtime scripts`() {
        val mainActivity = kotlinSources.entries
            .single { it.key.endsWith("MainActivity.kt") }
            .value
        val signal = mainActivity
            .substringAfter("private fun signalAppForegroundToWebUi(")
            .substringBefore("\n    }")

        assertThat(signal).contains("matchesConfiguredWebUiRoute(webView.url)")
        assertThat(signal).contains("HermesWebUiScripts.buildOriginGuardedRuntimeScript(")
        assertThat(signal).contains("HermesWebUiScripts.buildAppForegroundScript(active)")
    }

    @Test
    fun `background services never touch WebView capture`() {
        kotlinSources
            .filterKeys { it.contains("/background/") }
            .forEach { (path, source) ->
                assertWithMessage(path).that(source).doesNotContain("PermissionRequest")
                assertWithMessage(path).that(source).doesNotContain("RECORD_AUDIO")
            }
    }
}
