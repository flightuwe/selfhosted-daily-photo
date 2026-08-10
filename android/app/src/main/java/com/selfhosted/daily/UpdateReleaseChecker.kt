package com.selfhosted.daily

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateReleaseChecker {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private const val RELEASE_INDEX_URL = "https://releases.daily.harzcloud.de/index.json"
    private const val FORGEJO_RELEASES_LATEST_URL = "https://code.harzcloud.de/api/v1/repos/daily-harzcloud/daily/releases/latest"
    private const val FORGEJO_RELEASES_TAG_URL_PREFIX = "https://code.harzcloud.de/api/v1/repos/daily-harzcloud/daily/releases/tags/"
    private const val STATIC_RELEASES_BASE_URL = "https://releases.daily.harzcloud.de/apk/"

    suspend fun checkForUpdate(currentVersion: String, allowNetwork: Boolean): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!allowNetwork) return@withContext null
        val release = fetchIndexedRelease() ?: fetchForgeRelease(FORGEJO_RELEASES_LATEST_URL) ?: return@withContext null
        if (isVersionNewer(release.version, currentVersion)) {
            UpdateInfo(release.version, release.releaseUrl, release.apkUrl)
        } else {
            null
        }
    }

    suspend fun changelogLinesForVersion(version: String, allowNetwork: Boolean): List<String> = withContext(Dispatchers.IO) {
        if (!allowNetwork) return@withContext emptyList()
        val normalized = version.trim().removePrefix("v")
        if (normalized.isBlank()) return@withContext emptyList()
        val tag = "v$normalized"

        val indexed = fetchIndexedRelease(normalized)
        if (indexed?.notes?.isNotEmpty() == true) return@withContext indexed.notes

        val fromAsset = fetchChangelogAsset(tag)
        if (fromAsset.isNotEmpty()) return@withContext fromAsset

        val byTag = fetchForgeRelease("${FORGEJO_RELEASES_TAG_URL_PREFIX}$tag")
        if (byTag?.notes?.isNotEmpty() == true) return@withContext byTag.notes

        val latest = fetchIndexedRelease() ?: fetchForgeRelease(FORGEJO_RELEASES_LATEST_URL)
        if (latest?.notes?.isNotEmpty() == true) return@withContext latest.notes
        listOf("Release-Infos konnten nicht von Harzcloud geladen werden.")
    }

    private fun fetchIndexedRelease(version: String? = null): DailyRelease? {
        val req = Request.Builder()
            .url(RELEASE_INDEX_URL)
            .header("Accept", "application/json")
            .build()

        return runCatching {
            http.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val root = JSONObject(body)
                val releases = root.optJSONArray("releases") ?: return@use null
                val requested = version?.trim()?.removePrefix("v").orEmpty()
                val latest = clean(root.optString("latest")).removePrefix("v")
                val selected = if (requested.isNotBlank()) requested else latest
                if (selected.isBlank()) return@use null
                for (index in 0 until releases.length()) {
                    val item = releases.optJSONObject(index) ?: continue
                    val itemVersion = clean(item.optString("version")).removePrefix("v")
                    if (itemVersion != selected) continue
                    val tag = "v$itemVersion"
                    val releaseUrl = clean(item.optString("releaseUrl")).ifBlank {
                        "https://code.harzcloud.de/daily-harzcloud/daily/releases/tag/$tag"
                    }
                    val apkUrl = clean(item.optString("apkUrl")).ifBlank {
                        "$STATIC_RELEASES_BASE_URL$tag/app-release.apk"
                    }
                    val notes = item.optJSONArray("highlights").toStringList(limit = 24) +
                        item.optJSONArray("details").toStringList(limit = 24)
                    return@use DailyRelease(
                        version = itemVersion,
                        releaseUrl = releaseUrl,
                        apkUrl = apkUrl,
                        notes = notes.distinct().take(24),
                        publishedAt = clean(item.optString("releasedAt"))
                    )
                }
                null
            }
        }.getOrNull()
    }

    private fun fetchForgeRelease(url: String): DailyRelease? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val tag = clean(json.optString("tag_name")).removePrefix("v").trim()
            if (tag.isBlank()) return null

            val releaseUrl = clean(json.optString("html_url"))
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val item = assets.getJSONObject(i)
                    if (clean(item.optString("name")).endsWith(".apk")) {
                        apkUrl = clean(item.optString("browser_download_url")).ifBlank { null }
                        break
                    }
                }
            }
            val notes = extractNotes(clean(json.optString("body")))
            val publishedAt = clean(json.optString("published_at"))
            return DailyRelease(tag, releaseUrl, apkUrl, notes, publishedAt)
        }
    }

    private fun fetchChangelogAsset(tag: String): List<String> {
        val req = Request.Builder()
            .url("$STATIC_RELEASES_BASE_URL$tag/changelog.json")
            .header("Accept", "application/json")
            .build()

        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            val highlights = json.optJSONArray("highlights").toStringList(limit = 24)
            val details = json.optJSONArray("details").toStringList(limit = 24)
            return (highlights + details)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(24)
        }
    }

    private fun JSONArray?.toStringList(limit: Int): List<String> {
        if (this == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until minOf(length(), limit)) {
            val value = optString(i).trim()
            if (value.isNotBlank()) out += value
        }
        return out
    }

    private fun extractNotes(markdown: String): List<String> {
        if (markdown.isBlank()) return emptyList()
        return markdown
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                line
                    .removePrefix("- ")
                    .removePrefix("* ")
                    .removePrefix("+ ")
                    .removePrefix("## ")
                    .removePrefix("### ")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .take(24)
            .toList()
    }

    private fun clean(value: String): String {
        val v = value.trim()
        return if (v.equals("null", ignoreCase = true)) "" else v
    }

}

private data class DailyRelease(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val notes: List<String>,
    val publishedAt: String
)
