package com.selfhosted.daily

/** Provider-neutral release selection. Network and cache policy live in the distribution repositories. */
object UpdateReleaseChecker {
    fun findUpdate(currentVersion: String, releases: List<DistributionRelease>): UpdateInfo? {
        val release = releases
            .filter { it.isLatest && it.installable && isVersionNewer(it.version, currentVersion) }
            .maxWithOrNull { left, right -> ReleaseHistoryParser.compareVersions(left.version, right.version) }
            ?: return null
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
            legacyOfficialArtifact = release.legacyOfficialArtifact
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
}
