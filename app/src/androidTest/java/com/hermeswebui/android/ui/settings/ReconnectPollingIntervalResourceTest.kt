package com.hermeswebui.android.ui.settings

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.R
import org.junit.Test

class ReconnectPollingIntervalResourceTest {
    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun pollingIntervalDescriptionUsesSingularOnlyForOneSecond() {
        assertThat(
            resources.getQuantityString(
                R.plurals.reconnect_settings_polling_interval,
                1,
                1
            )
        ).isEqualTo("1 second between reconnect checks")

        assertThat(
            resources.getQuantityString(
                R.plurals.reconnect_settings_polling_interval,
                2,
                2
            )
        ).isEqualTo("2 seconds between reconnect checks")
    }
}