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
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.media.RingtoneManager
import android.provider.OpenableColumns
import android.provider.Settings
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
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.draw.blur
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
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random
import kotlin.coroutines.resume

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

data class PendingFotomojiCapture(
    val photoId: Long,
    val emoji: String,
    val saveTemplate: Boolean
)

data class User(
    val id: Long,
    val username: String,
    val isAdmin: Boolean,
    val favoriteColor: String = "#1F5FBF",
    val chatPushEnabled: Boolean = false,
    val pollPushEnabled: Boolean = false,
    val specialMomentPushEnabled: Boolean = false,
    val inviteRegistrationPushEnabled: Boolean = false,
    val photoReactionPushEnabled: Boolean = false,
    val photoCommentPushEnabled: Boolean = false,
    val allowPhotoDownload: Boolean = false,
    val locationFeatureEnabled: Boolean = false,
    val locationShareDefaultEnabled: Boolean = false,
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
    val quietHoursEnd: String = "07:00",
    val diagnosticsConsentGranted: Boolean = false,
    val diagnosticsConsentUpdatedAt: String? = null,
    val diagnosticsConsentSource: String? = null
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
    val pollPushEnabled: Boolean,
    val specialMomentPushEnabled: Boolean? = null,
    val inviteRegistrationPushEnabled: Boolean,
    val photoReactionPushEnabled: Boolean,
    val photoCommentPushEnabled: Boolean,
    val allowPhotoDownload: Boolean,
    val locationFeatureEnabled: Boolean? = null,
    val locationShareDefaultEnabled: Boolean? = null,
    val diagnosticsConsentGranted: Boolean? = null,
    val diagnosticsConsentSource: String? = null
)
data class UserPromptRule(
    val id: String,
    val enabled: Boolean = true,
    val triggerType: String = "app_version",
    val title: String = "",
    val body: String = "",
    val confirmLabel: String = "Zustimmen",
    val declineLabel: String = "Nicht teilen",
    val cooldownHours: Int = 0,
    val priority: Int = 0
)
data class UserPromptEvaluationResponse(
    val items: List<UserPromptRule> = emptyList(),
    val appVersion: String = ""
)
data class AuthResponse(
    val token: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val sessionId: String = "",
    val user: User
)
data class LoginRequest(val username: String, val password: String, val deviceName: String? = null)
data class InviteCodeRequest(val inviteCode: String)
data class InviteRegisterRequest(
    val inviteCode: String,
    val username: String,
    val password: String,
    val deviceName: String? = null
)
data class RefreshRequest(val refreshToken: String)
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
data class ChatPollOption(
    val id: Long,
    val text: String,
    val votes: Long = 0,
    val selected: Boolean = false
)
data class ChatPoll(
    val question: String = "",
    val allowMultiSelect: Boolean = false,
    val options: List<ChatPollOption>? = emptyList(),
    val mySelectedOptionIds: List<Long>? = emptyList(),
    val totalVoters: Long = 0,
    val isClosed: Boolean = false,
    val closedAt: String? = null,
    val canClose: Boolean = false
)
data class ChatPollCreateRequest(
    val question: String,
    val options: List<String>,
    val allowMultiSelect: Boolean = false
)
data class ChatPollVoteRequest(val optionIds: List<Long>)
data class ChatPollUpdateResponse(val ok: Boolean = false, val poll: ChatPoll? = null)
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
    val capsuleGroupRemind: Boolean = false,
    val capsulePreviewUrl: String? = null,
    val capsuleLocked: Boolean = false,
    val locationShared: Boolean = false,
    val locationDisplay: String? = null,
    val locationMapsUrl: String? = null
)
data class PromptResponse(
    val day: String,
    val canUpload: Boolean,
    val triggered: String? = null,
    val dailyTriggeredAt: String? = null,
    val dailyPending: Boolean = true,
    val specialTriggeredAt: String? = null,
    val specialRequestedByUser: String? = null,
    val specialRequestedByUserColor: String? = null,
    val hasPosted: Boolean = false,
    val hasPromptPostedToday: Boolean = false,
    val hasVisiblePostToday: Boolean = false,
    val hasAnyPostToday: Boolean = false,
    val ownPhoto: PromptPhoto? = null,
    val triggerSource: String? = null,
    val requestedByUser: String? = null,
    val momentKind: String? = null
)
data class PromptMeta(
    val day: String = "",
    val triggeredAt: String? = null,
    val uploadUntil: String? = null,
    val triggerSource: String? = null,
    val requestedByUser: String? = null,
    val momentKind: String? = null,
    val specialRequestedByUser: String? = null,
    val specialRequestedByUserColor: String? = null
)
data class FeedItem(
    val isEarly: Boolean = false,
    val isLate: Boolean = false,
    val capsuleLocked: Boolean = false,
    val capsuleReleased: Boolean = false,
    val photo: PromptPhoto,
    val user: User,
    val reactions: List<ReactionCount>? = null,
    val photoMojis: List<PhotoMojiItem>? = null,
    val comments: List<PhotoCommentItem>? = null,
    val triggerSource: String? = null,
    val requestedByUser: String? = null,
    val momentKind: String? = null,
    val specialRequestedByUserColor: String? = null
)

data class PendingLocationPayload(
    val latitude: Double,
    val longitude: Double
)
data class PhotoMojiItem(
    val id: Long,
    val emoji: String,
    val url: String,
    val createdAt: String,
    val user: User
)
data class MyPhotoMoji(
    val id: Long,
    val emoji: String,
    val url: String,
    val createdAt: String,
    val user: User? = null
)
data class FotomojiTemplateItem(
    val id: Long? = null,
    val emoji: String,
    val url: String,
    val createdAt: String? = null,
    val updatedAt: String? = null
)
data class FotomojiTemplatesResponse(val items: List<FotomojiTemplateItem> = emptyList())

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
    val momentKind: String? = null,
    val specialRequestedByUser: String? = null,
    val specialRequestedByUserColor: String? = null,
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
    val source: String = "user",
    val type: String = "text",
    val poll: ChatPoll? = null
)
data class ChatResponse(val items: List<ChatItem>)
data class ReactionCount(val emoji: String, val count: Long)
data class PhotoCommentItem(val id: Long, val body: String, val createdAt: String, val user: User)
data class PhotoInteractionsResponse(
    val photoId: Long,
    val reactions: List<ReactionCount> = emptyList(),
    val myReaction: String = "",
    val photoMojis: List<PhotoMojiItem> = emptyList(),
    val myPhotoMoji: MyPhotoMoji? = null,
    val comments: List<PhotoCommentItem> = emptyList(),
    val canDownload: Boolean = false
)
data class PhotoReactionRequest(val emoji: String)
data class PhotoFotomojiRequest(val emoji: String)
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
data class MigrationInfo(
    val enabled: Boolean = false,
    val startedAt: String? = null,
    val until: String? = null,
    val autoOffEnabled: Boolean = true,
    val autoOffReason: String? = null,
    val targetBaseUrl: String? = null,
    val downloadUrl: String? = null,
    val pushTitle: String? = null,
    val pushBody: String? = null,
    val screenTitle: String? = null,
    val screenBody: String? = null,
    val requirePromptFirst: Boolean = true,
    val baselineUserCount: Int = 0,
    val migratedUserCount: Int = 0,
    val migrationRatio: Double = 0.0,
    val remainingSeconds: Long = 0L
)
data class MigrationInfoResponse(
    val migration: MigrationInfo = MigrationInfo(),
    val serverNow: String? = null
)
data class MigrationHandoffRequest(
    val appVersion: String = BuildConfig.VERSION_NAME,
    val deviceName: String? = null
)
data class MigrationHandoffResponse(
    val handoffToken: String,
    val targetBaseUrl: String,
    val expiresAt: String? = null
)
data class MigrationHandoffConsumeRequest(
    val handoffToken: String,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val deviceName: String? = null
)
data class PromptRulesResponse(
    val promptWindowStartHour: Int,
    val promptWindowEndHour: Int,
    val uploadWindowMinutes: Int,
    val maxUploadBytes: Long,
    val timezone: String
)
data class DashboardBootstrapResponse(
    val schemaVersion: String = "dashboard_bootstrap_v1",
    val serverNow: String? = null,
    val me: MeResponse,
    val inviteCode: String = "",
    val prompt: PromptResponse,
    val promptRules: PromptRulesResponse,
    val specialMomentStatus: SpecialMomentStatus,
    val photos: List<PromptPhoto> = emptyList(),
    val chat: List<ChatItem> = emptyList(),
    val feedDays: List<String> = emptyList(),
    val communityStats: CommunityStatsResponse? = null
)

