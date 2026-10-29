package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.domain.SharedFileHandoff
import org.junit.Test

class SharedFileHandoffTest {
    private val files = listOf("content://provider/one", "content://provider/two")

    @Test
    fun `untrusted requester receives nothing and leaves files staged`() {
        val handoff = SharedFileHandoff().apply { stage(files) }

        assertThat(handoff.takeFor(requesterIsTrustedWebUi = false)).isEmpty()
        assertThat(handoff.hasStagedFiles).isTrue()
    }

    @Test
    fun `trusted WebUI requester receives staged files exactly once`() {
        val handoff = SharedFileHandoff().apply { stage(files) }
        handoff.takeFor(requesterIsTrustedWebUi = false)

        assertThat(handoff.takeFor(requesterIsTrustedWebUi = true)).containsExactlyElementsIn(files).inOrder()
        assertThat(handoff.hasStagedFiles).isFalse()
        assertThat(handoff.takeFor(requesterIsTrustedWebUi = true)).isEmpty()
    }

    @Test
    fun `new share replaces previously staged files`() {
        val handoff = SharedFileHandoff().apply { stage(files) }
        handoff.stage(listOf("content://provider/three"))

        assertThat(handoff.takeFor(requesterIsTrustedWebUi = true)).containsExactly("content://provider/three")
    }
}
