package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.ServerType
import com.hermeswebui.android.data.SettingsRepository
import org.junit.Test

class ServerTypeTest {
    @Test
    fun legacyAndUnknownLabelsDoNotGuessTheFrontendOrDropTheProfile() {
        for (extra in listOf("", ""","serverType":"future-type"""")) {
            val profiles = SettingsRepository.parseProfiles(
                """[{"id":"one","name":"Server","url":"https://hermes.test","createdAt":1$extra}]""",
                "one"
            )
            assertThat(profiles).hasSize(1)
            assertThat(profiles.single().serverType).isEqualTo(ServerType.UNSPECIFIED)
            assertThat(profiles.single().isActive).isTrue()
        }
    }

    @Test
    fun typesArePerProfileAndIndependentOfCurrentSelection() {
        val profiles = SettingsRepository.parseProfiles(
            """[
                {"id":"a","name":"Agent","url":"https://agent.test","createdAt":1,"serverType":"agent"},
                {"id":"w","name":"WebUI","url":"https://webui.test","createdAt":2,"serverType":"webui"}
            ]""".trimIndent(),
            "w"
        )
        assertThat(profiles.map { it.serverType }).containsExactly(ServerType.AGENT, ServerType.WEBUI).inOrder()
        assertThat(profiles.first().isActive).isFalse()
        assertThat(profiles.last().isActive).isTrue()
    }
}
