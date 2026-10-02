package com.hermeswebui.android.webui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Android half of the WebUI voice conversation contract: WebView capture is granted only
 * while the activity is foregrounded, foreground loss is signalled to WebUI immediately,
 * and nothing on the Android side ever re-arms listening.
 */
class VoiceCaptureLifecycleGateTest {
    private class Request(val name: String)

    private val granted = mutableListOf<Request>()
    private val denied = mutableListOf<Request>()
    private val signals = mutableListOf<Boolean>()
    private var promptLaunches = 0

    private val gate = VoiceCaptureLifecycleGate<Request>(
        grantAudioCapture = { granted += it },
        denyAudioCapture = { denied += it },
        launchRecordAudioPrompt = { promptLaunches++ },
        signalAppForeground = { signals += it }
    )

    private fun foreground() {
        gate.onStart()
        gate.onResume()
        signals.clear()
    }

    // 1. Foreground arming
    @Test
    fun `trusted capture is granted only while the activity is resumed`() {
        val beforeStart = Request("before-start")
        gate.onCaptureRequested(beforeStart, trustedAudioRequest = true, hasRecordAudioPermission = true)
        assertThat(denied).containsExactly(beforeStart)

        foreground()
        val armed = Request("armed")
        gate.onCaptureRequested(armed, trustedAudioRequest = true, hasRecordAudioPermission = true)

        assertThat(granted).containsExactly(armed)
    }

    @Test
    fun `started but not resumed activity cannot acquire the microphone`() {
        gate.onStart()
        val request = Request("visible-not-resumed")

        gate.onCaptureRequested(request, trustedAudioRequest = true, hasRecordAudioPermission = true)

        assertThat(granted).isEmpty()
        assertThat(denied).containsExactly(request)
    }

    @Test
    fun `untrusted origin is denied even in the foreground`() {
        foreground()
        val request = Request("untrusted")

        gate.onCaptureRequested(request, trustedAudioRequest = false, hasRecordAudioPermission = true)

        assertThat(granted).isEmpty()
        assertThat(denied).containsExactly(request)
        assertThat(promptLaunches).isEqualTo(0)
    }

    @Test
    fun `resume tells WebUI the app is foregrounded`() {
        gate.onStart()
        gate.onResume()

        assertThat(signals).containsExactly(true)
    }

    // 2. Background loss
    @Test
    fun `pause signals foreground loss immediately`() {
        foreground()

        gate.onPause()

        assertThat(signals).containsExactly(false)
    }

    @Test
    fun `stop signals foreground loss even when pause was skipped`() {
        foreground()

        gate.onStop()

        assertThat(signals).containsExactly(false)
    }

    @Test
    fun `capture requested after foreground loss is denied`() {
        foreground()
        gate.onPause()
        val late = Request("after-pause")

        gate.onCaptureRequested(late, trustedAudioRequest = true, hasRecordAudioPermission = true)

        assertThat(granted).isEmpty()
        assertThat(denied).containsExactly(late)
    }

    // 3. No auto-rearm
    @Test
    fun `returning to the foreground only reports state and never grants capture`() {
        foreground()
        gate.onPause()
        gate.onStop()
        signals.clear()

        gate.onStart()
        gate.onResume()

        assertThat(signals).containsExactly(true)
        assertThat(granted).isEmpty()
        assertThat(promptLaunches).isEqualTo(0)
    }

    // 4. Screen lock / app hide path: lock delivers onPause then onStop.
    @Test
    fun `screen lock sequence releases at pause and stays released through stop`() {
        foreground()

        gate.onPause()
        assertThat(signals).containsExactly(false)
        gate.onStop()

        assertThat(signals).containsExactly(false, false).inOrder()
        assertThat(signals).doesNotContain(true)
    }

    @Test
    fun `page loaded while backgrounded learns it is not foregrounded`() {
        foreground()
        gate.onPause()
        gate.onStop()
        signals.clear()

        gate.onPageReady()

        assertThat(signals).containsExactly(false)
    }

    @Test
    fun `page loaded in the foreground learns it may arm`() {
        foreground()

        gate.onPageReady()

        assertThat(signals).containsExactly(true)
    }

    // 6. Late results: an OS grant that lands after foreground loss is never applied.
    @Test
    fun `record audio grant arriving after the app stopped is denied`() {
        foreground()
        val request = Request("prompted")
        gate.onCaptureRequested(request, trustedAudioRequest = true, hasRecordAudioPermission = false)
        gate.onPause()
        gate.onStop()

        gate.onRecordAudioPromptResult(granted = true, stillTrusted = { true })

        assertThat(granted).isEmpty()
        assertThat(denied).containsExactly(request)
    }

