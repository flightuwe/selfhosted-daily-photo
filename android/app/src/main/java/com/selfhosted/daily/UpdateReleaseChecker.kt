package com.selfhosted.daily

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object UpdateReleaseChecker {
    private val http = OkHttpClient()
    private const val RELEASES_LATEST_URL = "https://api.github.com/repos/flightuwe/selfhosted-daily-photo/releases/latest"
    private const val RELEASES_TAG_URL_PREFIX = "https://api.github.com/repos/flightuwe/selfhosted-daily-photo/releases/tags/"
    private const val ACTION_RUNS_URL = "https://api.github.com/repos/flightuwe/selfhosted-daily-photo/actions/runs?per_page=50"

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
        val fromActions = fetchActionTitlesSince(byTag?.publishedAt, limit = 12)
        if (fromActions.isNotEmpty()) return@withContext fromActions

        val latest = fetchRelease(RELEASES_LATEST_URL)
        if (latest?.notes?.isNotEmpty() == true) return@withContext latest.notes
        listOf("Keine Action-Historie verfuegbar.")
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
            return GitHubRelease(tag, releaseUrl, apkUrl, notes, publishedAt)
        }
    }

    private fun fetchActionTitlesSince(sinceIso: String?, limit: Int): List<String> {
        val req = Request.Builder()
            .url(ACTION_RUNS_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val runs = json.optJSONArray("workflow_runs") ?: JSONArray()
            val since = parseInstantOrNull(sinceIso)

            val out = mutableListOf<String>()
            for (i in 0 until runs.length()) {
                val run = runs.getJSONObject(i)
                val createdAt = parseInstantOrNull(clean(run.optString("created_at")))
                if (since != null && createdAt != null && createdAt.isBefore(since)) continue

                val workflowName = clean(run.optString("name"))
                val displayTitle = clean(run.optString("display_title"))
                val title = when {
                    displayTitle.isNotBlank() && workflowName.isNotBlank() -> "$workflowName: $displayTitle"
                    displayTitle.isNotBlank() -> displayTitle
                    workflowName.isNotBlank() -> workflowName
                    else -> ""
                }
                if (title.isBlank()) continue
                out += title
                if (out.size >= limit) break
            }
            return out
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

    private fun clean(value: String): String {
        val v = value.trim()
        return if (v.equals("null", ignoreCase = true)) "" else v
    }

    private fun parseInstantOrNull(value: String?): Instant? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }
}

private data class GitHubRelease(
    val version: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val notes: List<String>,
    val publishedAt: String
)
