package com.hermeswebui.android.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.ServerType
import com.hermeswebui.android.launcher.LauncherIcon
import org.junit.Rule
import org.junit.Test

class AppearanceSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun launcherChoiceIsIndependentFromServerType() {
        var choice: LauncherIcon? = null
        compose.setContent {
            MaterialTheme { LauncherIconPicker(LauncherIcon.WEBUI) { choice = it } }
        }
        compose.onNodeWithText("Hermes WebUI").assertIsSelected()
        compose.onNodeWithText("Hermes Agent").performClick()
        assertThat(choice).isEqualTo(LauncherIcon.AGENT)
    }

    @Test
    fun editingTypeCommitsOnlyOnSaveAndCancelDoesNotPersist() {
        var result: ServerType? = null
        var cancelled = false
        compose.setContent {
            var visible by remember { mutableStateOf(true) }
            MaterialTheme {
                if (visible) EditProfileDialog(
                    currentName = "Forge",
                    currentUrl = "https://forge.example.test",
                    currentType = ServerType.WEBUI,
                    onConfirm = { _, _, type -> result = type },
                    onDismiss = { cancelled = true; visible = false }
                )
            }
        }
        compose.onNodeWithText("Hermes WebUI").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Hermes Agent").performScrollTo().performClick()
        assertThat(result).isNull()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(cancelled).isTrue()
        assertThat(result).isNull()
        compose.onNodeWithText("Save").assertDoesNotExist()
    }

    @Test
    fun savingTypeReturnsTheChosenLabel() {
        var result: ServerType? = null
        compose.setContent {
            MaterialTheme {
                EditProfileDialog(
                    currentName = "Server",
                    currentUrl = "https://hermes.example.test",
                    currentType = ServerType.WEBUI,
                    onConfirm = { _, _, type -> result = type },
                    onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("Hermes Agent").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        assertThat(result).isEqualTo(ServerType.AGENT)
    }
}
