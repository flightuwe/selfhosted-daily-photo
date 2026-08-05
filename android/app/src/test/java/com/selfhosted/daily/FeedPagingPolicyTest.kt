package com.selfhosted.daily

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedPagingPolicyTest {
    @Test
    fun chronologicalPagingAlwaysSeeksFromTheRenderedEdgeNotTheCalendarCache() {
        // This is the March-to-June regression: a startup index may contain
        // June and August while a bottom jump has injected March. Paging from
        // the calendar cache would incorrectly choose June as the next anchor.
        val renderedMarchWindow = listOf("2026-03-11", "2026-03-10", "2026-03-08")

        assertEquals(
            FeedEdgeWindowRequest(
                anchorDay = "2026-03-11",
                beforeDays = 3,
                afterDays = 0,
                appendOlder = false
            ),
            chronologicalFeedEdgeWindowRequest(
                visibleDays = renderedMarchWindow,
                direction = FeedAutoPageDirection.NEWER,
                count = 3
            )
        )
        assertEquals(
            FeedEdgeWindowRequest(
                anchorDay = "2026-03-08",
                beforeDays = 0,
                afterDays = 3,
                appendOlder = true
            ),
            chronologicalFeedEdgeWindowRequest(
                visibleDays = renderedMarchWindow,
                direction = FeedAutoPageDirection.OLDER,
                count = 3
            )
        )
    }

    @Test
    fun chronologicalPagingHasNoRequestWithoutARenderedEdge() {
        assertEquals(
            null,
            chronologicalFeedEdgeWindowRequest(emptyList(), FeedAutoPageDirection.NEWER, 3)
        )
    }

    @Test
    fun targetedNavigationSuppressesBothEdgesUntilScrollIsSettled() {
        assertEquals(
            FeedAutoPageDirection.NONE,
            feedAutoPageDirection(
                rowsSize = 12,
                firstVisibleIndex = 0,
                lastVisibleIndex = 11,
                paging = false,
                refreshInFlight = false,
                feedWindowReloadInFlight = false,
                navigationInFlight = true,
                pullInProgress = false,
                hasNewer = true,
                hasOlder = true
            )
        )
    }

    @Test
    fun pagerOnlyLoadsAnAvailableEdge() {
        assertEquals(
            FeedAutoPageDirection.NEWER,
            feedAutoPageDirection(20, 1, 4, false, false, false, false, false, true, true)
        )
        assertEquals(
            FeedAutoPageDirection.OLDER,
            feedAutoPageDirection(20, 10, 18, false, false, false, false, false, true, true)
        )
        assertEquals(
            FeedAutoPageDirection.NONE,
            feedAutoPageDirection(20, 0, 3, false, false, false, false, false, false, true)
        )
    }

    @Test
    fun transientLoadingAndRefreshStatesNeverSchedulePaging() {
        assertEquals(
            FeedAutoPageDirection.NONE,
            feedAutoPageDirection(8, 0, 7, true, false, false, false, false, true, true)
        )
        assertEquals(
            FeedAutoPageDirection.NONE,
            feedAutoPageDirection(8, 0, 7, false, false, true, false, false, true, true)
        )
    }

    @Test
    fun targetedNavigationAlwaysRequestsACompleteFeedWindow() {
        val cachedRevisions = mapOf(
            "2026-08-05" to 7L,
            "2026-08-04" to 6L
        )

        assertEquals(
            emptyMap<String, Long>(),
            feedWindowKnownRevisions(cachedRevisions, isTargetedNavigation = true)
        )
        assertEquals(
            cachedRevisions,
            feedWindowKnownRevisions(cachedRevisions, isTargetedNavigation = false)
        )
    }

    @Test
    fun unchangedEdgeRemainsLatchedAcrossACompletedNoOpRequest() {
        val entered = nextFeedAutoPagingLatch(
            previous = FeedAutoPageDirection.NONE,
            viewportEdge = FeedAutoPageDirection.NEWER,
            permittedDirection = FeedAutoPageDirection.NEWER
        )
        assertEquals(FeedAutoPageDirection.NEWER, entered)

        val whileLoading = nextFeedAutoPagingLatch(
            previous = entered,
            viewportEdge = FeedAutoPageDirection.NEWER,
            permittedDirection = FeedAutoPageDirection.NONE
        )
        assertEquals(FeedAutoPageDirection.NEWER, whileLoading)

        val afterNotModified = nextFeedAutoPagingLatch(
            previous = whileLoading,
            viewportEdge = FeedAutoPageDirection.NEWER,
            permittedDirection = FeedAutoPageDirection.NEWER
        )
        assertEquals(FeedAutoPageDirection.NEWER, afterNotModified)
        assertEquals(
            FeedAutoPageDirection.NONE,
            nextFeedAutoPagingLatch(afterNotModified, FeedAutoPageDirection.NONE, FeedAutoPageDirection.NONE)
        )
    }
}
