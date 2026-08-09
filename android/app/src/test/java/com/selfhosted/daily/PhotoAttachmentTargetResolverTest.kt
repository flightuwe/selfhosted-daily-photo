package com.selfhosted.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoAttachmentTargetResolverTest {
    @Test
    fun ownLatestRefreshMayReplaceTheOriginallyDisplayedOwnTarget() {
        val prompt = prompt(
            canAppendToOwnLatestPost = true,
            appendTargetPhotoId = 202L,
            activeCommunityPost = communityPhoto(101L)
        )

        assertEquals(
            PhotoAttachmentTargetResolution(photoId = 202L),
            resolvePhotoAttachmentTarget(201L, PhotoAttachmentTargetKind.OWN_LATEST, prompt)
        )
    }

    @Test
    fun communityTargetIsNeverReplacedByTheCurrentUsersOwnPost() {
        val prompt = prompt(
            canAppendToOwnLatestPost = true,
            appendTargetPhotoId = 202L,
            activeCommunityPost = communityPhoto(101L)
        )

        assertEquals(
            PhotoAttachmentTargetResolution(photoId = 101L),
            resolvePhotoAttachmentTarget(101L, PhotoAttachmentTargetKind.COMMUNITY, prompt)
        )
    }

    @Test
    fun staleCommunityTargetIsRejectedAfterPromptRefresh() {
        val prompt = prompt(
            canAppendToOwnLatestPost = true,
            appendTargetPhotoId = 202L,
            activeCommunityPost = communityPhoto(303L)
        )

        val result = resolvePhotoAttachmentTarget(101L, PhotoAttachmentTargetKind.COMMUNITY, prompt)

        assertNull(result.photoId)
        assertEquals(PhotoAttachmentTargetRejection.COMMUNITY_POST_INACTIVE, result.rejection)
    }

    @Test
    fun unavailableOwnTargetIsRejectedInsteadOfFallingBackToCommunity() {
        val prompt = prompt(
            canAppendToOwnLatestPost = false,
            appendTargetPhotoId = null,
            activeCommunityPost = communityPhoto(101L)
        )

        val result = resolvePhotoAttachmentTarget(202L, PhotoAttachmentTargetKind.OWN_LATEST, prompt)

        assertNull(result.photoId)
        assertEquals(PhotoAttachmentTargetRejection.OWN_LATEST_UNAVAILABLE, result.rejection)
    }

    @Test
    fun lastKnownTargetRemainsQueueableWhenPromptRefreshFails() {
        assertEquals(
            PhotoAttachmentTargetResolution(photoId = 101L),
            resolvePhotoAttachmentTarget(101L, PhotoAttachmentTargetKind.COMMUNITY, null)
        )
        assertEquals(
            PhotoAttachmentTargetResolution(photoId = 202L),
            resolvePhotoAttachmentTarget(202L, PhotoAttachmentTargetKind.OWN_LATEST, null)
        )
    }

    private fun prompt(
        canAppendToOwnLatestPost: Boolean,
        appendTargetPhotoId: Long?,
        activeCommunityPost: PromptPhoto?
    ) = PromptResponse(
        day = "2026-08-09",
        canUpload = false,
        canAppendToOwnLatestPost = canAppendToOwnLatestPost,
        appendTargetPhotoId = appendTargetPhotoId,
        activeCommunityPost = activeCommunityPost
    )

    private fun communityPhoto(id: Long) = PromptPhoto(
        id = id,
        day = "2026-08-09",
        promptOnly = false,
        caption = null,
        url = "https://daily.example.test/uploads/community.jpg",
        createdAt = "2026-08-09T12:00:00Z",
        communityPost = true,
        communityActive = true
    )
}
