package com.selfhosted.daily

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReleaseHistoryRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun requiredInstalledVersionRefreshesAnOtherwiseFreshOlderCache() = runBlocking {
        seedCache("0.8.16")
        var fetches = 0
        val repository = ReleaseHistoryRepository(context, releaseFetcher = {
            fetches++
            listOf(entry("0.8.22"), entry("0.8.16"))
        })

        val result = repository.history(allowNetwork = true, requiredVersion = "0.8.22")

        assertEquals(1, fetches)
        assertEquals(ChangelogHistorySource.NETWORK, result.source)
        assertEquals(listOf("0.8.22", "0.8.16"), result.entries.map { it.version })
    }

    @Test
    fun manualRefreshBypassesFreshCacheAndPersistsTheNewHistory() = runBlocking {
        seedCache("0.8.16")
        val repository = ReleaseHistoryRepository(context, releaseFetcher = { listOf(entry("0.8.22"), entry("0.8.16")) })

        val refreshed = repository.history(allowNetwork = true, forceRefresh = true)
        val cached = ReleaseHistoryRepository(context, releaseFetcher = { emptyList() }).history(allowNetwork = true)

        assertEquals(ChangelogHistorySource.NETWORK, refreshed.source)
        assertEquals(ChangelogHistorySource.FRESH_CACHE, cached.source)
        assertEquals("0.8.22", cached.entries.first().version)
    }

    @Test
    fun failedOnlineRefreshKeepsTheExistingCacheAndMarksItStale() = runBlocking {
        seedCache("0.8.16")

        val result = ReleaseHistoryRepository(context, releaseFetcher = { emptyList() })
            .history(allowNetwork = true, forceRefresh = true)

        assertEquals(ChangelogHistorySource.STALE_CACHE, result.source)
        assertEquals(listOf("0.8.16"), result.entries.map { it.version })
    }

    private fun seedCache(version: String) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putString("github_release_history_v1", "[{\"version\":\"$version\",\"title\":\"Cached\",\"highlights\":[\"Local\"]}]")
            .putLong("github_release_history_cached_at_v1", now)
            .commit()
    }

    private fun entry(version: String) = ChangelogEntry(version, "Daily $version", listOf("Test"))
}
