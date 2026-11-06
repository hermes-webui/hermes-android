package com.hermeswebui.android.ui.settings

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.R
import org.junit.Rule
import org.junit.Test

class ServerSetupHelpTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun bothButtonsCopyTheirCompleteDistinctPromptsToTheAndroidClipboard() {
        compose.setContent { MaterialTheme { ServerSetupHelp(initiallyExpanded = true) } }
        for ((label, resource) in listOf(
            R.string.setup_copy_webui to R.raw.setup_webui_prompt,
            R.string.setup_copy_webapp to R.raw.setup_webapp_prompt
        )) {
            compose.onNodeWithText(compose.activity.getString(label)).assertIsDisplayed().performClick()
            compose.runOnIdle {
                val activity = compose.activity
                assertThat(activity.hasWindowFocus()).isTrue()
                val clipboard = activity.getSystemService(ClipboardManager::class.java)
                val expected = activity.resources.openRawResource(resource).bufferedReader().use { it.readText().trim() }
                assertThat(clipboard.primaryClip?.getItemAt(0)?.text?.toString()).isEqualTo(expected)
            }
        }
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
