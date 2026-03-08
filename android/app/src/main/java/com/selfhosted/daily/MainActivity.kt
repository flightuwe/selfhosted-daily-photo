package com.selfhosted.daily

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AppTab { CAMERA, FEED, CALENDAR, CHAT, PROFILE }
enum class AuthMode { LOGIN, REGISTER }

data class User(
    val id: Long,
    val username: String,
    val isAdmin: Boolean,
    val favoriteColor: String = "#1F5FBF",
    val chatPushEnabled: Boolean = false
)
data class MeResponse(val user: User)
data class ProfileUpdateRequest(val username: String, val favoriteColor: String)
data class PreferencesUpdateRequest(val chatPushEnabled: Boolean)
data class AuthResponse(val token: String, val user: User)
data class LoginRequest(val username: String, val password: String)
data class InviteCodeRequest(val inviteCode: String)
data class InviteRegisterRequest(val inviteCode: String, val username: String, val password: String)
data class InviteOwner(val id: Long, val username: String, val favoriteColor: String = "#1F5FBF")
data class InvitePreviewResponse(val inviteCode: String, val inviter: InviteOwner)
data class InviteCodeResponse(val inviteCode: String)
data class DeviceTokenRequest(val token: String)
data class PasswordChangeRequest(val currentPassword: String, val newPassword: String)
data class ChatMessageRequest(val body: String)
data class PromptPhoto(
    val id: Long,
    val day: String,
    val promptOnly: Boolean,
    val caption: String?,
    val url: String,
    val secondUrl: String? = null,
    val createdAt: String
)
data class PromptResponse(
    val day: String,
    val canUpload: Boolean,
    val triggered: String? = null,
    val hasPosted: Boolean = false,
    val ownPhoto: PromptPhoto? = null,
    val triggerSource: String? = null,
    val requestedByUser: String? = null
)
data class PromptMeta(
    val day: String = "",
    val triggeredAt: String? = null,
    val uploadUntil: String? = null,
    val triggerSource: String? = null,
    val requestedByUser: String? = null
)
data class FeedItem(
    val isLate: Boolean = false,
    val photo: PromptPhoto,
    val user: User,
    val reactions: List<ReactionCount>? = null,
    val comments: List<PhotoCommentItem>? = null,
    val triggerSource: String? = null,
    val requestedByUser: String? = null
)
data class MonthlyReliableUser(
    val id: Long,
    val username: String,
    val favoriteColor: String = "#1F5FBF",
    val count: Long
)
data class MonthlySpontaneousMoment(
    val day: String,
    val userId: Long,
    val username: String,
    val minutesAfterTrigger: Long,
    val createdAt: String
)
data class MonthlyRecap(
    val month: String,
    val monthLabel: String,
    val yourMoments: Long,
    val mostReliableUser: MonthlyReliableUser? = null,
    val topSpontaneous: List<MonthlySpontaneousMoment> = emptyList()
)
data class FeedResponse(
    val items: List<FeedItem>,
    val day: String? = null,
    val triggeredAt: String? = null,
    val uploadUntil: String? = null,
    val triggerSource: String? = null,
    val requestedByUser: String? = null,
    val monthRecap: MonthlyRecap? = null
)
data class DayListResponse(val items: List<String>)
data class MyPhotoResponse(val items: List<PromptPhoto>)
data class ChatItem(val id: Long, val body: String, val createdAt: String, val user: User)
data class ChatResponse(val items: List<ChatItem>)
data class ReactionCount(val emoji: String, val count: Long)
data class PhotoCommentItem(val id: Long, val body: String, val createdAt: String, val user: User)
data class PhotoInteractionsResponse(
    val photoId: Long,
    val reactions: List<ReactionCount> = emptyList(),
    val myReaction: String = "",
    val comments: List<PhotoCommentItem> = emptyList()
)
data class PhotoReactionRequest(val emoji: String)
data class PhotoCommentRequest(val body: String)
data class SpecialMomentStatus(
    val canRequest: Boolean,
    val requestedThisWeek: Boolean,
    val remainingSeconds: Long,
    val nextAllowedAt: String? = null,
    val lastRequestedAt: String? = null
)
data class UpdateInfo(val latestVersion: String, val releaseUrl: String, val apkUrl: String?)
data class HealthResponse(val ok: Boolean, val version: String = "unknown", val provider: String = "unknown")
data class PromptRulesResponse(
    val promptWindowStartHour: Int,
    val promptWindowEndHour: Int,
    val uploadWindowMinutes: Int,
    val maxUploadBytes: Long,
    val timezone: String
)

interface Api {
    @GET("health")
    suspend fun health(): HealthResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("auth/register/preview")
    suspend fun previewInvite(@Body body: InviteCodeRequest): InvitePreviewResponse

    @POST("auth/register/confirm")
    suspend fun registerWithInvite(@Body body: InviteRegisterRequest): AuthResponse

    @GET("me")
    suspend fun me(@Header("Authorization") token: String): MeResponse

    @GET("me/invite")
    suspend fun myInviteCode(@Header("Authorization") token: String): InviteCodeResponse

    @POST("me/invite/roll")
    suspend fun rollInviteCode(@Header("Authorization") token: String): InviteCodeResponse

    @PUT("me/profile")
    suspend fun updateProfile(
        @Header("Authorization") token: String,
        @Body body: ProfileUpdateRequest
    ): MeResponse

    @PUT("me/preferences")
    suspend fun updatePreferences(
        @Header("Authorization") token: String,
        @Body body: PreferencesUpdateRequest
    ): MeResponse

    @GET("prompt/current")
    suspend fun prompt(@Header("Authorization") token: String): PromptResponse

    @GET("prompt/rules")
    suspend fun promptRules(@Header("Authorization") token: String): PromptRulesResponse

    @GET("moment/special/status")
    suspend fun specialMomentStatus(@Header("Authorization") token: String): SpecialMomentStatus

    @POST("moment/special/request")
    suspend fun requestSpecialMoment(@Header("Authorization") token: String)

    @GET("feed")
    suspend fun feed(@Header("Authorization") token: String, @Query("day") day: String): FeedResponse

    @GET("feed/days")
    suspend fun feedDays(@Header("Authorization") token: String): DayListResponse

    @GET("me/photos")
    suspend fun myPhotos(@Header("Authorization") token: String): MyPhotoResponse

    @PUT("me/password")
    suspend fun changePassword(
        @Header("Authorization") token: String,
        @Body body: PasswordChangeRequest
    )

    @POST("devices")
    suspend fun registerDevice(
        @Header("Authorization") token: String,
        @Body body: DeviceTokenRequest
    )

    @Multipart
    @POST("uploads")
    suspend fun upload(
        @Header("Authorization") token: String,
        @Part photo: MultipartBody.Part,
        @Part("kind") kind: RequestBody
    )

    @Multipart
    @POST("uploads/dual")
    suspend fun uploadDual(
        @Header("Authorization") token: String,
        @Part photoBack: MultipartBody.Part,
        @Part photoFront: MultipartBody.Part,
        @Part("kind") kind: RequestBody
    )

    @GET("chat")
    suspend fun chat(@Header("Authorization") token: String): ChatResponse

    @POST("chat")
    suspend fun sendChat(@Header("Authorization") token: String, @Body body: ChatMessageRequest)

    @GET("photos/{id}/interactions")
    suspend fun photoInteractions(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): PhotoInteractionsResponse

    @POST("photos/{id}/reaction")
    suspend fun reactPhoto(
        @Header("Authorization") token: String,
        @Path("id") id: Long,
        @Body body: PhotoReactionRequest
    ): PhotoInteractionsResponse

    @POST("photos/{id}/comments")
    suspend fun commentPhoto(
        @Header("Authorization") token: String,
        @Path("id") id: Long,
        @Body body: PhotoCommentRequest
    ): PhotoInteractionsResponse
}

