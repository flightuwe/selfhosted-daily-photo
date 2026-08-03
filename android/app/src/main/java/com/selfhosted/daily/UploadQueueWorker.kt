package com.selfhosted.daily

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.net.toUri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLException

data class QueuedUploadItem(
    val id: String,
    val backPath: String,
    val frontPath: String,
    val uploadClientId: String,
    val uploadMode: String,
    val appendTargetPhotoId: Long?,
    val isPrompt: Boolean,
    val capsuleMode: String,
    val capsulePrivate: Boolean,
    val capsuleGroupRemind: Boolean,
    val locationShared: Boolean,
    val locationLatitude: Double?,
    val locationLongitude: Double?,
    val status: String,
    val attempts: Int,
    val lastError: String,
    val transferProgressPercent: Int,
    val serverAckState: String,
    val nextRetryAtMs: Long,
    val capturedAtMs: Long,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastAttemptStartedAtMs: Long,
    val lastAttemptFinishedAtMs: Long,
    val lastFailureClass: String,
    val lastHttpCode: Int?,
    val retentionUntilMs: Long,
    val leaseExpiresAtMs: Long
)

object UploadQueueMode {
    const val DUAL = "dual"
    const val ATTACHMENT = "attachment"
}

object UploadQueueStatus {
    const val WAITING = "waiting"
    const val RUNNING = "running"
    const val WAITING_FOR_NETWORK = "waiting_for_network"
    const val WAITING_FOR_SECURE_NETWORK = "waiting_for_secure_network"
    const val AWAITING_SERVER_ACK = "awaiting_server_ack"
    const val FAILED_TRANSIENT = "failed_transient"
    const val FAILED_PERMANENT = "failed_permanent"
    const val ACTION_REQUIRED = "action_required"
    const val SUCCESS = "success"
    const val PAUSED = "paused"
}

object UploadQueueServerAckState {
    const val NONE = "none"
    const val PENDING = "pending"
    const val CONFIRMED = "confirmed"
}

private data class QueuedUploadFailureInfo(
    val message: String,
    val reason: String,
    val httpCode: Int?,
    val network: String?,
    val permanent: Boolean = false,
    val pauseQueue: Boolean = false,
    val overrideDelayMs: Long? = null,
    val ackUncertain: Boolean = false,
    val actionRequired: Boolean = false
)

private const val queueRetryRetentionMs = 7L * 24L * 60L * 60L * 1000L
private const val queueLeaseMs = 10L * 60L * 1000L
private const val queueMaxBackoffSec = 6L * 60L * 60L

