package com.selfhosted.daily

import android.content.Context
import com.google.gson.Gson
import java.net.URI
import java.security.MessageDigest

class DistributionConfigRepository(
    context: Context,
    private val fetcher: suspend (authorization: String) -> DistributionConfigResponse,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    suspend fun resolve(allowNetwork: Boolean): ResolvedDistributionConfig? {
        val session = AuthSessionCoordinator.snapshot(appContext)
        val userId = session.userId
        if (userId <= 0 || !session.hasAccessToken()) return null
        val apiOrigin = normalizedApiOrigin(resolveApiBaseUrl(appContext)) ?: return null
        val identityKey = digest("$apiOrigin|$userId")

        if (allowNetwork) {
            val fetched = runCatching { fetcher(session.authHeader()) }
                .getOrNull()
                ?.takeIf { isValid(it, apiOrigin) }
            if (fetched != null) {
                val resolved = ResolvedDistributionConfig(
                    apiOrigin = apiOrigin,
                    userId = userId,
                    config = fetched,
                    source = DistributionConfigSource.BACKEND,
                    cachedAt = nowMillis()
                )
                save(identityKey, resolved)
                return resolved
            }
        }

        load(identityKey, apiOrigin, userId)?.let { cached ->
            if (nowMillis() - cached.cachedAt <= LAST_KNOWN_GOOD_TTL_MS) return cached
        }

        return officialFallback(apiOrigin, userId)
    }

    private fun save(identityKey: String, resolved: ResolvedDistributionConfig) {
        val dimensionKey = digest(resolved.cacheDimension)
        prefs.edit()
            .putString("$CACHE_PREFIX$dimensionKey", gson.toJson(resolved))
            .putString("$POINTER_PREFIX$identityKey", dimensionKey)
            .apply()
    }

    private fun load(identityKey: String, apiOrigin: String, userId: Long): ResolvedDistributionConfig? {
        val dimensionKey = prefs.getString("$POINTER_PREFIX$identityKey", "").orEmpty()
        if (dimensionKey.isBlank()) return null
        val raw = prefs.getString("$CACHE_PREFIX$dimensionKey", "").orEmpty()
        val cached = runCatching { gson.fromJson(raw, ResolvedDistributionConfig::class.java) }.getOrNull()
            ?: return null
        if (cached.apiOrigin != apiOrigin || cached.userId != userId || !isValid(cached.config, apiOrigin)) return null
        return cached.copy(source = DistributionConfigSource.LAST_KNOWN_GOOD)
    }

    private fun officialFallback(apiOrigin: String, userId: Long): ResolvedDistributionConfig? {
        if (apiOrigin !in OFFICIAL_API_ORIGINS) return null
        return ResolvedDistributionConfig(
            apiOrigin = apiOrigin,
            userId = userId,
            source = DistributionConfigSource.OFFICIAL_BUILD_FALLBACK,
            config = DistributionConfigResponse(
                schemaVersion = 1,
                enabled = true,
                profileId = OFFICIAL_FALLBACK_PROFILE_ID,
                profileUpdatedAt = "build-fallback-v1",
                channel = "stable",
                projectUrl = "https://code.harzcloud.de/daily-harzcloud/daily",
                releaseIndexUrl = "https://releases.daily.harzcloud.de/index.json",
                releasePageUrl = "https://code.harzcloud.de/daily-harzcloud/daily/releases",
                expectedPackageName = "com.selfhosted.daily",
                expectedSigningCertSha256 = OFFICIAL_SIGNING_CERT_SHA256
            )
        )
    }

    internal fun isValid(config: DistributionConfigResponse, apiOrigin: String): Boolean {
        if (config.schemaVersion != 1) return false
        if (!config.enabled) return true
        if (config.profileId <= 0 || config.channel.isBlank() || config.profileUpdatedAt.isBlank()) return false
        val allowHttp = apiOrigin.startsWith("http://")
        val urls = listOf(config.projectUrl, config.releaseIndexUrl, config.releaseHistoryUrl, config.releasePageUrl) +
            listOfNotNull(config.directApk?.url)
        if (urls.filter { it.isNotBlank() }.any { !isAllowedUrl(it, allowHttp) }) return false
        val direct = config.directApk
        if (direct != null) {
            if (direct.versionName.isBlank() || direct.versionCode <= 0 || direct.url.isBlank()) return false
            if (!SHA256.matches(direct.sha256.trim())) return false
            if (direct.size != null && direct.size <= 0) return false
        } else if (config.releaseIndexUrl.isBlank()) {
            return false
        }
        val configuredSigner = config.expectedSigningCertSha256.trim()
        return config.expectedPackageName.isNotBlank() &&
            (configuredSigner.isBlank() || SHA256.matches(configuredSigner))
    }

    private fun isAllowedUrl(raw: String, allowHttp: Boolean): Boolean = runCatching {
        val uri = URI(raw.trim())
        uri.host?.isNotBlank() == true && uri.userInfo == null && uri.fragment == null &&
            (uri.scheme.equals("https", true) || (allowHttp && uri.scheme.equals("http", true)))
    }.getOrDefault(false)

    companion object {
        private const val PREF_NAME = "distribution_config_v1"
        private const val CACHE_PREFIX = "config:"
        private const val POINTER_PREFIX = "identity:"
        private const val LAST_KNOWN_GOOD_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val OFFICIAL_FALLBACK_PROFILE_ID = -1L
        const val OFFICIAL_SIGNING_CERT_SHA256 = "72e05a43a7be5837d83c922ad3496782499547fd94a5efa431dec712df6d4138"
        private val OFFICIAL_API_ORIGINS = setOf("https://daily.harzcloud.de", "https://daily.broutschek.de")
        private val SHA256 = Regex("^[a-fA-F0-9]{64}$")

        internal fun normalizedApiOrigin(raw: String): String? = runCatching {
            val uri = URI(raw.trim())
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            if (scheme !in setOf("http", "https") || host.isBlank()) return null
            val defaultPort = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
            "$scheme://$host${if (uri.port > 0 && !defaultPort) ":${uri.port}" else ""}"
        }.getOrNull()

        internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
