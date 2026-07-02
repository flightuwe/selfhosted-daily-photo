package com.selfhosted.daily

import android.content.Context
import kotlinx.coroutines.sync.Mutex

data class AuthSessionSnapshot(
    val accessToken: String,
    val refreshToken: String,
    val sessionId: String
) {
    fun authHeader(): String = "Bearer $accessToken"
    fun hasAccessToken(): Boolean = accessToken.isNotBlank()
    fun hasRefreshToken(): Boolean = refreshToken.isNotBlank()
}

object AuthSessionCoordinator {
    private const val PREF_NAME = "app"
    private const val KEY_TOKEN = "token"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_SESSION_ID = "session_id"

    private val refreshMutex = Mutex()

    fun snapshot(context: Context): AuthSessionSnapshot {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val access = prefs.getString(KEY_ACCESS_TOKEN, "")?.trim().orEmpty()
            .ifBlank { prefs.getString(KEY_TOKEN, "")?.trim().orEmpty() }
        return AuthSessionSnapshot(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "")?.trim().orEmpty(),
            sessionId = prefs.getString(KEY_SESSION_ID, "")?.trim().orEmpty()
        )
    }

    fun persist(context: Context, auth: AuthResponse): AuthSessionSnapshot {
        val access = auth.accessToken.trim().ifBlank { auth.token.trim() }
        val refresh = auth.refreshToken.trim()
        val sessionId = auth.sessionId.trim()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, access)
            .putString(KEY_ACCESS_TOKEN, access)
            .apply {
                if (refresh.isNotBlank()) putString(KEY_REFRESH_TOKEN, refresh) else remove(KEY_REFRESH_TOKEN)
                if (sessionId.isNotBlank()) putString(KEY_SESSION_ID, sessionId) else remove(KEY_SESSION_ID)
            }
            .commit()
        return snapshot(context)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_SESSION_ID)
            .commit()
    }

    suspend fun <T> withRefreshLock(block: suspend () -> T): T = refreshMutex.run {
        lock()
        try {
            block()
        } finally {
            unlock()
        }
    }
}
