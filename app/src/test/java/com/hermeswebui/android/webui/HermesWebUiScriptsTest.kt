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
        assertThat(script).contains("root.style.setProperty('--hermes-android-visual-viewport-height',")
        assertThat(script).contains("visualBottom: visualTop + visualHeight")
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
        // Measured geometry: visible panel bottom minus the greater of the
        // titlebar and visual-viewport top boundaries.
        assertThat(script).contains("document.querySelector('.app-titlebar')")
        assertThat(script).contains("promptCard.getBoundingClientRect().bottom + previousShift")
        assertThat(script).contains("viewport.visualBottom")
        assertThat(script).contains("anchorBottom - viewport.visualBottom")
        assertThat(script).contains("translateY(calc(-1 * var(")
        // The cap overrides the WebUI viewport-unit clamp on both the card and its
        // scrolling inner region, with internal scroll for oversized content
        assertThat(script).contains(".clarify-card:not(.collapsed) { max-height: ' + promptPanelMaxPx + ' !important; transform:")
        assertThat(script).contains(".clarify-card:not(.collapsed) .clarify-inner")
        assertThat(script).contains("overflow-y: auto !important; }")
        // A preferred WebUI floor must not exceed the actually visible space
        assertThat(script).contains("Math.max(titlebarBottom, viewport.visualTop)")
        assertThat(script).contains("Math.max(1, promptPanelMax)")
        assertThat(script).doesNotContain("Math.max(180, promptPanelMax)")
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
    fun `viewport fix script never lets composer ancestors clip the floating prompt card`() {
        val script = HermesWebUiScripts.viewportFixScript

        // The prompt cards float above the composer from inside the zero-height
        // .composer-flyout, so the flyout and composer-wrap must never become a
        // scroll/clip container — that re-clips the card to a sliver behind the
        // composer (#80 follow-up). Force overflow visible on both, re-applied
        // every scan so a stray inline repair cannot re-clip the card.
        assertThat(script).contains(".composer-flyout, .composer-wrap { overflow: visible !important; }")
        // Keep the composer-wrap out of the generic collapse repair so a transient
        // repair cannot turn it into a scroll container in the first place.
        assertThat(script).contains("el.classList.contains('composer-wrap')")
    }

    @Test
    fun `generic viewport repair preserves original overflow contract`() {
        val script = HermesWebUiScripts.viewportFixScript

        assertThat(script).contains("Preserve the element's existing overflow contract")
        assertThat(script).doesNotContain("data-hermes-android-vh-scrollable")
        assertThat(script).doesNotContain("el.style.overflowY = 'auto'")
        assertThat(script).doesNotContain("el.style.removeProperty('overflow-y')")
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

    @Test
    fun `enter key script defers to native handling when a hardware keyboard is attached`() {
        val script = HermesWebUiScripts.enterKeyNewlineScript

        // Hardware keyboard attached: return early so WebUI's native Enter-to-submit
        // and Shift+Enter-newline handling runs (desktop convention).
        assertThat(script).contains("window.__hermesAndroidHardwareKeyboard === true")
        // Soft keyboard: keep forcing a newline (Issue #34 behavior preserved).
        assertThat(script).contains("e.preventDefault();")
        assertThat(script).contains("e.stopImmediatePropagation();")
        assertThat(script).contains("Issue #83")
    }

    @Test
    fun `clarify autofocus script does not inspect unrelated dialogs`() {
        val script = HermesWebUiScripts.suppressClarifyAutofocusScript

        assertThat(script).contains("target.id === 'clarifyInput'")
        assertThat(script).contains("target.closest('.clarify-card')")
        assertThat(script).contains("target.closest('.clarify-choice.other')")
        assertThat(script).contains("event.key === 'Tab'")
        assertThat(script).contains("data-hermes-android-clarify-focus-handled")
        assertThat(script).contains("getClarifyPresentationKey")
        assertThat(script).contains("typeof _clarifyId !== 'undefined'")
        assertThat(script).contains("focusIntentPresentationKey === presentationKey")
        assertThat(script).contains("card.getAttribute(PRESENTATION_HANDLED_ATTR) === presentationKey")
        assertThat(script).contains("document.addEventListener('focusin', suppressAutomaticClarifyFocus, true)")
        assertThat(script).doesNotContain("role === 'dialog'")
        assertThat(script).doesNotContain("querySelectorAll('input")
    }
}
