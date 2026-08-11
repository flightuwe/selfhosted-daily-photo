package com.selfhosted.daily

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChangelogEntry(
    val version: String,
    val title: String,
    val highlights: List<String>,
    val details: List<String> = emptyList(),
    val releasedAt: String = "",
    val releaseUrl: String = ""
)

enum class ChangelogHistorySource {
    NETWORK,
    FRESH_CACHE,
    OFFLINE_CACHE,
    STALE_CACHE,
    UNAVAILABLE
}

data class ChangelogHistoryResult(
    val entries: List<ChangelogEntry>,
    val source: ChangelogHistorySource,
    val cachedAt: Long = 0L
)

/**
 * Small provider-neutral cache retained for isolated callers and tests. Production release loading
 * uses [DistributionReleaseSource], whose key includes the complete distribution dimension.
 */
class ReleaseHistoryRepository(
    context: Context,
    private val releaseFetcher: (() -> List<ChangelogEntry>)? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val cacheDimension: () -> String = { "unscoped" }
) {
    private val prefs = context.applicationContext.getSharedPreferences("release_history_v2", Context.MODE_PRIVATE)
    private val gson = Gson()

    suspend fun history(
        allowNetwork: Boolean,
        forceRefresh: Boolean = false,
        requiredVersion: String? = null
    ): ChangelogHistoryResult = withContext(Dispatchers.IO) {
        val key = DistributionConfigRepository.digest(cacheDimension())
        val cached = cachedHistory(key)
        val cachedAt = prefs.getLong("cached_at:$key", 0L)
        if (!allowNetwork) return@withContext cached.takeIf(List<ChangelogEntry>::isNotEmpty)
            ?.let { ChangelogHistoryResult(it, ChangelogHistorySource.OFFLINE_CACHE, cachedAt) }
            ?: ChangelogHistoryResult(emptyList(), ChangelogHistorySource.UNAVAILABLE)

        val required = requiredVersion?.trim()?.removePrefix("v").orEmpty()
        val fresh = cached.isNotEmpty() && nowMillis() - cachedAt < CACHE_TTL_MS
        val containsRequired = required.isBlank() || cached.any { it.version == required }
        if (!forceRefresh && fresh && containsRequired) {
            return@withContext ChangelogHistoryResult(cached, ChangelogHistorySource.FRESH_CACHE, cachedAt)
        }

        val fetched = releaseFetcher?.invoke().orEmpty().normalized()
        if (fetched.isNotEmpty()) {
            val now = nowMillis()
            prefs.edit().putString("entries:$key", gson.toJson(fetched)).putLong("cached_at:$key", now).apply()
            ChangelogHistoryResult(fetched, ChangelogHistorySource.NETWORK, now)
        } else if (cached.isNotEmpty()) {
            ChangelogHistoryResult(cached, ChangelogHistorySource.STALE_CACHE, cachedAt)
        } else ChangelogHistoryResult(emptyList(), ChangelogHistorySource.UNAVAILABLE)
    }

    private fun cachedHistory(key: String): List<ChangelogEntry> {
        val raw = prefs.getString("entries:$key", "").orEmpty()
        val type = object : TypeToken<List<ChangelogEntry>>() {}.type
        return runCatching { gson.fromJson<List<ChangelogEntry>>(raw, type).orEmpty().normalized() }.getOrDefault(emptyList())
    }

    private fun List<ChangelogEntry>.normalized(): List<ChangelogEntry> =
        filter { ReleaseHistoryParser.isStableVersion(it.version) }
            .distinctBy { it.version }
            .sortedWith { left, right -> ReleaseHistoryParser.compareVersions(right.version, left.version) }

    private companion object {
        const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    }
}

data class ParsedReleaseBody(val title: String, val highlights: List<String>, val details: List<String>)

object ReleaseHistoryParser {
    fun isStableVersion(version: String): Boolean = Regex("^\\d+\\.\\d+\\.\\d+$").matches(version.trim())

    fun compareVersions(left: String, right: String): Int {
        val a = left.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0..2) {
            val comparison = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    fun parseReleaseBody(markdown: String): ParsedReleaseBody {
        var title = ""
        var inDetails = false
        val highlights = mutableListOf<String>()
        val details = mutableListOf<String>()
        markdown.lineSequence().map(String::trim).forEach { line ->
            when {
                line.startsWith("#") -> {
                    val heading = line.trimStart('#').trim()
                    if (title.isBlank() && heading.isNotBlank() && !heading.equals("Highlights", true)) title = heading
                    inDetails = heading.equals("Details", true) || heading.equals("Weitere Details", true)
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    val value = line.drop(2).trim()
                    if (value.isBlank() || value.startsWith("Android APK:") || value.startsWith("Changelog JSON:")) return@forEach
                    if (inDetails) details += value else highlights += value
                }
            }
        }
        return ParsedReleaseBody(title, highlights.distinct(), details.distinct())
    }
}
