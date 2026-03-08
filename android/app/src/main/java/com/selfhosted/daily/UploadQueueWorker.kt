package com.selfhosted.daily

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

data class QueuedUploadItem(
    val id: String,
    val backPath: String,
    val frontPath: String,
    val isPrompt: Boolean,
    val authToken: String,
    val status: String,
    val attempts: Int,
    val lastError: String,
    val progressPercent: Int,
    val nextRetryAtMs: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

object UploadQueueStatus {
    const val WAITING = "waiting"
    const val RUNNING = "running"
    const val FAILED = "failed"
    const val SUCCESS = "success"
}

object UploadQueueManager {
    private const val PREF_NAME = "app"
    private const val PREF_KEY_ITEMS = "upload_queue_items"
    private const val MAX_ATTEMPTS = 8

    @Synchronized
    fun list(context: Context): List<QueuedUploadItem> = read(context)
        .sortedByDescending { it.createdAtMs }

    @Synchronized
    fun clear(context: Context) {
        val items = read(context)
        items.forEach { deleteFilesForItem(it) }
        write(context, emptyList())
    }

    @Synchronized
    fun enqueueFromFiles(
        context: Context,
        backPath: String,
        frontPath: String,
        isPrompt: Boolean,
        authToken: String
    ): QueuedUploadItem {
        val now = System.currentTimeMillis()
        val item = QueuedUploadItem(
            id = UUID.randomUUID().toString(),
            backPath = backPath,
            frontPath = frontPath,
            isPrompt = isPrompt,
            authToken = authToken,
            status = UploadQueueStatus.WAITING,
            attempts = 0,
            lastError = "",
            progressPercent = 0,
            nextRetryAtMs = 0L,
            createdAtMs = now,
            updatedAtMs = now
        )
        val all = read(context).toMutableList()
        all.add(item)
        write(context, prune(all))
        UploadQueueScheduler.enqueueNow(context)
        return item
    }

    @Synchronized
    fun markWaiting(context: Context, id: String): Boolean {
        val now = System.currentTimeMillis()
        val all = read(context).toMutableList()
        var found = false
        val next = all.map {
            if (it.id == id) {
                found = true
                it.copy(
                    status = UploadQueueStatus.WAITING,
                    lastError = "",
                    progressPercent = 0,
                    nextRetryAtMs = 0L,
                    updatedAtMs = now
                )
            } else it
        }
        if (found) {
            write(context, prune(next))
            UploadQueueScheduler.enqueueNow(context)
        }
        return found
    }

    @Synchronized
    fun nextRunnable(context: Context, nowMs: Long = System.currentTimeMillis()): QueuedUploadItem? {
        return read(context).firstOrNull {
            (it.status == UploadQueueStatus.WAITING || it.status == UploadQueueStatus.FAILED) &&
                it.attempts < MAX_ATTEMPTS &&
                (it.nextRetryAtMs <= 0L || it.nextRetryAtMs <= nowMs)
        }
    }

    @Synchronized
    fun markRunning(context: Context, id: String) {
        val now = System.currentTimeMillis()
        val next = read(context).map {
            if (it.id == id) it.copy(status = UploadQueueStatus.RUNNING, progressPercent = 1, updatedAtMs = now) else it
        }
        write(context, prune(next))
    }

    @Synchronized
    fun markProgress(context: Context, id: String, percent: Int) {
        val now = System.currentTimeMillis()
        val clamped = percent.coerceIn(0, 100)
        val next = read(context).map {
            if (it.id == id && it.status == UploadQueueStatus.RUNNING) {
                if (clamped >= it.progressPercent) it.copy(progressPercent = clamped, updatedAtMs = now) else it
            } else it
        }
        write(context, prune(next))
    }

    @Synchronized
    fun markSuccess(context: Context, id: String) {
        val now = System.currentTimeMillis()
        val all = read(context).toMutableList()
        val next = all.map {
            if (it.id == id) {
                deleteFilesForItem(it)
                it.copy(
                    status = UploadQueueStatus.SUCCESS,
                    lastError = "",
                    progressPercent = 100,
                    nextRetryAtMs = 0L,
                    updatedAtMs = now
                )
            } else it
        }
        write(context, prune(next))
    }

    @Synchronized
    fun markFailed(context: Context, id: String, error: String) {
        val now = System.currentTimeMillis()
        val next = read(context).map {
            if (it.id == id) {
                val nextAttempts = it.attempts + 1
                val backoffSec = (30L * (1L shl (nextAttempts - 1).coerceAtMost(6))).coerceAtMost(6 * 60 * 60L)
                it.copy(
                    status = UploadQueueStatus.FAILED,
                    attempts = nextAttempts,
                    lastError = error.take(300),
                    progressPercent = 0,
                    nextRetryAtMs = now + backoffSec * 1000L,
                    updatedAtMs = now
                )
            } else it
        }
        write(context, prune(next))
    }

    @Synchronized
    fun hasPending(context: Context): Boolean {
        val now = System.currentTimeMillis()
        return read(context).any {
            (it.status == UploadQueueStatus.WAITING || it.status == UploadQueueStatus.FAILED || it.status == UploadQueueStatus.RUNNING) &&
                it.attempts < MAX_ATTEMPTS
        }
    }

    @Synchronized
    fun nextDelaySeconds(context: Context): Long? {
        val now = System.currentTimeMillis()
        val items = read(context).filter {
            (it.status == UploadQueueStatus.WAITING || it.status == UploadQueueStatus.FAILED || it.status == UploadQueueStatus.RUNNING) &&
                it.attempts < MAX_ATTEMPTS
        }
        if (items.isEmpty()) return null
        val immediate = items.any {
            it.status == UploadQueueStatus.WAITING || it.status == UploadQueueStatus.RUNNING || it.nextRetryAtMs <= now
        }
        if (immediate) return 5L
        val minNext = items.minOfOrNull { it.nextRetryAtMs } ?: return 20L
        val sec = ((minNext - now) / 1000L).coerceAtLeast(5L)
        return sec
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun read(context: Context): List<QueuedUploadItem> {
        val raw = prefs(context).getString(PREF_KEY_ITEMS, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = mutableListOf<QueuedUploadItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                QueuedUploadItem(
                    id = o.optString("id"),
                    backPath = o.optString("backPath"),
                    frontPath = o.optString("frontPath"),
                    isPrompt = o.optBoolean("isPrompt", true),
                    authToken = o.optString("authToken"),
                    status = o.optString("status", UploadQueueStatus.WAITING),
                    attempts = o.optInt("attempts", 0),
                    lastError = o.optString("lastError"),
                    progressPercent = o.optInt("progressPercent", 0),
                    nextRetryAtMs = o.optLong("nextRetryAtMs", 0L),
                    createdAtMs = o.optLong("createdAtMs", 0L),
                    updatedAtMs = o.optLong("updatedAtMs", 0L)
                )
            )
        }
        return out
    }

    private fun write(context: Context, items: List<QueuedUploadItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("backPath", item.backPath)
                    put("frontPath", item.frontPath)
                    put("isPrompt", item.isPrompt)
                    put("authToken", item.authToken)
                    put("status", item.status)
                    put("attempts", item.attempts)
                    put("lastError", item.lastError)
                    put("progressPercent", item.progressPercent)
                    put("nextRetryAtMs", item.nextRetryAtMs)
                    put("createdAtMs", item.createdAtMs)
                    put("updatedAtMs", item.updatedAtMs)
                }
            )
        }
        prefs(context).edit().putString(PREF_KEY_ITEMS, arr.toString()).apply()
    }

    private fun prune(items: List<QueuedUploadItem>): List<QueuedUploadItem> {
        val now = System.currentTimeMillis()
        val keep = items.filterNot {
            it.status == UploadQueueStatus.SUCCESS && (now - it.updatedAtMs) > 24 * 60 * 60 * 1000L
        }
        return keep.sortedByDescending { it.createdAtMs }.take(60)
    }

    private fun deleteFilesForItem(item: QueuedUploadItem) {
        runCatching { File(item.backPath).delete() }
        runCatching { File(item.frontPath).delete() }
    }
}

class UploadQueueWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val item = UploadQueueManager.nextRunnable(applicationContext) ?: return Result.success()
        if (item.authToken.isBlank()) {
            UploadQueueManager.markFailed(applicationContext, item.id, "Kein Auth-Token")
            UploadQueueScheduler.scheduleSoon(applicationContext)
            return Result.success()
        }

        UploadQueueManager.markRunning(applicationContext, item.id)
        val result = runCatching { upload(item) }
        if (result.isSuccess) {
            UploadQueueManager.markSuccess(applicationContext, item.id)
        } else {
            UploadQueueManager.markFailed(applicationContext, item.id, result.exceptionOrNull()?.message ?: "Upload fehlgeschlagen")
        }

        val nextDelay = UploadQueueManager.nextDelaySeconds(applicationContext)
        if (nextDelay != null) {
            UploadQueueScheduler.scheduleIn(applicationContext, nextDelay)
        }
        return Result.success()
    }

    private suspend fun upload(item: QueuedUploadItem) {
        val backFile = File(item.backPath)
        val frontFile = File(item.frontPath)
        if (!backFile.exists() || !frontFile.exists()) {
            error("Dateien fehlen fuer Queue-Upload")
        }

        val api = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(Api::class.java)

        val totalBytes = (backFile.length() + frontFile.length()).coerceAtLeast(1L)
        var backSent = 0L
        var frontSent = 0L
        var lastSavedPercent = -1
        fun pushProgressIfNeeded() {
            val percent = (((backSent + frontSent).coerceAtMost(totalBytes) * 100) / totalBytes).toInt().coerceIn(0, 100)
            if (percent == 100 || percent >= lastSavedPercent + 5) {
                lastSavedPercent = percent
                UploadQueueManager.markProgress(applicationContext, item.id, percent)
            }
        }

        val backBody = ProgressRequestBody(backFile.asRequestBody("image/*".toMediaTypeOrNull())) { sent, _ ->
            backSent = sent
            pushProgressIfNeeded()
        }
        val frontBody = ProgressRequestBody(frontFile.asRequestBody("image/*".toMediaTypeOrNull())) { sent, _ ->
            frontSent = sent
            pushProgressIfNeeded()
        }

        val backPart = MultipartBody.Part.createFormData(
            "photo_back",
            backFile.name,
            backBody
        )
        val frontPart = MultipartBody.Part.createFormData(
            "photo_front",
            frontFile.name,
            frontBody
        )
        val kind = (if (item.isPrompt) "prompt" else "extra").toRequestBody("text/plain".toMediaTypeOrNull())
        UploadQueueManager.markProgress(applicationContext, item.id, 1)
        api.uploadDual("Bearer ${item.authToken}", backPart, frontPart, kind)
        UploadQueueManager.markProgress(applicationContext, item.id, 100)
    }
}

private class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
) : RequestBody() {
    override fun contentType() = delegate.contentType()

    override fun contentLength() = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength().coerceAtLeast(1L)
        var sent = 0L
        val forwarding = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                onProgress(sent.coerceAtMost(total), total)
            }
        }
        val buffered = forwarding.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
        onProgress(total, total)
    }
}

object UploadQueueScheduler {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun sync(context: Context) {
        val nextDelay = UploadQueueManager.nextDelaySeconds(context)
        if (nextDelay != null) {
            scheduleIn(context, nextDelay)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    fun enqueueNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<UploadQueueWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    fun scheduleSoon(context: Context, delaySeconds: Long = 20) {
        scheduleIn(context, delaySeconds)
    }

    fun scheduleIn(context: Context, delaySeconds: Long) {
        val req = OneTimeWorkRequestBuilder<UploadQueueWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    private const val WORK_NAME = "daily_upload_queue_worker"
}
