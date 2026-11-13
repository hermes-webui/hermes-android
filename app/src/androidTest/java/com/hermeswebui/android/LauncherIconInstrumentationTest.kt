package com.hermeswebui.android

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.launcher.AppShortcuts
import com.hermeswebui.android.launcher.LauncherIcon
import com.hermeswebui.android.launcher.LauncherIconManager
import org.junit.Test
import org.junit.runner.RunWith

@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class LauncherIconInstrumentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun settingsShortcutFollowsTheEnabledLauncherAliasAndOpensTheRecoveryRoute() {
        val manager = LauncherIconManager(context)
        val original = manager.selectedIcon()
        try {
            for (choice in listOf(LauncherIcon.AGENT, LauncherIcon.WEBUI)) {
                manager.select(choice)
                AppShortcuts.publish(context, choice)
                val shortcut = ShortcutManagerCompat.getDynamicShortcuts(context)
                    .single { it.id == AppShortcuts.SETTINGS_SHORTCUT_ID }
                assertThat(shortcut.activity).isEqualTo(ComponentName(context.packageName, choice.aliasName))
                assertThat(shortcut.intent.action).isEqualTo(Intent.ACTION_VIEW)
                assertThat(shortcut.intent.data).isEqualTo(Uri.parse("hermes://app/settings"))
                assertThat(context.packageManager.resolveActivity(shortcut.intent, 0)?.activityInfo?.name)
                    .isEqualTo(MainActivity::class.java.name)
            }
        } finally {
            manager.select(original)
            AppShortcuts.publish(context, original)
        }
    }

    @Test
    fun switchesExactlyOneRealLauncherAliasAndPreservesMainActivityAndRecoveryRoutes() {
        val manager = LauncherIconManager(context)
        val original = manager.selectedIcon()
        try {
            for (choice in listOf(LauncherIcon.AGENT, LauncherIcon.WEBUI, LauncherIcon.AGENT)) {
                manager.select(choice)
                assertThat(LauncherIconManager(context).selectedIcon()).isEqualTo(choice)
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(context.packageName)
                val launchers = context.packageManager.queryIntentActivities(intent, 0)
                assertThat(launchers).hasSize(1)
                assertThat(launchers.single().activityInfo.name).isEqualTo(choice.aliasName)
                assertThat(launchers.single().activityInfo.targetActivity).isEqualTo(MainActivity::class.java.name)
                assertThat(launchers.single().activityInfo.icon).isEqualTo(
                    if (choice == LauncherIcon.AGENT) R.mipmap.ic_launcher_agent else R.mipmap.ic_launcher
                )
                val main = ComponentName(context, MainActivity::class.java)
                assertThat(context.packageManager.getActivityInfo(main, 0).enabled).isTrue()
                val recovery = Intent(Intent.ACTION_VIEW, Uri.parse("hermes://app/settings"))
                    .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
                assertThat(context.packageManager.resolveActivity(recovery, 0)?.activityInfo?.name)
                    .isEqualTo(MainActivity::class.java.name)
                val share = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
                assertThat(context.packageManager.resolveActivity(share, 0)?.activityInfo?.name)
                    .isEqualTo(MainActivity::class.java.name)

                ActivityScenario.launch<MainActivity>(
                    intent.setComponent(ComponentName(context.packageName, choice.aliasName))
                ).use { scenario ->
                    scenario.onActivity { assertThat(it.isFinishing).isFalse() }
                }
            }
        } finally {
            manager.select(original)
        }
    }
}
