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
    fun `viewport fix script injects CSS custom properties for viewport dimensions`() {
        val script = HermesWebUiScripts.viewportFixScript

        assertThat(script).contains("root.style.setProperty('--vh',")
        assertThat(script).contains("root.style.setProperty('--dvh',")
        assertThat(script).contains("root.style.setProperty('--viewport-height',")
        assertThat(script).contains("root.style.setProperty('--viewport-width',")
    }

    @Test
    fun `viewport fix script uses generic collapse detection instead of explicit selectors`() {
        val script = HermesWebUiScripts.viewportFixScript

        // Generic detection heuristics
        assertThat(script).contains("isCollapsedElement")
        assertThat(script).contains("scrollHeight")
        assertThat(script).contains("rect.height")
        assertThat(script).contains("hasOverflowMismatch")
        
        // Performance guards
        assertThat(script).contains("MAX_REPAIRS_PER_SCAN")
        assertThat(script).contains("MIN_SCAN_INTERVAL_MS")
        
        // Repair tracking attribute
        assertThat(script).contains("data-hermes-android-vh-repaired")
    }

    @Test
    fun `viewport fix script skips chat descendants but keeps chat container repairable`() {
        val script = HermesWebUiScripts.viewportFixScript

        assertThat(script).contains("shouldSkipRepairForElement")
        assertThat(script).contains("el.closest('.messages, #messages, [data-testid=\"messages\"]')")
        assertThat(script).contains("if (el === chatSurface) return false;")
        assertThat(script).contains("avoid chat-window flicker")
    }

    @Test
    fun `viewport fix script keeps visible repaired panels from oscillating`() {
        val script = HermesWebUiScripts.viewportFixScript

        assertThat(script).contains("function updateRepair(el, viewport)")
        assertThat(script).contains("function clearRepairIfHidden(el)")
        assertThat(script).contains("make the panel oscillate")
        assertThat(script).doesNotContain("clearRepairIfHealthy")
    }

    @Test
    fun `viewport fix script caps expanded clarify panel to measured titlebar-composer space`() {
        val script = HermesWebUiScripts.viewportFixScript

        // Expanded clarify surface selector (#80) alongside the approval surface (#44)
        assertThat(script).contains(".approval-card.visible:not(.collapsed), .clarify-card.visible:not(.collapsed)")
        // Measured geometry: panel bottom (composer anchor) minus titlebar bottom
        assertThat(script).contains("document.querySelector('.app-titlebar')")
        assertThat(script).contains("promptCard.getBoundingClientRect().bottom - titlebarBottom - 8")
        // The cap overrides the WebUI viewport-unit clamp on both the card and its
        // scrolling inner region, with internal scroll for oversized content
        assertThat(script).contains(".clarify-card:not(.collapsed) { max-height: ' + promptPanelMaxPx + ' !important; }")
        assertThat(script).contains(".clarify-card:not(.collapsed) .clarify-inner")
        assertThat(script).contains("overflow-y: auto !important; }")
        // Usable floor matching the WebUI clamp minimum
        assertThat(script).contains("Math.max(180, promptPanelMax)")
    }

    @Test
    fun `viewport fix script leaves prompt surface to measured geometry instead of generic repair`() {
        val script = HermesWebUiScripts.viewportFixScript

        // The approval/clarify cards and their zero-height flyout anchor are excluded
        // from the generic viewport-derived repair so the two contracts cannot fight
        // and oscillate (#80)
        assertThat(script).contains("el.closest('.approval-card, .clarify-card')")
        assertThat(script).contains("el.classList.contains('composer-flyout')")
    }

    @Test
    fun `viewport fix script includes baseline CSS for layout containers`() {
        val script = HermesWebUiScripts.viewportFixScript

        // Root sizing
        assertThat(script).contains("html, body { min-height:")
        assertThat(script).contains("body { overflow-x: hidden")
        
        // Flex container helpers
        assertThat(script).contains(".layout, .rail, .sidebar, #sessionList, .messages { min-height: 0")
        
        // Settings page fix
        assertThat(script).contains(".main.showing-settings .main-view { max-height: none")
    }
}
