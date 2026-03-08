package com.selfhosted.daily

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object UpdateReleaseChecker {
    private val http = OkHttpClient()
    private const val RELEASES_LATEST_URL = "https://api.github.com/repos/flightuwe/selfhosted-daily-photo/releases/latest"
    private const val RELEASES_TAG_URL_PREFIX = "https://api.github.com/repos/flightuwe/selfhosted-daily-photo/releases/tags/"

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val release = fetchRelease(RELEASES_LATEST_URL) ?: return@withContext null
        if (isVersionNewer(release.version, currentVersion)) {
            UpdateInfo(release.version, release.releaseUrl, release.apkUrl)
        } else {
            null
        }
    }

    suspend fun changelogLinesForVersion(version: String): List<String> = withContext(Dispatchers.IO) {
        val normalized = version.trim().removePrefix("v")
        if (normalized.isBlank()) return@withContext emptyList()

        val byTag = fetchRelease("${RELEASES_TAG_URL_PREFIX}v$normalized")
        if (byTag?.notes?.isNotEmpty() == true) return@withContext byTag.notes

        val latest = fetchRelease(RELEASES_LATEST_URL)
        latest?.notes.orEmpty()
    }

    private fun fetchRelease(url: String): GitHubRelease? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v").trim()
            if (tag.isBlank()) return null

            val releaseUrl = json.optString("html_url")
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val item = assets.getJSONObject(i)
                    if (item.optString("name").endsWith(".apk")) {
                        apkUrl = item.optString("browser_download_url")
                        break
                    }
                }
            }
            val notes = extractNotes(json.optString("body"))
            return GitHubRelease(tag, releaseUrl, apkUrl, notes)
        }
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
}

private data class GitHubRelease(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val notes: List<String>
)
