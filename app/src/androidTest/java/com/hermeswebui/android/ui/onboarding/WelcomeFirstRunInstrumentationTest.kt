package com.hermeswebui.android.ui.onboarding

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.R
import com.hermeswebui.android.background.HermesDebugLoggingService
import com.hermeswebui.android.data.SettingsRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real MainActivity against isolated preferences to prove who sees the first-run
 * welcome guide: a brand-new user does, once; an existing user, someone who skipped it, and
 * someone who opened a deep link do not; and Settings can reopen it.
 */
@RunWith(AndroidJUnit4::class)
class WelcomeFirstRunInstrumentationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    private val introTitle: String get() = context.getString(R.string.welcome_intro_title)

    /** Unconfigured server-setup screen shows this button; the welcome guide never does. */
    private val serverSetupMarker = "Connect"

    @Test
    fun brandNewUserSeesTheGuideOnceAndSkipLeadsToServerSetup() {
        withFreshPreferences { repository ->
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitText(introTitle)
                assertThat(compose.onAllNodesWithText(serverSetupMarker).fetchSemanticsNodes()).isEmpty()

                compose.onNodeWithTag(WelcomeTags.SKIP).performClick()

                awaitText(serverSetupMarker)
                assertThat(compose.onAllNodesWithText(introTitle).fetchSemanticsNodes()).isEmpty()
                assertThat(repository.hasSeenWelcomeGuide()).isTrue()
            }
        }
    }

    @Test
    fun aUserWhoAlreadySawTheGuideGoesStraightToServerSetup() {
        withFreshPreferences { repository ->
            repository.markWelcomeGuideSeen()
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitText(serverSetupMarker)
                assertThat(compose.onAllNodesWithText(introTitle).fetchSemanticsNodes()).isEmpty()
            }
        }
    }

    @Test
    fun anExistingUserWithAConfiguredServerNeverSeesTheGuide() {
        withFreshPreferences { repository ->
            // Closed local port: the server is unreachable, but it is configured, which is what matters here.
            repository.saveAppUrls("http://127.0.0.1:9", "")
            assertThat(repository.hasSeenWelcomeGuide()).isFalse()
            ActivityScenario.launch(MainActivity::class.java).use {
                // Give the first frame a chance to show the guide if it was going to.
                Thread.sleep(2_000)
                compose.waitForIdle()
                assertThat(compose.onAllNodesWithText(introTitle).fetchSemanticsNodes()).isEmpty()
                assertThat(repository.hasSeenWelcomeGuide()).isTrue()
            }
        }
    }

    @Test
    fun theSettingsRecoveryLinkOnAFreshInstallSkipsTheGuide() {
        withFreshPreferences {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("hermes://app/settings"))
                .setClass(context, MainActivity::class.java)
            ActivityScenario.launch<MainActivity>(intent).use {
                awaitText(serverSetupMarker)
                assertThat(compose.onAllNodesWithText(introTitle).fetchSemanticsNodes()).isEmpty()
            }
        }
    }

    @Test
    fun settingsCanReopenTheGuideAndClosingItReturnsToSettings() {
        withFreshPreferences { repository ->
            repository.markWelcomeGuideSeen()
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitText(serverSetupMarker)

                compose.onNodeWithText(context.getString(R.string.welcome_settings_entry))
                    .performScrollTo()
                    .performClick()
                awaitText(introTitle)
                compose.onNodeWithText(introTitle).assertIsDisplayed()

                compose.onNodeWithTag(WelcomeTags.SKIP).performClick()
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithText(introTitle).fetchSemanticsNodes().isEmpty()
                }
                // Back on the settings sheet it was opened from.
                assertThat(compose.onAllNodesWithText(serverSetupMarker).fetchSemanticsNodes()).isNotEmpty()
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Runs [block] against empty encrypted preferences, then restores the exact originals. */
    private fun withFreshPreferences(block: (SettingsRepository) -> Unit) {
        val preferences = context.getSharedPreferences("hermes_secure_prefs", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        val loggingWasEnabled = SettingsRepository(context).isDebugLoggingEnabled()
        try {
            check(preferences.edit().clear().commit())
            block(SettingsRepository(context))
        } finally {
            if (!loggingWasEnabled) HermesDebugLoggingService.stop(context)
            val editor = preferences.edit().clear()
            original.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    else -> error("Unexpected preference type")
                }
            }
            check(editor.commit())
        }
    }
}
