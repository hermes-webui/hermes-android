package com.hermeswebui.android.core.network

import android.content.res.Configuration

/**
 * Hardware-keyboard detection for the Enter-key shim flag (#83). Takes raw
 * [Configuration] values so the rule is JVM-testable.
 */
object HardwareKeyboardState {
    fun isAttached(keyboard: Int, hardKeyboardHidden: Int): Boolean =
        keyboard != Configuration.KEYBOARD_NOKEYS &&
            hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
}
