package com.selfhosted.daily

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

data class DistributionReleaseResult(
    val releases: List<DistributionRelease>,
    val source: ChangelogHistorySource,
    val cachedAt: Long = 0L
)

class DistributionReleaseSource(
    context: Context,
    private val httpClient: OkHttpClient,
    private val responseFetcher: ((String) -> String?)? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    addressResolver: (String) -> Array<InetAddress> = InetAddress::getAllByName
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val urlPolicy = DistributionUrlPolicy(addressResolver)

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
        val cacheAge = nowMillis() - cachedAt
        val fresh = cached.isNotEmpty() && cacheAge in 0..CACHE_TTL_MS
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
        signingCertSha256 = config.expectedSigningCertSha256.trim().lowercase(),
        profilePackageName = config.expectedPackageName.trim(),
        profileSigningCertSha256 = config.expectedSigningCertSha256.trim().lowercase(),
        apkUrlExplicitlyConfigured = true,
        installable = true,
        isLatest = true
    )

    internal fun parseIndex(raw: String, config: DistributionConfigResponse): List<DistributionRelease> {
        if (raw.isBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_INDEX_BYTES) return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        if (root.optInt("schemaVersion", 0) != 1) return emptyList()
        val latestVersion = clean(root.optString("latest")).removePrefix("v")
        if (latestVersion.isBlank()) return emptyList()
        val rootChannel = clean(root.optString("channel")).lowercase().ifBlank { DEFAULT_CHANNEL }
        val configuredChannel = config.channel.trim().lowercase().ifBlank { DEFAULT_CHANNEL }
        val items = root.optJSONArray("releases") ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(items.length(), MAX_RELEASES)) {
                val item = items.optJSONObject(index) ?: continue
                val version = clean(item.optString("version")).removePrefix("v")
                if (version.isBlank()) continue
                val itemChannel = clean(item.optString("channel")).lowercase().ifBlank { rootChannel }
                if (itemChannel != configuredChannel) continue
                val prerelease = item.optBoolean("prerelease", !ReleaseHistoryParser.isStableVersion(version))
                if (prerelease && !config.allowPrerelease) continue
                val apkUrl = clean(item.optString("apkUrl")).ifBlank { null }?.takeIf(::isSafeManifestUrl)
                val releaseUrl = clean(item.optString("releaseUrl")).takeIf(::isHttpUrl)
                    ?: config.releasePageUrl.trim()
                val sha = clean(item.optString("apkSha256")).ifBlank { clean(item.optString("sha256")) }
                    .lowercase().ifBlank { null }
                val versionCode = item.optLong("versionCode").takeIf { it > 0 }
                val itemPackageName = clean(item.optString("packageName"))
                val itemSigningCert = clean(item.optString("signingCertSha256")).lowercase()
                val size = when {
                    item.has("apkSize") -> item.optLong("apkSize").takeIf { it > 0 }
                    else -> item.optLong("size").takeIf { it > 0 }
                }
                val completeIntegrityMetadata = apkUrl != null && versionCode != null &&
                    sha?.matches(SHA256) == true && itemPackageName.isNotBlank() && itemSigningCert.matches(SHA256)
                val legacyOfficial = isLegacyOfficialArtifact(apkUrl) && !completeIntegrityMetadata
                val configuredSigner = config.expectedSigningCertSha256.trim().lowercase()
                val profileIdentityPresent = config.expectedPackageName.isNotBlank() &&
                    (configuredSigner.isBlank() || configuredSigner.matches(SHA256))
                add(DistributionRelease(
                    version = version,
                    versionCode = versionCode,
                    title = clean(item.optString("title")).ifBlank { "Daily $version" },
                    highlights = item.optJSONArray("highlights").stringList(),
                    details = item.optJSONArray("details").stringList(),
                    releasedAt = clean(item.optString("releasedAt")),
                    releaseUrl = releaseUrl,
                    apkUrl = apkUrl,
                    apkSha256 = sha,
                    apkSize = size,
                    packageName = itemPackageName.ifBlank { config.expectedPackageName.trim() },
                    signingCertSha256 = itemSigningCert,
                    profilePackageName = config.expectedPackageName.trim(),
                    profileSigningCertSha256 = config.expectedSigningCertSha256.trim().lowercase(),
                    apkUrlExplicitlyConfigured = false,
                    legacyOfficialArtifact = legacyOfficial,
                    installable = profileIdentityPresent && (completeIntegrityMetadata || (legacyOfficial && apkUrl != null)),
                    isLatest = version == latestVersion
                ))
            }
        }.distinctBy { it.versionCode?.let { code -> "code:$code" } ?: "version:${it.version}" }
            .sortedWith { left, right ->
                val codeOrder = (right.versionCode ?: Long.MIN_VALUE).compareTo(left.versionCode ?: Long.MIN_VALUE)
                if (codeOrder != 0) codeOrder else ReleaseHistoryParser.compareVersions(right.version, left.version)
            }
    }

    private fun get(url: String): String? {
        responseFetcher?.let { return runCatching { it(url) }.getOrNull() }
        return try {
            val configuredOrigin = urlPolicy.configured(url)
            var current = configuredOrigin
            var redirects = 0
            while (true) {
                val request = Request.Builder().url(current.url).header("Accept", "application/json").build()
                val response = buildPinnedDistributionClient(httpClient, current).newCall(request).execute()
                if (response.code in REDIRECT_CODES) {
                    val next = response.use {
                        urlPolicy.redirect(configuredOrigin.url, current.url, it.header("Location"), redirects)
                    }
                    current = next
                    redirects += 1
                    continue
                }
                return response.use body@{ finalResponse ->
                    if (!finalResponse.isSuccessful) return@body null
                    val body = finalResponse.body ?: return@body null
                    val length = body.contentLength()
                    if (length > MAX_INDEX_BYTES) return@body null
                    body.byteStream().use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_INDEX_BYTES) return@body null
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray().toString(Charsets.UTF_8)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (_: Throwable) {
            null
        }
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

    private fun isSafeManifestUrl(value: String): Boolean = runCatching {
        urlPolicy.manifestSyntax(value)
        true
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
        private const val DEFAULT_CHANNEL = "stable"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}