data class ClientDebugLogUploadRequest(
    val type: String,
    val message: String,
    val meta: String = "",
    val appVersion: String,
    val deviceName: String,
    val sessionId: String = "",
    val requestId: String = ""
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

    @GET("migration/info")
    suspend fun migrationInfo(): MigrationInfoResponse

    @POST("migration/handoff/consume")
    suspend fun migrationHandoffConsume(@Body body: MigrationHandoffConsumeRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthResponse

    @POST("auth/register/preview")
    suspend fun previewInvite(@Body body: InviteCodeRequest): InvitePreviewResponse

    @POST("auth/register/confirm")
    suspend fun registerWithInvite(@Body body: InviteRegisterRequest): AuthResponse

    @POST("auth/logout")
    suspend fun logout(@Header("Authorization") token: String)

    @POST("auth/logout-all")
    suspend fun logoutAll(@Header("Authorization") token: String)

    @GET("me")
    suspend fun me(@Header("Authorization") token: String): MeResponse

    @GET("me/user-prompts/evaluate")
    suspend fun evaluateUserPrompts(
        @Header("Authorization") token: String,
        @Query("appVersion") appVersion: String
    ): UserPromptEvaluationResponse

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

    @GET("dashboard/bootstrap")
    suspend fun dashboardBootstrap(
        @Header("Authorization") token: String,
        @Query("includeChat") includeChat: Boolean = true,
        @Query("includePhotos") includePhotos: Boolean = true,
        @Query("includeCommunity") includeCommunity: Boolean = true
    ): DashboardBootstrapResponse

    @GET("moment/special/status")
    suspend fun specialMomentStatus(@Header("Authorization") token: String): SpecialMomentStatus

    @POST("moment/special/request")
    suspend fun requestSpecialMoment(@Header("Authorization") token: String)

    @GET("feed")
    suspend fun feed(@Header("Authorization") token: String, @Query("day") day: String): FeedResponse

    @GET("feed/days")
    suspend fun feedDays(
        @Header("Authorization") token: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null
    ): DayListResponse

    @GET("feed/day-stats")
    suspend fun feedDayStats(
        @Header("Authorization") token: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null
    ): DayStatsResponse

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

    @POST("migration/handoff")
    suspend fun migrationHandoff(
        @Header("Authorization") token: String,
        @Body body: MigrationHandoffRequest
    ): MigrationHandoffResponse

    @Multipart
    @POST("uploads")
    suspend fun upload(
        @Header("Authorization") token: String,
        @Part photo: MultipartBody.Part,
        @Part("kind") kind: RequestBody,
        @Part("capsule_mode") capsuleMode: RequestBody? = null,
        @Part("capsule_private") capsulePrivate: RequestBody? = null,
        @Part("capsule_group_remind") capsuleGroupRemind: RequestBody? = null,
        @Part("location_shared") locationShared: RequestBody? = null,
        @Part("location_latitude") locationLatitude: RequestBody? = null,
        @Part("location_longitude") locationLongitude: RequestBody? = null
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
        @Part("capsule_group_remind") capsuleGroupRemind: RequestBody? = null,
        @Part("location_shared") locationShared: RequestBody? = null,
        @Part("location_latitude") locationLatitude: RequestBody? = null,
        @Part("location_longitude") locationLongitude: RequestBody? = null
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

    @POST("chat/polls")
    suspend fun createChatPoll(@Header("Authorization") token: String, @Body body: ChatPollCreateRequest): ChatItem

    @POST("chat/polls/{id}/vote")
    suspend fun voteChatPoll(
        @Header("Authorization") token: String,
        @Path("id") id: Long,
        @Body body: ChatPollVoteRequest
    ): ChatPollUpdateResponse

    @POST("chat/polls/{id}/close")
    suspend fun closeChatPoll(@Header("Authorization") token: String, @Path("id") id: Long): ChatPollUpdateResponse

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

    @POST("photos/{id}/fotomojis")
    suspend fun fotomojiPhotoFromTemplate(
        @Header("Authorization") token: String,
        @Path("id") id: Long,
        @Body body: PhotoFotomojiRequest
    ): PhotoInteractionsResponse

    @Multipart
    @POST("photos/{id}/fotomojis/upload")
    suspend fun uploadPhotoFotomoji(
        @Header("Authorization") token: String,
        @Path("id") id: Long,
        @Part photo: MultipartBody.Part,
        @Part("emoji") emoji: RequestBody,
        @Part("saveTemplate") saveTemplate: RequestBody? = null
    ): PhotoInteractionsResponse

    @GET("me/fotomojis/templates")
    suspend fun listFotomojiTemplates(@Header("Authorization") token: String): FotomojiTemplatesResponse

    @Multipart
    @POST("me/fotomojis/templates")
    suspend fun upsertFotomojiTemplate(
        @Header("Authorization") token: String,
        @Part photo: MultipartBody.Part,
        @Part("emoji") emoji: RequestBody
    ): FotomojiTemplatesResponse

    @DELETE("me/fotomojis/templates/{emoji}")
    suspend fun deleteFotomojiTemplate(
        @Header("Authorization") token: String,
        @Path(value = "emoji", encoded = true) emoji: String
    )

    @POST("photos/{id}/comments")
    suspend fun commentPhoto(
        @Header("Authorization") token: String,
        @Path("id") id: Long,
        @Query("full") full: Int = 0,
        @Body body: PhotoCommentRequest
    ): PhotoInteractionsResponse
}

class AppRepo(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val fusedLocationClient by lazy { LocationServices.getFusedLocationProviderClient(context) }
    @Volatile
    private var api: Api = buildApiService(resolveApiBaseUrl(context), httpClient)
    private val maxUploadDimensionPx = 1600
    private val debugLogsPrefKey = "debug_logs_v1"
    private val debugUploadEnabledKey = "debug_upload_enabled"
    private val debugLastUploadAtKey = "debug_last_upload_at"
    private val diagnosticsConsentLocalKey = "diagnostics_consent_local"
    private val diagnosticsConsentPendingKey = "diagnostics_consent_pending"
    private val diagnosticsSessionIdKey = "diagnostics_session_id"
    private val promptSeenVersionPrefix = "user_prompt_seen_version_"
    private val debugMaxEntries = 500
    private val debugUploadMinIntervalMs = 5 * 60 * 1000L
    private val refreshMutex = Mutex()
    @Volatile
    private var lastAuthTransitionReason: String = "startup"

    fun token(): String = accessToken()

    private fun accessToken(): String {
        val access = prefs.getString("access_token", "") ?: ""
        if (access.isNotBlank()) return access
        return prefs.getString("token", "") ?: ""
    }

    private fun refreshToken(): String = prefs.getString("refresh_token", "") ?: ""

    private fun currentSessionId(): String = prefs.getString("session_id", "") ?: ""

    fun resolvedApiBaseUrl(): String = resolveApiBaseUrl(context)

    fun apiBaseUrlOverrideRaw(): String = currentApiBaseUrlOverride(context)

    fun isApiBaseUrlOverrideActive(): Boolean = com.selfhosted.daily.isApiBaseUrlOverrideActive(context)

    fun allowInsecureHttpOverride(): Boolean = com.selfhosted.daily.allowInsecureHttpOverride(context)

    fun setAllowInsecureHttpOverride(enabled: Boolean) {
        com.selfhosted.daily.setAllowInsecureHttpOverride(context, enabled)
    }

    fun validateApiBaseUrlInput(raw: String): ApiBaseUrlValidationResult =
        com.selfhosted.daily.validateApiBaseUrlInput(raw, allowInsecureHttpOverride())

    fun setApiBaseUrlOverride(normalizedOrBlank: String) {
        com.selfhosted.daily.setApiBaseUrlOverride(context, normalizedOrBlank)
        api = buildApiService(resolveApiBaseUrl(context), httpClient)
    }

    fun hasUsableNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return hasInternet
    }

    fun networkSnapshotMeta(): String {
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
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return "activeNetwork=true;capabilities=true;internet=$internet;validated=$validated;metered=$metered;transport=$transport"
    }

    fun saveToken(token: String) {
        val clean = token.trim()
        prefs.edit()
            .putString("token", clean)
            .putString("access_token", clean)
            .apply()
    }

    private fun recordAuthStateTransition(
        reason: String,
        endpoint: String = "",
        httpCode: Int? = null,
        rootCause: String = "",
        beforeTokenPresent: Boolean,
        afterTokenPresent: Boolean
    ) {
        val cleanReason = reason.trim().ifBlank { "unknown" }.take(64)
        lastAuthTransitionReason = cleanReason
        logDebug(
            type = "auth_state_transition",
            message = cleanReason,
            meta = "beforeTokenPresent=$beforeTokenPresent;afterTokenPresent=$afterTokenPresent;endpoint=${endpoint.ifBlank { "-" }};http=${httpCode ?: -1};sessionId=${currentSessionId().ifBlank { "-" }};rootCause=${rootCause.ifBlank { "-" }}"
        )
    }

    fun authStateTransitionReason(): String = lastAuthTransitionReason.ifBlank { "unknown" }

    private fun saveAuthSession(auth: AuthResponse, source: String = "auth_response") {
        val beforeTokenPresent = accessToken().trim().isNotBlank()
        val access = auth.accessToken.trim().ifBlank { auth.token.trim() }
        val refresh = auth.refreshToken.trim()
        val sessionId = auth.sessionId.trim()
        prefs.edit().apply {
            putString("token", access)
            putString("access_token", access)
            if (refresh.isNotBlank()) putString("refresh_token", refresh) else remove("refresh_token")
            if (sessionId.isNotBlank()) putString("session_id", sessionId) else remove("session_id")
        }.apply()
        val afterTokenPresent = accessToken().trim().isNotBlank()
        recordAuthStateTransition(
            reason = source,
            endpoint = "/api/auth",
            beforeTokenPresent = beforeTokenPresent,
            afterTokenPresent = afterTokenPresent
        )
    }

    fun adoptAuthSession(auth: AuthResponse) {
        saveAuthSession(auth, source = "adopt_auth_session")
    }

    fun clearToken(reason: String = "clear_token", endpoint: String = "") {
        val beforeTokenPresent = accessToken().trim().isNotBlank()
        prefs.edit()
            .remove("token")
            .remove("access_token")
            .remove("refresh_token")
            .remove("session_id")
            .apply()
        UploadQueueManager.clear(context)
        val afterTokenPresent = accessToken().trim().isNotBlank()
        recordAuthStateTransition(
            reason = reason,
            endpoint = endpoint,
            beforeTokenPresent = beforeTokenPresent,
            afterTokenPresent = afterTokenPresent
        )
    }

    private fun authHeader(): String = "Bearer ${accessToken()}"

    private suspend fun tryRefreshSessionLocked(): Boolean {
        val oldRefresh = refreshToken().trim()
        if (oldRefresh.isBlank()) return false
        val response = runCatching { api.refresh(RefreshRequest(oldRefresh)) }.getOrElse { return false }
        val nextAccess = response.accessToken.trim().ifBlank { response.token.trim() }
        val nextRefresh = response.refreshToken.trim()
        if (nextAccess.isBlank() || nextRefresh.isBlank()) return false
        saveAuthSession(response, source = "refresh_session_success")
        return true
    }

    private suspend fun tryRefreshSession(): Boolean {
        refreshMutex.lock()
        return try {
            tryRefreshSessionLocked()
        } finally {
            refreshMutex.unlock()
        }
    }

    private suspend fun <T> authorizedCall(endpoint: String, block: suspend (header: String) -> T): T {
        val firstHeader = authHeader()
        if (firstHeader.length <= "Bearer ".length) {
            logDebug(
                type = "auth_guard",
                message = "missing access token before authorized call",
                meta = "endpoint=$endpoint;derivedFrom=${authStateTransitionReason()};sessionId=${currentSessionId().ifBlank { "-" }}"
            )
            throw IllegalStateException("missing_access_token")
        }
        try {
            return block(firstHeader)
        } catch (http: HttpException) {
            if (http.code() == 423) {
                clearToken(reason = "after_423_migration_required", endpoint = endpoint)
                throw IllegalStateException("migration_required", http)
            }
            if (http.code() != 401) throw http
            val refreshed = tryRefreshSession()
            if (!refreshed) {
                logDebug(
                    type = "auth_refresh_failed",
                    message = "Session-Refresh fehlgeschlagen",
                    meta = "endpoint=$endpoint;http=401;failureClass=token_expired_refresh_failed;sessionId=${currentSessionId().ifBlank { "-" }}"
                )
                clearToken(reason = "after_401_refresh_failed", endpoint = endpoint)
                throw IllegalStateException("token_expired_refresh_failed", http)
            }
            return block(authHeader())
        }
    }

    fun diagnosticsUploadEnabled(): Boolean = prefs.getBoolean(debugUploadEnabledKey, false)

    fun diagnosticsConsentGrantedLocal(): Boolean = prefs.getBoolean(diagnosticsConsentLocalKey, false)

    fun setDiagnosticsConsentLocal(granted: Boolean) {
        prefs.edit().putBoolean(diagnosticsConsentLocalKey, granted).apply()
    }

    fun markDiagnosticsConsentPending(granted: Boolean) {
        prefs.edit().putBoolean(diagnosticsConsentPendingKey, granted).apply()
    }

    fun diagnosticsConsentPendingOrNull(): Boolean? =
        if (prefs.contains(diagnosticsConsentPendingKey)) prefs.getBoolean(diagnosticsConsentPendingKey, false) else null

    fun clearDiagnosticsConsentPending() {
        prefs.edit().remove(diagnosticsConsentPendingKey).apply()
    }

    fun hasSeenUserPromptVersion(version: String): Boolean {
        val clean = version.trim()
        if (clean.isBlank()) return false
        return prefs.getBoolean("$promptSeenVersionPrefix$clean", false)
    }

    fun markUserPromptVersionSeen(version: String) {
        val clean = version.trim()
        if (clean.isBlank()) return
        prefs.edit().putBoolean("$promptSeenVersionPrefix$clean", true).apply()
    }

    fun setDiagnosticsUploadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(debugUploadEnabledKey, enabled).apply()
    }

    fun diagnosticsSessionId(): String {
        val existing = prefs.getString(diagnosticsSessionIdKey, "")?.trim().orEmpty()
        if (existing.isNotBlank()) return existing
        val generated = "sess_${UUID.randomUUID()}"
        prefs.edit().putString(diagnosticsSessionIdKey, generated).apply()
        return generated
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
        if (!diagnosticsConsentGrantedLocal()) return 0
        if (!force && !diagnosticsUploadEnabled()) return 0
        val now = System.currentTimeMillis()
        val last = prefs.getLong(debugLastUploadAtKey, 0L)
        if (!force && now-last < debugUploadMinIntervalMs) return 0
        val rows = recentDebugLogs(20)
        if (rows.isEmpty()) return 0
        var sent = 0
        val sessionId = diagnosticsSessionId()
        rows.forEach { row ->
            runCatching {
                val inferredRequestId = extractRequestIdFromMeta(row.meta)
                val requestId = inferredRequestId.ifBlank { "dbg_${row.id}" }
                authorizedCall("/api/debug/client-log") { token ->
                    api.uploadClientDebugLog(
                        token,
                        ClientDebugLogUploadRequest(
                            type = row.type,
                            message = row.message,
                            meta = row.meta,
                            appVersion = BuildConfig.VERSION_NAME,
                            deviceName = currentDeviceName(),
                            sessionId = sessionId,
                            requestId = requestId
                        )
                    )
                }
            }.onSuccess { sent += 1 }
        }
        if (sent > 0) {
            prefs.edit().putLong(debugLastUploadAtKey, now).apply()
        }
        return sent
    }

    private fun extractRequestIdFromMeta(meta: String): String {
        if (meta.isBlank()) return ""
        val marker = "requestId="
        val idx = meta.indexOf(marker)
        if (idx < 0) return ""
        val start = idx + marker.length
        if (start >= meta.length) return ""
        val end = meta.indexOf(';', start).let { if (it < 0) meta.length else it }
        return meta.substring(start, end).trim().take(64)
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

    fun fotomojiUploadQuality(): Int = prefs.getInt("fotomoji_upload_quality", 30).coerceIn(20, 100)

    fun setFotomojiUploadQuality(value: Int) {
        prefs.edit().putInt("fotomoji_upload_quality", value.coerceIn(20, 100)).apply()
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

    fun markUpdateInstallPending(targetVersion: String) {
        val clean = targetVersion.trim()
        if (clean.isBlank()) return
        prefs.edit()
            .putString("update_pending_target_version", clean)
            .putLong("update_pending_started_at", System.currentTimeMillis())
            .apply()
    }

    fun clearPendingUpdateMarker() {
        prefs.edit()
            .remove("update_pending_target_version")
            .remove("update_pending_started_at")
            .apply()
    }

    fun pendingUpdateInstallWarning(currentVersion: String): String? {
        val target = prefs.getString("update_pending_target_version", "")?.trim().orEmpty()
        if (target.isBlank()) return null
        if (isVersionNewer(currentVersion, target) || currentVersion.trim().equals(target, ignoreCase = true)) {
            clearPendingUpdateMarker()
            return null
        }
        val startedAt = prefs.getLong("update_pending_started_at", 0L)
        val ageMin = if (startedAt > 0L) ((System.currentTimeMillis() - startedAt) / 60_000L).coerceAtLeast(0L) else 0L
        return "Update auf $target noch nicht aktiv (installiert: $currentVersion). APK bitte erneut installieren und Installer-Bestaetigung abschliessen."
            .let { if (ageMin > 0L) "$it (gestartet vor ${ageMin}m)" else it }
    }

    fun notificationMasterEnabled(): Boolean = prefs.getBoolean("notifications_master_enabled", true)

    fun setNotificationMasterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notifications_master_enabled", enabled).apply()
    }

    fun feedPostPushEnabled(): Boolean = prefs.getBoolean("feed_post_push_enabled", false)

    fun setFeedPostPushEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("feed_post_push_enabled", enabled).apply()
    }

    fun useFotomojiReactions(): Boolean = prefs.getBoolean("use_fotomoji_reactions", false)

    fun setUseFotomojiReactions(enabled: Boolean) {
        prefs.edit().putBoolean("use_fotomoji_reactions", enabled).apply()
    }

    fun chatPushLocalEnabled(): Boolean = prefs.getBoolean("chat_push_enabled_local", false)

    fun setChatPushLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("chat_push_enabled_local", enabled).apply()
    }

    fun pollPushLocalEnabled(): Boolean = prefs.getBoolean("poll_push_enabled_local", false)

    fun setPollPushLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("poll_push_enabled_local", enabled).apply()
    }

    fun specialMomentPushLocalEnabled(): Boolean = prefs.getBoolean("special_moment_push_enabled_local", false)

    fun setSpecialMomentPushLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("special_moment_push_enabled_local", enabled).apply()
    }

    fun ensurePollPushDefaultMigration() {
        val migratedKey = "poll_push_default_migrated_v1"
        val pendingKey = "poll_push_default_pending_sync_v1"
        if (prefs.getBoolean(migratedKey, false)) {
            return
        }
        val desired = notificationMasterEnabled()
        prefs.edit()
            .putBoolean("poll_push_enabled_local", desired)
            .putBoolean(migratedKey, true)
            .putBoolean(pendingKey, true)
            .apply()
    }

    fun pollPushPendingSync(): Boolean = prefs.getBoolean("poll_push_default_pending_sync_v1", false)

    fun clearPollPushPendingSync() {
        prefs.edit().putBoolean("poll_push_default_pending_sync_v1", false).apply()
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
        val res = api.login(LoginRequest(username, password, deviceName = currentDeviceName()))
        saveAuthSession(res, source = "login_success")
        if (token().isBlank()) {
            logDebug(
                type = "auth_invalid_payload",
                message = "login response without access token",
                meta = "endpoint=/api/auth/login;rootCause=invalid_auth_payload;sessionId=${currentSessionId().ifBlank { "-" }}"
            )
            throw IllegalStateException("invalid_auth_payload")
        }
        return res.user
    }

    suspend fun previewInvite(inviteCode: String): InvitePreviewResponse =
        api.previewInvite(InviteCodeRequest(inviteCode.trim()))

    suspend fun registerWithInvite(inviteCode: String, username: String, password: String): User {
        val res = api.registerWithInvite(
            InviteRegisterRequest(
                inviteCode = inviteCode.trim(),
                username = username,
                password = password,
                deviceName = currentDeviceName()
            )
        )
        saveAuthSession(res, source = "register_success")
        if (token().isBlank()) {
            logDebug(
                type = "auth_invalid_payload",
                message = "register response without access token",
                meta = "endpoint=/api/auth/register/confirm;rootCause=invalid_auth_payload;sessionId=${currentSessionId().ifBlank { "-" }}"
            )
            throw IllegalStateException("invalid_auth_payload")
        }
        return res.user
    }

    suspend fun health(): HealthResponse = api.health()
    suspend fun migrationInfoPublic(): MigrationInfo = api.migrationInfo().migration
    suspend fun requestMigrationHandoff(accessTokenOverride: String = ""): MigrationHandoffResponse {
        val token = accessTokenOverride.trim().ifBlank { accessToken().trim() }
        if (token.isBlank()) throw IllegalStateException("missing_access_token")
        return api.migrationHandoff(
            "Bearer $token",
            MigrationHandoffRequest(
                appVersion = BuildConfig.VERSION_NAME,
                deviceName = currentDeviceName()
            )
        )
    }
    suspend fun consumeMigrationHandoff(baseUrl: String, handoffToken: String): AuthResponse =
        buildApiService(baseUrl, httpClient).migrationHandoffConsume(
            MigrationHandoffConsumeRequest(
                handoffToken = handoffToken,
                appVersion = BuildConfig.VERSION_NAME,
                deviceName = currentDeviceName()
            )
        )
    suspend fun probeHealth(baseUrl: String): HealthResponse =
        buildApiService(baseUrl, httpClient).health()
    suspend fun me(): MeResponse = authorizedCall("/api/me") { token -> api.me(token) }
    suspend fun evaluateUserPrompts(appVersion: String): UserPromptEvaluationResponse =
        authorizedCall("/api/me/user-prompts/evaluate") { token -> api.evaluateUserPrompts(token, appVersion) }
    suspend fun myInviteCode(): String =
        authorizedCall("/api/me/invite") { token -> api.myInviteCode(token).inviteCode }
    suspend fun rollMyInviteCode(): String =
        authorizedCall("/api/me/invite/roll") { token -> api.rollInviteCode(token).inviteCode }
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
        authorizedCall("/api/me/profile") { token -> api.updateProfile(
            token,
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
        ) }.user

    suspend fun uploadAvatar(uri: Uri): User {
        val file = copyUriToTemp(uri)
        val part = MultipartBody.Part.createFormData(
            "avatar",
            file.name,
            file.asRequestBody("image/*".toMediaTypeOrNull())
        )
        return authorizedCall("/api/me/avatar") { token -> api.uploadAvatar(token, part) }.user
    }

    suspend fun updatePreferences(
        chatPushEnabled: Boolean,
        pollPushEnabled: Boolean,
        inviteRegistrationPushEnabled: Boolean,
        photoReactionPushEnabled: Boolean,
        photoCommentPushEnabled: Boolean,
        allowPhotoDownload: Boolean,
        specialMomentPushEnabled: Boolean? = null,
        locationFeatureEnabled: Boolean? = null,
        locationShareDefaultEnabled: Boolean? = null,
        diagnosticsConsentGranted: Boolean? = null,
        diagnosticsConsentSource: String? = null
    ): User =
        authorizedCall("/api/me/preferences") { token -> api.updatePreferences(
            token,
            PreferencesUpdateRequest(
                chatPushEnabled = chatPushEnabled,
                pollPushEnabled = pollPushEnabled,
                inviteRegistrationPushEnabled = inviteRegistrationPushEnabled,
                photoReactionPushEnabled = photoReactionPushEnabled,
                photoCommentPushEnabled = photoCommentPushEnabled,
                allowPhotoDownload = allowPhotoDownload,
                specialMomentPushEnabled = specialMomentPushEnabled,
                locationFeatureEnabled = locationFeatureEnabled,
                locationShareDefaultEnabled = locationShareDefaultEnabled,
                diagnosticsConsentGranted = diagnosticsConsentGranted,
                diagnosticsConsentSource = diagnosticsConsentSource
            )
        ) }.user

    suspend fun prompt(): PromptResponse = authorizedCall("/api/prompt/current") { token -> api.prompt(token) }
    suspend fun promptRules(): PromptRulesResponse =
        authorizedCall("/api/prompt/rules") { token -> api.promptRules(token) }
    suspend fun dashboardBootstrap(
        includeChat: Boolean = true,
        includePhotos: Boolean = true,
        includeCommunity: Boolean = true
    ): DashboardBootstrapResponse = authorizedCall("/api/dashboard/bootstrap") { token ->
        api.dashboardBootstrap(
            token,
            includeChat = includeChat,
            includePhotos = includePhotos,
            includeCommunity = includeCommunity
        )
    }
    suspend fun specialMomentStatus(): SpecialMomentStatus =
        authorizedCall("/api/moment/special/status") { token -> api.specialMomentStatus(token) }
    suspend fun requestSpecialMoment() {
        authorizedCall("/api/moment/special/request") { token -> api.requestSpecialMoment(token) }
    }

    suspend fun feedByDay(day: String): FeedResponse = authorizedCall("/api/feed") { token -> api.feed(token, day) }
    suspend fun feedDays(from: String? = null, to: String? = null): List<String> =
        authorizedCall("/api/feed/days") { token -> api.feedDays(token, from, to).items }
    suspend fun feedDayStats(from: String? = null, to: String? = null): List<DayStatItem> =
        authorizedCall("/api/feed/day-stats") { token -> api.feedDayStats(token, from, to).items }
    suspend fun communityStats(): CommunityStatsResponse =
        authorizedCall("/api/community/stats") { token -> api.communityStats(token) }

    suspend fun myPhotos(): List<PromptPhoto> = authorizedCall("/api/me/photos") { token -> api.myPhotos(token).items }

    suspend fun userProfile(userId: Long): UserProfileResponse =
        authorizedCall("/api/users/:id/profile") { token -> api.userProfile(token, userId) }

    suspend fun deleteMyPhoto(photoId: Long) {
        authorizedCall("/api/me/photos/:id") { token -> api.deleteMyPhoto(token, photoId) }
    }

    suspend fun listChat(): List<ChatItem> = authorizedCall("/api/chat") { token -> api.chat(token).items }

    suspend fun sendChat(body: String, clientMessageId: String): ChatSendResponse {
        return authorizedCall("/api/chat") { token -> api.sendChat(
            token,
            ChatMessageRequest(body = body, clientMessageId = clientMessageId)
        ) }
    }

    suspend fun createChatPoll(question: String, options: List<String>, allowMultiSelect: Boolean): ChatItem =
        authorizedCall("/api/chat/polls") { token -> api.createChatPoll(
            token,
            ChatPollCreateRequest(question = question, options = options, allowMultiSelect = allowMultiSelect)
        ) }

    suspend fun voteChatPoll(id: Long, optionIds: List<Long>): ChatPollUpdateResponse =
        authorizedCall("/api/chat/polls/:id/vote") { token -> api.voteChatPoll(token, id, ChatPollVoteRequest(optionIds)) }

    suspend fun closeChatPoll(id: Long): ChatPollUpdateResponse =
        authorizedCall("/api/chat/polls/:id/close") { token -> api.closeChatPoll(token, id) }

    suspend fun deleteChatMessage(id: Long): DeleteChatResponse =
        authorizedCall("/api/chat/:id") { token -> api.deleteChatMessage(token, id) }

    suspend fun photoInteractions(photoId: Long): PhotoInteractionsResponse =
        authorizedCall("/api/photos/:id/interactions") { token -> api.photoInteractions(token, photoId) }

    suspend fun reactPhoto(photoId: Long, emoji: String): PhotoInteractionsResponse =
        authorizedCall("/api/photos/:id/reaction") { token -> api.reactPhoto(token, photoId, PhotoReactionRequest(emoji)) }

    suspend fun reactPhotoFotomojiFromTemplate(photoId: Long, emoji: String): PhotoInteractionsResponse =
        authorizedCall("/api/photos/:id/fotomojis") { token -> api.fotomojiPhotoFromTemplate(token, photoId, PhotoFotomojiRequest(emoji)) }

    suspend fun uploadPhotoFotomoji(
        photoId: Long,
        emoji: String,
        uri: Uri,
        saveTemplate: Boolean
    ): PhotoInteractionsResponse {
        val file = copyUriToTemp(uri, quality = fotomojiUploadQuality())
        val part = MultipartBody.Part.createFormData(
            "photo",
            file.name,
            file.asRequestBody("image/*".toMediaTypeOrNull())
        )
        val emojiBody = emoji.toRequestBody("text/plain".toMediaTypeOrNull())
        val saveTemplateBody = saveTemplate.toString().toRequestBody("text/plain".toMediaTypeOrNull())
        return authorizedCall("/api/photos/:id/fotomojis/upload") { token ->
            api.uploadPhotoFotomoji(token, photoId, part, emojiBody, saveTemplateBody)
        }
    }

    suspend fun listFotomojiTemplates(): List<FotomojiTemplateItem> =
        authorizedCall("/api/me/fotomojis/templates") { token -> api.listFotomojiTemplates(token).items }

    suspend fun upsertFotomojiTemplate(emoji: String, uri: Uri): List<FotomojiTemplateItem> {
        val file = copyUriToTemp(uri, quality = fotomojiUploadQuality())
        val part = MultipartBody.Part.createFormData(
            "photo",
            file.name,
            file.asRequestBody("image/*".toMediaTypeOrNull())
        )
        val emojiBody = emoji.toRequestBody("text/plain".toMediaTypeOrNull())
        return authorizedCall("/api/me/fotomojis/templates") { token ->
            api.upsertFotomojiTemplate(token, part, emojiBody).items
        }
    }

    suspend fun deleteFotomojiTemplate(emoji: String) {
        val encoded = Uri.encode(emoji)
        authorizedCall("/api/me/fotomojis/templates/:emoji") { token ->
            api.deleteFotomojiTemplate(token, encoded)
        }
    }

    suspend fun commentPhoto(photoId: Long, body: String): PhotoInteractionsResponse =
        authorizedCall("/api/photos/:id/comments") { token -> api.commentPhoto(token, photoId, 0, PhotoCommentRequest(body)) }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        authorizedCall("/api/me/password") { token -> api.changePassword(token, PasswordChangeRequest(currentPassword, newPassword)) }
    }

    suspend fun logoutRemoteSafe(accessTokenOverride: String = "") {
        val effectiveToken = accessTokenOverride.trim().ifBlank { token() }
        if (effectiveToken.isBlank()) return
        runCatching { api.logout("Bearer $effectiveToken") }
            .onFailure {
                logDebug(
                    type = "auth_logout_failed",
                    message = it.message ?: "logout failed",
                    meta = "endpoint=/api/auth/logout;failureClass=${it::class.java.simpleName}"
                )
            }
    }

    suspend fun logoutAllRemoteSafe() {
        if (token().isBlank()) return
        runCatching { authorizedCall("/api/auth/logout-all") { token -> api.logoutAll(token) } }
            .onFailure {
                logDebug(
                    type = "auth_logout_all_failed",
                    message = it.message ?: "logout-all failed",
                    meta = "endpoint=/api/auth/logout-all;failureClass=${it::class.java.simpleName}"
                )
            }
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

        authorizedCall("/api/devices") { token -> api.registerDevice(token, DeviceTokenRequest(deviceToken, currentDeviceName())) }
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

    suspend fun upload(
        uri: Uri,
        isPrompt: Boolean,
        shareLocation: Boolean = false,
        capsule: CapsuleUploadOptions = CapsuleUploadOptions()
    ) {
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
        val locationPayload = if (shareLocation) lastAvailableLocationPayload() else null
        if (shareLocation && locationPayload == null) {
            logDebug("location_upload_skipped", "no device location available", "endpoint=/api/uploads")
        }
        val locationShared = locationPayload?.let { "true".toRequestBody("text/plain".toMediaTypeOrNull()) }
        val latitude = locationPayload?.latitude?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
        val longitude = locationPayload?.longitude?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
        authorizedCall("/api/uploads") { token ->
            api.upload(token, part, kind, capsuleMode, capsulePrivate, capsuleGroup, locationShared, latitude, longitude)
        }
    }

    suspend fun uploadDual(
        backUri: Uri,
        frontUri: Uri,
        isPrompt: Boolean,
        shareLocation: Boolean = false,
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
        val locationPayload = if (shareLocation) lastAvailableLocationPayload() else null
        if (shareLocation && locationPayload == null) {
            logDebug("location_upload_skipped", "no device location available", "endpoint=/api/uploads/dual")
        }
        val locationShared = locationPayload?.let { "true".toRequestBody("text/plain".toMediaTypeOrNull()) }
        val latitude = locationPayload?.latitude?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
        val longitude = locationPayload?.longitude?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
        emit()
        authorizedCall("/api/uploads/dual") { token ->
            api.uploadDual(token, backPart, frontPart, kind, capsuleMode, capsulePrivate, capsuleGroup, locationShared, latitude, longitude)
        }
        onProgress(totalBytes, totalBytes)
    }

    suspend fun enqueueDualUpload(
        backUri: Uri,
        frontUri: Uri,
        isPrompt: Boolean,
        shareLocation: Boolean = false,
        capsule: CapsuleUploadOptions = CapsuleUploadOptions()
    ): QueuedUploadItem {
        val backFile = copyUriToTemp(backUri)
        val frontFile = copyUriToTemp(frontUri)
        val queuedDir = File(context.filesDir, "upload-queue").apply { mkdirs() }
        val backQueued = moveToQueueFile(backFile, queuedDir, "back")
        val frontQueued = moveToQueueFile(frontFile, queuedDir, "front")
        val locationPayload = if (shareLocation) lastAvailableLocationPayload() else null
        if (shareLocation && locationPayload == null) {
            logDebug("location_queue_skipped", "no device location available", "endpoint=/api/uploads/dual")
        }
        return UploadQueueManager.enqueueFromFiles(
            context = context,
            backPath = backQueued.absolutePath,
            frontPath = frontQueued.absolutePath,
            isPrompt = isPrompt,
            capsuleMode = capsule.mode,
            capsulePrivate = capsule.privateOnly,
            capsuleGroupRemind = capsule.groupRemind,
            locationShared = locationPayload != null,
            locationLatitude = locationPayload?.latitude,
            locationLongitude = locationPayload?.longitude,
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
        val id = dm.enqueue(request)
        markUpdateInstallPending(update.latestVersion)
        return id
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

    private suspend fun lastAvailableLocationPayload(): PendingLocationPayload? {
        if (!hasLocationPermission(context)) {
            logDebug("location_permission_missing", "location permission not granted")
            return null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val candidates = buildList<Pair<String, android.location.Location>> {
            currentHighAccuracyLocation()?.let { add("fused_current" to it) }
            currentLocationFromManager(manager)?.let { add("manager_current" to it) }
            runCatching { fusedLocationClient.lastLocation.await() }.getOrNull()?.let { add("fused_last" to it) }
            runCatching { manager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()?.let { add("gps_last" to it) }
            runCatching { manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()?.let { add("network_last" to it) }
            runCatching { manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()?.let { add("passive_last" to it) }
        }
        val best = chooseBestLocation(candidates)
        if (best == null) {
            logDebug("location_unavailable", "no usable location fix", "providers=${manager.allProviders.joinToString(",")}")
            return null
        }
        logSelectedLocation(best.first, best.second)
        return PendingLocationPayload(latitude = best.second.latitude, longitude = best.second.longitude)
    }

    private suspend fun currentHighAccuracyLocation(): android.location.Location? {
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .setDurationMillis(8_000)
            .build()
        val cancellation = CancellationTokenSource()
        return runCatching {
            fusedLocationClient.getCurrentLocation(request, cancellation.token).await()
        }.getOrNull()
    }

    private suspend fun currentLocationFromManager(manager: LocationManager): android.location.Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val provider = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        return suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            runCatching {
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun chooseBestLocation(candidates: List<Pair<String, android.location.Location>>): Pair<String, android.location.Location>? {
        val now = System.currentTimeMillis()
        return candidates
            .filter { (_, location) -> location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0 }
            .minByOrNull { (_, location) ->
                val accuracyPenalty = if (location.hasAccuracy()) location.accuracy else 5000f
                val ageMs = (now - location.time).coerceAtLeast(0L)
                val agePenalty = (ageMs / 1000f) * 1.5f
                accuracyPenalty + agePenalty
            }
    }

    private fun logSelectedLocation(source: String, location: android.location.Location) {
        val ageMs = (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
        val accuracy = if (location.hasAccuracy()) "%.1f".format(Locale.US, location.accuracy) else "unknown"
        val provider = location.provider ?: "unknown"
        logDebug(
            "location_fix_selected",
            "location fix selected",
            "source=$source;provider=$provider;accuracyMeters=$accuracy;ageMs=$ageMs;lat=${location.latitude};lon=${location.longitude}"
        )
    }

    private fun copyUriToTemp(uri: Uri, quality: Int = uploadQuality()): File {
        val resolver = context.contentResolver
        val originalName = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        } ?: "upload.jpg"
        val safeBase = originalName.substringBeforeLast(".").ifBlank { "upload" }
        val target = File(context.cacheDir, "${safeBase}_${UUID.randomUUID()}.jpg")
        val clampedQuality = quality.coerceIn(20, 100)
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
                processed.compress(Bitmap.CompressFormat.JPEG, clampedQuality, out)
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
    val feedScrollRequestId: Long = 0L,
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
    val migrationInfo: MigrationInfo? = null,
    val migrationCanUseSessionShortcut: Boolean = false,
    val serverConnected: Boolean = false,
    val serverVersion: String = "unbekannt",
    val pushProvider: String = "unknown",
    val chatDeleteSupported: Boolean = false,
    val lastPingMs: Long? = null,
    val activeApiBaseUrl: String = BuildConfig.API_BASE_URL,
    val apiBaseUrlOverride: String = "",
    val allowInsecureHttpOverride: Boolean = false,
    val applyServerOverrideInFlight: Boolean = false,
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
    val fotomojiUploadQuality: Int = 30,
    val autoUpdateEnabled: Boolean = false,
    val notificationMasterEnabled: Boolean = true,
    val useFotomojiReactions: Boolean = false,
    val feedPostPushEnabled: Boolean = false,
    val pollPushEnabled: Boolean = false,
    val specialMomentPushEnabled: Boolean = false,
    val inviteRegistrationPushEnabled: Boolean = false,
    val photoReactionPushEnabled: Boolean = false,
    val photoCommentPushEnabled: Boolean = false,
    val locationFeatureEnabled: Boolean = false,
    val locationShareDefaultEnabled: Boolean = false,
    val customNotificationToneEnabled: Boolean = false,
    val customNotificationToneUri: String = "",
    val diagnosticsUploadEnabled: Boolean = false,
    val diagnosticsConsentGranted: Boolean = false,
    val diagnosticsConsentUpdatedAt: String? = null,
    val showDiagnosticsConsentDialog: Boolean = false,
    val diagnosticsConsentPrompt: UserPromptRule? = null,
    val debugLogs: List<DebugLogEntry> = emptyList(),
    val fotomojiTemplates: List<FotomojiTemplateItem> = emptyList(),
    val fotomojiTemplatesLoading: Boolean = false,
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
    val communityStats: CommunityStatsResponse?
)

private class RefreshStageException(
    val failedCall: String,
    cause: Throwable
) : RuntimeException("refresh stage failed: $failedCall", cause)

class MainVm(private val repo: AppRepo) : ViewModel() {
    private val chatSendMutex = Mutex()
    private val profileSaveMutex = Mutex()
    private val refreshAllMutex = Mutex()
    private val pendingChatBodies = mutableMapOf<String, Long>()
    private val recentDashboardFailureAt = mutableMapOf<String, Long>()
    private val pendingChatWindowMs = 4_000L
    private val refreshAllCooldownMs = 2_000L
    private val feedAutoRefreshBaseMs = 25_000L
    private val feedAutoRefreshJitterMs = 15_000L
    private val globalRefreshSuccessBaseMs = 45_000L
    private val globalRefreshSuccessJitterMs = 30_000L
    private val globalRefreshActiveBaseMs = 20_000L
    private val globalRefreshActiveJitterMs = 15_000L
    private val launchIntentRefreshMinIntervalMs = 5_000L
    private val networkFailureBackoffStagesMs = longArrayOf(30_000L, 60_000L, 120_000L, 300_000L)
    private val circuitBreakerActivationThreshold = 3
    private val manualRefreshDuringNetworkFailureMinIntervalMs = 4_000L
    private val dashboardFailureDedupMs = 5 * 60 * 1000L
    private var lastRefreshAllStartedAt = 0L
    private var lastLaunchIntentRefreshAtMs = 0L
    private var lastManualRefreshAtMs = 0L
    private var consecutiveNetworkRefreshFailures = 0
    private var lastRefreshFailureClass: String = ""
    private var refreshCircuitOpenUntilMs = 0L
    private var nextFeedScrollRequestId = 1L
    private var calendarStatsLoadedPrefix = 0
    private var calendarStatsLoading = false
    private val staleFeedDays = mutableSetOf<String>()
    private val profileSectionIds = listOf(
        "display",
        "notifications",
        "fotomojis",
        "invite",
        "profile_account",
        "profile_privacy",
        "app_connection",
        "debug_diagnose",
        "community_stats",
        "moment_rules",
        "upload_compression",
        "past_posts"
    )
    private var profileSetupPromptShownInSession = false
    private var migrationSessionTokenSnapshot: String = ""

    init {
        repo.ensurePollPushDefaultMigration()
    }

    var state by mutableStateOf(
        UiState(
            token = repo.token(),
            darkMode = repo.isDarkMode(),
            oledMode = repo.isOledMode(),
            uploadQuality = repo.uploadQuality(),
            fotomojiUploadQuality = repo.fotomojiUploadQuality(),
            autoUpdateEnabled = repo.autoUpdateEnabled(),
            notificationMasterEnabled = repo.notificationMasterEnabled(),
            useFotomojiReactions = repo.useFotomojiReactions(),
            feedPostPushEnabled = repo.feedPostPushEnabled(),
            pollPushEnabled = repo.pollPushLocalEnabled(),
            specialMomentPushEnabled = repo.specialMomentPushLocalEnabled(),
            inviteRegistrationPushEnabled = repo.inviteRegistrationPushLocalEnabled(),
            photoReactionPushEnabled = repo.photoReactionPushLocalEnabled(),
            photoCommentPushEnabled = repo.photoCommentPushLocalEnabled(),
            customNotificationToneEnabled = repo.customNotificationToneEnabled(),
            customNotificationToneUri = repo.customNotificationToneUri(),
            diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled() && repo.diagnosticsConsentGrantedLocal(),
            diagnosticsConsentGranted = repo.diagnosticsConsentGrantedLocal(),
            activeApiBaseUrl = repo.resolvedApiBaseUrl(),
            apiBaseUrlOverride = repo.apiBaseUrlOverrideRaw(),
            allowInsecureHttpOverride = repo.allowInsecureHttpOverride(),
            debugLogs = repo.recentDebugLogs()
        )
    )
        private set

    private fun normalizeChatBody(body: String): String =
        body.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ").lowercase()

    private fun issueFeedScrollRequestId(): Long {
        val id = nextFeedScrollRequestId
        nextFeedScrollRequestId += 1L
        return id
    }

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

    private fun shouldSamplePerf(success: Boolean): Boolean {
        if (!success) return true
        return Random.nextInt(100) < 25
    }

    private fun isAuthCriticalFailure(throwable: Throwable): Boolean {
        val root = rootCause(throwable)
        if (root is IllegalStateException) {
            return root.message == "missing_access_token" ||
                root.message == "token_expired_refresh_failed" ||
                root.message == "migration_required" ||
                root.message == "invalid_auth_payload"
        }
        val http = root as? HttpException
        return http?.code() == 401 || http?.code() == 423
    }

    private fun isNetworkFailureClass(failureClass: String): Boolean =
        failureClass == "dns" || failureClass == "connect" || failureClass == "timeout" || failureClass == "offline"

    private fun classifyFailure(throwable: Throwable): String {
        val network = networkFailureKind(throwable)
        if (network != null) return network
        val http = throwable as? HttpException
        return if (http != null) "http_${http.code()}" else throwable::class.java.simpleName
    }

    private fun nextNetworkBackoffDelayMs(): Long {
        val stageIndex = (consecutiveNetworkRefreshFailures - 1).coerceIn(0, networkFailureBackoffStagesMs.lastIndex)
        val base = networkFailureBackoffStagesMs[stageIndex]
        val jitter = Random.nextLong(0L, 8_001L)
        return base + jitter
    }

    private fun markRefreshSuccess() {
        consecutiveNetworkRefreshFailures = 0
        lastRefreshFailureClass = ""
        refreshCircuitOpenUntilMs = 0L
    }

    private fun markRefreshFailure(failureClass: String, nowMs: Long): Pair<Int, Long> {
        lastRefreshFailureClass = failureClass
        if (isNetworkFailureClass(failureClass)) {
            consecutiveNetworkRefreshFailures += 1
            val delayMs = nextNetworkBackoffDelayMs()
            if (consecutiveNetworkRefreshFailures >= circuitBreakerActivationThreshold) {
                refreshCircuitOpenUntilMs = nowMs + delayMs
            }
            return consecutiveNetworkRefreshFailures to delayMs
        }
        consecutiveNetworkRefreshFailures = 0
        refreshCircuitOpenUntilMs = 0L
        return 0 to globalRefreshSuccessBaseMs
    }

    private fun refreshCircuitOpenRemainingMs(nowMs: Long): Long {
        val remaining = refreshCircuitOpenUntilMs - nowMs
        return if (remaining > 0L) remaining else 0L
    }

    private fun logPerfEvent(event: String, durationMs: Long, success: Boolean, extra: String = "") {
        if (!shouldSamplePerf(success)) return
        val meta = buildString {
            append("event=").append(event)
            append(";durationMs=").append(durationMs.coerceAtLeast(0L))
            append(";result=").append(if (success) "ok" else "error")
            append(";appVersion=").append(BuildConfig.VERSION_NAME)
            if (extra.isNotBlank()) {
                append(";").append(extra)
            }
        }
        repo.logDebug(type = "perf_event", message = event, meta = meta)
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
        val perfStartedAt = System.currentTimeMillis()
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
            fotomojiUploadQuality = repo.fotomojiUploadQuality(),
            autoUpdateEnabled = repo.autoUpdateEnabled(),
            notificationMasterEnabled = repo.notificationMasterEnabled(),
            useFotomojiReactions = repo.useFotomojiReactions(),
            feedPostPushEnabled = repo.feedPostPushEnabled(),
            pollPushEnabled = repo.pollPushLocalEnabled(),
            specialMomentPushEnabled = repo.specialMomentPushLocalEnabled(),
            photoReactionPushEnabled = repo.photoReactionPushLocalEnabled(),
            photoCommentPushEnabled = repo.photoCommentPushLocalEnabled(),
            customNotificationToneEnabled = repo.customNotificationToneEnabled(),
            customNotificationToneUri = repo.customNotificationToneUri(),
            diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled() && repo.diagnosticsConsentGrantedLocal(),
            diagnosticsConsentGranted = repo.diagnosticsConsentGrantedLocal(),
            debugLogs = repo.recentDebugLogs(),
            message = if (health?.ok == true) "" else "Server nicht erreichbar"
        )
        repo.pendingUpdateInstallWarning(BuildConfig.VERSION_NAME)?.let { warning ->
            state = state.copy(message = warning)
        }
        runCatching { checkForUpdate(silent = true) }
        logPerfEvent(
            event = "app_start",
            durationMs = System.currentTimeMillis() - perfStartedAt,
            success = healthOk,
            extra = "serverConnected=$healthOk"
        )
    }

    suspend fun login(username: String, password: String) {
        state = state.copy(loading = true, message = "")
        try {
            val user = repo.login(username, password)
            migrationSessionTokenSnapshot = ""
            state = state.copy(
                user = user,
                token = repo.token(),
                loading = false,
                invitePreview = null,
                migrationCanUseSessionShortcut = false
            )
            runCatching { repo.syncDeviceTokenIfNeeded(force = true) }
            refreshAll()
        } catch (t: Throwable) {
            if (t is IllegalStateException && t.message == "migration_required") {
                handleMigrationRequiredState()
                return
            }
            logApiFailure("auth_login_failed", "/api/auth/login", t, "username=${username.trim().lowercase()}")
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
                migrationSessionTokenSnapshot = ""
                state = state.copy(
                    user = user,
                    token = repo.token(),
                    loading = false,
                    invitePreview = null,
                    migrationCanUseSessionShortcut = false
                )
                runCatching { repo.syncDeviceTokenIfNeeded(force = true) }
                refreshAll()
            }
            .onFailure {
                if (it is IllegalStateException && it.message == "migration_required") {
                    handleMigrationRequiredState()
                    return@onFailure
                }
                logApiFailure("auth_register_failed", "/api/auth/register/confirm", it, "username=${username.trim().lowercase()}")
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
        val tokenSnapshot = repo.token()
        viewModelScope.launch {
            repo.logoutRemoteSafe(tokenSnapshot)
        }
        repo.clearToken()
        migrationSessionTokenSnapshot = ""
        profileSetupPromptShownInSession = false
        state = UiState(
            startupDone = true,
            migrationInfo = state.migrationInfo,
            migrationCanUseSessionShortcut = false,
            serverConnected = state.serverConnected,
            serverVersion = state.serverVersion,
            pushProvider = state.pushProvider,
            darkMode = state.darkMode,
            oledMode = state.oledMode,
            uploadQuality = state.uploadQuality,
            fotomojiUploadQuality = state.fotomojiUploadQuality,
            autoUpdateEnabled = repo.autoUpdateEnabled(),
            notificationMasterEnabled = repo.notificationMasterEnabled(),
            useFotomojiReactions = repo.useFotomojiReactions(),
            feedPostPushEnabled = repo.feedPostPushEnabled(),
            pollPushEnabled = repo.pollPushLocalEnabled(),
            specialMomentPushEnabled = repo.specialMomentPushLocalEnabled(),
            customNotificationToneEnabled = repo.customNotificationToneEnabled(),
            customNotificationToneUri = repo.customNotificationToneUri(),
            diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled() && repo.diagnosticsConsentGrantedLocal(),
            diagnosticsConsentGranted = repo.diagnosticsConsentGrantedLocal(),
            activeApiBaseUrl = repo.resolvedApiBaseUrl(),
            apiBaseUrlOverride = repo.apiBaseUrlOverrideRaw(),
            allowInsecureHttpOverride = repo.allowInsecureHttpOverride(),
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
        val http = throwable as? HttpException
        val code = http?.code()
        val responseRequestId = http?.response()?.headers()?.get("X-Request-ID")?.trim().orEmpty()
        val network = networkFailureKind(throwable)
        val base = buildString {
            append("endpoint=").append(endpoint)
            append(";http=").append(code ?: -1)
            append(";error=").append(throwable::class.java.simpleName)
            if (responseRequestId.isNotBlank()) {
                append(";requestId=").append(responseRequestId.take(64))
            }
            if (network != null) {
                append(";network=").append(network)
            }
            if (throwable is IllegalStateException && throwable.message == "missing_access_token") {
                append(";derivedFrom=").append(repo.authStateTransitionReason())
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
        if (enabled && !state.diagnosticsConsentGranted) {
            state = state.copy(message = "Bitte gib zuerst die freiwillige Diagnose-Freigabe.")
            return
        }
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

    fun setDiagnosticsConsentGranted(granted: Boolean, source: String = "profile_toggle") {
        repo.setDiagnosticsConsentLocal(granted)
        if (!granted) {
            repo.setDiagnosticsUploadEnabled(false)
        }
        state = state.copy(
            diagnosticsConsentGranted = granted,
            diagnosticsUploadEnabled = if (granted) state.diagnosticsUploadEnabled else false,
            diagnosticsConsentUpdatedAt = OffsetDateTime.now().toString(),
            showDiagnosticsConsentDialog = false,
            diagnosticsConsentPrompt = null,
            message = if (granted) "Danke, Diagnose-Freigabe aktiviert." else "Diagnose-Freigabe deaktiviert."
        )
        val current = state.user ?: return
        viewModelScope.launch {
            runCatching {
                repo.updatePreferences(
                    chatPushEnabled = current.chatPushEnabled,
                    pollPushEnabled = current.pollPushEnabled,
                    inviteRegistrationPushEnabled = current.inviteRegistrationPushEnabled,
                    photoReactionPushEnabled = current.photoReactionPushEnabled,
                    photoCommentPushEnabled = current.photoCommentPushEnabled,
                    allowPhotoDownload = current.allowPhotoDownload,
                    diagnosticsConsentGranted = granted,
                    diagnosticsConsentSource = source
                )
            }.onSuccess { updated ->
                repo.setDiagnosticsConsentLocal(updated.diagnosticsConsentGranted)
                repo.clearDiagnosticsConsentPending()
                state = state.copy(
                    user = updated,
                    diagnosticsConsentGranted = updated.diagnosticsConsentGranted,
                    diagnosticsConsentUpdatedAt = updated.diagnosticsConsentUpdatedAt
                )
            }.onFailure {
                repo.markDiagnosticsConsentPending(granted)
                state = state.copy(message = "Freigabe lokal gespeichert. Server-Sync folgt automatisch.")
            }
        }
    }

    fun dismissDiagnosticsConsentDialogLater() {
        state = state.copy(showDiagnosticsConsentDialog = false, diagnosticsConsentPrompt = null)
    }

    fun refreshDebugLogs() {
        state = state.copy(debugLogs = repo.recentDebugLogs())
    }

    suspend fun autoUploadDebugLogsIfEnabled() {
        if (!state.diagnosticsUploadEnabled || !state.diagnosticsConsentGranted) return
        try {
            repo.uploadRecentDebugLogs()
        } catch (_: Throwable) {
        }
    }

    private suspend fun syncPendingDiagnosticsConsent(current: User) {
        val pending = repo.diagnosticsConsentPendingOrNull() ?: return
        if (current.diagnosticsConsentGranted == pending) {
            repo.clearDiagnosticsConsentPending()
            return
        }
        runCatching {
            repo.updatePreferences(
                chatPushEnabled = current.chatPushEnabled,
                pollPushEnabled = current.pollPushEnabled,
                inviteRegistrationPushEnabled = current.inviteRegistrationPushEnabled,
                photoReactionPushEnabled = current.photoReactionPushEnabled,
                photoCommentPushEnabled = current.photoCommentPushEnabled,
                allowPhotoDownload = current.allowPhotoDownload,
                diagnosticsConsentGranted = pending,
                diagnosticsConsentSource = "offline_retry"
            )
        }.onSuccess { updated ->
            repo.clearDiagnosticsConsentPending()
            repo.setDiagnosticsConsentLocal(updated.diagnosticsConsentGranted)
            state = state.copy(
                user = updated,
                diagnosticsConsentGranted = updated.diagnosticsConsentGranted,
                diagnosticsConsentUpdatedAt = updated.diagnosticsConsentUpdatedAt,
                diagnosticsUploadEnabled = state.diagnosticsUploadEnabled && updated.diagnosticsConsentGranted
            )
        }
    }

    private suspend fun evaluateDiagnosticsConsentPrompt() {
        val user = state.user ?: return
        val version = BuildConfig.VERSION_NAME
        if (version.isBlank()) return
        if (repo.hasSeenUserPromptVersion(version)) return
        if (user.diagnosticsConsentGranted) {
            repo.markUserPromptVersionSeen(version)
            return
        }
        val result = runCatching { repo.evaluateUserPrompts(version) }.getOrNull() ?: return
        val prompt = result.items
            .filter { it.enabled && it.triggerType.equals("app_version", ignoreCase = true) }
            .maxByOrNull { it.priority }
            ?: return
        repo.markUserPromptVersionSeen(version)
        state = state.copy(
            showDiagnosticsConsentDialog = true,
            diagnosticsConsentPrompt = prompt
        )
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
        if (tab == AppTab.CALENDAR) {
            state = state.copy(activeTab = tab)
            viewModelScope.launch { ensureCalendarStatsPrefix(14) }
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
        val scrollRequestId = issueFeedScrollRequestId()
        state = state.copy(
            activeTab = AppTab.FEED,
            feedFocusDay = day,
            feedFocusPhotoId = null,
            feedScrollRequestId = scrollRequestId
        )
        loadFeedWindow(day, around = 0, forceReload = false)
        state = state.copy(
            activeTab = AppTab.FEED,
            feedFocusDay = day,
            feedFocusPhotoId = null,
            feedScrollRequestId = scrollRequestId
        )
    }

    suspend fun jumpToPhoto(day: String, photoId: Long) {
        val scrollRequestId = issueFeedScrollRequestId()
        state = state.copy(
            activeTab = AppTab.FEED,
            feedFocusDay = day,
            feedFocusPhotoId = photoId,
            feedScrollRequestId = scrollRequestId
        )
        loadFeedWindow(day, around = 3, forceReload = false)
        state = state.copy(
            activeTab = AppTab.FEED,
            feedFocusDay = day,
            feedFocusPhotoId = photoId,
            feedScrollRequestId = scrollRequestId
        )
    }

    suspend fun refreshAll(
        reason: String = "general",
        forceFeedReload: Boolean = false,
        bypassCooldown: Boolean = false,
        showLoading: Boolean = true,
        respectCircuitBreaker: Boolean = true
    ): Boolean {
        if (repo.token().isBlank()) return false
        val now = System.currentTimeMillis()
        if (respectCircuitBreaker) {
            val remaining = refreshCircuitOpenRemainingMs(now)
            if (remaining > 0L) {
                repo.logDebug(
                    type = "refresh_skipped",
                    message = "refresh circuit breaker open",
                    meta = "reason=$reason;remainingMs=$remaining;failureClass=$lastRefreshFailureClass;backoffStage=$consecutiveNetworkRefreshFailures"
                )
                return false
            }
        }
        if (!refreshAllMutex.tryLock()) return false
        if (!bypassCooldown && now - lastRefreshAllStartedAt < refreshAllCooldownMs) {
            refreshAllMutex.unlock()
            return false
        }
        lastRefreshAllStartedAt = now
        if (showLoading) {
            state = state.copy(loading = true, communityStatsLoading = true)
        } else {
            state = state.copy(communityStatsLoading = true)
        }
        var success = false
        var refreshedFeedDays = 0
        var failedCall = "none"
        try {
            repo.syncDeviceTokenIfNeeded()
            val payload = runCatching {
                val bootstrap = repo.dashboardBootstrap(
                    includeChat = true,
                    includePhotos = true,
                    includeCommunity = true
                )
                DashboardData(
                    me = bootstrap.me.user,
                    streakDays = bootstrap.me.streakDays,
                    dailyMomentCount = bootstrap.me.dailyMomentCount,
                    inviteCode = bootstrap.inviteCode,
                    prompt = bootstrap.prompt,
                    rules = bootstrap.promptRules,
                    special = bootstrap.specialMomentStatus,
                    photos = bootstrap.photos,
                    chat = bootstrap.chat,
                    feedDays = bootstrap.feedDays,
                    communityStats = bootstrap.communityStats
                )
            }.getOrElse {
                if (isAuthCriticalFailure(it)) {
                    failedCall = "dashboardBootstrap"
                    throw RefreshStageException("dashboardBootstrap", it)
                }
                val meResp = runCatching { repo.me() }.getOrElse { meErr ->
                    failedCall = "me"
                    if (isAuthCriticalFailure(meErr)) {
                        throw RefreshStageException("me", meErr)
                    }
                    val cachedUser = state.user
                    if (cachedUser == null) {
                        throw RefreshStageException("me", meErr)
                    }
                    val failureClass = classifyFailure(meErr)
                    repo.logDebug(
                        type = "dashboard_refresh_degraded",
                        message = debugFailureMessage(meErr),
                        meta = "failedCall=me;fallback=cached_user;failureClass=$failureClass"
                    )
                    MeResponse(
                        user = cachedUser,
                        dailyMomentCount = state.dailyMomentCount,
                        streakDays = state.streakDays
                    )
                }
                val fetchedInviteCode = runCatching { repo.myInviteCode() }.getOrElse {
                    failedCall = "inviteCode"
                    state.myInviteCode
                }
                val fetchedPrompt = runCatching { repo.prompt() }.getOrElse {
                    failedCall = "prompt"
                    if (isAuthCriticalFailure(it)) {
                        throw RefreshStageException("prompt", it)
                    }
                    state.prompt ?: throw RefreshStageException("prompt", it)
                }
                val fetchedRules = runCatching { repo.promptRules() }.getOrElse {
                    failedCall = "promptRules"
                    if (isAuthCriticalFailure(it)) {
                        throw RefreshStageException("promptRules", it)
                    }
                    state.promptRules ?: PromptRulesResponse(
                        promptWindowStartHour = 8,
                        promptWindowEndHour = 20,
                        uploadWindowMinutes = 60,
                        maxUploadBytes = 0,
                        timezone = "UTC"
                    )
                }
                val fetchedSpecial = runCatching { repo.specialMomentStatus() }.getOrElse {
                    failedCall = "specialMoment"
                    state.specialMomentStatus ?: SpecialMomentStatus(
                        canRequest = false,
                        requestedThisWeek = false,
                        remainingSeconds = 0L,
                        nextAllowedAt = null,
                        lastRequestedAt = null
                    )
                }
                val fetchedPhotos = runCatching { repo.myPhotos() }.getOrElse {
                    failedCall = "myPhotos"
                    state.photos
                }
                val fetchedChat = runCatching { repo.listChat() }.getOrElse {
                    failedCall = "chat"
                    state.chat
                }
                val feedDays = runCatching { repo.feedDays() }.getOrElse {
                    failedCall = "feedDays"
                    state.calendarDays
                }
                val communityStats = runCatching { repo.communityStats() }.getOrElse {
                    failedCall = "communityStats"
                    state.communityStats
                }
                DashboardData(
                    me = meResp.user,
                    streakDays = meResp.streakDays,
                    dailyMomentCount = meResp.dailyMomentCount,
                    inviteCode = fetchedInviteCode,
                    prompt = fetchedPrompt,
                    rules = fetchedRules,
                    special = fetchedSpecial,
                    photos = fetchedPhotos,
                    chat = fetchedChat,
                    feedDays = feedDays,
                    communityStats = communityStats
                )
            }
            var me = payload.me
            val streakDays = payload.streakDays
            val dailyMomentCount = payload.dailyMomentCount
            val inviteCode = payload.inviteCode
            if (repo.pollPushPendingSync()) {
                val desiredPollPush = repo.pollPushLocalEnabled()
                if (me.pollPushEnabled != desiredPollPush) {
                    runCatching {
                        repo.updatePreferences(
                            me.chatPushEnabled,
                            desiredPollPush,
                            me.inviteRegistrationPushEnabled,
                            me.photoReactionPushEnabled,
                            me.photoCommentPushEnabled,
                            me.allowPhotoDownload
                        )
                    }.onSuccess {
                        me = it
                        repo.clearPollPushPendingSync()
                    }
                } else {
                    repo.clearPollPushPendingSync()
                }
            }
            val prompt = payload.prompt
            val rules = payload.rules
            val special = payload.special
            val photos = payload.photos
            val chat = payload.chat
            val calendarDays = payload.feedDays
            val previousCalendarDays = state.calendarDays
            val calendarChanged = previousCalendarDays != calendarDays
            if (calendarChanged) {
                calendarStatsLoadedPrefix = 0
            }
            val calendarDayStats = if (calendarChanged) {
                emptyMap()
            } else {
                state.calendarDayStats.filterKeys { calendarDays.contains(it) }
            }
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
            repo.setPollPushLocalEnabled(me.pollPushEnabled)
            repo.setSpecialMomentPushLocalEnabled(me.specialMomentPushEnabled)
            repo.setInviteRegistrationPushLocalEnabled(me.inviteRegistrationPushEnabled)
            repo.syncQuietHoursFromUser(me)
            repo.setPhotoReactionPushLocalEnabled(me.photoReactionPushEnabled)
            repo.setPhotoCommentPushLocalEnabled(me.photoCommentPushEnabled)
            val notificationMaster = repo.notificationMasterEnabled()
            val feedPostPushEnabled = repo.feedPostPushEnabled()
            val pollPushEnabled = repo.pollPushLocalEnabled()
            val specialMomentPushEnabled = repo.specialMomentPushLocalEnabled()
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
                pollPushEnabled = pollPushEnabled,
                specialMomentPushEnabled = specialMomentPushEnabled,
                inviteRegistrationPushEnabled = inviteRegistrationPushEnabled,
                photoReactionPushEnabled = photoReactionPushEnabled,
                photoCommentPushEnabled = photoCommentPushEnabled,
                notificationMasterEnabled = notificationMaster && autoUpdateEnabled && feedPostPushEnabled && me.chatPushEnabled && pollPushEnabled && inviteRegistrationPushEnabled && photoReactionPushEnabled && photoCommentPushEnabled,
                diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled() && me.diagnosticsConsentGranted,
                diagnosticsConsentGranted = me.diagnosticsConsentGranted,
                diagnosticsConsentUpdatedAt = me.diagnosticsConsentUpdatedAt,
                debugLogs = repo.recentDebugLogs(),
                profileSectionExpanded = profileSectionExpanded,
                loading = if (showLoading) false else state.loading,
                showPromptDialog = state.showPromptDialog || shouldPopup,
                message = ""
            )
            repo.setDiagnosticsConsentLocal(me.diagnosticsConsentGranted)
            if (!me.diagnosticsConsentGranted && state.diagnosticsUploadEnabled) {
                repo.setDiagnosticsUploadEnabled(false)
                state = state.copy(diagnosticsUploadEnabled = false)
            }
            syncPendingDiagnosticsConsent(me)
            evaluateDiagnosticsConsentPrompt()
            applyPendingLaunchNavigation(prompt, calendarDays)
            maybeShowProfileSetupPrompt(me)
            val focus = state.feedFocusDay
            val anchor = if (focus != null && calendarDays.contains(focus)) focus else prompt.day
            if (state.feedDays.isEmpty() || !state.feedDays.contains(anchor)) {
                refreshedFeedDays = loadFeedWindow(anchor, around = 1, forceReload = forceFeedReload)
            } else {
                refreshedFeedDays = if (forceFeedReload || staleFeedDays.isNotEmpty()) {
                    loadFeedWindow(anchor, around = 1, forceReload = forceFeedReload)
                } else {
                    val today = prompt.day
                    val hasVisibleTodayFeed = state.feedByDay[today].orEmpty().isNotEmpty()
                    state = state.copy(
                        feed = state.feedByDay[today].orEmpty(),
                        feedTodayLocked = !prompt.hasVisiblePostToday && !hasVisibleTodayFeed
                    )
                    0
                }
            }
            ensureCalendarStatsPrefix(2)
            success = true
            markRefreshSuccess()
            if (reason == "feed_pull" || reason == "feed_auto" || forceFeedReload) {
                val durationMs = System.currentTimeMillis() - now
                repo.logDebug(
                    type = "feed_refresh",
                    message = "feed refresh ok",
                    meta = "reason=$reason;forced=$forceFeedReload;daysReloaded=$refreshedFeedDays;durationMs=$durationMs;refreshMode=full"
                )
            }
        } catch (t: Throwable) {
            val actual = if (t is RefreshStageException) t.cause ?: t else t
            if (t is RefreshStageException) {
                failedCall = t.failedCall
            }
            if (actual is IllegalStateException && actual.message == "migration_required") {
                handleMigrationRequiredState()
                return false
            }
            if (isBenignCancellation(t)) {
                state = state.copy(loading = if (showLoading) false else state.loading, communityStatsLoading = false)
                return false
            }
            val failureClass = classifyFailure(actual)
            val (backoffStage, delayMs) = markRefreshFailure(failureClass, System.currentTimeMillis())
            if (isNetworkFailureClass(failureClass)) {
                repo.logDebug(
                    type = "network_snapshot",
                    message = "refresh failure network snapshot",
                    meta = "reason=$reason;failureClass=$failureClass;snapshot=${repo.networkSnapshotMeta()}"
                )
            }
            state = state.copy(
                loading = if (showLoading) false else state.loading,
                communityStatsLoading = false,
                message = apiError(actual, "Laden fehlgeschlagen")
            )
            logApiFailure(
                "dashboard_load_failed",
                "refresh_all/$failedCall",
                actual,
                "failedCall=$failedCall;failureClass=$failureClass;refreshMode=full;backoffStage=$backoffStage;nextDelayMs=$delayMs"
            )
            val durationMs = System.currentTimeMillis() - now
            repo.logDebug(
                type = "feed_refresh_failed",
                message = debugFailureMessage(actual),
                meta = "reason=$reason;forced=$forceFeedReload;durationMs=$durationMs;failedCall=$failedCall;failureClass=$failureClass;refreshMode=full;backoffStage=$backoffStage;nextDelayMs=$delayMs;root=${rootCause(actual)::class.java.simpleName};derivedFrom=${if (actual is IllegalStateException && actual.message == "missing_access_token") repo.authStateTransitionReason() else "-"}"
            )
        } finally {
            refreshAllMutex.unlock()
        }
        return success
    }

    suspend fun refreshFeed(reason: String = "feed_pull") {
        if (state.feedRefreshing) return
        val now = System.currentTimeMillis()
        val isManual = reason == "feed_pull"
        if (isManual && isNetworkFailureClass(lastRefreshFailureClass) && now - lastManualRefreshAtMs < manualRefreshDuringNetworkFailureMinIntervalMs) {
            val waitMs = manualRefreshDuringNetworkFailureMinIntervalMs - (now - lastManualRefreshAtMs)
            state = state.copy(message = "Bitte kurz warten (${(waitMs / 1000L).coerceAtLeast(1L)}s), dann erneut aktualisieren.")
            return
        }
        if (isManual) {
            lastManualRefreshAtMs = now
        }
        state = state.copy(feedRefreshing = true)
        val started = System.currentTimeMillis()
        var ok = false
        try {
            ok = refreshAll(
                reason = reason,
                forceFeedReload = true,
                bypassCooldown = true,
                showLoading = false,
                respectCircuitBreaker = !isManual
            )
        } finally {
            val elapsed = System.currentTimeMillis() - started
            logPerfEvent(
                event = "refresh_feed",
                durationMs = elapsed,
                success = ok,
                extra = "reason=$reason"
            )
            if (elapsed < 700) delay(700 - elapsed)
            state = state.copy(feedRefreshing = false)
        }
    }

    suspend fun loadMoreCalendarStats(batch: Int = 30) {
        ensureCalendarStatsPrefix(calendarStatsLoadedPrefix + batch)
    }

    private suspend fun ensureCalendarStatsPrefix(targetCount: Int) {
        if (calendarStatsLoading) return
        val days = state.calendarDays
        if (days.isEmpty()) return
        val capped = targetCount.coerceIn(0, days.size)
        if (capped <= calendarStatsLoadedPrefix) return
        val startIndex = calendarStatsLoadedPrefix
        val endIndexInclusive = capped - 1
        if (startIndex > endIndexInclusive) return

        calendarStatsLoading = true
        try {
            val toDay = days[startIndex]
            val fromDay = days[endIndexInclusive]
            val fetched = runCatching { repo.feedDayStats(from = fromDay, to = toDay) }.getOrDefault(emptyList())
            state = state.copy(calendarDayStats = state.calendarDayStats + fetched.associateBy { it.day })
            calendarStatsLoadedPrefix = capped
        } finally {
            calendarStatsLoading = false
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
                val fetched = fetchDaySafe(day, forceReload = false)
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
                val fetched = fetchDaySafe(day, forceReload = false)
                newMap[day] = fetched.items
                newPromptMap[day] = fetched.meta
                fetched.monthRecap?.let { newRecapMap[day] = it }
            }
        }
        state = state.copy(feedDays = prependDays + state.feedDays, feedByDay = newMap, monthRecapByDay = newRecapMap, promptMetaByDay = newPromptMap, feedPaging = false)
    }

    private suspend fun loadFeedWindow(anchorDay: String, around: Int, forceReload: Boolean): Int {
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
                feedTodayLocked = state.prompt?.hasVisiblePostToday == false,
                feedFocusDay = state.prompt?.day,
                feedFocusPhotoId = null
            )
            return 0
        }
        val target = if (allDays.contains(anchorDay)) anchorDay else allDays.first()
        val idx = allDays.indexOf(target)
        val start = maxOf(0, idx - around)
        val end = minOf(allDays.lastIndex, idx + around)
        val days = allDays.subList(start, end + 1)
        val map = mutableMapOf<String, List<FeedItem>>()
        val monthRecapMap = mutableMapOf<String, MonthlyRecap>()
        val promptMap = mutableMapOf<String, PromptMeta>()
        var reloadedCount = 0
        var reloadErrors = 0
        for (day in days.distinct()) {
            val cachedItems = state.feedByDay[day]
            val cachedMeta = state.promptMetaByDay[day]
            val shouldReloadDay = forceReload || staleFeedDays.contains(day)
            if (!shouldReloadDay && cachedItems != null && cachedMeta != null) {
                map[day] = cachedItems
                promptMap[day] = cachedMeta
                state.monthRecapByDay[day]?.let { monthRecapMap[day] = it }
                continue
            }

            val fetched = runCatching { fetchDaySafe(day, forceReload = true) }.getOrNull()
            if (fetched != null) {
                map[day] = fetched.items
                promptMap[day] = fetched.meta
                fetched.monthRecap?.let { monthRecapMap[day] = it }
                staleFeedDays.remove(day)
                reloadedCount++
                continue
            }
            reloadErrors++

            if (cachedItems != null && cachedMeta != null) {
                map[day] = cachedItems
                promptMap[day] = cachedMeta
                state.monthRecapByDay[day]?.let { monthRecapMap[day] = it }
            } else {
                map[day] = emptyList()
                promptMap[day] = PromptMeta(day = day)
            }
        }
        val today = state.prompt?.day ?: LocalDate.now().toString()
        val postedToday = state.prompt?.hasVisiblePostToday == true
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
        if (reloadErrors > 0 && (forceReload || staleFeedDays.isNotEmpty())) {
            repo.logDebug(
                type = "feed_refresh_failed",
                message = "partial day reload fallback",
                meta = "anchor=$anchorDay;days=${days.size};errors=$reloadErrors;reloaded=$reloadedCount"
            )
        }
        return reloadedCount
    }

    private data class DayFetchResult(val items: List<FeedItem>, val meta: PromptMeta, val monthRecap: MonthlyRecap? = null)

    private suspend fun fetchDaySafe(day: String, forceReload: Boolean): DayFetchResult {
        val startedAt = System.currentTimeMillis()
        return try {
            val res = repo.feedByDay(day)
            logPerfEvent(
                event = "feed_day_load",
                durationMs = System.currentTimeMillis() - startedAt,
                success = true,
                extra = "day=$day;items=${res.items.size};forced=$forceReload"
            )
            DayFetchResult(
                items = res.items,
                meta = PromptMeta(
                    day = res.day ?: day,
                    triggeredAt = res.triggeredAt,
                    uploadUntil = res.uploadUntil,
                    triggerSource = res.triggerSource,
                    requestedByUser = res.requestedByUser,
                    momentKind = res.momentKind,
                    specialRequestedByUser = res.specialRequestedByUser,
                    specialRequestedByUserColor = res.specialRequestedByUserColor
                ),
                monthRecap = res.monthRecap
            )
        } catch (e: HttpException) {
            val code = e.code()
            if (code == 403) {
                if (forceReload) {
                    staleFeedDays.remove(day)
                }
                logPerfEvent(
                    event = "feed_day_load",
                    durationMs = System.currentTimeMillis() - startedAt,
                    success = false,
                    extra = "day=$day;http=403;forced=$forceReload"
                )
                DayFetchResult(items = emptyList(), meta = PromptMeta(day = day), monthRecap = null)
            } else {
                logPerfEvent(
                    event = "feed_day_load",
                    durationMs = System.currentTimeMillis() - startedAt,
                    success = false,
                    extra = "day=$day;http=$code;forced=$forceReload"
                )
                throw e
            }
        } catch (t: Throwable) {
            logPerfEvent(
                event = "feed_day_load",
                durationMs = System.currentTimeMillis() - startedAt,
                success = false,
                extra = "day=$day;error=${t::class.java.simpleName};forced=$forceReload"
            )
            throw t
        }
    }

    suspend fun uploadDual(
        back: Uri,
        front: Uri,
        asPrompt: Boolean,
        shareLocation: Boolean,
        capsule: CapsuleUploadOptions = CapsuleUploadOptions(),
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        state = state.copy(loading = true)
        logPerfEvent(
            event = "upload_start",
            durationMs = 0,
            success = true,
            extra = "kind=${if (asPrompt) "prompt" else "extra"};capsule=${capsule.enabled}"
        )
        return try {
            repo.uploadDual(back, front, asPrompt, shareLocation, capsule, onProgress)
            state = state.copy(loading = false, message = "Fotos gepostet")
            refreshAll()
            logPerfEvent(
                event = "upload_end",
                durationMs = System.currentTimeMillis() - startedAt,
                success = true,
                extra = "kind=${if (asPrompt) "prompt" else "extra"};capsule=${capsule.enabled}"
            )
            true
        } catch (t: Throwable) {
            state = state.copy(loading = false, message = apiError(t, "Upload fehlgeschlagen"))
            logPerfEvent(
                event = "upload_end",
                durationMs = System.currentTimeMillis() - startedAt,
                success = false,
                extra = "kind=${if (asPrompt) "prompt" else "extra"};capsule=${capsule.enabled};error=${t::class.java.simpleName}"
            )
            false
        }
    }

    suspend fun enqueueDualUpload(
        back: Uri,
        front: Uri,
        asPrompt: Boolean,
        shareLocation: Boolean,
        capsule: CapsuleUploadOptions = CapsuleUploadOptions()
    ): Boolean {
        state = state.copy(loading = true)
        return runCatching {
            repo.enqueueDualUpload(back, front, asPrompt, shareLocation, capsule)
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

    suspend fun createChatPoll(question: String, options: List<String>, allowMultiSelect: Boolean): Boolean {
        val cleanQuestion = question.trim()
        val cleanOptions = options.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        if (cleanQuestion.length < 3 || cleanOptions.size < 2) {
            state = state.copy(message = "Umfrage braucht eine Frage und mindestens 2 Optionen.")
            return false
        }
        state = state.copy(chatSending = true)
        return try {
            runCatching { repo.createChatPoll(cleanQuestion, cleanOptions, allowMultiSelect) }
                .onSuccess {
                    refreshAll()
                    state = state.copy(message = "Umfrage erstellt")
                }
                .onFailure {
                    state = state.copy(message = apiError(it, "Umfrage erstellen fehlgeschlagen"))
                    logApiFailure("chat_poll_create_failed", "/api/chat/polls", it)
                }
                .isSuccess
        } finally {
            state = state.copy(chatSending = false)
        }
    }

    suspend fun voteChatPoll(messageId: Long, optionIds: List<Long>): Boolean {
        if (messageId <= 0L || optionIds.isEmpty()) return false
        return runCatching { repo.voteChatPoll(messageId, optionIds) }
            .map {
                refreshAll(showLoading = false, reason = "chat_poll_vote")
                true
            }
            .getOrElse {
                state = state.copy(message = apiError(it, "Abstimmung fehlgeschlagen"))
                logApiFailure("chat_poll_vote_failed", "/api/chat/polls/:id/vote", it, "pollId=$messageId")
                false
            }
    }

    suspend fun closeChatPoll(messageId: Long): Boolean {
        if (messageId <= 0L) return false
        return runCatching { repo.closeChatPoll(messageId) }
            .map {
                refreshAll(showLoading = false, reason = "chat_poll_close")
                true
            }
            .getOrElse {
                state = state.copy(message = apiError(it, "Umfrage schliessen fehlgeschlagen"))
                logApiFailure("chat_poll_close_failed", "/api/chat/polls/:id/close", it, "pollId=$messageId")
                false
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

    suspend fun tryPhotoFotomojiFromTemplate(photoId: Long, emoji: String): Boolean {
        if (photoId <= 0 || emoji.isBlank()) return false
        state = state.copy(interactionsLoading = true)
        return try {
            val response = repo.reactPhotoFotomojiFromTemplate(photoId, emoji)
            state = state.copy(interactionsLoading = false, photoInteractions = response)
            true
        } catch (t: Throwable) {
            val code = (t as? HttpException)?.code()
            if (code == 404) {
                state = state.copy(interactionsLoading = false)
                false
            } else {
                state = state.copy(interactionsLoading = false, message = apiError(t, "FotoMoji fehlgeschlagen"))
                true
            }
        }
    }

    suspend fun uploadPhotoFotomoji(photoId: Long, emoji: String, uri: Uri, saveTemplate: Boolean): Boolean {
        if (photoId <= 0 || emoji.isBlank()) return false
        state = state.copy(interactionsLoading = true)
        return runCatching { repo.uploadPhotoFotomoji(photoId, emoji, uri, saveTemplate) }
            .map {
                state = state.copy(
                    interactionsLoading = false,
                    photoInteractions = it,
                    message = if (saveTemplate) "FotoMoji + Template gespeichert" else "FotoMoji gesendet"
                )
                true
            }
            .getOrElse {
                state = state.copy(interactionsLoading = false, message = apiError(it, "FotoMoji Upload fehlgeschlagen"))
                false
            }
    }

    suspend fun refreshFotomojiTemplates() {
        if (state.token.isBlank()) return
        state = state.copy(fotomojiTemplatesLoading = true)
        runCatching { repo.listFotomojiTemplates() }
            .onSuccess { items ->
                state = state.copy(
                    fotomojiTemplatesLoading = false,
                    fotomojiTemplates = items.sortedBy { it.emoji }
                )
            }
            .onFailure {
                state = state.copy(
                    fotomojiTemplatesLoading = false,
                    message = apiError(it, "FotoMoji-Templates laden fehlgeschlagen")
                )
            }
    }

    suspend fun upsertFotomojiTemplate(emoji: String, uri: Uri): Boolean {
        if (emoji.isBlank()) return false
        state = state.copy(fotomojiTemplatesLoading = true)
        return runCatching { repo.upsertFotomojiTemplate(emoji, uri) }
            .map { items ->
                state = state.copy(
                    fotomojiTemplatesLoading = false,
                    fotomojiTemplates = items.sortedBy { it.emoji },
                    message = "FotoMoji-Template fuer $emoji gespeichert"
                )
                true
            }
            .getOrElse {
                state = state.copy(
                    fotomojiTemplatesLoading = false,
                    message = apiError(it, "FotoMoji-Template speichern fehlgeschlagen")
                )
                false
            }
    }

    suspend fun deleteFotomojiTemplate(emoji: String): Boolean {
        if (emoji.isBlank()) return false
        state = state.copy(fotomojiTemplatesLoading = true)
        return runCatching {
            repo.deleteFotomojiTemplate(emoji)
            repo.listFotomojiTemplates()
        }
            .map { items ->
                state = state.copy(
                    fotomojiTemplatesLoading = false,
                    fotomojiTemplates = items.sortedBy { it.emoji },
                    message = "FotoMoji-Template fuer $emoji geloescht"
                )
                true
            }
            .getOrElse {
                state = state.copy(
                    fotomojiTemplatesLoading = false,
                    message = apiError(it, "FotoMoji-Template loeschen fehlgeschlagen")
                )
                false
            }
    }

    suspend fun commentPhoto(photoId: Long, body: String) {
        val trimmed = body.trim()
        if (photoId <= 0 || trimmed.isBlank()) return
        val startedAt = System.currentTimeMillis()
        state = state.copy(interactionsLoading = true)
        try {
            val response = repo.commentPhoto(photoId, trimmed)
            val touchedDay = state.feedByDay.entries.firstOrNull { (_, items) ->
                items.any { feedItem -> feedItem.photo.id == photoId }
            }?.key
            if (!touchedDay.isNullOrBlank()) {
                staleFeedDays.add(touchedDay)
            }
            val needsFullReload = response.comments.isEmpty()
            state = state.copy(interactionsLoading = false, photoInteractions = response)
            if (needsFullReload) {
                loadPhotoInteractions(photoId)
            }
            logPerfEvent(
                event = "comment_submit",
                durationMs = System.currentTimeMillis() - startedAt,
                success = true,
                extra = "photoId=$photoId;bodyLen=${trimmed.length}"
            )
        } catch (t: Throwable) {
            state = state.copy(interactionsLoading = false, message = apiError(t, "Kommentar fehlgeschlagen"))
            logPerfEvent(
                event = "comment_submit",
                durationMs = System.currentTimeMillis() - startedAt,
                success = false,
                extra = "photoId=$photoId;error=${t::class.java.simpleName}"
            )
        }
    }

    fun feedAutoRefreshIntervalMs(): Long = feedAutoRefreshBaseMs + Random.nextLong(feedAutoRefreshJitterMs + 1L)

    fun globalRefreshIntervalMs(): Long {
        val now = System.currentTimeMillis()
        val remaining = refreshCircuitOpenRemainingMs(now)
        if (remaining > 0L) {
            return remaining + Random.nextLong(0L, 2_001L)
        }
        if (isNetworkFailureClass(lastRefreshFailureClass) && consecutiveNetworkRefreshFailures > 0) {
            return nextNetworkBackoffDelayMs()
        }
        val activeMoment = state.prompt?.let { isActiveMomentWindow(it) } == true
        val isFeedFocus = state.activeTab == AppTab.FEED
        return if (activeMoment || isFeedFocus) {
            globalRefreshActiveBaseMs + Random.nextLong(globalRefreshActiveJitterMs + 1L)
        } else {
            globalRefreshSuccessBaseMs + Random.nextLong(globalRefreshSuccessJitterMs + 1L)
        }
    }

    fun shouldRunLaunchIntentRefresh(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (nowMs - lastLaunchIntentRefreshAtMs < launchIntentRefreshMinIntervalMs) {
            return false
        }
        lastLaunchIntentRefreshAtMs = nowMs
        return true
    }

    fun shouldPauseFeedAutoRefresh(): Boolean {
        val now = System.currentTimeMillis()
        return refreshCircuitOpenRemainingMs(now) > 0L || isNetworkFailureClass(lastRefreshFailureClass)
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

    suspend fun refreshPublicMigrationInfo() {
        val info = runCatching { repo.migrationInfoPublic() }.getOrNull()
        state = state.copy(migrationInfo = info)
    }

    private suspend fun handleMigrationRequiredState(message: String = "Diese Instanz ist im Migrationsmodus. Bitte Zielserver eintragen und neu anmelden.") {
        migrationSessionTokenSnapshot = repo.token().trim()
        repo.clearToken()
        val info = runCatching { repo.migrationInfoPublic() }.getOrNull()
        profileSetupPromptShownInSession = false
        state = UiState(
            startupDone = true,
            migrationInfo = info,
            migrationCanUseSessionShortcut = migrationSessionTokenSnapshot.isNotBlank(),
            serverConnected = state.serverConnected,
            serverVersion = state.serverVersion,
            pushProvider = state.pushProvider,
            darkMode = state.darkMode,
            oledMode = state.oledMode,
            uploadQuality = state.uploadQuality,
            fotomojiUploadQuality = state.fotomojiUploadQuality,
            autoUpdateEnabled = repo.autoUpdateEnabled(),
            notificationMasterEnabled = repo.notificationMasterEnabled(),
            useFotomojiReactions = repo.useFotomojiReactions(),
            feedPostPushEnabled = repo.feedPostPushEnabled(),
            pollPushEnabled = repo.pollPushLocalEnabled(),
            specialMomentPushEnabled = repo.specialMomentPushLocalEnabled(),
            inviteRegistrationPushEnabled = repo.inviteRegistrationPushLocalEnabled(),
            photoReactionPushEnabled = repo.photoReactionPushLocalEnabled(),
            photoCommentPushEnabled = repo.photoCommentPushLocalEnabled(),
            customNotificationToneEnabled = repo.customNotificationToneEnabled(),
            customNotificationToneUri = repo.customNotificationToneUri(),
            diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled() && repo.diagnosticsConsentGrantedLocal(),
            diagnosticsConsentGranted = repo.diagnosticsConsentGrantedLocal(),
            activeApiBaseUrl = repo.resolvedApiBaseUrl(),
            apiBaseUrlOverride = repo.apiBaseUrlOverrideRaw(),
            allowInsecureHttpOverride = repo.allowInsecureHttpOverride(),
            debugLogs = repo.recentDebugLogs(),
            message = message
        )
    }

    fun setAllowInsecureHttpOverride(enabled: Boolean) {
        repo.setAllowInsecureHttpOverride(enabled)
        state = state.copy(allowInsecureHttpOverride = enabled)
    }

    suspend fun applyServerBaseUrlOverride(rawInput: String) {
        val validation = repo.validateApiBaseUrlInput(rawInput)
        if (validation.errorMessage != null) {
            state = state.copy(message = validation.errorMessage)
            return
        }

        val normalized = validation.normalizedBaseUrl
        state = state.copy(loading = true, applyServerOverrideInFlight = true)

        if (!normalized.isNullOrBlank()) {
            val healthResult = runCatching { repo.probeHealth(normalized) }.getOrElse {
                state = state.copy(
                    loading = false,
                    applyServerOverrideInFlight = false,
                    message = apiError(it, "Zielserver nicht erreichbar")
                )
                return
            }
            if (!healthResult.ok) {
                state = state.copy(
                    loading = false,
                    applyServerOverrideInFlight = false,
                    message = "Zielserver antwortet, aber nicht mit ok=true"
                )
                return
            }
        }

        repo.setApiBaseUrlOverride(normalized.orEmpty())
        repo.logDebug(
            "server_override_applied",
            "API-Server gewechselt",
            "target=${repo.resolvedApiBaseUrl()};custom=${repo.isApiBaseUrlOverrideActive()}"
        )
        migrationSessionTokenSnapshot = ""
        profileSetupPromptShownInSession = false
        repo.clearToken()
        state = UiState(
            startupDone = true,
            serverConnected = false,
            serverVersion = "unbekannt",
            pushProvider = "unknown",
            migrationCanUseSessionShortcut = false,
            darkMode = state.darkMode,
            oledMode = state.oledMode,
            uploadQuality = state.uploadQuality,
            fotomojiUploadQuality = state.fotomojiUploadQuality,
            autoUpdateEnabled = repo.autoUpdateEnabled(),
            notificationMasterEnabled = repo.notificationMasterEnabled(),
            useFotomojiReactions = repo.useFotomojiReactions(),
            feedPostPushEnabled = repo.feedPostPushEnabled(),
            pollPushEnabled = repo.pollPushLocalEnabled(),
            specialMomentPushEnabled = repo.specialMomentPushLocalEnabled(),
            inviteRegistrationPushEnabled = repo.inviteRegistrationPushLocalEnabled(),
            photoReactionPushEnabled = repo.photoReactionPushLocalEnabled(),
            photoCommentPushEnabled = repo.photoCommentPushLocalEnabled(),
            customNotificationToneEnabled = repo.customNotificationToneEnabled(),
            customNotificationToneUri = repo.customNotificationToneUri(),
            diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled() && repo.diagnosticsConsentGrantedLocal(),
            diagnosticsConsentGranted = repo.diagnosticsConsentGrantedLocal(),
            activeApiBaseUrl = repo.resolvedApiBaseUrl(),
            apiBaseUrlOverride = repo.apiBaseUrlOverrideRaw(),
            allowInsecureHttpOverride = repo.allowInsecureHttpOverride(),
            applyServerOverrideInFlight = false,
            debugLogs = repo.recentDebugLogs(),
            message = if (normalized.isNullOrBlank()) {
                "Custom-Server entfernt. Bitte neu anmelden."
            } else {
                "Custom-Server gespeichert. Bitte neu anmelden."
            }
        )
    }

    suspend fun migrateWithSessionShortcut() {
        val tokenSnapshot = migrationSessionTokenSnapshot.trim()
        if (tokenSnapshot.isBlank()) {
            state = state.copy(message = "Kein gueltiger Migrations-Shortcut verfuegbar. Bitte normal anmelden.")
            return
        }
        val targetRaw = state.migrationInfo?.targetBaseUrl?.trim().orEmpty()
        if (targetRaw.isBlank()) {
            state = state.copy(message = "Kein Zielserver konfiguriert. Bitte Server-URL manuell setzen.")
            return
        }
        val validation = repo.validateApiBaseUrlInput(targetRaw)
        if (validation.errorMessage != null || validation.normalizedBaseUrl.isNullOrBlank()) {
            state = state.copy(message = validation.errorMessage ?: "Zielserver-URL ist ungueltig.")
            return
        }
        val normalizedTarget = validation.normalizedBaseUrl.orEmpty()
        state = state.copy(loading = true, applyServerOverrideInFlight = true, message = "")
        runCatching {
            val health = repo.probeHealth(normalizedTarget)
            if (!health.ok) throw IllegalStateException("target_health_not_ok")
            val handoff = repo.requestMigrationHandoff(tokenSnapshot)
            val finalTarget = repo.validateApiBaseUrlInput(handoff.targetBaseUrl).normalizedBaseUrl
                ?.takeIf { it.isNotBlank() }
                ?: normalizedTarget
            val consumeAuth = repo.consumeMigrationHandoff(finalTarget, handoff.handoffToken)
            repo.setApiBaseUrlOverride(finalTarget)
            repo.adoptAuthSession(consumeAuth)
            Pair(consumeAuth.user, finalTarget)
        }.onSuccess { (user, finalTarget) ->
            migrationSessionTokenSnapshot = ""
            profileSetupPromptShownInSession = false
            state = state.copy(
                loading = false,
                applyServerOverrideInFlight = false,
                user = user,
                token = repo.token(),
                migrationInfo = null,
                migrationCanUseSessionShortcut = false,
                activeApiBaseUrl = repo.resolvedApiBaseUrl(),
                apiBaseUrlOverride = repo.apiBaseUrlOverrideRaw(),
                serverConnected = true,
                serverVersion = "verbunden",
                message = "Migration abgeschlossen. Verbunden mit $finalTarget"
            )
            runCatching { repo.syncDeviceTokenIfNeeded(force = true) }
            refreshAll()
        }.onFailure {
            logApiFailure("migration_handoff_failed", "/api/migration/handoff", it, "target=$normalizedTarget")
            state = state.copy(
                loading = false,
                applyServerOverrideInFlight = false,
                message = apiError(it, "Direkte Migration fehlgeschlagen. Bitte URL setzen und anmelden.")
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

    fun setFotomojiUploadQuality(value: Int) {
        repo.setFotomojiUploadQuality(value)
        state = state.copy(fotomojiUploadQuality = repo.fotomojiUploadQuality())
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        repo.setAutoUpdateEnabled(enabled)
        val auto = repo.autoUpdateEnabled()
        val chat = state.user?.chatPushEnabled ?: repo.chatPushLocalEnabled()
        val feed = repo.feedPostPushEnabled()
        val poll = state.user?.pollPushEnabled ?: repo.pollPushLocalEnabled()
        val invite = state.user?.inviteRegistrationPushEnabled ?: repo.inviteRegistrationPushLocalEnabled()
        val reaction = state.user?.photoReactionPushEnabled ?: repo.photoReactionPushLocalEnabled()
        val comment = state.user?.photoCommentPushEnabled ?: repo.photoCommentPushLocalEnabled()
        val master = auto && chat && feed && poll && invite && reaction && comment
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
        val poll = state.user?.pollPushEnabled ?: repo.pollPushLocalEnabled()
        val invite = state.user?.inviteRegistrationPushEnabled ?: repo.inviteRegistrationPushLocalEnabled()
        val reaction = state.user?.photoReactionPushEnabled ?: repo.photoReactionPushLocalEnabled()
        val comment = state.user?.photoCommentPushEnabled ?: repo.photoCommentPushLocalEnabled()
        val master = auto && chat && feed && poll && invite && reaction && comment
        repo.setNotificationMasterEnabled(master)
        state = state.copy(
            feedPostPushEnabled = feed,
            notificationMasterEnabled = master
        )
    }

    fun setUseFotomojiReactions(enabled: Boolean) {
        repo.setUseFotomojiReactions(enabled)
        state = state.copy(useFotomojiReactions = repo.useFotomojiReactions())
    }

    suspend fun setPollPushEnabled(enabled: Boolean) {
        val current = state.user ?: return
        state = state.copy(loading = true)
        runCatching {
            repo.updatePreferences(
                current.chatPushEnabled,
                enabled,
                current.inviteRegistrationPushEnabled,
                current.photoReactionPushEnabled,
                current.photoCommentPushEnabled,
                current.allowPhotoDownload
            )
        }
            .onSuccess { user ->
                repo.setChatPushLocalEnabled(user.chatPushEnabled)
                repo.setPollPushLocalEnabled(user.pollPushEnabled)
                repo.setInviteRegistrationPushLocalEnabled(user.inviteRegistrationPushEnabled)
                repo.setPhotoReactionPushLocalEnabled(user.photoReactionPushEnabled)
                repo.setPhotoCommentPushLocalEnabled(user.photoCommentPushEnabled)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = auto && user.chatPushEnabled && feed && user.pollPushEnabled && user.inviteRegistrationPushEnabled && user.photoReactionPushEnabled && user.photoCommentPushEnabled
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    pollPushEnabled = user.pollPushEnabled,
                    notificationMasterEnabled = master,
                    loading = false,
                    message = "Push bei Umfragen aktualisiert"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Push-Einstellung speichern fehlgeschlagen"))
            }
    }

    suspend fun setSpecialMomentPushEnabled(enabled: Boolean) {
        val current = state.user ?: return
        state = state.copy(loading = true)
        runCatching {
            repo.updatePreferences(
                current.chatPushEnabled,
                current.pollPushEnabled,
                current.inviteRegistrationPushEnabled,
                current.photoReactionPushEnabled,
                current.photoCommentPushEnabled,
                current.allowPhotoDownload,
                specialMomentPushEnabled = enabled
            )
        }
            .onSuccess { user ->
                repo.setSpecialMomentPushLocalEnabled(user.specialMomentPushEnabled)
                state = state.copy(
                    user = user,
                    specialMomentPushEnabled = user.specialMomentPushEnabled,
                    loading = false,
                    message = if (enabled) {
                        "Push bei Sondermomenten aktiviert"
                    } else {
                        "Push bei Sondermomenten deaktiviert"
                    }
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Sondermoment-Push speichern fehlgeschlagen"))
            }
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
        val pollEnabled = state.user?.pollPushEnabled ?: repo.pollPushLocalEnabled()
        val reactionEnabled = state.user?.photoReactionPushEnabled ?: repo.photoReactionPushLocalEnabled()
        val commentEnabled = state.user?.photoCommentPushEnabled ?: repo.photoCommentPushLocalEnabled()
        runCatching { repo.updatePreferences(enabled, pollEnabled, inviteEnabled, reactionEnabled, commentEnabled, allowDownload) }
            .onSuccess { user ->
                repo.setChatPushLocalEnabled(user.chatPushEnabled)
                repo.setPollPushLocalEnabled(user.pollPushEnabled)
                repo.setInviteRegistrationPushLocalEnabled(user.inviteRegistrationPushEnabled)
                repo.setPhotoReactionPushLocalEnabled(user.photoReactionPushEnabled)
                repo.setPhotoCommentPushLocalEnabled(user.photoCommentPushEnabled)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = auto && user.chatPushEnabled && feed && user.pollPushEnabled && user.inviteRegistrationPushEnabled && user.photoReactionPushEnabled && user.photoCommentPushEnabled
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    pollPushEnabled = user.pollPushEnabled,
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
        repo.setPollPushLocalEnabled(enabled)
        repo.setInviteRegistrationPushLocalEnabled(enabled)
        repo.setPhotoReactionPushLocalEnabled(enabled)
        repo.setPhotoCommentPushLocalEnabled(enabled)
        var nextUser = state.user
        if (state.user != null) {
            val allowDownload = state.user?.allowPhotoDownload ?: false
            runCatching { repo.updatePreferences(enabled, enabled, enabled, enabled, enabled, allowDownload) }
                .onSuccess {
                    nextUser = it
                    repo.setChatPushLocalEnabled(it.chatPushEnabled)
                    repo.setPollPushLocalEnabled(it.pollPushEnabled)
                    repo.setInviteRegistrationPushLocalEnabled(it.inviteRegistrationPushEnabled)
                    repo.setPhotoReactionPushLocalEnabled(it.photoReactionPushEnabled)
                    repo.setPhotoCommentPushLocalEnabled(it.photoCommentPushEnabled)
                }
                .onFailure {
                    state = state.copy(message = apiError(it, "Master-Benachrichtigung teilweise fehlgeschlagen"))
                }
        } else {
            repo.setChatPushLocalEnabled(enabled)
            repo.setPollPushLocalEnabled(enabled)
            repo.setInviteRegistrationPushLocalEnabled(enabled)
            repo.setPhotoReactionPushLocalEnabled(enabled)
            repo.setPhotoCommentPushLocalEnabled(enabled)
        }
        val auto = repo.autoUpdateEnabled()
        val feed = repo.feedPostPushEnabled()
        val chat = nextUser?.chatPushEnabled ?: repo.chatPushLocalEnabled()
        val poll = nextUser?.pollPushEnabled ?: repo.pollPushLocalEnabled()
        val invite = nextUser?.inviteRegistrationPushEnabled ?: repo.inviteRegistrationPushLocalEnabled()
        val reaction = nextUser?.photoReactionPushEnabled ?: repo.photoReactionPushLocalEnabled()
        val comment = nextUser?.photoCommentPushEnabled ?: repo.photoCommentPushLocalEnabled()
        val masterEffective = auto && feed && chat && poll && invite && reaction && comment
        repo.setNotificationMasterEnabled(masterEffective)
        state = state.copy(
            user = nextUser,
            autoUpdateEnabled = auto,
            feedPostPushEnabled = feed,
            pollPushEnabled = poll,
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
                current.pollPushEnabled,
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

    suspend fun setLocationFeatureEnabled(enabled: Boolean) {
        val current = state.user ?: return
        state = state.copy(loading = true)
        val nextDefault = if (enabled) current.locationShareDefaultEnabled else false
        runCatching {
            repo.updatePreferences(
                current.chatPushEnabled,
                current.pollPushEnabled,
                current.inviteRegistrationPushEnabled,
                current.photoReactionPushEnabled,
                current.photoCommentPushEnabled,
                current.allowPhotoDownload,
                locationFeatureEnabled = enabled,
                locationShareDefaultEnabled = nextDefault
            )
        }
            .onSuccess { user ->
                state = state.copy(
                    user = user,
                    locationFeatureEnabled = user.locationFeatureEnabled,
                    locationShareDefaultEnabled = user.locationShareDefaultEnabled,
                    loading = false,
                    message = if (enabled) "Standort-Feature aktiviert" else "Standort-Feature deaktiviert"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Standort-Feature speichern fehlgeschlagen"))
            }
    }

    suspend fun setLocationShareDefaultEnabled(enabled: Boolean) {
        val current = state.user ?: return
        state = state.copy(loading = true)
        runCatching {
            repo.updatePreferences(
                current.chatPushEnabled,
                current.pollPushEnabled,
                current.inviteRegistrationPushEnabled,
                current.photoReactionPushEnabled,
                current.photoCommentPushEnabled,
                current.allowPhotoDownload,
                locationFeatureEnabled = current.locationFeatureEnabled,
                locationShareDefaultEnabled = enabled
            )
        }
            .onSuccess { user ->
                state = state.copy(
                    user = user,
                    locationFeatureEnabled = user.locationFeatureEnabled,
                    locationShareDefaultEnabled = user.locationShareDefaultEnabled,
                    loading = false,
                    message = if (enabled) "Standort-Default aktiviert" else "Standort-Default deaktiviert"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Standort-Default speichern fehlgeschlagen"))
            }
    }

    suspend fun setInviteRegistrationPushEnabled(enabled: Boolean) {
        val current = state.user ?: return
        state = state.copy(loading = true)
        runCatching {
            repo.updatePreferences(
                current.chatPushEnabled,
                current.pollPushEnabled,
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
                val master = auto && user.chatPushEnabled && feed && user.pollPushEnabled && user.inviteRegistrationPushEnabled && user.photoReactionPushEnabled && user.photoCommentPushEnabled
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    pollPushEnabled = user.pollPushEnabled,
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
                current.pollPushEnabled,
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
                val master = auto && user.chatPushEnabled && feed && user.pollPushEnabled && user.inviteRegistrationPushEnabled && user.photoReactionPushEnabled && user.photoCommentPushEnabled
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    pollPushEnabled = user.pollPushEnabled,
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
                current.pollPushEnabled,
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
                val master = auto && user.chatPushEnabled && feed && user.pollPushEnabled && user.inviteRegistrationPushEnabled && user.photoReactionPushEnabled && user.photoCommentPushEnabled
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    pollPushEnabled = user.pollPushEnabled,
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
            if (targetDay == prompt.day && !prompt.hasVisiblePostToday) return null
            if (prompt.hasVisiblePostToday && availableDays.contains(prompt.day)) return prompt.day
            return availableDays.firstOrNull()
        }

        when {
            action == "open_chat" || type == "chat" || type == "chat_message" || type == "invite_registered" || type == "invite_registration" -> {
                setTab(AppTab.CHAT)
            }

            action == "open_feed" || type == "feed_post" || type == "post" || type == "extra_post" || type == "photo_reaction" || type == "photo_fotomoji" || type == "photo_comment" || targetDay.isNotBlank() || targetPhotoId != null -> {
                val targetIsTodayHidden = targetDay == prompt.day && !prompt.hasVisiblePostToday && !availableDays.contains(targetDay)
                if (targetIsTodayHidden) {
                    openCamera("Der heutige Feed wird sichtbar, sobald du einen sichtbaren Beitrag gepostet hast.")
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
                    feedFocusPhotoId = targetPhotoId,
                    feedScrollRequestId = issueFeedScrollRequestId()
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

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val requestId = "req_${UUID.randomUUID()}"
                val newReq = chain.request().newBuilder()
                    .header("X-Request-ID", requestId)
                    .build()
                chain.proceed(newReq)
            }
            .build()
        repo = AppRepo(this, httpClient)
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
    var pendingFotomojiCapture by remember { mutableStateOf<PendingFotomojiCapture?>(null) }
    var pendingProfileFotomojiTemplateEmoji by remember { mutableStateOf<String?>(null) }
    var captureAsPrompt by remember { mutableStateOf(true) }
    var captureCapsule by remember { mutableStateOf(CapsuleUploadOptions()) }
    var backPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var frontPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var cameraLocationShareEnabled by remember { mutableStateOf(false) }
    var cameraLocationToggleTouched by remember { mutableStateOf(false) }

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
    var locationPermissionGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        locationPermissionGranted = hasLocationPermission(context)
    }
    val appPermissionSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        locationPermissionGranted = hasLocationPermission(context)
    }

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
                        val shareLocation = cameraLocationShareEnabled && (state.user?.locationFeatureEnabled == true) && locationPermissionGranted
                        scope.launch {
                            val ok = vm.enqueueDualUpload(
                                back,
                                front,
                                asPrompt,
                                shareLocation,
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
                "fotomoji" -> {
                    val pending = pendingFotomojiCapture
                    if (pending != null && shotUri != null) {
                        scope.launch {
                            val ok = vm.uploadPhotoFotomoji(
                                photoId = pending.photoId,
                                emoji = pending.emoji,
                                uri = shotUri,
                                saveTemplate = pending.saveTemplate
                            )
                            if (ok) {
                                pendingFotomojiCapture = null
                            }
                        }
                    }
                }
                "fotomoji_template" -> {
                    val emoji = pendingProfileFotomojiTemplateEmoji
                    if (!emoji.isNullOrBlank() && shotUri != null) {
                        scope.launch {
                            vm.upsertFotomojiTemplate(emoji, shotUri)
                        }
                    }
                    pendingProfileFotomojiTemplateEmoji = null
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

    fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    fun openLocationPermissionSettings() {
        appPermissionSettingsLauncher.launch(appPermissionSettingsIntent(context))
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

    LaunchedEffect(state.user?.locationFeatureEnabled, state.user?.locationShareDefaultEnabled) {
        val featureEnabled = state.user?.locationFeatureEnabled == true
        val defaultEnabled = featureEnabled && state.user?.locationShareDefaultEnabled == true
        if (!cameraLocationToggleTouched || !featureEnabled) {
            cameraLocationShareEnabled = defaultEnabled
        }
        if (!featureEnabled) {
            cameraLocationToggleTouched = false
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
            delay(vm.globalRefreshIntervalMs())
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
        if (!vm.shouldRunLaunchIntentRefresh()) return@LaunchedEffect
        vm.refreshAll()
    }

    LaunchedEffect(state.token, state.startupDone, state.activeTab) {
        if (state.token.isBlank() || !state.startupDone) return@LaunchedEffect
        if (state.activeTab != AppTab.FEED) return@LaunchedEffect
        while (true) {
            delay(vm.feedAutoRefreshIntervalMs())
            if (state.activeTab != AppTab.FEED) break
            if (!state.feedRefreshing && !state.loading && !vm.shouldPauseFeedAutoRefresh()) {
                vm.refreshFeed(reason = "feed_auto")
            }
        }
    }

    LaunchedEffect(state.token, state.startupDone, state.activeTab) {
        if (state.token.isBlank() || !state.startupDone) return@LaunchedEffect
        if (state.activeTab != AppTab.PROFILE) return@LaunchedEffect
        vm.refreshFotomojiTemplates()
    }

    LaunchedEffect(state.token, state.startupDone, state.diagnosticsUploadEnabled) {
        if (state.token.isBlank() || !state.startupDone || !state.diagnosticsUploadEnabled) return@LaunchedEffect
        vm.autoUploadDebugLogsIfEnabled()
    }

    LaunchedEffect(state.token, state.activeApiBaseUrl) {
        if (state.token.isNotBlank()) return@LaunchedEffect
        vm.refreshPublicMigrationInfo()
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
            momentKind = normalizeMomentKind(state.prompt?.momentKind, state.prompt?.triggerSource),
            requestedByUser = state.prompt?.specialRequestedByUser ?: state.prompt?.requestedByUser,
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
                                            val isLocked = item.capsuleLocked
                                            val previewUrl = if (isLocked) {
                                                safeApiString(item.capsulePreviewUrl).ifBlank { item.url }
                                            } else {
                                                item.url
                                            }
                                            val urls = if (isLocked) emptyList() else listOfNotNull(item.url, item.secondUrl)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(92.dp)
                                            ) {
                                                AsyncImage(
                                                    model = previewUrl,
                                                    contentDescription = "Profilbild",
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .then(if (isLocked) Modifier.blur(12.dp) else Modifier)
                                                        .clickable(enabled = !isLocked) {
                                                            profileAvatarPreviewUrl = ""
                                                            vm.closeViewedProfile()
                                                            viewerUrls = urls
                                                            viewerIndex = 0
                                                            viewerPhotoId = item.id
                                                        },
                                                    contentScale = ContentScale.Crop
                                                )
                                                if (isLocked) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(Color(0x66131B2B)),
                                                        contentAlignment = Alignment.BottomCenter
                                                    ) {
                                                        Text(
                                                            "🔒 bis ${formatCapsuleOpenAt(item.capsuleVisibleAt)}",
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .background(Color(0xAA101828))
                                                                .padding(horizontal = 6.dp, vertical = 4.dp),
                                                            textAlign = TextAlign.Center,
                                                            color = Color.White,
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

    state.diagnosticsConsentPrompt?.let { consentPrompt ->
        if (state.showDiagnosticsConsentDialog) {
            AlertDialog(
                onDismissRequest = { vm.dismissDiagnosticsConsentDialogLater() },
                confirmButton = {
                    TextButton(onClick = { vm.setDiagnosticsConsentGranted(true, "version_prompt") }) {
                        Text(consentPrompt.confirmLabel.ifBlank { "Zustimmen" })
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.dismissDiagnosticsConsentDialogLater() }) { Text("Spaeter") }
                        TextButton(onClick = { vm.setDiagnosticsConsentGranted(false, "version_prompt") }) {
                            Text(consentPrompt.declineLabel.ifBlank { "Nicht teilen" })
                        }
                    }
                },
                title = { Text(consentPrompt.title.ifBlank { "Diagnose & Performance teilen?" }) },
                text = {
                    Text(
                        consentPrompt.body.ifBlank {
                            "Wenn du zustimmst, sendet die App technische Ladezeit- und Fehlerdaten zur Analyse. Das hilft bei der Fehlersuche. Du kannst das jederzeit im Profil widerrufen."
                        }
                    )
                }
            )
        }
    }

    if (viewerUrls.isNotEmpty()) {
        val viewerLocationMapsUrl = viewerPhotoId?.let { photoId ->
            state.feedByDay.values.asSequence()
                .flatMap { it.asSequence() }
                .map { it.photo }
                .firstOrNull { it.id == photoId }
                ?.locationMapsUrl
                ?: state.photos.firstOrNull { it.id == photoId }?.locationMapsUrl
        }
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
            locationMapsUrl = viewerLocationMapsUrl,
            comment = viewerComment,
            interactions = state.photoInteractions,
            interactionsLoading = state.interactionsLoading,
            ownDownloadFallback = viewerOwnDownloadFallback,
            useFotomojiReactions = state.useFotomojiReactions,
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
            onFotoMojiTap = { emoji ->
                val pid = viewerPhotoId ?: return@FullscreenPhotoViewer
                scope.launch {
                    if (emoji == viewerFotomojiLiveEmoji) {
                        pendingFotomojiCapture = PendingFotomojiCapture(photoId = pid, emoji = emoji, saveTemplate = false)
                        openCameraFor("fotomoji")
                        return@launch
                    }
                    val usedTemplate = vm.tryPhotoFotomojiFromTemplate(pid, emoji)
                    if (!usedTemplate) {
                        pendingFotomojiCapture = PendingFotomojiCapture(photoId = pid, emoji = emoji, saveTemplate = true)
                        openCameraFor("fotomoji")
                    }
                }
            },
            onFotoMojiLongPress = { emoji ->
                val pid = viewerPhotoId ?: return@FullscreenPhotoViewer
                val saveTemplate = emoji != viewerFotomojiLiveEmoji
                pendingFotomojiCapture = PendingFotomojiCapture(photoId = pid, emoji = emoji, saveTemplate = saveTemplate)
                openCameraFor("fotomoji")
            },
            onDoubleTapReact = {
                val pid = viewerPhotoId ?: return@FullscreenPhotoViewer
                val emoji = viewerReactionEmojis[Random.nextInt(viewerReactionEmojis.size)]
                scope.launch {
                    if (state.useFotomojiReactions) {
                        val usedTemplate = vm.tryPhotoFotomojiFromTemplate(pid, emoji)
                        if (!usedTemplate) {
                            pendingFotomojiCapture = PendingFotomojiCapture(photoId = pid, emoji = emoji, saveTemplate = true)
                            openCameraFor("fotomoji")
                        }
                    } else {
                        vm.reactPhoto(pid, emoji)
                    }
                }
            },
            onDownloadCurrent = { photoUrl ->
                vm.downloadPhotoFromViewer(photoUrl)
            },
            onOpenLocation = { url ->
                openExternalUrl(context, url)
            },
            onIndexChange = { viewerIndex = it },
            onClose = closeViewer
        )
    }

    if (state.token.isBlank()) {
        var loginPasswordVisible by rememberSaveable { mutableStateOf(false) }
        var registerPasswordVisible by rememberSaveable { mutableStateOf(false) }
        var showServerEditor by rememberSaveable { mutableStateOf(false) }
        var serverOverrideInput by rememberSaveable(state.apiBaseUrlOverride) { mutableStateOf(state.apiBaseUrlOverride) }
        val localContext = LocalContext.current
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

            state.migrationInfo?.takeIf { it.enabled }?.let { migration ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(migration.screenTitle?.ifBlank { "Daily ist umgezogen" } ?: "Daily ist umgezogen", fontWeight = FontWeight.SemiBold)
                        Text(migration.screenBody?.ifBlank { "Bitte App aktualisieren und neuen Server eintragen." } ?: "Bitte App aktualisieren und neuen Server eintragen.")
                        if (!migration.targetBaseUrl.isNullOrBlank()) {
                            Text("Neuer Server: ${migration.targetBaseUrl}", color = MaterialTheme.colorScheme.primary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!migration.downloadUrl.isNullOrBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            localContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(migration.downloadUrl)))
                                        }
                                    }
                                ) { Text("Update öffnen") }
                            }
                            if (state.migrationCanUseSessionShortcut) {
                                OutlinedButton(
                                    onClick = { scope.launch { vm.migrateWithSessionShortcut() } },
                                    enabled = !state.loading && !state.applyServerOverrideInFlight
                                ) { Text("Direkt migrieren") }
                            }
                            Button(
                                onClick = {
                                    if (!migration.targetBaseUrl.isNullOrBlank()) {
                                        serverOverrideInput = migration.targetBaseUrl
                                    }
                                    showServerEditor = true
                                }
                            ) { Text("Server setzen") }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Server", fontWeight = FontWeight.SemiBold)
                    Text("Aktiv: ${state.activeApiBaseUrl}")
                    TextButton(onClick = { showServerEditor = !showServerEditor }) {
                        Text(if (showServerEditor) "Server-Eingabe ausblenden" else "Server-URL eingeben")
                    }
                    if (showServerEditor) {
                        OutlinedTextField(
                            value = serverOverrideInput,
                            onValueChange = { serverOverrideInput = it },
                            label = { Text("Server-URL (leer = Standard)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { serverOverrideInput = "" },
                                modifier = Modifier.weight(1f)
                            ) { Text("Standard") }
                            Button(
                                onClick = { scope.launch { vm.applyServerBaseUrlOverride(serverOverrideInput) } },
                                modifier = Modifier.weight(1f),
                                enabled = !state.applyServerOverrideInFlight
                            ) {
                                Text(if (state.applyServerOverrideInFlight) "Prüfe..." else "Speichern")
                            }
                        }
                    }
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
                    currentUsername = state.user?.username,
                    networkUnstable = vm.shouldPauseFeedAutoRefresh(),
                    promptRules = state.promptRules,
                    locationFeatureEnabled = state.user?.locationFeatureEnabled == true,
                    locationPermissionGranted = locationPermissionGranted,
                    locationShareEnabled = cameraLocationShareEnabled && (state.user?.locationFeatureEnabled == true) && locationPermissionGranted,
                    updateAvailable = state.updateAvailable,
                    updateCheckInFlight = state.updateCheckInFlight,
                    specialMomentStatus = state.specialMomentStatus,
                    backPreviewUri = backPreviewUri,
                    frontPreviewUri = frontPreviewUri,
                    onDownloadUpdate = { vm.downloadLatestUpdateFromBadge() },
                    onLocationShareEnabledChange = {
                        cameraLocationToggleTouched = true
                        cameraLocationShareEnabled = it
                    },
                    onRequestLocationPermission = ::requestLocationPermission,
                    onOpenLocationPermissionSettings = ::openLocationPermissionSettings,
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
                            val shareLocation = cameraLocationShareEnabled && (state.user?.locationFeatureEnabled == true) && locationPermissionGranted
                            scope.launch {
                                val ok = vm.enqueueDualUpload(
                                    back,
                                    front,
                                    asPrompt,
                                    shareLocation,
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
                    scrollRequestId = state.feedScrollRequestId,
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
                    onLoadMoreStats = { scope.launch { vm.loadMoreCalendarStats() } },
                    onSelect = { day ->
                        scope.launch { vm.jumpToDay(day) }
                    }
                )

                AppTab.CHAT -> ChatTab(
                    items = state.chat,
                    meId = state.user?.id,
                    isAdmin = state.user?.isAdmin == true,
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
                    },
                    onCreatePoll = { question, options, allowMulti ->
                        scope.launch { vm.createChatPoll(question, options, allowMulti) }
                    },
                    onVotePoll = { messageId, optionIds ->
                        scope.launch { vm.voteChatPoll(messageId, optionIds) }
                    },
                    onClosePoll = { messageId ->
                        scope.launch { vm.closeChatPoll(messageId) }
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
                    apiBaseUrl = state.activeApiBaseUrl,
                    apiBaseUrlOverride = state.apiBaseUrlOverride,
                    allowInsecureHttpOverride = state.allowInsecureHttpOverride,
                    applyServerOverrideInFlight = state.applyServerOverrideInFlight,
                    serverConnected = state.serverConnected,
                    lastPingMs = state.lastPingMs,
                    uploadQuality = state.uploadQuality,
                    fotomojiUploadQuality = state.fotomojiUploadQuality,
                    autoUpdateEnabled = state.autoUpdateEnabled,
                    notificationMasterEnabled = state.notificationMasterEnabled,
                    useFotomojiReactions = state.useFotomojiReactions,
                    chatPushEnabled = state.user?.chatPushEnabled ?: false,
                    pollPushEnabled = state.user?.pollPushEnabled ?: state.pollPushEnabled,
                    specialMomentPushEnabled = state.user?.specialMomentPushEnabled ?: state.specialMomentPushEnabled,
                    inviteRegistrationPushEnabled = state.user?.inviteRegistrationPushEnabled ?: state.inviteRegistrationPushEnabled,
                    photoReactionPushEnabled = state.user?.photoReactionPushEnabled ?: state.photoReactionPushEnabled,
                    photoCommentPushEnabled = state.user?.photoCommentPushEnabled ?: state.photoCommentPushEnabled,
                    allowPhotoDownload = state.user?.allowPhotoDownload ?: false,
                    locationFeatureEnabled = state.user?.locationFeatureEnabled ?: false,
                    locationShareDefaultEnabled = state.user?.locationShareDefaultEnabled ?: false,
                    locationPermissionGranted = locationPermissionGranted,
                    feedPostPushEnabled = state.feedPostPushEnabled,
                    customNotificationToneEnabled = state.customNotificationToneEnabled,
                    customNotificationToneUri = state.customNotificationToneUri,
                    diagnosticsUploadEnabled = state.diagnosticsUploadEnabled,
                    diagnosticsConsentGranted = state.diagnosticsConsentGranted,
                    debugLogs = state.debugLogs,
                    fotomojiTemplates = state.fotomojiTemplates,
                    fotomojiTemplatesLoading = state.fotomojiTemplatesLoading,
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
                    onFotomojiUploadQualityChange = { vm.setFotomojiUploadQuality(it) },
                    onAutoUpdateEnabledChange = { vm.setAutoUpdateEnabled(it) },
                    onChatPushEnabledChange = { scope.launch { vm.setChatPushEnabled(it) } },
                    onPollPushEnabledChange = { scope.launch { vm.setPollPushEnabled(it) } },
                    onSpecialMomentPushEnabledChange = { scope.launch { vm.setSpecialMomentPushEnabled(it) } },
                    onInviteRegistrationPushEnabledChange = { scope.launch { vm.setInviteRegistrationPushEnabled(it) } },
                    onPhotoReactionPushEnabledChange = { scope.launch { vm.setPhotoReactionPushEnabled(it) } },
                    onPhotoCommentPushEnabledChange = { scope.launch { vm.setPhotoCommentPushEnabled(it) } },
                    onAllowPhotoDownloadChange = { scope.launch { vm.setAllowPhotoDownloadEnabled(it) } },
                    onLocationFeatureEnabledChange = { scope.launch { vm.setLocationFeatureEnabled(it) } },
                    onLocationShareDefaultEnabledChange = { scope.launch { vm.setLocationShareDefaultEnabled(it) } },
                    onRequestLocationPermission = ::requestLocationPermission,
                    onOpenLocationPermissionSettings = ::openLocationPermissionSettings,
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
                    onDiagnosticsConsentChange = { vm.setDiagnosticsConsentGranted(it, "profile_toggle") },
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
                    onRefreshFotomojiTemplates = { scope.launch { vm.refreshFotomojiTemplates() } },
                    onCaptureFotomojiTemplate = { emoji ->
                        pendingProfileFotomojiTemplateEmoji = emoji
                        openCameraFor("fotomoji_template")
                    },
                    onDeleteFotomojiTemplate = { emoji ->
                        scope.launch { vm.deleteFotomojiTemplate(emoji) }
                    },
                    onUseFotomojiReactionsChange = { vm.setUseFotomojiReactions(it) },
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
                    onAllowInsecureHttpOverrideChange = { vm.setAllowInsecureHttpOverride(it) },
                    onApplyServerBaseUrlOverride = { input -> scope.launch { vm.applyServerBaseUrlOverride(input) } },
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
    currentUsername: String?,
    networkUnstable: Boolean,
    promptRules: PromptRulesResponse?,
    locationFeatureEnabled: Boolean,
    locationPermissionGranted: Boolean,
    locationShareEnabled: Boolean,
    updateAvailable: Boolean,
    updateCheckInFlight: Boolean,
    specialMomentStatus: SpecialMomentStatus?,
    backPreviewUri: Uri?,
    frontPreviewUri: Uri?,
    onDownloadUpdate: () -> Unit,
    onLocationShareEnabledChange: (Boolean) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenLocationPermissionSettings: () -> Unit,
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
    val hasVisiblePosted = prompt?.hasVisiblePostToday == true
    val canUpload = prompt?.canUpload == true
    val canSpecial = specialMomentStatus?.canRequest == true
    val activeMomentKind = normalizeMomentKind(prompt?.momentKind, prompt?.triggerSource)
    val activeSpecialRequester = prompt?.requestedByUser?.takeIf { !it.isNullOrBlank() } ?: prompt?.specialRequestedByUser
    val specialRequesterKey = (prompt?.specialRequestedByUser ?: prompt?.requestedByUser).orEmpty().trim().lowercase()
    val currentUserKey = currentUsername.orEmpty().trim().lowercase()
    val specialTriggeredByOtherUserToday = !prompt?.specialTriggeredAt.isNullOrBlank() &&
        specialRequesterKey.isNotBlank() &&
        currentUserKey.isNotBlank() &&
        specialRequesterKey != currentUserKey
    val showSpecialMomentButton = !specialTriggeredByOtherUserToday
    val activeMomentLabel = when (activeMomentKind) {
        "special" -> if (!activeSpecialRequester.isNullOrBlank()) "Sondermoment von $activeSpecialRequester gerade aktiv." else "Sondermoment gerade aktiv."
        else -> "Daily-Moment gerade aktiv."
    }
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
        if (networkUnstable) {
            Text(
                "Verbindung gerade instabil. Uploads gehen trotzdem in die Queue und werden automatisch erneut versucht.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
        if (!prompt?.specialTriggeredAt.isNullOrBlank()) {
            val requester = prompt?.specialRequestedByUser
            if (!requester.isNullOrBlank()) {
                Text("Sondermoment heute um ${formatMomentTime(prompt?.specialTriggeredAt)} von $requester.")
            } else {
                Text("Sondermoment heute um ${formatMomentTime(prompt?.specialTriggeredAt)}.")
            }
        } else {
            Text("Sondermoment heute noch nicht ausgeloest.")
        }
        if (!prompt?.dailyTriggeredAt.isNullOrBlank()) {
            Text("Daily-Moment heute war um ${formatMomentTime(prompt?.dailyTriggeredAt)}.")
        } else if (prompt?.dailyPending == false) {
            Text("Daily-Moment heute war bereits.")
        } else {
            Text("Daily-Moment heute steht noch aus.")
        }
        if (promptRules != null) {
            Text("Zeitfenster heute: ${promptRules.promptWindowStartHour}:00-${promptRules.promptWindowEndHour}:00")
        }
        if (locationFeatureEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (!locationPermissionGranted) Color(0xFFFFE1E1) else if (locationShareEnabled) Color(0xFFFFD7D7) else Color(0xFFDFF5E3)
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Standort fuer neuen Post", fontWeight = FontWeight.Bold)
                            Text(
                                if (!locationPermissionGranted) {
                                    "Die App hat noch keine Standortberechtigung. Dieser Post wird trotz Feature-Schalter ohne Standort gesendet."
                                } else if (locationShareEnabled) {
                                    "Rot: Standort wird mit neuem Post geteilt."
                                } else {
                                    "Gruen: Kein Standort wird geteilt."
                                }
                            )
                        }
                        Switch(
                            checked = locationPermissionGranted && locationShareEnabled,
                            onCheckedChange = onLocationShareEnabledChange,
                            enabled = locationPermissionGranted,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFD32F2F),
                                uncheckedTrackColor = Color(0xFF2E7D32)
                            )
                        )
                    }
                    if (!locationPermissionGranted) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRequestLocationPermission) { Text("Berechtigung erlauben") }
                            OutlinedButton(onClick = onOpenLocationPermissionSettings) { Text("App-Einstellungen") }
                        }
                    }
                }
            }
        }

        if (hasVisiblePosted) {
            if (canUpload) {
                Text(activeMomentLabel, fontWeight = FontWeight.Bold)
                Text(
                    if (hasPromptPosted) "Du hast dein Daily-Moment heute schon gepostet."
                    else "Du kannst jetzt noch dein Foto fuer den aktiven Moment aufnehmen.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("Du hast heute schon einen sichtbaren Beitrag gepostet.", fontWeight = FontWeight.Bold)
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
            } else {
                Button(
                    onClick = { onCaptureExtra(CapsuleUploadOptions()) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Weiteres Extra posten") }
            }
            if (!canUpload) {
                TextButton(
                    onClick = { showCapsuleDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Timecapsule aufnehmen")
                }
                if (showSpecialMomentButton) {
                    SpecialMomentActionButton(
                        text = specialLabel,
                        onClick = onRequestSpecialMoment,
                        enabled = canSpecial,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
                Text(activeMomentLabel, fontWeight = FontWeight.Bold)
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
                } else {
                    Button(
                        onClick = { onCaptureExtra(CapsuleUploadOptions()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Extra posten") }
                }
                if (!canUpload) {
                    if (showSpecialMomentButton) {
                        SpecialMomentActionButton(
                            text = specialLabel,
                            onClick = onRequestSpecialMoment,
                            enabled = canSpecial,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
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
                            if (item.isPrompt && !canUpload) {
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
        title = { Text("Timecapsule aufnehmen") },
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
    scrollRequestId: Long,
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
    val context = LocalContext.current
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

    var handledScrollRequestId by remember { mutableLongStateOf(0L) }
    LaunchedEffect(scrollRequestId, rows.size) {
        if (scrollRequestId <= 0L || scrollRequestId == handledScrollRequestId) return@LaunchedEffect
        val idx = if (focusPhotoId != null) {
            rows.indexOfFirst { it is FeedRow.PhotoItem && it.item.photo.id == focusPhotoId }
        } else {
            val target = focusDay ?: return@LaunchedEffect
            rows.indexOfFirst { it is FeedRow.DayHeader && it.day == target }
        }
        if (idx < 0) return@LaunchedEffect
        listState.scrollToItem(idx)
        handledScrollRequestId = scrollRequestId
        if (focusPhotoId != null) onFocusPhotoConsumed()
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
            if (todayLocked && prompt?.hasVisiblePostToday == false) {
                item("today-locked") {
                    Card {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Heutiger Feed ist gesperrt, bis du einen sichtbaren Beitrag gepostet hast.")
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
                    val isMomentWindowPost = isWithinDailyMomentWindow(
                        item.photo.createdAt,
                        meta?.triggeredAt,
                        meta?.uploadUntil
                    )
                    val postMomentKind = normalizeMomentKind(item.momentKind ?: meta?.momentKind, item.triggerSource ?: meta?.triggerSource)
                    val requestedByUser = item.requestedByUser ?: meta?.specialRequestedByUser ?: meta?.requestedByUser
                    val requestedByUserColor = item.specialRequestedByUserColor ?: meta?.specialRequestedByUserColor
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
                            if (!isMomentWindowPost) {
                                Text(
                                    "🕒 ${formatMomentTime(item.photo.createdAt)}",
                                    color = secondaryTextColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (item.photo.locationShared && !item.photo.locationMapsUrl.isNullOrBlank()) {
                                    Text(
                                        "📍 Standort",
                                        color = Color(0xFFD32F2F),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { openExternalUrl(context, item.photo.locationMapsUrl) }
                                    )
                                }
                            } else {
                                if (postMomentKind == "special") {
                                    SpecialMomentBadge(requestedByUser, requestedByUserColor)
                                } else {
                                    DailyMomentBadge()
                                }
                                if (item.photo.locationShared && !item.photo.locationMapsUrl.isNullOrBlank()) {
                                    Text(
                                        "📍 Standort",
                                        color = Color(0xFFD32F2F),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { openExternalUrl(context, item.photo.locationMapsUrl) }
                                    )
                                }
                            }
                            if (item.capsuleLocked) {
                                Text(
                                    "🧊 Oeffnet wieder am ${formatCapsuleOpenAt(item.photo.capsuleVisibleAt)}",
                                    color = secondaryTextColor
                                )
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
                            val photoMojis = item.photoMojis.orEmpty().sortedWith(
                                compareBy<PhotoMojiItem>(
                                    { parseOffsetOrLocalDateTime(it.createdAt) ?: LocalDateTime.MIN },
                                    { it.id }
                                )
                            )
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
                            if (photoMojis.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    items(photoMojis) { foto ->
                                        Row(
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large)
                                                .clickable { onOpenViewer(listOf(foto.url), null) }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            AsyncImage(
                                                model = foto.url,
                                                contentDescription = "FotoMoji",
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .background(Color.LightGray, CircleShape),
                                                contentScale = ContentScale.Crop
                                            )
                                            Text(
                                                foto.emoji,
                                                fontWeight = FontWeight.Bold,
                                                color = parseUserColor(foto.user.favoriteColor)
                                            )
                                        }
                                    }
                                }
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
    onLoadMoreStats: () -> Unit,
    onSelect: (String) -> Unit
) {
    if (days.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Keine Tage mit Bildern vorhanden")
        }
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, days.size) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { lastVisible ->
            if (lastVisible >= days.lastIndex - 5) onLoadMoreStats()
        }
    }
    LazyColumn(
        state = listState,
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
                    momentReasonLine(meta?.momentKind, meta?.triggerSource, meta?.requestedByUser)?.let { reason ->
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
    isAdmin: Boolean,
    chatDeleteSupported: Boolean,
    input: String,
    sending: Boolean,
    onInput: (String) -> Unit,
    onOpenUserProfile: (Long) -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onSend: () -> Unit,
    onCreatePoll: (String, List<String>, Boolean) -> Unit,
    onVotePoll: (Long, List<Long>) -> Unit,
    onClosePoll: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    var deleteCandidate by remember { mutableStateOf<ChatItem?>(null) }
    var showPollDialog by remember { mutableStateOf(false) }
    var pollQuestion by remember { mutableStateOf("") }
    var pollOptionsText by remember { mutableStateOf("") }
    var pollAllowMulti by remember { mutableStateOf(false) }
    val pendingMultiVotes = remember { mutableStateMapOf<Long, Set<Long>>() }
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
                                if (item.type == "poll" && item.poll != null) {
                                    val poll = item.poll
                                    Text(
                                        poll.question.ifBlank { item.body },
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val pollOptions = poll.options.orEmpty()
                                    val selectedSet = pendingMultiVotes[item.id]
                                        ?: poll.mySelectedOptionIds.orEmpty().map { it.toLong() }.toSet()
                                    pollOptions.forEach { option ->
                                        val optionSelected = option.id in selectedSet
                                        val optionLabel = if (poll.isClosed) {
                                            "${option.text} (${option.votes})"
                                        } else {
                                            option.text
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                if (poll.isClosed) return@OutlinedButton
                                                if (poll.allowMultiSelect) {
                                                    val next = selectedSet.toMutableSet()
                                                    if (option.id in next) next.remove(option.id) else next.add(option.id)
                                                    pendingMultiVotes[item.id] = next
                                                } else {
                                                    onVotePoll(item.id, listOf(option.id))
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !poll.isClosed
                                        ) {
                                            Text(
                                                if (optionSelected) "✓ $optionLabel" else optionLabel,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                    if (poll.allowMultiSelect && !poll.isClosed) {
                                        val submitEnabled = selectedSet.isNotEmpty()
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { onVotePoll(item.id, selectedSet.toList()) },
                                                enabled = submitEnabled,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Abstimmen")
                                            }
                                            if ((poll.canClose || (meId != null && item.user.id == meId) || isAdmin) && !poll.isClosed) {
                                                OutlinedButton(
                                                    onClick = { onClosePoll(item.id) },
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("Umfrage schliessen")
                                                }
                                            }
                                        }
                                    } else if ((poll.canClose || (meId != null && item.user.id == meId) || isAdmin) && !poll.isClosed) {
                                        OutlinedButton(
                                            onClick = { onClosePoll(item.id) },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Umfrage schliessen")
                                        }
                                    }
                                    Text(
                                        "${poll.totalVoters} Teilnehmende${if (poll.isClosed) " · geschlossen" else ""}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(item.body)
                                }
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
        if (showPollDialog) {
            AlertDialog(
                onDismissRequest = { showPollDialog = false },
                title = { Text("Umfrage erstellen") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = pollQuestion,
                            onValueChange = { pollQuestion = it.take(280) },
                            label = { Text("Frage") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pollOptionsText,
                            onValueChange = { pollOptionsText = it },
                            label = { Text("Optionen (je Zeile eine)") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = pollAllowMulti, onCheckedChange = { pollAllowMulti = it })
                            Text("Mehrfachantwort erlauben")
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val options = pollOptionsText
                                .split('\n')
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                            onCreatePoll(pollQuestion.trim(), options, pollAllowMulti)
                            showPollDialog = false
                            pollQuestion = ""
                            pollOptionsText = ""
                            pollAllowMulti = false
                        },
                        enabled = pollQuestion.trim().length >= 3 && pollOptionsText.lines().map { it.trim() }.count { it.isNotBlank() } >= 2
                    ) {
                        Text("Posten")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPollDialog = false }) {
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
            OutlinedButton(
                onClick = { showPollDialog = true },
                enabled = !sending,
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Text("Umfrage")
            }
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
    apiBaseUrlOverride: String,
    allowInsecureHttpOverride: Boolean,
    applyServerOverrideInFlight: Boolean,
    serverConnected: Boolean,
    lastPingMs: Long?,
    uploadQuality: Int,
    fotomojiUploadQuality: Int,
    autoUpdateEnabled: Boolean,
    notificationMasterEnabled: Boolean,
    useFotomojiReactions: Boolean,
    chatPushEnabled: Boolean,
    pollPushEnabled: Boolean,
    specialMomentPushEnabled: Boolean,
    inviteRegistrationPushEnabled: Boolean,
    photoReactionPushEnabled: Boolean,
    photoCommentPushEnabled: Boolean,
    allowPhotoDownload: Boolean,
    locationFeatureEnabled: Boolean,
    locationShareDefaultEnabled: Boolean,
    locationPermissionGranted: Boolean,
    feedPostPushEnabled: Boolean,
    customNotificationToneEnabled: Boolean,
    customNotificationToneUri: String,
    diagnosticsUploadEnabled: Boolean,
    diagnosticsConsentGranted: Boolean,
    debugLogs: List<DebugLogEntry>,
    fotomojiTemplates: List<FotomojiTemplateItem>,
    fotomojiTemplatesLoading: Boolean,
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
    onFotomojiUploadQualityChange: (Int) -> Unit,
    onAutoUpdateEnabledChange: (Boolean) -> Unit,
    onChatPushEnabledChange: (Boolean) -> Unit,
    onPollPushEnabledChange: (Boolean) -> Unit,
    onSpecialMomentPushEnabledChange: (Boolean) -> Unit,
    onInviteRegistrationPushEnabledChange: (Boolean) -> Unit,
    onPhotoReactionPushEnabledChange: (Boolean) -> Unit,
    onPhotoCommentPushEnabledChange: (Boolean) -> Unit,
    onAllowPhotoDownloadChange: (Boolean) -> Unit,
    onLocationFeatureEnabledChange: (Boolean) -> Unit,
    onLocationShareDefaultEnabledChange: (Boolean) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenLocationPermissionSettings: () -> Unit,
    onNotificationMasterEnabledChange: (Boolean) -> Unit,
    onFeedPostPushEnabledChange: (Boolean) -> Unit,
    onCustomNotificationToneEnabledChange: (Boolean) -> Unit,
    onPickCustomNotificationTone: () -> Unit,
    onClearCustomNotificationTone: () -> Unit,
    onTestCustomNotificationTone: () -> Unit,
    onDiagnosticsUploadEnabledChange: (Boolean) -> Unit,
    onDiagnosticsConsentChange: (Boolean) -> Unit,
    onRefreshDebugLogs: () -> Unit,
    onShareDebugLogs: () -> Unit,
    onRefreshFotomojiTemplates: () -> Unit,
    onCaptureFotomojiTemplate: (String) -> Unit,
    onDeleteFotomojiTemplate: (String) -> Unit,
    onUseFotomojiReactionsChange: (Boolean) -> Unit,
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
    onAllowInsecureHttpOverrideChange: (Boolean) -> Unit,
    onApplyServerBaseUrlOverride: (String) -> Unit,
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
    var showLocationEnableWarning by remember { mutableStateOf(false) }
    var showLocationDisableWarning by remember { mutableStateOf(false) }
    var updatePulseTick by remember { mutableStateOf(0) }
    var updateChecked by remember { mutableStateOf(false) }
    var serverOverrideInput by remember(apiBaseUrlOverride) { mutableStateOf(apiBaseUrlOverride) }
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
    var locationFeatureEnabledValue by remember(locationFeatureEnabled) { mutableStateOf(locationFeatureEnabled) }
    var locationShareDefaultEnabledValue by remember(locationShareDefaultEnabled) { mutableStateOf(locationShareDefaultEnabled) }
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
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (locationFeatureEnabledValue) Color(0xFFFFE1E1) else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Standorteinstellungen", fontWeight = FontWeight.Bold)
                                    Text(
                                        if (locationFeatureEnabledValue) {
                                            "Achtung: Neue Posts koennen ab jetzt deinen exakten Standort fuer andere Nutzer sichtbar machen. Im Kamera-Tab entscheidet der Schnellschalter pro Post."
                                        } else {
                                            "Komplett opt-in: Erst wenn du dieses Feature aktivierst und dein Telefon bereits Standortdaten hat, kann ein Post deinen Standort teilen."
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    SettingsToggleRow(
                                        label = "Standort-Feature aktivieren",
                                        checked = locationFeatureEnabledValue,
                                        onCheckedChange = { checked ->
                                            if (checked != locationFeatureEnabledValue) {
                                                if (checked) {
                                                    showLocationEnableWarning = true
                                                } else {
                                                    showLocationDisableWarning = true
                                                }
                                            }
                                        },
                                        supportingText = if (locationFeatureEnabledValue) {
                                            "Aktiv: Im Kamera-Tab ist pro Post ein roter/gruener Standort-Schalter sichtbar."
                                        } else {
                                            "Inaktiv: Es wird nichts gefragt und beim Posten wird nie Standort mitgesendet."
                                        }
                                    )
                                    SettingsToggleRow(
                                        label = "Standort beim Posten standardmaessig mitsenden",
                                        checked = locationShareDefaultEnabledValue,
                                        onCheckedChange = {
                                            locationShareDefaultEnabledValue = it
                                            onLocationShareDefaultEnabledChange(it)
                                        },
                                        enabled = locationFeatureEnabledValue,
                                        supportingText = "Der Kamera-Schalter startet bei jedem App-Start wieder mit diesem Standard."
                                    )
                                    if (locationFeatureEnabledValue && !locationPermissionGranted) {
                                        Card(
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0F0))
                                        ) {
                                            Column(
                                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text("App-Standortberechtigung fehlt", fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F))
                                                Text(
                                                    "Im Moment bleibt der Kamera-Schalter zwar sichtbar, neue Posts werden aber ohne Standort hochgeladen, bis du der App die Standortberechtigung gibst.",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Button(onClick = onRequestLocationPermission) { Text("Berechtigung erlauben") }
                                                    OutlinedButton(onClick = onOpenLocationPermissionSettings) { Text("App-Einstellungen") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
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
            CollapsibleSection(
                title = "FotoMojis",
                subtitle = "Vorlagen fuer deine Reaktionsfotos verwalten",
                expanded = sectionExpanded("fotomojis"),
                onExpandedChange = { onProfileSectionExpandedChange("fotomojis", it) }
            ) {
                Text("Setze oder ersetze hier deine FotoMoji-Vorlagen. Tippen im Viewer nutzt dann direkt die passende Vorlage.")
                SettingsToggleRow(
                    label = "FotoMoji statt Emoji-Reaktion verwenden",
                    checked = useFotomojiReactions,
                    onCheckedChange = onUseFotomojiReactionsChange,
                    supportingText = "Aktiv: die Emoji-Leiste erstellt FotoMojis. Inaktiv: normale Emoji-Reaktionen."
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRefreshFotomojiTemplates,
                        enabled = !fotomojiTemplatesLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (fotomojiTemplatesLoading) "Lade..." else "Vorlagen aktualisieren")
                    }
                    Text("${fotomojiTemplates.size} aktiv")
                }

                val templateByEmoji = fotomojiTemplates.associateBy { it.emoji }
                val editableFotomojis = viewerReactionEmojis
                editableFotomojis.chunked(4).forEach { rowEmojis ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        rowEmojis.forEach { emoji ->
                            val template = templateByEmoji[emoji]
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(emoji, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    if (template != null) {
                                        AsyncImage(
                                            model = template.url,
                                            contentDescription = "FotoMoji Vorlage",
                                            modifier = Modifier
                                                .size(64.dp)
                                                .background(Color.LightGray, CircleShape),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(64.dp)
                                                .background(MaterialTheme.colorScheme.surface, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("--")
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            onCaptureFotomojiTemplate(emoji)
                                        },
                                        enabled = !fotomojiTemplatesLoading,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(if (template == null) "Aufnehmen" else "Neu aufnehmen")
                                    }
                                    TextButton(
                                        onClick = { onDeleteFotomojiTemplate(emoji) },
                                        enabled = !fotomojiTemplatesLoading && template != null,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Loeschen")
                                    }
                                }
                            }
                        }
                        repeat(4 - rowEmojis.size) {
                            Spacer(modifier = Modifier.weight(1f))
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
                subtitle = "Master + Update, Chat, Feed, Sondermomente, Interaktionen und neue Mitglieder",
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
                        label = "Push bei neuen Umfragen",
                        checked = pollPushEnabled,
                        onCheckedChange = onPollPushEnabledChange
                    )
                    SettingsToggleRow(
                        label = "Push bei spontanen Sondermomenten",
                        checked = specialMomentPushEnabled,
                        onCheckedChange = onSpecialMomentPushEnabledChange,
                        supportingText = "Optionaler Zusatzkanal fuer spontane Aufforderungen zum Posten ausserhalb des echten Daily-Moments."
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
                      subtitle = "Regler fuer Foto- und FotoMoji-Uploads",
                      expanded = sectionExpanded("upload_compression"),
                      onExpandedChange = { onProfileSectionExpandedChange("upload_compression", it) }
                  ) {
                      Card {
                          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                              Text("Normaler Foto-Upload", fontWeight = FontWeight.SemiBold)
                              Text("JPEG-Qualitaet: $uploadQuality%")
                              Slider(
                                  value = uploadQuality.toFloat(),
                                  onValueChange = { onUploadQualityChange(it.toInt()) },
                                  valueRange = 20f..100f
                              )
                              Text("Weniger Qualitaet = kleiner und schnellerer Upload")
                          }
                      }
                      Card {
                          Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                              Text("FotoMoji-Upload", fontWeight = FontWeight.SemiBold)
                              Text("FotoMoji JPEG-Qualitaet: $fotomojiUploadQuality%")
                              Slider(
                                  value = fotomojiUploadQuality.toFloat(),
                                  onValueChange = { onFotomojiUploadQualityChange(it.toInt()) },
                                  valueRange = 20f..100f
                              )
                              Text("Gilt fuer FotoMoji-Uploads und FotoMoji-Template-Aufnahmen")
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
                              Text(
                                  if (apiBaseUrlOverride.isBlank()) "Custom Server: aus"
                                  else "Custom Server: aktiv"
                              )
                              Spacer(modifier = Modifier.height(6.dp))
                              Button(onClick = onCheckConnection, modifier = Modifier.fillMaxWidth()) { Text("Verbindung pruefen") }
                              Spacer(modifier = Modifier.height(8.dp))
                              OutlinedTextField(
                                  value = serverOverrideInput,
                                  onValueChange = { serverOverrideInput = it },
                                  label = { Text("Server-Override (optional)") },
                                  placeholder = { Text("https://daily.example.com") },
                                  modifier = Modifier.fillMaxWidth(),
                                  singleLine = true
                              )
                              SettingsToggleRow(
                                  label = "Lokales HTTP erlauben",
                                  checked = allowInsecureHttpOverride,
                                  onCheckedChange = onAllowInsecureHttpOverrideChange,
                                  supportingText = "Nur fuer lokale Tests. Produktion sollte HTTPS nutzen."
                              )
                              Row(
                                  modifier = Modifier.fillMaxWidth(),
                                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                              ) {
                                  Button(
                                      onClick = { onApplyServerBaseUrlOverride(serverOverrideInput) },
                                      enabled = !applyServerOverrideInFlight,
                                      modifier = Modifier.weight(1f)
                                  ) { Text(if (applyServerOverrideInFlight) "Pruefe..." else "Server uebernehmen") }
                                  Button(
                                      onClick = {
                                          serverOverrideInput = ""
                                          onApplyServerBaseUrlOverride("")
                                      },
                                      enabled = !applyServerOverrideInFlight,
                                      modifier = Modifier.weight(1f)
                                  ) { Text("Reset auf Standard") }
                              }
                              Text(
                                  "Beim Wechsel wird die Session beendet und ein neuer Login am Zielserver gestartet.",
                                  color = MaterialTheme.colorScheme.onSurfaceVariant
                              )
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
                              label = "Freiwillige Datenfreigabe",
                              checked = diagnosticsConsentGranted,
                              onCheckedChange = onDiagnosticsConsentChange,
                              supportingText = "Damit duerfen technische Ladezeit- und Fehlerdaten zur Analyse uebermittelt werden. Jederzeit widerrufbar."
                          )
                          SettingsToggleRow(
                              label = "Diagnose-Upload aktivieren",
                              checked = diagnosticsUploadEnabled,
                              onCheckedChange = onDiagnosticsUploadEnabledChange,
                              supportingText = if (diagnosticsConsentGranted) {
                                  "Wenn aktiviert, werden Diagnose-Logs bei App-Start und bei neuen Fehlern automatisch an den Server geschickt."
                              } else {
                                  "Erfordert zuerst die freiwillige Datenfreigabe."
                              }
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
      if (showLocationEnableWarning) {
          AlertDialog(
            onDismissRequest = { showLocationEnableWarning = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLocationEnableWarning = false
                        locationFeatureEnabledValue = true
                        onLocationFeatureEnabledChange(true)
                        if (!locationPermissionGranted) {
                            onRequestLocationPermission()
                        }
                    }
                ) { Text("Aktivieren") }
            },
            dismissButton = {
                TextButton(onClick = { showLocationEnableWarning = false }) { Text("Abbrechen") }
            },
            title = { Text("Standort-Feature aktivieren?") },
            text = {
                Text("Achtung: Wenn du das aktivierst, koennen neue Posts deinen exakten Standort fuer andere Nutzer sichtbar machen. Nutze den Schalter im Kamera-Tab nur dann, wenn du das wirklich willst.")
            }
          )
      }
      if (showLocationDisableWarning) {
          AlertDialog(
            onDismissRequest = { showLocationDisableWarning = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLocationDisableWarning = false
                        locationFeatureEnabledValue = false
                        locationShareDefaultEnabledValue = false
                        onLocationFeatureEnabledChange(false)
                    }
                ) { Text("Deaktivieren") }
            },
            dismissButton = {
                TextButton(onClick = { showLocationDisableWarning = false }) { Text("Abbrechen") }
            },
            title = { Text("Standort-Feature deaktivieren?") },
            text = {
                Text("Ab jetzt werden bei neuen Posts keine Standortdaten mehr mitgesendet. Bereits freigegebene alte Posts behalten ihren gespeicherten Standort, bis er entfernt wird.")
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
    momentKind: String = "daily",
    requestedByUser: String? = null,
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

    val isSpecial = momentKind == "special"
    val title = if (isSpecial) {
        if (!requestedByUser.isNullOrBlank()) "Sondermoment von $requestedByUser" else "Sondermoment gestartet!"
    } else {
        "Daily-Moment gestartet!"
    }
    val description = if (isSpecial) {
        if (!requestedByUser.isNullOrBlank()) {
            "$requestedByUser hat einen Sondermoment angefordert. Jetzt sofort aufnehmen: Rueckkamera + Frontkamera."
        } else {
            "Jetzt sofort aufnehmen: Rueckkamera + Frontkamera."
        }
    } else {
        "Jetzt sofort aufnehmen: Rueckkamera + Frontkamera."
    }
    val actionLabel = if (isSpecial) "Sondermoment aufnehmen" else "Daily-Moment aufnehmen"

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
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        description,
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
                            Text(actionLabel)
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
    supportingText: String? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!supportingText.isNullOrBlank()) {
                Text(supportingText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(
            modifier = Modifier.widthIn(min = 56.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

private fun openExternalUrl(context: Context, url: String?) {
    val safeUrl = url?.trim().orEmpty()
    if (safeUrl.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    return hasFine || hasCoarse
}

private fun appPermissionSettingsIntent(context: Context): Intent {
    return Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

private fun normalizeMomentKind(momentKind: String?, triggerSource: String?): String {
    val normalizedKind = momentKind?.trim().orEmpty().lowercase()
    if (normalizedKind == "special" || normalizedKind == "daily") {
        return normalizedKind
    }
    return when (triggerSource?.trim().orEmpty().lowercase()) {
        "special_request", "chat_command" -> "special"
        else -> "daily"
    }
}

private fun momentReasonLine(momentKind: String?, triggerSource: String?, requestedByUser: String?): String? {
    if (momentKind.isNullOrBlank() && triggerSource.isNullOrBlank()) {
        return null
    }
    return when (normalizeMomentKind(momentKind, triggerSource)) {
        "special" -> if (!requestedByUser.isNullOrBlank()) "Sondermoment von $requestedByUser" else "Sondermoment"
        "daily" -> "Daily-Moment"
        else -> null
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

@Composable
private fun SpecialMomentBadge(requestedByUser: String?, requestedByUserColor: String?) {
    val label = if (!requestedByUser.isNullOrBlank()) {
        "Sondermoment von $requestedByUser"
    } else {
        "Sondermoment"
    }
    val bg = if (!requestedByUserColor.isNullOrBlank()) parseUserColor(requestedByUserColor) else Color(0xFF0A7A42)
    Box(
        modifier = Modifier
            .background(bg, shape = MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
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
    if (t is IllegalStateException) {
        return when (t.message?.trim().orEmpty()) {
            "token_expired_refresh_failed" -> "Sitzung abgelaufen. Bitte erneut einloggen."
            "missing_access_token" -> "Bitte einloggen."
            "migration_required" -> "Diese Instanz ist im Migrationsmodus. Bitte Zielserver eintragen."
            "invalid_auth_payload" -> "Login unvollstaendig. Bitte erneut einloggen."
            else -> fallback
        }
    }
    if (t is HttpException) {
        val raw = runCatching { t.response()?.errorBody()?.string().orEmpty() }.getOrDefault("").lowercase()
        return when (t.code()) {
            400 -> "Ungueltige Eingabe"
            401 -> when {
                raw.contains("invalid_credentials") -> "Login fehlgeschlagen"
                raw.contains("session_revoked") -> "Sitzung wurde beendet. Bitte erneut einloggen."
                else -> "Nicht autorisiert. Bitte erneut einloggen."
            }
            404 -> when {
                raw.contains("invite code not found") -> "Invite-Code nicht gefunden oder bereits benutzt."
                else -> fallback
            }
            403 -> when {
                raw.contains("prompt inactive") -> "Heute ist gerade kein aktiver Daily-Moment."
                raw.contains("extra unavailable during daily moment window") -> "Waehrend des aktiven Daily-Moments sind Extras gesperrt."
                raw.contains("upload window closed") -> "Upload-Zeitfenster ist geschlossen."
                raw.contains("poste zuerst dein tagesmoment") -> "Poste zuerst dein Tagesmoment."
                else -> "Aktion nicht erlaubt"
            }
            409 -> when {
                raw.contains("username exists") -> "Benutzername ist bereits vergeben."
                else -> "Du hast heute bereits gepostet"
            }
            423 -> when {
                raw.contains("migration_required") || raw.contains("migration required") -> "Diese Instanz ist im Migrationsmodus. Bitte Zielserver eintragen."
                else -> "Instanz gesperrt"
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
    "- Im Feed-Badge wird zwischen Daily-Moment und Sondermoment (mit Ausloesername) getrennt.",
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

private val viewerReactionEmojis = listOf("\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDE02", "\uD83D\uDD25", "\uD83D\uDE2E")
private const val viewerFotomojiLiveEmoji = "\u26A1"
private val viewerFotomojiEmojis = viewerReactionEmojis + viewerFotomojiLiveEmoji

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FullscreenPhotoViewer(
    urls: List<String>,
    initialIndex: Int,
    photoId: Long?,
    locationMapsUrl: String?,
    comment: String,
    interactions: PhotoInteractionsResponse?,
    interactionsLoading: Boolean,
    ownDownloadFallback: Boolean,
    useFotomojiReactions: Boolean,
    onCommentChange: (String) -> Unit,
    onCommentSend: () -> Unit,
    onReact: (String) -> Unit,
    onFotoMojiTap: (String) -> Unit,
    onFotoMojiLongPress: (String) -> Unit,
    onDoubleTapReact: () -> Unit,
    onDownloadCurrent: (String) -> Unit,
    onOpenLocation: (String) -> Unit,
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
                    locationMapsUrl = locationMapsUrl,
                    currentImageUrl = urls.getOrNull(pagerState.currentPage).orEmpty(),
                    comment = comment,
                    interactions = interactions,
                    interactionsLoading = interactionsLoading,
                    ownDownloadFallback = ownDownloadFallback,
                    useFotomojiReactions = useFotomojiReactions,
                    onCommentChange = onCommentChange,
                    onCommentSend = onCommentSend,
                    onReact = onReact,
                    onFotoMojiTap = onFotoMojiTap,
                    onFotoMojiLongPress = onFotoMojiLongPress,
                    onDownloadCurrent = onDownloadCurrent,
                    onOpenLocation = onOpenLocation
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ViewerInteractionSheet(
    photoId: Long?,
    locationMapsUrl: String?,
    currentImageUrl: String,
    comment: String,
    interactions: PhotoInteractionsResponse?,
    interactionsLoading: Boolean,
    ownDownloadFallback: Boolean,
    useFotomojiReactions: Boolean,
    onCommentChange: (String) -> Unit,
    onCommentSend: () -> Unit,
    onReact: (String) -> Unit,
    onFotoMojiTap: (String) -> Unit,
    onFotoMojiLongPress: (String) -> Unit,
    onDownloadCurrent: (String) -> Unit,
    onOpenLocation: (String) -> Unit
) {
    var selectedFotoMoji by remember { mutableStateOf<PhotoMojiItem?>(null) }
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
        val reactionCountByEmoji = interactions?.reactions.orEmpty().associate { it.emoji to it.count }
        val photoMojiCountByEmoji = interactions?.photoMojis.orEmpty()
            .groupingBy { it.emoji }
            .eachCount()
            .mapValues { it.value.toLong() }
        val reactionEmojis = if (useFotomojiReactions) viewerFotomojiEmojis else viewerReactionEmojis
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            reactionEmojis.forEach { emoji ->
                val selected = if (useFotomojiReactions) {
                    interactions?.myPhotoMoji?.emoji == emoji
                } else {
                    interactions?.myReaction == emoji
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.small
                        )
                        .combinedClickable(
                            onClick = {
                                if (useFotomojiReactions) onFotoMojiTap(emoji) else onReact(emoji)
                            },
                            onLongClick = {
                                if (useFotomojiReactions) {
                                    onFotoMojiLongPress(emoji)
                                }
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val count = if (useFotomojiReactions) {
                        photoMojiCountByEmoji[emoji] ?: 0L
                    } else {
                        reactionCountByEmoji[emoji] ?: 0L
                    }
                    Text(
                        "${if (selected) "+ " else ""}$emoji $count",
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
        if (useFotomojiReactions) {
            Text("FotoMoji-Modus aktiv: Tippen nutzt Template, lang druecken ersetzt das Template. ⚡ bleibt immer live.")
        }
        val photoMojis = interactions?.photoMojis.orEmpty().sortedWith(
            compareBy<PhotoMojiItem>(
                { parseOffsetOrLocalDateTime(it.createdAt) ?: LocalDateTime.MIN },
                { it.id }
            )
        )
        if (photoMojis.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(photoMojis) { item ->
                    Row(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large)
                            .clickable { selectedFotoMoji = item }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AsyncImage(
                            model = item.url,
                            contentDescription = "FotoMoji",
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color.LightGray, CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            item.emoji,
                            fontWeight = FontWeight.Bold,
                            color = parseUserColor(item.user.favoriteColor)
                        )
                    }
                }
            }
        }
        if ((interactions?.canDownload == true || ownDownloadFallback) && currentImageUrl.isNotBlank()) {
            Button(
                onClick = { onDownloadCurrent(currentImageUrl) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Post herunterladen") }
        }
        if (!locationMapsUrl.isNullOrBlank()) {
            Button(
                onClick = { onOpenLocation(locationMapsUrl) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) { Text("📍 Standort in Google Maps oeffnen") }
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
    selectedFotoMoji?.let { item ->
        Dialog(
            onDismissRequest = { selectedFotoMoji = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .clickable { selectedFotoMoji = null },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(
                        model = item.url,
                        contentDescription = "FotoMoji Vollansicht",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp)
                            .padding(horizontal = 20.dp),
                        contentScale = ContentScale.Fit
                    )
                    Text("@${item.user.username} ${item.emoji}", color = Color.White, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { selectedFotoMoji = null }) { Text("Schliessen") }
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
