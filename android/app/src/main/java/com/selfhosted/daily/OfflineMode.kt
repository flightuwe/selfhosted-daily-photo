package com.selfhosted.daily

import android.content.Context
import androidx.work.WorkManager
import java.io.IOException

/** A persisted, process-wide network kill switch for Daily-owned traffic. */
object OfflineModeManager {
    private const val PREFS = "app"
    private const val KEY_ENABLED = "offline_mode_enabled_v1"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).commit()
        if (enabled) {
            UploadQueueScheduler.cancelForOffline(appContext)
            UpdateCheckScheduler.cancelForOffline(appContext)
        } else {
            UploadQueueScheduler.sync(appContext)
            UpdateCheckScheduler.syncFromPrefs(appContext)
        }
    }

    fun requireOnline(context: Context) {
        if (isEnabled(context)) throw OfflineModeException()
    }
}

class OfflineModeException : IOException("Daily offline mode is enabled")
