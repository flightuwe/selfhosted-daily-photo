package com.selfhosted.daily

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChangelogEntry(
    val version: String,
    val title: String,
    val highlights: List<String>,
    val details: List<String> = emptyList(),
    val releasedAt: String = "",
    val releaseUrl: String = ""
)

/** GitHub remains the canonical source; the cache only makes it reliable offline. */
class ReleaseHistoryRepository(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun history(forceRefresh: Boolean = false): List<ChangelogEntry> = withContext(Dispatchers.IO) {
        val cached = cachedHistory()
        val cachedAt = prefs.getLong(CACHE_AT_KEY, 0L)
        val cacheFresh = cached.isNotEmpty() && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS
        if (!forceRefresh && cacheFresh) return@withContext cached

        val fresh = fetchAllReleases()
        if (fresh.isNotEmpty()) {
            saveHistory(fresh)
            fresh
        } else {
            cached
        }
    }

    private fun fetchAllReleases(): List<ChangelogEntry> {
        val entries = mutableListOf<ChangelogEntry>()
        var page = 1
        while (page <= MAX_PAGES) {
            val body = get("$RELEASES_URL?per_page=100&page=$page") ?: break
            val releases = runCatching { JSONArray(body) }.getOrNull() ?: break
            if (releases.length() == 0) break
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
                val version = release.optString("tag_name").trim().removePrefix("v")
                if (!ReleaseHistoryParser.isStableVersion(version)) continue
                val bodyText = release.optString("body").trim()
                val parsed = ReleaseHistoryParser.parseReleaseBody(bodyText)
                entries += ChangelogEntry(
                    version = version,
                    title = parsed.title.ifBlank { "Daily $version" },
                    highlights = parsed.highlights.ifEmpty { listOf("Keine Release-Details hinterlegt.") },
                    details = parsed.details,
                    releasedAt = release.optString("published_at").trim(),
                    releaseUrl = release.optString("html_url").trim()
                )
            }
            if (releases.length() < 100) break
            page += 1
        }
        return entries
            .distinctBy { it.version }
            .sortedWith { left, right -> ReleaseHistoryParser.compareVersions(right.version, left.version) }
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull()
    }

    private fun cachedHistory(): List<ChangelogEntry> {
        val raw = prefs.getString(CACHE_KEY, "").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val version = item.optString("version").trim()
                if (!ReleaseHistoryParser.isStableVersion(version)) continue
                add(ChangelogEntry(
                    version = version,
                    title = item.optString("title").trim().ifBlank { "Daily $version" },
                    highlights = item.optJSONArray("highlights").stringList(),
                    details = item.optJSONArray("details").stringList(),
                    releasedAt = item.optString("releasedAt").trim(),
                    releaseUrl = item.optString("releaseUrl").trim()
                ))
            }
        }.sortedWith { left, right -> ReleaseHistoryParser.compareVersions(right.version, left.version) }
    }

    private fun saveHistory(entries: List<ChangelogEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("version", entry.version)
                put("title", entry.title)
                put("highlights", JSONArray(entry.highlights))
                put("details", JSONArray(entry.details))
                put("releasedAt", entry.releasedAt)
                put("releaseUrl", entry.releaseUrl)
            })
        }
        prefs.edit().putString(CACHE_KEY, array.toString()).putLong(CACHE_AT_KEY, System.currentTimeMillis()).apply()
    }

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private companion object {
        const val RELEASES_URL = "https://api.github.com/repos/flightuwe/selfhosted-daily-photo/releases"
        const val CACHE_KEY = "github_release_history_v1"
        const val CACHE_AT_KEY = "github_release_history_cached_at_v1"
        const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        const val MAX_PAGES = 5
    }
}

data class ParsedReleaseBody(val title: String, val highlights: List<String>, val details: List<String>)

object ReleaseHistoryParser {
    fun isStableVersion(version: String): Boolean = Regex("^\\d+\\.\\d+\\.\\d+$").matches(version.trim())

    fun compareVersions(left: String, right: String): Int {
        val a = left.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0..2) {
            val comparison = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
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
