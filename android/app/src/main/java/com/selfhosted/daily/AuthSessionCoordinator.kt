package com.selfhosted.daily

import android.content.Context
import kotlinx.coroutines.sync.Mutex

data class AuthSessionSnapshot(
    val accessToken: String,
    val refreshToken: String,
    val sessionId: String,
    val userId: Long
) {
    fun authHeader(): String = "Bearer $accessToken"
    fun hasAccessToken(): Boolean = accessToken.isNotBlank()
    fun hasRefreshToken(): Boolean = refreshToken.isNotBlank()
}

class RefreshLockCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.run {
        lock()
        try {
            block()
        } finally {
            unlock()
        }
    }
}

object AuthSessionCoordinator {
    private const val PREF_NAME = "app"
    private const val KEY_TOKEN = "token"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_USER_ID = "distribution_user_id_v1"

    private val refreshCoordinator = RefreshLockCoordinator()

    fun snapshot(context: Context): AuthSessionSnapshot {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val access = prefs.getString(KEY_ACCESS_TOKEN, "")?.trim().orEmpty()
            .ifBlank { prefs.getString(KEY_TOKEN, "")?.trim().orEmpty() }
        return AuthSessionSnapshot(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "")?.trim().orEmpty(),
            sessionId = prefs.getString(KEY_SESSION_ID, "")?.trim().orEmpty(),
            userId = prefs.getLong(KEY_USER_ID, 0L)
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
                putLong(KEY_USER_ID, auth.user.id)
            }
            .commit()
        return snapshot(context)
    }

    fun persistUserId(context: Context, userId: Long) {
        if (userId <= 0 || !snapshot(context).hasAccessToken()) return
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_USER_ID, userId)
            .commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_SESSION_ID)
            .remove(KEY_USER_ID)
            .commit()
    }

    suspend fun <T> withRefreshLock(block: suspend () -> T): T = refreshCoordinator.withLock(block)
}
