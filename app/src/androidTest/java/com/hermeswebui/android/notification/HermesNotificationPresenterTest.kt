package com.hermeswebui.android.notification

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HermesNotificationPresenterTest {
    private val trustedUrl = "https://hermes.example.com/session-123"
    private val presenter = HermesNotificationPresenter(
        context = InstrumentationRegistry.getInstrumentation().targetContext,
        channelId = "test",
        notificationIdBase = 1,
        isTrustedTarget = { url -> url == trustedUrl }
    )

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
