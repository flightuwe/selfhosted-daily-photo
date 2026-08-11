package com.selfhosted.daily

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DistributionReleaseSourceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("distribution_releases_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun neutralIndexCarriesIntegrityAndIdentityMetadata() {
        val source = DistributionReleaseSource(context, buildStandardHttpClient(), responseFetcher = { null })
        val releases = source.parseIndex(index("0.9.0"), config(profileId = 4))

        assertEquals(1, releases.size)
        assertEquals("0.9.0", releases.single().version)
        assertEquals("ab".repeat(32), releases.single().apkSha256)
        assertEquals(1234L, releases.single().apkSize)
        assertEquals("com.selfhosted.daily", releases.single().packageName)
        assertTrue(releases.single().legacyOfficialArtifact)
    }

    @Test
    fun releaseCacheIsSeparatedByFullProfileDimension() = runBlocking {
        var fetches = 0
        val source = DistributionReleaseSource(context, buildStandardHttpClient(), responseFetcher = {
            fetches++
            index("0.9.1")
        })
        val first = resolved(profileId = 1)
        source.releases(first, allowNetwork = true)

        val sameOffline = source.releases(first, allowNetwork = false)
        val otherProfileOffline = source.releases(resolved(profileId = 2), allowNetwork = false)

        assertEquals(1, fetches)
        assertEquals("0.9.1", sameOffline.releases.single().version)
        assertTrue(otherProfileOffline.releases.isEmpty())
    }

    @Test
    fun pureSelectorNeverInventsAnApkFallback() {
        val release = DistributionRelease(version = "0.9.0", releaseUrl = "https://project.invalid/release")
        val update = UpdateReleaseChecker.findUpdate("0.8.28", listOf(release))

        assertEquals("0.9.0", update?.latestVersion)
        assertNull(update?.apkUrl)
        assertFalse(UpdateReleaseChecker.isVersionNewer("0.8.27", "0.8.28"))
    }

    private fun resolved(profileId: Long) = ResolvedDistributionConfig(
        apiOrigin = "https://tenant.invalid",
        userId = 7,
        config = config(profileId),
        source = DistributionConfigSource.BACKEND,
        cachedAt = 1L
    )

    private fun config(profileId: Long) = DistributionConfigResponse(
        schemaVersion = 1,
        enabled = true,
        profileId = profileId,
        profileUpdatedAt = "rev-$profileId",
        channel = "stable",
        releaseIndexUrl = "https://downloads.invalid/index.json",
        expectedPackageName = "com.selfhosted.daily",
        expectedSigningCertSha256 = DistributionConfigRepository.OFFICIAL_SIGNING_CERT_SHA256
    )

    private fun index(version: String) = """
        {"schemaVersion":1,"latest":"$version","releases":[{
          "version":"$version","apkUrl":"https://releases.daily.harzcloud.de/apk/v$version/app-release.apk",
          "releaseUrl":"https://project.invalid/releases/v$version","sha256":"${"ab".repeat(32)}","size":1234,
          "highlights":["One"],"details":["Two"]
        }]}
    """.trimIndent()
}
