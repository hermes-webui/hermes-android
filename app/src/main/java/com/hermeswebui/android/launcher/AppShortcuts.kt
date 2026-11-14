package com.hermeswebui.android.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.R

/**
 * Native ways into Application Settings that work even when the server page never renders
 * (Issue #194): a launcher shortcut and the reconnect notification action both open the existing
 * `hermes://app/settings` recovery route.
 */
object AppShortcuts {
    const val SETTINGS_SHORTCUT_ID = "settings"
    val SETTINGS_URI: Uri = Uri.parse("hermes://app/settings")

    fun settingsIntent(context: Context): Intent =
        Intent(Intent.ACTION_VIEW, SETTINGS_URI).setClass(context, MainActivity::class.java)

    /**
     * Bind the shortcut to the enabled launcher alias. Android removes dynamic shortcuts bound to
     * a disabled alias, so [LauncherIconManager.select] republishes after every switch.
     */
    fun publish(context: Context, launcherIcon: LauncherIcon) {
        val shortcut = ShortcutInfoCompat.Builder(context, SETTINGS_SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.shortcut_settings_short))
            .setLongLabel(context.getString(R.string.shortcut_settings_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_settings))
            .setActivity(ComponentName(context.packageName, launcherIcon.aliasName))
            .setIntent(settingsIntent(context))
            .build()
        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, listOf(shortcut))
        } catch (exception: RuntimeException) {
            Log.w("HermesShortcuts", "Could not publish the Settings shortcut", exception)
        }
    }
}
