package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.background.ApprovalActionSupport
import org.junit.Test

class ApprovalActionSupportTest {
    @Test
    fun `preferred allow choice prioritizes once over broader scopes`() {
        assertThat(
            ApprovalActionSupport.preferredAllowChoice(listOf("always", "once", "session"))
        ).isEqualTo("once")
    }

    @Test
    fun `deny choice accepts reject alias`() {
        assertThat(ApprovalActionSupport.denyChoice(listOf("reject"))).isEqualTo("reject")
    }

    @Test
    fun `labels are human readable`() {
        assertThat(ApprovalActionSupport.labelForChoice("session")).isEqualTo("Allow session")
        assertThat(ApprovalActionSupport.labelForChoice("deny")).isEqualTo("Deny")
    }
}