object UploadQueueManager {
    private const val PREF_NAME = "app"
    private const val PREF_KEY_ITEMS = "upload_queue_items"

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
        uploadClientId: String,
        isPrompt: Boolean,
        capsuleMode: String = "",
        capsulePrivate: Boolean = false,
        capsuleGroupRemind: Boolean = false,
        locationShared: Boolean = false,
        locationLatitude: Double? = null,
        locationLongitude: Double? = null,
        capturedAtMs: Long = 0L
    ): QueuedUploadItem {
        val now = System.currentTimeMillis()
        val item = QueuedUploadItem(
            id = UUID.randomUUID().toString(),
            backPath = backPath,
            frontPath = frontPath,
            uploadClientId = uploadClientId,
            uploadMode = UploadQueueMode.DUAL,
            appendTargetPhotoId = null,
            isPrompt = isPrompt,
            capsuleMode = capsuleMode.trim(),
            capsulePrivate = capsulePrivate,
            capsuleGroupRemind = capsuleGroupRemind,
            locationShared = locationShared && locationLatitude != null && locationLongitude != null,
            locationLatitude = locationLatitude,
            locationLongitude = locationLongitude,
            status = UploadQueueStatus.WAITING,
            attempts = 0,
            lastError = "",
            transferProgressPercent = 0,
            serverAckState = UploadQueueServerAckState.NONE,
            nextRetryAtMs = 0L,
            capturedAtMs = capturedAtMs,
            createdAtMs = now,
            updatedAtMs = now,
            lastAttemptStartedAtMs = 0L,
            lastAttemptFinishedAtMs = 0L,
            lastFailureClass = "",
            lastHttpCode = null,
            retentionUntilMs = now + queueRetryRetentionMs,
            leaseExpiresAtMs = 0L
        )
        val all = read(context).toMutableList()
        all.add(item)
        check(write(context, prune(all))) { "upload queue persistence failed" }
        UploadQueueScheduler.enqueueNow(context)
        return item
    }

    @Synchronized
    fun enqueueAttachmentFromFile(
        context: Context,
        filePath: String,
        uploadClientId: String,
        appendTargetPhotoId: Long,
        locationShared: Boolean = false,
        locationLatitude: Double? = null,
        locationLongitude: Double? = null,
        capturedAtMs: Long = 0L
    ): QueuedUploadItem {
        val now = System.currentTimeMillis()
        val item = QueuedUploadItem(
            id = UUID.randomUUID().toString(),
            backPath = filePath,
            frontPath = "",
            uploadClientId = uploadClientId,
            uploadMode = UploadQueueMode.ATTACHMENT,
            appendTargetPhotoId = appendTargetPhotoId,
            isPrompt = false,
            capsuleMode = "",
            capsulePrivate = false,
            capsuleGroupRemind = false,
            locationShared = locationShared && locationLatitude != null && locationLongitude != null,
            locationLatitude = locationLatitude,
            locationLongitude = locationLongitude,
            status = UploadQueueStatus.WAITING,
            attempts = 0,
            lastError = "",
            transferProgressPercent = 0,
            serverAckState = UploadQueueServerAckState.NONE,
            nextRetryAtMs = 0L,
            capturedAtMs = capturedAtMs,
            createdAtMs = now,
            updatedAtMs = now,
            lastAttemptStartedAtMs = 0L,
            lastAttemptFinishedAtMs = 0L,
            lastFailureClass = "",
            lastHttpCode = null,
            retentionUntilMs = now + queueRetryRetentionMs,
            leaseExpiresAtMs = 0L
        )
        val all = read(context).toMutableList()
        all.add(item)
        check(write(context, prune(all))) { "upload queue persistence failed" }
        UploadQueueScheduler.enqueueNow(context)
        return item
    }

    @Synchronized
    fun markWaiting(context: Context, id: String): Boolean {
        val now = System.currentTimeMillis()
        var found = false
        val next = read(context).map {
            if (it.id == id) {
                found = true
                it.copy(
                    status = UploadQueueStatus.WAITING,
                    lastError = "",
                    transferProgressPercent = 0,
                    serverAckState = UploadQueueServerAckState.NONE,
                    nextRetryAtMs = 0L,
                    updatedAtMs = now,
                    lastFailureClass = "",
                    lastHttpCode = null,
                    retentionUntilMs = now + queueRetryRetentionMs,
                    leaseExpiresAtMs = 0L
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
    fun convertToExtraAndRetry(context: Context, id: String): Boolean {
        val now = System.currentTimeMillis()
        var found = false
        val next = read(context).map {
            if (it.id == id && it.isPrompt) {
                found = true
                it.copy(
                    isPrompt = false,
                    status = UploadQueueStatus.WAITING,
                    attempts = 0,
                    lastError = "",
                    transferProgressPercent = 0,
                    serverAckState = UploadQueueServerAckState.NONE,
                    nextRetryAtMs = 0L,
                    updatedAtMs = now,
                    lastFailureClass = "",
                    lastHttpCode = null,
                    retentionUntilMs = now + queueRetryRetentionMs,
                    leaseExpiresAtMs = 0L
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
    fun deferExtraUntil(context: Context, id: String, retryAtMs: Long): Boolean {
        val now = System.currentTimeMillis()
        var found = false
        val next = read(context).map { item ->
            if (item.id == id && !item.isPrompt && item.uploadMode == UploadQueueMode.DUAL) {
                found = true
                item.copy(
                    status = UploadQueueStatus.WAITING,
                    attempts = 0,
                    lastError = "Extra wird nach dem aktuellen Daily-Fenster automatisch gesendet.",
                    nextRetryAtMs = retryAtMs.coerceAtLeast(now + 30_000L),
                    updatedAtMs = now,
                    lastFailureClass = "",
                    lastHttpCode = null,
                    retentionUntilMs = maxOf(item.retentionUntilMs, retryAtMs + queueRetryRetentionMs),
                    leaseExpiresAtMs = 0L
                )
            } else item
        }
        if (found) {
            write(context, prune(next))
            UploadQueueScheduler.sync(context)
        }
        return found
    }

    @Synchronized
    fun convertExtraToAttachments(context: Context, id: String, targetPhotoId: Long): Boolean {
        if (targetPhotoId <= 0L) return false
        val now = System.currentTimeMillis()
        val all = read(context).toMutableList()
        val index = all.indexOfFirst { it.id == id && !it.isPrompt && it.uploadMode == UploadQueueMode.DUAL }
        if (index < 0) return false
        val original = all.removeAt(index)
        val paths = listOf(original.backPath, original.frontPath).filter { it.isNotBlank() && File(it).exists() }
        if (paths.isEmpty()) return false
        paths.forEachIndexed { fileIndex, path ->
            all.add(
                original.copy(
                    id = UUID.randomUUID().toString(),
                    backPath = path,
                    frontPath = "",
                    uploadClientId = if (fileIndex == 0) original.uploadClientId else UUID.randomUUID().toString(),
                    uploadMode = UploadQueueMode.ATTACHMENT,
                    appendTargetPhotoId = targetPhotoId,
                    status = UploadQueueStatus.WAITING,
                    attempts = 0,
                    lastError = "",
                    transferProgressPercent = 0,
                    serverAckState = UploadQueueServerAckState.NONE,
                    nextRetryAtMs = 0L,
                    updatedAtMs = now,
                    lastFailureClass = "",
                    lastHttpCode = null,
                    retentionUntilMs = now + queueRetryRetentionMs,
                    leaseExpiresAtMs = 0L
                )
            )
        }
        write(context, prune(all))
        UploadQueueScheduler.enqueueNow(context)
        return true
    }

    @Synchronized
    fun remove(context: Context, id: String): Boolean {
        val all = read(context)
        val item = all.firstOrNull { it.id == id } ?: return false
        deleteFilesForItem(item)
        write(context, prune(all.filterNot { it.id == id }))
        return true
    }

    @Synchronized
    fun recoverStaleEntries(context: Context, nowMs: Long = System.currentTimeMillis()) {
        var recoveredCount = 0
        val next = read(context).map { item ->
            when {
                item.status == UploadQueueStatus.RUNNING || item.status == UploadQueueStatus.AWAITING_SERVER_ACK -> {
                    if (item.leaseExpiresAtMs > 0L && item.leaseExpiresAtMs < nowMs) {
                        recoveredCount += 1
                        item.copy(
                            status = UploadQueueStatus.FAILED_TRANSIENT,
                            lastError = "Vorheriger Upload wurde unterbrochen. Neuer Versuch folgt automatisch.",
                            serverAckState = UploadQueueServerAckState.NONE,
                            transferProgressPercent = 0,
                            nextRetryAtMs = nowMs + 15_000L,
                            updatedAtMs = nowMs,
                            lastAttemptFinishedAtMs = nowMs,
                            lastFailureClass = "worker_interrupted",
                            lastHttpCode = null,
                            leaseExpiresAtMs = 0L
                        )
                    } else item
                }
                isAutoRetryState(item.status) && item.retentionUntilMs in 1 until nowMs -> {
                    recoveredCount += 1
                    item.copy(
                        status = UploadQueueStatus.PAUSED,
                        nextRetryAtMs = 0L,
                        lastError = item.lastError.ifBlank { "Upload wurde pausiert. Bitte spaeter manuell fortsetzen." },
                        updatedAtMs = nowMs,
                        leaseExpiresAtMs = 0L
                    )
                }
                else -> item
            }
        }
        write(context, prune(next))
        if (recoveredCount > 0) {
            appendDebugLog(
                context = context,
                type = "upload_queue_state_recovered",
                message = "Unterbrochene Warteschlangen-Uploads wurden wiederhergestellt.",
                meta = "source=queue;recoveredCount=$recoveredCount"
            )
        }
    }

    @Synchronized
    fun nextRunnable(context: Context, nowMs: Long = System.currentTimeMillis()): QueuedUploadItem? {
        return read(context).firstOrNull {
            isAutoRetryState(it.status) &&
                (it.nextRetryAtMs <= 0L || it.nextRetryAtMs <= nowMs) &&
                (it.retentionUntilMs <= 0L || it.retentionUntilMs > nowMs)
        }
    }

    @Synchronized
    fun claimNextRunnable(context: Context, nowMs: Long = System.currentTimeMillis()): QueuedUploadItem? {
        val all = read(context).toMutableList()
        val index = all.indexOfFirst {
            isAutoRetryState(it.status) &&
                (it.nextRetryAtMs <= 0L || it.nextRetryAtMs <= nowMs) &&
                (it.retentionUntilMs <= 0L || it.retentionUntilMs > nowMs)
        }
        if (index < 0) return null
        val claimed = all[index].copy(
            status = UploadQueueStatus.RUNNING,
            transferProgressPercent = 1,
            serverAckState = UploadQueueServerAckState.NONE,
            updatedAtMs = nowMs,
            lastAttemptStartedAtMs = nowMs,
            leaseExpiresAtMs = nowMs + queueLeaseMs
        )
        all[index] = claimed
        if (!write(context, prune(all))) return null
        UploadQueueScheduler.scheduleRecoveryCheck(context, queueLeaseMs)
        return claimed
    }

    @Synchronized
    fun markRunning(context: Context, id: String) {
        val now = System.currentTimeMillis()
        val next = read(context).map {
            if (it.id == id) {
                it.copy(
                    status = UploadQueueStatus.RUNNING,
                    transferProgressPercent = 1,
                    serverAckState = UploadQueueServerAckState.NONE,
                    updatedAtMs = now,
                    lastAttemptStartedAtMs = now,
                    leaseExpiresAtMs = now + queueLeaseMs
                )
            } else it
        }
        write(context, prune(next))
    }

    @Synchronized
    fun markAwaitingServerAck(context: Context, id: String) {
        val now = System.currentTimeMillis()
        val next = read(context).map {
            if (it.id == id) {
                it.copy(
                    status = UploadQueueStatus.AWAITING_SERVER_ACK,
                    transferProgressPercent = it.transferProgressPercent.coerceAtLeast(95),
                    serverAckState = UploadQueueServerAckState.PENDING,
                    updatedAtMs = now,
                    leaseExpiresAtMs = now + queueLeaseMs
                )
            } else it
        }
        write(context, prune(next))
    }

    @Synchronized
    fun markProgress(context: Context, id: String, percent: Int) {
        val now = System.currentTimeMillis()
        val clamped = percent.coerceIn(0, 100)
        val displayPercent = if (clamped >= 100) 95 else clamped
        val next = read(context).map {
            if (it.id == id && (it.status == UploadQueueStatus.RUNNING || it.status == UploadQueueStatus.AWAITING_SERVER_ACK)) {
                if (displayPercent >= it.transferProgressPercent) {
                    it.copy(
                        transferProgressPercent = displayPercent,
                        updatedAtMs = now,
                        leaseExpiresAtMs = now + queueLeaseMs
                    )
                } else it
            } else it
        }
        write(context, prune(next))
    }

    @Synchronized
    fun markSuccess(context: Context, id: String) {
        val now = System.currentTimeMillis()
        val completedItem = read(context).firstOrNull { it.id == id }
        val next = read(context).map {
            if (it.id == id) {
                it.copy(
                    status = UploadQueueStatus.SUCCESS,
                    lastError = "",
                    transferProgressPercent = 100,
                    serverAckState = UploadQueueServerAckState.CONFIRMED,
                    nextRetryAtMs = 0L,
                    updatedAtMs = now,
                    lastAttemptFinishedAtMs = now,
                    lastFailureClass = "",
                    lastHttpCode = null,
                    leaseExpiresAtMs = 0L
                )
            } else it
        }
        if (write(context, prune(next))) {
            completedItem?.let(::deleteFilesForItem)
        }
    }

    @Synchronized
    fun markFailedTransient(
        context: Context,
        id: String,
        error: String,
        failureClass: String,
        httpCode: Int?,
        networkWaiting: Boolean,
        secureNetworkWaiting: Boolean = false,
        countAttempt: Boolean = true,
        overrideDelayMs: Long? = null
    ) {
        val now = System.currentTimeMillis()
        val next = read(context).map {
            if (it.id == id) {
                val nextAttempts = if (countAttempt) it.attempts + 1 else it.attempts
                val delayMs = overrideDelayMs ?: retryDelayMs(nextAttempts.coerceAtLeast(1))
                it.copy(
                    status = when {
                        secureNetworkWaiting -> UploadQueueStatus.WAITING_FOR_SECURE_NETWORK
                        networkWaiting -> UploadQueueStatus.WAITING_FOR_NETWORK
                        else -> UploadQueueStatus.FAILED_TRANSIENT
                    },
                    attempts = nextAttempts,
                    lastError = error.take(300),
                    transferProgressPercent = 0,
                    serverAckState = UploadQueueServerAckState.NONE,
                    nextRetryAtMs = now + delayMs,
                    updatedAtMs = now,
                    lastAttemptFinishedAtMs = now,
                    lastFailureClass = failureClass.take(64),
                    lastHttpCode = httpCode,
                    retentionUntilMs = maxOf(it.retentionUntilMs, now + queueRetryRetentionMs),
                    leaseExpiresAtMs = 0L
                )
            } else it
        }
        write(context, prune(next))
    }

    @Synchronized
    fun markFailedPermanent(
        context: Context,
        id: String,
        error: String,
        failureClass: String,
        httpCode: Int?
    ) {
        val now = System.currentTimeMillis()
        val next = read(context).map {
            if (it.id == id) {
                it.copy(
                    status = UploadQueueStatus.FAILED_PERMANENT,
                    attempts = it.attempts + 1,
                    lastError = error.take(300),
                    transferProgressPercent = 0,
                    serverAckState = UploadQueueServerAckState.NONE,
                    nextRetryAtMs = 0L,
                    updatedAtMs = now,
                    lastAttemptFinishedAtMs = now,
                    lastFailureClass = failureClass.take(64),
                    lastHttpCode = httpCode,
                    leaseExpiresAtMs = 0L
                )
            } else it
        }
        write(context, prune(next))
    }

    @Synchronized
    fun markActionRequired(
        context: Context,
        id: String,
        error: String,
        failureClass: String,
        httpCode: Int?
    ) {
        val now = System.currentTimeMillis()
        val next = read(context).map { item ->
            if (item.id == id) {
                item.copy(
                    status = UploadQueueStatus.ACTION_REQUIRED,
                    attempts = item.attempts + 1,
                    lastError = error.take(300),
                    transferProgressPercent = 0,
                    serverAckState = UploadQueueServerAckState.NONE,
                    nextRetryAtMs = 0L,
                    updatedAtMs = now,
                    lastAttemptFinishedAtMs = now,
                    lastFailureClass = failureClass.take(64),
                    lastHttpCode = httpCode,
                    leaseExpiresAtMs = 0L
                )
            } else item
        }
        write(context, prune(next))
        // A process can disappear after all request bytes have been handed to
        // the socket but before Retrofit receives the response. Persist a
        // separate watchdog so this state is recovered without requiring the
        // user to open the app again.
        UploadQueueScheduler.scheduleRecoveryCheck(context, queueLeaseMs)
    }

    @Synchronized
    fun markPaused(
        context: Context,
        id: String,
        error: String,
        failureClass: String
    ) {
        val now = System.currentTimeMillis()
        val next = read(context).map {
            if (it.id == id) {
                it.copy(
                    status = UploadQueueStatus.PAUSED,
                    lastError = error.take(300),
                    transferProgressPercent = 0,
                    serverAckState = UploadQueueServerAckState.NONE,
                    nextRetryAtMs = 0L,
                    updatedAtMs = now,
                    lastAttemptFinishedAtMs = now,
                    lastFailureClass = failureClass.take(64),
                    leaseExpiresAtMs = 0L
                )
            } else it
        }
        write(context, prune(next))
    }

    @Synchronized
    fun hasPending(context: Context): Boolean {
        return read(context).any {
            isAutoRetryState(it.status) || it.status == UploadQueueStatus.RUNNING || it.status == UploadQueueStatus.AWAITING_SERVER_ACK
        }
    }

    @Synchronized
    fun findById(context: Context, id: String): QueuedUploadItem? =
        read(context).firstOrNull { it.id == id }

    @Synchronized
    fun nextDelaySeconds(context: Context): Long? {
        val now = System.currentTimeMillis()
        val items = read(context).filter {
            (isAutoRetryState(it.status) || it.status == UploadQueueStatus.RUNNING || it.status == UploadQueueStatus.AWAITING_SERVER_ACK) &&
                (it.retentionUntilMs <= 0L || it.retentionUntilMs > now)
        }
        if (items.isEmpty()) return null
        val immediate = items.any {
            it.status == UploadQueueStatus.WAITING ||
                it.status == UploadQueueStatus.RUNNING ||
                it.status == UploadQueueStatus.AWAITING_SERVER_ACK ||
                it.nextRetryAtMs <= now
        }
        if (immediate) return 5L
        val minNext = items.minOfOrNull { it.nextRetryAtMs } ?: return 20L
        return ((minNext - now) / 1000L).coerceAtLeast(5L)
    }

    @Synchronized
    fun nextLeaseRecoveryDelaySeconds(context: Context, nowMs: Long = System.currentTimeMillis()): Long? {
        val earliestLease = read(context)
            .asSequence()
            .filter {
                (it.status == UploadQueueStatus.RUNNING || it.status == UploadQueueStatus.AWAITING_SERVER_ACK) &&
                    it.leaseExpiresAtMs > 0L &&
                    (it.retentionUntilMs <= 0L || it.retentionUntilMs > nowMs)
            }
            .map { it.leaseExpiresAtMs }
            .minOrNull()
            ?: return null
        return ((earliestLease - nowMs + 999L) / 1000L).coerceAtLeast(0L)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun read(context: Context): List<QueuedUploadItem> {
        val raw = prefs(context).getString(PREF_KEY_ITEMS, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = mutableListOf<QueuedUploadItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val createdAtMs = o.optLong("createdAtMs", 0L)
            val updatedAtMs = o.optLong("updatedAtMs", createdAtMs)
            val oldStatus = o.optString("status", UploadQueueStatus.WAITING)
            val mappedStatus = when (oldStatus) {
                "failed" -> UploadQueueStatus.FAILED_TRANSIENT
                "success" -> UploadQueueStatus.SUCCESS
                "running" -> UploadQueueStatus.RUNNING
                "waiting" -> UploadQueueStatus.WAITING
                UploadQueueStatus.WAITING_FOR_NETWORK,
                UploadQueueStatus.WAITING_FOR_SECURE_NETWORK,
                UploadQueueStatus.AWAITING_SERVER_ACK,
                UploadQueueStatus.FAILED_TRANSIENT,
                UploadQueueStatus.FAILED_PERMANENT,
                UploadQueueStatus.ACTION_REQUIRED,
                UploadQueueStatus.PAUSED -> oldStatus
                else -> UploadQueueStatus.WAITING
            }
            out.add(
                QueuedUploadItem(
                    id = o.optString("id"),
                    backPath = o.optString("backPath"),
                    frontPath = o.optString("frontPath"),
                    uploadClientId = o.optString("uploadClientId"),
                    uploadMode = o.optString("uploadMode", UploadQueueMode.DUAL).ifBlank { UploadQueueMode.DUAL },
                    appendTargetPhotoId = if (o.has("appendTargetPhotoId") && !o.isNull("appendTargetPhotoId")) o.optLong("appendTargetPhotoId") else null,
                    isPrompt = o.optBoolean("isPrompt", true),
                    capsuleMode = o.optString("capsuleMode"),
                    capsulePrivate = o.optBoolean("capsulePrivate", false),
                    capsuleGroupRemind = o.optBoolean("capsuleGroupRemind", false),
                    locationShared = o.optBoolean("locationShared", false),
                    locationLatitude = if (o.has("locationLatitude") && !o.isNull("locationLatitude")) o.optDouble("locationLatitude") else null,
                    locationLongitude = if (o.has("locationLongitude") && !o.isNull("locationLongitude")) o.optDouble("locationLongitude") else null,
                    status = mappedStatus,
                    attempts = o.optInt("attempts", 0),
                    lastError = o.optString("lastError"),
                    transferProgressPercent = o.optInt("transferProgressPercent", o.optInt("progressPercent", 0)),
                    serverAckState = o.optString("serverAckState", UploadQueueServerAckState.NONE).ifBlank { UploadQueueServerAckState.NONE },
                    nextRetryAtMs = o.optLong("nextRetryAtMs", 0L),
                    capturedAtMs = o.optLong("capturedAtMs", 0L),
                    createdAtMs = createdAtMs,
                    updatedAtMs = updatedAtMs,
                    lastAttemptStartedAtMs = o.optLong("lastAttemptStartedAtMs", 0L),
                    lastAttemptFinishedAtMs = o.optLong("lastAttemptFinishedAtMs", 0L),
                    lastFailureClass = o.optString("lastFailureClass"),
                    lastHttpCode = if (o.has("lastHttpCode") && !o.isNull("lastHttpCode")) o.optInt("lastHttpCode") else null,
                    retentionUntilMs = o.optLong("retentionUntilMs", if (createdAtMs > 0L) createdAtMs + queueRetryRetentionMs else 0L),
                    leaseExpiresAtMs = o.optLong("leaseExpiresAtMs", 0L)
                )
            )
        }
        return out
    }

    private fun write(context: Context, items: List<QueuedUploadItem>): Boolean {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("backPath", item.backPath)
                    put("frontPath", item.frontPath)
                    put("uploadClientId", item.uploadClientId)
                    put("uploadMode", item.uploadMode)
                    put("appendTargetPhotoId", item.appendTargetPhotoId)
                    put("isPrompt", item.isPrompt)
                    put("capsuleMode", item.capsuleMode)
                    put("capsulePrivate", item.capsulePrivate)
                    put("capsuleGroupRemind", item.capsuleGroupRemind)
                    put("locationShared", item.locationShared)
                    put("locationLatitude", item.locationLatitude)
                    put("locationLongitude", item.locationLongitude)
                    put("status", item.status)
                    put("attempts", item.attempts)
                    put("lastError", item.lastError)
                    put("transferProgressPercent", item.transferProgressPercent)
                    put("serverAckState", item.serverAckState)
                    put("nextRetryAtMs", item.nextRetryAtMs)
                    put("capturedAtMs", item.capturedAtMs)
                    put("createdAtMs", item.createdAtMs)
                    put("updatedAtMs", item.updatedAtMs)
                    put("lastAttemptStartedAtMs", item.lastAttemptStartedAtMs)
                    put("lastAttemptFinishedAtMs", item.lastAttemptFinishedAtMs)
                    put("lastFailureClass", item.lastFailureClass)
                    put("lastHttpCode", item.lastHttpCode)
                    put("retentionUntilMs", item.retentionUntilMs)
                    put("leaseExpiresAtMs", item.leaseExpiresAtMs)
                }
            )
        }
        // Queue claims and acknowledgements must survive an immediate process
        // death. commit() makes the read-modify-write lease durable before the
        // worker starts transferring bytes.
        return prefs(context).edit().putString(PREF_KEY_ITEMS, arr.toString()).commit()
    }

    private fun prune(items: List<QueuedUploadItem>): List<QueuedUploadItem> {
        val now = System.currentTimeMillis()
        val keep = items.filterNot {
            it.status == UploadQueueStatus.SUCCESS && (now - it.updatedAtMs) > 24L * 60L * 60L * 1000L
        }
        val actionItems = keep.filter {
            it.status == UploadQueueStatus.ACTION_REQUIRED ||
                it.status == UploadQueueStatus.FAILED_PERMANENT ||
                it.status == UploadQueueStatus.PAUSED
        }
        val boundedAutomaticItems = keep.filterNot { it in actionItems }
            .sortedByDescending { it.createdAtMs }
            .take(60)
        return (actionItems + boundedAutomaticItems).distinctBy { it.id }.sortedByDescending { it.createdAtMs }
    }

    private fun deleteFilesForItem(item: QueuedUploadItem) {
        runCatching { File(item.backPath).delete() }
        if (item.frontPath.isNotBlank()) {
            runCatching { File(item.frontPath).delete() }
        }
    }
}

class UploadQueueWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        UploadQueueManager.recoverStaleEntries(applicationContext)
        val item = UploadQueueManager.claimNextRunnable(applicationContext) ?: return Result.success()
        val networkSnapshot = queueNetworkSnapshotMeta(applicationContext)
        if (!queueHasStableNetwork(applicationContext)) {
            UploadQueueManager.markFailedTransient(
                context = applicationContext,
                id = item.id,
                error = "Keine stabile Verbindung. Upload wird spaeter automatisch fortgesetzt.",
                failureClass = "no_active_network",
                httpCode = null,
                networkWaiting = true,
                countAttempt = false,
                overrideDelayMs = 30_000L
            )
            appendDebugLog(
                context = applicationContext,
                type = "upload_queue_waiting_for_network",
                message = "Keine stabile Verbindung. Upload wird spaeter automatisch fortgesetzt.",
                meta = "source=queue;kind=${if (item.isPrompt) "prompt" else "extra"};queueItemId=${item.id};uploadClientId=${item.uploadClientId};failureClass=no_active_network;network=no_active_network;networkStable=false;snapshot=$networkSnapshot"
            )
            UploadQueueScheduler.sync(applicationContext)
            return Result.success()
        }
        NetworkUsageLedger.recordUidBoundary(applicationContext, "worker_start")
        if (item.attempts > 0) {
            NetworkUsageLedger.recordUploadRetry(applicationContext)
        }
        val repo = AppRepo(applicationContext, buildStandardHttpClient(applicationContext, "worker", timeoutProfile = QueueUploadHttpTimeoutProfile))
        val probe = repo.measureUploadTelemetryProbe()
        // Capture before markSuccess removes the queue files. The completion log
        // must report the transmitted size, not the now-deleted files as 1 byte.
        val uploadBytesTotal = (File(item.backPath).length() + File(item.frontPath).length()).coerceAtLeast(1L)
        appendDebugLog(
            context = applicationContext,
            type = "upload_queue_attempt_started",
            message = "Warteschlangen-Upload gestartet.",
            meta = buildString {
                append("source=queue")
                append(";kind=").append(if (item.isPrompt) "prompt" else "extra")
                append(";uploadClientId=").append(item.uploadClientId)
                append(";queueItemId=").append(item.id)
                append(";attempt=").append(item.attempts + 1)
                append(";bytesTotal=").append(uploadBytesTotal)
                append(";networkStable=").append(probe.networkStable)
                if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
                if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
                item.capturedAtMs.takeIf { it > 0L }?.let {
                    append(";capturedAt=").append(OffsetDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()))
                }
                append(";queuedAt=").append(OffsetDateTime.ofInstant(Instant.ofEpochMilli(item.createdAtMs), ZoneId.systemDefault()))
                append(";").append(probe.networkSnapshot)
            }
        )
        val result = runCatching { upload(item, repo, probe) }
        if (result.isSuccess) {
            UploadQueueManager.markSuccess(applicationContext, item.id)
            if (item.uploadMode == UploadQueueMode.ATTACHMENT) {
                val targetPhotoId = item.appendTargetPhotoId?.takeIf { it > 0L }
                appendFeedTraceLog(
                    context = applicationContext,
                    type = "attachment_upload_confirmed",
                    message = "attachment upload confirmed",
                    meta = "queueItemId=${item.id};targetPhotoId=${targetPhotoId ?: -1L};uploadClientId=${item.uploadClientId};attempt=${item.attempts + 1}"
                )
                queuePendingFeedInvalidation(
                    context = applicationContext,
                    day = "",
                    photoId = targetPhotoId,
                    reason = "attachment_uploaded",
                    source = "upload_queue_worker"
                )
                appendFeedTraceLog(
                    context = applicationContext,
                    type = "attachment_feed_invalidation_queued",
                    message = "attachment feed invalidation queued",
                    meta = "queueItemId=${item.id};targetPhotoId=${targetPhotoId ?: -1L};source=upload_queue_worker"
                )
                publishForegroundFeedInvalidation(
                    context = applicationContext,
                    day = "",
                    photoId = targetPhotoId,
                    reason = "attachment_uploaded",
                    source = "upload_queue_worker"
                )
                appendFeedTraceLog(
                    context = applicationContext,
                    type = "attachment_foreground_refresh_signaled",
                    message = "attachment foreground refresh signaled",
                    meta = "queueItemId=${item.id};targetPhotoId=${targetPhotoId ?: -1L};source=upload_queue_worker"
                )
            }
            appendDebugLog(
                context = applicationContext,
                type = "upload_queue_succeeded",
                message = "Warteschlangen-Upload erfolgreich bestaetigt.",
                meta = buildString {
                    append("source=queue")
                    append(";kind=").append(if (item.isPrompt) "prompt" else "extra")
                    append(";uploadClientId=").append(item.uploadClientId)
                    append(";queueItemId=").append(item.id)
                    append(";attempt=").append(item.attempts + 1)
                    append(";bytesTotal=").append(uploadBytesTotal)
                    append(";http=200")
                    append(";networkStable=").append(probe.networkStable)
                    if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
                    if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
                    item.capturedAtMs.takeIf { it > 0L }?.let {
                        append(";capturedAt=").append(OffsetDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()))
                    }
                    append(";queuedAt=").append(OffsetDateTime.ofInstant(Instant.ofEpochMilli(item.createdAtMs), ZoneId.systemDefault()))
                    append(";").append(probe.networkSnapshot)
                }
            )
        } else {
            val failure = result.exceptionOrNull()
            val failureInfo = failure?.let(::queuedUploadFailureInfo)
            val displayError = failureInfo?.message ?: "Upload fehlgeschlagen"
            when {
                failureInfo == null -> {
                    UploadQueueManager.markFailedTransient(
                        context = applicationContext,
                        id = item.id,
                        error = displayError,
                        failureClass = "unknown",
                        httpCode = null,
                        networkWaiting = false
                    )
                }
                failureInfo.pauseQueue -> {
                    UploadQueueManager.markPaused(
                        context = applicationContext,
                        id = item.id,
                        error = displayError,
                        failureClass = failureInfo.reason
                    )
                    UploadQueueManager.findById(applicationContext, item.id)?.let { updated ->
                        appendDebugLog(
                            context = applicationContext,
                            type = "upload_queue_paused",
                            message = displayError,
                            meta = buildString {
                                append("source=queue")
                                append(";kind=").append(if (item.isPrompt) "prompt" else "extra")
                                append(";queueItemId=").append(item.id)
                                append(";uploadClientId=").append(item.uploadClientId)
                                append(";attempt=").append(updated.attempts)
                                append(";failureClass=").append(failureInfo.reason)
                                append(";http=").append(failureInfo.httpCode ?: -1)
                                append(";status=").append(updated.status)
                                append(";action=user_reauth_required")
                            }
                        )
                    }
                }
                failureInfo.actionRequired -> {
                    UploadQueueManager.markActionRequired(
                        context = applicationContext,
                        id = item.id,
                        error = displayError,
                        failureClass = failureInfo.reason,
                        httpCode = failureInfo.httpCode
                    )
                    appendDebugLog(
                        context = applicationContext,
                        type = "upload_queue_action_required",
                        message = displayError,
                        meta = "source=queue;kind=extra;queueItemId=${item.id};uploadClientId=${item.uploadClientId};failureClass=${failureInfo.reason};http=${failureInfo.httpCode ?: -1};status=${UploadQueueStatus.ACTION_REQUIRED}"
                    )
                }
                failureInfo.permanent -> {
                    UploadQueueManager.markFailedPermanent(
                        context = applicationContext,
                        id = item.id,
                        error = displayError,
                        failureClass = failureInfo.reason,
                        httpCode = failureInfo.httpCode
                    )
                    UploadQueueManager.findById(applicationContext, item.id)?.let { updated ->
                        appendDebugLog(
                            context = applicationContext,
                            type = "upload_queue_action_required",
                            message = displayError,
                            meta = buildString {
                                append("source=queue")
                                append(";kind=").append(if (item.isPrompt) "prompt" else "extra")
                                append(";queueItemId=").append(item.id)
                                append(";uploadClientId=").append(item.uploadClientId)
                                append(";attempt=").append(updated.attempts)
                                append(";failureClass=").append(failureInfo.reason)
                                append(";http=").append(failureInfo.httpCode ?: -1)
                                append(";status=").append(updated.status)
                            }
                        )
                    }
                }
                else -> {
                    UploadQueueManager.markFailedTransient(
                        context = applicationContext,
                        id = item.id,
                        error = displayError,
                        failureClass = failureInfo.reason,
                        httpCode = failureInfo.httpCode,
                        networkWaiting = failureInfo.network != null && failureInfo.reason != "ssl_handshake" && failureInfo.reason != "cert_path_validator" && failureInfo.reason != "ssl_other",
                        secureNetworkWaiting = failureInfo.reason == "ssl_handshake" || failureInfo.reason == "cert_path_validator" || failureInfo.reason == "ssl_other",
                        overrideDelayMs = failureInfo.overrideDelayMs
                            ?: if (failureInfo.reason == "ssl_handshake" || failureInfo.reason == "cert_path_validator" || failureInfo.reason == "ssl_other") 15L * 60L * 1000L else null
                    )
                    UploadQueueManager.findById(applicationContext, item.id)?.let { updated ->
                        appendDebugLog(
                            context = applicationContext,
                            type = "upload_queue_retry_scheduled",
                            message = displayError,
                            meta = buildString {
                                append("source=queue")
                                append(";kind=").append(if (item.isPrompt) "prompt" else "extra")
                                append(";queueItemId=").append(item.id)
                                append(";uploadClientId=").append(item.uploadClientId)
                                append(";attempt=").append(updated.attempts)
                                append(";failureClass=").append(failureInfo.reason)
                                append(";http=").append(failureInfo.httpCode ?: -1)
                                append(";status=").append(updated.status)
                                append(";nextRetryAtMs=").append(updated.nextRetryAtMs)
                                append(";retryDelayMs=").append((updated.nextRetryAtMs - updated.updatedAtMs).coerceAtLeast(0L))
                                append(";ackUncertain=").append(failureInfo.ackUncertain)
                            }
                        )
                    }
                }
            }
            if (failure != null && failureInfo != null) {
                logQueuedUploadFailure(applicationContext, item, failure, failureInfo, networkSnapshot, probe)
            }
        }

        UploadQueueScheduler.sync(applicationContext)
        NetworkUsageLedger.recordUidBoundary(applicationContext, "worker_end")
        return Result.success()
    }

    private suspend fun upload(item: QueuedUploadItem, repo: AppRepo, probe: UploadTelemetryProbe) {
        if (item.uploadMode == UploadQueueMode.ATTACHMENT) {
            val file = File(item.backPath)
            if (!file.exists()) throw IOException("attachment file missing")
            val totalBytes = file.length().coerceAtLeast(1L)
            repo.appendPhotoToPost(
                photoId = item.appendTargetPhotoId ?: throw IllegalStateException("append_target_missing"),
                uri = file.toUri(),
                shareLocation = item.locationShared,
                capturedAtOverride = item.capturedAtMs.takeIf { it > 0L }?.let {
                    OffsetDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                },
                uploadClientIdOverride = item.uploadClientId
            ) { sent, _ ->
                val percent = ((sent.coerceAtMost(totalBytes) * 100L) / totalBytes).toInt().coerceIn(0, 100)
                UploadQueueManager.markProgress(applicationContext, item.id, percent)
            }
            return
        }
        val backFile = File(item.backPath)
        val frontFile = File(item.frontPath)
        if (!backFile.exists() || !frontFile.exists()) {
            error("Dateien fehlen fuer Queue-Upload")
        }

        val totalBytes = (backFile.length() + frontFile.length()).coerceAtLeast(1L)
        var backSent = 0L
        var frontSent = 0L
        var awaitingAckLogged = false
        var lastSavedPercent = -1
        val startedAt = System.currentTimeMillis()

        fun pushProgressIfNeeded() {
            val percent = (((backSent + frontSent).coerceAtMost(totalBytes) * 100) / totalBytes).toInt().coerceIn(0, 100)
            if (!awaitingAckLogged && (backSent + frontSent) >= totalBytes) {
                awaitingAckLogged = true
                UploadQueueManager.markAwaitingServerAck(applicationContext, item.id)
                appendDebugLog(
                    context = applicationContext,
                    type = "upload_queue_server_ack_pending",
                    message = "Upload gesendet, warte auf Bestaetigung.",
                    meta = buildString {
                        append("source=queue")
                        append(";kind=").append(if (item.isPrompt) "prompt" else "extra")
                        append(";queueItemId=").append(item.id)
                        append(";uploadClientId=").append(item.uploadClientId)
                        append(";attempt=").append(item.attempts + 1)
                        append(";bytesTotal=").append(totalBytes)
                        append(";bytesSent=").append((backSent + frontSent).coerceAtMost(totalBytes))
                        append(";durationMs=").append((System.currentTimeMillis() - startedAt).coerceAtLeast(0L))
                        append(";networkStable=").append(probe.networkStable)
                        if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
                        if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
                        item.capturedAtMs.takeIf { it > 0L }?.let {
                            append(";capturedAt=").append(OffsetDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()))
                        }
                        append(";queuedAt=").append(OffsetDateTime.ofInstant(Instant.ofEpochMilli(item.createdAtMs), ZoneId.systemDefault()))
                        append(";").append(probe.networkSnapshot)
                    }
                )
                return
            }
            if (percent >= lastSavedPercent + 5) {
                lastSavedPercent = percent
                UploadQueueManager.markProgress(applicationContext, item.id, percent)
            }
        }

        val backBody = QueueProgressRequestBody(backFile.asRequestBody("image/*".toMediaTypeOrNull())) { sent, _ ->
            backSent = sent
            pushProgressIfNeeded()
        }
        val frontBody = QueueProgressRequestBody(frontFile.asRequestBody("image/*".toMediaTypeOrNull())) { sent, _ ->
            frontSent = sent
            pushProgressIfNeeded()
        }

        val backPart = MultipartBody.Part.createFormData("photo_back", backFile.name, backBody)
        val frontPart = MultipartBody.Part.createFormData("photo_front", frontFile.name, frontBody)
        val kind = (if (item.isPrompt) "prompt" else "extra").toRequestBody("text/plain".toMediaTypeOrNull())
        val capturedAtPart = item.capturedAtMs.takeIf { it > 0L }
            ?.let { formatCapturedAtForApi(OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), java.time.ZoneId.systemDefault())) }
            ?.toRequestBody("text/plain".toMediaTypeOrNull())
        val uploadClientIdPart = item.uploadClientId.trim().takeIf { it.isNotBlank() }
            ?.toRequestBody("text/plain".toMediaTypeOrNull())
        val capsuleModePart = item.capsuleMode.trim().takeIf { it.isNotBlank() }
            ?.toRequestBody("text/plain".toMediaTypeOrNull())
        val capsulePrivatePart = if (capsuleModePart != null) {
            item.capsulePrivate.toString().toRequestBody("text/plain".toMediaTypeOrNull())
        } else null
        val capsuleGroupRemindPart = if (capsuleModePart != null) {
            item.capsuleGroupRemind.toString().toRequestBody("text/plain".toMediaTypeOrNull())
        } else null
        val locationSharedPart = if (item.locationShared && item.locationLatitude != null && item.locationLongitude != null) {
            "true".toRequestBody("text/plain".toMediaTypeOrNull())
        } else null
        val locationLatitudePart = item.locationLatitude?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
        val locationLongitudePart = item.locationLongitude?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())

        UploadQueueManager.markProgress(applicationContext, item.id, 1)
        try {
            repo.uploadDualAuthorized(
                backPart = backPart,
                frontPart = frontPart,
                kind = kind,
                capturedAtPart = capturedAtPart,
                uploadClientIdPart = uploadClientIdPart,
                capsuleModePart = capsuleModePart,
                capsulePrivatePart = capsulePrivatePart,
                capsuleGroupRemindPart = capsuleGroupRemindPart,
                locationSharedPart = locationSharedPart,
                locationLatitudePart = locationLatitudePart,
                locationLongitudePart = locationLongitudePart
            )
        } catch (t: Throwable) {
            if (awaitingAckLogged && queuedUploadNetworkFailureKind(t) == "timeout") {
                throw QueueServerAckTimeoutException(t)
            }
            throw t
        }
    }
}

