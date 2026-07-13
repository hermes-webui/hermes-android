package com.hermeswebui.android.webui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HermesWebUiScriptsTest {
    @Test
    fun `app settings script preserves folded navigation selectors`() {
        val script = HermesWebUiScripts.appSettingsEntryScript

        assertThat(script).contains("width < 799")
        assertThat(script).contains("button.nav-tab.has-tooltip--bottom[data-tooltip=\"Settings\"]")
        assertThat(script).contains(".mobile-nav button[data-tooltip=\"Settings\"]")
        assertThat(script).contains(".bottom-nav button[data-tooltip=\"Settings\"]")
        assertThat(script).contains("findCompactSettingsAnchor() || findAnchorByKind('settings') || findAnchorByKind('help')")
    }

    @Test
    fun `app settings script routes to native application settings deep link`() {
        val script = HermesWebUiScripts.appSettingsEntryScript

        assertThat(script).contains("var appSettingsHref = 'hermes://app/settings';")
        assertThat(script).contains("window.location.href = appSettingsHref;")
        assertThat(script).contains("Application Settings")
    }

    @Test
    fun `notification bridge builder injects bridge name and permission`() {
        val script = HermesWebUiScripts.buildNotificationBridgeScript(
            bridgeName = "HermesAndroidNotifications",
            initialPermission = "granted"
        )

        assertThat(script).contains("var bridgeName = \"HermesAndroidNotifications\";")
        assertThat(script).contains("var initialPermission = \"granted\";")
        assertThat(script).contains("window.__hermesAndroidSetNotificationPermission")
    }

    @Test
    fun `viewport fix script patches collapsed and expanded update summary containers`() {
        val script = HermesWebUiScripts.viewportFixScript

        assertThat(script).contains("#updateSummaryPanel { max-height:")
        assertThat(script).contains("#updateSummaryScroll { max-height:")
        assertThat(script).contains("#updateSummaryPanel.update-summary-expanded #updateSummaryScroll { max-height:")
        assertThat(script).contains("viewportWidth > 0 && viewportWidth <= 600")
    }

    @Test
    fun `viewport fix script patches expanded mobile approval card`() {
        val script = HermesWebUiScripts.viewportFixScript

        assertThat(script).contains("var approvalMax = Math.min(420, Math.round(height * 0.60)) + 'px';")
        assertThat(script).contains(".approval-card:not(.collapsed) .approval-inner { max-height: ' + approvalMax + ' !important;")
        assertThat(script).contains("viewportWidth > 0 && viewportWidth <= 640")
    }
}
