package com.selfhosted.daily

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.UnknownHostException

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
        assertTrue(releases.single().installable)
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
    fun profileRevisionAndChannelInvalidateTheReleaseCache() = runBlocking {
        var fetches = 0
        val source = DistributionReleaseSource(context, buildStandardHttpClient(), responseFetcher = {
            fetches++
            index("0.9.1")
        })
        val first = resolved(profileId = 1)
        source.releases(first, allowNetwork = true)

        val newRevision = first.copy(config = first.config.copy(profileUpdatedAt = "rev-new"))
        val newChannel = first.copy(config = first.config.copy(channel = "beta"))

        assertTrue(source.releases(newRevision, allowNetwork = false).releases.isEmpty())
        assertTrue(source.releases(newChannel, allowNetwork = false).releases.isEmpty())
        assertEquals(1, fetches)
    }

    @Test
    fun forcedOfflineHistoryRefreshNeverUsesTheNetwork() = runBlocking {
        var fetches = 0
        val source = DistributionReleaseSource(context, buildStandardHttpClient(), responseFetcher = {
            fetches++
            index("0.9.1")
        })
        val resolved = resolved(profileId = 1)
        source.releases(resolved, allowNetwork = true, forHistory = true)

        val offline = source.releases(resolved, allowNetwork = false, forceRefresh = true, forHistory = true)

        assertEquals(1, fetches)
        assertEquals(ChangelogHistorySource.OFFLINE_CACHE, offline.source)
    }

    @Test
    fun indexAndHistoryConnectionsUseTheirValidatedPinnedAddresses() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(index("0.9.1")))
            server.enqueue(MockResponse().setBody(index("0.9.0")))
            var policyLookups = 0
            var baseLookups = 0
            val base = OkHttpClient.Builder().dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    baseLookups += 1
                    throw UnknownHostException("base DNS must not be used")
                }
            }).build()
            val source = DistributionReleaseSource(
                context,
                base,
                addressResolver = { host ->
                    policyLookups += 1
                    arrayOf(InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1)))
                }
            )
            val config = config(33).copy(
                releaseIndexUrl = "http://index.example:${server.port}/index.json",
                releaseHistoryUrl = "http://history.example:${server.port}/history.json"
            )
            val resolved = resolved(profileId = 33).copy(config = config)

            assertEquals("0.9.1", source.releases(resolved, allowNetwork = true).releases.single().version)
            assertEquals(
                "0.9.0",
                source.releases(resolved, allowNetwork = true, forHistory = true).releases.single().version
            )

            assertEquals("/index.json", server.takeRequest().path)
            assertEquals("/history.json", server.takeRequest().path)
            assertEquals(2, policyLookups)
            assertEquals(0, baseLookups)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun pureSelectorNeverInventsAnApkFallback() {
        val release = DistributionRelease(version = "0.9.0", releaseUrl = "https://project.invalid/release")
        val update = UpdateReleaseChecker.findUpdate("0.8.28", 142028, listOf(release))

        assertNull(update)
        assertFalse(UpdateReleaseChecker.isVersionNewer("0.8.27", "0.8.28"))
    }

    @Test
    fun incompleteHistoricalEntriesRemainVisibleButAreNotInstallable() {
        val source = DistributionReleaseSource(context, buildStandardHttpClient(), responseFetcher = { null })
        val raw = """
            {"schemaVersion":1,"latest":"0.9.1","releases":[
              {"version":"0.9.1","releaseUrl":"https://project.invalid/releases/0.9.1"},
              {"version":"0.9.0","apkUrl":"https://foreign.invalid/app.apk"}
            ]}
        """.trimIndent()

        val releases = source.parseIndex(raw, config(1))

        assertEquals(listOf("0.9.1", "0.9.0"), releases.map { it.version })
        assertTrue(releases.none { it.installable })
        assertNull(UpdateReleaseChecker.findUpdate("0.8.28", 142028, releases))
    }

    @Test
    fun unknownManifestSchemaVersionIsRejected() {
        val source = DistributionReleaseSource(context, buildStandardHttpClient(), responseFetcher = { null })
        val raw = """{"schemaVersion":2,"latest":"9.9.9","releases":[{"version":"9.9.9"}]}"""

        assertTrue(source.parseIndex(raw, config(1)).isEmpty())
    }

    @Test
    fun completeGenericManifestEntryIsInstallableWithoutLegacyMode() {
        val source = DistributionReleaseSource(context, buildStandardHttpClient(), responseFetcher = { null })
        val raw = """
            {"schemaVersion":1,"latest":"0.9.2","releases":[{
              "version":"0.9.2","versionCode":142092,
              "apkUrl":"https://downloads.invalid/app.apk","sha256":"${"ab".repeat(32)}","size":1234,
              "packageName":"com.selfhosted.daily","signingCertSha256":"${DistributionConfigRepository.OFFICIAL_SIGNING_CERT_SHA256}"
            }]}
        """.trimIndent()

        val release = source.parseIndex(raw, config(profileId = 9)).single()

        assertTrue(release.installable)
        assertFalse(release.legacyOfficialArtifact)
        assertEquals("0.9.2", UpdateReleaseChecker.findUpdate("0.8.28", 142028, listOf(release))?.latestVersion)
    }

    @Test
    fun manifestEntriesAreRestrictedToConfiguredChannel() {
        val source = DistributionReleaseSource(context, buildStandardHttpClient(), responseFetcher = { null })
        val raw = """
            {"schemaVersion":1,"latest":"0.9.3","releases":[
              {"version":"0.9.3","channel":"beta"},
              {"version":"0.9.2","channel":"stable"},
              {"version":"0.9.1"}
            ]}
        """.trimIndent()

        assertEquals(listOf("0.9.2", "0.9.1"), source.parseIndex(raw, config(1)).map { it.version })
        assertEquals(listOf("0.9.3"), source.parseIndex(raw, config(1).copy(channel = "beta")).map { it.version })
    }

    @Test
    fun rootChannelAppliesUnlessAnEntryOverridesIt() {
        val source = DistributionReleaseSource(context, buildStandardHttpClient(), responseFetcher = { null })
        val raw = """
            {"schemaVersion":1,"channel":"beta","latest":"0.9.3","releases":[
              {"version":"0.9.3"},
              {"version":"0.9.2","channel":"stable"}
            ]}
        """.trimIndent()

        assertEquals(listOf("0.9.3"), source.parseIndex(raw, config(1).copy(channel = "beta")).map { it.version })
        assertEquals(listOf("0.9.2"), source.parseIndex(raw, config(1)).map { it.version })
    }

    @Test
    fun prereleasesRequireExplicitProfilePermission() {
        val source = DistributionReleaseSource(context, buildStandardHttpClient(), responseFetcher = { null })
        val raw = """
            {"schemaVersion":1,"latest":"1.0.0-beta.1","releases":[
              {"version":"1.0.0-beta.1","channel":"beta","prerelease":true}
            ]}
        """.trimIndent()
        val beta = config(1).copy(channel = "beta")

        assertTrue(source.parseIndex(raw, beta).isEmpty())
        assertEquals("1.0.0-beta.1", source.parseIndex(raw, beta.copy(allowPrerelease = true)).single().version)
        assertEquals(listOf("0.9.2"), source.parseIndex(
            """{"schemaVersion":1,"latest":"0.9.2","releases":[{"version":"0.9.2","channel":"stable"}]}""",
            config(1).copy(channel = "")
        ).map { it.version })
        assertTrue(source.parseIndex(raw, beta.copy(channel = "nightly", allowPrerelease = true)).isEmpty())
    }

    @Test
    fun optionalProfileFingerprintStillAllowsCompleteManifestEntry() {
        val source = DistributionReleaseSource(context, buildStandardHttpClient(), responseFetcher = { null })
        val raw = """
            {"schemaVersion":1,"latest":"0.9.2","releases":[{
              "version":"0.9.2","versionCode":142092,
              "apkUrl":"https://downloads.invalid/app.apk","sha256":"${"ab".repeat(32)}","size":1234,
              "packageName":"com.selfhosted.daily","signingCertSha256":"${DistributionConfigRepository.OFFICIAL_SIGNING_CERT_SHA256}"
            }]}
        """.trimIndent()

        assertTrue(source.parseIndex(raw, config(1).copy(expectedSigningCertSha256 = "")).single().installable)
    }

    @Test
    fun updateSelectionUsesHighestVersionCodeInsteadOfRootLatestOrCoreSemver() {
        val base = installable("1.0.0-beta.1", 100, isLatest = true)
        val betaTwo = installable("1.0.0-beta.2", 101)
        val stable = installable("1.0.0", 102)
        val sameNameHigherCode = installable("1.0.0", 103)
        val misleadingVersion = installable("9.0.0", 99)

        assertEquals("1.0.0-beta.2", UpdateReleaseChecker.findUpdate("1.0.0-beta.1", 100, listOf(base, betaTwo))?.latestVersion)
        assertEquals(102L, UpdateReleaseChecker.findUpdate("1.0.0-beta.2", 101, listOf(betaTwo, stable))?.versionCode)
        assertEquals(103L, UpdateReleaseChecker.findUpdate("1.0.0", 102, listOf(stable, sameNameHigherCode))?.versionCode)
        assertNull(UpdateReleaseChecker.findUpdate("1.0.0", 100, listOf(misleadingVersion)))
        assertEquals(103L, UpdateReleaseChecker.findUpdate("0.8.28", 90, listOf(base, sameNameHigherCode))?.versionCode)
    }

    @Test
    fun minimumSupportedVersionOnlyMarksACompleteAvailableCandidateAsRequired() {
        val verified = installable("1.2.0", 120)
        val required = UpdateReleaseChecker.findUpdate("1.0.0", 100, listOf(verified), minSupportedVersionCode = 110)
        val unavailable = UpdateReleaseChecker.findUpdate("1.0.0", 100, listOf(verified.copy(versionCode = 105)), minSupportedVersionCode = 110)

        assertTrue(required?.required == true)
        assertTrue(unavailable?.required == false)
    }

    private fun installable(version: String, versionCode: Long, isLatest: Boolean = false) = DistributionRelease(
        version = version,
        versionCode = versionCode,
        apkUrl = "https://downloads.invalid/app.apk",
        apkSha256 = "ab".repeat(32),
        apkSize = 1234,
        packageName = "com.selfhosted.daily",
        signingCertSha256 = DistributionConfigRepository.OFFICIAL_SIGNING_CERT_SHA256,
        installable = true,
        isLatest = isLatest
    )

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
