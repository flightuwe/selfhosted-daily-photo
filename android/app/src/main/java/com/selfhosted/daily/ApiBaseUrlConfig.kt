package com.selfhosted.daily

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private const val PREF_NAME = "app"
private const val PREF_KEY_SERVER_BASE_URL_OVERRIDE = "server_base_url_override"
private const val PREF_KEY_ALLOW_INSECURE_HTTP = "allow_insecure_http_server_override"

data class ApiBaseUrlValidationResult(
    val normalizedBaseUrl: String?,
    val errorMessage: String?
)

fun resolveApiBaseUrl(context: Context): String {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val overrideRaw = prefs.getString(PREF_KEY_SERVER_BASE_URL_OVERRIDE, "").orEmpty().trim()
    val normalizedOverride = normalizeApiBaseUrl(overrideRaw)
    return normalizedOverride ?: normalizeApiBaseUrl(BuildConfig.API_BASE_URL).orEmpty()
}

fun currentApiBaseUrlOverride(context: Context): String {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    return prefs.getString(PREF_KEY_SERVER_BASE_URL_OVERRIDE, "").orEmpty().trim()
}

fun isApiBaseUrlOverrideActive(context: Context): Boolean = currentApiBaseUrlOverride(context).isNotBlank()

fun setApiBaseUrlOverride(context: Context, normalizedBaseUrlOrBlank: String) {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val normalized = normalizeApiBaseUrl(normalizedBaseUrlOrBlank).orEmpty()
    if (normalized.isBlank()) {
        prefs.edit().remove(PREF_KEY_SERVER_BASE_URL_OVERRIDE).apply()
    } else {
        prefs.edit().putString(PREF_KEY_SERVER_BASE_URL_OVERRIDE, normalized).apply()
    }
}

fun allowInsecureHttpOverride(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    return prefs.getBoolean(PREF_KEY_ALLOW_INSECURE_HTTP, false)
}

fun setAllowInsecureHttpOverride(context: Context, enabled: Boolean) {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    prefs.edit().putBoolean(PREF_KEY_ALLOW_INSECURE_HTTP, enabled).apply()
}

fun validateApiBaseUrlInput(
    rawInput: String,
    allowInsecureHttp: Boolean
): ApiBaseUrlValidationResult {
    val trimmed = rawInput.trim()
    if (trimmed.isBlank()) return ApiBaseUrlValidationResult(null, null)
    val normalized = normalizeApiBaseUrl(trimmed)
        ?: return ApiBaseUrlValidationResult(null, "Ungueltige URL. Beispiel: https://example.com")
    val lower = normalized.lowercase()
    if (lower.startsWith("http://") && !allowInsecureHttp) {
        return ApiBaseUrlValidationResult(
            null,
            "HTTP ist deaktiviert. Aktiviere erst den lokalen HTTP-Schalter."
        )
    }
    if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
        return ApiBaseUrlValidationResult(null, "Nur http:// oder https:// sind erlaubt.")
    }
    return ApiBaseUrlValidationResult(normalized, null)
}

fun normalizeApiBaseUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
        trimmed
    } else {
        "https://$trimmed"
    }
    return runCatching {
        val uri = java.net.URI(withScheme)
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.trim().orEmpty()
        if (host.isBlank()) return null
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        val path = (uri.path ?: "").trim()
        val normalizedPath = when {
            path.isBlank() || path == "/" -> "/api/"
            path.equals("/api", ignoreCase = true) -> "/api/"
            path.equals("/api/", ignoreCase = true) -> "/api/"
            else -> "${path.trimEnd('/')}/"
        }
        "$scheme://$host$portPart$normalizedPath"
    }.getOrNull()
}

fun buildApiService(baseUrl: String, httpClient: OkHttpClient): Api {
    return Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(Api::class.java)
}
