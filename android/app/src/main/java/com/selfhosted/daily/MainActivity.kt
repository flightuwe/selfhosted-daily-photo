package com.selfhosted.daily

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
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
import android.os.Environment
import android.media.RingtoneManager
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
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
import org.json.JSONObject
import org.json.JSONArray
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

const val EXTRA_LAUNCH_ACTION = "daily_launch_action"
const val EXTRA_LAUNCH_TYPE = "daily_launch_type"
const val EXTRA_LAUNCH_DAY = "daily_launch_day"
const val EXTRA_LAUNCH_PHOTO_ID = "daily_launch_photo_id"

enum class AppTab { CAMERA, FEED, CALENDAR, CHAT, PROFILE }
enum class AuthMode { LOGIN, REGISTER }

data class PendingLaunch(
    val action: String = "",
    val type: String = "",
    val targetDay: String = "",
    val targetPhotoId: Long? = null
)

data class User(
    val id: Long,
    val username: String,
    val isAdmin: Boolean,
    val favoriteColor: String = "#1F5FBF",
    val chatPushEnabled: Boolean = false,
    val inviteRegistrationPushEnabled: Boolean = false,
    val photoReactionPushEnabled: Boolean = false,
    val photoCommentPushEnabled: Boolean = false,
    val allowPhotoDownload: Boolean = false,
    val avatarUrl: String = "",
    val bio: String = "",
    val statusText: String = "",
    val statusEmoji: String = "",
    val statusExpiresAt: String? = null,
    val profileVisible: Boolean = false,
    val avatarVisible: Boolean = false,
    val bioVisible: Boolean = false,
    val statusVisible: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: String = "22:00",
    val quietHoursEnd: String = "07:00"
)
data class MeResponse(val user: User, val dailyMomentCount: Int = 0, val streakDays: Int = 0)
data class ProfileUpdateRequest(
    val username: String,
    val favoriteColor: String,
    val bio: String = "",
    val statusText: String = "",
    val statusEmoji: String = "",
    val statusExpiresAt: String? = null,
    val profileVisible: Boolean = false,
    val avatarVisible: Boolean = false,
    val bioVisible: Boolean = false,
    val statusVisible: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: String = "22:00",
    val quietHoursEnd: String = "07:00"
)
data class PreferencesUpdateRequest(
    val chatPushEnabled: Boolean,
    val inviteRegistrationPushEnabled: Boolean,
    val photoReactionPushEnabled: Boolean,
    val photoCommentPushEnabled: Boolean,
    val allowPhotoDownload: Boolean
)
data class AuthResponse(val token: String, val user: User)
data class LoginRequest(val username: String, val password: String)
data class InviteCodeRequest(val inviteCode: String)
data class InviteRegisterRequest(val inviteCode: String, val username: String, val password: String)
data class InviteOwner(val id: Long, val username: String, val favoriteColor: String = "#1F5FBF")
data class InvitePreviewResponse(val inviteCode: String, val inviter: InviteOwner)
data class InviteCodeResponse(val inviteCode: String)
data class DeviceTokenRequest(val token: String, val deviceName: String = "", val appVersion: String = BuildConfig.VERSION_NAME)
data class PasswordChangeRequest(val currentPassword: String, val newPassword: String)
data class ChatMessageRequest(
    val body: String,
    val clientMessageId: String? = null
)
data class ChatSendResponse(
    val id: Long? = null,
    val body: String? = null,
    val source: String? = null,
    val createdAt: String? = null,
    val command: Boolean = false,
    val report: Boolean = false,
    val reportId: Long? = null,
    val reportType: String? = null,
    val reportStatus: String? = null,
    val message: String? = null
)
data class DeleteChatResponse(
    val ok: Boolean = false,
    val deletedId: Long? = null
)
data class PromptPhoto(
    val id: Long,
    val day: String,
    val promptOnly: Boolean,
    val caption: String?,
    val url: String,
    val secondUrl: String? = null,
    val createdAt: String,
    val dailyMoment: Boolean = false,
    val capsuleMode: String? = null,
    val capsuleVisibleAt: String? = null,
    val capsulePrivate: Boolean = false,
    val capsuleGroupRemind: Boolean = false
)
data class PromptResponse(
    val day: String,
    val canUpload: Boolean,
    val triggered: String? = null,
    val hasPosted: Boolean = false,
    val hasPromptPostedToday: Boolean = false,
    val hasAnyPostToday: Boolean = false,
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
    val isEarly: Boolean = false,
    val isLate: Boolean = false,
    val capsuleLocked: Boolean = false,
    val capsuleReleased: Boolean = false,
    val photo: PromptPhoto,
    val user: User,
    val reactions: List<ReactionCount>? = null,
    val comments: List<PhotoCommentItem>? = null,
    val triggerSource: String? = null,
    val requestedByUser: String? = null
)