private class QueueServerAckTimeoutException(cause: Throwable) : IOException("queue_server_ack_timeout", cause)

private fun logQueuedUploadFailure(
    context: Context,
    item: QueuedUploadItem,
    throwable: Throwable,
    failureInfo: QueuedUploadFailureInfo,
    networkSnapshot: String,
    probe: UploadTelemetryProbe
) {
    val meta = buildString {
        append("source=queue")
        append(";endpoint=upload_dual_queue")
        append(";http=").append(failureInfo.httpCode ?: -1)
        append(";error=").append(throwable::class.java.simpleName)
        append(";kind=").append(if (item.isPrompt) "prompt" else "extra")
        append(";queueItemId=").append(item.id)
        append(";uploadClientId=").append(item.uploadClientId)
        append(";attempt=").append(item.attempts + 1)
        append(";reason=").append(failureInfo.reason)
        append(";failureClass=").append(failureInfo.reason)
        append(";securityFailureClass=").append(failureInfo.reason)
        append(";ackUncertain=").append(failureInfo.ackUncertain)
        append(";networkStateClass=").append(if (probe.networkStable) "stable" else "unstable")
        append(";retrySuppressedReason=").append(
            if (failureInfo.reason == "ssl_handshake" || failureInfo.reason == "cert_path_validator" || failureInfo.reason == "ssl_other") "waiting_for_secure_network" else "-"
        )
        append(";userAdviceShown=").append(
            failureInfo.reason == "ssl_handshake" || failureInfo.reason == "cert_path_validator" || failureInfo.reason == "ssl_other"
        )
        append(";stateBefore=").append(item.status)
        append(";networkStable=").append(probe.networkStable)
        append(";networkSnapshot=").append(networkSnapshot)
        append(";bytesTotal=").append((File(item.backPath).length() + File(item.frontPath).length()).coerceAtLeast(1L))
        if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
        if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
        item.capturedAtMs.takeIf { it > 0L }?.let {
            append(";capturedAt=").append(OffsetDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault()))
        }
        append(";queuedAt=").append(OffsetDateTime.ofInstant(Instant.ofEpochMilli(item.createdAtMs), ZoneId.systemDefault()))
        if (failureInfo.network != null) {
            append(";network=").append(failureInfo.network)
        }
        append(";").append(debugThrowableMetaShared(throwable))
    }
    appendDebugLog(
        context = context,
        type = "upload_queue_failed",
        message = failureInfo.message,
        meta = meta
    )
}

