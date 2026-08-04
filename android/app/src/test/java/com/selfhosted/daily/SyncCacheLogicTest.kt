package com.selfhosted.daily

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SyncCacheLogicTest {
    @Test
    fun mediaSelectionUsesOriginalInNormalMode() {
        val media = PostMediaItem(
            url = "original.jpg",
            thumbnailUrl = "thumb.jpg",
            renditions = listOf(MediaRendition("feed", "avif", 720, "feed.avif"), MediaRendition("feed", "webp", 720, "feed.webp"))
        )

        assertEquals(listOf("original.jpg"), selectFeedMediaCandidates(media, "normal", metered = true, sdkInt = 34, avifDisabled = false))
    }

    @Test
    fun mediaSelectionUsesCompatibleFallbackMatrix() {
        val media = PostMediaItem(
            url = "original.jpg",
            thumbnailUrl = "thumb.jpg",
            renditions = listOf(
                MediaRendition("feed", "jpeg", 720, "feed.jpg"),
                MediaRendition("feed", "webp", 720, "feed.webp"),
                MediaRendition("feed", "avif", 720, "feed.avif")
            )
        )

        assertEquals(listOf("feed.webp", "feed.jpg", "thumb.jpg", "original.jpg"), selectFeedMediaCandidates(media, "data_saver", true, 26, false))
        assertEquals(listOf("feed.avif", "feed.webp", "feed.jpg", "thumb.jpg", "original.jpg"), selectFeedMediaCandidates(media, "automatic", true, 31, false))
        assertEquals(listOf("feed.webp", "feed.jpg", "thumb.jpg", "original.jpg"), selectFeedMediaCandidates(media, "automatic", true, 34, true))
        assertEquals(listOf("original.jpg"), selectFeedMediaCandidates(media, "automatic", false, 34, false))
    }

    @Test
    fun todayFeedIsRetainedWhenServerAlreadyHasItemsButPromptIsStale() {
        assertTrue(shouldRetainTodayFeed(promptReportsVisiblePost = false, serverItemCount = 1))
        assertTrue(shouldRetainTodayFeed(promptReportsVisiblePost = true, serverItemCount = 0))
        assertFalse(shouldRetainTodayFeed(promptReportsVisiblePost = false, serverItemCount = 0))
    }
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "warm-cache").deleteRecursively()
    }

    @Test
    fun timelineDeltaReplacesChangedPrefixDeduplicatesAndKeepsCompleteSuffix() {
        val existing = (10 downTo 1).map { timeline("id-$it", "old-$it") }
        val incoming = listOf(
            timeline("id-12", "new-12"),
            timeline("id-11", "new-11"),
            timeline("id-10", "updated-10"),
            timeline("id-9", "updated-9"),
            timeline("id-7", "updated-7")
        )

        val merged = mergeCompleteTimelineDelta(existing, incoming)

        assertEquals((listOf(12, 11, 10, 9, 7) + (6 downTo 1)).map { "id-$it" }, merged.map { it.id })
        assertEquals("updated-10", merged.first { it.id == "id-10" }.title)
        assertFalse("a deleted item inside the covered prefix must not survive", merged.any { it.id == "id-8" })
    }

    @Test
    fun timelineDeltaDropsItemsThatLeftTheSevenDayWindow() {
        val items = listOf(
            HubTimelineItem(id = "fresh", occurredAt = "2026-08-03T08:00:00+02:00"),
            HubTimelineItem(id = "edge", day = "2026-07-28"),
            HubTimelineItem(id = "expired", occurredAt = "2026-07-27T23:59:59+02:00")
        )

        val trimmed = trimTimelineWindow(items, "2026-08-03T12:00:00+02:00", 7)

        assertEquals(listOf("fresh", "edge"), trimmed.map { it.id })
    }

    @Test
    fun bootstrapPreviewNeverShortensACompleteOrPopulatedTimeline() {
        assertFalse(shouldApplyTimelinePreview(timelineComplete = true, timelineItemsEmpty = false))
        assertFalse(shouldApplyTimelinePreview(timelineComplete = false, timelineItemsEmpty = false))
        assertTrue(shouldApplyTimelinePreview(timelineComplete = false, timelineItemsEmpty = true))
    }

    @Test
    fun mobileRefreshPolicyIsLongerThanWifiPolicy() {
        val wifi = feedAutoRefreshBoundsMs(metered = false)
        val mobile = feedAutoRefreshBoundsMs(metered = true)
        assertEquals(90_000L, wifi.first)
        assertEquals(120_000L, wifi.last)
        assertEquals(300_000L, mobile.first)
        assertEquals(360_000L, mobile.last)
        assertTrue(mobile.first > wifi.last)
        assertTrue(shouldPreloadThumbnails(metered = false))
        assertFalse(shouldPreloadThumbnails(metered = true))
    }

    @Test
    fun v1CacheMigratesAndInvalidCacheIsDiscardedWithoutTouchingQueue() {
        val userId = 42L
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("warm_cache_last_user_id_v1", userId)
            .putString("upload_queue_items", "queue-must-survive")
            .commit()
        val cacheFile = File(context.filesDir, "warm-cache/user_${userId}_hub.json").apply { parentFile?.mkdirs() }
        cacheFile.writeText(Gson().toJson(AppWarmCacheEnvelope(schemaVersion = "app_warm_cache_v1", userId = userId)))
        val repo = AppRepo(context, OkHttpClient())

        assertEquals("app_warm_cache_v3", repo.loadLastWarmCache()?.schemaVersion)

        cacheFile.writeText(Gson().toJson(AppWarmCacheEnvelope(schemaVersion = "broken", userId = userId)))
        assertNull(repo.loadLastWarmCache())
        assertFalse(cacheFile.exists())
        assertEquals("queue-must-survive", prefs.getString("upload_queue_items", ""))
    }

    @Test
    fun feedWarmCacheCanRenderImmediatelyAfterRepositoryRestart() {
        val userId = 7L
        val firstRepo = AppRepo(context, OkHttpClient())
        firstRepo.saveFeedWarmCache(
            userId = userId,
            days = listOf("2026-08-03"),
            feedByDay = mapOf("2026-08-03" to emptyList()),
            promptMetaByDay = mapOf("2026-08-03" to PromptMeta(day = "2026-08-03")),
            monthRecapByDay = emptyMap(),
            revisions = mapOf("2026-08-03" to 9L)
        )

        val restored = AppRepo(context, OkHttpClient()).loadLastWarmCache()?.feed
        assertEquals(listOf("2026-08-03"), restored?.days)
        assertEquals(9L, restored?.revisions?.get("2026-08-03"))
    }

    private fun timeline(id: String, title: String) = HubTimelineItem(id = id, title = title)
}
