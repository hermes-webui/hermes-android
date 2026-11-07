package com.hermeswebui.android.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class WelcomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var finishCount = 0

    private val pageTitles = listOf(
        R.string.welcome_intro_title,
        R.string.welcome_interfaces_title,
        R.string.welcome_setup_title,
        R.string.welcome_remote_title,
        R.string.welcome_ready_title
    )

    @Before
    fun render() {
        finishCount = 0
        compose.setContent { MaterialTheme { WelcomeScreen(onFinish = { finishCount++ }) } }
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private fun assertOnPage(index: Int) {
        compose.onNodeWithText(text(pageTitles[index])).assertIsDisplayed()
    }

    private fun next() {
        compose.onNodeWithTag(WelcomeTags.NEXT).performClick()
        compose.waitForIdle()
    }

    private fun goToPage(index: Int) {
        repeat(index) { next() }
        assertOnPage(index)
    }

    @Test
    fun startsOnTheIntroPageWithSkipAndNoBackButton() {
        assertOnPage(0)
        compose.onNodeWithText(text(R.string.welcome_intro_body)).assertIsDisplayed()
        compose.onNodeWithTag(WelcomeTags.SKIP).assertIsDisplayed()
        compose.onNodeWithTag(WelcomeTags.NEXT).assertIsDisplayed()
        compose.onNodeWithTag(WelcomeTags.BACK).assertDoesNotExist()
    }

    @Test
    fun nextAndBackStepThroughEveryPageInOrder() {
        for (index in 1 until pageTitles.size) {
            next()
            assertOnPage(index)
        }
        for (index in pageTitles.size - 2 downTo 0) {
            compose.onNodeWithTag(WelcomeTags.BACK).performClick()
            compose.waitForIdle()
            assertOnPage(index)
        }
        assertThat(finishCount).isEqualTo(0)
    }

    @Test
    fun swipingMovesForwardAndBackBetweenPages() {
        compose.onNodeWithTag(WelcomeTags.PAGER).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertOnPage(1)
        compose.onNodeWithTag(WelcomeTags.PAGER).performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertOnPage(0)
    }

    @Test
    fun interfacePageExplainsBothWebUiAndTheOfficialWebapp() {
        goToPage(1)
        compose.onNodeWithText(text(R.string.welcome_webui_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.welcome_webapp_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.welcome_webapp_body)).assertIsDisplayed()
    }

    @Test
    fun setupPageOffersBothCopyCommandsAndSaysCopyingChecksNothing() {
        goToPage(2)
        compose.onNodeWithText(text(R.string.setup_copy_webui)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_copy_webapp)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.welcome_setup_note)).assertIsDisplayed()
    }

    @Test
    fun awayFromHomePageListsEveryRemoteAccessOption() {
        goToPage(3)
        WelcomeLinks.remoteAccessOptions.forEach { option ->
            compose.onNodeWithText(option.name).assertExists()
        }
        compose.onNodeWithText(text(R.string.welcome_remote_note)).assertExists()
    }

    @Test
    fun lastPageShowsGetStartedInsteadOfSkipAndNext() {
        goToPage(pageTitles.lastIndex)
        compose.onNodeWithTag(WelcomeTags.GET_STARTED).assertIsDisplayed()
        compose.onNodeWithTag(WelcomeTags.SKIP).assertDoesNotExist()
        compose.onNodeWithTag(WelcomeTags.NEXT).assertDoesNotExist()
    }

    @Test
    fun introPageShowsTheAgentAndAppDiagram() {
        compose.onNodeWithText(text(R.string.welcome_diagram_agent)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.welcome_diagram_app)).assertIsDisplayed()
    }

    @Test
    fun lastPageListsWhatTheUserWillNeed() {
        goToPage(pageTitles.lastIndex)
        compose.onNodeWithText(text(R.string.welcome_needs_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.welcome_needs_address)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.welcome_needs_signin)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.welcome_needs_private)).assertIsDisplayed()
    }

    @Test
    fun skipFinishesTheGuideOnce() {
        compose.onNodeWithTag(WelcomeTags.SKIP).performClick()
        assertThat(finishCount).isEqualTo(1)
    }

    @Test
    fun getStartedFinishesTheGuideOnce() {
        goToPage(pageTitles.lastIndex)
        compose.onNodeWithTag(WelcomeTags.GET_STARTED).performClick()
        assertThat(finishCount).isEqualTo(1)
    }

    @Test
    fun systemBackStepsToThePreviousPageAndOnlyFinishesFromTheFirst() {
        goToPage(1)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertOnPage(0)
        assertThat(finishCount).isEqualTo(0)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertThat(finishCount).isEqualTo(1)
    }
}