private fun queuedUploadNetworkFailureKind(throwable: Throwable): String? {
    val root = generateSequence(throwable) { it.cause }.last()
    return when {
        root is CertPathValidatorException -> "cert_path_validator"
        root is SSLHandshakeException -> "ssl_handshake"
        root is UnknownHostException -> "dns"
        root is ConnectException -> "connect"
        root is SocketTimeoutException -> "timeout"
        root is SSLException -> "ssl_other"
        root is IOException -> "io"
        else -> null
    }
}

private fun queuedUploadFailureInfo(throwable: Throwable): QueuedUploadFailureInfo {
    if (throwable is QueueServerAckTimeoutException) {
        return QueuedUploadFailureInfo(
            message = "Upload wurde gesendet, aber der Server hat zu spaet bestaetigt. Die App gleicht den Status automatisch erneut ab.",
            reason = "server_ack_timeout",
            httpCode = null,
            network = "timeout",
            overrideDelayMs = 20_000L,
            ackUncertain = true
        )
    }
    if (throwable is CancellationException || throwable::class.java.simpleName.contains("JobCancellationException")) {
        return QueuedUploadFailureInfo(
            message = "Vorheriger Uploadlauf wurde intern neu geplant. Neuer Versuch folgt automatisch.",
            reason = "worker_cancelled",
            httpCode = null,
            network = null,
            overrideDelayMs = 10_000L
        )
    }
    val networkKind = queuedUploadNetworkFailureKind(throwable)
    if (networkKind != null) {
        val message = when (networkKind) {
            "dns" -> "Servername konnte nicht aufgeloest werden. Upload wird spaeter automatisch fortgesetzt."
            "connect" -> "Keine stabile Verbindung. Upload wird spaeter automatisch fortgesetzt."
            "timeout" -> "Server antwortet zu langsam. Upload wird spaeter automatisch fortgesetzt."
            "ssl_handshake" -> "Sichere Verbindung fehlgeschlagen. Upload wartet auf sichere Verbindung."
            "cert_path_validator" -> "Dieses Netzwerk vertraut dem Daily-Zertifikat nicht oder veraendert die Verbindung. Upload wartet auf sichere Verbindung."
            "ssl_other" -> "Sichere Verbindung fehlgeschlagen. Upload wartet auf sichere Verbindung."
            else -> "Upload wird spaeter automatisch fortgesetzt."
        }
        return QueuedUploadFailureInfo(
            message = message,
            reason = networkKind,
            httpCode = null,
            network = networkKind,
            overrideDelayMs = if (networkKind == "timeout") 45_000L else null
        )
    }
    if (throwable is HttpException) {
        val rawBody = runCatching { throwable.response()?.errorBody()?.string().orEmpty() }.getOrDefault("")
        val raw = rawBody.lowercase()
        val errorCode = parseApiErrorCode(rawBody)?.lowercase().orEmpty()
        val reason = when (throwable.code()) {
            400 -> when {
                raw.contains("invalid captured_at") -> "captured_at_invalid"
                else -> "invalid_request"
            }
            401 -> "http_401"
            403 -> when {
                errorCode == "prompt_inactive" || raw.contains("prompt inactive") -> "prompt_inactive"
                errorCode == "extra_window_blocked" || raw.contains("extra unavailable during daily moment window") -> "extra_window_blocked"
                errorCode == "upload_window_closed" || raw.contains("upload window closed") -> "upload_window_closed"
                errorCode == "daily_required" || raw.contains("poste zuerst dein tagesmoment") || raw.contains("sichtbaren beitrag") -> "daily_required"
                else -> "forbidden"
            }
            409 -> "already_posted"
            in 500..599 -> "http_${throwable.code()}"
            else -> "http_${throwable.code()}"
        }
        val permanent = throwable.code() in 400..499 && throwable.code() !in listOf(401, 408, 429)
        val message = when (throwable.code()) {
            400 -> when {
                raw.contains("invalid captured_at") -> "Aufnahmezeit konnte nicht verarbeitet werden. Bitte erneut versuchen."
                else -> "Upload-Daten sind ungueltig. Bitte neu aufnehmen oder erneut versuchen."
            }
            401 -> "Sitzung abgelaufen. Bitte App oeffnen und erneut anmelden."
            403 -> when {
                errorCode == "prompt_inactive" || raw.contains("prompt inactive") -> "Kein aktiver Daily-Moment mehr. Diesen Upload loeschen oder als Extra posten."
                errorCode == "extra_window_blocked" || raw.contains("extra unavailable during daily moment window") -> "Waehrend des aktiven Daily-Moments sind Extras gesperrt."
                errorCode == "upload_window_closed" || raw.contains("upload window closed") -> "Upload-Zeitfenster ist geschlossen."
                errorCode == "daily_required" || raw.contains("poste zuerst dein tagesmoment") || raw.contains("sichtbaren beitrag") -> "Poste zuerst dein Tagesmoment."
                else -> "Aktion nicht erlaubt"
            }
            409 -> "Du hast fuer diesen Fall bereits gepostet."
            in 500..599 -> "Serverfehler. Upload wird spaeter automatisch fortgesetzt."
            else -> throwable.message ?: "Upload fehlgeschlagen"
        }
        return QueuedUploadFailureInfo(
            message = message,
            reason = reason,
            httpCode = throwable.code(),
            network = null,
            permanent = permanent,
            pauseQueue = throwable.code() == 401,
            actionRequired = reason == "extra_window_blocked"
        )
    }
    if (throwable is IllegalStateException) {
        val raw = throwable.message.orEmpty()
        return when {
            raw.contains("missing_access_token") || raw.contains("token_expired_refresh_failed") -> QueuedUploadFailureInfo(
                message = "Sitzung abgelaufen. Bitte App oeffnen und erneut anmelden.",
                reason = "auth_missing",
                httpCode = 401,
                network = null,
                pauseQueue = true
            )
            else -> QueuedUploadFailureInfo(
                message = raw.ifBlank { "Upload fehlgeschlagen" },
                reason = "illegal_state",
                httpCode = null,
                network = null
            )
        }
    }
    return QueuedUploadFailureInfo(
        message = throwable.message ?: throwable::class.java.simpleName,
        reason = throwable::class.java.simpleName,
        httpCode = null,
        network = null
    )
}