class AppRepo(private val api: Api, private val context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val maxUploadDimensionPx = 1600

    fun token(): String = prefs.getString("token", "") ?: ""

    fun saveToken(token: String) {
        prefs.edit().putString("token", token).apply()
    }

    fun clearToken() {
        prefs.edit().remove("token").apply()
        UploadQueueManager.clear(context)
    }

    fun uploadQueue(): List<QueuedUploadItem> = UploadQueueManager.list(context)

    fun syncUploadQueueScheduler() {
        UploadQueueScheduler.sync(context)
    }

    fun retryUploadQueueItem(id: String): Boolean {
        val ok = UploadQueueManager.markWaiting(context, id)
        if (ok) UploadQueueScheduler.enqueueNow(context)
        return ok
    }

    fun isDarkMode(): Boolean = prefs.getBoolean("dark_mode", false)

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean("dark_mode", enabled).apply()
    }

    fun isOledMode(): Boolean = prefs.getBoolean("oled_mode", false)

    fun setOledMode(enabled: Boolean) {
        prefs.edit().putBoolean("oled_mode", enabled).apply()
    }

    fun uploadQuality(): Int = prefs.getInt("upload_quality", 82).coerceIn(45, 95)

    fun setUploadQuality(value: Int) {
        prefs.edit().putInt("upload_quality", value.coerceIn(45, 95)).apply()
    }

    private fun lastSyncedDeviceToken(): String = prefs.getString("last_synced_device_token", "") ?: ""
    private fun lastSyncedDeviceTokenAt(): Long = prefs.getLong("last_synced_device_token_at", 0L)

    private fun setLastSyncedDeviceToken(token: String) {
        prefs.edit()
            .putString("last_synced_device_token", token)
            .putLong("last_synced_device_token_at", System.currentTimeMillis())
            .apply()
    }

    fun seenPromptMarker(): String = prefs.getString("seen_prompt_marker", "") ?: ""

    fun setSeenPromptMarker(marker: String) {
        prefs.edit().putString("seen_prompt_marker", marker).apply()
    }

    fun autoUpdateEnabled(): Boolean = prefs.getBoolean("auto_update_enabled", false)

    fun setAutoUpdateEnabled(enabled: Boolean) {
        UpdateCheckScheduler.setEnabled(context, enabled)
    }

    fun syncAutoUpdateScheduler() {
        UpdateCheckScheduler.syncFromPrefs(context)
    }

    fun lastSeenOtherChatMillis(): Long = prefs.getLong("chat_seen_other_ms", 0L)

    fun setLastSeenOtherChatMillis(value: Long) {
        prefs.edit().putLong("chat_seen_other_ms", value.coerceAtLeast(0L)).apply()
    }

    fun lastSeenChangelogVersion(): String = prefs.getString("last_seen_changelog_version", "") ?: ""

    fun shouldShowChangelog(currentVersion: String): Boolean {
        if (currentVersion.isBlank()) return false
        return lastSeenChangelogVersion() != currentVersion
    }

    fun markChangelogSeen(currentVersion: String) {
        if (currentVersion.isBlank()) return
        prefs.edit().putString("last_seen_changelog_version", currentVersion).apply()
    }

    suspend fun login(username: String, password: String): User {
        val res = api.login(LoginRequest(username, password))
        saveToken(res.token)
        return res.user
    }

    suspend fun previewInvite(inviteCode: String): InvitePreviewResponse =
        api.previewInvite(InviteCodeRequest(inviteCode.trim()))

    suspend fun registerWithInvite(inviteCode: String, username: String, password: String): User {
        val res = api.registerWithInvite(InviteRegisterRequest(inviteCode.trim(), username, password))
        saveToken(res.token)
        return res.user
    }

    suspend fun health(): HealthResponse = api.health()
    suspend fun me(): User = api.me("Bearer ${token()}").user
    suspend fun myInviteCode(): String = api.myInviteCode("Bearer ${token()}").inviteCode
    suspend fun rollMyInviteCode(): String = api.rollInviteCode("Bearer ${token()}").inviteCode
    suspend fun updateProfile(username: String, favoriteColor: String): User =
        api.updateProfile("Bearer ${token()}", ProfileUpdateRequest(username, favoriteColor)).user

    suspend fun updateChatPushEnabled(enabled: Boolean): User =
        api.updatePreferences("Bearer ${token()}", PreferencesUpdateRequest(enabled)).user

    suspend fun prompt(): PromptResponse = api.prompt("Bearer ${token()}")
    suspend fun promptRules(): PromptRulesResponse = api.promptRules("Bearer ${token()}")
    suspend fun specialMomentStatus(): SpecialMomentStatus = api.specialMomentStatus("Bearer ${token()}")
    suspend fun requestSpecialMoment() { api.requestSpecialMoment("Bearer ${token()}") }

    suspend fun feedByDay(day: String): FeedResponse = api.feed("Bearer ${token()}", day)
    suspend fun feedDays(): List<String> = api.feedDays("Bearer ${token()}").items

    suspend fun myPhotos(): List<PromptPhoto> = api.myPhotos("Bearer ${token()}").items

    suspend fun listChat(): List<ChatItem> = api.chat("Bearer ${token()}").items

    suspend fun sendChat(body: String) {
        api.sendChat("Bearer ${token()}", ChatMessageRequest(body))
    }

    suspend fun photoInteractions(photoId: Long): PhotoInteractionsResponse =
        api.photoInteractions("Bearer ${token()}", photoId)

    suspend fun reactPhoto(photoId: Long, emoji: String): PhotoInteractionsResponse =
        api.reactPhoto("Bearer ${token()}", photoId, PhotoReactionRequest(emoji))

    suspend fun commentPhoto(photoId: Long, body: String): PhotoInteractionsResponse =
        api.commentPhoto("Bearer ${token()}", photoId, PhotoCommentRequest(body))

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        api.changePassword("Bearer ${token()}", PasswordChangeRequest(currentPassword, newPassword))
    }

    suspend fun syncDeviceTokenIfNeeded(force: Boolean = false) {
        if (token().isBlank()) return
        val pending = prefs.getString("pending_fcm_token", "") ?: ""
        val fromFirebase = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull().orEmpty()
        val deviceToken = if (pending.isNotBlank()) pending else fromFirebase
        if (deviceToken.isBlank()) return
        val sameToken = deviceToken == lastSyncedDeviceToken()
        val recentSync = (System.currentTimeMillis() - lastSyncedDeviceTokenAt()) < 6 * 60 * 60 * 1000L
        if (!force && sameToken && recentSync) return

        api.registerDevice("Bearer ${token()}", DeviceTokenRequest(deviceToken))
        setLastSyncedDeviceToken(deviceToken)
        prefs.edit().remove("pending_fcm_token").apply()
    }

    suspend fun upload(uri: Uri, isPrompt: Boolean) {
        val file = copyUriToTemp(uri)
        val part = MultipartBody.Part.createFormData(
            "photo",
            file.name,
            file.asRequestBody("image/*".toMediaTypeOrNull())
        )
        val kind = (if (isPrompt) "prompt" else "extra").toRequestBody("text/plain".toMediaTypeOrNull())
        api.upload("Bearer ${token()}", part, kind)
    }

    suspend fun uploadDual(
        backUri: Uri,
        frontUri: Uri,
        isPrompt: Boolean,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        val backFile = copyUriToTemp(backUri)
        val frontFile = copyUriToTemp(frontUri)
        val totalBytes = (backFile.length() + frontFile.length()).coerceAtLeast(1L)
        var backSent = 0L
        var frontSent = 0L
        fun emit() = onProgress((backSent + frontSent).coerceAtMost(totalBytes), totalBytes)

        val backBody = ProgressRequestBody(
            delegate = backFile.asRequestBody("image/*".toMediaTypeOrNull())
        ) { sent, _ ->
            backSent = sent
            emit()
        }
        val frontBody = ProgressRequestBody(
            delegate = frontFile.asRequestBody("image/*".toMediaTypeOrNull())
        ) { sent, _ ->
            frontSent = sent
            emit()
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
        val kind = (if (isPrompt) "prompt" else "extra").toRequestBody("text/plain".toMediaTypeOrNull())
        emit()
        api.uploadDual("Bearer ${token()}", backPart, frontPart, kind)
        onProgress(totalBytes, totalBytes)
    }

    suspend fun enqueueDualUpload(backUri: Uri, frontUri: Uri, isPrompt: Boolean): QueuedUploadItem {
        val backFile = copyUriToTemp(backUri)
        val frontFile = copyUriToTemp(frontUri)
        val queuedDir = File(context.filesDir, "upload-queue").apply { mkdirs() }
        val backQueued = moveToQueueFile(backFile, queuedDir, "back")
        val frontQueued = moveToQueueFile(frontFile, queuedDir, "front")
        return UploadQueueManager.enqueueFromFiles(
            context = context,
            backPath = backQueued.absolutePath,
            frontPath = frontQueued.absolutePath,
            isPrompt = isPrompt,
            authToken = token()
        )
    }

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? =
        UpdateReleaseChecker.checkForUpdate(currentVersion)

    suspend fun changelogLines(currentVersion: String): List<String> =
        UpdateReleaseChecker.changelogLinesForVersion(currentVersion)

    private fun copyUriToTemp(uri: Uri): File {
        val resolver = context.contentResolver
        val originalName = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        } ?: "upload.jpg"
        val safeBase = originalName.substringBeforeLast(".").ifBlank { "upload" }
        val target = File(context.cacheDir, "${safeBase}_${UUID.randomUUID()}.jpg")
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri).use { input ->
                BitmapFactory.decodeStream(input, null, opts)
            }
            val sample = calculateInSampleSize(opts.outWidth, opts.outHeight, maxUploadDimensionPx)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri).use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            } ?: error("Bild konnte nicht gelesen werden")

            val rotation = resolver.openInputStream(uri).use { input ->
                if (input == null) 0 else exifRotation(input)
            }
            val processed = if (rotation == 0) decoded else rotateBitmap(decoded, rotation)
            if (processed !== decoded) decoded.recycle()
            FileOutputStream(target).use { out ->
                processed.compress(Bitmap.CompressFormat.JPEG, uploadQuality(), out)
            }
            processed.recycle()
            target
        }.getOrElse {
            val fallback = File(context.cacheDir, "${safeBase}_${UUID.randomUUID()}_raw")
            resolver.openInputStream(uri).use { input ->
                FileOutputStream(fallback).use { out ->
                    input?.copyTo(out)
                }
            }
            fallback
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w > maxSide || h > maxSide) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun moveToQueueFile(source: File, dir: File, suffix: String): File {
        val target = File(dir, "${System.currentTimeMillis()}_${UUID.randomUUID()}_$suffix.jpg")
        if (!source.exists()) throw IOException("Quelldatei fehlt fuer Queue")
        if (source.renameTo(target)) return target
        runCatching {
            source.inputStream().use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            source.delete()
            return target
        }
        throw IOException("Queue-Datei konnte nicht gespeichert werden")
    }

    private fun exifRotation(input: java.io.InputStream): Int {
        return when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}

