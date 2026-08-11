package com.selfhosted.daily

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DistributionConfigRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("distribution_config_v1", Context.MODE_PRIVATE).edit().clear().commit()
        setApiBaseUrlOverride(context, "https://tenant.invalid/api/")
    }

    @Test
    fun backendWinsAndExactIdentityCanUseLastKnownGoodOffline() = runBlocking {
        persistUser(41)
        var fetches = 0
        val repository = DistributionConfigRepository(context, fetcher = {
            fetches++
            validConfig(profileId = 8)
        })

        val online = repository.resolve(allowNetwork = true)
        val offline = repository.resolve(allowNetwork = false)

        assertEquals(1, fetches)
        assertEquals(DistributionConfigSource.BACKEND, online?.source)
        assertEquals(DistributionConfigSource.LAST_KNOWN_GOOD, offline?.source)
        assertEquals(8L, offline?.config?.profileId)
    }

    @Test
    fun cacheNeverCrossesUsersAndMissingIdentityNeverFetches() = runBlocking {
        var fetches = 0
        val repository = DistributionConfigRepository(context, fetcher = {
            fetches++
            validConfig(profileId = 9)
        })

        assertNull(repository.resolve(allowNetwork = true))
        persistUser(1)
        repository.resolve(allowNetwork = true)
        persistUser(2)
        val otherUserOffline = repository.resolve(allowNetwork = false)

        assertEquals(1, fetches)
        assertNull(otherUserOffline)
    }

    @Test
    fun expiredCacheFallsBackOnlyForKnownOfficialOrigin() = runBlocking {
        var now = 1_000L
        persistUser(3)
        val repository = DistributionConfigRepository(context, fetcher = { validConfig(3) }, nowMillis = { now })
        repository.resolve(allowNetwork = true)
        now += 8L * 24L * 60L * 60L * 1000L
        assertNull(repository.resolve(allowNetwork = false))

        setApiBaseUrlOverride(context, "https://daily.harzcloud.de/api/")
        val official = DistributionConfigRepository(context, fetcher = { error("must not fetch") }, nowMillis = { now })
            .resolve(allowNetwork = false)
        assertEquals(DistributionConfigSource.OFFICIAL_BUILD_FALLBACK, official?.source)
    }

    @Test
    fun disabledBackendProfileIsAValidNoOpAndIsCached() = runBlocking {
        persistUser(5)
        val repository = DistributionConfigRepository(context, fetcher = {
            DistributionConfigResponse(schemaVersion = 1, enabled = false)
        })

        val online = repository.resolve(allowNetwork = true)
        val offline = repository.resolve(allowNetwork = false)

        assertEquals(false, online?.config?.enabled)
        assertEquals(false, offline?.config?.enabled)
        assertEquals(DistributionConfigSource.LAST_KNOWN_GOOD, offline?.source)
    }

    private fun persistUser(id: Long) {
        AuthSessionCoordinator.persist(context, AuthResponse(token = "token", user = User(id, "user$id", false)))
    }

    private fun validConfig(profileId: Long) = DistributionConfigResponse(
        schemaVersion = 1,
        enabled = true,
        profileId = profileId,
        profileUpdatedAt = "2026-08-11T10:00:00Z",
        channel = "stable",
        releaseIndexUrl = "https://downloads.invalid/index.json",
        expectedPackageName = "com.selfhosted.daily",
        expectedSigningCertSha256 = DistributionConfigRepository.OFFICIAL_SIGNING_CERT_SHA256
    )
}
