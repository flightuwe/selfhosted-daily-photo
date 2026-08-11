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
        context.getSharedPreferences("release_history_v2", android.content.Context.MODE_PRIVATE).edit().clear().commit()
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
        var fetches = 0
        val repository = ReleaseHistoryRepository(context, releaseFetcher = {
            fetches++
            listOf(ChangelogEntry("0.8.21", "Cached", listOf("Local")))
        })
        repository.history(allowNetwork = true)

        val result = repository.history(allowNetwork = false, forceRefresh = true)

        assertEquals(1, fetches)
        assertEquals(ChangelogHistorySource.OFFLINE_CACHE, result.source)
        assertEquals(listOf("0.8.21"), result.entries.map { it.version })
    }
}