private fun appendDebugLog(context: Context, type: String, message: String, meta: String = "") {
    val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    val raw = prefs.getString("debug_logs_v1", "") ?: ""
    val current = runCatching {
        val arr = if (raw.isBlank()) JSONArray() else JSONArray(raw)
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let(out::add)
        }
        out
    }.getOrDefault(mutableListOf())
    val cleanType = type.trim().ifBlank { "unknown" }.take(32)
    val cleanMessage = message.trim().ifBlank { "unknown error" }.take(500)
    val cleanMeta = meta.trim().take(4000)
    current.add(
        JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("type", cleanType)
            put("message", cleanMessage)
            put("meta", cleanMeta)
            put("createdAt", OffsetDateTime.now().toString())
        }
    )
    val arr = JSONArray()
    current.takeLast(500).forEach(arr::put)
    prefs.edit().putString("debug_logs_v1", arr.toString()).apply()
}

private class QueueProgressRequestBody(
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

// This worker is deliberately independent from the upload worker. WorkManager
// can then execute recovery after an app/process death even though the original
// upload work never reached its normal scheduler path.
class UploadQueueRecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        UploadQueueManager.recoverStaleEntries(applicationContext)
        UploadQueueScheduler.sync(applicationContext)
        return Result.success()
    }
}

