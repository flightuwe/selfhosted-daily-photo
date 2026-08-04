package com.selfhosted.daily

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedPagingPolicyTest {
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
