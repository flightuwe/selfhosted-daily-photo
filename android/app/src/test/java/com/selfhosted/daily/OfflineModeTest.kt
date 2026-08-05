package com.selfhosted.daily

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals

@RunWith(RobolectricTestRunner::class)
class OfflineModeTest {
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun reset() {
        OfflineModeManager.setEnabled(context, false)
    }

    @Test
    fun persistsOfflineStateAndRejectsNetworkWork() {
        OfflineModeManager.setEnabled(context, true)

        assertTrue(OfflineModeManager.isEnabled(context))
        val rejected = runCatching { OfflineModeManager.requireOnline(context) }.exceptionOrNull()
        assertTrue(rejected is OfflineModeException)

        OfflineModeManager.setEnabled(context, false)
        assertFalse(OfflineModeManager.isEnabled(context))
    }

    @Test
    fun changelogHistoryUsesOnlyItsLocalCacheWhenNetworkIsDisallowed() = runBlocking {
        context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(
                "github_release_history_v1",
                "[{\"version\":\"0.8.21\",\"title\":\"Cached\",\"highlights\":[\"Local\"]}]"
            )
            .apply()

        val result = ReleaseHistoryRepository(context).history(allowNetwork = false, forceRefresh = true)

        assertEquals(ChangelogHistorySource.OFFLINE_CACHE, result.source)
        assertEquals(listOf("0.8.21"), result.entries.map { it.version })
    }
}
