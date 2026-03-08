package com.selfhosted.daily

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object UpdateReleaseChecker {
    private val http = OkHttpClient()

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/flightuwe/selfhosted-daily-photo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()

        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val tag = json.optString("tag_name").removePrefix("v")
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

            if (isVersionNewer(tag, currentVersion)) UpdateInfo(tag, releaseUrl, apkUrl) else null
        }
    }
}
