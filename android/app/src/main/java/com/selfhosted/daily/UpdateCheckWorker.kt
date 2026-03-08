package com.selfhosted.daily

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_AUTO_UPDATE_ENABLED, false)
        if (!enabled) return Result.success()

        runCatching {
            val update = UpdateReleaseChecker.checkForUpdate(BuildConfig.VERSION_NAME)
            if (update != null) {
                val lastNotified = prefs.getString(PREF_LAST_NOTIFIED_VERSION, "") ?: ""
                if (lastNotified != update.latestVersion) {
                    PushMessagingService.showLocalUpdateNotification(applicationContext, update)
                    prefs.edit().putString(PREF_LAST_NOTIFIED_VERSION, update.latestVersion).apply()
                }
            }
        }

        UpdateCheckScheduler.scheduleNext(applicationContext, delayMinutes = 10)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "daily_auto_update_check"
        const val PREF_AUTO_UPDATE_ENABLED = "auto_update_enabled"
        const val PREF_LAST_NOTIFIED_VERSION = "last_notified_update_version"
    }
}

object UpdateCheckScheduler {
    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(UpdateCheckWorker.PREF_AUTO_UPDATE_ENABLED, enabled).apply()
        if (enabled) {
            enqueueNow(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(UpdateCheckWorker.WORK_NAME)
        }
    }

    fun syncFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(UpdateCheckWorker.PREF_AUTO_UPDATE_ENABLED, false)
        if (enabled) {
            scheduleNext(context, delayMinutes = 10, keepExisting = true)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(UpdateCheckWorker.WORK_NAME)
        }
    }

    fun enqueueNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<UpdateCheckWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UpdateCheckWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    fun scheduleNext(context: Context, delayMinutes: Long, keepExisting: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UpdateCheckWorker.WORK_NAME,
            if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
            req
        )
    }
}
