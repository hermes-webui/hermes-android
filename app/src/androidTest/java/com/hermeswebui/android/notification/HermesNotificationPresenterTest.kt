package com.hermeswebui.android.notification

import android.content.Intent
import android.content.Context
import android.app.Notification
import android.app.NotificationManager
import android.app.NotificationChannel
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
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class HermesNotificationPresenterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val trustedUrl = "https://hermes.example.com/session-123"
    private val presenter = HermesNotificationPresenter(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        channelId = "test",
        isTrustedTarget = { url -> url == trustedUrl }
    )

    @Before
    fun grantNotifications() {
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}"
        ).close()
        presenter.cancelOngoingNotification()
        HermesNotificationSlot.dismiss(context, HermesNotificationSlot.currentMessageToken())
        manager.createNotificationChannel(NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_LOW))
        awaitOngoingNotification(false)
        SystemClock.sleep(1_100)
    }

    @After
    fun clearNotifications() {
        presenter.cancelOngoingNotification()
        HermesNotificationSlot.dismiss(context, HermesNotificationSlot.currentMessageToken())
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

    @Test
    fun newestMessageReplacesShortcutAndOtherTagsAndOldDismissCannotEraseIt() {
        presenter.syncOngoingNotification(true)
        presenter.showNotification(
            JSONObject().put("title", "First").put("options", JSONObject().put("tag", "first").put("body", "First message")),
            trustedUrl
        )
        val oldToken = HermesNotificationSlot.currentMessageToken()
        val oldTap = manager.activeNotifications.single { it.id == HermesNotificationSlot.ID }.notification.contentIntent
        SystemClock.sleep(1_100)
        presenter.showNotification(
            JSONObject().put("title", "Second").put("options", JSONObject().put("tag", "second").put("body", "Latest message")),
            trustedUrl
        )
        HermesNotificationSlot.dismiss(context, oldToken)
        awaitText("Latest message")
        val entries = manager.activeNotifications.filter { it.id == HermesNotificationSlot.ID }
        assertThat(entries).hasSize(1)
        assertThat(entries.single().tag).isNull()
        assertThat(entries.single().notification.contentIntent).isNotEqualTo(oldTap)
        assertThat(entries.single().notification.actions.map { it.title.toString() }).containsExactly("Settings")
        presenter.syncOngoingNotification(false)
        awaitText("Latest message")
        val latest = manager.activeNotifications.single { it.id == HermesNotificationSlot.ID }.notification
        latest.deleteIntent.send()
        awaitOngoingNotification(false)
        presenter.syncOngoingNotification(true)
        awaitText("Expand for Settings, or tap to return to Hermes.")
    }

    @Test
    fun processRestartPreservesUnreadTextButRemovesDeadReplyActions() {
        val configured = SettingsRepository(context).getSettings("", "").serverUrl
        val url = "https://hermes.example.com/session/chat"
        val chat = HermesNotificationPresenter(context, "test") { it == url }
        chat.showNotification(
            JSONObject().put("title", "Unread").put("replyProfile", "work").put("replySessionId", "chat")
                .put("options", JSONObject().put("body", "Retained answer")),
            url, configured
        )
        awaitText("Retained answer")
        assertThat(manager.activeNotifications.single { it.id == HermesNotificationSlot.ID }
            .notification.actions.any { !it.remoteInputs.isNullOrEmpty() }).isTrue()
        listOf("message", "messageToken", "open", "service", "selectedServer", "lastPosted").forEach { name ->
            HermesNotificationSlot::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(HermesNotificationSlot, null)
        }
        HermesNotificationSlot::class.java.getDeclaredField("initialized").apply { isAccessible = true }
            .setBoolean(HermesNotificationSlot, false)
        NotificationReplyActions.clear()
        HermesNotificationSlot.selectServer(context, configured)
        presenter.syncOngoingNotification(false)
        awaitText("Retained answer")
        assertThat(manager.activeNotifications.single { it.id == HermesNotificationSlot.ID }
            .notification.actions.all { it.remoteInputs.isNullOrEmpty() }).isTrue()
    }

    @Test
    fun replyUsesCompactSettingsAndTapCarriesTheTrustedConversation() {
        val sessionUrl = "https://hermes.example.com/session/chat"
        val chatPresenter = HermesNotificationPresenter(context, "test") { it == sessionUrl }
        chatPresenter.showNotification(
            JSONObject().put("title", "Agent").put("replyProfile", "work").put("replySessionId", "chat")
                .put("options", JSONObject().put("body", "Latest answer").put("data", JSONObject().put("url", sessionUrl))),
            sessionUrl, "https://hermes.example.com/"
        )
        awaitText("Latest answer")
        val notification = manager.activeNotifications.single { it.id == HermesNotificationSlot.ID }.notification
        assertThat(notification.category).isEqualTo(Notification.CATEGORY_MESSAGE)
        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Reply", "⚙").inOrder()
        assertThat(notification.actions.last().getIcon()).isNotNull()
        assertThat(notification.visibility).isEqualTo(Notification.VISIBILITY_PRIVATE)
        var launched: MainActivity? = null
        try {
            notification.contentIntent.send()
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (launched == null && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    launched = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull()
                }
                SystemClock.sleep(25)
            }
            assertThat(launched).isNotNull()
            assertThat(launched!!.intent.action).isEqualTo(HermesNotificationPresenter.ACTION_OPEN_NOTIFICATION_URL)
            assertThat(chatPresenter.notificationTargetUrl(launched!!.intent)).isEqualTo(sessionUrl)
        } finally {
            instrumentation.runOnMainSync { launched?.finish() }
        }
        awaitOngoingNotification(false)
    }

    private fun awaitText(text: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (manager.activeNotifications.any {
                it.id == HermesNotificationSlot.ID &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == text
            }) return
            SystemClock.sleep(25)
        }
        error("Notification did not show $text")
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
    fun activityRemovesIdleSettingsNotificationInBackgroundAndRestoresItOnReturn() {
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
                awaitOngoingNotification(false)
                scenario.moveToState(Lifecycle.State.RESUMED)
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