data class CapsuleUploadOptions(
    val mode: String = "",
    val privateOnly: Boolean = false,
    val groupRemind: Boolean = false
) {
    val enabled: Boolean get() = mode.isNotBlank()
}
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
data class CalendarFeaturedPhoto(
    val photoId: Long,
    val url: String,
    val secondUrl: String? = null,
    val user: User,
    val reactionCount: Long = 0,
    val commentCount: Long = 0,
    val interactionCount: Long = 0
)
data class DayStatItem(
    val day: String,
    val count: Long = 0,
    val postCount: Long = 0,
    val participantCount: Long = 0,
    val featuredPhoto: CalendarFeaturedPhoto? = null
)
data class DayStatsResponse(val items: List<DayStatItem>)
data class TopReactionStat(val emoji: String, val count: Long)
data class LatestActiveUser(
    val username: String,
    val createdAt: String
)
data class DailyMomentParticipationStat(
    val participants: Int,
    val totalUsers: Int,
    val percent: Int
)
data class CommunityStatsResponse(
    val registeredUsers: Long = 0,
    val activeUsersToday: Long = 0,
    val latestActiveUser: LatestActiveUser? = null,
    val postsToday: Long = 0,
    val chatMessagesToday: Long = 0,
    val topReactions7d: List<TopReactionStat> = emptyList(),
    val dailyMomentParticipation7d: DailyMomentParticipationStat = DailyMomentParticipationStat(0, 0, 0)
)
data class MyPhotoResponse(val items: List<PromptPhoto>)
data class UserProfileResponse(
    val profileVisible: Boolean = false,
    val isSelf: Boolean = false,
    val user: User,
    val photos: List<PromptPhoto> = emptyList()
)
data class ChatItem(
    val id: Long,
    val body: String,
    val createdAt: String,
    val user: User,
    val source: String = "user"
)
data class ChatResponse(val items: List<ChatItem>)
data class ReactionCount(val emoji: String, val count: Long)
data class PhotoCommentItem(val id: Long, val body: String, val createdAt: String, val user: User)
data class PhotoInteractionsResponse(
    val photoId: Long,
    val reactions: List<ReactionCount> = emptyList(),
    val myReaction: String = "",
    val comments: List<PhotoCommentItem> = emptyList(),
    val canDownload: Boolean = false
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
data class HealthFeatures(val chatDelete: Boolean = false)
data class HealthResponse(
    val ok: Boolean,
    val version: String = "unknown",
    val provider: String = "unknown",
    val features: HealthFeatures = HealthFeatures()
)
data class PromptRulesResponse(
    val promptWindowStartHour: Int,
    val promptWindowEndHour: Int,
    val uploadWindowMinutes: Int,
    val maxUploadBytes: Long,
    val timezone: String
)

data class ClientDebugLogUploadRequest(
    val type: String,
    val message: String,
    val meta: String = "",
    val appVersion: String,
    val deviceName: String
)

data class DebugLogEntry(
    val id: String,
    val type: String,
    val message: String,
    val meta: String = "",
    val createdAt: String
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

    @GET("users/{id}/profile")
    suspend fun userProfile(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): UserProfileResponse

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

    @GET("feed/day-stats")
    suspend fun feedDayStats(@Header("Authorization") token: String): DayStatsResponse

    @GET("community/stats")
    suspend fun communityStats(@Header("Authorization") token: String): CommunityStatsResponse

    @GET("me/photos")
    suspend fun myPhotos(@Header("Authorization") token: String): MyPhotoResponse

    @DELETE("me/photos/{id}")
    suspend fun deleteMyPhoto(@Header("Authorization") token: String, @Path("id") id: Long)

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
        @Part("kind") kind: RequestBody,
        @Part("capsule_mode") capsuleMode: RequestBody? = null,
        @Part("capsule_private") capsulePrivate: RequestBody? = null,
        @Part("capsule_group_remind") capsuleGroupRemind: RequestBody? = null
    )

    @Multipart
    @POST("uploads/dual")
    suspend fun uploadDual(
        @Header("Authorization") token: String,
        @Part photoBack: MultipartBody.Part,
        @Part photoFront: MultipartBody.Part,
        @Part("kind") kind: RequestBody,
        @Part("capsule_mode") capsuleMode: RequestBody? = null,
        @Part("capsule_private") capsulePrivate: RequestBody? = null,
        @Part("capsule_group_remind") capsuleGroupRemind: RequestBody? = null
    )

    @Multipart
    @POST("me/avatar")
    suspend fun uploadAvatar(
        @Header("Authorization") token: String,
        @Part avatar: MultipartBody.Part
    ): MeResponse

    @GET("chat")
    suspend fun chat(@Header("Authorization") token: String): ChatResponse

    @POST("chat")
    suspend fun sendChat(@Header("Authorization") token: String, @Body body: ChatMessageRequest): ChatSendResponse

    @DELETE("chat/{id}")
    suspend fun deleteChatMessage(@Header("Authorization") token: String, @Path("id") id: Long): DeleteChatResponse

    @POST("debug/client-log")
    suspend fun uploadClientDebugLog(
        @Header("Authorization") token: String,
        @Body body: ClientDebugLogUploadRequest
    )

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
    private val debugLogsPrefKey = "debug_logs_v1"
    private val debugUploadEnabledKey = "debug_upload_enabled"
    private val debugLastUploadAtKey = "debug_last_upload_at"
    private val debugMaxEntries = 500
    private val debugUploadMinIntervalMs = 5 * 60 * 1000L

    fun token(): String = prefs.getString("token", "") ?: ""

    fun saveToken(token: String) {
        prefs.edit().putString("token", token).apply()
    }

    fun clearToken() {
        prefs.edit().remove("token").apply()
        UploadQueueManager.clear(context)
    }

    fun diagnosticsUploadEnabled(): Boolean = prefs.getBoolean(debugUploadEnabledKey, false)

    fun setDiagnosticsUploadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(debugUploadEnabledKey, enabled).apply()
    }

    private fun readDebugLogsInternal(): MutableList<DebugLogEntry> {
        val raw = prefs.getString(debugLogsPrefKey, "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            val out = mutableListOf<DebugLogEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                out.add(
                    DebugLogEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        type = obj.optString("type", "unknown"),
                        message = obj.optString("message", ""),
                        meta = obj.optString("meta", ""),
                        createdAt = obj.optString("createdAt", "")
                    )
                )
            }
            out
        }.getOrElse { mutableListOf() }
    }

    private fun writeDebugLogsInternal(items: List<DebugLogEntry>) {
        val arr = JSONArray()
        items.takeLast(debugMaxEntries).forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("type", item.type)
            obj.put("message", item.message)
            obj.put("meta", item.meta)
            obj.put("createdAt", item.createdAt)
            arr.put(obj)
        }
        prefs.edit().putString(debugLogsPrefKey, arr.toString()).apply()
    }

    fun recentDebugLogs(limit: Int = 80): List<DebugLogEntry> =
        readDebugLogsInternal().takeLast(limit).reversed()

    fun logDebug(type: String, message: String, meta: String = "") {
        val cleanType = type.trim().ifBlank { "unknown" }.take(32)
        val cleanMessage = message.trim().ifBlank { "unknown error" }.take(500)
        val cleanMeta = meta.trim().take(4000)
        val createdAt = OffsetDateTime.now().toString()
        val current = readDebugLogsInternal()
        current.add(
            DebugLogEntry(
                id = UUID.randomUUID().toString(),
                type = cleanType,
                message = cleanMessage,
                meta = cleanMeta,
                createdAt = createdAt
            )
        )
        writeDebugLogsInternal(current)
    }

    fun exportDebugLogsForShare(): Uri {
        val exportDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(exportDir, "daily-diagnose-${System.currentTimeMillis()}.txt")
        val lines = buildString {
            appendLine("Daily Diagnose Export")
            appendLine("Generated: ${OffsetDateTime.now()}")
            appendLine("App version: ${BuildConfig.VERSION_NAME}")
            appendLine("Device: ${currentDeviceName()}")
            appendLine("")
            recentDebugLogs(300).reversed().forEach { row ->
                appendLine("[${row.createdAt}] ${row.type}: ${row.message}")
                if (row.meta.isNotBlank()) appendLine("meta: ${row.meta}")
            }
        }
        file.writeText(lines, Charsets.UTF_8)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    suspend fun uploadRecentDebugLogs(force: Boolean = false): Int {
        if (token().isBlank()) return 0
        if (!force && !diagnosticsUploadEnabled()) return 0
        val now = System.currentTimeMillis()
        val last = prefs.getLong(debugLastUploadAtKey, 0L)
        if (!force && now-last < debugUploadMinIntervalMs) return 0
        val rows = recentDebugLogs(20)
        if (rows.isEmpty()) return 0
        var sent = 0
        rows.forEach { row ->
            runCatching {
                api.uploadClientDebugLog(
                    "Bearer ${token()}",
                    ClientDebugLogUploadRequest(
                        type = row.type,
                        message = row.message,
                        meta = row.meta,
                        appVersion = BuildConfig.VERSION_NAME,
                        deviceName = currentDeviceName()
                    )
                )
            }.onSuccess { sent += 1 }
        }
        if (sent > 0) {
            prefs.edit().putLong(debugLastUploadAtKey, now).apply()
        }
        return sent
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val msg = throwable.message ?: throwable::class.java.simpleName
            val stack = throwable.stackTraceToString().take(3500)
            logDebug("crash_unhandled", msg, "thread=${thread.name};stack=$stack")
            previous?.uncaughtException(thread, throwable)
        }
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

    fun retryUploadQueueItemAsExtra(id: String): Boolean {
        val ok = UploadQueueManager.convertToExtraAndRetry(context, id)
        if (ok) UploadQueueScheduler.enqueueNow(context)
        return ok
    }

    fun removeUploadQueueItem(id: String): Boolean =
        UploadQueueManager.remove(context, id)

    fun isDarkMode(): Boolean = prefs.getBoolean("dark_mode", false)

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean("dark_mode", enabled).apply()
    }

    fun isOledMode(): Boolean = prefs.getBoolean("oled_mode", false)

    fun setOledMode(enabled: Boolean) {
        prefs.edit().putBoolean("oled_mode", enabled).apply()
    }

    fun uploadQuality(): Int = prefs.getInt("upload_quality", 80).coerceIn(20, 100)

    fun setUploadQuality(value: Int) {
        prefs.edit().putInt("upload_quality", value.coerceIn(20, 100)).apply()
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

    fun notificationMasterEnabled(): Boolean = prefs.getBoolean("notifications_master_enabled", true)

    fun setNotificationMasterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notifications_master_enabled", enabled).apply()
    }

    fun feedPostPushEnabled(): Boolean = prefs.getBoolean("feed_post_push_enabled", false)

    fun setFeedPostPushEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("feed_post_push_enabled", enabled).apply()
    }

    fun chatPushLocalEnabled(): Boolean = prefs.getBoolean("chat_push_enabled_local", false)

    fun setChatPushLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("chat_push_enabled_local", enabled).apply()
    }

    fun inviteRegistrationPushLocalEnabled(): Boolean = prefs.getBoolean("invite_registration_push_enabled_local", false)

    fun setInviteRegistrationPushLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("invite_registration_push_enabled_local", enabled).apply()
    }

    fun photoReactionPushLocalEnabled(): Boolean = prefs.getBoolean("photo_reaction_push_enabled_local", false)

    fun setPhotoReactionPushLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("photo_reaction_push_enabled_local", enabled).apply()
    }

    fun photoCommentPushLocalEnabled(): Boolean = prefs.getBoolean("photo_comment_push_enabled_local", false)

    fun setPhotoCommentPushLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("photo_comment_push_enabled_local", enabled).apply()
    }

    fun customNotificationToneEnabled(): Boolean = prefs.getBoolean("custom_notification_tone_enabled", false)

    fun setCustomNotificationToneEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("custom_notification_tone_enabled", enabled).apply()
    }

    fun customNotificationToneUri(): String = prefs.getString("custom_notification_tone_uri", "") ?: ""

    fun setCustomNotificationToneUri(uri: String) {
        prefs.edit().putString("custom_notification_tone_uri", uri.trim()).apply()
    }

    fun triggerLocalToneTestNotification() {
        PushMessagingService.showLocalToneTestNotification(context)
    }

    fun lastSeenOtherChatMillis(): Long = prefs.getLong("chat_seen_other_ms", 0L)

    fun setLastSeenOtherChatMillis(value: Long) {
        prefs.edit().putLong("chat_seen_other_ms", value.coerceAtLeast(0L)).apply()
    }

    fun profileSetupNeverAsk(): Boolean = prefs.getBoolean("profile_setup_never_ask", false)

    fun setProfileSetupNeverAsk(value: Boolean) {
        prefs.edit().putBoolean("profile_setup_never_ask", value).apply()
    }

    fun profileSetupCompleted(): Boolean = prefs.getBoolean("profile_setup_completed", false)

    fun setProfileSetupCompleted(value: Boolean) {
        prefs.edit().putBoolean("profile_setup_completed", value).apply()
    }

    fun syncQuietHoursFromUser(user: User) {
        prefs.edit()
            .putBoolean("quiet_hours_enabled", user.quietHoursEnabled)
            .putString("quiet_hours_start", user.quietHoursStart)
            .putString("quiet_hours_end", user.quietHoursEnd)
            .apply()
    }

    fun getProfileSectionExpanded(userId: Long, sectionId: String): Boolean {
        val key = "profile_section_${userId}_${sectionId.trim()}"
        return prefs.getBoolean(key, false)
    }

    fun setProfileSectionExpanded(userId: Long, sectionId: String, expanded: Boolean) {
        val key = "profile_section_${userId}_${sectionId.trim()}"
        prefs.edit().putBoolean(key, expanded).apply()
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

    fun randomStartupQuote(): String {
        return runCatching {
            val raw = context.assets.open("daily_photo_quotes.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("quotes") ?: return@runCatching ""
            val quotes = buildList<String> {
                for (i in 0 until arr.length()) {
                    val q = arr.optString(i).trim()
                    if (q.isNotBlank()) add(q)
                }
            }
            quotes.randomOrNull().orEmpty()
        }.getOrDefault("")
    }

    fun lastStartupChatSignature(): String = prefs.getString("startup_chat_signature", "") ?: ""

    fun setLastStartupChatSignature(signature: String) {
        prefs.edit().putString("startup_chat_signature", signature.trim()).apply()
    }

    fun captureLaunchIntent(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_LAUNCH_ACTION)?.trim().orEmpty()
        val type = intent?.getStringExtra(EXTRA_LAUNCH_TYPE)?.trim().orEmpty()
        val day = intent?.getStringExtra(EXTRA_LAUNCH_DAY)?.trim().orEmpty()
        val photoId = intent?.extras?.let { extras ->
            when {
                extras.containsKey(EXTRA_LAUNCH_PHOTO_ID) -> runCatching { extras.getLong(EXTRA_LAUNCH_PHOTO_ID) }.getOrNull()
                else -> null
            }
        }?.takeIf { it > 0L }
        if (action.isBlank() && type.isBlank() && day.isBlank() && photoId == null) return
        prefs.edit()
            .putString("pending_launch_action", action)
            .putString("pending_launch_type", type)
            .putString("pending_launch_day", day)
            .apply {
                if (photoId != null) putLong("pending_launch_photo_id", photoId) else remove("pending_launch_photo_id")
            }
            .apply()
    }

    fun consumePendingLaunchAction(): PendingLaunch {
        val action = prefs.getString("pending_launch_action", "").orEmpty()
        val type = prefs.getString("pending_launch_type", "").orEmpty()
        val day = prefs.getString("pending_launch_day", "").orEmpty()
        val photoId = if (prefs.contains("pending_launch_photo_id")) prefs.getLong("pending_launch_photo_id", 0L).takeIf { it > 0L } else null
        prefs.edit()
            .remove("pending_launch_action")
            .remove("pending_launch_type")
            .remove("pending_launch_day")
            .remove("pending_launch_photo_id")
            .apply()
        return PendingLaunch(action = action, type = type, targetDay = day, targetPhotoId = photoId)
    }

    fun randomStartupChatLine(chatItems: List<ChatItem>): String {
        val candidates = chatItems
            .filter { it.body.trim().isNotBlank() }
            .filter { it.source.equals("user", ignoreCase = true) || it.source.isBlank() }
            .map { it to "${it.user.id}|${it.body.trim()}" }
        if (candidates.isEmpty()) return ""
        val lastSig = lastStartupChatSignature()
        val pool = if (candidates.size > 1) candidates.filter { it.second != lastSig } else candidates
        val picked = pool.randomOrNull() ?: candidates.random()
        setLastStartupChatSignature(picked.second)
        return "${picked.first.user.username}: ${picked.first.body.trim()}"
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
    suspend fun me(): MeResponse = api.me("Bearer ${token()}")
    suspend fun myInviteCode(): String = api.myInviteCode("Bearer ${token()}").inviteCode
    suspend fun rollMyInviteCode(): String = api.rollInviteCode("Bearer ${token()}").inviteCode
    suspend fun updateProfile(
        username: String,
        favoriteColor: String,
        bio: String,
        statusText: String,
        statusEmoji: String,
        statusExpiresAt: String?,
        profileVisible: Boolean,
        avatarVisible: Boolean,
        bioVisible: Boolean,
        statusVisible: Boolean,
        quietHoursEnabled: Boolean,
        quietHoursStart: String,
        quietHoursEnd: String
    ): User =
        api.updateProfile(
            "Bearer ${token()}",
            ProfileUpdateRequest(
                username = username,
                favoriteColor = favoriteColor,
                bio = bio,
                statusText = statusText,
                statusEmoji = statusEmoji,
                statusExpiresAt = statusExpiresAt,
                profileVisible = profileVisible,
                avatarVisible = avatarVisible,
                bioVisible = bioVisible,
                statusVisible = statusVisible,
                quietHoursEnabled = quietHoursEnabled,
                quietHoursStart = quietHoursStart,
                quietHoursEnd = quietHoursEnd
            )
        ).user

    suspend fun uploadAvatar(uri: Uri): User {
        val file = copyUriToTemp(uri)
        val part = MultipartBody.Part.createFormData(
            "avatar",
            file.name,
            file.asRequestBody("image/*".toMediaTypeOrNull())
        )
        return api.uploadAvatar("Bearer ${token()}", part).user
    }

    suspend fun updatePreferences(
        chatPushEnabled: Boolean,
        inviteRegistrationPushEnabled: Boolean,
        photoReactionPushEnabled: Boolean,
        photoCommentPushEnabled: Boolean,
        allowPhotoDownload: Boolean
    ): User =
        api.updatePreferences(
            "Bearer ${token()}",
            PreferencesUpdateRequest(
                chatPushEnabled = chatPushEnabled,
                inviteRegistrationPushEnabled = inviteRegistrationPushEnabled,
                photoReactionPushEnabled = photoReactionPushEnabled,
                photoCommentPushEnabled = photoCommentPushEnabled,
                allowPhotoDownload = allowPhotoDownload
            )
        ).user

    suspend fun prompt(): PromptResponse = api.prompt("Bearer ${token()}")
    suspend fun promptRules(): PromptRulesResponse = api.promptRules("Bearer ${token()}")
    suspend fun specialMomentStatus(): SpecialMomentStatus = api.specialMomentStatus("Bearer ${token()}")
    suspend fun requestSpecialMoment() { api.requestSpecialMoment("Bearer ${token()}") }

    suspend fun feedByDay(day: String): FeedResponse = api.feed("Bearer ${token()}", day)
    suspend fun feedDays(): List<String> = api.feedDays("Bearer ${token()}").items
    suspend fun feedDayStats(): List<DayStatItem> = api.feedDayStats("Bearer ${token()}").items
    suspend fun communityStats(): CommunityStatsResponse = api.communityStats("Bearer ${token()}")

    suspend fun myPhotos(): List<PromptPhoto> = api.myPhotos("Bearer ${token()}").items

    suspend fun userProfile(userId: Long): UserProfileResponse = api.userProfile("Bearer ${token()}", userId)

    suspend fun deleteMyPhoto(photoId: Long) {
        api.deleteMyPhoto("Bearer ${token()}", photoId)
    }

    suspend fun listChat(): List<ChatItem> = api.chat("Bearer ${token()}").items

    suspend fun sendChat(body: String, clientMessageId: String): ChatSendResponse {
        return api.sendChat(
            "Bearer ${token()}",
            ChatMessageRequest(body = body, clientMessageId = clientMessageId)
        )
    }

    suspend fun deleteChatMessage(id: Long): DeleteChatResponse =
        api.deleteChatMessage("Bearer ${token()}", id)

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

        api.registerDevice("Bearer ${token()}", DeviceTokenRequest(deviceToken, currentDeviceName()))
        setLastSyncedDeviceToken(deviceToken)
        prefs.edit().remove("pending_fcm_token").apply()
    }

    private fun currentDeviceName(): String {
        val brand = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val version = Build.VERSION.RELEASE?.trim().orEmpty()
        val name = listOf(brand, model)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        if (name.isBlank()) return "Android-Geraet"
        return if (version.isNotBlank()) "$name (Android $version)" else name
    }

    suspend fun upload(uri: Uri, isPrompt: Boolean, capsule: CapsuleUploadOptions = CapsuleUploadOptions()) {
        val file = copyUriToTemp(uri)
        val part = MultipartBody.Part.createFormData(
            "photo",
            file.name,
            file.asRequestBody("image/*".toMediaTypeOrNull())
        )
        val kind = (if (isPrompt) "prompt" else "extra").toRequestBody("text/plain".toMediaTypeOrNull())
        val capsuleMode = capsule.mode.trim().takeIf { it.isNotBlank() }?.toRequestBody("text/plain".toMediaTypeOrNull())
        val capsulePrivate = if (capsuleMode != null) capsule.privateOnly.toString().toRequestBody("text/plain".toMediaTypeOrNull()) else null
        val capsuleGroup = if (capsuleMode != null) capsule.groupRemind.toString().toRequestBody("text/plain".toMediaTypeOrNull()) else null
        api.upload("Bearer ${token()}", part, kind, capsuleMode, capsulePrivate, capsuleGroup)
    }

    suspend fun uploadDual(
        backUri: Uri,
        frontUri: Uri,
        isPrompt: Boolean,
        capsule: CapsuleUploadOptions = CapsuleUploadOptions(),
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
        val capsuleMode = capsule.mode.trim().takeIf { it.isNotBlank() }?.toRequestBody("text/plain".toMediaTypeOrNull())
        val capsulePrivate = if (capsuleMode != null) capsule.privateOnly.toString().toRequestBody("text/plain".toMediaTypeOrNull()) else null
        val capsuleGroup = if (capsuleMode != null) capsule.groupRemind.toString().toRequestBody("text/plain".toMediaTypeOrNull()) else null
        emit()
        api.uploadDual("Bearer ${token()}", backPart, frontPart, kind, capsuleMode, capsulePrivate, capsuleGroup)
        onProgress(totalBytes, totalBytes)
    }

    suspend fun enqueueDualUpload(
        backUri: Uri,
        frontUri: Uri,
        isPrompt: Boolean,
        capsule: CapsuleUploadOptions = CapsuleUploadOptions()
    ): QueuedUploadItem {
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
            capsuleMode = capsule.mode,
            capsulePrivate = capsule.privateOnly,
            capsuleGroupRemind = capsule.groupRemind,
            authToken = token()
        )
    }

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? =
        UpdateReleaseChecker.checkForUpdate(currentVersion)

    suspend fun changelogLines(currentVersion: String): List<String> =
        UpdateReleaseChecker.changelogLinesForVersion(currentVersion)

    fun downloadLatestApk(update: UpdateInfo): Long {
        val fallbackUrl = "https://github.com/flightuwe/selfhosted-daily-photo/releases/latest/download/app-release.apk"
        val apkUrl = update.apkUrl?.trim().takeUnless { it.isNullOrBlank() } ?: fallbackUrl
        val safeVersion = update.latestVersion.trim().ifBlank { "latest" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Daily Update $safeVersion")
            .setDescription("Neue APK wird heruntergeladen")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "daily-v$safeVersion.apk")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    fun downloadPhotoToDownloads(photoUrl: String): Long {
        val safeUrl = photoUrl.trim()
        require(safeUrl.isNotBlank()) { "empty photo url" }
        val parsed = Uri.parse(safeUrl)
        val rawName = parsed.lastPathSegment?.substringAfterLast('/').orEmpty()
        val fileName = (if (rawName.isNotBlank()) rawName else "daily-photo-${System.currentTimeMillis()}.jpg")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val request = DownloadManager.Request(parsed)
            .setTitle("Daily Post")
            .setDescription("Bild wird heruntergeladen")
            .setMimeType("image/*")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

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
    val calendarDayStats: Map<String, DayStatItem> = emptyMap(),
    val communityStats: CommunityStatsResponse? = null,
    val communityStatsLoading: Boolean = false,
    val feedFocusDay: String? = null,
    val feedFocusPhotoId: Long? = null,
    val feedPaging: Boolean = false,
    val feedRefreshing: Boolean = false,
    val feedTodayLocked: Boolean = false,
    val chatHasOtherMessages: Boolean = true,
    val chatHasUnreadMessages: Boolean = false,
    val photos: List<PromptPhoto> = emptyList(),
    val streakDays: Int = 0,
    val dailyMomentCount: Int = 0,
    val chat: List<ChatItem> = emptyList(),
    val uploadQueue: List<QueuedUploadItem> = emptyList(),
    val photoInteractions: PhotoInteractionsResponse? = null,
    val viewedProfile: UserProfileResponse? = null,
    val viewedProfileLoading: Boolean = false,
    val interactionsLoading: Boolean = false,
    val chatSending: Boolean = false,
    val loading: Boolean = false,
    val message: String = "",
    val activeTab: AppTab = AppTab.CAMERA,
    val startupDone: Boolean = false,
    val startupQuote: String = "",
    val serverConnected: Boolean = false,
    val serverVersion: String = "unbekannt",
    val pushProvider: String = "unknown",
    val chatDeleteSupported: Boolean = false,
    val lastPingMs: Long? = null,
    val showPromptDialog: Boolean = false,
    val showProfileSetupPrompt: Boolean = false,
    val showProfileSetupGuide: Boolean = false,
    val profileSetupStep: Int = 0,
    val profileSetupJumpTarget: String = "",
    val showChangelogDialog: Boolean = false,
    val changelogLines: List<String> = emptyList(),
    val showHelpDialog: Boolean = false,
    val promptRules: PromptRulesResponse? = null,
    val specialMomentStatus: SpecialMomentStatus? = null,
    val updateInfo: UpdateInfo? = null,
    val updateAvailable: Boolean = false,
    val latestUpdateInfo: UpdateInfo? = null,
    val updateCheckInFlight: Boolean = false,
    val updateError: String? = null,
    val darkMode: Boolean = false,
    val oledMode: Boolean = false,
    val uploadQuality: Int = 80,
    val autoUpdateEnabled: Boolean = false,
    val notificationMasterEnabled: Boolean = true,
    val feedPostPushEnabled: Boolean = false,
    val inviteRegistrationPushEnabled: Boolean = false,
    val photoReactionPushEnabled: Boolean = false,
    val photoCommentPushEnabled: Boolean = false,
    val customNotificationToneEnabled: Boolean = false,
    val customNotificationToneUri: String = "",
    val diagnosticsUploadEnabled: Boolean = false,
    val debugLogs: List<DebugLogEntry> = emptyList(),
    val profileSectionExpanded: Map<String, Boolean> = emptyMap()
)

data class DashboardData(
    val me: User,
    val streakDays: Int,
    val dailyMomentCount: Int,
    val inviteCode: String,
    val prompt: PromptResponse,
    val rules: PromptRulesResponse,
    val special: SpecialMomentStatus,
    val photos: List<PromptPhoto>,
    val chat: List<ChatItem>,
    val feedDays: List<String>,
    val dayStats: List<DayStatItem>,
    val communityStats: CommunityStatsResponse?
)

class MainVm(private val repo: AppRepo) : ViewModel() {
    private val chatSendMutex = Mutex()
    private val profileSaveMutex = Mutex()
    private val refreshAllMutex = Mutex()
    private val pendingChatBodies = mutableMapOf<String, Long>()
    private val recentDashboardFailureAt = mutableMapOf<String, Long>()
    private val pendingChatWindowMs = 4_000L
    private val refreshAllCooldownMs = 2_000L
    private val dashboardFailureDedupMs = 5 * 60 * 1000L
    private var lastRefreshAllStartedAt = 0L
    private val profileSectionIds = listOf(
        "display",
        "notifications",
        "invite",
        "profile_account",
        "profile_privacy",
        "app_connection",
        "debug_diagnose",
        "community_stats",
        "moment_rules",
        "upload_quality",
        "past_posts"
    )
    private var profileSetupPromptShownInSession = false

    var state by mutableStateOf(
        UiState(
            token = repo.token(),
            darkMode = repo.isDarkMode(),
            oledMode = repo.isOledMode(),
            uploadQuality = repo.uploadQuality(),
            autoUpdateEnabled = repo.autoUpdateEnabled(),
            notificationMasterEnabled = repo.notificationMasterEnabled(),
            feedPostPushEnabled = repo.feedPostPushEnabled(),
            inviteRegistrationPushEnabled = repo.inviteRegistrationPushLocalEnabled(),
            photoReactionPushEnabled = repo.photoReactionPushLocalEnabled(),
            photoCommentPushEnabled = repo.photoCommentPushLocalEnabled(),
            customNotificationToneEnabled = repo.customNotificationToneEnabled(),
            customNotificationToneUri = repo.customNotificationToneUri(),
            diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled(),
            debugLogs = repo.recentDebugLogs()
        )
    )
        private set

    private fun normalizeChatBody(body: String): String =
        body.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ").lowercase()

    private fun cleanupPendingChatBodies(nowMs: Long) {
        val it = pendingChatBodies.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (nowMs-entry.value > pendingChatWindowMs) {
                it.remove()
            }
        }
    }

    private fun rootCause(throwable: Throwable): Throwable {
        var current = throwable
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun isBenignCancellation(throwable: Throwable): Boolean {
        if (throwable is CancellationException) return true
        return throwable::class.java.simpleName.contains("LeftCompositionCancellationException")
    }

    private fun networkFailureKind(throwable: Throwable): String? {
        return when (rootCause(throwable)) {
            is UnknownHostException -> "dns"
            is ConnectException -> "connect"
            is SocketTimeoutException -> "timeout"
            else -> null
        }
    }

    private fun debugFailureMessage(throwable: Throwable): String {
        return when (networkFailureKind(throwable)) {
            "dns" -> "Servername konnte nicht aufgeloest werden"
            "connect" -> "Verbindung zum Server fehlgeschlagen"
            "timeout" -> "Server antwortet zu langsam"
            else -> throwable.message ?: "request failed"
        }
    }

    private fun shouldLogDashboardFailure(endpoint: String, throwable: Throwable): Boolean {
        val kind = networkFailureKind(throwable) ?: throwable::class.java.simpleName
        val key = "$endpoint|$kind"
        val now = System.currentTimeMillis()
        val it = recentDashboardFailureAt.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value > dashboardFailureDedupMs) {
                it.remove()
            }
        }
        val last = recentDashboardFailureAt[key] ?: 0L
        if (now - last < dashboardFailureDedupMs) return false
        recentDashboardFailureAt[key] = now
        return true
    }

    private suspend fun fetchChangelogLinesFresh(): List<String> {
        suspend fun loadOnce(): List<String> =
            runCatching { repo.changelogLines(BuildConfig.VERSION_NAME) }
                .getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.isNotBlank() }

        fun isPlaceholderOnly(lines: List<String>): Boolean =
            lines.size == 1 && lines.first().equals("Keine Action-Historie verfuegbar.", ignoreCase = true)

        var lines = loadOnce()
        if (lines.isEmpty() || isPlaceholderOnly(lines)) {
            delay(800)
            val retry = loadOnce()
            if (retry.isNotEmpty() && !isPlaceholderOnly(retry)) {
                lines = retry
            }
        }
        return lines.filterNot { it.equals("Keine Action-Historie verfuegbar.", ignoreCase = true) }
    }

    suspend fun bootstrap() {
        if (state.startupDone) return
        profileSetupPromptShownInSession = false
        state = state.copy(startupDone = false, startupQuote = "")
        repo.syncAutoUpdateScheduler()
        repo.syncUploadQueueScheduler()
        val started = System.currentTimeMillis()
        val health = runCatching { repo.health() }.getOrNull()
        val elapsed = System.currentTimeMillis() - started
        if (elapsed < 900) {
            delay(900 - elapsed)
        }
        val showChangelog = repo.shouldShowChangelog(BuildConfig.VERSION_NAME)
        val changelogLines = if (showChangelog) fetchChangelogLinesFresh() else emptyList()
        val healthOk = health?.ok == true
        val startupQuote = if (healthOk) {
            if (repo.token().isNotBlank()) {
                val chatLine = runCatching { repo.randomStartupChatLine(repo.listChat()) }.getOrDefault("")
                if (chatLine.isNotBlank()) chatLine else repo.randomStartupQuote()
            } else {
                repo.randomStartupQuote()
            }
        } else ""
        if (healthOk && startupQuote.isNotBlank()) {
            state = state.copy(
                startupDone = false,
                startupQuote = startupQuote,
                serverConnected = true,
                serverVersion = health?.version ?: "nicht erreichbar",
                pushProvider = health?.provider ?: "unknown",
                chatDeleteSupported = health?.features?.chatDelete == true
            )
            delay(1300)
        }
        state = state.copy(
            startupDone = true,
            startupQuote = startupQuote,
            serverConnected = healthOk,
            serverVersion = health?.version ?: "nicht erreichbar",
            pushProvider = health?.provider ?: "unknown",
            chatDeleteSupported = health?.features?.chatDelete == true,
            showChangelogDialog = showChangelog,
            changelogLines = changelogLines,
            uploadQueue = repo.uploadQueue(),
            autoUpdateEnabled = repo.autoUpdateEnabled(),
            notificationMasterEnabled = repo.notificationMasterEnabled(),
            feedPostPushEnabled = repo.feedPostPushEnabled(),
            photoReactionPushEnabled = repo.photoReactionPushLocalEnabled(),
            photoCommentPushEnabled = repo.photoCommentPushLocalEnabled(),
            customNotificationToneEnabled = repo.customNotificationToneEnabled(),
            customNotificationToneUri = repo.customNotificationToneUri(),
            diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled(),
            debugLogs = repo.recentDebugLogs(),
            message = if (health?.ok == true) "" else "Server nicht erreichbar"
        )
        runCatching { checkForUpdate(silent = true) }
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
        profileSetupPromptShownInSession = false
        state = UiState(
            startupDone = true,
            serverConnected = state.serverConnected,
            serverVersion = state.serverVersion,
            pushProvider = state.pushProvider,
            darkMode = state.darkMode,
            oledMode = state.oledMode,
            uploadQuality = state.uploadQuality,
            autoUpdateEnabled = repo.autoUpdateEnabled(),
            notificationMasterEnabled = repo.notificationMasterEnabled(),
            feedPostPushEnabled = repo.feedPostPushEnabled(),
            customNotificationToneEnabled = repo.customNotificationToneEnabled(),
            customNotificationToneUri = repo.customNotificationToneUri(),
            diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled(),
            debugLogs = repo.recentDebugLogs(),
            invitePreview = null
        )
    }

    fun setProfileSectionExpanded(sectionId: String, expanded: Boolean) {
        val userId = state.user?.id ?: return
        val normalizedId = sectionId.trim()
        if (normalizedId.isBlank()) return
        repo.setProfileSectionExpanded(userId, normalizedId, expanded)
        state = state.copy(profileSectionExpanded = state.profileSectionExpanded + (normalizedId to expanded))
    }

    private suspend fun logApiFailure(type: String, endpoint: String, throwable: Throwable, extraMeta: String = "") {
        if (isBenignCancellation(throwable)) return
        if (type == "dashboard_load_failed" && !shouldLogDashboardFailure(endpoint, throwable)) return
        val code = (throwable as? HttpException)?.code()
        val network = networkFailureKind(throwable)
        val base = buildString {
            append("endpoint=").append(endpoint)
            append(";http=").append(code ?: -1)
            append(";error=").append(throwable::class.java.simpleName)
            if (network != null) {
                append(";network=").append(network)
            }
        }
        val meta = if (extraMeta.isBlank()) base else "$base;$extraMeta"
        repo.logDebug(type = type, message = debugFailureMessage(throwable), meta = meta)
        if (state.diagnosticsUploadEnabled) {
            try {
                repo.uploadRecentDebugLogs()
            } catch (_: Throwable) {
            }
        }
    }

    fun setDiagnosticsUploadEnabled(enabled: Boolean) {
        repo.setDiagnosticsUploadEnabled(enabled)
        state = state.copy(diagnosticsUploadEnabled = enabled, message = if (enabled) "Diagnose-Upload aktiviert" else "Diagnose-Upload deaktiviert")
        if (enabled) {
            viewModelScope.launch {
                try {
                    val sent = repo.uploadRecentDebugLogs(force = true)
                    if (sent > 0) {
                        state = state.copy(message = "Diagnose-Upload aktiviert, $sent Eintraege hochgeladen")
                    }
                } catch (_: Throwable) {
                    // Keep the toggle enabled even if the first upload attempt fails.
                }
            }
        }
    }

    fun refreshDebugLogs() {
        state = state.copy(debugLogs = repo.recentDebugLogs())
    }

    suspend fun autoUploadDebugLogsIfEnabled() {
        if (!state.diagnosticsUploadEnabled) return
        try {
            repo.uploadRecentDebugLogs()
        } catch (_: Throwable) {
        }
    }

    fun exportDebugLogsForShare(): Uri? {
        return runCatching { repo.exportDebugLogsForShare() }.getOrNull()
    }

    private fun profileSetupIncomplete(user: User): Boolean {
        val hasAvatar = user.avatarUrl.trim().isNotBlank()
        val hasBio = user.bio.trim().isNotBlank()
        val hasStatus = user.statusText.trim().isNotBlank() || user.statusEmoji.trim().isNotBlank()
        val hasAnyVisibility = user.profileVisible || user.avatarVisible || user.bioVisible || user.statusVisible
        return !(hasAvatar && hasBio && hasStatus && hasAnyVisibility)
    }

    private fun maybeShowProfileSetupPrompt(user: User) {
        if (profileSetupPromptShownInSession) return
        if (repo.profileSetupNeverAsk()) return
        if (repo.profileSetupCompleted()) return
        if (!profileSetupIncomplete(user)) {
            repo.setProfileSetupCompleted(true)
            return
        }
        profileSetupPromptShownInSession = true
        state = state.copy(showProfileSetupPrompt = true)
    }

    fun profileSetupPromptYes() {
        state = state.copy(
            showProfileSetupPrompt = false,
            showProfileSetupGuide = true,
            profileSetupStep = 0,
            activeTab = AppTab.PROFILE
        )
    }

    fun profileSetupPromptNo() {
        state = state.copy(showProfileSetupPrompt = false)
    }

    fun profileSetupPromptNeverAsk() {
        repo.setProfileSetupNeverAsk(true)
        state = state.copy(showProfileSetupPrompt = false)
    }

    fun openProfileSetupGuide() {
        state = state.copy(showProfileSetupGuide = true, profileSetupStep = 0, activeTab = AppTab.PROFILE)
    }

    fun closeProfileSetupGuide(markCompleted: Boolean) {
        if (markCompleted) {
            repo.setProfileSetupCompleted(true)
        }
        state = state.copy(showProfileSetupGuide = false, profileSetupStep = 0)
    }

    fun nextProfileSetupStep() {
        val next = (state.profileSetupStep + 1).coerceAtMost(2)
        state = state.copy(profileSetupStep = next)
    }

    fun jumpToSetupSection(sectionId: String) {
        setTab(AppTab.PROFILE)
        setProfileSectionExpanded(sectionId, true)
        if (sectionId == "profile_account") {
            setProfileSectionExpanded("profile_privacy", true)
        }
        state = state.copy(profileSetupJumpTarget = sectionId)
    }

    fun consumeProfileSetupJumpTarget() {
        if (state.profileSetupJumpTarget.isNotBlank()) {
            state = state.copy(profileSetupJumpTarget = "")
        }
    }

    suspend fun loadUserProfile(userId: Long) {
        state = state.copy(viewedProfileLoading = true, viewedProfile = null)
        runCatching { repo.userProfile(userId) }
            .onSuccess {
                repo.logDebug(
                    type = "profile_open_ok",
                    message = "profile opened",
                    meta = "targetUserId=$userId;profileVisible=${it.profileVisible};photoCount=${it.photos.size}"
                )
                state = state.copy(viewedProfileLoading = false, viewedProfile = it)
            }
            .onFailure {
                state = state.copy(viewedProfileLoading = false, message = apiError(it, "Profil laden fehlgeschlagen"))
                logApiFailure("profile_open_failed", "/api/users/:id/profile", it, "targetUserId=$userId")
            }
    }

    fun closeViewedProfile() {
        state = state.copy(viewedProfile = null, viewedProfileLoading = false)
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

    fun clearFeedPhotoFocus() {
        if (state.feedFocusPhotoId != null) {
            state = state.copy(feedFocusPhotoId = null)
        }
    }

    suspend fun jumpToDay(day: String) {
        state = state.copy(activeTab = AppTab.FEED, feedFocusDay = day, feedFocusPhotoId = null)
        loadFeedWindow(day, around = 0)
        state = state.copy(activeTab = AppTab.FEED, feedFocusDay = day, feedFocusPhotoId = null)
    }

    suspend fun jumpToPhoto(day: String, photoId: Long) {
        state = state.copy(activeTab = AppTab.FEED, feedFocusDay = day, feedFocusPhotoId = photoId)
        loadFeedWindow(day, around = 3)
        state = state.copy(activeTab = AppTab.FEED, feedFocusDay = day, feedFocusPhotoId = photoId)
    }

    suspend fun refreshAll() {
        if (repo.token().isBlank()) return
        val now = System.currentTimeMillis()
        if (!refreshAllMutex.tryLock()) return
        if (now - lastRefreshAllStartedAt < refreshAllCooldownMs) {
            refreshAllMutex.unlock()
            return
        }
        lastRefreshAllStartedAt = now
        state = state.copy(loading = true, communityStatsLoading = true)
        try {
            repo.syncDeviceTokenIfNeeded()
            val meResp = repo.me()
            val payload = DashboardData(
                me = meResp.user,
                streakDays = meResp.streakDays,
                dailyMomentCount = meResp.dailyMomentCount,
                inviteCode = repo.myInviteCode(),
                prompt = repo.prompt(),
                rules = repo.promptRules(),
                special = repo.specialMomentStatus(),
                photos = repo.myPhotos(),
                chat = repo.listChat(),
                feedDays = repo.feedDays(),
                dayStats = runCatching { repo.feedDayStats() }.getOrDefault(emptyList()),
                communityStats = runCatching { repo.communityStats() }.getOrNull()
            )
            val me = payload.me
            val streakDays = payload.streakDays
            val dailyMomentCount = payload.dailyMomentCount
            val inviteCode = payload.inviteCode
            val prompt = payload.prompt
            val rules = payload.rules
            val special = payload.special
            val photos = payload.photos
            val chat = payload.chat
            val calendarDays = payload.feedDays
            val calendarDayStats = payload.dayStats.associateBy { it.day }
            val latestOtherChat = latestOtherChatMillis(chat, me.id)
            val seenChat = repo.lastSeenOtherChatMillis()
            var hasUnreadChat = latestOtherChat > seenChat
            if (state.activeTab == AppTab.CHAT && latestOtherChat > 0L) {
                repo.setLastSeenOtherChatMillis(latestOtherChat)
                hasUnreadChat = false
            }
            val marker = "${prompt.day}:${prompt.triggered ?: ""}"
            val shouldPopup = prompt.canUpload && !prompt.triggered.isNullOrBlank() && !prompt.hasPromptPostedToday && marker != repo.seenPromptMarker()
            if (shouldPopup) repo.setSeenPromptMarker(marker)
            repo.setChatPushLocalEnabled(me.chatPushEnabled)
            repo.setInviteRegistrationPushLocalEnabled(me.inviteRegistrationPushEnabled)
            repo.syncQuietHoursFromUser(me)
            repo.setPhotoReactionPushLocalEnabled(me.photoReactionPushEnabled)
            repo.setPhotoCommentPushLocalEnabled(me.photoCommentPushEnabled)
            val notificationMaster = repo.notificationMasterEnabled()
            val feedPostPushEnabled = repo.feedPostPushEnabled()
            val inviteRegistrationPushEnabled = repo.inviteRegistrationPushLocalEnabled()
            val photoReactionPushEnabled = repo.photoReactionPushLocalEnabled()
            val photoCommentPushEnabled = repo.photoCommentPushLocalEnabled()
            val autoUpdateEnabled = repo.autoUpdateEnabled()
            val profileSectionExpanded = profileSectionIds.associateWith { sectionId ->
                repo.getProfileSectionExpanded(me.id, sectionId)
            }

            state = state.copy(
                user = me,
                myInviteCode = inviteCode,
                prompt = prompt,
                promptRules = rules,
                specialMomentStatus = special,
                photos = photos,
                streakDays = streakDays,
                dailyMomentCount = dailyMomentCount,
                chat = chat,
                chatHasOtherMessages = true,
                chatHasUnreadMessages = hasUnreadChat,
                calendarDays = calendarDays,
                calendarDayStats = calendarDayStats,
                communityStats = payload.communityStats,
                communityStatsLoading = false,
                uploadQueue = repo.uploadQueue(),
                autoUpdateEnabled = autoUpdateEnabled,
                feedPostPushEnabled = feedPostPushEnabled,
                inviteRegistrationPushEnabled = inviteRegistrationPushEnabled,
                photoReactionPushEnabled = photoReactionPushEnabled,
                photoCommentPushEnabled = photoCommentPushEnabled,
                notificationMasterEnabled = notificationMaster && autoUpdateEnabled && feedPostPushEnabled && me.chatPushEnabled && inviteRegistrationPushEnabled && photoReactionPushEnabled && photoCommentPushEnabled,
                diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled(),
                debugLogs = repo.recentDebugLogs(),
                profileSectionExpanded = profileSectionExpanded,
                loading = false,
                showPromptDialog = state.showPromptDialog || shouldPopup,
                message = ""
            )
            applyPendingLaunchNavigation(prompt, calendarDays)
            maybeShowProfileSetupPrompt(me)
            val focus = state.feedFocusDay
            val anchor = if (focus != null && calendarDays.contains(focus)) focus else prompt.day
            loadFeedWindow(anchor, around = 3)
        } catch (t: Throwable) {
            if (isBenignCancellation(t)) {
                state = state.copy(loading = false, communityStatsLoading = false)
                return
            }
            state = state.copy(loading = false, communityStatsLoading = false, message = apiError(t, "Laden fehlgeschlagen"))
            logApiFailure("dashboard_load_failed", "refresh_all", t)
        } finally {
            refreshAllMutex.unlock()
        }
    }

    suspend fun refreshFeed() {
        if (state.feedRefreshing) return
        state = state.copy(feedRefreshing = true)
        val started = System.currentTimeMillis()
        try {
            refreshAll()
        } finally {
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < 700) delay(700 - elapsed)
            state = state.copy(feedRefreshing = false)
        }
    }

    suspend fun deleteMyPhoto(photoId: Long) {
        state = state.copy(loading = true)
        runCatching { repo.deleteMyPhoto(photoId) }
            .onSuccess {
                state = state.copy(loading = false, message = "Beitrag geloescht")
                refreshAll()
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Beitrag loeschen fehlgeschlagen"))
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
                feedTodayLocked = state.prompt?.hasAnyPostToday == false,
                feedFocusDay = state.prompt?.day,
                feedFocusPhotoId = null
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
        val postedToday = state.prompt?.hasAnyPostToday == true
        val hasVisibleTodayFeed = map[today].orEmpty().isNotEmpty()
        val todayLocked = !postedToday && !hasVisibleTodayFeed
        state = state.copy(
            feedDays = days.distinct(),
            feedByDay = map,
            monthRecapByDay = monthRecapMap,
            promptMetaByDay = promptMap,
            feed = map[today] ?: emptyList(),
            feedTodayLocked = todayLocked,
            feedFocusDay = target,
            feedFocusPhotoId = state.feedFocusPhotoId
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
        capsule: CapsuleUploadOptions = CapsuleUploadOptions(),
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean {
        state = state.copy(loading = true)
        return try {
            repo.uploadDual(back, front, asPrompt, capsule, onProgress)
            state = state.copy(loading = false, message = "Fotos gepostet")
            refreshAll()
            true
        } catch (t: Throwable) {
            state = state.copy(loading = false, message = apiError(t, "Upload fehlgeschlagen"))
            false
        }
    }

    suspend fun enqueueDualUpload(
        back: Uri,
        front: Uri,
        asPrompt: Boolean,
        capsule: CapsuleUploadOptions = CapsuleUploadOptions()
    ): Boolean {
        state = state.copy(loading = true)
        return runCatching {
            repo.enqueueDualUpload(back, front, asPrompt, capsule)
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

    fun retryQueuedUploadAsExtra(id: String) {
        val ok = repo.retryUploadQueueItemAsExtra(id)
        if (ok) {
            state = state.copy(uploadQueue = repo.uploadQueue(), message = "Upload als Extra neu geplant")
        }
    }

    fun removeQueuedUpload(id: String) {
        val ok = repo.removeUploadQueueItem(id)
        if (ok) {
            state = state.copy(uploadQueue = repo.uploadQueue(), message = "Upload aus Warteschlange entfernt")
        }
    }

    fun refreshUploadQueueLocal() {
        if (repo.token().isBlank()) return
        state = state.copy(uploadQueue = repo.uploadQueue())
    }

    suspend fun sendChat(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isBlank() || state.chatSending) return false
        if (!chatSendMutex.tryLock()) return false
        val nowMs = System.currentTimeMillis()
        val normalized = normalizeChatBody(trimmed)
        cleanupPendingChatBodies(nowMs)
        val pendingAt = pendingChatBodies[normalized]
        if (pendingAt != null && nowMs-pendingAt <= pendingChatWindowMs) {
            chatSendMutex.unlock()
            return false
        }

        val clientMessageId = UUID.randomUUID().toString()
        pendingChatBodies[normalized] = nowMs
        state = state.copy(chatSending = true)
        return try {
            runCatching { repo.sendChat(trimmed, clientMessageId) }
                .onSuccess { response ->
                    val localMessage = response.message?.trim().orEmpty()
                    if (response.report) {
                        state = state.copy(message = if (localMessage.isNotBlank()) localMessage else "Meldung wurde an den Server geschickt.")
                    } else {
                        refreshAll()
                        if (localMessage.isNotBlank()) {
                            state = state.copy(message = localMessage)
                        }
                    }
                }
                .onFailure {
                    pendingChatBodies.remove(normalized)
                    state = state.copy(message = apiError(it, "Chat senden fehlgeschlagen"))
                    logApiFailure("chat_send_failed", "/api/chat", it)
                }
                .isSuccess
        } finally {
            state = state.copy(chatSending = false)
            chatSendMutex.unlock()
        }
    }

    suspend fun deleteChatMessage(id: Long): Boolean {
        if (id <= 0L) return false
        return runCatching { repo.deleteChatMessage(id) }
            .map {
                val deletedId = it.deletedId ?: id
                val updatedChat = state.chat.filterNot { item -> item.id == deletedId }
                val meId = state.user?.id
                val latestOtherChat = latestOtherChatMillis(updatedChat, meId)
                val seenChat = repo.lastSeenOtherChatMillis()
                state = state.copy(
                    chat = updatedChat,
                    chatHasOtherMessages = meId != null && updatedChat.any { item -> item.user.id != meId },
                    chatHasUnreadMessages = latestOtherChat > seenChat,
                    message = "Nachricht geloescht"
                )
                true
            }
            .getOrElse {
                val httpCode = (it as? HttpException)?.code()
                val extraMeta = buildString {
                    append("chatId=").append(id)
                    if (httpCode == 404) append(";serverFeature=chatDeleteUnsupportedOrMissing")
                }
                state = state.copy(
                    message = if (httpCode == 404) {
                        "Nachrichten-Loeschen wird vom Server noch nicht unterstuetzt"
                    } else {
                        apiError(it, "Nachricht loeschen fehlgeschlagen")
                    }
                )
                logApiFailure("chat_delete_failed", "/api/chat/:id", it, extraMeta)
                false
            }
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

    suspend fun checkForUpdate(silent: Boolean = false) {
        state = state.copy(
            loading = if (silent) state.loading else true,
            updateCheckInFlight = true,
            updateError = null
        )
        runCatching { repo.checkForUpdate(BuildConfig.VERSION_NAME) }
            .onSuccess { update ->
                state = if (update != null) {
                    state.copy(
                        loading = if (silent) state.loading else false,
                        updateInfo = if (silent) state.updateInfo else update,
                        updateAvailable = true,
                        latestUpdateInfo = update,
                        updateCheckInFlight = false,
                        updateError = null,
                        message = if (silent) state.message else "Neue Version ${update.latestVersion} gefunden"
                    )
                } else {
                    state.copy(
                        loading = if (silent) state.loading else false,
                        updateInfo = if (silent) state.updateInfo else null,
                        updateAvailable = false,
                        latestUpdateInfo = null,
                        updateCheckInFlight = false,
                        updateError = null,
                        message = if (silent) state.message else "Du nutzt bereits die neueste Version"
                    )
                }
            }
            .onFailure {
                val err = apiError(it, "Update-Pruefung fehlgeschlagen")
                state = state.copy(
                    loading = if (silent) state.loading else false,
                    updateCheckInFlight = false,
                    updateAvailable = false,
                    latestUpdateInfo = null,
                    updateError = err,
                    message = if (silent) state.message else err
                )
            }
    }

    suspend fun checkConnection() {
        state = state.copy(loading = true)
        val startedAt = System.currentTimeMillis()
        runCatching { repo.health() }
            .onSuccess { health ->
                val pingMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                state = state.copy(
                    loading = false,
                    serverConnected = health.ok,
                    serverVersion = health.version,
                    pushProvider = health.provider,
                    chatDeleteSupported = health.features.chatDelete,
                    lastPingMs = pingMs,
                    message = if (health.ok) "Verbindung erfolgreich geprueft" else "Server nicht erreichbar"
                )
            }
            .onFailure {
                state = state.copy(
                    loading = false,
                    serverConnected = false,
                    chatDeleteSupported = false,
                    lastPingMs = null,
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

    fun downloadLatestUpdateFromBadge() {
        val update = state.latestUpdateInfo
        if (update == null) {
            state = state.copy(message = "Keine Update-Information verfuegbar")
            return
        }
        runCatching { repo.downloadLatestApk(update) }
            .onSuccess {
                state = state.copy(
                    message = "Download gestartet: ${update.latestVersion}",
                    updateAvailable = false
                )
            }
            .onFailure { state = state.copy(message = apiError(it, "Download konnte nicht gestartet werden")) }
    }

    suspend fun showChangelogDialog() {
        val lines = fetchChangelogLinesFresh()
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

    fun setThemeMode(mode: Int) {
        when (mode.coerceIn(0, 2)) {
            0 -> {
                repo.setDarkMode(false)
                repo.setOledMode(false)
            }
            1 -> {
                repo.setDarkMode(true)
                repo.setOledMode(false)
            }
            else -> {
                repo.setDarkMode(true)
                repo.setOledMode(true)
            }
        }
        state = state.copy(darkMode = repo.isDarkMode(), oledMode = repo.isOledMode())
    }

    fun setUploadQuality(value: Int) {
        repo.setUploadQuality(value)
        state = state.copy(uploadQuality = repo.uploadQuality())
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        repo.setAutoUpdateEnabled(enabled)
        val auto = repo.autoUpdateEnabled()
        val chat = state.user?.chatPushEnabled ?: repo.chatPushLocalEnabled()
        val feed = repo.feedPostPushEnabled()
        val invite = state.user?.inviteRegistrationPushEnabled ?: repo.inviteRegistrationPushLocalEnabled()
        val master = auto && chat && feed && invite
        repo.setNotificationMasterEnabled(master)
        state = state.copy(
            autoUpdateEnabled = auto,
            notificationMasterEnabled = master
        )
    }

    fun setFeedPostPushEnabled(enabled: Boolean) {
        repo.setFeedPostPushEnabled(enabled)
        val auto = repo.autoUpdateEnabled()
        val chat = state.user?.chatPushEnabled ?: repo.chatPushLocalEnabled()
        val feed = repo.feedPostPushEnabled()
        val invite = state.user?.inviteRegistrationPushEnabled ?: repo.inviteRegistrationPushLocalEnabled()
        val reaction = state.user?.photoReactionPushEnabled ?: repo.photoReactionPushLocalEnabled()
        val comment = state.user?.photoCommentPushEnabled ?: repo.photoCommentPushLocalEnabled()
        val master = auto && chat && feed && invite && reaction && comment
        repo.setNotificationMasterEnabled(master)
        state = state.copy(
            feedPostPushEnabled = feed,
            notificationMasterEnabled = master
        )
    }

    fun setCustomNotificationToneEnabled(enabled: Boolean) {
        repo.setCustomNotificationToneEnabled(enabled)
        state = state.copy(customNotificationToneEnabled = repo.customNotificationToneEnabled())
    }

    fun setCustomNotificationToneUri(uri: String) {
        repo.setCustomNotificationToneUri(uri)
        state = state.copy(customNotificationToneUri = repo.customNotificationToneUri())
    }

    fun testCustomNotificationTone() {
        repo.triggerLocalToneTestNotification()
        state = state.copy(message = "Ton-Test Push gesendet")
    }

    fun downloadPhotoFromViewer(photoUrl: String) {
        val safeUrl = photoUrl.trim()
        if (safeUrl.isBlank()) {
            state = state.copy(message = "Download fehlgeschlagen: keine Bild-URL")
            return
        }
        runCatching { repo.downloadPhotoToDownloads(safeUrl) }
            .onSuccess { state = state.copy(message = "Bild-Download gestartet") }
            .onFailure { state = state.copy(message = apiError(it, "Bild konnte nicht heruntergeladen werden")) }
    }

    suspend fun updateProfile(
        username: String,
        favoriteColor: String,
        bio: String,
        statusText: String,
        statusEmoji: String,
        statusExpiresAt: String?,
        profileVisible: Boolean,
        avatarVisible: Boolean,
        bioVisible: Boolean,
        statusVisible: Boolean,
        quietHoursEnabled: Boolean,
        quietHoursStart: String,
        quietHoursEnd: String
    ) {
        if (username.trim().length < 3) {
            state = state.copy(message = "Benutzername muss mindestens 3 Zeichen haben")
            return
        }
        profileSaveMutex.lock()
        state = state.copy(loading = true)
        try {
            runCatching {
                repo.updateProfile(
                    username = username,
                    favoriteColor = favoriteColor,
                    bio = bio,
                    statusText = statusText,
                    statusEmoji = statusEmoji,
                    statusExpiresAt = statusExpiresAt,
                    profileVisible = profileVisible,
                    avatarVisible = avatarVisible,
                    bioVisible = bioVisible,
                    statusVisible = statusVisible,
                    quietHoursEnabled = quietHoursEnabled,
                    quietHoursStart = quietHoursStart,
                    quietHoursEnd = quietHoursEnd
                )
            }
                .onSuccess { user ->
                    repo.syncQuietHoursFromUser(user)
                    state = state.copy(user = user, loading = false, message = "Profil aktualisiert")
                }
                .onFailure { state = state.copy(loading = false, message = apiError(it, "Profil speichern fehlgeschlagen")) }
        } finally {
            profileSaveMutex.unlock()
        }
    }

    suspend fun uploadAvatar(uri: Uri) {
        state = state.copy(loading = true)
        runCatching { repo.uploadAvatar(uri) }
            .onSuccess { user ->
                state = state.copy(user = user, loading = false, message = "Profilbild aktualisiert")
                refreshAll()
            }
            .onFailure { state = state.copy(loading = false, message = apiError(it, "Profilbild Upload fehlgeschlagen")) }
    }

    suspend fun setChatPushEnabled(enabled: Boolean) {
        state = state.copy(loading = true)
        val allowDownload = state.user?.allowPhotoDownload ?: false
        val inviteEnabled = state.user?.inviteRegistrationPushEnabled ?: repo.inviteRegistrationPushLocalEnabled()
        val reactionEnabled = state.user?.photoReactionPushEnabled ?: repo.photoReactionPushLocalEnabled()
        val commentEnabled = state.user?.photoCommentPushEnabled ?: repo.photoCommentPushLocalEnabled()
        runCatching { repo.updatePreferences(enabled, inviteEnabled, reactionEnabled, commentEnabled, allowDownload) }
            .onSuccess { user ->
                repo.setChatPushLocalEnabled(user.chatPushEnabled)
                repo.setInviteRegistrationPushLocalEnabled(user.inviteRegistrationPushEnabled)
                repo.setPhotoReactionPushLocalEnabled(user.photoReactionPushEnabled)
                repo.setPhotoCommentPushLocalEnabled(user.photoCommentPushEnabled)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = auto && user.chatPushEnabled && feed && user.inviteRegistrationPushEnabled && user.photoReactionPushEnabled && user.photoCommentPushEnabled
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    inviteRegistrationPushEnabled = user.inviteRegistrationPushEnabled,
                    photoReactionPushEnabled = user.photoReactionPushEnabled,
                    photoCommentPushEnabled = user.photoCommentPushEnabled,
                    loading = false,
                    notificationMasterEnabled = master,
                    message = "Chat-Push aktualisiert"
                )
            }
            .onFailure { state = state.copy(loading = false, message = apiError(it, "Chat-Push speichern fehlgeschlagen")) }
    }

    suspend fun setNotificationMasterEnabled(enabled: Boolean) {
        state = state.copy(loading = true)
        repo.setNotificationMasterEnabled(enabled)
        repo.setAutoUpdateEnabled(enabled)
        repo.setFeedPostPushEnabled(enabled)
        repo.setInviteRegistrationPushLocalEnabled(enabled)
        repo.setPhotoReactionPushLocalEnabled(enabled)
        repo.setPhotoCommentPushLocalEnabled(enabled)
        var nextUser = state.user
        if (state.user != null) {
            val allowDownload = state.user?.allowPhotoDownload ?: false
            runCatching { repo.updatePreferences(enabled, enabled, enabled, enabled, allowDownload) }
                .onSuccess {
                    nextUser = it
                    repo.setChatPushLocalEnabled(it.chatPushEnabled)
                    repo.setInviteRegistrationPushLocalEnabled(it.inviteRegistrationPushEnabled)
                    repo.setPhotoReactionPushLocalEnabled(it.photoReactionPushEnabled)
                    repo.setPhotoCommentPushLocalEnabled(it.photoCommentPushEnabled)
                }
                .onFailure {
                    state = state.copy(message = apiError(it, "Master-Benachrichtigung teilweise fehlgeschlagen"))
                }
        } else {
            repo.setChatPushLocalEnabled(enabled)
            repo.setInviteRegistrationPushLocalEnabled(enabled)
            repo.setPhotoReactionPushLocalEnabled(enabled)
            repo.setPhotoCommentPushLocalEnabled(enabled)
        }
        val auto = repo.autoUpdateEnabled()
        val feed = repo.feedPostPushEnabled()
        val chat = nextUser?.chatPushEnabled ?: repo.chatPushLocalEnabled()
        val invite = nextUser?.inviteRegistrationPushEnabled ?: repo.inviteRegistrationPushLocalEnabled()
        val reaction = nextUser?.photoReactionPushEnabled ?: repo.photoReactionPushLocalEnabled()
        val comment = nextUser?.photoCommentPushEnabled ?: repo.photoCommentPushLocalEnabled()
        val masterEffective = auto && feed && chat && invite && reaction && comment
        repo.setNotificationMasterEnabled(masterEffective)
        state = state.copy(
            user = nextUser,
            autoUpdateEnabled = auto,
            feedPostPushEnabled = feed,
            inviteRegistrationPushEnabled = invite,
            photoReactionPushEnabled = reaction,
            photoCommentPushEnabled = comment,
            notificationMasterEnabled = masterEffective,
            loading = false,
            message = if (masterEffective == enabled) {
                if (enabled) "Alle Benachrichtigungen aktiviert" else "Alle Benachrichtigungen deaktiviert"
            } else {
                "Benachrichtigungen teilweise aktualisiert"
            }
        )
    }

    suspend fun setAllowPhotoDownloadEnabled(enabled: Boolean) {
        val current = state.user ?: return
        state = state.copy(loading = true)
        runCatching {
            repo.updatePreferences(
                current.chatPushEnabled,
                current.inviteRegistrationPushEnabled,
                current.photoReactionPushEnabled,
                current.photoCommentPushEnabled,
                enabled
            )
        }
            .onSuccess { user ->
                state = state.copy(
                    user = user,
                    loading = false,
                    message = if (enabled) "Download-Freigabe aktiviert" else "Download-Freigabe deaktiviert"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Download-Freigabe speichern fehlgeschlagen"))
            }
    }

    suspend fun setInviteRegistrationPushEnabled(enabled: Boolean) {
        val current = state.user ?: return
        state = state.copy(loading = true)
        runCatching {
            repo.updatePreferences(
                current.chatPushEnabled,
                enabled,
                current.photoReactionPushEnabled,
                current.photoCommentPushEnabled,
                current.allowPhotoDownload
            )
        }
            .onSuccess { user ->
                repo.setInviteRegistrationPushLocalEnabled(user.inviteRegistrationPushEnabled)
                repo.setPhotoReactionPushLocalEnabled(user.photoReactionPushEnabled)
                repo.setPhotoCommentPushLocalEnabled(user.photoCommentPushEnabled)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = auto && user.chatPushEnabled && feed && user.inviteRegistrationPushEnabled && user.photoReactionPushEnabled && user.photoCommentPushEnabled
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    inviteRegistrationPushEnabled = user.inviteRegistrationPushEnabled,
                    photoReactionPushEnabled = user.photoReactionPushEnabled,
                    photoCommentPushEnabled = user.photoCommentPushEnabled,
                    notificationMasterEnabled = master,
                    loading = false,
                    message = "Push bei neuen Mitgliedern aktualisiert"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Push-Einstellung speichern fehlgeschlagen"))
            }
    }

    suspend fun setPhotoReactionPushEnabled(enabled: Boolean) {
        val current = state.user ?: return
        state = state.copy(loading = true)
        runCatching {
            repo.updatePreferences(
                current.chatPushEnabled,
                current.inviteRegistrationPushEnabled,
                enabled,
                current.photoCommentPushEnabled,
                current.allowPhotoDownload
            )
        }
            .onSuccess { user ->
                repo.setChatPushLocalEnabled(user.chatPushEnabled)
                repo.setInviteRegistrationPushLocalEnabled(user.inviteRegistrationPushEnabled)
                repo.setPhotoReactionPushLocalEnabled(user.photoReactionPushEnabled)
                repo.setPhotoCommentPushLocalEnabled(user.photoCommentPushEnabled)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = auto && user.chatPushEnabled && feed && user.inviteRegistrationPushEnabled && user.photoReactionPushEnabled && user.photoCommentPushEnabled
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    photoReactionPushEnabled = user.photoReactionPushEnabled,
                    notificationMasterEnabled = master,
                    loading = false,
                    message = "Push bei Reaktionen aktualisiert"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Push-Einstellung speichern fehlgeschlagen"))
            }
    }

    suspend fun setPhotoCommentPushEnabled(enabled: Boolean) {
        val current = state.user ?: return
        state = state.copy(loading = true)
        runCatching {
            repo.updatePreferences(
                current.chatPushEnabled,
                current.inviteRegistrationPushEnabled,
                current.photoReactionPushEnabled,
                enabled,
                current.allowPhotoDownload
            )
        }
            .onSuccess { user ->
                repo.setChatPushLocalEnabled(user.chatPushEnabled)
                repo.setInviteRegistrationPushLocalEnabled(user.inviteRegistrationPushEnabled)
                repo.setPhotoReactionPushLocalEnabled(user.photoReactionPushEnabled)
                repo.setPhotoCommentPushLocalEnabled(user.photoCommentPushEnabled)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = auto && user.chatPushEnabled && feed && user.inviteRegistrationPushEnabled && user.photoReactionPushEnabled && user.photoCommentPushEnabled
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    photoCommentPushEnabled = user.photoCommentPushEnabled,
                    notificationMasterEnabled = master,
                    loading = false,
                    message = "Push bei Kommentaren aktualisiert"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Push-Einstellung speichern fehlgeschlagen"))
            }
    }

    fun setMessage(message: String) {
        state = state.copy(message = message)
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

    private fun isActiveMomentWindow(prompt: PromptResponse): Boolean {
        return prompt.canUpload && !prompt.triggered.isNullOrBlank()
    }

    private suspend fun applyPendingLaunchNavigation(prompt: PromptResponse, availableDays: List<String>) {
        val pending = repo.consumePendingLaunchAction()
        val action = pending.action.trim().lowercase()
        val type = pending.type.trim().lowercase()
        val targetDay = pending.targetDay.trim()
        val targetPhotoId = pending.targetPhotoId
        if (action.isBlank() && type.isBlank() && targetDay.isBlank() && targetPhotoId == null) return

        fun openCamera(message: String? = null) {
            state = state.copy(
                activeTab = AppTab.CAMERA,
                message = message ?: state.message
            )
        }

        fun resolveFeedDay(): String? {
            if (targetDay.isNotBlank() && availableDays.contains(targetDay)) return targetDay
            if (targetDay == prompt.day && !prompt.hasAnyPostToday) return null
            if (prompt.hasAnyPostToday && availableDays.contains(prompt.day)) return prompt.day
            return availableDays.firstOrNull()
        }

        when {
            action == "open_chat" || type == "chat" || type == "chat_message" || type == "invite_registered" || type == "invite_registration" -> {
                setTab(AppTab.CHAT)
            }

            action == "open_feed" || type == "feed_post" || type == "post" || type == "extra_post" || type == "photo_reaction" || type == "photo_comment" || targetDay.isNotBlank() || targetPhotoId != null -> {
                val targetIsTodayHidden = targetDay == prompt.day && !prompt.hasAnyPostToday && !availableDays.contains(targetDay)
                if (targetIsTodayHidden) {
                    openCamera("Der heutige Feed wird sichtbar, sobald du selbst gepostet hast.")
                    return
                }
                val day = resolveFeedDay()
                if (day == null) {
                    openCamera()
                    return
                }
                state = state.copy(
                    activeTab = AppTab.FEED,
                    feedFocusDay = day,
                    feedFocusPhotoId = targetPhotoId
                )
            }

            action == "open_camera" || type == "daily_prompt" || type == "daily_moment" || type == "special_moment" || type == "special_request" -> {
                openCamera()
            }

            else -> {
                openCamera()
            }
        }
    }
}

class MainVmFactory(private val repo: AppRepo) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MainVm(repo) as T
}

class MainActivity : ComponentActivity() {
    private lateinit var repo: AppRepo
    private var launchIntentTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val api = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(Api::class.java)
        repo = AppRepo(api, this)
        repo.installCrashHandler()
        repo.captureLaunchIntent(intent)

        setContent {
            val intentTick = launchIntentTick
            val vm: MainVm = viewModel(factory = MainVmFactory(repo))
            val useDark = vm.state.darkMode
            val useOled = vm.state.oledMode
            val oledColorScheme = darkColorScheme(
                background = Color.Black,
                surface = Color.Black
            )
            MaterialTheme(colorScheme = if (useDark) (if (useOled) oledColorScheme else darkColorScheme()) else lightColorScheme()) {
                AppScreen(vm, intentTick)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::repo.isInitialized) {
            repo.captureLaunchIntent(intent)
        }
        launchIntentTick += 1
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: MainVm, launchIntentTick: Int = 0) {
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
    var captureCapsule by remember { mutableStateOf(CapsuleUploadOptions()) }
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
    var viewerOwnDownloadFallback by remember { mutableStateOf(false) }
    var viewerComment by remember { mutableStateOf("") }
    var profileAvatarPreviewUrl by remember { mutableStateOf("") }
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
                            val ok = vm.enqueueDualUpload(
                                back,
                                front,
                                asPrompt,
                                if (asPrompt) CapsuleUploadOptions() else captureCapsule
                            )
                            cameraUploading = false
                            if (ok) {
                                backPreviewUri = null
                                frontPreviewUri = null
                                captureCapsule = CapsuleUploadOptions()
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
    val notificationTonePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            vm.setCustomNotificationToneUri(picked?.toString().orEmpty())
        }
    }

    fun openCameraFor(target: String) {
        val uri = createTempImageUri(context)
        captureTarget = target
        captureUri = uri
        cameraLauncher.launch(uri)
    }

    fun startDualCapture(asPrompt: Boolean, capsule: CapsuleUploadOptions = CapsuleUploadOptions()) {
        captureAsPrompt = asPrompt
        captureCapsule = if (asPrompt) CapsuleUploadOptions() else capsule
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
            appVersion = BuildConfig.VERSION_NAME,
            startupQuote = state.startupQuote
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

    LaunchedEffect(state.token, state.startupDone) {
        if (state.token.isBlank() || !state.startupDone) return@LaunchedEffect
        while (true) {
            vm.refreshUploadQueueLocal()
            delay(1_000)
        }
    }

    LaunchedEffect(launchIntentTick, state.token, state.startupDone) {
        if (launchIntentTick <= 0) return@LaunchedEffect
        if (state.token.isBlank() || !state.startupDone) return@LaunchedEffect
        vm.refreshAll()
    }

    LaunchedEffect(state.token, state.startupDone, state.diagnosticsUploadEnabled) {
        if (state.token.isBlank() || !state.startupDone || !state.diagnosticsUploadEnabled) return@LaunchedEffect
        vm.autoUploadDebugLogsIfEnabled()
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
        DailyMomentStartOverlay(
            onCaptureNow = {
                vm.dismissPromptDialog()
                startDualCapture(true)
            },
            onLater = { vm.dismissPromptDialog() }
        )
    }

    if (state.showProfileSetupPrompt) {
        AlertDialog(
            onDismissRequest = { vm.profileSetupPromptNo() },
            confirmButton = {
                TextButton(onClick = { vm.profileSetupPromptYes() }) { Text("Ja") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.profileSetupPromptNo() }) { Text("Nein") }
                    TextButton(onClick = { vm.profileSetupPromptNeverAsk() }) { Text("Nicht mehr fragen") }
                }
            },
            title = { Text("Profil einrichten?") },
            text = { Text("Moechtest du Profilbild, Kurzbeschreibung, Status und Sichtbarkeit jetzt einrichten? Alles ist optional.") }
        )
    }

    if (state.showProfileSetupGuide) {
        val step = state.profileSetupStep
        val title = when (step) {
            0 -> "Setup: Profil & Datenschutz"
            1 -> "Setup: Sichtbarkeit"
            else -> "Setup: Benachrichtigungen & Ruhezeit"
        }
        val body = when (step) {
            0 -> "Lege Profilbild, Kurzbeschreibung und Status fest. Alles bleibt privat, bis du es sichtbar schaltest."
            1 -> "Schalte separat frei: Profil aufrufbar, Profilbild sichtbar, Bio sichtbar, Status sichtbar."
            else -> "Aktiviere Ruhezeiten, wenn du nachts keine Pushs willst. Daily- und Sondermoment bleiben als Ausnahmen aktiv."
        }
        AlertDialog(
            onDismissRequest = { vm.closeProfileSetupGuide(markCompleted = false) },
            confirmButton = {
                TextButton(onClick = {
                    when (step) {
                        0 -> {
                            vm.jumpToSetupSection("profile_account")
                            vm.nextProfileSetupStep()
                        }
                        1 -> {
                            vm.jumpToSetupSection("profile_privacy")
                            vm.nextProfileSetupStep()
                        }
                        else -> {
                            vm.jumpToSetupSection("profile_privacy")
                            vm.closeProfileSetupGuide(markCompleted = true)
                        }
                    }
                }) { Text(if (step < 2) "Weiter" else "Fertig") }
            },
            dismissButton = {
                TextButton(onClick = { vm.closeProfileSetupGuide(markCompleted = false) }) { Text("Spaeter") }
            },
            title = { Text(title) },
            text = { Text(body) }
        )
    }

    state.viewedProfile?.let { profile ->
        val profileUsername = safeApiString(profile.user.username, "unbekannt")
        val profileAvatarUrl = safeApiString(profile.user.avatarUrl)
        val profileBio = safeApiString(profile.user.bio)
        val profileStatusText = safeApiString(profile.user.statusText)
        val profileStatusEmoji = safeApiString(profile.user.statusEmoji)
        AlertDialog(
            onDismissRequest = {
                profileAvatarPreviewUrl = ""
                vm.closeViewedProfile()
            },
            confirmButton = {
                TextButton(onClick = {
                    profileAvatarPreviewUrl = ""
                    vm.closeViewedProfile()
                }) { Text("Schliessen") }
            },
            title = { Text("@$profileUsername") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!profile.profileVisible && !profile.isSelf) {
                        Text("Profil privat")
                    } else {
                        if (profileAvatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = profileAvatarUrl,
                                contentDescription = "Avatar",
                                modifier = Modifier
                                    .size(84.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                    .clickable { profileAvatarPreviewUrl = profileAvatarUrl }
                            )
                        }
                        if (profileBio.isNotBlank()) {
                            Text(profileBio)
                        }
                        if (profile.user.statusVisible && (profileStatusText.isNotBlank() || profileStatusEmoji.isNotBlank())) {
                            Text("$profileStatusEmoji $profileStatusText".trim())
                        }
                        if (profile.photos.isNotEmpty()) {
                            val rows = profile.photos.chunked(3)
                            rows.forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    for (i in 0 until 3) {
                                        val item = row.getOrNull(i)
                                        if (item == null) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        } else {
                                            val urls = listOfNotNull(item.url, item.secondUrl)
                                            AsyncImage(
                                                model = item.url,
                                                contentDescription = "Profilbild",
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(92.dp)
                                                    .clickable {
                                                        profileAvatarPreviewUrl = ""
                                                        vm.closeViewedProfile()
                                                        viewerUrls = urls
                                                        viewerIndex = 0
                                                        viewerPhotoId = item.id
                                                    },
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }
    if (profileAvatarPreviewUrl.isNotBlank()) {
        Dialog(
            onDismissRequest = { profileAvatarPreviewUrl = "" },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.82f))
                    .clickable { profileAvatarPreviewUrl = "" },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = profileAvatarPreviewUrl,
                    contentDescription = "Profilbild Grossansicht",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
    if (state.viewedProfileLoading) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Profil wird geladen") },
            text = { Text("Bitte kurz warten ...") }
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
                    vm.downloadLatestUpdateFromBadge()
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
        val closeViewer = {
            viewerUrls = emptyList()
            viewerIndex = 0
            viewerPhotoId = null
            viewerOwnDownloadFallback = false
            viewerComment = ""
            vm.clearPhotoInteractions()
        }
        FullscreenPhotoViewer(
            urls = viewerUrls,
            initialIndex = viewerIndex,
            photoId = viewerPhotoId,
            comment = viewerComment,
            interactions = state.photoInteractions,
            interactionsLoading = state.interactionsLoading,
            ownDownloadFallback = viewerOwnDownloadFallback,
            onCommentChange = { viewerComment = it },
            onCommentSend = {
                val body = viewerComment
                if (body.isNotBlank()) {
                    scope.launch {
                        vm.commentPhoto(viewerPhotoId ?: 0L, body)
                        viewerComment = ""
                    }
                }
            },
            onReact = { emoji ->
                val pid = viewerPhotoId ?: return@FullscreenPhotoViewer
                scope.launch { vm.reactPhoto(pid, emoji) }
            },
            onDoubleTapReact = {
                val pid = viewerPhotoId ?: return@FullscreenPhotoViewer
                val emoji = viewerReactionEmojis[Random.nextInt(viewerReactionEmojis.size)]
                scope.launch { vm.reactPhoto(pid, emoji) }
            },
            onDownloadCurrent = { photoUrl ->
                vm.downloadPhotoFromViewer(photoUrl)
            },
            onIndexChange = { viewerIndex = it },
            onClose = closeViewer
        )
    }

    if (state.token.isBlank()) {
        var loginPasswordVisible by rememberSaveable { mutableStateOf(false) }
        var registerPasswordVisible by rememberSaveable { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Daily",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text("App-Version: ${BuildConfig.VERSION_NAME}")
                    Text(
                        text = if (state.updateAvailable) "(nicht aktuell)" else "(aktuell)",
                        color = if (state.updateAvailable) Color(0xFFD32F2F) else Color(0xFF2E7D32),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
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
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Passwort") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (loginPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { loginPasswordVisible = !loginPasswordVisible }) {
                            Icon(
                                imageVector = if (loginPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (loginPasswordVisible) "Passwort verbergen" else "Passwort anzeigen"
                            )
                        }
                    }
                )
                SpecialMomentActionButton(
                    text = "Einloggen",
                    onClick = { scope.launch { vm.login(username, password) } },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    value = inviteCodeInput,
                    onValueChange = { inviteCodeInput = it.uppercase() },
                    label = { Text("Invite-Code") },
                    modifier = Modifier.fillMaxWidth()
                )
                SpecialMomentActionButton(
                    text = "Code pruefen",
                    onClick = { scope.launch { vm.previewInvite(inviteCodeInput) } },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth()
                )

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
                    val registerPasswordInvalid = password.isNotBlank() && !isPasswordValid(password)
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Neuer Benutzername") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Passwort") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = registerPasswordInvalid,
                        visualTransformation = if (registerPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { registerPasswordVisible = !registerPasswordVisible }) {
                                Icon(
                                    imageVector = if (registerPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (registerPasswordVisible) "Passwort verbergen" else "Passwort anzeigen"
                                )
                            }
                        },
                        supportingText = {
                            Text(
                                passwordRequirementText,
                                color = if (registerPasswordInvalid) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                    Button(
                        onClick = {
                            if (!isPasswordValid(password)) {
                                vm.setMessage("Das Passwort muss mindestens 6 Zeichen haben.")
                            } else {
                                scope.launch {
                                    vm.registerWithInvite(inviteCodeInput, username, password)
                                }
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
                    updateAvailable = state.updateAvailable,
                    updateCheckInFlight = state.updateCheckInFlight,
                    specialMomentStatus = state.specialMomentStatus,
                    backPreviewUri = backPreviewUri,
                    frontPreviewUri = frontPreviewUri,
                    onDownloadUpdate = { vm.downloadLatestUpdateFromBadge() },
                    onCapturePrompt = { startDualCapture(true) },
                    onCaptureExtra = { capsule -> startDualCapture(false, capsule) },
                    onRequestSpecialMoment = { showSpecialMomentConfirm = true },
                    onReset = {
                        backPreviewUri = null
                        frontPreviewUri = null
                        captureCapsule = CapsuleUploadOptions()
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
                                val ok = vm.enqueueDualUpload(
                                    back,
                                    front,
                                    asPrompt,
                                    if (asPrompt) CapsuleUploadOptions() else captureCapsule
                                )
                                cameraUploading = false
                                if (ok) {
                                    cameraUploadPercent = 100
                                    cameraUploadDone = true
                                    backPreviewUri = null
                                    frontPreviewUri = null
                                    captureCapsule = CapsuleUploadOptions()
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
                    onRetryQueuedAsExtra = { id -> vm.retryQueuedUploadAsExtra(id) },
                    onRemoveQueued = { id -> vm.removeQueuedUpload(id) },
                    onOpenViewer = { urls, photoId ->
                        viewerUrls = urls
                        viewerIndex = 0
                        viewerPhotoId = photoId
                        val isOwn = photoId != null && state.photos.any { it.id == photoId }
                        viewerOwnDownloadFallback = isOwn && (state.user?.allowPhotoDownload == true)
                    }
                )

                AppTab.FEED -> FeedTab(
                    prompt = state.prompt,
                    days = state.feedDays,
                    byDay = state.feedByDay,
                    monthRecapByDay = state.monthRecapByDay,
                    promptMetaByDay = state.promptMetaByDay,
                    focusDay = state.feedFocusDay,
                    focusPhotoId = state.feedFocusPhotoId,
                    listState = feedListState,
                    refreshing = state.feedRefreshing,
                    todayLocked = state.feedTodayLocked,
                    paging = state.feedPaging,
                    onTakePhoto = { vm.setTab(AppTab.CAMERA) },
                    onRefresh = { scope.launch { vm.refreshFeed() } },
                    onLoadOlder = { scope.launch { vm.loadOlderFeedDays() } },
                    onLoadNewer = { scope.launch { vm.loadNewerFeedDays() } },
                    onJumpToCapsule = { day, photoId -> scope.launch { vm.jumpToPhoto(day, photoId) } },
                    onFocusPhotoConsumed = { vm.clearFeedPhotoFocus() },
                    onOpenUserProfile = { userId -> scope.launch { vm.loadUserProfile(userId) } },
                    onOpenViewer = { urls, photoId ->
                        viewerUrls = urls
                        viewerIndex = 0
                        viewerPhotoId = photoId
                        val isOwn = photoId != null && state.photos.any { it.id == photoId }
                        viewerOwnDownloadFallback = isOwn && (state.user?.allowPhotoDownload == true)
                    }
                )

                AppTab.CALENDAR -> CalendarTab(
                    days = state.calendarDays,
                    dayStats = state.calendarDayStats,
                    monthRecapByDay = state.monthRecapByDay,
                    promptMetaByDay = state.promptMetaByDay,
                    selected = state.feedFocusDay ?: state.prompt?.day.orEmpty(),
                    onSelect = { day ->
                        scope.launch { vm.jumpToDay(day) }
                    }
                )

                AppTab.CHAT -> ChatTab(
                    items = state.chat,
                    meId = state.user?.id,
                    chatDeleteSupported = state.chatDeleteSupported,
                    input = chatInput,
                    sending = state.chatSending,
                    onInput = { chatInput = it },
                    onOpenUserProfile = { userId -> scope.launch { vm.loadUserProfile(userId) } },
                    onDeleteMessage = { messageId ->
                        scope.launch { vm.deleteChatMessage(messageId) }
                    },
                    onSend = {
                        val body = chatInput
                        if (body.isNotBlank() && !state.chatSending) {
                            scope.launch {
                                val ok = vm.sendChat(body)
                                if (ok) chatInput = ""
                            }
                        }
                    }
                )

                AppTab.PROFILE -> ProfileTab(
                    username = state.user?.username ?: "",
                    inviteCode = state.myInviteCode,
                    streakDays = state.streakDays,
                    dailyMomentCount = state.dailyMomentCount,
                    promptRules = state.promptRules,
                    photos = state.photos,
                    themeMode = themeModeValue(state.darkMode, state.oledMode),
                    currentPassword = pwCurrent,
                    newPassword = pwNext,
                    editableUsername = profileUsername,
                    editableColor = profileColor,
                    appVersion = BuildConfig.VERSION_NAME,
                    updateAvailable = state.updateAvailable,
                    serverVersion = state.serverVersion,
                    pushProvider = state.pushProvider,
                    apiBaseUrl = BuildConfig.API_BASE_URL,
                    serverConnected = state.serverConnected,
                    lastPingMs = state.lastPingMs,
                    uploadQuality = state.uploadQuality,
                    autoUpdateEnabled = state.autoUpdateEnabled,
                    notificationMasterEnabled = state.notificationMasterEnabled,
                    chatPushEnabled = state.user?.chatPushEnabled ?: false,
                    inviteRegistrationPushEnabled = state.user?.inviteRegistrationPushEnabled ?: state.inviteRegistrationPushEnabled,
                    photoReactionPushEnabled = state.user?.photoReactionPushEnabled ?: state.photoReactionPushEnabled,
                    photoCommentPushEnabled = state.user?.photoCommentPushEnabled ?: state.photoCommentPushEnabled,
                    allowPhotoDownload = state.user?.allowPhotoDownload ?: false,
                    feedPostPushEnabled = state.feedPostPushEnabled,
                    customNotificationToneEnabled = state.customNotificationToneEnabled,
                    customNotificationToneUri = state.customNotificationToneUri,
                    diagnosticsUploadEnabled = state.diagnosticsUploadEnabled,
                    debugLogs = state.debugLogs,
                    profileSetupJumpTarget = state.profileSetupJumpTarget,
                    profileSectionsExpanded = state.profileSectionExpanded,
                    avatarUrl = state.user?.avatarUrl.orEmpty(),
                    bio = state.user?.bio.orEmpty(),
                    statusText = state.user?.statusText.orEmpty(),
                    statusEmoji = state.user?.statusEmoji.orEmpty(),
                    statusExpiresAt = state.user?.statusExpiresAt,
                    profileVisible = state.user?.profileVisible ?: false,
                    avatarVisible = state.user?.avatarVisible ?: false,
                    bioVisible = state.user?.bioVisible ?: false,
                    statusVisible = state.user?.statusVisible ?: false,
                    quietHoursEnabled = state.user?.quietHoursEnabled ?: false,
                    quietHoursStart = state.user?.quietHoursStart ?: "22:00",
                    quietHoursEnd = state.user?.quietHoursEnd ?: "07:00",
                    communityStats = state.communityStats,
                    communityStatsLoading = state.communityStatsLoading,
                    onThemeModeChange = { vm.setThemeMode(it) },
                    onUploadQualityChange = { vm.setUploadQuality(it) },
                    onAutoUpdateEnabledChange = { vm.setAutoUpdateEnabled(it) },
                    onChatPushEnabledChange = { scope.launch { vm.setChatPushEnabled(it) } },
                    onInviteRegistrationPushEnabledChange = { scope.launch { vm.setInviteRegistrationPushEnabled(it) } },
                    onPhotoReactionPushEnabledChange = { scope.launch { vm.setPhotoReactionPushEnabled(it) } },
                    onPhotoCommentPushEnabledChange = { scope.launch { vm.setPhotoCommentPushEnabled(it) } },
                    onAllowPhotoDownloadChange = { scope.launch { vm.setAllowPhotoDownloadEnabled(it) } },
                    onNotificationMasterEnabledChange = { scope.launch { vm.setNotificationMasterEnabled(it) } },
                    onFeedPostPushEnabledChange = { vm.setFeedPostPushEnabled(it) },
                    onCustomNotificationToneEnabledChange = { vm.setCustomNotificationToneEnabled(it) },
                    onPickCustomNotificationTone = {
                        val currentUri = state.customNotificationToneUri.trim().takeIf { it.isNotBlank() }?.let(Uri::parse)
                        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Benachrichtigungston waehlen")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
                        }
                        notificationTonePickerLauncher.launch(intent)
                    },
                    onClearCustomNotificationTone = { vm.setCustomNotificationToneUri("") },
                    onTestCustomNotificationTone = { vm.testCustomNotificationTone() },
                    onDiagnosticsUploadEnabledChange = { vm.setDiagnosticsUploadEnabled(it) },
                    onRefreshDebugLogs = { vm.refreshDebugLogs() },
                    onShareDebugLogs = {
                        val uri = vm.exportDebugLogsForShare()
                        if (uri != null) {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "Daily Diagnose Export")
                                putExtra(Intent.EXTRA_TEXT, "Diagnose-Export aus Daily")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Diagnose teilen"))
                        }
                    },
                    onProfileSectionExpandedChange = { sectionId, expanded -> vm.setProfileSectionExpanded(sectionId, expanded) },
                    onUploadAvatar = { uri -> scope.launch { vm.uploadAvatar(uri) } },
                    onEditableUsernameChange = { profileUsername = it },
                    onEditableColorChange = { profileColor = it },
                    onAutoSaveProfile = { usernameValue, colorValue, bioValue, statusTextValue, statusEmojiValue, statusExpiresAtValue, profileVisibleValue, avatarVisibleValue, bioVisibleValue, statusVisibleValue, quietEnabledValue, quietStartValue, quietEndValue ->
                        scope.launch {
                            vm.updateProfile(
                                username = usernameValue,
                                favoriteColor = colorValue,
                                bio = bioValue,
                                statusText = statusTextValue,
                                statusEmoji = statusEmojiValue,
                                statusExpiresAt = statusExpiresAtValue,
                                profileVisible = profileVisibleValue,
                                avatarVisible = avatarVisibleValue,
                                bioVisible = bioVisibleValue,
                                statusVisible = statusVisibleValue,
                                quietHoursEnabled = quietEnabledValue,
                                quietHoursStart = quietStartValue,
                                quietHoursEnd = quietEndValue
                            )
                        }
                    },
                    onConsumeSetupJumpTarget = { vm.consumeProfileSetupJumpTarget() },
                    onCurrentPasswordChange = { pwCurrent = it },
                    onNewPasswordChange = { pwNext = it },
                    onChangePassword = {
                        if (!isPasswordValid(pwNext)) {
                            vm.setMessage("Das Passwort muss mindestens 6 Zeichen haben.")
                        } else if (pwCurrent.isNotBlank() && pwNext.isNotBlank()) {
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
                    onOpenSetupGuide = { vm.openProfileSetupGuide() },
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
                    onDeletePhoto = { photoId -> scope.launch { vm.deleteMyPhoto(photoId) } },
                    onOpenViewer = { urls, photoId ->
                        viewerUrls = urls
                        viewerIndex = 0
                        viewerPhotoId = photoId
                        val isOwn = photoId != null && state.photos.any { it.id == photoId }
                        viewerOwnDownloadFallback = isOwn && (state.user?.allowPhotoDownload == true)
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
fun StartupScreen(serverConnected: Boolean, appVersion: String, startupQuote: String) {
    val transition = rememberInfiniteTransition(label = "startup")
    val pulseA by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-a"
    )
    val pulseB by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-b"
    )
    val logoScale by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo-scale"
    )
    val dotsPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "connect-dots"
    )
    val dots = ".".repeat((dotsPhase * 3f).toInt().coerceIn(0, 3))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(1200f, 2200f)
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(146.dp)) {
                    Box(
                        modifier = Modifier
                            .size((130f * pulseA).dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size((130f * pulseB).dp)
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f), CircleShape)
                    )
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Daily Logo",
                        modifier = Modifier
                            .size(92.dp)
                            .graphicsLayer {
                                scaleX = logoScale
                                scaleY = logoScale
                            }
                    )
                }

                Text("Daily", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                Text(
                    if (serverConnected) "Verbindung zum Server hergestellt" else "Verbindung zum Server$dots",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (serverConnected && startupQuote.isNotBlank()) {
                    Text(
                        "\"$startupQuote\"",
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }
                Text("App-Version: $appVersion", color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun CameraTab(
    prompt: PromptResponse?,
    promptRules: PromptRulesResponse?,
    updateAvailable: Boolean,
    updateCheckInFlight: Boolean,
    specialMomentStatus: SpecialMomentStatus?,
    backPreviewUri: Uri?,
    frontPreviewUri: Uri?,
    onDownloadUpdate: () -> Unit,
    onCapturePrompt: () -> Unit,
    onCaptureExtra: (CapsuleUploadOptions) -> Unit,
    onRequestSpecialMoment: () -> Unit,
    onReset: () -> Unit,
    onRetryUpload: () -> Unit,
    uploading: Boolean,
    uploadPercent: Int,
    uploadDone: Boolean,
    uploadError: String,
    uploadQueue: List<QueuedUploadItem>,
    onRetryQueued: (String) -> Unit,
    onRetryQueuedAsExtra: (String) -> Unit,
    onRemoveQueued: (String) -> Unit,
    onOpenViewer: (List<String>, Long?) -> Unit
) {
    val hasPromptPosted = prompt?.hasPromptPostedToday == true
    val hasAnyPosted = prompt?.hasAnyPostToday == true
    val canUpload = prompt?.canUpload == true
    val canSpecial = specialMomentStatus?.canRequest == true
    var showCapsuleDialog by remember { mutableStateOf(false) }
    var pendingCapsule by remember { mutableStateOf<CapsuleUploadOptions?>(null) }
    val dayLabel = formatDayLabel(prompt?.day ?: LocalDate.now().toString())
    val specialLabel = if (canSpecial) {
        "Sondermoment anfordern"
    } else {
        val rem = specialMomentStatus?.remainingSeconds ?: 0L
        "Sondermoment schon angefordert, naechster Sondermoment in ${formatRemaining(rem)}"
    }
    val updatePulse = rememberInfiniteTransition(label = "camera-update-pulse")
    val updateScale by updatePulse.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "camera-update-scale"
    )
    val updateAlpha by updatePulse.animateFloat(
        initialValue = 0.74f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "camera-update-alpha"
    )

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RainbowDailyTitle()
                if (updateAvailable) {
                    Text(
                        text = "UPDATE VERFUEGBAR",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = updateScale
                                scaleY = updateScale
                                alpha = updateAlpha
                            }
                            .background(Color(0xFFD32F2F), shape = MaterialTheme.shapes.small)
                            .clickable(onClick = onDownloadUpdate)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                } else if (updateCheckInFlight) {
                    Text("Update-Check ...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(dayLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!prompt?.triggered.isNullOrBlank()) {
            Text("Der heutige Moment war um ${formatMomentTime(prompt?.triggered)}.")
        } else {
            Text("Der heutige Moment ist noch nicht gekommen.")
        }
        if (promptRules != null) {
            Text("Zeitfenster heute: ${promptRules.promptWindowStartHour}:00-${promptRules.promptWindowEndHour}:00")
        }

        if (hasAnyPosted) {
            if (canUpload) {
                Text("Daily-Moment gerade aktiv.", fontWeight = FontWeight.Bold)
                Text(
                    if (hasPromptPosted) "Du hast dein Daily-Moment heute schon gepostet."
                    else "Du kannst jetzt noch dein echtes Daily-Moment fuer den aktiven Moment aufnehmen.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("Du hast heute gepostet.", fontWeight = FontWeight.Bold)
            }
            val ownUrls = if (hasPromptPosted) listOfNotNull(prompt?.ownPhoto?.url, prompt?.ownPhoto?.secondUrl) else emptyList()
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
            if (canUpload && !hasPromptPosted) {
                DailyMomentActionButton(
                    onClick = onCapturePrompt,
                    blink = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onCaptureExtra(CapsuleUploadOptions()) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Stattdessen als Extra posten") }
            } else {
                Button(
                    onClick = { onCaptureExtra(CapsuleUploadOptions()) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Weitere Bilder hinzufuegen") }
            }
            if (!canUpload) {
                TextButton(
                    onClick = { showCapsuleDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Fuer spaeter merken")
                }
                SpecialMomentActionButton(
                    text = specialLabel,
                    onClick = onRequestSpecialMoment,
                    enabled = canSpecial,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    "Time Capsule und Sondermoment sind waehrend des aktiven Daily-Fensters gesperrt.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text("Heute sind zwei Fotos noetig: Rueckkamera und Frontkamera.")
            if (prompt?.triggered.isNullOrBlank()) {
                Text("Der Moment ist noch nicht gestartet. Du kannst jetzt schon ein Extra posten.")
            } else if (canUpload) {
                Text("Daily-Moment gerade aktiv.", fontWeight = FontWeight.Bold)
            } else {
                Text("Momentfenster vorbei. Du kannst trotzdem ein Extra posten.")
            }

            if (backPreviewUri == null) {
                if (canUpload) {
                    DailyMomentActionButton(
                        onClick = onCapturePrompt,
                        blink = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { onCaptureExtra(CapsuleUploadOptions()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Oder als Extra posten") }
                } else {
                    Button(
                        onClick = { onCaptureExtra(CapsuleUploadOptions()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Extra posten") }
                }
                if (!canUpload) {
                    SpecialMomentActionButton(
                        text = specialLabel,
                        onClick = onRequestSpecialMoment,
                        enabled = canSpecial,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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

        val queueItems = visibleQueueItems(uploadQueue)
        if (queueItems.isNotEmpty()) {
            Text("Upload-Queue", style = MaterialTheme.typography.titleMedium)
            queueItems.forEach { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val kindLabel = if (item.isPrompt) "Tagesmoment" else "Extra"
                        Text("$kindLabel - ${queueStatusLabel(item.status)}", fontWeight = FontWeight.SemiBold)
                        if (item.status == UploadQueueStatus.RUNNING) {
                            val p = item.progressPercent.coerceIn(0, 100)
                            Text("Fortschritt: $p%")
                            LinearProgressIndicator(
                                progress = p / 100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text("Versuche: ${item.attempts}")
                        if (item.lastError.isNotBlank()) {
                            Text(item.lastError, color = Color(0xFF8B0000), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (item.status == UploadQueueStatus.FAILED) {
                            Button(onClick = { onRetryQueued(item.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Erneut versuchen")
                            }
                            if (item.isPrompt) {
                                Button(onClick = { onRetryQueuedAsExtra(item.id) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Als Extra posten")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(
                                    onClick = { onRemoveQueued(item.id) },
                                    modifier = Modifier.background(
                                        color = Color(0xFFD32F2F),
                                        shape = CircleShape
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Upload entfernen",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCapsuleDialog) {
        AlertDialog(
            onDismissRequest = { showCapsuleDialog = false },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCapsuleDialog = false }) { Text("Schliessen") }
            },
            title = { Text("Fuer spaeter merken") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Achtung: Wenn du eine Time Capsule waehlst, bleibt der Beitrag bis zum gewaehlten Datum verborgen.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            showCapsuleDialog = false
                            pendingCapsule = CapsuleUploadOptions(mode = "7d")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("in einer Woche zeigen 👶") }
                    Button(
                        onClick = {
                            showCapsuleDialog = false
                            pendingCapsule = CapsuleUploadOptions(mode = "30d")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("in 30 Tagen zeigen 🧑") }
                    Button(
                        onClick = {
                            showCapsuleDialog = false
                            pendingCapsule = CapsuleUploadOptions(mode = "1y")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("in einem Jahr zeigen 💀") }
                }
            }
        )
    }
    pendingCapsule?.let { selected ->
        val label = when (selected.mode) {
            "7d" -> "in einer Woche"
            "30d" -> "in 30 Tagen"
            "1y" -> "in einem Jahr"
            else -> selected.mode
        }
        AlertDialog(
            onDismissRequest = { pendingCapsule = null },
            confirmButton = {
                Button(onClick = {
                    showCapsuleDialog = false
                    pendingCapsule = null
                    onCaptureExtra(selected)
                }) { Text("Ja, Time Capsule starten") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCapsule = null }) { Text("Abbrechen") }
            },
            title = { Text("Bitte bestaetigen") },
            text = { Text("Du siehst diesen Beitrag dann erst wieder $label. Wirklich fortfahren?") }
        )
    }

}

@Composable
private fun DailyMomentActionButton(
    onClick: () -> Unit,
    blink: Boolean,
    modifier: Modifier = Modifier
) {
    if (blink) {
        SpecialMomentActionButton(
            text = "Daily-Moment posten",
            onClick = onClick,
            enabled = true,
            modifier = modifier
        )
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            )
        ) {
            Text("Daily-Moment posten")
        }
    }
}

@Composable
private fun RainbowDailyTitle() {
    val transition = rememberInfiniteTransition(label = "daily-title-rainbow")
    val hueShift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "daily-title-hue"
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            rainbowColor(hueShift + 0f),
            rainbowColor(hueShift + 90f),
            rainbowColor(hueShift + 180f),
            rainbowColor(hueShift + 270f)
        )
    )
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(brush = brush)) {
                append("Daily")
            }
        },
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
    )
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
@OptIn(ExperimentalMaterialApi::class)
fun FeedTab(
    prompt: PromptResponse?,
    days: List<String>,
    byDay: Map<String, List<FeedItem>>,
    monthRecapByDay: Map<String, MonthlyRecap>,
    promptMetaByDay: Map<String, PromptMeta>,
    focusDay: String?,
    focusPhotoId: Long?,
    listState: LazyListState,
    refreshing: Boolean,
    todayLocked: Boolean,
    paging: Boolean,
    onTakePhoto: () -> Unit,
    onRefresh: () -> Unit,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    onJumpToCapsule: (day: String, photoId: Long) -> Unit,
    onFocusPhotoConsumed: () -> Unit,
    onOpenUserProfile: (Long) -> Unit,
    onOpenViewer: (List<String>, Long?) -> Unit
) {
    val primaryTextColor = MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pullRefreshState = rememberPullRefreshState(refreshing = refreshing, onRefresh = onRefresh)

    val rows = remember(days, byDay, monthRecapByDay, promptMetaByDay) {
        buildList {
            for (day in days) {
                add(FeedRow.DayHeader(day, promptMetaByDay[day]))
                byDay[day].orEmpty().forEach { add(FeedRow.PhotoItem(day, it)) }
                monthRecapByDay[day]?.let { add(FeedRow.MonthRecapItem(day, it)) }
            }
        }
    }

    val todayDay = prompt?.day ?: LocalDate.now().toString()
    val capsuleTargets = remember(rows, todayDay) {
        rows.asSequence()
            .filterIsInstance<FeedRow.PhotoItem>()
            .map { it.day to it.item }
            .filter { (day, item) ->
                day != todayDay &&
                    item.capsuleReleased
            }
            .map { (day, item) -> day to item.photo.id }
            .distinct()
            .toList()
    }

    LaunchedEffect(focusDay, focusPhotoId, rows.size) {
        val idx = if (focusPhotoId != null) {
            rows.indexOfFirst { it is FeedRow.PhotoItem && it.item.photo.id == focusPhotoId }
        } else {
            val target = focusDay ?: return@LaunchedEffect
            rows.indexOfFirst { it is FeedRow.DayHeader && it.day == target }
        }
        if (idx >= 0) {
            listState.scrollToItem(idx)
        }
        if (focusPhotoId != null) {
            onFocusPhotoConsumed()
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pullRefresh(pullRefreshState)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Noch keine Beitraege gefunden")
            }
            PullRefreshIndicator(
                refreshing = refreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullRefresh(pullRefreshState)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (todayLocked && prompt?.hasAnyPostToday == false) {
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
                    val headerColor = weekdayRainbowColor(row.day)
                    Card(colors = CardDefaults.cardColors(containerColor = headerColor)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    formatDayWithWeekday(row.day),
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            }
                            if (row.day == todayDay && capsuleTargets.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Time Capsules verfuegbar",
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Black
                                )
                                capsuleTargets.forEach { (day, photoId) ->
                                    TextButton(
                                        onClick = { onJumpToCapsule(day, photoId) },
                                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                                    ) {
                                        Text("Capsule vom ${formatDayWithWeekday(day)} oeffnen", color = Color.Black)
                                    }
                                }
                            }
                        }
                    }
                }
                is FeedRow.PhotoItem -> {
                    val item = row.item
                    val meta = promptMetaByDay[row.day]
                    val urls = listOfNotNull(item.photo.url, item.photo.secondUrl)
                    val isDailyMomentPost = isWithinDailyMomentWindow(
                        item.photo.createdAt,
                        meta?.triggeredAt,
                        meta?.uploadUntil
                    )
                    Card {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                item.user.username,
                                fontWeight = FontWeight.SemiBold,
                                color = parseUserColor(item.user.favoriteColor),
                                modifier = Modifier.clickable { onOpenUserProfile(item.user.id) }
                            )
                            if (item.user.statusVisible && (item.user.statusText.isNotBlank() || item.user.statusEmoji.isNotBlank())) {
                                Text(
                                    "${item.user.statusEmoji} ${item.user.statusText}".trim(),
                                    color = secondaryTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!isDailyMomentPost) {
                                Text(
                                    "🕒 ${formatMomentTime(item.photo.createdAt)}",
                                    color = secondaryTextColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                DailyMomentBadge()
                            }
                            if (item.capsuleLocked) {
                                Text(
                                    "🧊 Oeffnet wieder am ${formatCapsuleOpenAt(item.photo.capsuleVisibleAt)}",
                                    color = secondaryTextColor
                                )
                                if (item.photo.capsulePrivate) {
                                    Text("private Kapsel", color = secondaryTextColor)
                                }
                            } else {
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
                            }
                            val reactions = item.reactions.orEmpty()
                            val comments = item.comments.orEmpty().sortedWith(
                                compareBy<PhotoCommentItem>(
                                    { parseOffsetOrLocalDateTime(it.createdAt) ?: LocalDateTime.MIN },
                                    { it.id }
                                )
                            )
                            if (reactions.isNotEmpty()) {
                                Text(
                                    reactions.joinToString("  ") { "${it.emoji} ${it.count}" },
                                    color = primaryTextColor
                                )
                            }
                            if (comments.isNotEmpty()) {
                                comments.forEach { comment ->
                                    Text(
                                        "${comment.user.username}: ${comment.body}",
                                        color = secondaryTextColor
                                    )
                                }
                            }
                            if (!item.photo.caption.isNullOrBlank()) {
                                Text(
                                    item.photo.caption,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = secondaryTextColor
                                )
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
        PullRefreshIndicator(
            refreshing = refreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
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
    dayStats: Map<String, DayStatItem>,
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
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(days) { day ->
            val selectedDay = day == selected
            val meta = promptMetaByDay[day]
            val stats = dayStats[day]
            val participantCount = stats?.participantCount ?: 0
            val featured = stats?.featuredPhoto
            Card(modifier = Modifier.fillMaxWidth().clickable { onSelect(day) }) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        formatDayWithWeekday(day),
                        fontWeight = if (selectedDay) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        if (participantCount == 1L) "1 Nutzer hat gepostet" else "$participantCount Nutzer haben gepostet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    featured?.let {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!it.secondUrl.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    AsyncImage(
                                        model = it.url,
                                        contentDescription = "Kalender-Vorschau 1",
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(88.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                    AsyncImage(
                                        model = it.secondUrl,
                                        contentDescription = "Kalender-Vorschau 2",
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(88.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            } else {
                                AsyncImage(
                                    model = it.url,
                                    contentDescription = "Kalender-Vorschau",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Text(
                                "${it.reactionCount} Reaktionen · ${it.commentCount} Kommentare",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    momentReasonLine(meta?.triggerSource, meta?.requestedByUser)?.let { reason ->
                        Text(reason, color = Color(0xFF1F5FBF))
                    }
                    monthRecapByDay[day]?.let { recap ->
                        Text(
                            "Monatsrueckblick: ${recap.monthLabel}",
                            color = Color(0xFF0A7A42),
                            fontWeight = FontWeight.SemiBold
                        )
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
fun ChatTab(
    items: List<ChatItem>,
    meId: Long?,
    chatDeleteSupported: Boolean,
    input: String,
    sending: Boolean,
    onInput: (String) -> Unit,
    onOpenUserProfile: (Long) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onSend: () -> Unit
) {
    val listState = rememberLazyListState()
    var deleteCandidate by remember { mutableStateOf<ChatItem?>(null) }
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
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) {
            listState.scrollToItem(rows.lastIndex)
        }
    }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Chat", style = MaterialTheme.typography.titleLarge)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(rows.size) { idx ->
                when (val row = rows[idx]) {
                    is ChatRow.DayHeader -> {
                        val headerColor = weekdayRainbowColor(row.day)
                        Card(colors = CardDefaults.cardColors(containerColor = headerColor)) {
                            Text(
                                formatDayLabel(row.day),
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black
                            )
                        }
                    }
                    is ChatRow.MessageItem -> {
                        val item = row.item
                        val canDelete = chatDeleteSupported && meId != null && item.user.id == meId && item.source == "user"
                        val holdModifier = if (canDelete) {
                            Modifier.pointerInput(item.id) {
                                detectTapGestures(
                                    onPress = {
                                        kotlinx.coroutines.coroutineScope {
                                            var longPressTriggered = false
                                            val holdJob = launch {
                                                delay(3000)
                                                longPressTriggered = true
                                                deleteCandidate = item
                                            }
                                            try {
                                                tryAwaitRelease()
                                            } finally {
                                                holdJob.cancel()
                                            }
                                            if (longPressTriggered) {
                                                return@coroutineScope
                                            }
                                        }
                                    }
                                )
                            }
                        } else {
                            Modifier
                        }
                        Card(modifier = holdModifier) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    item.user.username,
                                    fontWeight = FontWeight.SemiBold,
                                    color = parseUserColor(item.user.favoriteColor),
                                    modifier = Modifier.clickable { onOpenUserProfile(item.user.id) }
                                )
                                Text(item.body)
                            }
                        }
                    }
                }
            }
        }
        deleteCandidate?.let { candidate ->
            AlertDialog(
                onDismissRequest = { deleteCandidate = null },
                title = { Text("Nachricht loeschen?") },
                text = { Text("Willst du diese Nachricht wirklich loeschen?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteCandidate = null
                            onDeleteMessage(candidate.id)
                        }
                    ) {
                        Text("Loeschen")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteCandidate = null }) {
                        Text("Abbrechen")
                    }
                }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                label = { Text("Nachricht") },
                enabled = !sending,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onSend,
                enabled = !sending && input.trim().isNotEmpty(),
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text(if (sending) "Sende..." else "Senden")
            }
        }
    }

}

@Composable
private fun CollapsibleSection(
    title: String,
    subtitle: String? = null,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    headerBrush: Brush? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (headerBrush != null) {
                            Modifier.background(headerBrush, shape = MaterialTheme.shapes.medium)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onExpandedChange(!expanded) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (headerBrush != null) Color.White else MaterialTheme.colorScheme.onSurface
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                color = if (headerBrush != null) Color.White.copy(alpha = 0.92f) else Color.Gray,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(
                        if (expanded) "v" else ">",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (headerBrush != null) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileTab(
    username: String,
    inviteCode: String,
    streakDays: Int,
    dailyMomentCount: Int,
    promptRules: PromptRulesResponse?,
    photos: List<PromptPhoto>,
    themeMode: Int,
    currentPassword: String,
    newPassword: String,
    editableUsername: String,
    editableColor: String,
    appVersion: String,
    updateAvailable: Boolean,
    serverVersion: String,
    pushProvider: String,
    apiBaseUrl: String,
    serverConnected: Boolean,
    lastPingMs: Long?,
    uploadQuality: Int,
    autoUpdateEnabled: Boolean,
    notificationMasterEnabled: Boolean,
    chatPushEnabled: Boolean,
    inviteRegistrationPushEnabled: Boolean,
    photoReactionPushEnabled: Boolean,
    photoCommentPushEnabled: Boolean,
    allowPhotoDownload: Boolean,
    feedPostPushEnabled: Boolean,
    customNotificationToneEnabled: Boolean,
    customNotificationToneUri: String,
    diagnosticsUploadEnabled: Boolean,
    debugLogs: List<DebugLogEntry>,
    profileSetupJumpTarget: String,
    profileSectionsExpanded: Map<String, Boolean>,
    avatarUrl: String,
    bio: String,
    statusText: String,
    statusEmoji: String,
    statusExpiresAt: String?,
    profileVisible: Boolean,
    avatarVisible: Boolean,
    bioVisible: Boolean,
    statusVisible: Boolean,
    quietHoursEnabled: Boolean,
    quietHoursStart: String,
    quietHoursEnd: String,
    communityStats: CommunityStatsResponse?,
    communityStatsLoading: Boolean,
    onThemeModeChange: (Int) -> Unit,
    onUploadQualityChange: (Int) -> Unit,
    onAutoUpdateEnabledChange: (Boolean) -> Unit,
    onChatPushEnabledChange: (Boolean) -> Unit,
    onInviteRegistrationPushEnabledChange: (Boolean) -> Unit,
    onPhotoReactionPushEnabledChange: (Boolean) -> Unit,
    onPhotoCommentPushEnabledChange: (Boolean) -> Unit,
    onAllowPhotoDownloadChange: (Boolean) -> Unit,
    onNotificationMasterEnabledChange: (Boolean) -> Unit,
    onFeedPostPushEnabledChange: (Boolean) -> Unit,
    onCustomNotificationToneEnabledChange: (Boolean) -> Unit,
    onPickCustomNotificationTone: () -> Unit,
    onClearCustomNotificationTone: () -> Unit,
    onTestCustomNotificationTone: () -> Unit,
    onDiagnosticsUploadEnabledChange: (Boolean) -> Unit,
    onRefreshDebugLogs: () -> Unit,
    onShareDebugLogs: () -> Unit,
    onProfileSectionExpandedChange: (String, Boolean) -> Unit,
    onUploadAvatar: (Uri) -> Unit,
    onEditableUsernameChange: (String) -> Unit,
    onEditableColorChange: (String) -> Unit,
    onAutoSaveProfile: (
        username: String,
        favoriteColor: String,
        bio: String,
        statusText: String,
        statusEmoji: String,
        statusExpiresAt: String?,
        profileVisible: Boolean,
        avatarVisible: Boolean,
        bioVisible: Boolean,
        statusVisible: Boolean,
        quietHoursEnabled: Boolean,
        quietHoursStart: String,
        quietHoursEnd: String
    ) -> Unit,
    onConsumeSetupJumpTarget: () -> Unit,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onChangePassword: () -> Unit,
    onCheckUpdate: () -> Unit,
    onShowChangelog: () -> Unit,
    onShowHelp: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    onCheckConnection: () -> Unit,
    onRollInviteCode: () -> Unit,
    onShareInviteCode: () -> Unit,
    onLogout: () -> Unit,
    onDeletePhoto: (Long) -> Unit,
    onOpenViewer: (List<String>, Long?) -> Unit
) {
    val context = LocalContext.current
    val appPrefs = remember(context) { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    val expiryPresetKey = remember(username) { "status_expiry_preset_${username.lowercase(Locale.ROOT)}" }
    val advancedSettingsKey = remember(username) { "profile_advanced_visible_${username.lowercase(Locale.ROOT)}" }
    var showColorPicker by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var pickerHsv by remember(editableColor) { mutableStateOf(hexToHsv(normalizeHexColor(editableColor))) }
    var themeSliderValue by remember(themeMode) { mutableStateOf(themeMode.toFloat()) }
    var advancedSettingsVisible by remember(username) { mutableStateOf(appPrefs.getBoolean(advancedSettingsKey, false)) }
    var deleteCandidate by remember { mutableStateOf<PromptPhoto?>(null) }
    var showAllowDownloadWarning by remember { mutableStateOf(false) }
    var updatePulseTick by remember { mutableStateOf(0) }
    var updateChecked by remember { mutableStateOf(false) }
    val updateButtonScale = remember { Animatable(1f) }
    fun sectionExpanded(sectionId: String): Boolean = profileSectionsExpanded[sectionId] ?: false
    var bioValue by remember(bio) { mutableStateOf(bio) }
    var statusTextValue by remember(statusText) { mutableStateOf(statusText) }
    var statusEmojiValue by remember(statusEmoji) { mutableStateOf(statusEmoji) }
    var statusExpiresAtValue by remember(statusExpiresAt) { mutableStateOf(statusExpiresAt ?: "") }
    var statusExpiryPreset by remember(statusExpiresAt, username) {
        mutableStateOf(appPrefs.getString(expiryPresetKey, "none") ?: "none")
    }
    var profileVisibleValue by remember(profileVisible) { mutableStateOf(profileVisible) }
    var avatarVisibleValue by remember(avatarVisible) { mutableStateOf(avatarVisible) }
    var bioVisibleValue by remember(bioVisible) { mutableStateOf(bioVisible) }
    var statusVisibleValue by remember(statusVisible) { mutableStateOf(statusVisible) }
    var quietHoursEnabledValue by remember(quietHoursEnabled) { mutableStateOf(quietHoursEnabled) }
    var quietHoursStartValue by remember(quietHoursStart) { mutableStateOf(quietHoursStart) }
    var quietHoursEndValue by remember(quietHoursEnd) { mutableStateOf(quietHoursEnd) }
    var usernameDirty by remember { mutableStateOf(false) }
    var bioDirty by remember { mutableStateOf(false) }
    var statusTextDirty by remember { mutableStateOf(false) }
    var statusEmojiDirty by remember { mutableStateOf(false) }
    var quietStartDirty by remember { mutableStateOf(false) }
    var quietEndDirty by remember { mutableStateOf(false) }
    var autosaveJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val accountBringRequester = remember { BringIntoViewRequester() }
    val privacyBringRequester = remember { BringIntoViewRequester() }
    val avatarPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onUploadAvatar(uri)
    }

    fun triggerProfileAutosave(debounced: Boolean) {
        val saveAction = {
            val expiryRaw = statusExpiresAtValue.trim()
            if (expiryRaw.isBlank()) {
                statusExpiryPreset = "none"
            } else if (statusExpiryPreset !in listOf("24h", "72h", "7d")) {
                statusExpiryPreset = "custom"
            }
            appPrefs.edit().putString(expiryPresetKey, statusExpiryPreset).apply()
            onAutoSaveProfile(
                editableUsername.trim(),
                normalizeHexColor(editableColor),
                bioValue.trim(),
                statusTextValue.trim(),
                statusEmojiValue.trim(),
                expiryRaw.ifBlank { null },
                profileVisibleValue,
                avatarVisibleValue,
                bioVisibleValue,
                statusVisibleValue,
                quietHoursEnabledValue,
                quietHoursStartValue.trim().ifBlank { "22:00" },
                quietHoursEndValue.trim().ifBlank { "07:00" }
            )
        }

        autosaveJob?.cancel()
        if (debounced) {
            autosaveJob = scope.launch {
                delay(500)
                saveAction()
            }
        } else {
            autosaveJob = scope.launch { saveAction() }
        }
    }

    LaunchedEffect(updatePulseTick) {
        if (updatePulseTick <= 0) return@LaunchedEffect
        updateButtonScale.snapTo(1f)
        updateButtonScale.animateTo(1.08f, animationSpec = tween(130))
        updateButtonScale.animateTo(0.96f, animationSpec = tween(110))
        updateButtonScale.animateTo(1f, animationSpec = tween(170))
        delay(1400)
        updateChecked = false
    }

    LaunchedEffect(profileSetupJumpTarget) {
        val target = profileSetupJumpTarget.trim()
        if (target.isBlank()) return@LaunchedEffect
        if (target == "profile_account") {
            onProfileSectionExpandedChange("profile_account", true)
            onProfileSectionExpandedChange("profile_privacy", true)
            delay(120)
            accountBringRequester.bringIntoView()
        } else if (target == "profile_privacy") {
            onProfileSectionExpandedChange("profile_account", true)
            onProfileSectionExpandedChange("profile_privacy", true)
            delay(120)
            privacyBringRequester.bringIntoView()
        }
        onConsumeSetupJumpTarget()
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("@$username", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("🔥 $streakDays", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("🌈 $dailyMomentCount", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Dein Hub fuer Konto, Hilfe und Updates",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                updatePulseTick += 1
                                updateChecked = true
                                onCheckUpdate()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .graphicsLayer {
                                    scaleX = updateButtonScale.value
                                    scaleY = updateButtonScale.value
                                }
                        ) { Text(if (updateChecked) "Update geprueft" else "Update pruefen") }
                        Button(onClick = onShowChangelog, modifier = Modifier.widthIn(min = 56.dp)) { Text("!") }
                        Button(onClick = onShowHelp, modifier = Modifier.widthIn(min = 96.dp)) { Text("Hilfe") }
                    }
                    Button(onClick = { showLogoutConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Abmelden") }
                }
            }
        }
        item {
            val accountTransition = rememberInfiniteTransition(label = "profile-account-rainbow")
            val accountHue by accountTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 18000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "profile-account-hue"
            )
            val accountBrush = Brush.horizontalGradient(
                listOf(
                    rainbowColor(accountHue + 0f),
                    rainbowColor(accountHue + 75f),
                    rainbowColor(accountHue + 150f),
                    rainbowColor(accountHue + 225f)
                )
            )
            CollapsibleSection(
                title = "Profil & Konto",
                subtitle = "Konto, persoenliche Angaben und Privatsphaere",
                expanded = sectionExpanded("profile_account"),
                onExpandedChange = { onProfileSectionExpandedChange("profile_account", it) },
                headerBrush = accountBrush
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(accountBringRequester),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SettingsSubsection("Konto", "Profilbild, Benutzername und Passwort") {
                        if (avatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = avatarUrl,
                                contentDescription = "Profilbild",
                                modifier = Modifier
                                    .size(96.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            )
                        }
                        Button(
                            onClick = { avatarPickerLauncher.launch("image/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Profilbild hochladen") }
                        OutlinedTextField(
                            value = editableUsername,
                            onValueChange = {
                                usernameDirty = true
                                onEditableUsernameChange(it)
                            },
                            label = { Text("Benutzername") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged {
                                    if (!it.isFocused && usernameDirty) {
                                        usernameDirty = false
                                        triggerProfileAutosave(debounced = true)
                                    }
                                }
                        )
                        OutlinedTextField(
                            value = currentPassword,
                            onValueChange = onCurrentPasswordChange,
                            label = { Text("Aktuelles Passwort") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        val newPasswordInvalid = newPassword.isNotBlank() && !isPasswordValid(newPassword)
                        OutlinedTextField(
                            value = newPassword,
                            onValueChange = onNewPasswordChange,
                            label = { Text("Neues Passwort") },
                            modifier = Modifier.fillMaxWidth(),
                            isError = newPasswordInvalid,
                            supportingText = {
                                Text(
                                    "Neues Passwort: $passwordRequirementText",
                                    color = if (newPasswordInvalid) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                        Button(
                            onClick = onChangePassword,
                            enabled = currentPassword.isNotBlank() && isPasswordValid(newPassword),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Passwort speichern") }
                    }

                    SettingsSubsection("Persoenlich", "Bio, Status und Farbe fuer dein Profil") {
                        OutlinedTextField(
                            value = bioValue,
                            onValueChange = {
                                bioDirty = true
                                bioValue = it.take(280)
                            },
                            label = { Text("Kurzbeschreibung") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged {
                                    if (!it.isFocused && bioDirty) {
                                        bioDirty = false
                                        triggerProfileAutosave(debounced = true)
                                    }
                                }
                        )
                        OutlinedTextField(
                            value = statusTextValue,
                            onValueChange = {
                                statusTextDirty = true
                                statusTextValue = it.take(120)
                            },
                            label = { Text("Status-Text") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged {
                                    if (!it.isFocused && statusTextDirty) {
                                        statusTextDirty = false
                                        triggerProfileAutosave(debounced = true)
                                    }
                                }
                        )
                        OutlinedTextField(
                            value = statusEmojiValue,
                            onValueChange = {
                                statusEmojiDirty = true
                                statusEmojiValue = it.take(8)
                            },
                            label = { Text("Status-Emoji") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged {
                                    if (!it.isFocused && statusEmojiDirty) {
                                        statusEmojiDirty = false
                                        triggerProfileAutosave(debounced = true)
                                    }
                                }
                        )
                        val expiryTransition = rememberInfiniteTransition(label = "status-expiry-rainbow")
                        val expiryHue by expiryTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 16000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "status-expiry-hue"
                        )
                        val expiryBrush = Brush.horizontalGradient(
                            listOf(
                                rainbowColor(expiryHue + 0f),
                                rainbowColor(expiryHue + 90f),
                                rainbowColor(expiryHue + 180f),
                                rainbowColor(expiryHue + 270f)
                            )
                        )
                        val expiryLabel = when (statusExpiryPreset) {
                            "24h" -> "24 Stunden"
                            "72h" -> "72 Stunden"
                            "7d" -> "7 Tage"
                            "none" -> "Kein Verfallsdatum"
                            else -> if (statusExpiresAtValue.isNotBlank()) "Bis ${formatCapsuleOpenAt(statusExpiresAtValue)}" else "Kein Verfallsdatum"
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(expiryBrush, shape = MaterialTheme.shapes.small),
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                        ) {
                            Text(
                                text = "Verfallsdatum: $expiryLabel",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = {
                                statusExpiresAtValue = OffsetDateTime.now().plusHours(24).toString()
                                statusExpiryPreset = "24h"
                                appPrefs.edit().putString(expiryPresetKey, statusExpiryPreset).apply()
                                triggerProfileAutosave(debounced = false)
                            }, modifier = Modifier.weight(1f)) { Text("24h") }
                            Button(onClick = {
                                statusExpiresAtValue = OffsetDateTime.now().plusHours(72).toString()
                                statusExpiryPreset = "72h"
                                appPrefs.edit().putString(expiryPresetKey, statusExpiryPreset).apply()
                                triggerProfileAutosave(debounced = false)
                            }, modifier = Modifier.weight(1f)) { Text("72h") }
                            Button(onClick = {
                                statusExpiresAtValue = OffsetDateTime.now().plusDays(7).toString()
                                statusExpiryPreset = "7d"
                                appPrefs.edit().putString(expiryPresetKey, statusExpiryPreset).apply()
                                triggerProfileAutosave(debounced = false)
                            }, modifier = Modifier.weight(1f)) { Text("7d") }
                            Button(onClick = {
                                statusExpiresAtValue = ""
                                statusExpiryPreset = "none"
                                appPrefs.edit().putString(expiryPresetKey, statusExpiryPreset).apply()
                                triggerProfileAutosave(debounced = false)
                            }, modifier = Modifier.weight(1f)) { Text("Nie") }
                        }
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
                        Text(
                            text = "Vorschau Name",
                            color = parseUserColor(editableColor),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    SettingsSubsection(
                        title = "Privatsphaere",
                        subtitle = "Sichtbarkeit, Download-Freigabe und Ruhezeiten"
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .bringIntoViewRequester(privacyBringRequester),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SettingsToggleRow(
                                label = "Download der eigenen Bilder fuer andere Benutzer zulassen",
                                checked = allowPhotoDownload,
                                onCheckedChange = { checked ->
                                    if (checked && !allowPhotoDownload) {
                                        showAllowDownloadWarning = true
                                    } else if (!checked && allowPhotoDownload) {
                                        onAllowPhotoDownloadChange(false)
                                    }
                                },
                                supportingText = "Nur wenn du das aktiv einschaltest, erscheint im Bildbetrachter ein Download-Button."
                            )
                            SettingsToggleRow(
                                label = "Profil aufrufbar",
                                checked = profileVisibleValue,
                                onCheckedChange = {
                                    profileVisibleValue = it
                                    triggerProfileAutosave(debounced = false)
                                }
                            )
                            SettingsToggleRow(
                                label = "Profilbild sichtbar",
                                checked = avatarVisibleValue,
                                onCheckedChange = {
                                    avatarVisibleValue = it
                                    triggerProfileAutosave(debounced = false)
                                }
                            )
                            SettingsToggleRow(
                                label = "Kurzbeschreibung sichtbar",
                                checked = bioVisibleValue,
                                onCheckedChange = {
                                    bioVisibleValue = it
                                    triggerProfileAutosave(debounced = false)
                                }
                            )
                            SettingsToggleRow(
                                label = "Status sichtbar",
                                checked = statusVisibleValue,
                                onCheckedChange = {
                                    statusVisibleValue = it
                                    triggerProfileAutosave(debounced = false)
                                }
                            )
                            SettingsToggleRow(
                                label = "Ruhezeit aktiv",
                                checked = quietHoursEnabledValue,
                                onCheckedChange = {
                                    quietHoursEnabledValue = it
                                    triggerProfileAutosave(debounced = false)
                                },
                                supportingText = "Ausnahmen: Daily-Moment und Sondermoment bleiben aktiv."
                            )
                            if (quietHoursEnabledValue) {
                                OutlinedTextField(
                                    value = quietHoursStartValue,
                                    onValueChange = {
                                        quietStartDirty = true
                                        quietHoursStartValue = it.take(5)
                                    },
                                    label = { Text("Ruhezeit Start (HH:mm)") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged {
                                            if (!it.isFocused && quietStartDirty) {
                                                quietStartDirty = false
                                                triggerProfileAutosave(debounced = true)
                                            }
                                        }
                                )
                                OutlinedTextField(
                                    value = quietHoursEndValue,
                                    onValueChange = {
                                        quietEndDirty = true
                                        quietHoursEndValue = it.take(5)
                                    },
                                    label = { Text("Ruhezeit Ende (HH:mm)") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged {
                                            if (!it.isFocused && quietEndDirty) {
                                                quietEndDirty = false
                                                triggerProfileAutosave(debounced = true)
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            val transition = rememberInfiniteTransition(label = "notif-master-rainbow")
            val hueShift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 18000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "notif-master-hue"
            )
            val rainbowBrush = Brush.horizontalGradient(
                listOf(
                    rainbowColor(hueShift + 0f),
                    rainbowColor(hueShift + 70f),
                    rainbowColor(hueShift + 140f),
                    rainbowColor(hueShift + 210f)
                )
            )
            CollapsibleSection(
                title = "Benachrichtigungen",
                subtitle = "Master + Update, Chat, Feed, Interaktionen und neue Mitglieder",
                expanded = sectionExpanded("notifications"),
                onExpandedChange = { onProfileSectionExpandedChange("notifications", it) }
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rainbowBrush, shape = MaterialTheme.shapes.medium),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Alle Benachrichtigungen",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Schaltet alle optionalen Push-Nachrichten gemeinsam ein oder aus.",
                                color = Color.White.copy(alpha = 0.92f)
                            )
                        }
                        Switch(
                            checked = notificationMasterEnabled,
                            onCheckedChange = onNotificationMasterEnabledChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0x66000000),
                                uncheckedThumbColor = Color(0xFFE0E0E0),
                                uncheckedTrackColor = Color(0x55808080)
                            )
                        )
                    }
                }
                SettingsSubsection("Allgemein", "Alles, was den Alltag in der Gruppe betrifft") {
                    SettingsToggleRow(
                        label = "Auto-Update-Suche (10 Min)",
                        checked = autoUpdateEnabled,
                        onCheckedChange = onAutoUpdateEnabledChange
                    )
                    SettingsToggleRow(
                        label = "Chat Push bei neuen Nachrichten",
                        checked = chatPushEnabled,
                        onCheckedChange = onChatPushEnabledChange
                    )
                    SettingsToggleRow(
                        label = "Push bei Posts anderer Nutzer",
                        checked = feedPostPushEnabled,
                        onCheckedChange = onFeedPostPushEnabledChange
                    )
                    SettingsToggleRow(
                        label = "Push bei neuen Mitgliedern",
                        checked = inviteRegistrationPushEnabled,
                        onCheckedChange = onInviteRegistrationPushEnabledChange
                    )
                }
                SettingsSubsection("Meine Beitraege", "Benachrichtigungen zu Interaktionen auf deine Posts") {
                    SettingsToggleRow(
                        label = "Push bei Reaktionen auf meine Beitraege",
                        checked = photoReactionPushEnabled,
                        onCheckedChange = onPhotoReactionPushEnabledChange
                    )
                    SettingsToggleRow(
                        label = "Push bei Kommentaren auf meine Beitraege",
                        checked = photoCommentPushEnabled,
                        onCheckedChange = onPhotoCommentPushEnabledChange
                    )
                }
                SettingsSubsection("Ton", "Klingelton und Test fuer deine Push-Benachrichtigungen") {
                    SettingsToggleRow(
                        label = "Custom-Benachrichtigungston",
                        checked = customNotificationToneEnabled,
                        onCheckedChange = onCustomNotificationToneEnabledChange
                    )
                    if (customNotificationToneEnabled) {
                        val toneLabel = remember(customNotificationToneUri) {
                            resolveNotificationToneTitle(context, customNotificationToneUri)
                        }
                        Text(
                            "Ausgewaehlter Ton: ${if (toneLabel.isBlank()) "System-Standard" else toneLabel}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = onPickCustomNotificationTone, modifier = Modifier.weight(1f)) {
                                Text("Ton auswaehlen")
                            }
                            Button(onClick = onClearCustomNotificationTone, modifier = Modifier.weight(1f)) {
                                Text("Zuruecksetzen")
                            }
                        }
                        Button(
                            onClick = onTestCustomNotificationTone,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Test-Benachrichtigungston + Push")
                        }
                    }
                }
            }
        }
        item {
            CollapsibleSection(
                title = "Vergangene Beitraege",
                subtitle = "Deine Galerie",
                expanded = sectionExpanded("past_posts"),
                onExpandedChange = { onProfileSectionExpandedChange("past_posts", it) }
            ) {
                if (photos.isEmpty()) {
                    Text("Noch keine Beitraege")
                } else {
                    val rainbowBorder = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFF5F6D),
                            Color(0xFFFFC371),
                            Color(0xFF6EEB83),
                            Color(0xFF5AA9E6),
                            Color(0xFFB517FF)
                        )
                    )
                    val photoRows = remember(photos) { photos.chunked(3) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        photoRows.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (i in 0 until 3) {
                                    val photo = row.getOrNull(i)
                                    if (photo == null) {
                                        Spacer(modifier = Modifier.weight(1f))
                                        continue
                                    }
                                    val urls = listOfNotNull(photo.url, photo.secondUrl)
                                    val imageModifier = Modifier
                                        .fillMaxWidth()
                                        .height(96.dp)
                                        .background(Color.LightGray)
                                        .then(
                                            if (photo.dailyMoment) {
                                                Modifier.border(1.dp, rainbowBorder, MaterialTheme.shapes.small)
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .pointerInput(photo.id) {
                                            detectTapGestures(
                                                onPress = {
                                                    kotlinx.coroutines.coroutineScope {
                                                        var longPressTriggered = false
                                                        val holdJob = launch {
                                                            delay(3000)
                                                            longPressTriggered = true
                                                            deleteCandidate = photo
                                                        }
                                                        val released = tryAwaitRelease()
                                                        holdJob.cancel()
                                                        if (released && !longPressTriggered) {
                                                            onOpenViewer(urls, photo.id)
                                                        }
                                                    }
                                                }
                                            )
                                        }

                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        AsyncImage(
                                            model = photo.url,
                                            contentDescription = formatDayLabel(photo.day),
                                            modifier = imageModifier,
                                            contentScale = ContentScale.Crop
                                        )
                                        if (photo.secondUrl != null) {
                                            Text("2 Bilder", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Text("Zeit ${formatMomentTime(photo.createdAt)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(formatDayLabel(photo.day), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            CollapsibleSection(
                title = "Invite-Code",
                subtitle = "Code teilen oder erneuern",
                expanded = sectionExpanded("invite"),
                onExpandedChange = { onProfileSectionExpandedChange("invite", it) }
            ) {
                Text(inviteCode.ifBlank { "wird geladen ..." }, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onRollInviteCode, modifier = Modifier.weight(1f)) { Text("Erneuern") }
                    Button(onClick = onShareInviteCode, modifier = Modifier.weight(1f)) { Text("Teilen") }
                }
                Text("Jeder Code ist einmal gueltig. Nach Nutzung wird automatisch ein neuer Code erzeugt.")
            }
        }
        item {
            CollapsibleSection(
                title = "Anzeige",
                subtitle = "Design und Theme",
                expanded = sectionExpanded("display"),
                onExpandedChange = { onProfileSectionExpandedChange("display", it) }
            ) {
                Text("Darstellung: ${themeModeLabel(themeSliderValue.toInt())}")
                Slider(
                    value = themeSliderValue,
                    onValueChange = {
                        themeSliderValue = it.coerceIn(0f, 2f)
                    },
                    valueRange = 0f..2f,
                    steps = 1,
                    onValueChangeFinished = {
                        val selected = themeSliderValue.toInt().coerceIn(0, 2)
                        themeSliderValue = selected.toFloat()
                        onThemeModeChange(selected)
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                      Text("Light", color = if (themeSliderValue < 0.5f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                      Text("Dark", color = if (themeSliderValue in 0.5f..1.5f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                      Text("OLED", color = if (themeSliderValue > 1.5f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                  SettingsToggleRow(
                      label = "Erweiterte Einstellungen anzeigen",
                      checked = advancedSettingsVisible,
                      onCheckedChange = {
                          advancedSettingsVisible = it
                          appPrefs.edit().putBoolean(advancedSettingsKey, it).apply()
                      },
                      supportingText = "Zeigt technische und selten benoetigte Bereiche wie Upload, Verbindung, Community-Stats und Diagnose."
                  )
              }
          }
          if (advancedSettingsVisible) {
              item {
                  CollapsibleSection(
                      title = "Upload-Komprimierung",
                      subtitle = "Qualitaet vs. Geschwindigkeit",
                      expanded = sectionExpanded("upload_quality"),
                      onExpandedChange = { onProfileSectionExpandedChange("upload_quality", it) }
                  ) {
                      Card {
                          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                              Text("JPEG-Qualitaet: $uploadQuality%")
                              Slider(
                                  value = uploadQuality.toFloat(),
                                  onValueChange = { onUploadQualityChange(it.toInt()) },
                                  valueRange = 20f..100f
                              )
                              Text("Weniger Qualitaet = kleiner und schnellerer Upload")
                          }
                      }
                  }
              }
              item {
                  CollapsibleSection(
                      title = "Moment-Bedingungen",
                      subtitle = "Aktuelle Regeln vom Server",
                      expanded = sectionExpanded("moment_rules"),
                      onExpandedChange = { onProfileSectionExpandedChange("moment_rules", it) }
                  ) {
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
              }
              item {
                  CollapsibleSection(
                      title = "App & Verbindung",
                      subtitle = "Versionen und Serverstatus",
                      expanded = sectionExpanded("app_connection"),
                      onExpandedChange = { onProfileSectionExpandedChange("app_connection", it) }
                  ) {
                      Card {
                          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                              Text("Status: ${if (serverConnected) "Verbunden" else "Nicht verbunden"}")
                              Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                  Text("App-Version: $appVersion")
                                  Text(
                                      text = if (updateAvailable) "(nicht aktuell)" else "(aktuell)",
                                      color = if (updateAvailable) Color(0xFFD32F2F) else Color(0xFF2E7D32),
                                      fontWeight = FontWeight.SemiBold
                                  )
                              }
                              Text("Server-Version: $serverVersion")
                              Text("Push-Provider: $pushProvider")
                              Text("Letzter Ping: ${lastPingMs?.let { "${it} ms" } ?: "-"}")
                              Text("API: $apiBaseUrl")
                              Spacer(modifier = Modifier.height(6.dp))
                              Button(onClick = onCheckConnection, modifier = Modifier.fillMaxWidth()) { Text("Verbindung pruefen") }
                          }
                      }
                  }
              }
              item {
                  CollapsibleSection(
                      title = "Community-Stats",
                      subtitle = "Heute + letzte 7 Tage",
                      expanded = sectionExpanded("community_stats"),
                      onExpandedChange = { onProfileSectionExpandedChange("community_stats", it) }
                  ) {
                      Card {
                          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                              if (communityStatsLoading && communityStats == null) {
                                  Text("Community-Stats werden geladen ...")
                              } else if (communityStats == null) {
                                  Text("Noch keine Daten vorhanden")
                              } else {
                                  Text("Registrierte Nutzer: ${communityStats.registeredUsers}")
                                  Text("Heute aktiv: ${communityStats.activeUsersToday}")
                                  val latest = communityStats.latestActiveUser
                                  Text(
                                      if (latest == null) {
                                          "Zuletzt aktiv: -"
                                      } else {
                                          "Zuletzt aktiv: @${latest.username} - ${formatMomentTime(latest.createdAt)}"
                                      }
                                  )
                                  Text("Posts heute: ${communityStats.postsToday}")
                                  Text("Chat-Nachrichten heute: ${communityStats.chatMessagesToday}")
                                  Spacer(modifier = Modifier.height(4.dp))
                                  Text("Top 5 Reaktionen (7 Tage)", fontWeight = FontWeight.SemiBold)
                                  if (communityStats.topReactions7d.isEmpty()) {
                                      Text("Noch keine Reaktionen in den letzten 7 Tagen")
                                  } else {
                                      communityStats.topReactions7d.take(5).forEachIndexed { index, item ->
                                          Text("${index + 1}. ${item.emoji}  ${item.count}")
                                      }
                                  }
                                  Spacer(modifier = Modifier.height(4.dp))
                                  val dm = communityStats.dailyMomentParticipation7d
                                  Text("Daily-Moment-Quote (7 Tage): ${dm.participants}/${dm.totalUsers} Nutzer (${dm.percent}%)")
                              }
                          }
                      }
                  }
              }
              item {
                  CollapsibleSection(
                      title = "Debug & Diagnose",
                      subtitle = "Fehlerlogs lokal speichern, exportieren und optional hochladen",
                      expanded = sectionExpanded("debug_diagnose"),
                      onExpandedChange = { onProfileSectionExpandedChange("debug_diagnose", it) }
                  ) {
                      SettingsSubsection(
                          title = "Diagnose",
                          subtitle = "Nur wenn du Probleme nachvollziehen oder uns Logs schicken willst."
                      ) {
                          SettingsToggleRow(
                              label = "Diagnose-Upload aktivieren",
                              checked = diagnosticsUploadEnabled,
                              onCheckedChange = onDiagnosticsUploadEnabledChange,
                              supportingText = "Wenn aktiviert, werden Diagnose-Logs bei App-Start und bei neuen Fehlern automatisch an den Server geschickt."
                          )
                          Row(
                              modifier = Modifier.fillMaxWidth(),
                              horizontalArrangement = Arrangement.spacedBy(8.dp)
                          ) {
                              Button(onClick = onRefreshDebugLogs, modifier = Modifier.weight(1f)) { Text("Letzte Fehler") }
                              Button(onClick = onShareDebugLogs, modifier = Modifier.weight(1f)) { Text("Diagnose exportieren") }
                          }
                          Card(modifier = Modifier.fillMaxWidth()) {
                              Column(
                                  modifier = Modifier.padding(10.dp),
                                  verticalArrangement = Arrangement.spacedBy(6.dp)
                              ) {
                                  if (debugLogs.isEmpty()) {
                                      Text("Keine lokalen Fehlereintraege vorhanden")
                                  } else {
                                      debugLogs.take(8).forEach { row ->
                                          Text("[${row.createdAt.take(16)}] ${row.type}", fontWeight = FontWeight.SemiBold)
                                          Text(row.message, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                          if (row.meta.isNotBlank()) {
                                              Text(
                                                  row.meta,
                                                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                  maxLines = 1,
                                                  overflow = TextOverflow.Ellipsis
                                              )
                                          }
                                      }
                                  }
                              }
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
                        triggerProfileAutosave(debounced = false)
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

    deleteCandidate?.let { photo ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePhoto(photo.id)
                        deleteCandidate = null
                    }
                ) { Text("Loeschen") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Abbrechen") }
            },
            title = { Text("Beitrag loeschen?") },
            text = {
                Text(
                    "Willst du diesen Beitrag wirklich loeschen?\n\nTag: ${formatDayLabel(photo.day)}\nHalte ein Bild 3 Sekunden gedrueckt, um diesen Dialog zu oeffnen."
                )
            }
        )
    }

      if (showAllowDownloadWarning) {
          AlertDialog(
            onDismissRequest = { showAllowDownloadWarning = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAllowDownloadWarning = false
                        onAllowPhotoDownloadChange(true)
                    }
                ) { Text("Aktivieren") }
            },
            dismissButton = {
                TextButton(onClick = { showAllowDownloadWarning = false }) { Text("Abbrechen") }
            },
            title = { Text("Download-Freigabe aktivieren?") },
            text = {
                Text("Wenn du das aktivierst, koennen andere Benutzer deine Bilder herunterladen.")
            }
          )
      }
      if (showLogoutConfirm) {
          AlertDialog(
              onDismissRequest = { showLogoutConfirm = false },
              confirmButton = {
                  TextButton(
                      onClick = {
                          showLogoutConfirm = false
                          onLogout()
                      }
                  ) { Text("Abmelden") }
              },
              dismissButton = {
                  TextButton(onClick = { showLogoutConfirm = false }) { Text("Abbrechen") }
              },
              title = { Text("Wirklich abmelden?") },
              text = { Text("Willst du dich wirklich abmelden?") }
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

private const val passwordRequirementText = "Mindestens 6 Zeichen"

private fun isPasswordValid(password: String): Boolean = password.trim().length >= 6

private fun formatDayWithWeekday(day: String): String {
    return try {
        val d = LocalDate.parse(day)
        d.format(DateTimeFormatter.ofPattern("EEEE, dd.MM.yyyy", Locale.GERMAN))
    } catch (_: Throwable) {
        day
    }
}

private sealed class ChatRow {
    data class DayHeader(val day: String) : ChatRow()
    data class MessageItem(val item: ChatItem) : ChatRow()
}

private fun createdAtDay(value: String): String {
    val raw = value.trim()
    if (raw.isBlank()) return value
    runCatching {
        return OffsetDateTime.parse(raw)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }
    runCatching {
        return LocalDateTime.parse(raw)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }
    runCatching {
        val normalized = raw.replace(" ", "T")
        return LocalDateTime.parse(normalized)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }
    if (value.length >= 10) {
        val prefix = value.substring(0, 10)
        if (prefix[4] == '-' && prefix[7] == '-') return prefix
    }
    return value
}

private fun weekdayRainbowColor(day: String): Color {
    val weekday = runCatching { LocalDate.parse(day).dayOfWeek.value }.getOrElse { 1 }
    return when (weekday) {
        1 -> Color(0xFFFF6B6B) // Montag - Rot
        2 -> Color(0xFFFFA94D) // Dienstag - Orange
        3 -> Color(0xFFFFE066) // Mittwoch - Gelb
        4 -> Color(0xFF8CE99A) // Donnerstag - Gruen
        5 -> Color(0xFF66D9E8) // Freitag - Cyan
        6 -> Color(0xFF74C0FC) // Samstag - Blau
        else -> Color(0xFFB197FC) // Sonntag - Violett
    }
}

private fun formatRemaining(seconds: Long): String {
    val sec = seconds.coerceAtLeast(0L)
    val days = sec / 86400
    val hours = (sec % 86400) / 3600
    return "${days}d ${hours}h"
}

private fun formatMomentTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "-"
    val parsed = runCatching {
        OffsetDateTime.parse(raw)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrElse {
        runCatching {
            LocalDateTime.parse(raw)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .format(DateTimeFormatter.ofPattern("HH:mm"))
        }.getOrElse {
            runCatching {
                LocalDateTime.parse(raw.replace(" ", "T"))
                    .atZone(ZoneId.systemDefault())
                    .toLocalTime()
                    .format(DateTimeFormatter.ofPattern("HH:mm"))
            }.getOrElse {
                raw.take(16).replace('T', ' ')
            }
        }
    }
    return parsed
}

@Composable
private fun DailyMomentStartOverlay(
    onCaptureNow: () -> Unit,
    onLater: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "daily-overlay-rainbow")
    val hueShift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "daily-overlay-hue"
    )

    val bgBrush = Brush.linearGradient(
        colors = listOf(
            rainbowColor(hueShift + 0f).copy(alpha = 0.90f),
            rainbowColor(hueShift + 90f).copy(alpha = 0.90f),
            rainbowColor(hueShift + 180f).copy(alpha = 0.90f),
            rainbowColor(hueShift + 270f).copy(alpha = 0.90f)
        )
    )

    Dialog(onDismissRequest = onLater) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(12.dp)
        ) {
            Card {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bgBrush)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Daily-Moment gestartet!",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        "Jetzt sofort aufnehmen: Rueckkamera + Frontkamera.",
                        color = Color.White
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onCaptureNow,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF111111)
                            )
                        ) {
                            Text("Daily-Moment aufnehmen")
                        }
                        TextButton(
                            onClick = onLater,
                            modifier = Modifier.weight(0.45f)
                        ) {
                            Text("Spaeter", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSubsection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingText: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label)
            if (!supportingText.isNullOrBlank()) {
                Text(supportingText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(
            modifier = Modifier.widthIn(min = 56.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private fun isWithinDailyMomentWindow(createdAtRaw: String?, triggeredAtRaw: String?, uploadUntilRaw: String?): Boolean {
    val created = parseOffsetOrLocalDateTime(createdAtRaw) ?: return false
    val triggered = parseOffsetOrLocalDateTime(triggeredAtRaw) ?: return false
    val until = parseOffsetOrLocalDateTime(uploadUntilRaw) ?: return false
    return !created.isBefore(triggered) && !created.isAfter(until)
}

private fun parseOffsetOrLocalDateTime(raw: String?): LocalDateTime? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        OffsetDateTime.parse(raw)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDateTime()
    }.getOrElse {
        runCatching {
            LocalDateTime.parse(raw)
        }.getOrElse {
            runCatching {
                LocalDateTime.parse(raw.replace(" ", "T"))
            }.getOrNull()
        }
    }
}

private fun formatCapsuleOpenAt(raw: String?): String {
    if (raw.isNullOrBlank()) return "spaeter"
    val parsed = runCatching {
        OffsetDateTime.parse(raw)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    }.getOrElse {
        runCatching {
            LocalDateTime.parse(raw.replace(" ", "T"))
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
        }.getOrElse {
            raw.take(16).replace('T', ' ')
        }
    }
    return parsed
}

private fun themeModeValue(darkMode: Boolean, oledMode: Boolean): Int {
    return if (!darkMode) 0 else if (oledMode) 2 else 1
}

private fun themeModeLabel(mode: Int): String {
    return when (mode) {
        0 -> "Light"
        1 -> "Dark"
        else -> "OLED-Schwarz"
    }
}

private fun momentReasonLine(triggerSource: String?, requestedByUser: String?): String? {
    val src = triggerSource?.trim().orEmpty().lowercase()
    return if (src == "special_request" || src == "chat_command") {
        if (!requestedByUser.isNullOrBlank()) "Sondermoment von $requestedByUser" else "Sondermoment"
    } else if (src.isNotBlank()) {
        "Daily-Moment"
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

private fun visibleQueueItems(items: List<QueuedUploadItem>, nowMs: Long = System.currentTimeMillis()): List<QueuedUploadItem> {
    val successKeepMs = 90_000L
    val failedKeepMs = 12 * 60 * 60 * 1000L
    return items
        .asSequence()
        .filter { item ->
            when (item.status) {
                UploadQueueStatus.SUCCESS -> (nowMs - item.updatedAtMs) <= successKeepMs
                UploadQueueStatus.FAILED -> (nowMs - item.updatedAtMs) <= failedKeepMs
                else -> true
            }
        }
        .sortedByDescending { it.updatedAtMs }
        .take(6)
        .toList()
}

private fun rainbowColor(hue: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val intColor = AndroidColor.HSVToColor(floatArrayOf(h, 0.55f, 0.95f))
    return Color(intColor)
}

@Composable
private fun DailyMomentBadge() {
    val transition = rememberInfiniteTransition(label = "daily-feed-badge-rainbow")
    val hueShift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "daily-feed-badge-hue"
    )
    val rainbowBrush = Brush.horizontalGradient(
        colors = listOf(
            rainbowColor(hueShift + 0f),
            rainbowColor(hueShift + 70f),
            rainbowColor(hueShift + 140f),
            rainbowColor(hueShift + 210f)
        )
    )
    Box(
        modifier = Modifier
            .background(rainbowBrush, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text("Daily-Moment", color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

private fun resolveNotificationToneTitle(context: Context, uriValue: String): String {
    val raw = uriValue.trim()
    if (raw.isBlank()) return ""
    return runCatching {
        val uri = Uri.parse(raw)
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return@runCatching raw
        ringtone.getTitle(context)?.trim().orEmpty()
    }.getOrElse { raw }
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
    val root = generateSequence(t) { it.cause }.last()
    return when (root) {
        is UnknownHostException -> "Servername konnte nicht aufgeloest werden."
        is ConnectException -> "Server ist aktuell nicht erreichbar."
        is SocketTimeoutException -> "Server antwortet zu langsam."
        else -> t.message ?: fallback
    }
}

private fun createTempImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File.createTempFile("moment_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun safeApiString(value: String?, fallback: String = ""): String {
    val clean = value?.trim().orEmpty()
    return if (clean.isBlank()) fallback else clean
}

private fun fallbackChangelogLines(): List<String> {
    return listOf(
        "Release-Infos konnten nicht von GitHub geladen werden.",
        "Bitte pruefe spaeter erneut oder oeffne die Release-Seite im Browser."
    )
}

private fun helpLines(): List<String> = listOf(
    "Willkommen bei Daily. Ziel ist ein kurzer, gemeinsamer Moment pro Tag.",
    "",
    "Grundregeln",
    "- Pro Nutzer ist ein Tagesmoment (Prompt-Post) pro Tag erlaubt.",
    "- Der heutige Feed bleibt gesperrt, bis du dein Tagesmoment gepostet hast.",
    "- Fruehere und spaetere Posts sind erlaubt und werden mit Uhrzeit angezeigt.",
    "- Zusaetzliche Bilder sind jederzeit moeglich (ausser bei serverseitigen Limits).",
    "",
    "Reiter U: Kamera",
    "- Tagesmoment aufnehmen: immer 2 Fotos (Rueckkamera + Frontkamera).",
    "- Upload startet danach automatisch im Hintergrund (mit Upload-Queue).",
    "- Weitere Bilder hinzufuegen: zusaetzliche Posts am selben Tag.",
    "- Time Capsule (nur ausserhalb des aktiven Daily-Fensters):",
    "  in einer Woche 👶 / in 30 Tagen 🧑 / in einem Jahr 💀.",
    "- Vor Time Capsule gibt es eine Sicherheitsabfrage.",
    "- Sondermoment: jeder Nutzer kann 1x pro Woche einen Sondermoment anfordern.",
    "",
    "Reiter T: Feed",
    "- Alle Tage als Verlauf mit Tages-Headern und klarer Trennung.",
    "- Daily-Moment wird nur gezeigt, wenn der Post im echten Daily-Zeitfenster lag.",
    "- Reaktionen und Kommentare stehen direkt unter den Bildern.",
    "- Kommentare sind chronologisch (aelter oben, neuer unten).",
    "- Time-Capsule-Hinweise koennen dich zu entsperrten Capsule-Posts springen lassen.",
    "",
    "Reiter G: Kalender",
    "- Zeigt nur Tage, an denen Beitraege vorhanden sind.",
    "- Jeder Eintrag zeigt Datum + Anzahl der geposteten Bilder.",
    "- Tippen auf einen Tag springt in den Feed an diese Stelle.",
    "",
    "Reiter D: Chat",
    "- Gruppenchat fuer die gesamte Gruppe.",
    "- Ungelesene Nachrichten werden am Tab markiert.",
    "- Datumsbloecke sind farblich pro Wochentag hervorgehoben.",
    "- Chat-Push kann im Profil separat aktiviert/deaktiviert werden.",
    "",
    "Reiter M: Profil",
    "- Profil, Streak, alte Beitraege und Verbindungsstatus.",
    "- Community-Stats zeigen Gruppenaktivitaet von heute und den letzten 7 Tagen.",
    "- Benutzername und Namensfarbe anpassen.",
    "- Invite-Code ansehen, erneuern und direkt teilen.",
    "- Vergangene Beitraege: lang druecken zum Loeschen (mit Bestaetigung).",
    "- App & Verbindung: Verbindung pruefen inkl. Ping, Server-Version, Push-Provider.",
    "- Passwort aendern, Theme (Light/Dark/OLED), Upload-Qualitaet (20-100).",
    "",
    "Benachrichtigungen",
    "- Master-Schalter aktiviert/deaktiviert alle App-Benachrichtigungen.",
    "- Einzel-Toggles: Update-Checks, Chat-Push, Push bei Posts anderer Nutzer, Reaktionen auf eigene Beitraege, Kommentare auf eigene Beitraege und Push bei neuen Mitgliedern.",
    "- Optional: eigener Benachrichtigungston + Ton-Test.",
    "",
    "Updates und Changelog",
    "- Update pruefen sucht nach neuen Releases auf GitHub.",
    "- Das Symbol ! oeffnet den Changelog-Dialog.",
    "- Bei neuer App-Version wird der Changelog beim ersten Start automatisch angezeigt.",
    "",
    "Hinweis",
    "- Einige Funktionen (z. B. Push-Zustellung) haengen von korrekter Server/FCM-Konfiguration ab."
)

private val viewerReactionEmojis = listOf("❤️", "👍", "😂", "🔥", "😮")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FullscreenPhotoViewer(
    urls: List<String>,
    initialIndex: Int,
    photoId: Long?,
    comment: String,
    interactions: PhotoInteractionsResponse?,
    interactionsLoading: Boolean,
    ownDownloadFallback: Boolean,
    onCommentChange: (String) -> Unit,
    onCommentSend: () -> Unit,
    onReact: (String) -> Unit,
    onDoubleTapReact: () -> Unit,
    onDownloadCurrent: (String) -> Unit,
    onIndexChange: (Int) -> Unit,
    onClose: () -> Unit
) {
    if (urls.isEmpty()) return
    val viewerBg = MaterialTheme.colorScheme.surface
    val viewerFg = MaterialTheme.colorScheme.onSurface
    val safeInitial = initialIndex.coerceIn(0, urls.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { urls.size })
    val scales = remember(urls) { mutableStateMapOf<Int, Float>() }
    val currentScale = scales[pagerState.currentPage] ?: 1f
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)

    LaunchedEffect(pagerState.currentPage) {
        onIndexChange(pagerState.currentPage)
        scales[pagerState.currentPage] = 1f
    }
    LaunchedEffect(safeInitial, urls.size) {
        if (pagerState.currentPage != safeInitial) {
            pagerState.scrollToPage(safeInitial)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = 170.dp,
            sheetContent = {
                ViewerInteractionSheet(
                    photoId = photoId,
                    currentImageUrl = urls.getOrNull(pagerState.currentPage).orEmpty(),
                    comment = comment,
                    interactions = interactions,
                    interactionsLoading = interactionsLoading,
                    ownDownloadFallback = ownDownloadFallback,
                    onCommentChange = onCommentChange,
                    onCommentSend = onCommentSend,
                    onReact = onReact,
                    onDownloadCurrent = onDownloadCurrent
                )
            },
            containerColor = viewerBg,
            contentColor = viewerFg
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(viewerBg)
                    .padding(innerPadding)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${pagerState.currentPage + 1} / ${urls.size}",
                        color = viewerFg,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = onClose) { Text("Schliessen") }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .pointerInput(currentScale, pagerState.currentPage) {
                            if (currentScale <= 1f) {
                                var dragY = 0f
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, dragAmount ->
                                        dragY += dragAmount
                    change.consumePositionChange()
                                    },
                                    onDragEnd = {
                                        if (dragY > 140f) onClose()
                                        dragY = 0f
                                    }
                                )
                            }
                        }
                ) {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = currentScale <= 1f,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        ZoomableViewerImage(
                            url = urls[page],
                            active = page == pagerState.currentPage,
                            onScaleChanged = { scale -> scales[page] = scale },
                            onDoubleTap = onDoubleTapReact
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerInteractionSheet(
    photoId: Long?,
    currentImageUrl: String,
    comment: String,
    interactions: PhotoInteractionsResponse?,
    interactionsLoading: Boolean,
    ownDownloadFallback: Boolean,
    onCommentChange: (String) -> Unit,
    onCommentSend: () -> Unit,
    onReact: (String) -> Unit,
    onDownloadCurrent: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Unter diesem Bild kannst du reagieren oder kommentieren.")
        if (photoId == null) return@Column
        val countByEmoji = interactions?.reactions.orEmpty().associate { it.emoji to it.count }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            viewerReactionEmojis.forEach { emoji ->
                val selected = interactions?.myReaction == emoji
                Button(
                    onClick = { onReact(emoji) },
                    modifier = Modifier.weight(1f)
                ) {
                    val count = countByEmoji[emoji] ?: 0L
                    Text("${if (selected) "+ " else ""}$emoji $count")
                }
            }
        }
        if ((interactions?.canDownload == true || ownDownloadFallback) && currentImageUrl.isNotBlank()) {
            Button(
                onClick = { onDownloadCurrent(currentImageUrl) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Post herunterladen") }
        }
        OutlinedTextField(
            value = comment,
            onValueChange = onCommentChange,
            label = { Text("Kommentar") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onCommentSend,
            enabled = comment.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Kommentieren") }
        if (interactionsLoading) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableViewerImage(
    url: String,
    active: Boolean,
    onScaleChanged: (Float) -> Unit,
    onDoubleTap: (() -> Unit)? = null
) {
    var scale by remember(url) { mutableStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        if (nextScale == 1f) {
            offset = Offset.Zero
        } else {
            offset += panChange
        }
        scale = nextScale
    }

    LaunchedEffect(active) {
        if (active) {
            scale = 1f
            offset = Offset.Zero
            onScaleChanged(1f)
        }
    }
    LaunchedEffect(scale) {
        onScaleChanged(scale)
    }

    AsyncImage(
        model = url,
        contentDescription = "Vollbild",
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(url) {
                detectTapGestures(
                    onDoubleTap = {
                        onDoubleTap?.invoke()
                    }
                )
            }
            // Important: keep single-finger horizontal swipes for pager navigation.
            // Pan gestures are only consumed while zoomed in.
            .transformable(
                state = transformState,
                canPan = { scale > 1f }
            ),
        contentScale = ContentScale.Fit
    )
}