object UploadQueueScheduler {
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun sync(context: Context) {
        UploadQueueManager.recoverStaleEntries(context)
        syncRecoveryCheck(context)
        val nextDelay = UploadQueueManager.nextDelaySeconds(context)
        if (hasRunningWork(context)) {
            return
        }
        if (nextDelay != null) {
            scheduleIn(context, nextDelay)
        } else {
            cancelPendingWork(context)
        }
    }

    fun enqueueNow(context: Context) {
        scheduleInternal(context, 0L)
    }

    fun scheduleSoon(context: Context, delaySeconds: Long = 20) {
        scheduleIn(context, delaySeconds)
    }

    fun scheduleIn(context: Context, delaySeconds: Long) {
        scheduleInternal(context, delaySeconds.coerceAtLeast(0L))
    }

    fun scheduleRecoveryCheck(context: Context, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<UploadQueueRecoveryWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RECOVERY_WORK_NAME,
            // The lease is refreshed while an upload makes progress. Replace
            // an earlier watchdog so it always fires after the newest lease,
            // rather than quietly completing before that lease expires.
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun syncRecoveryCheck(context: Context) {
        val delaySeconds = UploadQueueManager.nextLeaseRecoveryDelaySeconds(context)
        if (delaySeconds == null) {
            WorkManager.getInstance(context).cancelUniqueWork(RECOVERY_WORK_NAME)
        } else {
            scheduleRecoveryCheck(context, delaySeconds * 1000L)
        }
    }

    @Synchronized
    private fun scheduleInternal(context: Context, delaySeconds: Long) {
        if (hasRunningWork(context)) return
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val desiredAtMs = System.currentTimeMillis() + delaySeconds * 1000L
        val existingScheduledAtMs = prefs.getLong(PREF_KEY_NEXT_SCHEDULED_AT_MS, 0L)
        if (hasPendingWork(context) && existingScheduledAtMs in 1..desiredAtMs) {
            return
        }
        cancelPendingWork(context)
        val req = OneTimeWorkRequestBuilder<UploadQueueWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            req
        )
        prefs.edit().putLong(PREF_KEY_NEXT_SCHEDULED_AT_MS, desiredAtMs).apply()
    }

