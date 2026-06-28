package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.update.AppVersionComparator
import org.junit.Test

class AppVersionComparatorTest {
    @Test
    fun `github suffix is ignored when comparing versions`() {
        assertThat(AppVersionComparator.isNewer("v0.1.21", "0.1.20-github")).isTrue()
    }

    @Test
    fun `same base version is not newer across channels`() {
        assertThat(AppVersionComparator.isNewer("v0.1.20", "0.1.20-github")).isFalse()
    }

    @Test
    fun `older version is not newer`() {
        assertThat(AppVersionComparator.isNewer("v0.1.19", "0.1.20")).isFalse()
    }
}
