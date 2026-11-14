package com.hermeswebui.android.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

enum class LauncherIcon(val aliasName: String) {
    WEBUI("com.hermeswebui.android.WebUiLauncher"),
    AGENT("com.hermeswebui.android.AgentLauncher")
}

class LauncherIconManager(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val packageManager = context.packageManager
    private val packageName = context.packageName

    fun selectedIcon(): LauncherIcon =
        if (isEnabled(LauncherIcon.AGENT)) LauncherIcon.AGENT else LauncherIcon.WEBUI

    fun select(icon: LauncherIcon) {
        val ordered = listOf(icon) + LauncherIcon.entries.filter { it != icon }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(ordered.map { choice ->
                PackageManager.ComponentEnabledSetting(
                    component(choice),
                    enabledState(choice == icon),
                    PackageManager.DONT_KILL_APP
                )
            })
        } else {
            // Enable the replacement first so older Android never has zero launcher entries.
            ordered.forEach { choice ->
                packageManager.setComponentEnabledSetting(
                    component(choice), enabledState(choice == icon), PackageManager.DONT_KILL_APP
                )
            }
        }
        check(selectedIcon() == icon && LauncherIcon.entries.count(::isEnabled) == 1) {
            "Android did not apply the launcher icon selection"
        }
        // Android removes dynamic shortcuts bound to the alias that was just disabled.
        AppShortcuts.publish(appContext, icon)
    }

    private fun component(icon: LauncherIcon) = ComponentName(packageName, icon.aliasName)

    private fun enabledState(enabled: Boolean): Int =
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    private fun isEnabled(icon: LauncherIcon): Boolean =
        when (packageManager.getComponentEnabledSetting(component(icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == LauncherIcon.WEBUI
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> false
        }
}
