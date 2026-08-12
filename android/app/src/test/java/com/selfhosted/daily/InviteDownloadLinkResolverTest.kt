package com.selfhosted.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteDownloadLinkResolverTest {
    @Test
    fun effectiveOfficialAndForeignProfilesWin() {
        val official = resolved("https://code.harzcloud.de/daily-harzcloud/daily/releases", DistributionConfigSource.BACKEND)
        val foreign = resolved("https://forge.example.net/team/daily/releases", DistributionConfigSource.BACKEND)

        assertEquals(official.config.releasePageUrl, InviteDownloadLinkResolver.resolve(official, null, "https://daily.harzcloud.de/api/", false))
        assertEquals(foreign.config.releasePageUrl, InviteDownloadLinkResolver.resolve(foreign, null, "https://daily.example.net/api/", false))
    }

    @Test
    fun disabledProfileUsesServerConfigThenSafeApiOrigin() {
        val disabled = resolved("https://ignored.example/releases", DistributionConfigSource.BACKEND)
            .copy(config = DistributionConfigResponse(schemaVersion = 1, enabled = false))
        assertEquals(
            "https://downloads.example.net/daily",
            InviteDownloadLinkResolver.resolve(disabled, PublicDailyConfig(downloadUrl = "https://downloads.example.net/daily"), "https://api.example.net/api/", false)
        )
        assertEquals("https://api.example.net/#download", InviteDownloadLinkResolver.resolve(disabled, null, "https://api.example.net/api/", false))
    }

    @Test
    fun offlineUsesOnlyIdentityBoundLastKnownGoodProfile() {
        val lkg = resolved("https://forge.example.net/team/daily/releases", DistributionConfigSource.LAST_KNOWN_GOOD)
        assertEquals(lkg.config.releasePageUrl, InviteDownloadLinkResolver.resolve(lkg, null, "https://api.example.net/api/", true))
        assertNull(InviteDownloadLinkResolver.resolve(lkg.copy(source = DistributionConfigSource.BACKEND), null, "https://api.example.net/api/", true))
        assertNull(InviteDownloadLinkResolver.resolve(null, null, "https://api.example.net/api/", true))
    }

    private fun resolved(releasePageUrl: String, source: DistributionConfigSource) = ResolvedDistributionConfig(
        apiOrigin = "https://api.example.net",
        userId = 7,
        source = source,
        config = DistributionConfigResponse(
            schemaVersion = 1,
            enabled = true,
            profileId = 2,
            profileUpdatedAt = "rev-1",
            releasePageUrl = releasePageUrl,
            projectUrl = "https://project.example.net/daily",
            releaseIndexUrl = "https://downloads.example.net/index.json",
            expectedPackageName = "com.selfhosted.daily"
        )
    )
}
