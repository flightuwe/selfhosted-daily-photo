package com.selfhosted.daily

/** Provider-neutral release selection. Network and cache policy live in the distribution repositories. */
object UpdateReleaseChecker {
    fun findUpdate(
        currentVersion: String,
        currentVersionCode: Long,
        releases: List<DistributionRelease>,
        minSupportedVersionCode: Long? = null
    ): UpdateInfo? {
        if (currentVersionCode < 1 || !isPlausibleVersion(currentVersion)) return null
        val release = releases
            .filter { it.installable && it.versionCode != null && it.versionCode > currentVersionCode && isPlausibleVersion(it.version) }
            .maxWithOrNull { left, right ->
                val codeOrder = left.versionCode!!.compareTo(right.versionCode!!)
                if (codeOrder != 0) codeOrder else ReleaseHistoryParser.compareVersions(left.version, right.version)
            }
            ?: return null
        val required = minSupportedVersionCode != null && currentVersionCode < minSupportedVersionCode &&
            release.versionCode!! >= minSupportedVersionCode
        return UpdateInfo(
            latestVersion = release.version,
            releaseUrl = release.releaseUrl,
            apkUrl = release.apkUrl,
            versionCode = release.versionCode,
            apkSha256 = release.apkSha256,
            apkSize = release.apkSize,
            packageName = release.packageName,
            signingCertSha256 = release.signingCertSha256,
            profilePackageName = release.profilePackageName,
            profileSigningCertSha256 = release.profileSigningCertSha256,
            apkUrlExplicitlyConfigured = release.apkUrlExplicitlyConfigured,
            legacyOfficialArtifact = release.legacyOfficialArtifact,
            required = required
        )
    }

    fun changelogLinesForVersion(version: String, releases: List<DistributionRelease>): List<String> {
        val normalized = version.trim().removePrefix("v")
        val release = releases.firstOrNull { it.version.trim().removePrefix("v") == normalized }
            ?: return emptyList()
        return (release.highlights + release.details).map(String::trim).filter(String::isNotBlank).distinct().take(48)
    }

    fun isVersionNewer(candidate: String, current: String): Boolean =
        ReleaseHistoryParser.compareVersions(candidate.trim().removePrefix("v"), current.trim().removePrefix("v")) > 0

    private fun isPlausibleVersion(version: String): Boolean =
        VERSION_NAME.matches(version.trim().removePrefix("v"))

    private val VERSION_NAME = Regex("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$")
}
