package com.hermeswebui.android.notification

import android.content.Intent
import android.content.Context
import android.app.Notification
import android.app.NotificationManager
import android.Manifest
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.MainActivity
import com.hermeswebui.android.background.HermesDebugLoggingService
import com.hermeswebui.android.background.HermesReconnectService
import com.hermeswebui.android.data.SettingsRepository
import com.hermeswebui.android.ui.MainViewModel
import com.hermeswebui.android.launcher.AppShortcuts
import org.junit.Test
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HermesNotificationPresenterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val trustedUrl = "https://hermes.example.com/session-123"
    private val presenter = HermesNotificationPresenter(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        channelId = "test",
        notificationIdBase = 1,
        isTrustedTarget = { url -> url == trustedUrl }
    )

    @Before
    fun grantNotifications() {
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}"
        ).close()
        presenter.cancelOngoingNotification()
        awaitOngoingNotification(false)
    }

    @After
    fun clearNotifications() {
        presenter.cancelOngoingNotification()
    }

    @Test
    fun ongoingNotificationIsQuietRedactedAndHasSettingsWithoutASession() {
        presenter.syncOngoingNotification(true)
        awaitOngoingNotification(true)
        val notification = manager.activeNotifications.single {
            it.id == HermesNotificationPresenter.ONGOING_NOTIFICATION_ID
        }.notification
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            .isEqualTo("Expand for Settings, or tap to return to Hermes.")
        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Settings")
        assertThat(notification.contentIntent).isNotNull()
        assertThat(manager.getNotificationChannel(HermesNotificationPresenter.ONGOING_CHANNEL_ID).importance)
            .isEqualTo(NotificationManager.IMPORTANCE_MIN)
        assertThat(manager.getNotificationChannel(HermesNotificationPresenter.ONGOING_CHANNEL_ID).canShowBadge())
            .isFalse()
        presenter.syncOngoingNotification(false)
        awaitOngoingNotification(false)
    }

    private fun awaitOngoingNotification(expected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (manager.activeNotifications.any {
                it.id == HermesNotificationPresenter.ONGOING_NOTIFICATION_ID
            } == expected) return
            SystemClock.sleep(25)
        }
        error("Ongoing notification presence did not become $expected")
    }

    @Test
    fun activityKeepsSettingsNotificationInBackgroundAndRemovesItOnClose() {
        val preferences = context.getSharedPreferences("hermes_secure_prefs", Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        val loggingWasEnabled = SettingsRepository(context).isDebugLoggingEnabled()
        try {
            check(preferences.edit().clear().commit())
            SettingsRepository(context).apply {
                saveAppUrls("http://127.0.0.1:9", "")
                setBackgroundReconnectEnabled(true)
                setSseTransportEnabled(false)
                setDebugLoggingEnabled(false)
            }
            ActivityScenario.launch<MainActivity>(AppShortcuts.settingsIntent(context)).use { scenario ->
                lateinit var launchedActivity: MainActivity
                scenario.onActivity {
                    launchedActivity = it
                    val viewModel = MainActivity::class.java.getDeclaredField("viewModel").apply {
                        isAccessible = true
                    }.get(it) as MainViewModel
                    viewModel.closeSettings()
                }
                awaitOngoingNotification(true)
                scenario.moveToState(Lifecycle.State.CREATED)
                awaitOngoingNotification(true)
                val notification = manager.activeNotifications.single {
                    it.id == HermesNotificationPresenter.ONGOING_NOTIFICATION_ID
                }.notification
                notification.actions.single { it.title.toString() == "Settings" }.actionIntent.send()
                instrumentation.waitForIdleSync()
                val deadline = SystemClock.uptimeMillis() + 5_000
                var resumed = false
                while (!resumed && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        resumed = ActivityLifecycleMonitorRegistry.getInstance()
                            .getLifecycleStageOf(launchedActivity) == Stage.RESUMED
                    }
                    SystemClock.sleep(25)
                }
                assertThat(resumed).isTrue()
                scenario.onActivity { activity ->
                    val viewModel = MainActivity::class.java.getDeclaredField("viewModel").apply {
                        isAccessible = true
                    }.get(activity) as MainViewModel
                    assertThat(viewModel.uiState.value.isSettingsVisible).isTrue()
                }
            }
            awaitOngoingNotification(false)
        } finally {
            HermesReconnectService.stop(context)
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

    @Test
    fun trustedNotificationTargetIsReturned() {
        assertThat(
            presenter.notificationTargetUrl(
                Intent(HermesNotificationPresenter.ACTION_OPEN_NOTIFICATION_URL).apply {
                    putExtra(HermesNotificationPresenter.EXTRA_NOTIFICATION_URL, trustedUrl)
                }
            )
        ).isEqualTo(trustedUrl)
    }

    @Test
    fun untrustedNotificationTargetIsRejected() {
        assertThat(
            presenter.notificationTargetUrl(
                Intent(HermesNotificationPresenter.ACTION_OPEN_NOTIFICATION_URL).apply {
                    putExtra(
                        HermesNotificationPresenter.EXTRA_NOTIFICATION_URL,
                        "https://attacker.example.com/session-123"
                    )
                }
            )
        ).isNull()
    }

    @Test
    fun unrelatedIntentActionIsIgnored() {
        assertThat(
            presenter.notificationTargetUrl(
                Intent(Intent.ACTION_VIEW).apply {
                    putExtra(HermesNotificationPresenter.EXTRA_NOTIFICATION_URL, trustedUrl)
                }
            )
        ).isNull()
    }

    @Test
    fun handleIntentInvokesCallbackOnlyForTrustedTarget() {
        var openedUrl: String? = null
        val handled = presenter.handleIntent(
            Intent(HermesNotificationPresenter.ACTION_OPEN_NOTIFICATION_URL).apply {
                putExtra(HermesNotificationPresenter.EXTRA_NOTIFICATION_URL, trustedUrl)
            }
        ) { url -> openedUrl = url }

        assertThat(handled).isTrue()
        assertThat(openedUrl).isEqualTo(trustedUrl)
    }
}
