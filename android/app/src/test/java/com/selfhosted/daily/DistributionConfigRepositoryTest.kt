package com.selfhosted.daily

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

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
    fun cacheNeverCrossesApiOriginsForTheSameUser() = runBlocking {
        persistUser(4)
        val repository = DistributionConfigRepository(context, fetcher = { validConfig(profileId = 4) })
        repository.resolve(allowNetwork = true)

        setApiBaseUrlOverride(context, "https://other.invalid/api/")

        assertNull(repository.resolve(allowNetwork = false))
    }

    @Test
    fun rejectedFetchThatClearsSessionNeverUsesLastKnownGood() = runBlocking {
        persistUser(4)
        DistributionConfigRepository(context, fetcher = { validConfig(4) }).resolve(allowNetwork = true)
        var fetches = 0
        val rejected = DistributionConfigRepository(context, fetcher = {
            fetches++
            AuthSessionCoordinator.clear(context)
            throw IOException("wrapped auth rejection")
        })

        assertNull(rejected.resolve(allowNetwork = true))
        assertEquals(1, fetches)
        assertTrue(AuthSessionCoordinator.snapshot(context).accessToken.isBlank())
    }

    @Test
    fun temporaryFailureUsesLkgOnlyWhileExactSessionRemains() = runBlocking {
        persistUser(4)
        DistributionConfigRepository(context, fetcher = { validConfig(4) }).resolve(allowNetwork = true)
        val temporary = DistributionConfigRepository(context, fetcher = { throw IOException("offline") })

        assertEquals(DistributionConfigSource.LAST_KNOWN_GOOD, temporary.resolve(allowNetwork = true)?.source)

        val rotated = DistributionConfigRepository(context, fetcher = {
            AuthSessionCoordinator.persist(context, AuthResponse(token = "rotated", user = User(4, "user4", false)))
            throw IOException("failed after token change")
        })
        assertNull(rotated.resolve(allowNetwork = true))
    }

    @Test
    fun normalizedOriginKeepsSchemeHostAndEffectivePortButIgnoresPath() {
        assertEquals(
            "https://tenant.invalid",
            DistributionConfigRepository.normalizedApiOrigin("HTTPS://Tenant.Invalid:443/api/v1/")
        )
        assertEquals(
            "https://tenant.invalid:8443",
            DistributionConfigRepository.normalizedApiOrigin("https://tenant.invalid:8443/other/path")
        )
        assertEquals(
            "http://tenant.invalid",
            DistributionConfigRepository.normalizedApiOrigin("http://tenant.invalid:80/api")
        )
    }

    @Test
    fun logoutClearsPersistentDistributionIdentity() {
        persistUser(14)
        assertEquals(14L, AuthSessionCoordinator.snapshot(context).userId)

        AuthSessionCoordinator.clear(context)

        val cleared = AuthSessionCoordinator.snapshot(context)
        assertEquals(0L, cleared.userId)
        assertTrue(cleared.accessToken.isBlank())
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

    @Test
    fun authenticatedBackendMayReturnDeploymentApprovedHttpTarget() = runBlocking {
        persistUser(6)
        val repository = DistributionConfigRepository(context, fetcher = {
            validConfig(6).copy(releaseIndexUrl = "http://updates.lan/index.json")
        })

        assertEquals("http://updates.lan/index.json", repository.resolve(allowNetwork = true)?.config?.releaseIndexUrl)
    }

    @Test
    fun profileSigningFingerprintIsOptionalButMustBeValidWhenConfigured() = runBlocking {
        persistUser(12)
        val optional = DistributionConfigRepository(context, fetcher = {
            validConfig(12).copy(expectedSigningCertSha256 = "")
        })
        assertEquals(12L, optional.resolve(allowNetwork = true)?.config?.profileId)

        persistUser(13)
        val malformed = DistributionConfigRepository(context, fetcher = {
            validConfig(13).copy(expectedSigningCertSha256 = "not-a-sha256")
        })
        assertNull(malformed.resolve(allowNetwork = true))
    }

    @Test
    fun existingSessionLearnsDistributionIdentityFromSuccessfulMeBootstrap() = runBlocking {
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("access_token", "legacy-token").commit()
        var fetches = 0
        val repository = DistributionConfigRepository(context, fetcher = {
            fetches++
            validConfig(11)
        })
        assertNull(repository.resolve(allowNetwork = true))

        AuthSessionCoordinator.persistUserId(context, 77)
        val resolved = repository.resolve(allowNetwork = true)

        assertEquals(1, fetches)
        assertEquals(77L, resolved?.userId)
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