    private fun hasRunningWork(context: Context): Boolean {
        val infos = runCatching { WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrNull()
            ?: return false
        return infos.any { it.state == WorkInfo.State.RUNNING }
    }

    private fun hasPendingWork(context: Context): Boolean {
        val infos = runCatching { WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrNull()
            ?: return false
        return infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    }

    private fun cancelPendingWork(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val infos = runCatching { workManager.getWorkInfosForUniqueWork(WORK_NAME).get() }.getOrNull().orEmpty()
        infos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            .forEach { workManager.cancelWorkById(it.id) }
        context.getSharedPreferences("app", Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_KEY_NEXT_SCHEDULED_AT_MS)
            .apply()
    }

    private const val WORK_NAME = "daily_upload_queue_worker"
    private const val RECOVERY_WORK_NAME = "daily_upload_queue_recovery"
    private const val PREF_KEY_NEXT_SCHEDULED_AT_MS = "upload_queue_next_scheduled_at_ms"
}

private fun retryDelayMs(attemptNumber: Int): Long {
    val backoffSec = (30L * (1L shl (attemptNumber - 1).coerceAtMost(6))).coerceAtMost(queueMaxBackoffSec)
    return backoffSec * 1000L
}

private fun isAutoRetryState(status: String): Boolean {
    return status == UploadQueueStatus.WAITING ||
        status == UploadQueueStatus.WAITING_FOR_NETWORK ||
        status == UploadQueueStatus.WAITING_FOR_SECURE_NETWORK ||
        status == UploadQueueStatus.FAILED_TRANSIENT
}

private fun queueHasStableNetwork(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun queueNetworkSnapshotMeta(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return "activeNetwork=false;capabilities=false;reason=no_connectivity_manager"
    val network = cm.activeNetwork ?: return "activeNetwork=false;capabilities=false;reason=no_active_network"
    val caps = cm.getNetworkCapabilities(network)
        ?: return "activeNetwork=true;capabilities=false;reason=no_capabilities"
    val transports = mutableListOf<String>()
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "wifi"
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "cellular"
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "ethernet"
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports += "vpn"
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports += "bluetooth"
    val transport = if (transports.isEmpty()) "unknown" else transports.joinToString("|")
    return "activeNetwork=true;capabilities=true;internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)};validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)};metered=${!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)};transport=$transport"
}
