package com.hermeswebui.android.server

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HermesServerProfileCoordinatorTest {
    @Test
    fun `configured startup does not show auth-required toast`() {
        assertThat(shouldShowAuthRequiredToast(ServerValidationFlow.CONFIGURED_STARTUP)).isFalse()
    }

    @Test
    fun `persistence flow still shows auth-required toast`() {
        assertThat(shouldShowAuthRequiredToast(ServerValidationFlow.PERSISTENCE)).isTrue()
    }
}