fun isVersionNewer(latest: String, current: String): Boolean {
    fun parse(v: String): List<Int> = v.split(".").mapNotNull { it.trim().toIntOrNull() }
    val a = parse(latest)
    val b = parse(current)
    val max = maxOf(a.size, b.size)
    for (i in 0 until max) {
        val av = a.getOrElse(i) { 0 }
        val bv = b.getOrElse(i) { 0 }
        if (av > bv) return true
        if (av < bv) return false
    }
    return false
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

data class UiState(
    val token: String = "",
    val user: User? = null,
    val myInviteCode: String = "",
    val invitePreview: InvitePreviewResponse? = null,
    val prompt: PromptResponse? = null,
    val feed: List<FeedItem> = emptyList(),
    val feedDays: List<String> = emptyList(),
    val feedByDay: Map<String, List<FeedItem>> = emptyMap(),
    val monthRecapByDay: Map<String, MonthlyRecap> = emptyMap(),
    val promptMetaByDay: Map<String, PromptMeta> = emptyMap(),
    val calendarDays: List<String> = emptyList(),
    val feedFocusDay: String? = null,
    val feedPaging: Boolean = false,
    val feedTodayLocked: Boolean = false,
    val chatHasOtherMessages: Boolean = true,
    val chatHasUnreadMessages: Boolean = false,
    val photos: List<PromptPhoto> = emptyList(),
    val chat: List<ChatItem> = emptyList(),
    val uploadQueue: List<QueuedUploadItem> = emptyList(),
    val photoInteractions: PhotoInteractionsResponse? = null,
    val interactionsLoading: Boolean = false,
    val loading: Boolean = false,
    val message: String = "",
    val activeTab: AppTab = AppTab.CAMERA,
    val startupDone: Boolean = false,
    val serverConnected: Boolean = false,
    val serverVersion: String = "unbekannt",
    val pushProvider: String = "unknown",
    val showPromptDialog: Boolean = false,
    val showChangelogDialog: Boolean = false,
    val changelogLines: List<String> = emptyList(),
    val showHelpDialog: Boolean = false,
    val promptRules: PromptRulesResponse? = null,
    val specialMomentStatus: SpecialMomentStatus? = null,
    val updateInfo: UpdateInfo? = null,
    val darkMode: Boolean = false,
    val oledMode: Boolean = false,
    val uploadQuality: Int = 82,
    val autoUpdateEnabled: Boolean = false
)

data class DashboardData(
    val me: User,
    val inviteCode: String,
    val prompt: PromptResponse,
    val rules: PromptRulesResponse,
    val special: SpecialMomentStatus,
    val photos: List<PromptPhoto>,
    val chat: List<ChatItem>,
    val feedDays: List<String>
)

class MainVm(private val repo: AppRepo) : ViewModel() {
    var state by mutableStateOf(
        UiState(
            token = repo.token(),
            darkMode = repo.isDarkMode(),
            oledMode = repo.isOledMode(),
            uploadQuality = repo.uploadQuality(),
            autoUpdateEnabled = repo.autoUpdateEnabled()
        )
    )
        private set

    suspend fun bootstrap() {
        if (state.startupDone) return
        state = state.copy(startupDone = false)
        repo.syncAutoUpdateScheduler()
        repo.syncUploadQueueScheduler()
        val started = System.currentTimeMillis()
        val health = runCatching { repo.health() }.getOrNull()
        val elapsed = System.currentTimeMillis() - started
        if (elapsed < 900) {
            delay(900 - elapsed)
        }
        val changelogLines = runCatching { repo.changelogLines(BuildConfig.VERSION_NAME) }
            .getOrDefault(emptyList())
        state = state.copy(
            startupDone = true,
            serverConnected = health?.ok == true,
            serverVersion = health?.version ?: "nicht erreichbar",
            pushProvider = health?.provider ?: "unknown",
            showChangelogDialog = repo.shouldShowChangelog(BuildConfig.VERSION_NAME),
            changelogLines = changelogLines,
            uploadQueue = repo.uploadQueue(),
            message = if (health?.ok == true) "" else "Server nicht erreichbar"
        )
    }

    suspend fun login(username: String, password: String) {
        state = state.copy(loading = true, message = "")
        try {
            val user = repo.login(username, password)
            state = state.copy(user = user, token = repo.token(), loading = false, invitePreview = null)
            runCatching { repo.syncDeviceTokenIfNeeded(force = true) }
            refreshAll()
        } catch (t: Throwable) {
            state = state.copy(loading = false, message = apiError(t, "Login fehlgeschlagen"))
        }
    }

    suspend fun previewInvite(inviteCode: String) {
        state = state.copy(loading = true, message = "")
        runCatching { repo.previewInvite(inviteCode) }
            .onSuccess {
                state = state.copy(loading = false, invitePreview = it, message = "Code gueltig: @${it.inviter.username}")
            }
            .onFailure {
                state = state.copy(loading = false, invitePreview = null, message = apiError(it, "Invite-Code ungueltig"))
            }
    }

    fun clearInvitePreview() {
        state = state.copy(invitePreview = null)
    }

    suspend fun registerWithInvite(inviteCode: String, username: String, password: String) {
        state = state.copy(loading = true, message = "")
        runCatching { repo.registerWithInvite(inviteCode, username, password) }
            .onSuccess { user ->
                state = state.copy(user = user, token = repo.token(), loading = false, invitePreview = null)
                runCatching { repo.syncDeviceTokenIfNeeded(force = true) }
                refreshAll()
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Registrierung fehlgeschlagen"))
            }
    }

    suspend fun rollInviteCode() {
        if (repo.token().isBlank()) return
        state = state.copy(loading = true, message = "")
        runCatching { repo.rollMyInviteCode() }
            .onSuccess { state = state.copy(loading = false, myInviteCode = it, message = "Invite-Code erneuert") }
            .onFailure { state = state.copy(loading = false, message = apiError(it, "Invite-Code erneuern fehlgeschlagen")) }
    }

    fun logout() {
        repo.clearToken()
        state = UiState(
            startupDone = true,
            serverConnected = state.serverConnected,
            serverVersion = state.serverVersion,
            pushProvider = state.pushProvider,
            darkMode = state.darkMode,
            oledMode = state.oledMode,
            uploadQuality = state.uploadQuality,
            autoUpdateEnabled = repo.autoUpdateEnabled(),
            invitePreview = null
        )
    }

    fun setTab(tab: AppTab) {
        if (tab == AppTab.CHAT) {
            val latestOther = latestOtherChatMillis(state.chat, state.user?.id)
            if (latestOther > 0L) {
                repo.setLastSeenOtherChatMillis(latestOther)
            }
            state = state.copy(
                activeTab = tab,
                chatHasOtherMessages = true,
                chatHasUnreadMessages = false
            )
            return
        }
        state = state.copy(activeTab = tab)
    }

    suspend fun jumpToDay(day: String) {
        loadFeedWindow(day, around = 5)
        state = state.copy(activeTab = AppTab.FEED)
    }

    suspend fun refreshAll() {
        if (repo.token().isBlank()) return
        state = state.copy(loading = true)
        runCatching {
            repo.syncDeviceTokenIfNeeded()
            val me = repo.me()
            val inviteCode = repo.myInviteCode()
            val prompt = repo.prompt()
            val rules = repo.promptRules()
            val special = repo.specialMomentStatus()
            val photos = repo.myPhotos()
            val chat = repo.listChat()
            val feedDays = repo.feedDays()
            DashboardData(me, inviteCode, prompt, rules, special, photos, chat, feedDays)
        }.onSuccess { payload ->
            val me = payload.me
            val inviteCode = payload.inviteCode
            val prompt = payload.prompt
            val rules = payload.rules
            val special = payload.special
            val photos = payload.photos
            val chat = payload.chat
            val calendarDays = payload.feedDays
            val latestOtherChat = latestOtherChatMillis(chat, me.id)
            val seenChat = repo.lastSeenOtherChatMillis()
            var hasUnreadChat = latestOtherChat > seenChat
            if (state.activeTab == AppTab.CHAT && latestOtherChat > 0L) {
                repo.setLastSeenOtherChatMillis(latestOtherChat)
                hasUnreadChat = false
            }
            val marker = "${prompt.day}:${prompt.triggered ?: ""}"
            val shouldPopup = prompt.canUpload && !prompt.triggered.isNullOrBlank() && !prompt.hasPosted && marker != repo.seenPromptMarker()
            if (shouldPopup) repo.setSeenPromptMarker(marker)

            state = state.copy(
                user = me,
                myInviteCode = inviteCode,
                prompt = prompt,
                promptRules = rules,
                specialMomentStatus = special,
                photos = photos,
                chat = chat,
                chatHasOtherMessages = true,
                chatHasUnreadMessages = hasUnreadChat,
                calendarDays = calendarDays,
                uploadQueue = repo.uploadQueue(),
                loading = false,
                showPromptDialog = state.showPromptDialog || shouldPopup,
                message = ""
            )
            val focus = state.feedFocusDay
            val anchor = if (focus != null && calendarDays.contains(focus)) focus else prompt.day
            loadFeedWindow(anchor, around = 3)
        }.onFailure {
            state = state.copy(loading = false, message = apiError(it, "Laden fehlgeschlagen"))
        }
    }

    suspend fun loadOlderFeedDays(count: Int = 3) {
        if (state.feedPaging || state.calendarDays.isEmpty()) return
        val base = state.feedDays.lastOrNull() ?: return
        val all = state.calendarDays
        val idx = all.indexOf(base)
        if (idx < 0) return
        val newDays = all.drop(idx + 1).take(count)
        if (newDays.isEmpty()) return
        state = state.copy(feedPaging = true)
        val newMap = state.feedByDay.toMutableMap()
        val newPromptMap = state.promptMetaByDay.toMutableMap()
        val newRecapMap = state.monthRecapByDay.toMutableMap()
        for (day in newDays) {
            if (!newMap.containsKey(day)) {
                val fetched = fetchDaySafe(day)
                newMap[day] = fetched.items
                newPromptMap[day] = fetched.meta
                fetched.monthRecap?.let { newRecapMap[day] = it }
            }
        }
        state = state.copy(feedDays = state.feedDays + newDays, feedByDay = newMap, monthRecapByDay = newRecapMap, promptMetaByDay = newPromptMap, feedPaging = false)
    }

    suspend fun loadNewerFeedDays(count: Int = 3) {
        if (state.feedPaging || state.calendarDays.isEmpty()) return
        val base = state.feedDays.firstOrNull() ?: return
        val all = state.calendarDays
        val idx = all.indexOf(base)
        if (idx <= 0) return
        val start = maxOf(0, idx - count)
        val prependDays = all.subList(start, idx)
        if (prependDays.isEmpty()) return
        state = state.copy(feedPaging = true)
        val newMap = state.feedByDay.toMutableMap()
        val newPromptMap = state.promptMetaByDay.toMutableMap()
        val newRecapMap = state.monthRecapByDay.toMutableMap()
        for (day in prependDays) {
            if (!newMap.containsKey(day)) {
                val fetched = fetchDaySafe(day)
                newMap[day] = fetched.items
                newPromptMap[day] = fetched.meta
                fetched.monthRecap?.let { newRecapMap[day] = it }
            }
        }
        state = state.copy(feedDays = prependDays + state.feedDays, feedByDay = newMap, monthRecapByDay = newRecapMap, promptMetaByDay = newPromptMap, feedPaging = false)
    }

    private suspend fun loadFeedWindow(anchorDay: String, around: Int) {
        val fetchedDays = if (state.calendarDays.isEmpty()) {
            runCatching { repo.feedDays() }.getOrDefault(emptyList())
        } else {
            state.calendarDays
        }
        if (state.calendarDays.isEmpty() && fetchedDays.isNotEmpty()) {
            state = state.copy(calendarDays = fetchedDays)
        }
        val allDays = if (state.calendarDays.isNotEmpty()) state.calendarDays else fetchedDays
        if (allDays.isEmpty()) {
            state = state.copy(
                feedDays = emptyList(),
                feedByDay = emptyMap(),
                monthRecapByDay = emptyMap(),
                promptMetaByDay = emptyMap(),
                feed = emptyList(),
                feedTodayLocked = state.prompt?.hasPosted == false,
                feedFocusDay = state.prompt?.day
            )
            return
        }
        val target = if (allDays.contains(anchorDay)) anchorDay else allDays.first()
        val idx = allDays.indexOf(target)
        val start = maxOf(0, idx - around)
        val end = minOf(allDays.lastIndex, idx + around)
        val days = allDays.subList(start, end + 1)
        val map = mutableMapOf<String, List<FeedItem>>()
        val monthRecapMap = mutableMapOf<String, MonthlyRecap>()
        val promptMap = mutableMapOf<String, PromptMeta>()
        for (day in days.distinct()) {
            val fetched = fetchDaySafe(day)
            map[day] = fetched.items
            promptMap[day] = fetched.meta
            fetched.monthRecap?.let { monthRecapMap[day] = it }
        }
        val today = state.prompt?.day ?: LocalDate.now().toString()
        val postedToday = state.prompt?.hasPosted == true
        val hasVisibleTodayFeed = map[today].orEmpty().isNotEmpty()
        val todayLocked = !postedToday && !hasVisibleTodayFeed
        state = state.copy(
            feedDays = days.distinct(),
            feedByDay = map,
            monthRecapByDay = monthRecapMap,
            promptMetaByDay = promptMap,
            feed = map[today] ?: emptyList(),
            feedTodayLocked = todayLocked,
            feedFocusDay = target
        )
    }

    private data class DayFetchResult(val items: List<FeedItem>, val meta: PromptMeta, val monthRecap: MonthlyRecap? = null)

    private suspend fun fetchDaySafe(day: String): DayFetchResult {
        return try {
            val res = repo.feedByDay(day)
            DayFetchResult(
                items = res.items,
                meta = PromptMeta(
                    day = res.day ?: day,
                    triggeredAt = res.triggeredAt,
                    uploadUntil = res.uploadUntil,
                    triggerSource = res.triggerSource,
                    requestedByUser = res.requestedByUser
                ),
                monthRecap = res.monthRecap
            )
        } catch (e: HttpException) {
            if (e.code() == 403) {
                DayFetchResult(items = emptyList(), meta = PromptMeta(day = day), monthRecap = null)
            } else {
                throw e
            }
        }
    }

    suspend fun uploadDual(
        back: Uri,
        front: Uri,
        asPrompt: Boolean,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean {
        state = state.copy(loading = true)
        return try {
            repo.uploadDual(back, front, asPrompt, onProgress)
            state = state.copy(loading = false, message = "Fotos gepostet")
            refreshAll()
            true
        } catch (t: Throwable) {
            state = state.copy(loading = false, message = apiError(t, "Upload fehlgeschlagen"))
            false
        }
    }

    suspend fun enqueueDualUpload(back: Uri, front: Uri, asPrompt: Boolean): Boolean {
        state = state.copy(loading = true)
        return runCatching {
            repo.enqueueDualUpload(back, front, asPrompt)
        }.onSuccess {
            repo.syncUploadQueueScheduler()
            state = state.copy(
                loading = false,
                uploadQueue = repo.uploadQueue(),
                message = "Upload in Warteschlange. Wird im Hintergrund hochgeladen."
            )
        }.onFailure {
            state = state.copy(loading = false, message = apiError(it, "Upload-Queue fehlgeschlagen"))
        }.isSuccess
    }

    fun retryQueuedUpload(id: String) {
        val ok = repo.retryUploadQueueItem(id)
        if (ok) {
            state = state.copy(uploadQueue = repo.uploadQueue(), message = "Upload erneut geplant")
        }
    }

    suspend fun sendChat(body: String) {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return
        runCatching { repo.sendChat(trimmed) }
            .onSuccess { refreshAll() }
            .onFailure { state = state.copy(message = apiError(it, "Chat senden fehlgeschlagen")) }
    }

    suspend fun loadPhotoInteractions(photoId: Long) {
        if (photoId <= 0) return
        state = state.copy(interactionsLoading = true)
        runCatching { repo.photoInteractions(photoId) }
            .onSuccess { state = state.copy(interactionsLoading = false, photoInteractions = it) }
            .onFailure { state = state.copy(interactionsLoading = false, message = apiError(it, "Interaktionen laden fehlgeschlagen")) }
    }

    suspend fun reactPhoto(photoId: Long, emoji: String) {
        if (photoId <= 0 || emoji.isBlank()) return
        state = state.copy(interactionsLoading = true)
        runCatching { repo.reactPhoto(photoId, emoji) }
            .onSuccess { state = state.copy(interactionsLoading = false, photoInteractions = it) }
            .onFailure { state = state.copy(interactionsLoading = false, message = apiError(it, "Reaktion fehlgeschlagen")) }
    }

    suspend fun commentPhoto(photoId: Long, body: String) {
        val trimmed = body.trim()
        if (photoId <= 0 || trimmed.isBlank()) return
        state = state.copy(interactionsLoading = true)
        runCatching { repo.commentPhoto(photoId, trimmed) }
            .onSuccess { state = state.copy(interactionsLoading = false, photoInteractions = it) }
            .onFailure { state = state.copy(interactionsLoading = false, message = apiError(it, "Kommentar fehlgeschlagen")) }
    }

    fun clearPhotoInteractions() {
        state = state.copy(photoInteractions = null, interactionsLoading = false)
    }

    suspend fun changePassword(current: String, next: String) {
        state = state.copy(loading = true)
        runCatching { repo.changePassword(current, next) }
            .onSuccess { state = state.copy(loading = false, message = "Passwort geaendert") }
            .onFailure { state = state.copy(loading = false, message = apiError(it, "Passwort aendern fehlgeschlagen")) }
    }

    suspend fun checkForUpdate() {
        state = state.copy(loading = true)
        runCatching { repo.checkForUpdate(BuildConfig.VERSION_NAME) }
            .onSuccess { update ->
                state = if (update != null) {
                    state.copy(loading = false, updateInfo = update, message = "Neue Version ${update.latestVersion} gefunden")
                } else {
                    state.copy(loading = false, message = "Du nutzt bereits die neueste Version")
                }
            }
            .onFailure { state = state.copy(loading = false, message = apiError(it, "Update-Pruefung fehlgeschlagen")) }
    }

    suspend fun checkConnection() {
        state = state.copy(loading = true)
        runCatching { repo.health() }
            .onSuccess { health ->
                state = state.copy(
                    loading = false,
                    serverConnected = health.ok,
                    serverVersion = health.version,
                    pushProvider = health.provider,
                    message = if (health.ok) "Verbindung erfolgreich geprueft" else "Server nicht erreichbar"
                )
            }
            .onFailure {
                state = state.copy(
                    loading = false,
                    serverConnected = false,
                    message = apiError(it, "Verbindung pruefen fehlgeschlagen")
                )
            }
    }

    suspend fun requestSpecialMoment() {
        state = state.copy(loading = true)
        runCatching { repo.requestSpecialMoment() }
            .onSuccess {
                state = state.copy(loading = false, message = "Sondermoment ausgelost")
                refreshAll()
            }
            .onFailure { state = state.copy(loading = false, message = apiError(it, "Sondermoment anfordern fehlgeschlagen")) }
    }

    fun dismissPromptDialog() {
        state = state.copy(showPromptDialog = false)
    }

    fun dismissUpdateDialog() {
        state = state.copy(updateInfo = null)
    }

    suspend fun showChangelogDialog() {
        val lines = runCatching { repo.changelogLines(BuildConfig.VERSION_NAME) }
            .getOrDefault(emptyList())
        state = state.copy(
            showChangelogDialog = true,
            changelogLines = if (lines.isNotEmpty()) lines else state.changelogLines
        )
    }

    fun dismissChangelogDialog() {
        repo.markChangelogSeen(BuildConfig.VERSION_NAME)
        state = state.copy(showChangelogDialog = false)
    }

    fun showHelpDialog() {
        state = state.copy(showHelpDialog = true)
    }

    fun dismissHelpDialog() {
        state = state.copy(showHelpDialog = false)
    }

    fun setDarkMode(enabled: Boolean) {
        repo.setDarkMode(enabled)
        if (!enabled && state.oledMode) {
            repo.setOledMode(false)
        }
        state = state.copy(darkMode = repo.isDarkMode(), oledMode = repo.isOledMode())
    }

    fun setOledMode(enabled: Boolean) {
        repo.setOledMode(enabled)
        if (enabled && !repo.isDarkMode()) {
            repo.setDarkMode(true)
        }
        state = state.copy(darkMode = repo.isDarkMode(), oledMode = repo.isOledMode())
    }

    fun setUploadQuality(value: Int) {
        repo.setUploadQuality(value)
        state = state.copy(uploadQuality = repo.uploadQuality())
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        repo.setAutoUpdateEnabled(enabled)
        state = state.copy(autoUpdateEnabled = repo.autoUpdateEnabled())
    }

    suspend fun updateProfile(username: String, favoriteColor: String) {
        state = state.copy(loading = true)
        runCatching { repo.updateProfile(username, favoriteColor) }
            .onSuccess { user ->
                state = state.copy(user = user, loading = false, message = "Profil aktualisiert")
                refreshAll()
            }
            .onFailure { state = state.copy(loading = false, message = apiError(it, "Profil speichern fehlgeschlagen")) }
    }

    suspend fun setChatPushEnabled(enabled: Boolean) {
        state = state.copy(loading = true)
        runCatching { repo.updateChatPushEnabled(enabled) }
            .onSuccess { user ->
                state = state.copy(user = user, loading = false, message = "Chat-Push aktualisiert")
            }
            .onFailure { state = state.copy(loading = false, message = apiError(it, "Chat-Push speichern fehlgeschlagen")) }
    }

    private fun latestOtherChatMillis(items: List<ChatItem>, meId: Long?): Long {
        if (meId == null) return 0L
        var latest = 0L
        for (item in items) {
            if (item.user.id == meId) continue
            val ts = parseChatMillis(item.createdAt)
            if (ts > latest) latest = ts
        }
        return latest
    }

    private fun parseChatMillis(value: String): Long {
        val raw = value.trim()
        if (raw.isBlank()) return 0L
        runCatching { return OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        runCatching {
            val normalized = raw.replace(" ", "T")
            return LocalDateTime.parse(normalized).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return 0L
    }
}

class MainVmFactory(private val repo: AppRepo) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainVm(repo) as T
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val api = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(Api::class.java)

        setContent {
            val vm: MainVm = viewModel(factory = MainVmFactory(AppRepo(api, this)))
            val useDark = vm.state.darkMode
            val useOled = vm.state.oledMode
            val oledColorScheme = darkColorScheme(
                background = Color.Black,
                surface = Color.Black
            )
            MaterialTheme(colorScheme = if (useDark) (if (useOled) oledColorScheme else darkColorScheme()) else lightColorScheme()) {
                AppScreen(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: MainVm) {
    val state = vm.state
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf(AuthMode.LOGIN) }
    var inviteCodeInput by remember { mutableStateOf("") }
    var inviteConfirmed by remember { mutableStateOf(false) }

    var captureUri by remember { mutableStateOf<Uri?>(null) }
    var captureTarget by remember { mutableStateOf<String?>(null) }
    var captureAsPrompt by remember { mutableStateOf(true) }
    var backPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var frontPreviewUri by remember { mutableStateOf<Uri?>(null) }

    var pwCurrent by remember { mutableStateOf("") }
    var pwNext by remember { mutableStateOf("") }
    var profileUsername by remember { mutableStateOf("") }
    var profileColor by remember { mutableStateOf("#1F5FBF") }
    var chatInput by remember { mutableStateOf("") }
    var viewerUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf(0) }
    var viewerPhotoId by remember { mutableStateOf<Long?>(null) }
    var viewerComment by remember { mutableStateOf("") }
    var showSpecialMomentConfirm by remember { mutableStateOf(false) }
    var requestFrontCapture by remember { mutableStateOf(false) }
    var cameraUploading by remember { mutableStateOf(false) }
    var cameraUploadPercent by remember { mutableStateOf(0) }
    var cameraUploadError by remember { mutableStateOf("") }
    var cameraUploadDone by remember { mutableStateOf(false) }
    val feedListState = remember { LazyListState() }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val target = captureTarget
        val shotUri = captureUri
        if (success) {
            when (target) {
                "back" -> {
                    backPreviewUri = shotUri
                    requestFrontCapture = true
                }
                "front" -> {
                    frontPreviewUri = shotUri
                    val back = backPreviewUri
                    val front = shotUri
                    if (back != null && front != null && !cameraUploading) {
                        cameraUploading = true
                        cameraUploadPercent = 0
                        cameraUploadError = ""
                        cameraUploadDone = false
                        val asPrompt = captureAsPrompt
                        scope.launch {
                            val ok = vm.enqueueDualUpload(back, front, asPrompt)
                            cameraUploading = false
                            if (ok) {
                                backPreviewUri = null
                                frontPreviewUri = null
                                cameraUploadPercent = 100
                                cameraUploadDone = true
                                if (asPrompt) vm.setTab(AppTab.FEED)
                            } else {
                                cameraUploadDone = false
                                cameraUploadError = vm.state.message.ifBlank { "Upload fehlgeschlagen" }
                            }
                        }
                    }
                }
            }
        }
        captureUri = null
        captureTarget = null
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    fun openCameraFor(target: String) {
        val uri = createTempImageUri(context)
        captureTarget = target
        captureUri = uri
        cameraLauncher.launch(uri)
    }

    fun startDualCapture(asPrompt: Boolean) {
        captureAsPrompt = asPrompt
        cameraUploadPercent = 0
        cameraUploadError = ""
        cameraUploadDone = false
        openCameraFor("back")
    }

    LaunchedEffect(requestFrontCapture) {
        if (requestFrontCapture) {
            requestFrontCapture = false
            openCameraFor("front")
        }
    }

    LaunchedEffect(Unit) {
        vm.bootstrap()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (!state.startupDone) {
        StartupScreen(
            serverConnected = state.serverConnected,
            serverVersion = state.serverVersion,
            appVersion = BuildConfig.VERSION_NAME,
            pushProvider = state.pushProvider
        )
        return
    }

    LaunchedEffect(state.token) {
        if (state.token.isBlank()) return@LaunchedEffect
        while (true) {
            vm.refreshAll()
            delay(20_000)
        }
    }

    LaunchedEffect(state.user?.id, state.user?.username, state.user?.favoriteColor) {
        val u = state.user ?: return@LaunchedEffect
        profileUsername = u.username
        profileColor = normalizeHexColor(u.favoriteColor)
    }

    LaunchedEffect(inviteCodeInput) {
        if (state.invitePreview != null && normalizeInviteCodeLocal(inviteCodeInput) != state.invitePreview.inviteCode) {
            inviteConfirmed = false
            vm.clearInvitePreview()
        }
    }

    LaunchedEffect(viewerPhotoId) {
        val pid = viewerPhotoId ?: return@LaunchedEffect
        vm.loadPhotoInteractions(pid)
    }

    if (state.showPromptDialog) {
        AlertDialog(
            onDismissRequest = { vm.dismissPromptDialog() },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissPromptDialog()
                    startDualCapture(true)
                }) { Text("Kamera oeffnen") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPromptDialog() }) { Text("Spaeter") }
            },
            title = { Text("Zeit fuer deinen taeglichen Moment") },
            text = { Text("Nimm jetzt Rueckkamera und Frontkamera auf.") }
        )
    }

    if (showSpecialMomentConfirm) {
        AlertDialog(
            onDismissRequest = { showSpecialMomentConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    showSpecialMomentConfirm = false
                    scope.launch { vm.requestSpecialMoment() }
                }) { Text("Ja, anfordern") }
            },
            dismissButton = {
                TextButton(onClick = { showSpecialMomentConfirm = false }) { Text("Abbrechen") }
            },
            title = { Text("Sondermoment anfordern") },
            text = { Text("Jeder Nutzer kann nur einmal pro Woche einen Sondermoment anfordern. Fortfahren?") }
        )
    }

    state.updateInfo?.let { update ->
        AlertDialog(
            onDismissRequest = { vm.dismissUpdateDialog() },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissUpdateDialog()
                    val target = update.apkUrl ?: update.releaseUrl
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissUpdateDialog() }) { Text("Spaeter") }
            },
            title = { Text("Update verfuegbar") },
            text = { Text("Neue Version ${update.latestVersion}") }
        )
    }

    if (state.showChangelogDialog) {
        val lines = if (state.changelogLines.isNotEmpty()) state.changelogLines else fallbackChangelogLines()
        AlertDialog(
            onDismissRequest = { vm.dismissChangelogDialog() },
            confirmButton = {
                TextButton(onClick = { vm.dismissChangelogDialog() }) { Text("Schliessen") }
            },
            dismissButton = {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/flightuwe/selfhosted-daily-photo")
                        )
                    )
                }) { Text("GitHub") }
            },
            title = { Text("Changelog ${BuildConfig.VERSION_NAME}") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    lines.forEach { line -> Text("- $line") }
                }
            }
        )
    }

    if (state.showHelpDialog) {
        val lines = helpLines()
        AlertDialog(
            onDismissRequest = { vm.dismissHelpDialog() },
            confirmButton = {
                TextButton(onClick = { vm.dismissHelpDialog() }) { Text("Schliessen") }
            },
            title = { Text("Hilfe") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    lines.forEach { line -> Text(line) }
                }
            }
        )
    }

    if (viewerUrls.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                viewerUrls = emptyList()
                viewerIndex = 0
                viewerPhotoId = null
                viewerComment = ""
                vm.clearPhotoInteractions()
            },
            confirmButton = {
                if (viewerUrls.size > 1 && viewerIndex < viewerUrls.lastIndex) {
                    TextButton(onClick = { viewerIndex += 1 }) { Text("Naechstes Bild") }
                } else {
                    TextButton(onClick = {
                        viewerUrls = emptyList()
                        viewerIndex = 0
                        viewerPhotoId = null
                        viewerComment = ""
                        vm.clearPhotoInteractions()
                    }) { Text("Schliessen") }
                }
            },
            dismissButton = {
                if (viewerUrls.size > 1 && viewerIndex > 0) {
                    TextButton(onClick = { viewerIndex -= 1 }) { Text("Vorheriges Bild") }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier.pointerInput(viewerUrls, viewerIndex) {
                            var dragX = 0f
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { _, amount ->
                                    dragX += amount
                                },
                                onDragEnd = {
                                    if (dragX > 80f && viewerIndex > 0) {
                                        viewerIndex -= 1
                                    } else if (dragX < -80f && viewerIndex < viewerUrls.lastIndex) {
                                        viewerIndex += 1
                                    }
                                    dragX = 0f
                                }
                            )
                        }
                    ) {
                        ZoomableViewerImage(url = viewerUrls[viewerIndex])
                    }
                    Text("Unter diesem Bild kannst du reagieren oder kommentieren.")
                    if (viewerPhotoId != null) {
                        val interactions = state.photoInteractions
                        val countByEmoji = interactions?.reactions.orEmpty().associate { it.emoji to it.count }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf("❤️", "👍", "😂", "🔥", "😮").forEach { emoji ->
                                val selected = interactions?.myReaction == emoji
                                Button(
                                    onClick = { scope.launch { vm.reactPhoto(viewerPhotoId ?: 0L, emoji) } },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    val count = countByEmoji[emoji] ?: 0L
                                    Text("${if (selected) "✓" else ""}$emoji $count")
                                }
                            }
                        }
                        OutlinedTextField(
                            value = viewerComment,
                            onValueChange = { viewerComment = it },
                            label = { Text("Kommentar") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                val body = viewerComment
                                if (body.isNotBlank()) {
                                    scope.launch {
                                        vm.commentPhoto(viewerPhotoId ?: 0L, body)
                                        viewerComment = ""
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Kommentieren") }
                        if (state.interactionsLoading) {
                            Text("Interaktionen werden geladen ...")
                        }
                        interactions?.comments?.takeLast(40)?.forEach { item ->
                            Card {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        item.user.username,
                                        fontWeight = FontWeight.SemiBold,
                                        color = parseUserColor(item.user.favoriteColor)
                                    )
                                    Text(item.body)
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    if (state.token.isBlank()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            Text("Daily", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        authMode = AuthMode.LOGIN
                        inviteConfirmed = false
                        vm.clearInvitePreview()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Anmelden") }
                Button(
                    onClick = { authMode = AuthMode.REGISTER },
                    modifier = Modifier.weight(1f)
                ) { Text("Registrieren") }
            }

            if (authMode == AuthMode.LOGIN) {
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Passwort") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { scope.launch { vm.login(username, password) } }, modifier = Modifier.fillMaxWidth()) { Text("Einloggen") }
            } else {
                OutlinedTextField(
                    value = inviteCodeInput,
                    onValueChange = { inviteCodeInput = it.uppercase() },
                    label = { Text("Invite-Code") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { scope.launch { vm.previewInvite(inviteCodeInput) } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Code pruefen") }

                state.invitePreview?.let { preview ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Code von @${preview.inviter.username}", color = parseUserColor(preview.inviter.favoriteColor))
                            Button(
                                onClick = { inviteConfirmed = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Code bestaetigen") }
                        }
                    }
                }

                if (inviteConfirmed) {
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Neuer Benutzername") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Passwort") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            scope.launch {
                                vm.registerWithInvite(inviteCodeInput, username, password)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Registrierung abschliessen") }
                }
            }
            if (state.message.isNotBlank()) Text(state.message, color = Color.Red)
        }
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = state.activeTab == AppTab.CAMERA, onClick = { vm.setTab(AppTab.CAMERA) }, label = { Text("Kamera") }, icon = { Text("U") })
                NavigationBarItem(selected = state.activeTab == AppTab.FEED, onClick = { vm.setTab(AppTab.FEED) }, label = { Text("Feed") }, icon = { Text("T") })
                NavigationBarItem(selected = state.activeTab == AppTab.CALENDAR, onClick = { vm.setTab(AppTab.CALENDAR) }, label = { Text("Kalender") }, icon = { Text("G") })
                NavigationBarItem(
                    selected = state.activeTab == AppTab.CHAT,
                    onClick = { vm.setTab(AppTab.CHAT) },
                    label = { Text("Chat") },
                    icon = {
                        ChatTabIcon(
                            showIndicator = true,
                            unread = state.chatHasUnreadMessages
                        )
                    }
                )
                NavigationBarItem(selected = state.activeTab == AppTab.PROFILE, onClick = { vm.setTab(AppTab.PROFILE) }, label = { Text("Profil") }, icon = { Text("M") })
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp)
        ) {
            when (state.activeTab) {
                AppTab.CAMERA -> CameraTab(
                    prompt = state.prompt,
                    promptRules = state.promptRules,
                    specialMomentStatus = state.specialMomentStatus,
                    backPreviewUri = backPreviewUri,
                    frontPreviewUri = frontPreviewUri,
                    onCapturePrompt = { startDualCapture(true) },
                    onCaptureExtra = { startDualCapture(false) },
                    onRequestSpecialMoment = { showSpecialMomentConfirm = true },
                    onReset = {
                        backPreviewUri = null
                        frontPreviewUri = null
                        cameraUploading = false
                        cameraUploadPercent = 0
                        cameraUploadError = ""
                        cameraUploadDone = false
                    },
                    onRetryUpload = {
                        val back = backPreviewUri
                        val front = frontPreviewUri
                        if (back != null && front != null && !cameraUploading) {
                            cameraUploading = true
                            cameraUploadPercent = 0
                            cameraUploadError = ""
                            cameraUploadDone = false
                            val asPrompt = captureAsPrompt
                            scope.launch {
                                val ok = vm.enqueueDualUpload(back, front, asPrompt)
                                cameraUploading = false
                                if (ok) {
                                    cameraUploadPercent = 100
                                    cameraUploadDone = true
                                    backPreviewUri = null
                                    frontPreviewUri = null
                                    if (asPrompt) vm.setTab(AppTab.FEED)
                                } else {
                                    cameraUploadDone = false
                                    cameraUploadError = vm.state.message.ifBlank { "Upload fehlgeschlagen" }
                                }
                            }
                        }
                    },
                    uploading = cameraUploading,
                    uploadPercent = cameraUploadPercent,
                    uploadDone = cameraUploadDone,
                    uploadError = cameraUploadError,
                    uploadQueue = state.uploadQueue,
                    onRetryQueued = { id -> vm.retryQueuedUpload(id) },
                    onOpenViewer = { urls, photoId ->
                        viewerUrls = urls
                        viewerIndex = 0
                        viewerPhotoId = photoId
                    }
                )

                AppTab.FEED -> FeedTab(
                    prompt = state.prompt,
                    days = state.feedDays,
                    byDay = state.feedByDay,
                    monthRecapByDay = state.monthRecapByDay,
                    promptMetaByDay = state.promptMetaByDay,
                    focusDay = state.feedFocusDay,
                    listState = feedListState,
                    todayLocked = state.feedTodayLocked,
                    paging = state.feedPaging,
                    onTakePhoto = { vm.setTab(AppTab.CAMERA) },
                    onLoadOlder = { scope.launch { vm.loadOlderFeedDays() } },
                    onLoadNewer = { scope.launch { vm.loadNewerFeedDays() } },
                    onOpenViewer = { urls, photoId ->
                        viewerUrls = urls
                        viewerIndex = 0
                        viewerPhotoId = photoId
                    }
                )

                AppTab.CALENDAR -> CalendarTab(
                    days = state.calendarDays,
                    monthRecapByDay = state.monthRecapByDay,
                    promptMetaByDay = state.promptMetaByDay,
                    selected = state.feedFocusDay ?: state.prompt?.day.orEmpty(),
                    onSelect = { day ->
                        scope.launch { vm.jumpToDay(day) }
                    }
                )

                AppTab.CHAT -> ChatTab(
                    items = state.chat,
                    input = chatInput,
                    onInput = { chatInput = it },
                    onSend = {
                        val body = chatInput
                        if (body.isNotBlank()) {
                            scope.launch {
                                vm.sendChat(body)
                                chatInput = ""
                            }
                        }
                    }
                )

                AppTab.PROFILE -> ProfileTab(
                    username = state.user?.username ?: "",
                    inviteCode = state.myInviteCode,
                    streakDays = computePostingStreak(state.photos),
                    promptRules = state.promptRules,
                    photos = state.photos,
                    darkMode = state.darkMode,
                    oledMode = state.oledMode,
                    currentPassword = pwCurrent,
                    newPassword = pwNext,
                    editableUsername = profileUsername,
                    editableColor = profileColor,
                    appVersion = BuildConfig.VERSION_NAME,
                    serverVersion = state.serverVersion,
                    pushProvider = state.pushProvider,
                    apiBaseUrl = BuildConfig.API_BASE_URL,
                    serverConnected = state.serverConnected,
                    uploadQuality = state.uploadQuality,
                    autoUpdateEnabled = state.autoUpdateEnabled,
                    chatPushEnabled = state.user?.chatPushEnabled ?: false,
                    onDarkModeChange = { vm.setDarkMode(it) },
                    onOledModeChange = { vm.setOledMode(it) },
                    onUploadQualityChange = { vm.setUploadQuality(it) },
                    onAutoUpdateEnabledChange = { vm.setAutoUpdateEnabled(it) },
                    onChatPushEnabledChange = { scope.launch { vm.setChatPushEnabled(it) } },
                    onEditableUsernameChange = { profileUsername = it },
                    onEditableColorChange = { profileColor = it },
                    onSaveProfile = {
                        if (profileUsername.trim().length >= 3) {
                            scope.launch { vm.updateProfile(profileUsername, profileColor) }
                        }
                    },
                    onCurrentPasswordChange = { pwCurrent = it },
                    onNewPasswordChange = { pwNext = it },
                    onChangePassword = {
                        if (pwCurrent.isNotBlank() && pwNext.isNotBlank()) {
                            scope.launch {
                                vm.changePassword(pwCurrent, pwNext)
                                pwCurrent = ""
                                pwNext = ""
                            }
                        }
                    },
                    onCheckUpdate = { scope.launch { vm.checkForUpdate() } },
                    onShowChangelog = { scope.launch { vm.showChangelogDialog() } },
                    onShowHelp = { vm.showHelpDialog() },
                    onCheckConnection = { scope.launch { vm.checkConnection() } },
                    onRollInviteCode = { scope.launch { vm.rollInviteCode() } },
                    onShareInviteCode = {
                        val code = state.myInviteCode.trim()
                        if (code.isNotBlank()) {
                            val inviter = state.user?.username?.ifBlank { "ein Mitglied" } ?: "ein Mitglied"
                            val apkUrl = "https://github.com/flightuwe/selfhosted-daily-photo/releases/latest/download/app-release.apk"
                            val shortGuide = "1) APK installieren  2) App oeffnen -> Registrieren  3) Invite-Code eingeben"
                            val text = buildString {
                                appendLine("Daily Invite von @$inviter")
                                appendLine("Invite-Code: $code")
                                appendLine(shortGuide)
                                append("Neueste APK: $apkUrl")
                            }
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(send, "Invite-Code teilen"))
                        }
                    },
                    onLogout = { vm.logout() },
                    onOpenViewer = { urls, photoId ->
                        viewerUrls = urls
                        viewerIndex = 0
                        viewerPhotoId = photoId
                    }
                )
            }

            if (state.loading) {
                Text("Lade...", modifier = Modifier.padding(top = 8.dp))
            }
            if (state.message.isNotBlank()) {
                Text(state.message, modifier = Modifier.padding(top = 8.dp), color = Color(0xFF8B0000))
            }
        }
    }
}

@Composable
fun StartupScreen(serverConnected: Boolean, serverVersion: String, appVersion: String, pushProvider: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Daily Logo",
            modifier = Modifier.size(96.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("Daily", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Text(if (serverConnected) "Server verbunden" else "Server wird geprueft ...")
        Text("Server-Version: $serverVersion")
        Text("Push-Provider: $pushProvider")
        Text("App-Version: $appVersion")
        Spacer(modifier = Modifier.height(12.dp))
        Text("Lade ...")
    }
}

@Composable
fun CameraTab(
    prompt: PromptResponse?,
    promptRules: PromptRulesResponse?,
    specialMomentStatus: SpecialMomentStatus?,
    backPreviewUri: Uri?,
    frontPreviewUri: Uri?,
    onCapturePrompt: () -> Unit,
    onCaptureExtra: () -> Unit,
    onRequestSpecialMoment: () -> Unit,
    onReset: () -> Unit,
    onRetryUpload: () -> Unit,
    uploading: Boolean,
    uploadPercent: Int,
    uploadDone: Boolean,
    uploadError: String,
    uploadQueue: List<QueuedUploadItem>,
    onRetryQueued: (String) -> Unit,
    onOpenViewer: (List<String>, Long?) -> Unit
) {
    val hasPosted = prompt?.hasPosted == true
    val canUpload = prompt?.canUpload == true
    val canSpecial = specialMomentStatus?.canRequest == true
    val specialLabel = if (canSpecial) {
        "Sondermoment anfordern"
    } else {
        val rem = specialMomentStatus?.remainingSeconds ?: 0L
        "Sondermoment schon angefordert, naechster Sondermoment in ${formatRemaining(rem)}"
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Heutiger Moment", style = MaterialTheme.typography.titleLarge)
        Text(prompt?.day ?: "-")
        if (!prompt?.triggered.isNullOrBlank()) {
            Text("Der heutige Moment war um ${formatMomentTime(prompt?.triggered)}.")
        } else {
            Text("Der heutige Moment ist noch nicht gekommen.")
        }
        if (promptRules != null) {
            Text("Zeitfenster heute: ${promptRules.promptWindowStartHour}:00-${promptRules.promptWindowEndHour}:00")
        }

        if (hasPosted) {
            Text("Du hast heute gepostet.", fontWeight = FontWeight.Bold)
            val ownUrls = listOfNotNull(prompt?.ownPhoto?.url, prompt?.ownPhoto?.secondUrl)
            if (ownUrls.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ownUrls.forEach { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "Mein heutiges Foto",
                            modifier = Modifier
                                .weight(1f)
                                .height(220.dp)
                                .clickable { onOpenViewer(ownUrls, prompt?.ownPhoto?.id) },
                                contentScale = ContentScale.Crop
                            )
                    }
                }
            }
            Button(onClick = onCaptureExtra, modifier = Modifier.fillMaxWidth()) { Text("Weitere Bilder hinzufuegen") }
            SpecialMomentActionButton(
                text = specialLabel,
                onClick = onRequestSpecialMoment,
                enabled = canSpecial,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text("Heute sind zwei Fotos noetig: Rueckkamera und Frontkamera.")
            if (prompt?.triggered.isNullOrBlank()) {
                Text("Der Moment ist noch nicht gestartet. Du kannst trotzdem schon posten.")
            } else if (canUpload) {
                Text("Momentfenster gerade aktiv.")
            } else {
                Text("Momentfenster vorbei. Du kannst trotzdem spaet posten.")
            }

            if (backPreviewUri == null) {
                Button(onClick = onCapturePrompt, modifier = Modifier.fillMaxWidth()) { Text("Tagesmoment aufnehmen") }
                SpecialMomentActionButton(
                    text = specialLabel,
                    onClick = onRequestSpecialMoment,
                    enabled = canSpecial,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text("Rueckkamera aufgenommen")
                AsyncImage(
                    model = backPreviewUri,
                    contentDescription = "Rueckkamera",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentScale = ContentScale.Crop
                )

                if (frontPreviewUri == null) {
                    Text("Jetzt wird Frontkamera geoeffnet und danach automatisch gepostet.")
                    Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Neu starten") }
                } else {
                    Text("Frontkamera aufgenommen")
                    AsyncImage(
                        model = frontPreviewUri,
                        contentDescription = "Frontkamera",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentScale = ContentScale.Crop
                    )
                    if (uploading) {
                        Text("Upload laeuft im Hintergrund ... $uploadPercent%")
                        LinearProgressIndicator(
                            progress = uploadPercent / 100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Du kannst den Tab wechseln oder die App schliessen. Die Queue versucht den Upload automatisch erneut.")
                    } else if (uploadDone) {
                        Text("Upload wurde zur Queue hinzugefuegt.")
                    } else if (uploadError.isNotBlank()) {
                        Text("Upload fehlgeschlagen: $uploadError", color = Color(0xFF8B0000))
                        Button(onClick = onRetryUpload, modifier = Modifier.fillMaxWidth()) { Text("Upload erneut versuchen") }
                    } else {
                        Text("Bereit fuer Upload.")
                    }
                    Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Erneut aufnehmen") }
                }
            }
        }

        val queueItems = uploadQueue.take(6)
        if (queueItems.isNotEmpty()) {
            Text("Upload-Queue", style = MaterialTheme.typography.titleMedium)
            queueItems.forEach { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val kindLabel = if (item.isPrompt) "Tagesmoment" else "Extra"
                        Text("$kindLabel - ${queueStatusLabel(item.status)}", fontWeight = FontWeight.SemiBold)
                        Text("Versuche: ${item.attempts}")
                        if (item.lastError.isNotBlank()) {
                            Text(item.lastError, color = Color(0xFF8B0000), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (item.status == UploadQueueStatus.FAILED) {
                            Button(onClick = { onRetryQueued(item.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Erneut versuchen")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecialMomentActionButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "special-rainbow")
    val hueShift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "hue-shift"
    )
    val rainbowBrush = Brush.linearGradient(
        colors = listOf(
            rainbowColor(hueShift + 0f),
            rainbowColor(hueShift + 60f),
            rainbowColor(hueShift + 120f),
            rainbowColor(hueShift + 180f),
            rainbowColor(hueShift + 240f),
            rainbowColor(hueShift + 300f)
        )
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.background(rainbowBrush, shape = MaterialTheme.shapes.medium),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Color(0xFFE0E0E0)
        )
    ) {
        Text(text)
    }
}

@Composable
fun ChatTabIcon(showIndicator: Boolean, unread: Boolean) {
    Box(modifier = Modifier.size(20.dp)) {
        Text("D", modifier = Modifier.align(Alignment.Center))
        if (showIndicator) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(8.dp)
                    .background(
                        color = if (unread) Color(0xFFD32F2F) else Color(0xFF2E7D32),
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
fun FeedTab(
    prompt: PromptResponse?,
    days: List<String>,
    byDay: Map<String, List<FeedItem>>,
    monthRecapByDay: Map<String, MonthlyRecap>,
    promptMetaByDay: Map<String, PromptMeta>,
    focusDay: String?,
    listState: LazyListState,
    todayLocked: Boolean,
    paging: Boolean,
    onTakePhoto: () -> Unit,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    onOpenViewer: (List<String>, Long?) -> Unit
) {
    val rows = remember(days, byDay, monthRecapByDay, promptMetaByDay) {
        buildList {
            for (day in days) {
                add(FeedRow.DayHeader(day, promptMetaByDay[day]))
                byDay[day].orEmpty().forEach { add(FeedRow.PhotoItem(day, it)) }
                monthRecapByDay[day]?.let { add(FeedRow.MonthRecapItem(day, it)) }
            }
        }
    }

    LaunchedEffect(focusDay, rows.size) {
        val target = focusDay ?: return@LaunchedEffect
        val idx = rows.indexOfFirst { it is FeedRow.DayHeader && it.day == target }
        if (idx >= 0) {
            listState.scrollToItem(idx)
        }
    }

    LaunchedEffect(listState, rows.size, paging) {
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: -1
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            first to last
        }.collect { (first, last) ->
            if (rows.isEmpty() || paging) return@collect
            if (first in 0..2) onLoadNewer()
            if (last >= rows.lastIndex - 4) onLoadOlder()
        }
    }

    if (rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Noch keine Beitraege gefunden")
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (todayLocked && prompt?.hasPosted == false) {
            item("today-locked") {
                Card {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Heutiger Feed ist gesperrt, bis du dein heutiges Foto postest.")
                        Button(onClick = onTakePhoto) { Text("Foto aufnehmen") }
                    }
                }
            }
        }

        items(rows, key = {
            when (it) {
                is FeedRow.DayHeader -> "day-${it.day}"
                is FeedRow.PhotoItem -> "photo-${it.item.photo.id}"
                is FeedRow.MonthRecapItem -> "recap-${it.recap.month}"
            }
        }) { row ->
            when (row) {
                is FeedRow.DayHeader -> {
                    Card {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(formatDayLabel(row.day), fontWeight = FontWeight.Bold)
                                Text(row.day, color = Color.Gray)
                            }
                            momentReasonLine(row.meta?.triggerSource, row.meta?.requestedByUser)?.let { reason ->
                                Text(
                                    reason,
                                    color = Color(0xFF1F5FBF),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                is FeedRow.PhotoItem -> {
                    val item = row.item
                    val urls = listOfNotNull(item.photo.url, item.photo.secondUrl)
                    Card {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                item.user.username,
                                fontWeight = FontWeight.SemiBold,
                                color = parseUserColor(item.user.favoriteColor)
                            )
                            if (item.isLate) {
                                Text("Spaeter gepostet", color = Color(0xFF8B0000))
                            }
                            if (item.photo.promptOnly) {
                                momentReasonLine(item.triggerSource, item.requestedByUser)?.let { reason ->
                                    Text(reason, color = Color(0xFF1F5FBF))
                                }
                            }
                            val reactions = item.reactions.orEmpty()
                            val comments = item.comments.orEmpty()
                            if (reactions.isNotEmpty()) {
                                Text(
                                    reactions.joinToString("  ") { "${it.emoji} ${it.count}" },
                                    color = Color(0xFF37474F)
                                )
                            }
                            if (comments.isNotEmpty()) {
                                comments.take(2).forEach { comment ->
                                    Text(
                                        "${comment.user.username}: ${comment.body}",
                                        color = Color(0xFF455A64),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (!item.photo.promptOnly) {
                                Text("Extra", color = Color(0xFF1F5FBF))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                urls.forEach { url ->
                                    AsyncImage(
                                        model = url,
                                        contentDescription = "${item.user.username} Foto",
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(180.dp)
                                            .clickable { onOpenViewer(urls, item.photo.id) },
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                            if (!item.photo.caption.isNullOrBlank()) {
                                Text(item.photo.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                is FeedRow.MonthRecapItem -> {
                    MonthlyRecapCard(row.recap)
                }
            }
        }

        if (paging) {
            item("paging") {
                Text("Lade weitere Tage ...", modifier = Modifier.padding(12.dp))
            }
        }
    }
}

private sealed class FeedRow {
    data class DayHeader(val day: String, val meta: PromptMeta?) : FeedRow()
    data class PhotoItem(val day: String, val item: FeedItem) : FeedRow()
    data class MonthRecapItem(val day: String, val recap: MonthlyRecap) : FeedRow()
}

@Composable
private fun MonthlyRecapCard(recap: MonthlyRecap) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Monatsrueckblick ${recap.monthLabel}", fontWeight = FontWeight.Bold)
            Text("Dein Monat in ${recap.yourMoments} Momenten")
            recap.mostReliableUser?.let { reliable ->
                Text("Am zuverlaessigsten: ${reliable.username} (${reliable.count} Tage)")
            }
            if (recap.topSpontaneous.isNotEmpty()) {
                Text("Top 5 spontanste Momente")
                recap.topSpontaneous.take(5).forEach { row ->
                    Text("- ${formatDayLabel(row.day)}: ${row.username} nach ${row.minutesAfterTrigger} min")
                }
            }
        }
    }
}

@Composable
fun CalendarTab(
    days: List<String>,
    monthRecapByDay: Map<String, MonthlyRecap>,
    promptMetaByDay: Map<String, PromptMeta>,
    selected: String,
    onSelect: (String) -> Unit
) {
    if (days.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Keine Tage mit Bildern vorhanden")
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(days) { day ->
            val selectedDay = day == selected
            val meta = promptMetaByDay[day]
            Card(modifier = Modifier.clickable { onSelect(day) }) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formatDayLabel(day), fontWeight = if (selectedDay) FontWeight.Bold else FontWeight.Normal)
                    Text(day, color = Color.Gray)
                    momentReasonLine(meta?.triggerSource, meta?.requestedByUser)?.let { reason ->
                        Text(reason, color = Color(0xFF1F5FBF))
                    }
                    monthRecapByDay[day]?.let { recap ->
                        Text("Monatsrueckblick: ${recap.monthLabel}", color = Color(0xFF0A7A42), fontWeight = FontWeight.SemiBold)
                    }
                    if (selectedDay) {
                        Text("Ausgewaehlt", color = Color(0xFF1F5FBF))
                    }
                }
            }
        }
    }
}

@Composable
fun ChatTab(items: List<ChatItem>, input: String, onInput: (String) -> Unit, onSend: () -> Unit) {
    val rows = remember(items) {
        buildList<ChatRow> {
            var lastDay = ""
            for (item in items) {
                val day = createdAtDay(item.createdAt)
                if (day != lastDay) {
                    add(ChatRow.DayHeader(day))
                    lastDay = day
                }
                add(ChatRow.MessageItem(item))
            }
        }
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Chat", style = MaterialTheme.typography.titleLarge)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows.size) { idx ->
                when (val row = rows[idx]) {
                    is ChatRow.DayHeader -> {
                        Card {
                            Text(
                                formatDayLabel(row.day),
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    is ChatRow.MessageItem -> {
                        val item = row.item
                        Card {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    item.user.username,
                                    fontWeight = FontWeight.SemiBold,
                                    color = parseUserColor(item.user.favoriteColor)
                                )
                                Text(item.body)
                            }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                label = { Text("Nachricht") },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onSend, modifier = Modifier.align(Alignment.CenterVertically)) { Text("Senden") }
        }
    }
}

@Composable
fun ProfileTab(
    username: String,
    inviteCode: String,
    streakDays: Int,
    promptRules: PromptRulesResponse?,
    photos: List<PromptPhoto>,
    darkMode: Boolean,
    oledMode: Boolean,
    currentPassword: String,
    newPassword: String,
    editableUsername: String,
    editableColor: String,
    appVersion: String,
    serverVersion: String,
    pushProvider: String,
    apiBaseUrl: String,
    serverConnected: Boolean,
    uploadQuality: Int,
    autoUpdateEnabled: Boolean,
    chatPushEnabled: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onOledModeChange: (Boolean) -> Unit,
    onUploadQualityChange: (Int) -> Unit,
    onAutoUpdateEnabledChange: (Boolean) -> Unit,
    onChatPushEnabledChange: (Boolean) -> Unit,
    onEditableUsernameChange: (String) -> Unit,
    onEditableColorChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onChangePassword: () -> Unit,
    onCheckUpdate: () -> Unit,
    onShowChangelog: () -> Unit,
    onShowHelp: () -> Unit,
    onCheckConnection: () -> Unit,
    onRollInviteCode: () -> Unit,
    onShareInviteCode: () -> Unit,
    onLogout: () -> Unit,
    onOpenViewer: (List<String>, Long?) -> Unit
) {
    var showColorPicker by remember { mutableStateOf(false) }
    var pickerHsv by remember(editableColor) { mutableStateOf(hexToHsv(normalizeHexColor(editableColor))) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("@$username", style = MaterialTheme.typography.titleLarge)
                Text("🔥 $streakDays", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCheckUpdate) { Text("Update pruefen") }
                Button(onClick = onShowChangelog) { Text("!") }
                Button(onClick = onShowHelp) { Text("Hilfe") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onLogout) { Text("Abmelden") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Dark Mode")
                Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("OLED Schwarz")
                Switch(checked = oledMode, onCheckedChange = onOledModeChange)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto-Update-Suche (10 Min)")
                Switch(checked = autoUpdateEnabled, onCheckedChange = onAutoUpdateEnabledChange)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chat Push bei neuen Nachrichten")
                Switch(checked = chatPushEnabled, onCheckedChange = onChatPushEnabledChange)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Invite-Code", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(inviteCode.ifBlank { "wird geladen ..." }, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onRollInviteCode, modifier = Modifier.weight(1f)) { Text("Erneuern") }
                        Button(onClick = onShareInviteCode, modifier = Modifier.weight(1f)) { Text("Teilen") }
                    }
                    Text("Jeder Code ist einmal gueltig. Nach Nutzung wird automatisch ein neuer Code erzeugt.")
                }
            }
        }

        item {
            Text("Profil", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = editableUsername,
                onValueChange = onEditableUsernameChange,
                label = { Text("Benutzername") },
                modifier = Modifier.fillMaxWidth()
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aktuelle Lieblingsfarbe: ${normalizeHexColor(editableColor)}")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(parseUserColor(editableColor))
                    ) {}
                    Button(onClick = { showColorPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Lieblingsfarbe waehlen")
                    }
                }
            }
            Text(
                text = "Vorschau Name",
                color = parseUserColor(editableColor),
                fontWeight = FontWeight.Bold
            )
            Button(onClick = onSaveProfile, modifier = Modifier.fillMaxWidth()) { Text("Profil speichern") }
        }

        item {
            Text("App & Verbindung", style = MaterialTheme.typography.titleMedium)
            Card {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Status: ${if (serverConnected) "Verbunden" else "Nicht verbunden"}")
                    Text("App-Version: $appVersion")
                    Text("Server-Version: $serverVersion")
                    Text("Push-Provider: $pushProvider")
                    Text("API: $apiBaseUrl")
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(onClick = onCheckConnection, modifier = Modifier.fillMaxWidth()) { Text("Verbindung pruefen") }
                }
            }
        }

        item {
            Text("Moment-Bedingungen", style = MaterialTheme.typography.titleMedium)
            Card {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (promptRules == null) {
                        Text("Bedingungen werden geladen ...")
                    } else {
                        Text("Prompt-Fenster: ${promptRules.promptWindowStartHour}:00-${promptRules.promptWindowEndHour}:00")
                        Text("Upload-Fenster: ${promptRules.uploadWindowMinutes} Minuten")
                        Text("Max Upload: ${if (promptRules.maxUploadBytes <= 0) "Unbegrenzt" else formatBytes(promptRules.maxUploadBytes.toDouble())}")
                        Text("Zeitzone: ${promptRules.timezone}")
                    }
                }
            }
        }

        item {
            Text("Upload-Komprimierung", style = MaterialTheme.typography.titleMedium)
            Card {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("JPEG-Qualitaet: $uploadQuality%")
                    Slider(
                        value = uploadQuality.toFloat(),
                        onValueChange = { onUploadQualityChange(it.toInt()) },
                        valueRange = 45f..95f
                    )
                    Text("Weniger Qualitaet = kleiner und schnellerer Upload")
                }
            }
        }

        item {
            Text("Passwort aendern", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = currentPassword,
                onValueChange = onCurrentPasswordChange,
                label = { Text("Aktuelles Passwort") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = newPassword,
                onValueChange = onNewPasswordChange,
                label = { Text("Neues Passwort") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onChangePassword, modifier = Modifier.fillMaxWidth()) { Text("Passwort speichern") }
        }

        item {
            Text("Vergangene Beitraege", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
        }

        item {
            if (photos.isEmpty()) {
                Text("Noch keine Beitraege")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    userScrollEnabled = false,
                    modifier = Modifier.height((((photos.size / 3) + 2) * 96).dp)
                ) {
                    items(photos) { photo ->
                        val urls = listOfNotNull(photo.url, photo.secondUrl)
                        Column {
                            AsyncImage(
                                model = photo.url,
                                contentDescription = "${photo.day}",
                                modifier = Modifier
                                    .size(96.dp)
                                    .background(Color.LightGray)
                                    .clickable { onOpenViewer(urls, photo.id) },
                                contentScale = ContentScale.Crop
                            )
                            if (photo.secondUrl != null) {
                                Text("2 Bilder", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (!photo.promptOnly) {
                                Text("Extra", color = Color(0xFF1F5FBF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(photo.day, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    if (showColorPicker) {
        AlertDialog(
            onDismissRequest = { showColorPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEditableColorChange(hsvToHex(pickerHsv[0], pickerHsv[1], pickerHsv[2]))
                        showColorPicker = false
                    }
                ) { Text("Uebernehmen") }
            },
            dismissButton = {
                TextButton(onClick = { showColorPicker = false }) { Text("Abbrechen") }
            },
            title = { Text("Lieblingsfarbe waehlen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val previewHex = hsvToHex(pickerHsv[0], pickerHsv[1], pickerHsv[2])
                    Text(previewHex, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .background(parseUserColor(previewHex))
                    ) {}
                    Text("Farbton")
                    Slider(
                        value = pickerHsv[0],
                        onValueChange = { pickerHsv = floatArrayOf(it, pickerHsv[1], pickerHsv[2]) },
                        valueRange = 0f..360f
                    )
                    Text("Saettigung")
                    Slider(
                        value = pickerHsv[1],
                        onValueChange = { pickerHsv = floatArrayOf(pickerHsv[0], it, pickerHsv[2]) },
                        valueRange = 0f..1f
                    )
                    Text("Helligkeit")
                    Slider(
                        value = pickerHsv[2],
                        onValueChange = { pickerHsv = floatArrayOf(pickerHsv[0], pickerHsv[1], it) },
                        valueRange = 0f..1f
                    )
                }
            }
        )
    }
}

private fun formatDayLabel(day: String): String {
    return try {
        val d = LocalDate.parse(day)
        d.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    } catch (_: Throwable) {
        day
    }
}

private sealed class ChatRow {
    data class DayHeader(val day: String) : ChatRow()
    data class MessageItem(val item: ChatItem) : ChatRow()
}

private fun createdAtDay(value: String): String {
    if (value.length >= 10) {
        val prefix = value.substring(0, 10)
        if (prefix[4] == '-' && prefix[7] == '-') return prefix
    }
    return value
}

private fun formatRemaining(seconds: Long): String {
    val sec = seconds.coerceAtLeast(0L)
    val days = sec / 86400
    val hours = (sec % 86400) / 3600
    return "${days}d ${hours}h"
}

private fun formatMomentTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "-"
    return runCatching {
        val dt = OffsetDateTime.parse(raw)
        dt.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrElse {
        raw.take(16).replace('T', ' ')
    }
}

private fun momentReasonLine(triggerSource: String?, requestedByUser: String?): String? {
    val src = triggerSource?.trim().orEmpty().lowercase()
    return if (src == "special_request" || src == "chat_command") {
        if (!requestedByUser.isNullOrBlank()) "⭐ Sondermoment von $requestedByUser" else "⭐ Sondermoment"
    } else if (src.isNotBlank()) {
        "⏳ Daily-Moment"
    } else {
        null
    }
}

private fun queueStatusLabel(status: String): String {
    return when (status) {
        UploadQueueStatus.WAITING -> "wartend"
        UploadQueueStatus.RUNNING -> "laeuft"
        UploadQueueStatus.FAILED -> "fehlgeschlagen"
        UploadQueueStatus.SUCCESS -> "erfolgreich"
        else -> status
    }
}

private fun rainbowColor(hue: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val intColor = AndroidColor.HSVToColor(floatArrayOf(h, 0.55f, 0.95f))
    return Color(intColor)
}

private fun formatBytes(bytes: Double): String {
    if (!bytes.isFinite() || bytes <= 0.0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes
    var idx = 0
    while (value >= 1024.0 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    val shown = if (idx == 0) "%.0f".format(value) else "%.2f".format(value)
    return "$shown ${units[idx]}"
}

private fun computePostingStreak(photos: List<PromptPhoto>): Int {
    if (photos.isEmpty()) return 0
    val postedDays = photos
        .asSequence()
        .filter { it.promptOnly }
        .mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }
        .toSet()
    if (postedDays.isEmpty()) return 0

    var streak = 0
    var day = LocalDate.now()
    while (postedDays.contains(day)) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}

private fun normalizeHexColor(input: String): String {
    val raw = input.trim().ifBlank { "#1F5FBF" }
    val withHash = if (raw.startsWith("#")) raw else "#$raw"
    val isHex = withHash.length == 7 && withHash.substring(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    return if (isHex) withHash.uppercase() else "#1F5FBF"
}

private fun normalizeInviteCodeLocal(input: String): String {
    return input
        .trim()
        .uppercase()
        .replace("-", "")
        .replace(" ", "")
}

private fun parseUserColor(input: String): Color {
    val hex = normalizeHexColor(input).removePrefix("#")
    val value = hex.toLongOrNull(16) ?: 0x1F5FBF
    val r = ((value shr 16) and 0xFF).toInt()
    val g = ((value shr 8) and 0xFF).toInt()
    val b = (value and 0xFF).toInt()
    return Color(r, g, b)
}

private fun hexToHsv(hex: String): FloatArray {
    val c = normalizeHexColor(hex)
    val parsed = runCatching { AndroidColor.parseColor(c) }.getOrDefault(AndroidColor.parseColor("#1F5FBF"))
    val hsv = floatArrayOf(0f, 0f, 0f)
    AndroidColor.colorToHSV(parsed, hsv)
    return hsv
}

private fun hsvToHex(h: Float, s: Float, v: Float): String {
    val colorInt = AndroidColor.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
    return String.format("#%06X", 0xFFFFFF and colorInt)
}

private fun apiError(t: Throwable, fallback: String): String {
    if (t is HttpException) {
        val raw = runCatching { t.response()?.errorBody()?.string().orEmpty() }.getOrDefault("").lowercase()
        return when (t.code()) {
            400 -> "Ungueltige Eingabe"
            401 -> "Login fehlgeschlagen"
            404 -> when {
                raw.contains("invite code not found") -> "Invite-Code nicht gefunden oder bereits benutzt."
                else -> fallback
            }
            403 -> when {
                raw.contains("prompt inactive") -> "Heute ist kein aktiver Moment. Bitte im Admin-Panel Event ausloesen."
                raw.contains("upload window closed") -> "Upload-Zeitfenster ist geschlossen."
                raw.contains("poste zuerst dein tagesmoment") -> "Poste zuerst dein Tagesmoment."
                else -> "Aktion nicht erlaubt"
            }
            409 -> when {
                raw.contains("username exists") -> "Benutzername ist bereits vergeben."
                else -> "Du hast heute bereits gepostet"
            }
            429 -> when {
                raw.contains("sondermoment") -> "Sondermoment diese Woche bereits angefordert."
                else -> "Zu viele Anfragen. Bitte spaeter erneut versuchen."
            }
            else -> fallback
        }
    }
    return t.message ?: fallback
}

private fun createTempImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File.createTempFile("moment_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun fallbackChangelogLines(): List<String> {
    return listOf(
        "Release-Infos konnten nicht von GitHub geladen werden.",
        "Bitte pruefe spaeter erneut oder oeffne die Release-Seite im Browser."
    )
}

private fun helpLines(): List<String> = listOf(
    "Willkommen bei Daily. Ziel ist ein kurzer gemeinsamer Moment pro Tag.",
    "",
    "Wichtige Grundregeln",
    "- Pro Nutzer ist genau ein Tagesmoment pro Tag erlaubt.",
    "- Der Feed des aktuellen Tages ist gesperrt, bis du dein Tagesmoment gepostet hast.",
    "- Extra-Bilder sind zusaetzlich moeglich und als Extra markiert.",
    "- Bei verpasstem Zeitfenster kannst du weiterhin posten, aber ggf. als spaet markiert.",
    "",
    "Reiter: Kamera",
    "- Hier startest du den Tagesmoment mit Rueck- und Frontkamera.",
    "- Der Upload startet nach der Aufnahme automatisch im Hintergrund.",
    "- Du kannst zusaetzliche Bilder aufnehmen.",
    "- Sondermoment kann je Nutzer nur 1x pro Woche angefordert werden.",
    "",
    "Reiter: Feed",
    "- Zeigt Beitraege nach Tagen getrennt.",
    "- Jeder Tag hat einen klaren Header, damit der Verlauf uebersichtlich bleibt.",
    "- Monatsrueckblicke erscheinen am Monatsende als eigener Block.",
    "",
    "Reiter: Kalender",
    "- Zeigt nur Tage, an denen es Beitraege gibt.",
    "- Beim Auswaehlen springst du im Feed zu diesem Tag.",
    "",
    "Reiter: Chat",
    "- Gruppenchat fuer kurze Nachrichten.",
    "- Ein Punkt am Reiter zeigt ungelesene Nachrichten von anderen Nutzern an.",
    "- Je nach Server-Konfiguration koennen Chat-Commands aktiv sein.",
    "",
    "Reiter: Profil",
    "- Zeigt Account, Streak, alte Beitraege und Verbindungsstatus.",
    "- Hier kannst du Benutzername, Farbe, Passwort und App-Optionen aendern.",
    "- Unter Moment-Bedingungen siehst du die aktuell gueltigen Regeln vom Server.",
    "",
    "Update und Verbindung",
    "- Update pruefen sucht nach neuer App-Version.",
    "- Verbindung pruefen aktualisiert Server-Version und Provider.",
    "- Das Symbol ! oeffnet den Changelog der aktuellen App-Version."
)

@Composable
private fun ZoomableViewerImage(url: String) {
    var scale by remember(url) { mutableStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }

    AsyncImage(
        model = url,
        contentDescription = "Vollbild",
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(url) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    }
                )
            }
            .pointerInput(url) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                    if (nextScale == 1f) {
                        offset = Offset.Zero
                    } else {
                        offset += pan
                    }
                    scale = nextScale
                }
            },
        contentScale = ContentScale.Fit
    )
}

