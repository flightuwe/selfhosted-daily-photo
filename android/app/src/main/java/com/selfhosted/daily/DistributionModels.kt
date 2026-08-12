package com.selfhosted.daily

data class DistributionDirectApk(
    val versionName: String = "",
    val versionCode: Long = 0,
    val url: String = "",
    val sha256: String = "",
    val size: Long? = null
)

data class DistributionConfigResponse(
    val schemaVersion: Int = 0,
    val enabled: Boolean = false,
    val profileId: Long = 0,
    val profileUpdatedAt: String = "",
    val channel: String = "stable",
    val projectUrl: String = "",
    val releaseIndexUrl: String = "",
    val releaseHistoryUrl: String = "",
    val releasePageUrl: String = "",
    val directApk: DistributionDirectApk? = null,
    val expectedPackageName: String = "",
    val expectedSigningCertSha256: String = "",
    val minSupportedVersionCode: Long? = null,
    val allowPrerelease: Boolean = false
)

data class ResolvedDistributionConfig(
    val apiOrigin: String,
    val userId: Long,
    val config: DistributionConfigResponse,
    val source: DistributionConfigSource,
    val cachedAt: Long = 0L
) {
    val cacheDimension: String
        get() = listOf(
            apiOrigin,
            userId.toString(),
            config.profileId.toString(),
            config.channel.trim().lowercase(),
            config.profileUpdatedAt.trim()
        ).joinToString("|")
}

enum class DistributionConfigSource {
    BACKEND,
    LAST_KNOWN_GOOD,
    OFFICIAL_BUILD_FALLBACK
}

data class DistributionRelease(
    val version: String,
    val versionCode: Long? = null,
    val title: String = "",
    val highlights: List<String> = emptyList(),
    val details: List<String> = emptyList(),
    val releasedAt: String = "",
    val releaseUrl: String = "",
    val apkUrl: String? = null,
    val apkSha256: String? = null,
    val apkSize: Long? = null,
    val packageName: String = "",
    val signingCertSha256: String = "",
    val profilePackageName: String = "",
    val profileSigningCertSha256: String = "",
    val apkUrlExplicitlyConfigured: Boolean = false,
    val legacyOfficialArtifact: Boolean = false,
    val installable: Boolean = false,
    val isLatest: Boolean = false
)

data class UpdateInfo(
    val latestVersion: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val versionCode: Long? = null,
    val apkSha256: String? = null,
    val apkSize: Long? = null,
    val packageName: String = "",
    val signingCertSha256: String = "",
    val profilePackageName: String = "",
    val profileSigningCertSha256: String = "",
    val apkUrlExplicitlyConfigured: Boolean = false,
    val legacyOfficialArtifact: Boolean = false,
    val required: Boolean = false
) {
    val targetHost: String
        get() = runCatching { java.net.URI(apkUrl ?: releaseUrl).host.orEmpty() }.getOrDefault("")
}
