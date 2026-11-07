package com.hermeswebui.android.ui.settings

import android.content.ClipboardManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.hermeswebui.android.R
import org.junit.Rule
import org.junit.Test

class ServerSetupHelpTest {
    private companion object {
        const val CLIPBOARD_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 250L
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bothButtonsCopyTheirCompleteDistinctPromptsToTheAndroidClipboard() {
        compose.setContent { MaterialTheme { ServerSetupHelp(initiallyExpanded = true) } }
        for ((label, resource) in listOf(
            R.string.setup_copy_webui to R.raw.setup_webui_prompt,
            R.string.setup_copy_webapp to R.raw.setup_webapp_prompt
        )) {
            val expected = compose.activity.resources.openRawResource(resource).bufferedReader().use { it.readText().trim() }
            compose.onNodeWithText(compose.activity.getString(label)).assertIsDisplayed().performClick()
            awaitClipboardText(expected, compose.activity.getString(label))
        }
    }

    /**
     * Writing the clipboard never needs focus, but since Android 10 only the focused app can read it
     * back. A CI emulator can briefly hand focus to a system surface, which used to fail this test on
     * timing alone. Poll for the expected text, nudge focus back when it is missing, and if it still
     * does not arrive fail with the focused window so the cause is named.
     */
    private fun awaitClipboardText(expected: String, label: String) {
        val deadline = SystemClock.uptimeMillis() + CLIPBOARD_TIMEOUT_MS
        var attempts = 0
        var lastText: String? = null
        var focused = false
        while (SystemClock.uptimeMillis() < deadline) {
            compose.runOnIdle {
                focused = compose.activity.hasWindowFocus()
                lastText = compose.activity.getSystemService(ClipboardManager::class.java)
                    ?.primaryClip?.getItemAt(0)?.text?.toString()
            }
            if (lastText == expected) return
            if (!focused && attempts % 4 == 1) recoverFocus()
            attempts++
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError(
            "Clipboard did not contain the '$label' prompt after ${CLIPBOARD_TIMEOUT_MS}ms. " +
                "windowFocus=$focused, clipboardChars=${lastText?.length ?: "null (unreadable without focus, or empty)"}. " +
                "Focused window: ${describeFocus()}"
        )
    }

    private fun recoverFocus() {
        compose.runOnUiThread { compose.activity.window.decorView.requestFocus() }
        shell("cmd statusbar collapse")
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        shell("wm dismiss-keyguard")
    }

    private fun describeFocus(): String = shell("dumpsys window")
        .lineSequence()
        .filter { "mCurrentFocus" in it || "mFocusedApp" in it }
        .joinToString(" | ") { it.trim() }
        .ifBlank { "unavailable" }

    private fun shell(command: String): String = try {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        ""
    }

    @Test
    fun configuredHelpStaysCollapsedUntilRequestedAndCanBeHiddenAgain() {
        compose.setContent { MaterialTheme { ServerSetupHelp(initiallyExpanded = false) } }
        compose.onNodeWithText("Copy command to set up Hermes WebApp").assertDoesNotExist()
        compose.onNodeWithText("Need help setting up a server?").performClick()
        compose.onNodeWithText("Copy command to set up WebUI").assertIsDisplayed()
        compose.onNodeWithText("Copy command to set up Hermes WebApp").assertIsDisplayed()
        compose.onNodeWithText("Hide server setup help").performClick()
        compose.onNodeWithText("Copy command to set up WebUI").assertDoesNotExist()
    }
}
