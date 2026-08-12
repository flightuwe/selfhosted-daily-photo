package com.selfhosted.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseHistoryParserTest {
    @Test
    fun stableVersionsUseNumericSemverOrdering() {
        assertTrue(ReleaseHistoryParser.isStableVersion("0.8.16"))
        assertFalse(ReleaseHistoryParser.isStableVersion("v0.8.16-beta"))
        assertTrue(ReleaseHistoryParser.compareVersions("0.8.10", "0.8.9") > 0)
        assertTrue(ReleaseHistoryParser.compareVersions("0.9.0", "0.8.99") > 0)
        assertTrue(ReleaseHistoryParser.compareVersions("0.9.1-beta.1", "0.9.0") > 0)
    }

    @Test
    fun parsesReleaseBodyAndIgnoresWorkflowAssets() {
        val parsed = ReleaseHistoryParser.parseReleaseBody(
            """
            ## Daily v0.8.16 – Changelog-Verlauf

            ### Highlights
            - Vollstaendiger Verlauf direkt in der App

            ### Details
            - Lokaler Cache fuer Offline-Nutzung

            ### Assets
            - Android APK: `app-release.apk`
            - Changelog JSON: `changelog.json`
            """.trimIndent()
        )

        assertEquals("Daily v0.8.16 – Changelog-Verlauf", parsed.title)
        assertEquals(listOf("Vollstaendiger Verlauf direkt in der App"), parsed.highlights)
        assertEquals(listOf("Lokaler Cache fuer Offline-Nutzung"), parsed.details)
    }
}
