package com.selfhosted.daily

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class DistributionReleaseResult(
    val releases: List<DistributionRelease>,
    val source: ChangelogHistorySource,
    val cachedAt: Long = 0L
)

class DistributionReleaseSource(
    context: Context,
    private val httpClient: OkHttpClient,
    private val responseFetcher: ((String) -> String?)? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    suspend fun releases(
        resolved: ResolvedDistributionConfig,
        allowNetwork: Boolean,
        forceRefresh: Boolean = false,
        forHistory: Boolean = false
    ): DistributionReleaseResult = withContext(Dispatchers.IO) {
        val config = resolved.config
        if (!config.enabled) return@withContext DistributionReleaseResult(emptyList(), ChangelogHistorySource.UNAVAILABLE)
        config.directApk?.let { direct ->
            return@withContext DistributionReleaseResult(
                listOf(directRelease(config, direct)),
                resolved.source.toHistorySource(),
                resolved.cachedAt
            )
        }

        val cacheKey = cacheKey(resolved, forHistory)
        val cached = parseIndex(prefs.getString("$CACHE_PREFIX$cacheKey", "").orEmpty(), config)
        val cachedAt = prefs.getLong("$CACHE_AT_PREFIX$cacheKey", 0L)
        val fresh = cached.isNotEmpty() && nowMillis() - cachedAt <= CACHE_TTL_MS
        if (!allowNetwork) {
            return@withContext if (cached.isNotEmpty()) {
                DistributionReleaseResult(cached, ChangelogHistorySource.OFFLINE_CACHE, cachedAt)
            } else DistributionReleaseResult(emptyList(), ChangelogHistorySource.UNAVAILABLE)
        }
        if (!forceRefresh && fresh) {
            return@withContext DistributionReleaseResult(cached, ChangelogHistorySource.FRESH_CACHE, cachedAt)
        }

        val url = if (forHistory) {
            config.releaseHistoryUrl.trim().ifBlank { config.releaseIndexUrl.trim() }
        } else {
            config.releaseIndexUrl.trim()
        }
        val raw = get(url)
        val releases = raw?.let { parseIndex(it, config) }.orEmpty()
        if (releases.isNotEmpty()) {
            prefs.edit()
                .putString("$CACHE_PREFIX$cacheKey", raw)
                .putLong("$CACHE_AT_PREFIX$cacheKey", nowMillis())
                .apply()
            DistributionReleaseResult(releases, ChangelogHistorySource.NETWORK, nowMillis())
        } else if (cached.isNotEmpty()) {
            DistributionReleaseResult(cached, ChangelogHistorySource.STALE_CACHE, cachedAt)
        } else {
            DistributionReleaseResult(emptyList(), ChangelogHistorySource.UNAVAILABLE)
        }
    }

    private fun directRelease(config: DistributionConfigResponse, direct: DistributionDirectApk) = DistributionRelease(
        version = direct.versionName.trim().removePrefix("v"),
        versionCode = direct.versionCode,
        title = "Daily ${direct.versionName.trim().removePrefix("v")}",
        releaseUrl = config.releasePageUrl.trim().ifBlank { config.projectUrl.trim() },
        apkUrl = direct.url.trim(),
        apkSha256 = direct.sha256.trim().lowercase(),
        apkSize = direct.size,
        packageName = config.expectedPackageName.trim(),
        signingCertSha256 = config.expectedSigningCertSha256.trim().lowercase()
    )

    internal fun parseIndex(raw: String, config: DistributionConfigResponse): List<DistributionRelease> {
        if (raw.isBlank() || raw.toByteArray().size > MAX_INDEX_BYTES) return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        if (root.optInt("schemaVersion", 0) != 1) return emptyList()
        val items = root.optJSONArray("releases") ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(items.length(), MAX_RELEASES)) {
                val item = items.optJSONObject(index) ?: continue
                val version = clean(item.optString("version")).removePrefix("v")
                if (version.isBlank()) continue
                val prerelease = item.optBoolean("prerelease", !ReleaseHistoryParser.isStableVersion(version))
                if (prerelease && !config.allowPrerelease) continue
                val apkUrl = clean(item.optString("apkUrl")).ifBlank { null }
                if (apkUrl != null && !isHttpUrl(apkUrl)) continue
                val releaseUrl = clean(item.optString("releaseUrl")).ifBlank { config.releasePageUrl.trim() }
                val sha = clean(item.optString("apkSha256")).ifBlank { clean(item.optString("sha256")) }
                    .lowercase().ifBlank { null }
                val size = when {
                    item.has("apkSize") -> item.optLong("apkSize").takeIf { it > 0 }
                    else -> item.optLong("size").takeIf { it > 0 }
                }
                add(DistributionRelease(
                    version = version,
                    versionCode = item.optLong("versionCode").takeIf { it > 0 },
                    title = clean(item.optString("title")).ifBlank { "Daily $version" },
                    highlights = item.optJSONArray("highlights").stringList(),
                    details = item.optJSONArray("details").stringList(),
                    releasedAt = clean(item.optString("releasedAt")),
                    releaseUrl = releaseUrl,
                    apkUrl = apkUrl,
                    apkSha256 = sha,
                    apkSize = size,
                    packageName = clean(item.optString("packageName")).ifBlank { config.expectedPackageName.trim() },
                    signingCertSha256 = clean(item.optString("signingCertSha256")).ifBlank {
                        config.expectedSigningCertSha256.trim()
                    }.lowercase(),
                    legacyOfficialArtifact = isLegacyOfficialArtifact(apkUrl)
                ))
            }
        }.distinctBy { it.version }
            .sortedWith { left, right -> ReleaseHistoryParser.compareVersions(right.version, left.version) }
    }

    private fun get(url: String): String? {
        if (!isHttpUrl(url)) return null
        responseFetcher?.let { return runCatching { it(url) }.getOrNull() }
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        return runCatching {
            httpClient.newCall(request).execute().use body@{ response ->
                if (!response.isSuccessful) return@body null
                val length = response.body?.contentLength() ?: -1
                if (length > MAX_INDEX_BYTES) return@body null
                response.body?.charStream()?.use reader@{ reader ->
                    val buffer = CharArray(8192)
                    val out = StringBuilder()
                    var total = 0
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_INDEX_BYTES) return@reader null
                        out.append(buffer, 0, read)
                    }
                    out.toString()
                }
            }
        }.getOrNull()
    }

    private fun cacheKey(resolved: ResolvedDistributionConfig, forHistory: Boolean): String =
        DistributionConfigRepository.digest("${resolved.cacheDimension}|${if (forHistory) "history" else "index"}")

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(length(), 48)) {
                optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun clean(value: String): String = value.trim().takeUnless { it.equals("null", true) }.orEmpty()
    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value.trim())
        uri.host?.isNotBlank() == true && uri.scheme.lowercase() in setOf("https", "http") && uri.userInfo == null
    }.getOrDefault(false)

    private fun isLegacyOfficialArtifact(url: String?): Boolean = runCatching {
        java.net.URI(url.orEmpty()).host.equals("releases.daily.harzcloud.de", true)
    }.getOrDefault(false)

    private fun DistributionConfigSource.toHistorySource(): ChangelogHistorySource = when (this) {
        DistributionConfigSource.BACKEND -> ChangelogHistorySource.NETWORK
        DistributionConfigSource.LAST_KNOWN_GOOD -> ChangelogHistorySource.FRESH_CACHE
        DistributionConfigSource.OFFICIAL_BUILD_FALLBACK -> ChangelogHistorySource.FRESH_CACHE
    }

    companion object {
        private const val PREF_NAME = "distribution_releases_v1"
        private const val CACHE_PREFIX = "index:"
        private const val CACHE_AT_PREFIX = "cached_at:"
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val MAX_INDEX_BYTES = 1024 * 1024
        private const val MAX_RELEASES = 500
    }
}
