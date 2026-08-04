package com.selfhosted.daily

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UploadQueueLifecycleTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun lostResponseAndProcessDeathKeepsSameIdempotencyKeyUntilConfirmed() {
        val back = queueFile("lost-back.jpg")
        val front = queueFile("lost-front.jpg")
        val capturedAt = 1_785_700_000_000L
        val item = UploadQueueManager.enqueueFromFiles(
            context = context,
            backPath = back.absolutePath,
            frontPath = front.absolutePath,
            uploadClientId = "client-lost-response",
            isPrompt = false,
            capturedAtMs = capturedAt
        )

        val claimed = UploadQueueManager.claimNextRunnable(context, 10_000L)
        assertNotNull(claimed)
        assertNull("a durable lease must prevent a second worker claim", UploadQueueManager.claimNextRunnable(context, 10_001L))
        assertEquals(600L, UploadQueueManager.nextLeaseRecoveryDelaySeconds(context, 10_000L))
        UploadQueueManager.markAwaitingServerAck(context, item.id)

        val afterLease = System.currentTimeMillis() + 11L * 60L * 1000L
        UploadQueueManager.recoverStaleEntries(context, afterLease)
        val recovered = UploadQueueManager.findById(context, item.id)
        assertEquals(UploadQueueStatus.FAILED_TRANSIENT, recovered?.status)
        assertEquals("client-lost-response", recovered?.uploadClientId)
        assertEquals(capturedAt, recovered?.capturedAtMs)
        assertTrue(back.exists())
        assertTrue(front.exists())

        val retried = UploadQueueManager.claimNextRunnable(context, afterLease + 16_000L)
        assertEquals("client-lost-response", retried?.uploadClientId)
        UploadQueueManager.markSuccess(context, item.id)
        assertEquals(UploadQueueStatus.SUCCESS, UploadQueueManager.findById(context, item.id)?.status)
        assertFalse("files are deleted only after confirmed success", back.exists())
        assertFalse(front.exists())
    }

    @Test
    fun blockedExtraRemainsDecidableWithAllThreeUserActions() {
        val deferItem = enqueueExtra("defer")
        UploadQueueManager.markActionRequired(context, deferItem.id, "blocked", "extra_window_blocked", 403)
        assertTrue(UploadQueueManager.deferExtraUntil(context, deferItem.id, System.currentTimeMillis() + 60_000L))
        assertEquals(UploadQueueStatus.WAITING, UploadQueueManager.findById(context, deferItem.id)?.status)

        val attachItem = enqueueExtra("attach", capturedAtMs = 1_785_700_123_000L)
        UploadQueueManager.markActionRequired(context, attachItem.id, "blocked", "extra_window_blocked", 403)
        assertTrue(UploadQueueManager.convertExtraToAttachments(context, attachItem.id, 77L))
        val attachments = UploadQueueManager.list(context).filter { it.appendTargetPhotoId == 77L }
        assertEquals(2, attachments.size)
        assertTrue(attachments.all { it.uploadMode == UploadQueueMode.ATTACHMENT })
        assertTrue(attachments.all { it.capturedAtMs == 1_785_700_123_000L })

        val deleteItem = enqueueExtra("delete")
        val paths = listOf(File(deleteItem.backPath), File(deleteItem.frontPath))
        UploadQueueManager.markActionRequired(context, deleteItem.id, "blocked", "extra_window_blocked", 403)
        assertTrue(UploadQueueManager.remove(context, deleteItem.id))
        assertNull(UploadQueueManager.findById(context, deleteItem.id))
        assertTrue(paths.none(File::exists))
    }

    @Test
    fun offlineExtraDraftReleasesAttachmentsOnlyAfterParentAndInCaptureOrder() {
        val parent = enqueueExtra("draft-parent")
        val first = UploadQueueManager.enqueueDraftAttachmentFromFile(
            context, queueFile("draft-first.jpg").absolutePath, "draft-first", parent, capturedAtMs = 101L
        )
        val second = UploadQueueManager.enqueueDraftAttachmentFromFile(
            context, queueFile("draft-second.jpg").absolutePath, "draft-second", parent, capturedAtMs = 102L
        )

        assertEquals(parent.id, UploadQueueManager.latestOpenExtraDraft(context)?.id)
        val claimedParent = UploadQueueManager.claimNextRunnable(context, 1L)
        assertEquals(parent.id, claimedParent?.id)
        assertNull("children must not upload before the parent id is durable", UploadQueueManager.claimNextRunnable(context, 2L))

        UploadQueueManager.resolveDraftParent(context, parent.localExtraDraftId, parent.uploadClientId, 77L)
        UploadQueueManager.markSuccess(context, parent.id)
        val claimedFirst = UploadQueueManager.claimNextRunnable(context, 3L)
        assertEquals(first.id, claimedFirst?.id)
        assertEquals(77L, claimedFirst?.resolvedParentPhotoId)
        UploadQueueManager.markFailedTransient(context, first.id, "offline", "connect", null, networkWaiting = true, overrideDelayMs = 60_000L)
        assertNull("a retrying first image must keep the second image behind it", UploadQueueManager.claimNextRunnable(context, 4L))

        UploadQueueManager.markSuccess(context, first.id)
        val claimedSecond = UploadQueueManager.claimNextRunnable(context, 5L)
        assertEquals(second.id, claimedSecond?.id)
        UploadQueueManager.markSuccess(context, second.id)
        assertNull("the draft closes when all images are confirmed", UploadQueueManager.latestOpenExtraDraft(context))
    }

    @Test
    fun removingAnyOfflineDraftEntryDeletesTheWholeDraft() {
        val parent = enqueueExtra("delete-draft")
        val attachment = UploadQueueManager.enqueueDraftAttachmentFromFile(
            context, queueFile("delete-draft-attachment.jpg").absolutePath, "delete-draft-attachment", parent
        )
        val parentFiles = listOf(File(parent.backPath), File(parent.frontPath))
        val attachmentFile = File(attachment.backPath)

        assertTrue(UploadQueueManager.remove(context, attachment.id))
        assertNull(UploadQueueManager.findById(context, parent.id))
        assertNull(UploadQueueManager.findById(context, attachment.id))
        assertTrue((parentFiles + attachmentFile).none(File::exists))
    }

    private fun enqueueExtra(name: String, capturedAtMs: Long = 1_785_700_000_000L): QueuedUploadItem =
        UploadQueueManager.enqueueFromFiles(
            context = context,
            backPath = queueFile("$name-back.jpg").absolutePath,
            frontPath = queueFile("$name-front.jpg").absolutePath,
            uploadClientId = "client-$name",
            isPrompt = false,
            capturedAtMs = capturedAtMs
        )

    private fun queueFile(name: String): File = File(context.cacheDir, name).apply {
        parentFile?.mkdirs()
        writeText("test-image")
    }
}
