package com.hermeswebui.android

import com.google.common.truth.Truth.assertThat
import com.hermeswebui.android.data.ServerProfile
import com.hermeswebui.android.server.ServerProfileConflict
import com.hermeswebui.android.server.ServerProfileRules
import org.junit.Test

class ServerProfileRulesTest {
    private val firstProfile = ServerProfile(
        id = "first",
        name = "Primary",
        url = "https://hermes.example.com/"
    )
    private val secondProfile = ServerProfile(
        id = "second",
        name = "Backup",
        url = "https://backup.example.com"
    )
    private val profiles = listOf(firstProfile, secondProfile)

    @Test
    fun duplicateUrlIgnoresCaseAndTrailingSlash() {
        assertThat(
            ServerProfileRules.findConflict(
                existingProfiles = profiles,
                candidateName = "Different",
                candidateUrl = " HTTPS://HERMES.EXAMPLE.COM "
            )
        ).isEqualTo(ServerProfileConflict.URL)
    }

    @Test
    fun duplicateNameIgnoresCaseAndWhitespace() {
        assertThat(
            ServerProfileRules.findConflict(
                existingProfiles = profiles,
                candidateName = " primary ",
                candidateUrl = "https://different.example.com"
            )
        ).isEqualTo(ServerProfileConflict.NAME)
    }

    @Test
    fun editingProfileExcludesItsCurrentValues() {
        assertThat(
            ServerProfileRules.findConflict(
                existingProfiles = profiles,
                candidateName = "Primary",
                candidateUrl = "https://hermes.example.com/",
                excludedProfileId = firstProfile.id
            )
        ).isNull()
    }

    @Test
    fun editingProfileStillRejectsAnotherProfileValues() {
        assertThat(
            ServerProfileRules.findConflict(
                existingProfiles = profiles,
                candidateName = "Backup",
                candidateUrl = "https://hermes-new.example.com",
                excludedProfileId = firstProfile.id
            )
        ).isEqualTo(ServerProfileConflict.NAME)
    }
}