    @Test
    fun `stale prompt request is replaced and denied by a newer request`() {
        foreground()
        val first = Request("first")
        val second = Request("second")
        gate.onCaptureRequested(first, trustedAudioRequest = true, hasRecordAudioPermission = false)
        gate.onCaptureRequested(second, trustedAudioRequest = true, hasRecordAudioPermission = false)

        gate.onRecordAudioPromptResult(granted = true, stillTrusted = { true })

        assertThat(denied).containsExactly(first)
        assertThat(granted).containsExactly(second)
    }

    @Test
    fun `cancelled WebView request is not granted by a later prompt result`() {
        foreground()
        val request = Request("navigated-away")
        gate.onCaptureRequested(request, trustedAudioRequest = true, hasRecordAudioPermission = false)

        gate.onCaptureRequestCanceled(request)
        gate.onRecordAudioPromptResult(granted = true, stillTrusted = { true })

        assertThat(granted).isEmpty()
        assertThat(gate.isRecordAudioPromptPending).isFalse()
    }

    @Test
    fun `prompt result for a page that lost trust is denied`() {
        foreground()
        val request = Request("origin-changed")
        gate.onCaptureRequested(request, trustedAudioRequest = true, hasRecordAudioPermission = false)
        gate.onPause()

        gate.onRecordAudioPromptResult(granted = true, stillTrusted = { false })

        assertThat(granted).isEmpty()
        assertThat(denied).containsExactly(request)
    }

    // 7. Permission denial
    @Test
    fun `record audio denial denies the WebView request without granting`() {
        foreground()
        val request = Request("arm")
        gate.onCaptureRequested(request, trustedAudioRequest = true, hasRecordAudioPermission = false)
        gate.onPause()

        val userDenied = gate.onRecordAudioPromptResult(granted = false, stillTrusted = { true })

        assertThat(userDenied).isTrue()
        assertThat(granted).isEmpty()
        assertThat(denied).containsExactly(request)
        assertThat(gate.isRecordAudioPromptPending).isFalse()
    }

    @Test
    fun `missing record audio permission launches exactly one prompt and grants nothing yet`() {
        foreground()

        gate.onCaptureRequested(Request("arm"), trustedAudioRequest = true, hasRecordAudioPermission = false)

        assertThat(promptLaunches).isEqualTo(1)
        assertThat(granted).isEmpty()
        assertThat(gate.isRecordAudioPromptPending).isTrue()
    }

    @Test
    fun `the record audio prompt pausing the activity does not cancel the arming request`() {
        // No capture can exist before RECORD_AUDIO is granted, so the prompt's own pause
        // must not tell WebUI to abandon the arm the user just tapped.
        foreground()
        val request = Request("first-arm")
        gate.onCaptureRequested(request, trustedAudioRequest = true, hasRecordAudioPermission = false)

        gate.onPause()
        gate.onRecordAudioPromptResult(granted = true, stillTrusted = { true })
        gate.onResume()

        assertThat(signals).containsExactly(true)
        assertThat(granted).containsExactly(request)
    }

    @Test
    fun `leaving the app while the record audio prompt is open denies the request`() {
        foreground()
        val request = Request("first-arm")
        gate.onCaptureRequested(request, trustedAudioRequest = true, hasRecordAudioPermission = false)
        gate.onPause()

        gate.onStop()

        assertThat(denied).containsExactly(request)
        assertThat(signals).containsExactly(false)
        assertThat(gate.isRecordAudioPromptPending).isFalse()
    }

    @Test
    fun `prompt result without a pending request is ignored`() {
        foreground()

        val userDenied = gate.onRecordAudioPromptResult(granted = false, stillTrusted = { true })

        assertThat(userDenied).isFalse()
        assertThat(granted).isEmpty()
        assertThat(denied).isEmpty()
    }

    // 8. Android never creates a listener of its own: the gate only grants or denies the
    // request WebUI made and reports foreground state.
    @Test
    fun `granting is driven only by WebUI requests`() {
        gate.onStart()
        gate.onResume()
        gate.onPageReady()
        gate.onPause()
        gate.onResume()

        assertThat(granted).isEmpty()
        assertThat(denied).isEmpty()
        assertThat(promptLaunches).isEqualTo(0)
    }
}
