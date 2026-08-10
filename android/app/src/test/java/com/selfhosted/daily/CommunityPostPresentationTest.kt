package com.selfhosted.daily

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityPostPresentationTest {
    @Test
    fun participantsKeepOwnerFirstAndDeduplicateContributors() {
        val contributors = listOf(
            CommunityContributor(2, "Zwei", "#445566"),
            CommunityContributor(1, "Owner from server", "#112233", "https://example.test/owner.jpg", true),
            CommunityContributor(2, "Zwei doppelt", "#FFFFFF")
        )

        val resolved = resolvedCommunityContributors(
            contributors = contributors,
            ownerId = 1,
            ownerUsername = "Fallback owner",
            ownerColor = "#000000",
            ownerAvatarUrl = "",
            ownerAvatarVisible = false
        )

        assertEquals(listOf(1L, 2L), resolved.map { it.id })
        assertEquals("Owner from server", resolved.first().username)
        assertTrue(resolved.first().avatarVisible)
    }

    @Test
    fun missingPayloadFallsBackToOwnerAndRespectsAvatarVisibility() {
        val hidden = resolvedCommunityContributors(
            contributors = emptyList(),
            ownerId = 7,
            ownerUsername = "Ada",
            ownerColor = "#ABCDEF",
            ownerAvatarUrl = "https://example.test/ada.jpg",
            ownerAvatarVisible = false
        ).single()
        assertEquals(7L, hidden.id)
        assertEquals("Ada", hidden.username)
        assertFalse(hidden.avatarVisible)
        assertEquals("", hidden.avatarUrl)

        val visible = resolvedCommunityContributors(
            contributors = emptyList(),
            ownerId = 7,
            ownerUsername = "Ada",
            ownerColor = "#ABCDEF",
            ownerAvatarUrl = "https://example.test/ada.jpg",
            ownerAvatarVisible = true
        ).single()
        assertTrue(visible.avatarVisible)
        assertEquals("https://example.test/ada.jpg", visible.avatarUrl)
    }

    @Test
    fun chipLabelUsesSingularAndPlural() {
        assertEquals("Community · 1 Person", communityParticipantsChipLabel(1))
        assertEquals("Community · 2 Beteiligte", communityParticipantsChipLabel(2))
        assertEquals("Community · 12 Beteiligte", communityParticipantsChipLabel(12))
    }

    @Test
    fun gradientKeepsParticipantOrderAndUsesFallbackForInvalidColors() {
        val colors = communityGradientColors(
            listOf(
                CommunityContributor(1, "Eins", "#112233"),
                CommunityContributor(2, "Zwei", "not-a-color"),
                CommunityContributor(3, "Drei", "#AABBCC")
            )
        )

        assertEquals(
            listOf(Color(0xFF112233), Color(0xFF1F5FBF), Color(0xFFAABBCC)),
            colors
        )
        assertEquals(listOf(Color(0xFF1F5FBF)), communityGradientColors(emptyList()))
    }

    @Test
    fun avatarFallbackUsesFirstVisibleCharacter() {
        assertEquals("Y", communityContributorInitial("  yosho"))
        assertEquals("?", communityContributorInitial("   "))
    }
}
