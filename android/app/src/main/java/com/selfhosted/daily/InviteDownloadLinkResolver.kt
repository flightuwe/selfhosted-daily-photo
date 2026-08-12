package com.selfhosted.daily

import java.net.URI

internal object InviteDownloadLinkResolver {
    fun resolve(
        distribution: ResolvedDistributionConfig?,
        publicConfig: PublicDailyConfig?,
        apiBaseUrl: String,
        offline: Boolean
    ): String? {
        val profileUrl = distribution
            ?.takeIf { it.config.enabled && (!offline || it.source == DistributionConfigSource.LAST_KNOWN_GOOD) }
            ?.config
            ?.let { config ->
                listOf(config.releasePageUrl, config.projectUrl, config.directApk?.url.orEmpty())
                    .firstNotNullOfOrNull { safeUrl(it) }
            }
        if (profileUrl != null) return profileUrl
        if (offline) return null
        listOf(publicConfig?.downloadUrl.orEmpty(), publicConfig?.projectUrl.orEmpty())
            .firstNotNullOfOrNull { safeUrl(it) }
            ?.let { return it }
        val origin = DistributionConfigRepository.normalizedApiOrigin(apiBaseUrl) ?: return null
        return safeUrl("$origin/#download")
    }

    private fun safeUrl(raw: String): String? = runCatching {
        val clean = raw.trim()
        val uri = URI(clean)
        if (clean.isBlank() || uri.host.isNullOrBlank() || uri.userInfo != null || uri.scheme?.lowercase() !in setOf("https", "http")) {
            null
        } else clean
    }.getOrNull()
}
