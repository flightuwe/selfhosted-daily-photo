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
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.VerticalAlignTop
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import java.security.cert.CertPathValidatorException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLException
import kotlin.math.abs
import kotlin.random.Random
import kotlin.coroutines.resume

const val EXTRA_LAUNCH_ACTION = "daily_launch_action"
const val EXTRA_LAUNCH_TYPE = "daily_launch_type"
const val EXTRA_LAUNCH_DAY = "daily_launch_day"
const val EXTRA_LAUNCH_PHOTO_ID = "daily_launch_photo_id"
const val DEBUG_MASTER_ENABLED_KEY = "debug_master_enabled_v1"
const val FEED_DEBUG_ENABLED_KEY = "feed_debug_enabled_v1"
const val PENDING_FEED_INVALIDATIONS_KEY = "pending_feed_invalidations_v1"

fun queuePendingFeedInvalidation(
    context: Context,
    day: String,
    photoId: Long? = null,
    reason: String = "",
    source: String = ""
) {
    val cleanDay = day.trim()
    if (cleanDay.isBlank()) return
    val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    val current = runCatching {
        JSONArray(prefs.getString(PENDING_FEED_INVALIDATIONS_KEY, "").orEmpty())
    }.getOrDefault(JSONArray())
    current.put(
        JSONObject().apply {
            put("day", cleanDay)
            put("photoId", photoId ?: 0L)
            put("reason", reason.trim().take(64))
            put("source", source.trim().take(64))
            put("createdAt", OffsetDateTime.now().toString())
        }
    )
    val trimmed = JSONArray()
    val start = (current.length() - 40).coerceAtLeast(0)
    for (i in start until current.length()) {
        trimmed.put(current.opt(i))
    }
    prefs.edit().putString(PENDING_FEED_INVALIDATIONS_KEY, trimmed.toString()).apply()
}

fun isFeedRelatedPush(action: String, type: String, day: String, photoId: String): Boolean {
    val normalizedAction = action.trim().lowercase()
    val normalizedType = type.trim().lowercase()
    return normalizedAction == "open_feed" ||
        normalizedType == "feed_post" ||
        normalizedType == "post" ||
        normalizedType == "extra_post" ||
        normalizedType.startsWith("photo_") ||
        normalizedType.startsWith("bookmarked_photo_") ||
        day.isNotBlank() ||
        photoId.isNotBlank()
}

enum class AppTab { CAMERA, FEED, CALENDAR, CHAT, PROFILE }
enum class AuthMode { LOGIN, REGISTER }
enum class CalendarMode { PUBLIC, BOOKMARKS, SEARCH, TIME_CAPSULES }
enum class FeedOrderMode { CHRONO, TREND, RANDOM }
enum class TimeCapsuleFilter { ALL, RELEASED, LOCKED }
enum class BookmarkCalendarFilter { MINE, ALL }

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
    val bookmarkedPhotoPushEnabled: Boolean = false,
    val postChangePushEnabled: Boolean = false,
    val autoSubscribeInteractedPostsEnabled: Boolean = false,
    val ownPostNumberInPushEnabled: Boolean = false,
    val postNumberInPushEnabled: Boolean = false,
    val yoloModeEnabled: Boolean = false,
    val allowPhotoDownload: Boolean = false,
    val allowCommunityNsfwMarking: Boolean = false,
    val showNsfwByDefault: Boolean = false,
    val creativePostMode: String = "none",
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
data class MeResponse(
    val user: User,
    val dailyMomentCount: Int = 0,
    val streakDays: Int = 0,
    val bookmarksGivenCount: Int = 0,
    val bookmarksReceivedCount: Int = 0
)
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
    val bookmarkedPhotoPushEnabled: Boolean? = null,
    val postChangePushEnabled: Boolean? = null,
    val autoSubscribeInteractedPostsEnabled: Boolean? = null,
    val ownPostNumberInPushEnabled: Boolean? = null,
    val postNumberInPushEnabled: Boolean? = null,
    val yoloModeEnabled: Boolean? = null,
    val allowPhotoDownload: Boolean,
    val allowCommunityNsfwMarking: Boolean? = null,
    val showNsfwByDefault: Boolean? = null,
    val creativePostMode: String? = null,
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
data class PostMediaItem(
    val id: String = "",
    val url: String = "",
    val previewUrl: String? = null,
    val capturedAt: String? = null,
    val sourceKind: String = "attachment"
)
data class PromptPhoto(
    val id: Long,
    val day: String,
    val promptOnly: Boolean,
    val caption: String?,
    val url: String,
    val secondUrl: String? = null,
    val createdAt: String,
    val capturedAt: String? = null,
    val uploadedAt: String? = null,
    val timeShifted: Boolean = false,
    val deduplicated: Boolean = false,
    val dailyMoment: Boolean = false,
    val capsuleMode: String? = null,
    val capsuleVisibleAt: String? = null,
    val capsulePrivate: Boolean = false,
    val capsuleGroupRemind: Boolean = false,
    val capsulePreviewUrl: String? = null,
    val capsuleLocked: Boolean = false,
    val locationShared: Boolean = false,
    val locationDisplay: String? = null,
    val locationMapsUrl: String? = null,
    val bookmarkedByMe: Boolean = false,
    val bookmarkCount: Int = 0,
    val nsfw: Boolean = false,
    val nsfwMarkedByUserId: Long? = null,
    val nsfwMarkedAt: String? = null,
    val nsfwMarkAllowed: Boolean = false,
    val nsfwUnmarkAllowed: Boolean = false,
    val publicNumber: String? = null,
    val creativePostMode: String = "none",
    val canMark: Boolean = false,
    val canPaint: Boolean = false,
    val markedByMe: Boolean = false,
    val paintedByMe: Boolean = false,
    val media: List<PostMediaItem> = emptyList(),
    val mediaCount: Int = 0,
    val marks: List<PhotoMarkOverlay> = emptyList(),
    val paints: List<PhotoPaintOverlay> = emptyList()
)
data class PhotoMarkOverlay(
    val id: Long,
    val userId: Long,
    val username: String = "",
    val color: String = "#1F5FBF",
    val surface: String = "frame",
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radiusX: Float = 0.12f,
    val radiusY: Float = 0.1f,
    val rotation: Float = 0f,
    val seed: Long = 0L,
    val layer: Long = 0L
)
data class PhotoPaintPoint(
    val x: Float = 0f,
    val y: Float = 0f
)
data class PhotoPaintPath(
    val points: List<PhotoPaintPoint> = emptyList()
)
data class PhotoPaintOverlay(
    val id: Long,
    val userId: Long,
    val username: String = "",
    val color: String = "#1F5FBF",
    val surface: String = "frame",
    val strokeWidth: Float = 0.035f,
    val pathsJson: String = ""
)
data class PhotoPaintRequest(
    val paths: List<PhotoPaintPath> = emptyList(),
    val strokeWidth: Float = 0.035f,
    val surface: String = "card"
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
    val canAppendToOwnLatestPost: Boolean = false,
    val appendTargetPhotoId: Long? = null,
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

private fun PromptPhoto.mediaItems(): List<PostMediaItem> {
    if (media.isNotEmpty()) return media.filter { it.url.isNotBlank() }
    return listOfNotNull(
        url.takeIf { it.isNotBlank() }?.let { PostMediaItem(id = "${id}-primary", url = it, capturedAt = capturedAt, sourceKind = "primary") },
        secondUrl?.takeIf { it.isNotBlank() }?.let { PostMediaItem(id = "${id}-secondary", url = it, capturedAt = capturedAt, sourceKind = "secondary") }
    )
}

private fun PromptPhoto.mediaUrls(): List<String> = mediaItems().map { it.url }

private data class PaintEditorTarget(
    val item: FeedItem,
    val isMomentWindowPost: Boolean,
    val postMomentKind: String?,
    val requestedByUser: String?,
    val requestedByUserColor: String?
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
data class FeedWindowResponse(
    val anchorDay: String,
    val days: List<FeedResponse> = emptyList(),
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false,
    val oldestLoadedDay: String? = null,
    val newestLoadedDay: String? = null,
    val resolvedFocusPhotoId: Long? = null,
    val mode: String? = null,
    val offset: Int = 0,
    val nextOffset: Int = 0,
    val randomSeed: Long = 0L
)
enum class FeedViewportAnchorKind {
    PHOTO,
    DAY_HEADER,
    RECAP,
    LOADING
}

enum class FeedJumpBoundary {
    START,
    END
}

data class FeedViewportAnchor(
    val day: String? = null,
    val photoId: Long? = null,
    val rowOffsetPx: Int = 0,
    val kind: FeedViewportAnchorKind = FeedViewportAnchorKind.DAY_HEADER,
    val rowIndex: Int = -1,
    val firstVisibleIndex: Int = -1,
    val lastVisibleIndex: Int = -1,
    val rowsSize: Int = 0,
    val presentInRows: Boolean = false
)

data class PendingFeedMutation(
    val photoOverride: PromptPhoto? = null,
    val commentsOverride: List<PhotoCommentItem>? = null,
    val reactionsOverride: List<ReactionCount>? = null,
    val photoMojisOverride: List<PhotoMojiItem>? = null
)

private enum class RefreshPriority(val weight: Int) {
    GLOBAL(0),
    AUTO(1),
    MANUAL(2),
    MUTATION(3),
    NAVIGATION(4)
}

private data class QueuedRefreshRequest(
    val reason: String,
    val forceFeedReload: Boolean,
    val refreshFeedWindow: Boolean,
    val bypassCooldown: Boolean,
    val showLoading: Boolean,
    val respectCircuitBreaker: Boolean,
    val priority: RefreshPriority
)

data class DayListResponse(
    val items: List<String>,
    val hasOlder: Boolean = false,
    val hasNewer: Boolean = false
)
data class CalendarFeaturedPhoto(
    val photoId: Long,
    val url: String,
    val secondUrl: String? = null,
    val user: User,
    val reactionCount: Long = 0,
    val commentCount: Long = 0,
    val interactionCount: Long = 0,
    val bookmarkedByMe: Boolean = false,
    val bookmarkCount: Int = 0,
    val nsfw: Boolean = false,
    val nsfwMarkedByUserId: Long? = null,
    val nsfwMarkedAt: String? = null,
    val nsfwMarkAllowed: Boolean = false,
    val nsfwUnmarkAllowed: Boolean = false,
    val publicNumber: String? = null,
    val capsuleLocked: Boolean = false,
    val capsuleVisibleAt: String? = null
)
data class CalendarPhotoItem(
    val photo: PromptPhoto,
    val user: User
)
data class DayStatItem(
    val day: String,
    val count: Long = 0,
    val postCount: Long = 0,
    val participantCount: Long = 0,
    val featuredPhoto: CalendarFeaturedPhoto? = null
)
data class DayStatsResponse(val items: List<DayStatItem>)
data class CalendarUserOption(
    val id: Long,
    val username: String,
    val favoriteColor: String = "#1F5FBF"
)
data class CalendarPayloadResponse(
    val days: List<String> = emptyList(),
    val dayStats: List<DayStatItem> = emptyList(),
    val photosByDay: Map<String, List<CalendarPhotoItem>> = emptyMap(),
    val users: List<CalendarUserOption> = emptyList(),
    val items: List<FeedItem> = emptyList(),
    val lockedCount: Int = 0,
    val releasedCount: Int = 0
)
data class CalendarSearchMatchItem(
    val photo: PromptPhoto,
    val user: User,
    val excerpt: String = "",
    val matchedCaption: Boolean = false,
    val matchedComments: List<String> = emptyList(),
    val matchedHashtags: List<String> = emptyList()
)
data class CalendarSearchResponse(
    val query: String = "",
    val normalizedQuery: String = "",
    val days: List<String> = emptyList(),
    val dayStats: List<DayStatItem> = emptyList(),
    val matchedPhotosByDay: Map<String, List<CalendarSearchMatchItem>> = emptyMap()
)
data class BookmarkClearResponse(
    val ok: Boolean = false,
    val deletedCount: Int = 0
)
data class PhotoReportResponse(
    val ok: Boolean = false,
    val report: Boolean = false,
    val reportId: Long? = null,
    val reportType: String? = null,
    val reportStatus: String? = null,
    val message: String? = null
)
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
    val photos: List<PromptPhoto> = emptyList(),
    val bookmarksGivenCount: Int = 0,
    val bookmarksReceivedCount: Int = 0
)
data class PhotoMutationResponse(
    val ok: Boolean = false,
    val photo: PromptPhoto? = null
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
    val chatMessageMaxLength: Int = 5000,
    val chatMessageUnlimited: Boolean = false,
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
    val createdAt: String,
    val aggregateCount: Int = 1,
    val firstSeenAt: String = createdAt,
    val lastSeenAt: String = createdAt
)

data class UploadTelemetryProbe(
    val pingMs: Long? = null,
    val pingFailure: String = "",
    val networkSnapshot: String = "",
    val networkStable: Boolean = false
)

private fun debugRootCauseShared(throwable: Throwable): Throwable {
    var current = throwable
    while (current.cause != null && current.cause !== current) {
        current = current.cause!!
    }
    return current
}

private fun debugNetworkFailureKindShared(throwable: Throwable): String? {
    val root = debugRootCauseShared(throwable)
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

private fun isBenignCancellationShared(throwable: Throwable): Boolean {
    if (throwable is CancellationException) return true
    return throwable::class.java.simpleName.contains("LeftCompositionCancellationException")
}

private fun debugMetaSanitizeShared(value: String, maxLen: Int = 160): String {
    val clean = value
        .replace(";", ",")
        .replace("\n", " ")
        .replace("\r", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (clean.isBlank()) return "-"
    return if (clean.length > maxLen) clean.take(maxLen) else clean
}

private fun debugThrowableChainShared(throwable: Throwable, maxDepth: Int = 6): String {
    return generateSequence(throwable) { it.cause }
        .take(maxDepth)
        .joinToString(">") { it::class.java.simpleName.ifBlank { "Unknown" } }
        .ifBlank { "Unknown" }
}

private fun debugSecurityFailureDetailShared(throwable: Throwable): String {
    val messages = generateSequence(throwable) { it.cause }
        .mapNotNull { it.message?.trim() }
        .map { it.lowercase() }
        .toList()
    return when {
        messages.any { it.contains("trust anchor for certification path not found") } -> "trust_anchor_missing"
        messages.any { it.contains("certificate pinning") } -> "certificate_pinning"
        messages.any { it.contains("hostname") && it.contains("not verified") } -> "hostname_not_verified"
        messages.any { it.contains("unable to find valid certification path") } -> "cert_path_untrusted"
        messages.any { it.contains("handshake") && it.contains("failure") } -> "handshake_failure"
        messages.any { it.contains("certificate expired") || it.contains("notafter") } -> "certificate_expired"
        messages.any { it.contains("certificate revoked") } -> "certificate_revoked"
        messages.any { it.contains("protocol version") } -> "protocol_version"
        messages.any { it.contains("remote host terminated the handshake") } -> "remote_handshake_abort"
        debugNetworkFailureKindShared(throwable) == "cert_path_validator" -> "cert_path_validator"
        debugNetworkFailureKindShared(throwable) == "ssl_handshake" -> "ssl_handshake"
        debugNetworkFailureKindShared(throwable) == "ssl_other" -> "ssl_other"
        else -> "-"
    }
}

fun debugThrowableMetaShared(throwable: Throwable): String {
    val root = debugRootCauseShared(throwable)
    val rootMessage = debugMetaSanitizeShared(root.message.orEmpty())
    val topMessage = debugMetaSanitizeShared(throwable.message.orEmpty())
    return buildString {
        append("rootClass=").append(root::class.java.simpleName.ifBlank { "Unknown" })
        append(";rootMessage=").append(rootMessage)
        append(";topClass=").append(throwable::class.java.simpleName.ifBlank { "Unknown" })
        append(";topMessage=").append(topMessage)
        append(";failureChain=").append(debugMetaSanitizeShared(debugThrowableChainShared(throwable), 220))
        append(";securityDetail=").append(debugSecurityFailureDetailShared(throwable))
    }
}

private fun securityAdviceForFailure(failureClass: String): String {
    return when (failureClass) {
        "ssl_handshake",
        "cert_path_validator",
        "ssl_other" -> "Daily konnte in diesem Netzwerk keine sichere Verbindung aufbauen. Bitte mobile Daten oder ein anderes WLAN versuchen."
        else -> ""
    }
}

private fun parseIsoInstantMs(value: String): Long {
    return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrDefault(0L)
}

private const val DEFAULT_CHAT_MESSAGE_MAX_LENGTH = 5000

private fun textCodePointLength(value: String): Int = value.codePointCount(0, value.length)

private fun PromptRulesResponse?.effectiveChatMessageMaxLength(): Int =
    this?.chatMessageMaxLength?.takeIf { it > 0 } ?: DEFAULT_CHAT_MESSAGE_MAX_LENGTH

private fun PromptRulesResponse?.isChatMessageUnlimited(): Boolean = this?.chatMessageUnlimited == true

private fun peekHttpErrorBody(throwable: Throwable, maxBytes: Long = 2048L): String {
    val http = throwable as? HttpException ?: return ""
    return runCatching { http.response()?.raw()?.peekBody(maxBytes)?.string().orEmpty() }
        .getOrDefault("")
        .trim()
}

private fun extractJsonIntField(raw: String, field: String): Int? {
    val regex = Regex(""""$field"\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
    return regex.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

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

    @GET("feed/window")
    suspend fun feedWindow(
        @Header("Authorization") token: String,
        @Query("anchor_day") anchorDay: String,
        @Query("before_days") beforeDays: Int = 2,
        @Query("after_days") afterDays: Int = 2,
        @Query("focus_photo_id") focusPhotoId: Long? = null
    ): FeedWindowResponse

    @GET("feed/discover")
    suspend fun feedDiscover(
        @Header("Authorization") token: String,
        @Query("mode") mode: String,
        @Query("offset") offset: Int = 0,
        @Query("limit_days") limitDays: Int = 7,
        @Query("random_seed") randomSeed: Long? = null,
        @Query("anchor_day") anchorDay: String? = null,
        @Query("focus_photo_id") focusPhotoId: Long? = null
    ): FeedWindowResponse

    @GET("feed/days")
    suspend fun feedDays(
        @Header("Authorization") token: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("before_day") beforeDay: String? = null,
        @Query("after_day") afterDay: String? = null,
        @Query("anchor_day") anchorDay: String? = null,
        @Query("limit") limit: Int? = null
    ): DayListResponse

    @GET("feed/day-stats")
    suspend fun feedDayStats(
        @Header("Authorization") token: String,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null
    ): DayStatsResponse

    @GET("calendar/public")
    suspend fun calendarPublic(@Header("Authorization") token: String): CalendarPayloadResponse

    @GET("calendar/user/{id}")
    suspend fun calendarUser(@Header("Authorization") token: String, @Path("id") id: Long): CalendarPayloadResponse

    @GET("calendar/bookmarks")
    suspend fun calendarBookmarks(@Header("Authorization") token: String, @Query("scope") scope: String? = null): CalendarPayloadResponse

    @GET("calendar/time-capsules")
    suspend fun calendarTimeCapsules(@Header("Authorization") token: String): CalendarPayloadResponse

    @GET("calendar/search")
    suspend fun calendarSearch(
        @Header("Authorization") token: String,
        @Query("q") query: String
    ): CalendarSearchResponse

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
        @Part("captured_at") capturedAt: RequestBody? = null,
        @Part("upload_client_id") uploadClientId: RequestBody? = null,
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
        @Part("captured_at") capturedAt: RequestBody? = null,
        @Part("upload_client_id") uploadClientId: RequestBody? = null,
        @Part("capsule_mode") capsuleMode: RequestBody? = null,
        @Part("capsule_private") capsulePrivate: RequestBody? = null,
        @Part("capsule_group_remind") capsuleGroupRemind: RequestBody? = null,
        @Part("location_shared") locationShared: RequestBody? = null,
        @Part("location_latitude") locationLatitude: RequestBody? = null,
        @Part("location_longitude") locationLongitude: RequestBody? = null
    )

    @Multipart
    @POST("photos/{id}/attachments")
    suspend fun appendPhotoAttachment(
        @Header("Authorization") token: String,
        @Path("id") id: Long,
        @Part photo: MultipartBody.Part,
        @Part("captured_at") capturedAt: RequestBody? = null,
        @Part("upload_client_id") uploadClientId: RequestBody? = null,
        @Part("location_shared") locationShared: RequestBody? = null,
        @Part("location_latitude") locationLatitude: RequestBody? = null,
        @Part("location_longitude") locationLongitude: RequestBody? = null
    ): PhotoMutationResponse

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

    @POST("photos/{id}/bookmark")
    suspend fun bookmarkPhoto(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): PhotoMutationResponse

    @DELETE("photos/{id}/bookmark")
    suspend fun unbookmarkPhoto(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): PhotoMutationResponse

    @POST("photos/{id}/mark")
    suspend fun markPhoto(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): PhotoMutationResponse

    @DELETE("photos/{id}/mark")
    suspend fun unmarkPhoto(
        @Header("Authorization") token: String,
        @Path("id") id: Long,
        @Query("userId") userId: Long? = null
    ): PhotoMutationResponse

    @PUT("photos/{id}/paint")
    suspend fun savePhotoPaint(
        @Header("Authorization") token: String,
        @Path("id") id: Long,
        @Body body: PhotoPaintRequest
    ): PhotoMutationResponse

    @DELETE("photos/{id}/paint")
    suspend fun deletePhotoPaint(
        @Header("Authorization") token: String,
        @Path("id") id: Long,
        @Query("userId") userId: Long? = null
    ): PhotoMutationResponse

    @DELETE("photos/bookmarks")
    suspend fun clearBookmarks(@Header("Authorization") token: String): BookmarkClearResponse

    @POST("photos/{id}/report")
    suspend fun reportPhoto(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): PhotoReportResponse

    @POST("photos/{id}/nsfw")
    suspend fun markPhotoNsfw(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): PhotoMutationResponse

    @DELETE("photos/{id}/nsfw")
    suspend fun unmarkPhotoNsfw(
        @Header("Authorization") token: String,
        @Path("id") id: Long
    ): PhotoMutationResponse

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
    private val debugMasterEnabledKey = DEBUG_MASTER_ENABLED_KEY
    private val feedDebugEnabledKey = FEED_DEBUG_ENABLED_KEY
    private val pendingFeedInvalidationsKey = PENDING_FEED_INVALIDATIONS_KEY
    private val diagnosticsSecurityAdviceLastShownAtKey = "diagnostics_security_advice_last_shown_at"
    private val diagnosticsConsentLocalKey = "diagnostics_consent_local"
    private val diagnosticsConsentPendingKey = "diagnostics_consent_pending"
    private val diagnosticsSessionIdKey = "diagnostics_session_id"
    private val promptSeenVersionPrefix = "user_prompt_seen_version_"
    private val debugMaxEntries = 500
    private val debugUploadMinIntervalMs = 5 * 60 * 1000L
    private val debugAggregateWindowMs = 10 * 60 * 1000L
    @Volatile
    private var lastAuthTransitionReason: String = "startup"

    fun token(): String = accessToken()

    private fun accessToken(): String = AuthSessionCoordinator.snapshot(context).accessToken

    private fun currentSessionId(): String = AuthSessionCoordinator.snapshot(context).sessionId

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
        val downKbps = caps.linkDownstreamBandwidthKbps
        val upKbps = caps.linkUpstreamBandwidthKbps
        return "activeNetwork=true;capabilities=true;internet=$internet;validated=$validated;metered=$metered;transport=$transport;downKbps=$downKbps;upKbps=$upKbps"
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
        val beforeTokenPresent = AuthSessionCoordinator.snapshot(context).hasAccessToken()
        val afterTokenPresent = AuthSessionCoordinator.persist(context, auth).hasAccessToken()
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
        val beforeTokenPresent = AuthSessionCoordinator.snapshot(context).hasAccessToken()
        AuthSessionCoordinator.clear(context)
        val afterTokenPresent = AuthSessionCoordinator.snapshot(context).hasAccessToken()
        recordAuthStateTransition(
            reason = reason,
            endpoint = endpoint,
            beforeTokenPresent = beforeTokenPresent,
            afterTokenPresent = afterTokenPresent
        )
    }

    private fun authHeader(): String = AuthSessionCoordinator.snapshot(context).authHeader()

    private suspend fun tryRefreshSessionLocked(expectedHeader: String? = null): Boolean {
        val before = AuthSessionCoordinator.snapshot(context)
        if (!expectedHeader.isNullOrBlank() && before.hasAccessToken() && before.authHeader() != expectedHeader) {
            return true
        }
        if (!before.hasRefreshToken()) return false
        val response = runCatching { api.refresh(RefreshRequest(before.refreshToken)) }.getOrElse { return false }
        val nextAccess = response.accessToken.trim().ifBlank { response.token.trim() }
        val nextRefresh = response.refreshToken.trim()
        if (nextAccess.isBlank() || nextRefresh.isBlank()) return false
        saveAuthSession(response, source = "refresh_session_success")
        return true
    }

    private suspend fun tryRefreshSession(expectedHeader: String? = null): Boolean =
        AuthSessionCoordinator.withRefreshLock { tryRefreshSessionLocked(expectedHeader) }

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
            val latestHeader = authHeader()
            if (latestHeader.length > "Bearer ".length && latestHeader != firstHeader) {
                return block(latestHeader)
            }
            val refreshed = tryRefreshSession(firstHeader)
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

    suspend fun uploadDualAuthorized(
        backPart: MultipartBody.Part,
        frontPart: MultipartBody.Part,
        kind: RequestBody,
        capturedAtPart: RequestBody? = null,
        uploadClientIdPart: RequestBody? = null,
        capsuleModePart: RequestBody? = null,
        capsulePrivatePart: RequestBody? = null,
        capsuleGroupRemindPart: RequestBody? = null,
        locationSharedPart: RequestBody? = null,
        locationLatitudePart: RequestBody? = null,
        locationLongitudePart: RequestBody? = null
    ) {
        authorizedCall("/api/uploads/dual") { token ->
            api.uploadDual(
                token,
                backPart,
                frontPart,
                kind,
                capturedAtPart,
                uploadClientIdPart,
                capsuleModePart,
                capsulePrivatePart,
                capsuleGroupRemindPart,
                locationSharedPart,
                locationLatitudePart,
                locationLongitudePart
            )
        }
    }

    fun diagnosticsUploadEnabled(): Boolean = prefs.getBoolean(debugUploadEnabledKey, false)

    fun debugMasterEnabled(): Boolean = prefs.getBoolean(debugMasterEnabledKey, false)

    fun setDebugMasterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(debugMasterEnabledKey, enabled).apply()
    }

    fun feedDebugEnabled(): Boolean = debugMasterEnabled() && prefs.getBoolean(feedDebugEnabledKey, false)

    fun setFeedDebugEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(feedDebugEnabledKey, enabled).apply()
    }

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

    fun queueFeedInvalidation(
        day: String,
        photoId: Long? = null,
        reason: String = "",
        source: String = ""
    ) {
        queuePendingFeedInvalidation(context, day, photoId, reason, source)
    }

    fun consumePendingFeedInvalidations(): List<PendingLaunch> {
        val raw = prefs.getString(pendingFeedInvalidationsKey, "").orEmpty()
        prefs.edit().remove(pendingFeedInvalidationsKey).apply()
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = mutableListOf<PendingLaunch>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val day = obj.optString("day", "").trim()
            if (day.isBlank()) continue
            val photoId = obj.optLong("photoId", 0L).takeIf { it > 0L }
            out += PendingLaunch(
                action = obj.optString("source", ""),
                type = obj.optString("reason", ""),
                targetDay = day,
                targetPhotoId = photoId
            )
        }
        return out
    }

    fun diagnosticsSessionId(): String {
        val existing = prefs.getString(diagnosticsSessionIdKey, "")?.trim().orEmpty()
        if (existing.isNotBlank()) return existing
        val generated = "sess_${UUID.randomUUID()}"
        prefs.edit().putString(diagnosticsSessionIdKey, generated).apply()
        return generated
    }

    fun lastSecurityAdviceShownAtMs(): Long = prefs.getLong(diagnosticsSecurityAdviceLastShownAtKey, 0L)

    fun setLastSecurityAdviceShownAtMs(value: Long) {
        prefs.edit().putLong(diagnosticsSecurityAdviceLastShownAtKey, value.coerceAtLeast(0L)).apply()
    }

    private fun aggregateMetaSignature(type: String, meta: String): String {
        val pairs = meta.split(";")
            .mapNotNull {
                val part = it.trim()
                if (!part.contains("=")) null else part
            }
            .associate {
                val idx = it.indexOf("=")
                it.substring(0, idx).trim() to it.substring(idx + 1).trim()
            }
        val failureClass = pairs["failureClass"].orEmpty()
        val endpoint = pairs["endpoint"].orEmpty()
        val transport = pairs["transport"].orEmpty()
        val network = pairs["network"].orEmpty()
        val reason = pairs["reason"].orEmpty()
        val failedCall = pairs["failedCall"].orEmpty()
        return listOf(type.trim(), failureClass, endpoint, transport, network, reason, failedCall).joinToString("|")
    }

    private fun shouldAggregateDebugType(type: String): Boolean {
        return when (type.trim()) {
            "feed_refresh_failed",
            "dashboard_refresh_degraded",
            "network_snapshot",
            "partial_day_reload_fallback",
            "refresh_circuit_open" -> true
            else -> false
        }
    }

    private fun parseIsoInstantMs(value: String): Long {
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    private fun currentSessionDebugRows(source: List<DebugLogEntry>): List<DebugLogEntry> {
        if (source.isEmpty()) return source
        val sessionStartIdx = source.indexOfLast { row ->
            row.type == "perf_event" && row.meta.contains("event=app_start")
        }
        return if (sessionStartIdx >= 0) source.drop(sessionStartIdx) else source
    }

    private fun appendAggregateFields(meta: String, entry: DebugLogEntry): String {
        val clean = meta.trim()
        val aggregatePart = "aggregateCount=${entry.aggregateCount};firstSeenAt=${entry.firstSeenAt};lastSeenAt=${entry.lastSeenAt}"
        return if (clean.isBlank()) aggregatePart else "$clean;$aggregatePart"
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
                        createdAt = obj.optString("createdAt", ""),
                        aggregateCount = obj.optInt("aggregateCount", 1).coerceAtLeast(1),
                        firstSeenAt = obj.optString("firstSeenAt", obj.optString("createdAt", "")),
                        lastSeenAt = obj.optString("lastSeenAt", obj.optString("createdAt", ""))
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
            obj.put("aggregateCount", item.aggregateCount.coerceAtLeast(1))
            obj.put("firstSeenAt", item.firstSeenAt.ifBlank { item.createdAt })
            obj.put("lastSeenAt", item.lastSeenAt.ifBlank { item.createdAt })
            arr.put(obj)
        }
        prefs.edit().putString(debugLogsPrefKey, arr.toString()).apply()
    }

    fun recentDebugLogs(limit: Int = 80): List<DebugLogEntry> =
        readDebugLogsInternal().takeLast(limit).reversed()

    fun logDetailedDebug(type: String, message: String, meta: String = "") {
        if (!debugMasterEnabled()) return
        logDebug(type, message, meta)
    }

    fun logFeedDebug(type: String, message: String, meta: String = "") {
        if (!feedDebugEnabled()) return
        logDebug(type, message, meta)
    }

    fun logDebug(type: String, message: String, meta: String = "") {
        val cleanType = type.trim().ifBlank { "unknown" }.take(32)
        val cleanMessage = message.trim().ifBlank { "unknown error" }.take(500)
        val cleanMeta = meta.trim().take(4000)
        val createdAt = OffsetDateTime.now().toString()
        val current = readDebugLogsInternal()
        val newEntry = DebugLogEntry(
            id = UUID.randomUUID().toString(),
            type = cleanType,
            message = cleanMessage,
            meta = cleanMeta,
            createdAt = createdAt,
            aggregateCount = 1,
            firstSeenAt = createdAt,
            lastSeenAt = createdAt
        )
        if (shouldAggregateDebugType(cleanType)) {
            val signature = aggregateMetaSignature(cleanType, cleanMeta)
            val nowMs = parseIsoInstantMs(createdAt)
            val idx = current.indexOfLast { existing ->
                existing.type == cleanType &&
                    aggregateMetaSignature(existing.type, existing.meta) == signature &&
                    (nowMs - parseIsoInstantMs(existing.lastSeenAt.ifBlank { existing.createdAt })) <= debugAggregateWindowMs
            }
            if (idx >= 0) {
                val existing = current[idx]
                current[idx] = existing.copy(
                    message = cleanMessage,
                    meta = cleanMeta,
                    aggregateCount = existing.aggregateCount + 1,
                    lastSeenAt = createdAt
                )
                writeDebugLogsInternal(current)
                return
            }
        }
        current.add(newEntry)
        writeDebugLogsInternal(current)
    }

    fun postDebugNotificationBurst() {
        PushMessagingService.showDebugTrackedNotificationBurst(context)
    }

    fun logNotificationDebugSnapshot(reason: String) {
        PushNotificationDiagnostics.recordEvent(
            context,
            type = "push_snapshot_manual",
            message = reason,
            meta = PushNotificationDiagnostics.activeNotificationsSnapshot(context)
        )
    }

    fun notificationDebugState(): NotificationDebugState = PushNotificationDiagnostics.readState(context)

    fun notificationDebugEnabled(): Boolean = PushNotificationDiagnostics.isEnabled(context)

    fun notificationDebugExpiresAt(): String = PushNotificationDiagnostics.expiresAt(context)

    fun setNotificationDebugEnabled(enabled: Boolean) {
        PushNotificationDiagnostics.setEnabled(context, enabled)
    }

    fun clearNotificationDebugData(keepMode: Boolean = true) {
        PushNotificationDiagnostics.clearStoredState(context, keepMode)
    }

    fun exportNotificationDebugBundle(): Uri {
        return PushNotificationDiagnostics.exportBundle(context)
    }

    fun refreshNotificationDebugEnvironment(reason: String = "manual_refresh") {
        PushNotificationDiagnostics.recordEnvironmentSnapshot(context, reason)
    }

    fun clearTrackedNotificationsForDebug(reason: String = "debug_tracked_only") {
        PushMessagingService.clearTrackedPushNotifications(context, reason = reason, aggressive = false)
    }

    fun clearAllNotificationsForDebug(reason: String = "debug_cancel_all") {
        PushMessagingService.clearAllNotifications(context, reason = reason)
    }

    fun clearTrackedAndAllNotificationsForDebug(reason: String = "debug_tracked_plus_all") {
        PushMessagingService.clearTrackedPushNotifications(context, reason = reason, aggressive = true)
    }

    fun postNotificationDebugScenario(scenarioId: String) {
        PushMessagingService.showDebugNotificationScenario(context, scenarioId)
    }

    fun exportDebugLogsForShare(): Uri {
        val exportDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(exportDir, "daily-diagnose-${System.currentTimeMillis()}.txt")
        val allRows = recentDebugLogs(400).reversed()
        val rows = currentSessionDebugRows(allRows)
        val families = linkedMapOf(
            "dns" to 0,
            "no_active_network" to 0,
            "ssl_handshake" to 0,
            "cert_path_validator" to 0
        )
        rows.forEach { row ->
            val lowerMeta = row.meta.lowercase()
            val family = when {
                lowerMeta.contains("failureclass=cert_path_validator") || lowerMeta.contains("network=cert_path_validator") -> "cert_path_validator"
                lowerMeta.contains("failureclass=ssl_handshake") || lowerMeta.contains("network=ssl_handshake") -> "ssl_handshake"
                lowerMeta.contains("failureclass=no_active_network") || lowerMeta.contains("network=no_active_network") || lowerMeta.contains("reason=no_active_network") -> "no_active_network"
                lowerMeta.contains("failureclass=dns") || lowerMeta.contains("network=dns") || lowerMeta.contains("unknownhostexception") -> "dns"
                else -> ""
            }
            if (family.isNotBlank()) {
                families[family] = (families[family] ?: 0) + row.aggregateCount.coerceAtLeast(1)
            }
        }
        val aggregateRows = rows.filter { it.aggregateCount > 1 }
        val rawRows = rows.filter { it.aggregateCount <= 1 }
        val lines = buildString {
            appendLine("Daily Diagnose Export")
            appendLine("Generated: ${OffsetDateTime.now()}")
            appendLine("App version: ${BuildConfig.VERSION_NAME}")
            appendLine("Device: ${currentDeviceName()}")
            appendLine("Debug master: ${debugMasterEnabled()}")
            appendLine("Feed debug: ${feedDebugEnabled()}")
            appendLine("Notification debug: ${notificationDebugEnabled()}")
            appendLine("Diagnostics upload: ${diagnosticsUploadEnabled()}")
            appendLine("Session rows: ${rows.size}")
            appendLine("Total rows kept locally: ${allRows.size}")
            appendLine("")
            appendLine("Zusammenfassung")
            appendLine("- DNS-Fehler: ${families["dns"] ?: 0}x")
            appendLine("- Keine aktive Verbindung: ${families["no_active_network"] ?: 0}x")
            appendLine("- TLS-Handshake: ${families["ssl_handshake"] ?: 0}x")
            appendLine("- Zertifikatspfad: ${families["cert_path_validator"] ?: 0}x")
            appendLine("")
            if (aggregateRows.isNotEmpty()) {
                appendLine("Verdichtete Wiederholungen")
                aggregateRows.forEach { row ->
                    appendLine("[${row.firstSeenAt} -> ${row.lastSeenAt}] ${row.type}: ${row.message} (${row.aggregateCount}x)")
                    if (row.meta.isNotBlank()) {
                        appendLine("meta: ${appendAggregateFields(row.meta, row)}")
                    }
                }
                appendLine("")
            }
            appendLine("Wichtige Einzelereignisse")
            rawRows.forEach { row ->
                appendLine("[${row.createdAt}] ${row.type}: ${row.message}")
                if (row.meta.isNotBlank()) appendLine("meta: ${appendAggregateFields(row.meta, row)}")
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
                            meta = appendAggregateFields(row.meta, row),
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

    fun feedOrderMode(): FeedOrderMode =
        runCatching { FeedOrderMode.valueOf((prefs.getString("feed_order_mode", FeedOrderMode.CHRONO.name) ?: FeedOrderMode.CHRONO.name).uppercase()) }
            .getOrDefault(FeedOrderMode.CHRONO)

    fun setFeedOrderMode(mode: FeedOrderMode) {
        prefs.edit().putString("feed_order_mode", mode.name).apply()
    }

    fun randomFeedSeed(): Long = prefs.getLong("random_feed_seed", 0L)

    fun setRandomFeedSeed(seed: Long) {
        prefs.edit().putLong("random_feed_seed", seed).apply()
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

    fun bookmarkedPhotoPushLocalEnabled(): Boolean = prefs.getBoolean("bookmarked_photo_push_enabled_local", false)

    fun setBookmarkedPhotoPushLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("bookmarked_photo_push_enabled_local", enabled).apply()
    }

    fun postChangePushLocalEnabled(): Boolean = prefs.getBoolean("post_change_push_enabled_local", false)

    fun setPostChangePushLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("post_change_push_enabled_local", enabled).apply()
    }

    fun autoSubscribeInteractedPostsLocalEnabled(): Boolean = prefs.getBoolean("auto_subscribe_interacted_posts_enabled_local", false)

    fun setAutoSubscribeInteractedPostsLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_subscribe_interacted_posts_enabled_local", enabled).apply()
    }

    fun ownPostNumberInPushLocalEnabled(): Boolean = prefs.getBoolean("own_post_number_in_push_enabled_local", false)

    fun setOwnPostNumberInPushLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("own_post_number_in_push_enabled_local", enabled).apply()
    }

    fun postNumberInPushLocalEnabled(): Boolean = prefs.getBoolean("post_number_in_push_enabled_local", false)

    fun setPostNumberInPushLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("post_number_in_push_enabled_local", enabled).apply()
    }

    fun yoloModeLocalEnabled(): Boolean = prefs.getBoolean("yolo_mode_enabled_local", false)

    fun setYoloModeLocalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("yolo_mode_enabled_local", enabled).apply()
    }

    fun appliedYoloFeatureIds(): Set<String> {
        val raw = prefs.getString("yolo_applied_feature_ids", "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun markYoloFeatureIdsApplied(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val merged = (appliedYoloFeatureIds() + ids.map { it.trim() }.filter { it.isNotBlank() }).toSortedSet()
        prefs.edit().putString("yolo_applied_feature_ids", merged.joinToString(",")).apply()
    }

    fun showPublicPostNumbers(): Boolean = prefs.getBoolean("show_public_post_numbers", false)

    fun setShowPublicPostNumbers(enabled: Boolean) {
        prefs.edit().putBoolean("show_public_post_numbers", enabled).apply()
    }

    fun preferSwipeForTwoImagePosts(): Boolean = prefs.getBoolean("prefer_swipe_for_two_image_posts", false)

    fun setPreferSwipeForTwoImagePosts(enabled: Boolean) {
        prefs.edit().putBoolean("prefer_swipe_for_two_image_posts", enabled).apply()
    }

    fun showConnectionHealthIndicator(): Boolean = prefs.getBoolean("show_connection_health_indicator", false)

    fun setShowConnectionHealthIndicator(enabled: Boolean) {
        prefs.edit().putBoolean("show_connection_health_indicator", enabled).apply()
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
        allowCommunityNsfwMarking: Boolean? = null,
        showNsfwByDefault: Boolean? = null,
        creativePostMode: String? = null,
        bookmarkedPhotoPushEnabled: Boolean? = null,
        postChangePushEnabled: Boolean? = null,
        autoSubscribeInteractedPostsEnabled: Boolean? = null,
        ownPostNumberInPushEnabled: Boolean? = null,
        postNumberInPushEnabled: Boolean? = null,
        yoloModeEnabled: Boolean? = null,
        specialMomentPushEnabled: Boolean? = null,
        locationFeatureEnabled: Boolean? = null,
        locationShareDefaultEnabled: Boolean? = null,
        diagnosticsConsentGranted: Boolean? = null,
        diagnosticsConsentSource: String? = null
    ): User {
        val response = authorizedCall("/api/me/preferences") { token -> api.updatePreferences(
            token,
            PreferencesUpdateRequest(
                chatPushEnabled = chatPushEnabled,
                pollPushEnabled = pollPushEnabled,
                inviteRegistrationPushEnabled = inviteRegistrationPushEnabled,
                photoReactionPushEnabled = photoReactionPushEnabled,
                photoCommentPushEnabled = photoCommentPushEnabled,
                bookmarkedPhotoPushEnabled = bookmarkedPhotoPushEnabled,
                postChangePushEnabled = postChangePushEnabled,
                autoSubscribeInteractedPostsEnabled = autoSubscribeInteractedPostsEnabled,
                ownPostNumberInPushEnabled = ownPostNumberInPushEnabled,
                postNumberInPushEnabled = postNumberInPushEnabled,
                yoloModeEnabled = yoloModeEnabled,
                allowPhotoDownload = allowPhotoDownload,
                allowCommunityNsfwMarking = allowCommunityNsfwMarking,
                showNsfwByDefault = showNsfwByDefault,
                creativePostMode = creativePostMode,
                specialMomentPushEnabled = specialMomentPushEnabled,
                locationFeatureEnabled = locationFeatureEnabled,
                locationShareDefaultEnabled = locationShareDefaultEnabled,
                diagnosticsConsentGranted = diagnosticsConsentGranted,
                diagnosticsConsentSource = diagnosticsConsentSource
            )
        ) }
        val user = response.user
        val mismatches = buildList {
            fun check(name: String, expected: Boolean?, actual: Boolean) {
                if (expected != null && expected != actual) {
                    add("$name:$expected->$actual")
                }
            }
            fun checkText(name: String, expected: String?, actual: String?) {
                if (expected != null && expected != actual) {
                    add("$name:${expected.ifBlank { "-" }}->${actual.orEmpty().ifBlank { "-" }}")
                }
            }
            check("chatPushEnabled", chatPushEnabled, user.chatPushEnabled)
            check("pollPushEnabled", pollPushEnabled, user.pollPushEnabled)
            check("inviteRegistrationPushEnabled", inviteRegistrationPushEnabled, user.inviteRegistrationPushEnabled)
            check("photoReactionPushEnabled", photoReactionPushEnabled, user.photoReactionPushEnabled)
            check("photoCommentPushEnabled", photoCommentPushEnabled, user.photoCommentPushEnabled)
            check("allowPhotoDownload", allowPhotoDownload, user.allowPhotoDownload)
            check("allowCommunityNsfwMarking", allowCommunityNsfwMarking, user.allowCommunityNsfwMarking)
            check("showNsfwByDefault", showNsfwByDefault, user.showNsfwByDefault)
            checkText("creativePostMode", creativePostMode, user.creativePostMode)
            check("bookmarkedPhotoPushEnabled", bookmarkedPhotoPushEnabled, user.bookmarkedPhotoPushEnabled)
            check("postChangePushEnabled", postChangePushEnabled, user.postChangePushEnabled)
            check("autoSubscribeInteractedPostsEnabled", autoSubscribeInteractedPostsEnabled, user.autoSubscribeInteractedPostsEnabled)
            check("ownPostNumberInPushEnabled", ownPostNumberInPushEnabled, user.ownPostNumberInPushEnabled)
            check("postNumberInPushEnabled", postNumberInPushEnabled, user.postNumberInPushEnabled)
            check("yoloModeEnabled", yoloModeEnabled, user.yoloModeEnabled)
            check("specialMomentPushEnabled", specialMomentPushEnabled, user.specialMomentPushEnabled)
            check("locationFeatureEnabled", locationFeatureEnabled, user.locationFeatureEnabled)
            check("locationShareDefaultEnabled", locationShareDefaultEnabled, user.locationShareDefaultEnabled)
            check("diagnosticsConsentGranted", diagnosticsConsentGranted, user.diagnosticsConsentGranted)
        }
        if (mismatches.isNotEmpty()) {
            logDebug(
                type = "preference_sync_conflict",
                message = "server returned different preference values",
                meta = "mismatches=${mismatches.joinToString(",")};consentSource=${diagnosticsConsentSource ?: "-"}"
            )
        }
        return user
    }

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
    suspend fun feedWindow(
        anchorDay: String,
        beforeDays: Int = 2,
        afterDays: Int = 2,
        focusPhotoId: Long? = null
    ): FeedWindowResponse = authorizedCall("/api/feed/window") { token ->
        api.feedWindow(token, anchorDay, beforeDays, afterDays, focusPhotoId)
    }
    suspend fun feedDiscover(
        mode: FeedOrderMode,
        offset: Int = 0,
        limitDays: Int = 7,
        randomSeed: Long? = null,
        anchorDay: String? = null,
        focusPhotoId: Long? = null
    ): FeedWindowResponse = authorizedCall("/api/feed/discover") { token ->
        api.feedDiscover(token, mode.name.lowercase(), offset, limitDays, randomSeed, anchorDay, focusPhotoId)
    }
    suspend fun feedDays(
        from: String? = null,
        to: String? = null,
        beforeDay: String? = null,
        afterDay: String? = null,
        anchorDay: String? = null,
        limit: Int? = null
    ): DayListResponse =
        authorizedCall("/api/feed/days") { token ->
            api.feedDays(token, from, to, beforeDay, afterDay, anchorDay, limit)
        }
    suspend fun feedDayStats(from: String? = null, to: String? = null): List<DayStatItem> =
        authorizedCall("/api/feed/day-stats") { token -> api.feedDayStats(token, from, to).items }
    suspend fun calendarPublic(): CalendarPayloadResponse =
        authorizedCall("/api/calendar/public") { token -> api.calendarPublic(token) }
    suspend fun calendarBookmarks(scope: BookmarkCalendarFilter = BookmarkCalendarFilter.MINE): CalendarPayloadResponse =
        authorizedCall("/api/calendar/bookmarks") { token ->
            api.calendarBookmarks(
                token,
                when (scope) {
                    BookmarkCalendarFilter.MINE -> "mine"
                    BookmarkCalendarFilter.ALL -> "all"
                }
            )
        }
    suspend fun calendarTimeCapsules(): CalendarPayloadResponse =
        authorizedCall("/api/calendar/time-capsules") { token -> api.calendarTimeCapsules(token) }
    suspend fun calendarSearch(query: String): CalendarSearchResponse =
        authorizedCall("/api/calendar/search") { token -> api.calendarSearch(token, query) }
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

    suspend fun bookmarkPhoto(photoId: Long): PromptPhoto? =
        authorizedCall("/api/photos/:id/bookmark") { token -> api.bookmarkPhoto(token, photoId) }.photo

    suspend fun unbookmarkPhoto(photoId: Long): PromptPhoto? =
        authorizedCall("/api/photos/:id/bookmark") { token -> api.unbookmarkPhoto(token, photoId) }.photo

    suspend fun markPhoto(photoId: Long): PromptPhoto? =
        authorizedCall("/api/photos/:id/mark") { token -> api.markPhoto(token, photoId) }.photo

    suspend fun unmarkPhoto(photoId: Long, targetUserId: Long? = null): PromptPhoto? =
        authorizedCall("/api/photos/:id/mark") { token -> api.unmarkPhoto(token, photoId, targetUserId) }.photo

    suspend fun savePhotoPaint(
        photoId: Long,
        paths: List<PhotoPaintPath>,
        strokeWidth: Float
    ): PromptPhoto? =
        authorizedCall("/api/photos/:id/paint") { token ->
            api.savePhotoPaint(token, photoId, PhotoPaintRequest(paths = paths, strokeWidth = strokeWidth, surface = "frame"))
        }.photo

    suspend fun deletePhotoPaint(photoId: Long, targetUserId: Long? = null): PromptPhoto? =
        authorizedCall("/api/photos/:id/paint") { token -> api.deletePhotoPaint(token, photoId, targetUserId) }.photo

    suspend fun clearBookmarks(): Int =
        authorizedCall("/api/photos/bookmarks") { token -> api.clearBookmarks(token) }.deletedCount

    suspend fun reportPhoto(photoId: Long): PhotoReportResponse =
        authorizedCall("/api/photos/:id/report") { token -> api.reportPhoto(token, photoId) }

    suspend fun markPhotoNsfw(photoId: Long): PromptPhoto? =
        authorizedCall("/api/photos/:id/nsfw") { token -> api.markPhotoNsfw(token, photoId) }.photo

    suspend fun unmarkPhotoNsfw(photoId: Long): PromptPhoto? =
        authorizedCall("/api/photos/:id/nsfw") { token -> api.unmarkPhotoNsfw(token, photoId) }.photo

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
        if (token().isBlank()) {
            return
        }
        val pending = prefs.getString("pending_fcm_token", "") ?: ""
        val recentSync = (System.currentTimeMillis() - lastSyncedDeviceTokenAt()) < 6 * 60 * 60 * 1000L
        if (!force && pending.isBlank() && recentSync && lastSyncedDeviceToken().isNotBlank()) {
            return
        }
        val firebaseAttempt = runCatching { FirebaseMessaging.getInstance().token.await() }
        firebaseAttempt.exceptionOrNull()?.let {
            if (isBenignCancellationShared(it)) {
                return
            }
            val failureKind = debugNetworkFailureKindShared(it) ?: it::class.java.simpleName
            logDebug(
                type = "push_token_fetch_failed",
                message = failureKind,
                meta = "force=$force;failureClass=${it::class.java.simpleName};root=${debugRootCauseShared(it)::class.java.simpleName}"
            )
        }
        val fromFirebase = firebaseAttempt.getOrNull().orEmpty()
        val deviceToken = if (pending.isNotBlank()) pending else fromFirebase
        val source = when {
            pending.isNotBlank() -> "pending"
            fromFirebase.isNotBlank() -> "firebase"
            else -> "none"
        }
        if (deviceToken.isBlank()) {
            logDebug(
                type = "push_token_sync_skipped",
                message = "push token sync skipped without device token",
                meta = "force=$force;source=$source;pendingPresent=${pending.isNotBlank()}"
            )
            return
        }
        val sameToken = deviceToken == lastSyncedDeviceToken()
        val tokenFingerprint = "${deviceToken.length}:${deviceToken.hashCode().toUInt().toString(16)}"
        if (!force && sameToken && recentSync) {
            return
        }

        runCatching {
            authorizedCall("/api/devices") { token -> api.registerDevice(token, DeviceTokenRequest(deviceToken, currentDeviceName())) }
        }.onSuccess {
            logDebug(
                type = "push_token_synced",
                message = "push device token synced",
                meta = "force=$force;source=$source;token=$tokenFingerprint;pendingCleared=${pending.isNotBlank()}"
            )
        }.onFailure {
            val failureKind = debugNetworkFailureKindShared(it) ?: it::class.java.simpleName
            logDebug(
                type = "push_token_sync_failed",
                message = failureKind,
                meta = "force=$force;source=$source;token=$tokenFingerprint;failureClass=${it::class.java.simpleName};root=${debugRootCauseShared(it)::class.java.simpleName}"
            )
            throw it
        }
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

    suspend fun measureUploadTelemetryProbe(): UploadTelemetryProbe {
        val snapshot = networkSnapshotMeta()
        val stable = snapshot.contains("activeNetwork=true") &&
            snapshot.contains("internet=true") &&
            snapshot.contains("validated=true")
        val startedAt = System.currentTimeMillis()
        return runCatching {
            api.health()
            val pingMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            UploadTelemetryProbe(
                pingMs = pingMs,
                pingFailure = "",
                networkSnapshot = snapshot,
                networkStable = stable
            )
        }.getOrElse {
            UploadTelemetryProbe(
                pingMs = null,
                pingFailure = debugNetworkFailureKindShared(it) ?: it::class.java.simpleName,
                networkSnapshot = snapshot,
                networkStable = stable
            )
        }
    }

    suspend fun upload(
        uri: Uri,
        isPrompt: Boolean,
        shareLocation: Boolean = false,
        capsule: CapsuleUploadOptions = CapsuleUploadOptions()
    ) {
        val capturedAt = readCapturedAtFromUri(uri)
        val file = copyUriToTemp(uri)
        val bytesTotal = file.length().coerceAtLeast(1L)
        val uploadClientId = UUID.randomUUID().toString()
        val probe = measureUploadTelemetryProbe()
        var awaitingAckLogged = false
        val part = MultipartBody.Part.createFormData(
            "photo",
            file.name,
            ProgressRequestBody(file.asRequestBody("image/*".toMediaTypeOrNull())) { sent, total ->
                if (!awaitingAckLogged && sent >= total) {
                    awaitingAckLogged = true
                    logDebug(
                        type = "upload_direct_server_ack_pending",
                        message = "Upload gesendet, warte auf Bestaetigung.",
                        meta = buildString {
                            append("source=direct")
                            append(";kind=").append(if (isPrompt) "prompt" else "extra")
                            append(";uploadClientId=").append(uploadClientId)
                            append(";bytesTotal=").append(bytesTotal)
                            append(";bytesSent=").append(sent.coerceAtMost(total))
                            append(";networkStable=").append(probe.networkStable)
                            if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
                            if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
                            if (capturedAt != null) append(";capturedAt=").append(capturedAt)
                            append(";").append(probe.networkSnapshot)
                        }
                    )
                }
            }
        )
        val kind = (if (isPrompt) "prompt" else "extra").toRequestBody("text/plain".toMediaTypeOrNull())
        val capturedAtPart = capturedAt?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
        val uploadClientIdPart = uploadClientId.toRequestBody("text/plain".toMediaTypeOrNull())
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
        val startedAt = System.currentTimeMillis()
        logDebug(
            type = "upload_direct_started",
            message = "Direkter Upload gestartet.",
            meta = buildString {
                append("source=direct")
                append(";kind=").append(if (isPrompt) "prompt" else "extra")
                append(";uploadClientId=").append(uploadClientId)
                append(";bytesTotal=").append(bytesTotal)
                append(";networkStable=").append(probe.networkStable)
                if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
                if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
                if (capturedAt != null) append(";capturedAt=").append(capturedAt)
                append(";").append(probe.networkSnapshot)
            }
        )
        try {
            authorizedCall("/api/uploads") { token ->
                api.upload(token, part, kind, capturedAtPart, uploadClientIdPart, capsuleMode, capsulePrivate, capsuleGroup, locationShared, latitude, longitude)
            }
            logDebug(
                type = "upload_direct_succeeded",
                message = "Upload erfolgreich bestaetigt.",
                meta = buildString {
                    append("source=direct")
                    append(";kind=").append(if (isPrompt) "prompt" else "extra")
                    append(";uploadClientId=").append(uploadClientId)
                    append(";bytesTotal=").append(bytesTotal)
                    append(";durationMs=").append((System.currentTimeMillis() - startedAt).coerceAtLeast(0L))
                    append(";http=200")
                    append(";networkStable=").append(probe.networkStable)
                    if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
                    if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
                    if (capturedAt != null) append(";capturedAt=").append(capturedAt)
                    append(";").append(probe.networkSnapshot)
                }
            )
        } catch (t: Throwable) {
            val failureClass = debugNetworkFailureKindShared(t) ?: t::class.java.simpleName
            val advice = securityAdviceForFailure(failureClass)
            logDebug(
                type = "upload_direct_failed",
                message = when (failureClass) {
                    "dns" -> "Servername konnte nicht aufgeloest werden."
                    "no_active_network" -> "Keine aktive Internetverbindung verfuegbar."
                    "connect" -> "Keine stabile Verbindung."
                    "timeout" -> "Server antwortet zu langsam."
                    "ssl_handshake" -> "Sichere Verbindung fehlgeschlagen."
                    "cert_path_validator" -> "Dieses Netzwerk vertraut dem Daily-Zertifikat nicht oder veraendert die Verbindung."
                    "ssl_other" -> "Sichere Verbindung fehlgeschlagen."
                    else -> t.message ?: "Upload fehlgeschlagen."
                },
                meta = buildString {
                    append("source=direct")
                    append(";kind=").append(if (isPrompt) "prompt" else "extra")
                    append(";uploadClientId=").append(uploadClientId)
                    append(";bytesTotal=").append(bytesTotal)
                    append(";durationMs=").append((System.currentTimeMillis() - startedAt).coerceAtLeast(0L))
                    append(";failureClass=").append(failureClass)
                    append(";securityFailureClass=").append(failureClass)
                    append(";networkStateClass=").append(if (probe.networkStable) "stable" else "unstable")
                    append(";retrySuppressedReason=").append("-")
                    append(";userAdviceShown=").append(advice.isNotBlank())
                    val http = (t as? HttpException)?.code() ?: -1
                    append(";http=").append(http)
                    append(";networkStable=").append(probe.networkStable)
                    if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
                    if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
                    if (capturedAt != null) append(";capturedAt=").append(capturedAt)
                    append(";").append(debugThrowableMetaShared(t))
                    append(";").append(probe.networkSnapshot)
                }
            )
            throw t
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
        val backCapturedAt = readCapturedAtFromUri(backUri)
        val frontCapturedAt = readCapturedAtFromUri(frontUri)
        val capturedAt = earliestCapturedAt(backCapturedAt, frontCapturedAt)
        val backFile = copyUriToTemp(backUri)
        val frontFile = copyUriToTemp(frontUri)
        val totalBytes = (backFile.length() + frontFile.length()).coerceAtLeast(1L)
        val uploadClientId = UUID.randomUUID().toString()
        val probe = measureUploadTelemetryProbe()
        var backSent = 0L
        var frontSent = 0L
        var awaitingAckLogged = false
        fun emit() = onProgress((backSent + frontSent).coerceAtMost(totalBytes), totalBytes)

        val backBody = ProgressRequestBody(
            delegate = backFile.asRequestBody("image/*".toMediaTypeOrNull())
        ) { sent, _ ->
            backSent = sent
            if (!awaitingAckLogged && (backSent + frontSent) >= totalBytes) {
                awaitingAckLogged = true
                logDebug(
                    type = "upload_direct_server_ack_pending",
                    message = "Upload gesendet, warte auf Bestaetigung.",
                    meta = buildString {
                        append("source=direct")
                        append(";kind=").append(if (isPrompt) "prompt" else "extra")
                        append(";uploadClientId=").append(uploadClientId)
                        append(";bytesTotal=").append(totalBytes)
                        append(";bytesSent=").append((backSent + frontSent).coerceAtMost(totalBytes))
                        append(";networkStable=").append(probe.networkStable)
                        if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
                        if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
                        if (capturedAt != null) append(";capturedAt=").append(capturedAt)
                        append(";").append(probe.networkSnapshot)
                    }
                )
            }
            emit()
        }
        val frontBody = ProgressRequestBody(
            delegate = frontFile.asRequestBody("image/*".toMediaTypeOrNull())
        ) { sent, _ ->
            frontSent = sent
            if (!awaitingAckLogged && (backSent + frontSent) >= totalBytes) {
                awaitingAckLogged = true
                logDebug(
                    type = "upload_direct_server_ack_pending",
                    message = "Upload gesendet, warte auf Bestaetigung.",
                    meta = buildString {
                        append("source=direct")
                        append(";kind=").append(if (isPrompt) "prompt" else "extra")
                        append(";uploadClientId=").append(uploadClientId)
                        append(";bytesTotal=").append(totalBytes)
                        append(";bytesSent=").append((backSent + frontSent).coerceAtMost(totalBytes))
                        append(";networkStable=").append(probe.networkStable)
                        if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
                        if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
                        if (capturedAt != null) append(";capturedAt=").append(capturedAt)
                        append(";").append(probe.networkSnapshot)
                    }
                )
            }
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
        val capturedAtPart = capturedAt?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
        val uploadClientIdPart = uploadClientId.toRequestBody("text/plain".toMediaTypeOrNull())
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
        val startedAt = System.currentTimeMillis()
        logDebug(
            type = "upload_direct_started",
            message = "Direkter Upload gestartet.",
            meta = buildString {
                append("source=direct")
                append(";kind=").append(if (isPrompt) "prompt" else "extra")
                append(";uploadClientId=").append(uploadClientId)
                append(";bytesTotal=").append(totalBytes)
                append(";networkStable=").append(probe.networkStable)
                if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
                if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
                if (capturedAt != null) append(";capturedAt=").append(capturedAt)
                append(";").append(probe.networkSnapshot)
            }
        )
        emit()
        try {
            authorizedCall("/api/uploads/dual") { token ->
                api.uploadDual(token, backPart, frontPart, kind, capturedAtPart, uploadClientIdPart, capsuleMode, capsulePrivate, capsuleGroup, locationShared, latitude, longitude)
            }
            logDebug(
                type = "upload_direct_succeeded",
                message = "Upload erfolgreich bestaetigt.",
                meta = buildString {
                    append("source=direct")
                    append(";kind=").append(if (isPrompt) "prompt" else "extra")
                    append(";uploadClientId=").append(uploadClientId)
                    append(";bytesTotal=").append(totalBytes)
                    append(";durationMs=").append((System.currentTimeMillis() - startedAt).coerceAtLeast(0L))
                    append(";http=200")
                    append(";networkStable=").append(probe.networkStable)
                    if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
                    if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
                    if (capturedAt != null) append(";capturedAt=").append(capturedAt)
                    append(";").append(probe.networkSnapshot)
                }
            )
        } catch (t: Throwable) {
            val failureClass = debugNetworkFailureKindShared(t) ?: t::class.java.simpleName
            val advice = securityAdviceForFailure(failureClass)
            logDebug(
                type = "upload_direct_failed",
                message = when (failureClass) {
                    "dns" -> "Servername konnte nicht aufgeloest werden."
                    "no_active_network" -> "Keine aktive Internetverbindung verfuegbar."
                    "connect" -> "Keine stabile Verbindung."
                    "timeout" -> "Server antwortet zu langsam."
                    "ssl_handshake" -> "Sichere Verbindung fehlgeschlagen."
                    "cert_path_validator" -> "Dieses Netzwerk vertraut dem Daily-Zertifikat nicht oder veraendert die Verbindung."
                    "ssl_other" -> "Sichere Verbindung fehlgeschlagen."
                    else -> t.message ?: "Upload fehlgeschlagen."
                },
                meta = buildString {
                    append("source=direct")
                    append(";kind=").append(if (isPrompt) "prompt" else "extra")
                    append(";uploadClientId=").append(uploadClientId)
                    append(";bytesTotal=").append(totalBytes)
                    append(";durationMs=").append((System.currentTimeMillis() - startedAt).coerceAtLeast(0L))
                    append(";failureClass=").append(failureClass)
                    append(";securityFailureClass=").append(failureClass)
                    append(";networkStateClass=").append(if (probe.networkStable) "stable" else "unstable")
                    append(";retrySuppressedReason=").append("-")
                    append(";userAdviceShown=").append(advice.isNotBlank())
                    val http = (t as? HttpException)?.code() ?: -1
                    append(";http=").append(http)
                    append(";networkStable=").append(probe.networkStable)
                    if (probe.pingMs != null) append(";pingMs=").append(probe.pingMs)
                    if (probe.pingFailure.isNotBlank()) append(";pingFailure=").append(probe.pingFailure)
                    if (capturedAt != null) append(";capturedAt=").append(capturedAt)
                    append(";").append(debugThrowableMetaShared(t))
                    append(";").append(probe.networkSnapshot)
                }
            )
            throw t
        }
        onProgress(totalBytes, totalBytes)
    }

    suspend fun appendPhotoToPost(
        photoId: Long,
        uri: Uri,
        shareLocation: Boolean = false,
        capturedAtOverride: OffsetDateTime? = null,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): PromptPhoto? {
        val capturedAt = capturedAtOverride ?: readCapturedAtFromUri(uri)
        val file = copyUriToTemp(uri)
        val bytesTotal = file.length().coerceAtLeast(1L)
        val uploadClientId = UUID.randomUUID().toString()
        val probe = measureUploadTelemetryProbe()
        var awaitingAckLogged = false
        val part = MultipartBody.Part.createFormData(
            "photo",
            file.name,
            ProgressRequestBody(file.asRequestBody("image/*".toMediaTypeOrNull())) { sent, total ->
                onProgress(sent.coerceAtMost(total), total)
                if (!awaitingAckLogged && sent >= total) {
                    awaitingAckLogged = true
                    logDebug(
                        type = "upload_direct_server_ack_pending",
                        message = "Anhang gesendet, warte auf Bestaetigung.",
                        meta = buildString {
                            append("source=direct")
                            append(";kind=append")
                            append(";targetPhotoId=").append(photoId)
                            append(";uploadClientId=").append(uploadClientId)
                            append(";bytesTotal=").append(bytesTotal)
                            append(";networkStable=").append(probe.networkStable)
                            append(";").append(probe.networkSnapshot)
                        }
                    )
                }
            }
        )
        val capturedAtPart = capturedAt?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
        val uploadClientIdPart = uploadClientId.toRequestBody("text/plain".toMediaTypeOrNull())
        val locationPayload = if (shareLocation) lastAvailableLocationPayload() else null
        val locationShared = locationPayload?.let { "true".toRequestBody("text/plain".toMediaTypeOrNull()) }
        val latitude = locationPayload?.latitude?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
        val longitude = locationPayload?.longitude?.toString()?.toRequestBody("text/plain".toMediaTypeOrNull())
        val response = authorizedCall("/api/photos/:id/attachments") { token ->
            api.appendPhotoAttachment(token, photoId, part, capturedAtPart, uploadClientIdPart, locationShared, latitude, longitude)
        }
        onProgress(bytesTotal, bytesTotal)
        return response.photo
    }

    suspend fun enqueueDualUpload(
        backUri: Uri,
        frontUri: Uri,
        isPrompt: Boolean,
        shareLocation: Boolean = false,
        capsule: CapsuleUploadOptions = CapsuleUploadOptions()
    ): QueuedUploadItem {
        val backCapturedAt = readCapturedAtFromUri(backUri)
        val frontCapturedAt = readCapturedAtFromUri(frontUri)
        val capturedAt = earliestCapturedAt(backCapturedAt, frontCapturedAt)
        val backFile = copyUriToTemp(backUri)
        val frontFile = copyUriToTemp(frontUri)
        val queuedDir = File(context.filesDir, "upload-queue").apply { mkdirs() }
        val backQueued = moveToQueueFile(backFile, queuedDir, "back")
        val frontQueued = moveToQueueFile(frontFile, queuedDir, "front")
        val uploadClientId = UUID.randomUUID().toString()
        val locationPayload = if (shareLocation) lastAvailableLocationPayload() else null
        if (shareLocation && locationPayload == null) {
            logDebug("location_queue_skipped", "no device location available", "endpoint=/api/uploads/dual")
        }
        val queuedItem = UploadQueueManager.enqueueFromFiles(
            context = context,
            backPath = backQueued.absolutePath,
            frontPath = frontQueued.absolutePath,
            uploadClientId = uploadClientId,
            isPrompt = isPrompt,
            capsuleMode = capsule.mode,
            capsulePrivate = capsule.privateOnly,
            capsuleGroupRemind = capsule.groupRemind,
            locationShared = locationPayload != null,
            locationLatitude = locationPayload?.latitude,
            locationLongitude = locationPayload?.longitude,
            capturedAtMs = capturedAt?.toInstant()?.toEpochMilli() ?: 0L
        )
        logDebug(
            type = "upload_queue_enqueued",
            message = "Upload in Warteschlange aufgenommen.",
            meta = buildString {
                append("source=queue")
                append(";kind=").append(if (isPrompt) "prompt" else "extra")
                append(";uploadClientId=").append(uploadClientId)
                append(";queueItemId=").append(queuedItem.id)
                append(";bytesTotal=").append((backQueued.length() + frontQueued.length()).coerceAtLeast(1L))
                append(";queuedAt=").append(OffsetDateTime.ofInstant(Instant.ofEpochMilli(queuedItem.createdAtMs), ZoneId.systemDefault()))
                append(";networkStable=").append(networkSnapshotMeta().contains("validated=true"))
                if (capturedAt != null) append(";capturedAt=").append(capturedAt)
                append(";").append(networkSnapshotMeta())
            }
        )
        return queuedItem
    }

    suspend fun enqueuePhotoAttachmentUpload(
        photoId: Long,
        uri: Uri,
        shareLocation: Boolean = false
    ): QueuedUploadItem {
        val capturedAt = readCapturedAtFromUri(uri)
        val file = copyUriToTemp(uri)
        val queuedDir = File(context.filesDir, "upload-queue").apply { mkdirs() }
        val queuedFile = moveToQueueFile(file, queuedDir, "attachment")
        val uploadClientId = UUID.randomUUID().toString()
        val locationPayload = if (shareLocation) lastAvailableLocationPayload() else null
        return UploadQueueManager.enqueueAttachmentFromFile(
            context = context,
            filePath = queuedFile.absolutePath,
            uploadClientId = uploadClientId,
            appendTargetPhotoId = photoId,
            locationShared = locationPayload != null,
            locationLatitude = locationPayload?.latitude,
            locationLongitude = locationPayload?.longitude,
            capturedAtMs = capturedAt?.toInstant()?.toEpochMilli() ?: 0L
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

    private fun readCapturedAtFromUri(uri: Uri): OffsetDateTime? {
        val resolver = context.contentResolver
        val exifValue = resolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            exifDateTimeOriginal(exif)
        }
        if (exifValue != null) {
            return exifValue
        }

        val projection = arrayOf(
            MediaStore.Images.ImageColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        runCatching {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val takenIdx = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN)
                    if (takenIdx >= 0) {
                        val taken = cursor.getLong(takenIdx)
                        if (taken > 0L) {
                            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(taken), ZoneId.systemDefault())
                        }
                    }
                    val modifiedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    if (modifiedIdx >= 0) {
                        val modified = cursor.getLong(modifiedIdx)
                        if (modified > 0L) {
                            return OffsetDateTime.ofInstant(Instant.ofEpochSecond(modified), ZoneId.systemDefault())
                        }
                    }
                }
            }
        }

        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (file.exists() && file.lastModified() > 0L) {
                    return OffsetDateTime.ofInstant(Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault())
                }
            }
        }
        return null
    }

    private fun exifDateTimeOriginal(exif: ExifInterface): OffsetDateTime? {
        val value = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.trim().orEmpty()
        if (value.isBlank()) return null
        val local = runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US))
        }.getOrNull() ?: return null
        val offsetText = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.trim().orEmpty()
        val offset = runCatching { ZoneOffset.of(offsetText) }.getOrNull()
        return if (offset != null) {
            OffsetDateTime.of(local, offset)
        } else {
            local.atZone(ZoneId.systemDefault()).toOffsetDateTime()
        }
    }

    private fun earliestCapturedAt(first: OffsetDateTime?, second: OffsetDateTime?): OffsetDateTime? {
        return when {
            first == null -> second
            second == null -> first
            first.isBefore(second) -> first
            else -> second
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

data class CalendarDataset(
    val days: List<String> = emptyList(),
    val dayStats: Map<String, DayStatItem> = emptyMap(),
    val photosByDay: Map<String, List<CalendarPhotoItem>> = emptyMap(),
    val feedItems: List<FeedItem> = emptyList(),
    val lockedCount: Int = 0,
    val releasedCount: Int = 0
)

data class CalendarSearchDataset(
    val query: String = "",
    val normalizedQuery: String = "",
    val dataset: CalendarDataset = CalendarDataset(),
    val matchesByDay: Map<String, List<CalendarSearchMatchItem>> = emptyMap()
) {
    val flatMatches: List<CalendarSearchMatchItem>
        get() = dataset.days.flatMap { day -> matchesByDay[day].orEmpty() }
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
    val feedOrderMode: FeedOrderMode = FeedOrderMode.CHRONO,
    val randomFeedSeed: Long = 0L,
    val feedDiscoverOffset: Int = 0,
    val feedDiscoverNextOffset: Int = 0,
    val calendarDays: List<String> = emptyList(),
    val feedIndexHasOlder: Boolean = true,
    val feedIndexHasNewer: Boolean = false,
    val calendarDayStats: Map<String, DayStatItem> = emptyMap(),
    val calendarMode: CalendarMode = CalendarMode.PUBLIC,
    val calendarPickerExpanded: Boolean = false,
    val calendarSelectedDay: String? = null,
    val calendarPublicData: CalendarDataset = CalendarDataset(),
    val calendarBookmarksData: CalendarDataset = CalendarDataset(),
    val calendarTimeCapsulesData: CalendarDataset = CalendarDataset(),
    val calendarSearchData: CalendarSearchDataset = CalendarSearchDataset(),
    val calendarSearchQuery: String = "",
    val calendarBookmarksFilter: BookmarkCalendarFilter = BookmarkCalendarFilter.MINE,
    val calendarTimeCapsuleFilter: TimeCapsuleFilter = TimeCapsuleFilter.ALL,
    val calendarLoading: Boolean = false,
    val communityStats: CommunityStatsResponse? = null,
    val communityStatsLoading: Boolean = false,
    val feedFocusDay: String? = null,
    val feedFocusPhotoId: Long? = null,
    val feedFocusBoundary: FeedJumpBoundary? = null,
    val feedVisibleAnchorDay: String? = null,
    val feedViewportAnchor: FeedViewportAnchor = FeedViewportAnchor(),
    val feedScrollRequestId: Long = 0L,
    val feedViewportRestoreAnchor: FeedViewportAnchor = FeedViewportAnchor(),
    val feedViewportRestoreRequestId: Long = 0L,
    val feedPaging: Boolean = false,
    val feedRefreshing: Boolean = false,
    val feedWindowReloadInFlight: Boolean = false,
    val feedTodayLocked: Boolean = false,
    val feedJumpLoadingDay: String? = null,
    val chatHasOtherMessages: Boolean = true,
    val chatHasUnreadMessages: Boolean = false,
    val photos: List<PromptPhoto> = emptyList(),
    val streakDays: Int = 0,
    val dailyMomentCount: Int = 0,
    val bookmarksGivenCount: Int = 0,
    val bookmarksReceivedCount: Int = 0,
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
    val bookmarkedPhotoPushEnabled: Boolean = false,
    val postChangePushEnabled: Boolean = false,
    val autoSubscribeInteractedPostsEnabled: Boolean = false,
    val ownPostNumberInPushEnabled: Boolean = false,
    val postNumberInPushEnabled: Boolean = false,
    val yoloModeEnabled: Boolean = false,
    val allowCommunityNsfwMarking: Boolean = false,
    val showNsfwByDefault: Boolean = false,
    val locationFeatureEnabled: Boolean = false,
    val locationShareDefaultEnabled: Boolean = false,
    val showPublicPostNumbers: Boolean = false,
    val preferSwipeForTwoImagePosts: Boolean = false,
    val showConnectionHealthIndicator: Boolean = false,
    val connectionHealthSnapshot: ConnectionHealthSnapshot = ConnectionHealthSnapshot(),
    val lastApiSuccessAtMs: Long = 0L,
    val lastApiFailureAtMs: Long = 0L,
    val lastApiFailureMessage: String = "",
    val networkSnapshot: String = "activeNetwork=false;capabilities=false;reason=not_checked",
    val customNotificationToneEnabled: Boolean = false,
    val customNotificationToneUri: String = "",
    val debugMasterEnabled: Boolean = false,
    val feedDebugEnabled: Boolean = false,
    val diagnosticsUploadEnabled: Boolean = false,
    val diagnosticsConsentGranted: Boolean = false,
    val diagnosticsConsentUpdatedAt: String? = null,
    val showDiagnosticsConsentDialog: Boolean = false,
    val diagnosticsConsentPrompt: UserPromptRule? = null,
    val debugLogs: List<DebugLogEntry> = emptyList(),
    val notificationDebugEnabled: Boolean = false,
    val notificationDebugExpiresAt: String = "",
    val notificationDebugEvents: List<NotificationDebugEvent> = emptyList(),
    val notificationDebugLaunches: List<NotificationDebugLaunch> = emptyList(),
    val notificationDebugPayloads: List<NotificationDebugPayload> = emptyList(),
    val notificationDebugActiveItems: List<NotificationDebugActiveItem> = emptyList(),
    val feedHasHiddenNewerContent: Boolean = false,
    val feedHiddenNewerAnchorDay: String? = null,
    val notificationDebugEnvironment: NotificationDebugEnvironment = NotificationDebugEnvironment(
        notificationsEnabled = true,
        postPermissionGranted = true,
        activeCount = 0,
        manufacturer = "",
        model = "",
        sdkInt = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE.orEmpty(),
        channels = emptyList()
    ),
    val networkAdvice: String = "",
    val fotomojiTemplates: List<FotomojiTemplateItem> = emptyList(),
    val fotomojiTemplatesLoading: Boolean = false,
    val profileSectionExpanded: Map<String, Boolean> = emptyMap()
)

data class DashboardData(
    val me: User,
    val streakDays: Int,
    val dailyMomentCount: Int,
    val bookmarksGivenCount: Int,
    val bookmarksReceivedCount: Int,
    val inviteCode: String,
    val prompt: PromptResponse,
    val rules: PromptRulesResponse,
    val special: SpecialMomentStatus,
    val photos: List<PromptPhoto>,
    val chat: List<ChatItem>,
    val feedDays: List<String>,
    val communityStats: CommunityStatsResponse?
)

private data class YoloPreferenceState(
    var autoUpdateEnabled: Boolean,
    var feedPostPushEnabled: Boolean,
    var useFotomojiReactions: Boolean,
    var showPublicPostNumbers: Boolean,
    var preferSwipeForTwoImagePosts: Boolean,
    var chatPushEnabled: Boolean,
    var pollPushEnabled: Boolean,
    var specialMomentPushEnabled: Boolean,
    var inviteRegistrationPushEnabled: Boolean,
    var photoReactionPushEnabled: Boolean,
    var photoCommentPushEnabled: Boolean,
    var bookmarkedPhotoPushEnabled: Boolean,
    var postChangePushEnabled: Boolean,
    var autoSubscribeInteractedPostsEnabled: Boolean,
    var ownPostNumberInPushEnabled: Boolean,
    var postNumberInPushEnabled: Boolean,
    var yoloModeEnabled: Boolean,
    var allowPhotoDownload: Boolean,
    var allowCommunityNsfwMarking: Boolean,
    var showNsfwByDefault: Boolean,
    var creativePostMode: String,
    var locationFeatureEnabled: Boolean,
    var locationShareDefaultEnabled: Boolean
)

private data class YoloFeatureDefinition(
    val id: String,
    val introducedInVersion: String,
    val title: String,
    val warningCategory: String,
    val apply: (YoloPreferenceState) -> Unit
)

private class RefreshStageException(
    val failedCall: String,
    cause: Throwable
) : RuntimeException("refresh stage failed: $failedCall", cause)

class MainVm(private val repo: AppRepo) : ViewModel() {
    private enum class PerfEventResult(val wireValue: String) {
        OK("ok"),
        ERROR("error"),
        SKIPPED("skipped"),
        DEFERRED("deferred"),
        CANCELLED("cancelled")
    }

    private enum class RefreshExecutionDisposition {
        IDLE,
        SUCCESS,
        FAILURE,
        QUEUED,
        SKIPPED_CIRCUIT,
        SKIPPED_COOLDOWN,
        NO_TOKEN,
        CANCELLED
    }

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
    private var lastApiSuccessAtMs = 0L
    private var lastApiFailureAtMs = 0L
    private var lastApiFailureMessage = ""
    private var lastRefreshExecutionDisposition = RefreshExecutionDisposition.IDLE
    private var nextFeedScrollRequestId = 1L
    private val pendingFeedMutations = mutableMapOf<Long, PendingFeedMutation>()
    private var queuedRefreshRequest: QueuedRefreshRequest? = null
    private var calendarStatsLoadedPrefix = 0
    private var calendarStatsLoading = false
    private val staleFeedDays = mutableSetOf<String>()
    private var lastFeedAnchorDebugSignature = ""
    private var lastFeedJumpAnchorBefore: FeedViewportAnchor? = null
    private val profileSectionIds = listOf(
        "display",
        "yolo_mode",
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
        "calendar_functions",
        "past_posts"
    )
    private var profileSetupPromptShownInSession = false
    private var migrationSessionTokenSnapshot: String = ""
    private val yoloRegistry = listOf(
        YoloFeatureDefinition("auto_update_enabled_v1", "0.6.0", "Auto-Update-Suche", "updates") { it.autoUpdateEnabled = true },
        YoloFeatureDefinition("feed_post_push_enabled_v1", "0.6.0", "Push bei Posts anderer Nutzer", "notifications") { it.feedPostPushEnabled = true },
        YoloFeatureDefinition("chat_push_enabled_v1", "0.6.0", "Chat Push", "notifications") { it.chatPushEnabled = true },
        YoloFeatureDefinition("poll_push_enabled_v1", "0.6.0", "Umfrage Push", "notifications") { it.pollPushEnabled = true },
        YoloFeatureDefinition("special_moment_push_enabled_v1", "0.6.0", "Sondermoment Push", "notifications") { it.specialMomentPushEnabled = true },
        YoloFeatureDefinition("invite_registration_push_enabled_v1", "0.6.0", "Push bei neuen Mitgliedern", "notifications") { it.inviteRegistrationPushEnabled = true },
        YoloFeatureDefinition("photo_reaction_push_enabled_v1", "0.6.0", "Push bei Reaktionen", "notifications") { it.photoReactionPushEnabled = true },
        YoloFeatureDefinition("photo_comment_push_enabled_v1", "0.6.0", "Push bei Kommentaren", "notifications") { it.photoCommentPushEnabled = true },
        YoloFeatureDefinition("bookmarked_photo_push_enabled_v1", "0.6.0", "Push bei gemerkten Beitraegen", "notifications") { it.bookmarkedPhotoPushEnabled = true },
        YoloFeatureDefinition("post_change_push_enabled_v1", "0.6.0", "Push bei Post-Aenderungen", "notifications") { it.postChangePushEnabled = true },
        YoloFeatureDefinition("auto_subscribe_interacted_posts_enabled_v1", "0.5.21", "Interaktions-Auto-Abo", "interactions") { it.autoSubscribeInteractedPostsEnabled = true },
        YoloFeatureDefinition("own_post_number_in_push_enabled_v1", "0.6.0", "Postnummern bei eigenen Beitrags-Pushes", "notifications") { it.ownPostNumberInPushEnabled = true },
        YoloFeatureDefinition("post_number_in_push_enabled_v1", "0.6.0", "Postnummern bei gemerkten Beitrags-Pushes", "notifications") { it.postNumberInPushEnabled = true },
        YoloFeatureDefinition("allow_photo_download_v1", "0.6.0", "Download-Freigabe", "sharing") { it.allowPhotoDownload = true },
        YoloFeatureDefinition("allow_community_nsfw_marking_v1", "0.6.0", "NSFW-Markierung durch andere erlauben", "posting") { it.allowCommunityNsfwMarking = true },
        YoloFeatureDefinition("show_nsfw_by_default_v1", "0.6.0", "NSFW standardmaessig anzeigen", "display") { it.showNsfwByDefault = true },
        YoloFeatureDefinition("creative_post_mode_both_v1", "0.6.0", "Kreativmodus", "posting") { it.creativePostMode = "both" },
        YoloFeatureDefinition("location_feature_enabled_v1", "0.6.0", "Standort-Feature", "location") { it.locationFeatureEnabled = true },
        YoloFeatureDefinition("location_share_default_enabled_v1", "0.6.0", "Standort standardmaessig mitsenden", "location") {
            it.locationFeatureEnabled = true
            it.locationShareDefaultEnabled = true
        },
        YoloFeatureDefinition("use_fotomoji_reactions_v1", "0.6.0", "FotoMoji statt Emoji-Reaktion", "interactions") { it.useFotomojiReactions = true },
        YoloFeatureDefinition("show_public_post_numbers_v1", "0.6.0", "Postnummern anzeigen", "display") { it.showPublicPostNumbers = true },
        YoloFeatureDefinition("prefer_swipe_for_two_image_posts_v1", "0.6.0", "2-Bild-Posts als Wischansicht", "display") { it.preferSwipeForTwoImagePosts = true }
    )

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
            bookmarkedPhotoPushEnabled = repo.bookmarkedPhotoPushLocalEnabled(),
            postChangePushEnabled = repo.postChangePushLocalEnabled(),
            autoSubscribeInteractedPostsEnabled = repo.autoSubscribeInteractedPostsLocalEnabled(),
            ownPostNumberInPushEnabled = repo.ownPostNumberInPushLocalEnabled(),
            postNumberInPushEnabled = repo.postNumberInPushLocalEnabled(),
            yoloModeEnabled = repo.yoloModeLocalEnabled(),
            feedOrderMode = repo.feedOrderMode(),
            randomFeedSeed = repo.randomFeedSeed(),
            showPublicPostNumbers = repo.showPublicPostNumbers(),
            preferSwipeForTwoImagePosts = repo.preferSwipeForTwoImagePosts(),
            customNotificationToneEnabled = repo.customNotificationToneEnabled(),
            customNotificationToneUri = repo.customNotificationToneUri(),
            debugMasterEnabled = repo.debugMasterEnabled(),
            feedDebugEnabled = repo.feedDebugEnabled(),
            diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled() && repo.diagnosticsConsentGrantedLocal(),
            diagnosticsConsentGranted = repo.diagnosticsConsentGrantedLocal(),
            activeApiBaseUrl = repo.resolvedApiBaseUrl(),
            apiBaseUrlOverride = repo.apiBaseUrlOverrideRaw(),
            allowInsecureHttpOverride = repo.allowInsecureHttpOverride(),
            debugLogs = repo.recentDebugLogs()
        ).let(::withNotificationDebugState)
    )
        private set

    private fun withNotificationDebugState(base: UiState): UiState {
        val debugState = repo.notificationDebugState()
        return base.copy(
            notificationDebugEnabled = debugState.enabled,
            notificationDebugExpiresAt = debugState.expiresAt,
            notificationDebugEvents = debugState.events,
            notificationDebugLaunches = debugState.launches,
            notificationDebugPayloads = debugState.payloads,
            notificationDebugActiveItems = debugState.activeItems,
            notificationDebugEnvironment = debugState.environment
        )
    }

    private fun normalizeChatBody(body: String): String =
        body.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ").lowercase()

    private fun issueFeedScrollRequestId(): Long {
        val id = nextFeedScrollRequestId
        nextFeedScrollRequestId += 1L
        return id
    }

    private fun refreshPriorityFor(reason: String, forceFeedReload: Boolean): RefreshPriority = when {
        reason == "feed_auto" -> RefreshPriority.AUTO
        reason == "feed_pull" -> RefreshPriority.MANUAL
        reason.startsWith("photo_") || reason == "comment_submit" -> RefreshPriority.MUTATION
        forceFeedReload && (reason.contains("jump") || reason.contains("capsule") || reason.contains("launch")) -> RefreshPriority.NAVIGATION
        else -> RefreshPriority.GLOBAL
    }

    @Synchronized
    private fun queueRefreshRequest(request: QueuedRefreshRequest) {
        val current = queuedRefreshRequest
        queuedRefreshRequest = if (current == null || request.priority.weight >= current.priority.weight) {
            request.copy(
                forceFeedReload = request.forceFeedReload || (current?.forceFeedReload ?: false),
                refreshFeedWindow = request.refreshFeedWindow || (current?.refreshFeedWindow ?: false),
                bypassCooldown = request.bypassCooldown || (current?.bypassCooldown ?: false),
                showLoading = request.showLoading || (current?.showLoading ?: false),
                respectCircuitBreaker = request.respectCircuitBreaker && (current?.respectCircuitBreaker ?: true)
            )
        } else {
            current.copy(
                forceFeedReload = current.forceFeedReload || request.forceFeedReload,
                refreshFeedWindow = current.refreshFeedWindow || request.refreshFeedWindow,
                bypassCooldown = current.bypassCooldown || request.bypassCooldown,
                showLoading = current.showLoading || request.showLoading,
                respectCircuitBreaker = current.respectCircuitBreaker && request.respectCircuitBreaker
            )
        }
    }

    @Synchronized
    private fun consumeQueuedRefreshRequest(): QueuedRefreshRequest? {
        val next = queuedRefreshRequest
        queuedRefreshRequest = null
        return next
    }

    private fun hasPendingFeedNavigation(): Boolean =
        !state.feedFocusDay.isNullOrBlank() || state.feedFocusPhotoId != null || state.feedFocusBoundary != null

    private fun currentRefreshViewportAnchor(): FeedViewportAnchor? =
        state.feedViewportAnchor.takeIf { anchor ->
            !anchor.day.isNullOrBlank() && state.feedDays.isNotEmpty() && !hasPendingFeedNavigation()
        }

    private fun effectiveRefreshViewportAnchor(): FeedViewportAnchor? {
        val direct = currentRefreshViewportAnchor()
        if (direct != null) return direct
        if (state.activeTab != AppTab.FEED) return null
        val anchor = state.feedViewportAnchor
        if (anchor.day.isNullOrBlank() || state.feedDays.isEmpty()) return null
        return anchor.takeIf { state.feedDays.contains(it.day) || state.calendarDays.contains(it.day) }
    }

    private fun describeAnchor(anchor: FeedViewportAnchor?): String {
        if (anchor == null) return "-"
        return buildString {
            append("day=").append(anchor.day ?: "-")
            append(",photoId=").append(anchor.photoId ?: -1L)
            append(",kind=").append(anchor.kind.name.lowercase())
            append(",rowIndex=").append(anchor.rowIndex)
            append(",offsetPx=").append(anchor.rowOffsetPx)
            append(",firstVisible=").append(anchor.firstVisibleIndex)
            append(",lastVisible=").append(anchor.lastVisibleIndex)
            append(",rowsSize=").append(anchor.rowsSize)
            append(",present=").append(anchor.presentInRows)
        }
    }

    private fun logFeedDecision(
        type: String,
        message: String,
        meta: String
    ) {
        repo.logFeedDebug(type, message, "$meta;orderMode=${state.feedOrderMode.name.lowercase()};feedDays=${state.feedDays.joinToString(",").ifBlank { "-" }};calendarDaysPrefix=${state.calendarDays.take(8).joinToString(",").ifBlank { "-" }};focusDay=${state.feedFocusDay ?: "-"};focusPhotoId=${state.feedFocusPhotoId ?: -1L};feedWindowReloadInFlight=${state.feedWindowReloadInFlight};paging=${state.feedPaging};networkSnapshot=${state.networkSnapshot}")
    }

    fun updateFeedViewportAnchor(anchor: FeedViewportAnchor) {
        val normalizedDay = anchor.day?.takeIf { it.isNotBlank() }
        val normalized = anchor.copy(day = normalizedDay, rowOffsetPx = anchor.rowOffsetPx.coerceAtLeast(0))
        if (state.feedViewportAnchor != normalized || state.feedVisibleAnchorDay != normalized.day) {
            state = state.copy(
                feedViewportAnchor = normalized,
                feedVisibleAnchorDay = normalized.day
            )
            val signature = "${normalized.day}|${normalized.photoId}|${normalized.kind}|${normalized.rowIndex}|${normalized.firstVisibleIndex}|${normalized.lastVisibleIndex}|${normalized.rowsSize}|${normalized.presentInRows}"
            if (signature != lastFeedAnchorDebugSignature) {
                lastFeedAnchorDebugSignature = signature
                logFeedDecision(
                    type = "feed_viewport_anchor_changed",
                    message = "viewport anchor updated",
                    meta = "anchor=${describeAnchor(normalized)}"
                )
            }
        }
    }

    private fun requestFeedViewportRestore(anchor: FeedViewportAnchor) {
        if (anchor.day.isNullOrBlank() && anchor.photoId == null) return
        lastFeedJumpAnchorBefore = anchor
        state = state.copy(
            feedViewportRestoreAnchor = anchor,
            feedViewportRestoreRequestId = issueFeedScrollRequestId()
        )
        logFeedDecision(
            type = "feed_viewport_restore_requested",
            message = "viewport restore requested",
            meta = "anchor=${describeAnchor(anchor)}"
        )
    }

    fun consumeFeedViewportRestore() {
        if (state.feedViewportRestoreRequestId != 0L) {
            state = state.copy(feedViewportRestoreRequestId = 0L)
        }
    }

    fun reportFeedViewportRestoreResult(
        requested: FeedViewportAnchor,
        resolved: FeedViewportAnchor?,
        failureReason: String = ""
    ) {
        if (resolved == null) {
            logFeedDecision(
                type = "feed_viewport_restore_failed",
                message = "viewport restore failed",
                meta = "requestedAnchor=${describeAnchor(requested)};failureReason=${failureReason.ifBlank { "anchor_not_found" }};rowsSize=${state.feedViewportAnchor.rowsSize}"
            )
            logFeedDecision(
                type = "feed_refresh_result",
                message = "feed restore failed",
                meta = "result=restore_failed;requestedAnchor=${describeAnchor(requested)};failureReason=${failureReason.ifBlank { "anchor_not_found" }}"
            )
            return
        }
        logFeedDecision(
            type = "feed_viewport_restore_applied",
            message = "viewport restore applied",
            meta = "requestedAnchor=${describeAnchor(requested)};resolvedRowIndex=${resolved.rowIndex};resolvedRowType=${resolved.kind.name.lowercase()};resolvedDay=${resolved.day ?: "-"};resolvedPhotoId=${resolved.photoId ?: -1L};offsetPx=${resolved.rowOffsetPx}"
        )
        val before = lastFeedJumpAnchorBefore
        if (before != null && before.day != null && resolved.day != null) {
            val rowDistance = if (before.rowIndex >= 0 && resolved.rowIndex >= 0) abs(before.rowIndex - resolved.rowIndex) else 0
            val dayDistance = abs(compareDayStrings(before.day, resolved.day))
            if (before.day != resolved.day || rowDistance > 6) {
                logFeedDecision(
                    type = "feed_jump_detected",
                    message = "feed viewport moved during restore",
                    meta = "beforeAnchor=${describeAnchor(before)};afterAnchor=${describeAnchor(resolved)};beforeFirstVisible=${before.firstVisibleIndex};afterFirstVisible=${resolved.firstVisibleIndex};jumpDistanceRows=$rowDistance;jumpDistanceDays=$dayDistance;trigger=viewport_restore"
                )
            }
        }
        lastFeedJumpAnchorBefore = null
    }

    fun consumeFeedScrollRequest() {
        if (!hasPendingFeedNavigation()) return
        state = state.copy(
            feedFocusDay = null,
            feedFocusPhotoId = null,
            feedFocusBoundary = null,
            feedScrollRequestId = 0L
        )
    }

    private fun upsertPendingFeedMutation(photoId: Long, transform: (PendingFeedMutation) -> PendingFeedMutation) {
        val next = transform(pendingFeedMutations[photoId] ?: PendingFeedMutation())
        if (next.photoOverride == null && next.commentsOverride == null && next.reactionsOverride == null && next.photoMojisOverride == null) {
            pendingFeedMutations.remove(photoId)
        } else {
            pendingFeedMutations[photoId] = next
        }
    }

    private fun clearPendingFeedMutation(photoId: Long) {
        pendingFeedMutations.remove(photoId)
    }

    private fun pendingFeedMutationParts(mutation: PendingFeedMutation): List<String> = buildList {
        if (mutation.photoOverride != null) add("photo")
        if (mutation.commentsOverride != null) add("comments")
        if (mutation.reactionsOverride != null) add("reactions")
        if (mutation.photoMojisOverride != null) add("photoMojis")
    }

    private fun applyPendingFeedMutation(item: FeedItem): FeedItem {
        val pending = pendingFeedMutations[item.photo.id] ?: return item
        return item.copy(
            photo = pending.photoOverride ?: item.photo,
            reactions = pending.reactionsOverride ?: item.reactions,
            photoMojis = pending.photoMojisOverride ?: item.photoMojis,
            comments = pending.commentsOverride ?: item.comments
        )
    }

    private fun reconcilePendingFeedMutation(item: FeedItem) {
        val pending = pendingFeedMutations[item.photo.id] ?: return
        val beforeParts = pendingFeedMutationParts(pending)
        val next = pending.copy(
            photoOverride = pending.photoOverride?.takeUnless { it == item.photo },
            commentsOverride = pending.commentsOverride?.takeUnless { it == item.comments.orEmpty() },
            reactionsOverride = pending.reactionsOverride?.takeUnless { it == item.reactions.orEmpty() },
            photoMojisOverride = pending.photoMojisOverride?.takeUnless { it == item.photoMojis.orEmpty() }
        )
        val afterParts = pendingFeedMutationParts(next)
        if (beforeParts != afterParts) {
            repo.logDebug(
                type = "feed_mutation_reconciled",
                message = if (afterParts.isEmpty()) "pending feed mutation fully reconciled" else "pending feed mutation partially reconciled",
                meta = "photoId=${item.photo.id};resolved=${beforeParts.filterNot(afterParts::contains).joinToString(",").ifBlank { "-" }};remaining=${afterParts.joinToString(",").ifBlank { "-" }}"
            )
        }
        if (next.photoOverride == null && next.commentsOverride == null && next.reactionsOverride == null && next.photoMojisOverride == null) {
            pendingFeedMutations.remove(item.photo.id)
        } else {
            pendingFeedMutations[item.photo.id] = next
        }
    }

    private fun CalendarPayloadResponse.toDataset(): CalendarDataset =
        CalendarDataset(
            days = days,
            dayStats = dayStats.associateBy { it.day },
            photosByDay = photosByDay,
            feedItems = items,
            lockedCount = lockedCount,
            releasedCount = releasedCount
        )

    private fun CalendarSearchResponse.toDataset(): CalendarSearchDataset =
        CalendarSearchDataset(
            query = query,
            normalizedQuery = normalizedQuery,
            dataset = CalendarDataset(days = days, dayStats = dayStats.associateBy { it.day }),
            matchesByDay = matchedPhotosByDay
        )

    private fun applyCalendarDataset(dataset: CalendarDataset) {
        val selectedDay = state.calendarSelectedDay?.takeIf { dataset.days.contains(it) }
            ?: dataset.days.firstOrNull()
        state = state.copy(
            calendarDays = dataset.days,
            calendarDayStats = dataset.dayStats,
            calendarSelectedDay = selectedDay
        )
    }

    private fun applyCalendarModeDataset() {
        val dataset = when (state.calendarMode) {
            CalendarMode.PUBLIC -> state.calendarPublicData
            CalendarMode.BOOKMARKS -> state.calendarBookmarksData
            CalendarMode.TIME_CAPSULES -> state.calendarTimeCapsulesData
            CalendarMode.SEARCH -> state.calendarSearchData.dataset
        }
        applyCalendarDataset(dataset)
    }

    private fun patchPhotoState(
        photoId: Long,
        transform: (PromptPhoto) -> PromptPhoto
    ) {
        val newFeedByDay = state.feedByDay.mapValues { (_, items) ->
            items.map { item ->
                if (item.photo.id == photoId) item.copy(photo = transform(item.photo)) else item
            }
        }
        val newPhotos = state.photos.map { photo ->
            if (photo.id == photoId) transform(photo) else photo
        }
        val viewedProfile = state.viewedProfile
        val newViewedProfile = viewedProfile?.copy(
            photos = viewedProfile.photos.map { photo ->
                if (photo.id == photoId) transform(photo) else photo
            }
        )
        fun updateDataset(dataset: CalendarDataset): CalendarDataset {
            val updatedStats = dataset.dayStats.mapValues { (_, stat) ->
                val featured = stat.featuredPhoto
                if (featured?.photoId == photoId) {
                    val patched = transform(
                        PromptPhoto(
                            id = featured.photoId,
                            day = stat.day,
                            promptOnly = false,
                            caption = null,
                            url = featured.url,
                            secondUrl = featured.secondUrl,
                            createdAt = "",
                            bookmarkedByMe = featured.bookmarkedByMe,
                            bookmarkCount = featured.bookmarkCount,
                            publicNumber = featured.publicNumber
                        )
                    )
                    stat.copy(
                        featuredPhoto = featured.copy(
                            bookmarkedByMe = patched.bookmarkedByMe,
                            bookmarkCount = patched.bookmarkCount
                        )
                    )
                } else stat
            }
            val updatedPhotosByDay = dataset.photosByDay.mapValues { (_, items) ->
                items.map { item ->
                    if (item.photo.id == photoId) item.copy(photo = transform(item.photo)) else item
                }
            }
            val updatedFeedItems = dataset.feedItems.map { item ->
                if (item.photo.id == photoId) item.copy(photo = transform(item.photo)) else item
            }
            return dataset.copy(dayStats = updatedStats, photosByDay = updatedPhotosByDay, feedItems = updatedFeedItems)
        }
        val updatedSearchMatches = state.calendarSearchData.matchesByDay.mapValues { (_, matches) ->
            matches.map { match ->
                if (match.photo.id == photoId) match.copy(photo = transform(match.photo)) else match
            }
        }
        state = state.copy(
            feedByDay = newFeedByDay,
            feed = newFeedByDay[state.prompt?.day].orEmpty(),
            photos = newPhotos,
            viewedProfile = newViewedProfile,
            calendarPublicData = updateDataset(state.calendarPublicData),
            calendarBookmarksData = updateDataset(state.calendarBookmarksData),
            calendarTimeCapsulesData = updateDataset(state.calendarTimeCapsulesData),
            calendarSearchData = state.calendarSearchData.copy(
                dataset = updateDataset(state.calendarSearchData.dataset),
                matchesByDay = updatedSearchMatches
            )
        )
        applyCalendarModeDataset()
    }

    private fun patchFeedItemState(
        photoId: Long,
        transform: (FeedItem) -> FeedItem
    ) {
        val newFeedByDay = state.feedByDay.mapValues { (_, items) ->
            items.map { item ->
                if (item.photo.id == photoId) transform(item) else item
            }
        }
        fun updateDataset(dataset: CalendarDataset): CalendarDataset =
            dataset.copy(
                feedItems = dataset.feedItems.map { item ->
                    if (item.photo.id == photoId) transform(item) else item
                }
            )
        state = state.copy(
            feedByDay = newFeedByDay,
            feed = newFeedByDay[state.prompt?.day].orEmpty(),
            calendarPublicData = updateDataset(state.calendarPublicData),
            calendarBookmarksData = updateDataset(state.calendarBookmarksData),
            calendarTimeCapsulesData = updateDataset(state.calendarTimeCapsulesData),
            calendarSearchData = state.calendarSearchData.copy(
                dataset = updateDataset(state.calendarSearchData.dataset)
            )
        )
        applyCalendarModeDataset()
    }

    private fun applyPhotoInteractionsToFeedState(photoId: Long, interactions: PhotoInteractionsResponse) {
        patchFeedItemState(photoId) { item ->
            item.copy(
                reactions = interactions.reactions,
                photoMojis = interactions.photoMojis,
                comments = interactions.comments
            )
        }
    }

    private fun applyServerPhoto(photo: PromptPhoto) {
        val pendingPhoto = pendingFeedMutations[photo.id]?.photoOverride
        patchPhotoState(photo.id) { pendingPhoto ?: photo }
        pendingFeedMutations[photo.id]?.let { pending ->
            upsertPendingFeedMutation(photo.id) {
                it.copy(photoOverride = pending.photoOverride?.takeUnless { override -> override == photo })
            }
        }
    }

    private fun findPhotoDay(photoId: Long): String? {
        state.feedByDay.forEach { (day, items) ->
            if (items.any { it.photo.id == photoId }) return day
        }
        state.viewedProfile?.photos?.firstOrNull { it.id == photoId }?.day?.let { return it }
        state.photos.firstOrNull { it.id == photoId }?.day?.let { return it }
        state.calendarPublicData.photosByDay.forEach { (day, items) ->
            if (items.any { it.photo.id == photoId }) return day
        }
        state.calendarBookmarksData.photosByDay.forEach { (day, items) ->
            if (items.any { it.photo.id == photoId }) return day
        }
        state.calendarSearchData.matchesByDay.forEach { (day, items) ->
            if (items.any { it.photo.id == photoId }) return day
        }
        state.calendarTimeCapsulesData.photosByDay.forEach { (day, items) ->
            if (items.any { it.photo.id == photoId }) return day
        }
        return null
    }

    private suspend fun refreshVisibleFeedAfterCreativeMutation(photoId: Long, reason: String) {
        findPhotoDay(photoId)?.let { staleFeedDays.add(it) }
        if (state.activeTab == AppTab.FEED || state.feedByDay.isNotEmpty()) {
            refreshFeed(reason)
        }
    }

    fun applyLocalPhotoPaint(
        photoId: Long,
        viewerId: Long,
        username: String,
        color: String,
        paths: List<PhotoPaintPath>,
        strokeWidth: Float
    ) {
        val normalizedColor = normalizeHexColor(color)
        val pathsJson = encodePhotoPaintPaths(paths)
        patchPhotoState(photoId) { photo ->
            val existing = photo.paints.firstOrNull { it.userId == viewerId }
            val nextPaints = if (paths.isEmpty()) {
                photo.paints.filterNot { it.userId == viewerId }
            } else {
                photo.paints.filterNot { it.userId == viewerId } + PhotoPaintOverlay(
                    id = existing?.id ?: -((photoId * 1000L) + viewerId),
                    userId = viewerId,
                    username = existing?.username?.takeIf { it.isNotBlank() } ?: username,
                    color = existing?.color?.takeIf { it.isNotBlank() } ?: normalizedColor,
                    surface = "frame",
                    strokeWidth = strokeWidth,
                    pathsJson = pathsJson
                )
            }
            photo.copy(
                paints = nextPaints,
                paintedByMe = nextPaints.any { it.userId == viewerId }
            )
        }
        val patchedPhoto = state.feedByDay.values
            .asSequence()
            .flatten()
            .firstOrNull { it.photo.id == photoId }
            ?.photo
            ?: state.photos.firstOrNull { it.id == photoId }
        if (patchedPhoto != null) {
            upsertPendingFeedMutation(photoId) { it.copy(photoOverride = patchedPhoto) }
        }
    }

    private fun updateBookmarkStateLocally(photoId: Long, bookmarked: Boolean) {
        patchPhotoState(photoId) { photo ->
            val nextCount = (photo.bookmarkCount + if (bookmarked) 1 else -1).coerceAtLeast(0)
            photo.copy(bookmarkedByMe = bookmarked, bookmarkCount = nextCount)
        }
    }

    private fun findPhotoOwnerId(photoId: Long): Long? {
        state.feedByDay.values.asSequence().flatten().firstOrNull { it.photo.id == photoId }?.let { return it.user.id }
        state.viewedProfile?.photos?.firstOrNull { it.id == photoId }?.let { return state.viewedProfile?.user?.id }
        state.calendarPublicData.photosByDay.values.asSequence().flatten().firstOrNull { it.photo.id == photoId }?.let { return it.user.id }
        state.calendarBookmarksData.photosByDay.values.asSequence().flatten().firstOrNull { it.photo.id == photoId }?.let { return it.user.id }
        state.calendarSearchData.matchesByDay.values.asSequence().flatten().firstOrNull { it.photo.id == photoId }?.let { return it.user.id }
        state.calendarTimeCapsulesData.photosByDay.values.asSequence().flatten().firstOrNull { it.photo.id == photoId }?.let { return it.user.id }
        return null
    }

    private fun adjustBookmarkCounters(photoId: Long, delta: Int) {
        if (delta == 0) return
        val meId = state.user?.id ?: return
        val ownerId = findPhotoOwnerId(photoId) ?: return
        state = state.copy(
            bookmarksGivenCount = (state.bookmarksGivenCount + delta).coerceAtLeast(0),
            bookmarksReceivedCount = if (ownerId == meId) {
                (state.bookmarksReceivedCount + delta).coerceAtLeast(0)
            } else {
                state.bookmarksReceivedCount
            },
            viewedProfile = state.viewedProfile?.let { profile ->
                if (profile.isSelf) {
                    profile.copy(
                        bookmarksGivenCount = (profile.bookmarksGivenCount + delta).coerceAtLeast(0),
                        bookmarksReceivedCount = if (ownerId == meId) {
                            (profile.bookmarksReceivedCount + delta).coerceAtLeast(0)
                        } else {
                            profile.bookmarksReceivedCount
                        }
                    )
                } else {
                    profile
                }
            }
        )
    }

    private fun removeBookmarkFromBookmarksDataset(photoId: Long) {
        if (state.calendarBookmarksFilter != BookmarkCalendarFilter.MINE) {
            return
        }
        val remainingPhotosByDay = state.calendarBookmarksData.photosByDay.mapValues { (_, items) ->
            items.filterNot { it.photo.id == photoId }
        }.filterValues { it.isNotEmpty() }
        val remainingStats = state.calendarBookmarksData.dayStats.values.filter { stat ->
            remainingPhotosByDay.containsKey(stat.day) || stat.featuredPhoto?.photoId != photoId
        }
        val updated = CalendarDataset(
            days = remainingPhotosByDay.keys.sortedDescending(),
            dayStats = remainingStats.associateBy { it.day },
            photosByDay = remainingPhotosByDay
        )
        state = state.copy(calendarBookmarksData = updated)
        if (state.calendarMode == CalendarMode.BOOKMARKS) {
            applyCalendarDataset(updated)
        }
    }

    private fun clearAllBookmarksLocally() {
        val newFeedByDay = state.feedByDay.mapValues { (_, items) ->
            items.map { item -> item.copy(photo = item.photo.copy(bookmarkedByMe = false)) }
        }
        state = state.copy(
            feedByDay = newFeedByDay,
            feed = newFeedByDay[state.prompt?.day].orEmpty(),
            photos = state.photos.map { it.copy(bookmarkedByMe = false) },
            viewedProfile = state.viewedProfile?.let { profile ->
                profile.copy(photos = profile.photos.map { it.copy(bookmarkedByMe = false) })
            },
            calendarPublicData = state.calendarPublicData.copy(
                dayStats = state.calendarPublicData.dayStats.mapValues { (_, stat) ->
                    stat.copy(featuredPhoto = stat.featuredPhoto?.copy(bookmarkedByMe = false))
                },
                photosByDay = state.calendarPublicData.photosByDay.mapValues { (_, items) ->
                    items.map { it.copy(photo = it.photo.copy(bookmarkedByMe = false)) }
                }
            ),
            calendarBookmarksData = CalendarDataset(),
            calendarSearchData = state.calendarSearchData.copy(
                dataset = state.calendarSearchData.dataset.copy(
                    dayStats = state.calendarSearchData.dataset.dayStats.mapValues { (_, stat) ->
                        stat.copy(featuredPhoto = stat.featuredPhoto?.copy(bookmarkedByMe = false))
                    }
                ),
                matchesByDay = state.calendarSearchData.matchesByDay.mapValues { (_, matches) ->
                    matches.map { it.copy(photo = it.photo.copy(bookmarkedByMe = false)) }
                }
            )
        )
        applyCalendarModeDataset()
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

    private fun chatLimitValue(): Int = state.promptRules.effectiveChatMessageMaxLength()

    private fun chatUnlimitedEnabled(): Boolean = state.promptRules.isChatMessageUnlimited()

    private fun chatInputDebugMeta(input: String, trimmed: String = input.trim()): String =
        buildString {
            append("inputLength=").append(textCodePointLength(input))
            append(";trimmedLength=").append(textCodePointLength(trimmed))
            append(";newlineCount=").append(input.count { it == '\n' })
            append(";configuredChatLimit=").append(chatLimitValue())
            append(";unlimited=").append(chatUnlimitedEnabled())
        }

    private fun rootCause(throwable: Throwable): Throwable {
        var current = throwable
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun isBenignCancellation(throwable: Throwable): Boolean {
        return isBenignCancellationShared(throwable)
    }

    private fun networkFailureKind(throwable: Throwable): String? {
        val root = rootCause(throwable)
        if (!repo.hasUsableNetwork()) return "no_active_network"
        return when {
            root is CertPathValidatorException -> "cert_path_validator"
            root is SSLHandshakeException -> "ssl_handshake"
            root is UnknownHostException -> "dns"
            root is ConnectException -> "connect"
            root is SocketTimeoutException -> "timeout"
            root is SSLException -> "ssl_other"
            else -> null
        }
    }

    private fun debugFailureMessage(throwable: Throwable): String {
        return when (networkFailureKind(throwable)) {
            "dns" -> "Servername konnte nicht aufgeloest werden"
            "no_active_network" -> "Keine aktive Internetverbindung verfuegbar"
            "ssl_handshake" -> "Sichere Verbindung fehlgeschlagen"
            "cert_path_validator" -> "Dieses Netzwerk vertraut dem Daily-Zertifikat nicht oder veraendert die Verbindung"
            "ssl_other" -> "Sichere Verbindung fehlgeschlagen"
            "connect" -> "Verbindung zum Server fehlgeschlagen"
            "timeout" -> "Server antwortet zu langsam"
            else -> throwable.message ?: "request failed"
        }
    }

    private fun securityAdviceForFailure(failureClass: String): String {
        return when (failureClass) {
            "ssl_handshake",
            "cert_path_validator",
            "ssl_other" -> "Daily konnte in diesem Netzwerk keine sichere Verbindung aufbauen. Bitte mobile Daten oder ein anderes WLAN versuchen."
            else -> ""
        }
    }

    private fun shouldShowSecurityAdvice(failureClass: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (failureClass != "ssl_handshake" && failureClass != "cert_path_validator" && failureClass != "ssl_other") return false
        val thresholdMs = 10 * 60 * 1000L
        val hits = repo.recentDebugLogs(120).count { row ->
            val family = row.meta.lowercase()
            val createdMs = parseIsoInstantMs(row.lastSeenAt.ifBlank { row.createdAt })
            (nowMs - createdMs) <= thresholdMs && (
                family.contains("failureclass=$failureClass") ||
                    family.contains("network=$failureClass")
                )
        }
        val lastShownAt = repo.lastSecurityAdviceShownAtMs()
        if (hits >= 3 && (nowMs - lastShownAt) > thresholdMs) {
            repo.setLastSecurityAdviceShownAtMs(nowMs)
            repo.logDebug(
                type = "network_security_advice_shown",
                message = securityAdviceForFailure(failureClass),
                meta = "failureClass=$failureClass;countWindow=10m;threshold=3"
            )
            return true
        }
        return false
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
        failureClass == "dns" ||
            failureClass == "connect" ||
            failureClass == "timeout" ||
            failureClass == "offline" ||
            failureClass == "no_active_network" ||
            failureClass == "ssl_handshake" ||
            failureClass == "cert_path_validator" ||
            failureClass == "ssl_other"

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

    private fun currentNetworkSnapshot(): String = repo.networkSnapshotMeta()

    private fun refreshConnectionHealthState(base: UiState = state): UiState {
        val next = base.copy(
            lastApiSuccessAtMs = lastApiSuccessAtMs,
            lastApiFailureAtMs = lastApiFailureAtMs,
            lastApiFailureMessage = lastApiFailureMessage,
            networkSnapshot = currentNetworkSnapshot()
        )
        val snapshot = evaluateConnectionHealth(
            ConnectionHealthInputs(
                nowMs = System.currentTimeMillis(),
                startupDone = next.startupDone,
                serverConnected = next.serverConnected,
                lastPingMs = next.lastPingMs,
                lastApiSuccessAtMs = next.lastApiSuccessAtMs,
                lastApiFailureAtMs = next.lastApiFailureAtMs,
                lastApiFailureMessage = next.lastApiFailureMessage,
                networkSnapshot = next.networkSnapshot,
                refreshCircuitRemainingMs = refreshCircuitOpenRemainingMs(System.currentTimeMillis()),
                lastRefreshFailureClass = lastRefreshFailureClass,
                uploadQueue = next.uploadQueue
            )
        )
        return next.copy(connectionHealthSnapshot = snapshot)
    }

    private fun logPerfEvent(event: String, durationMs: Long, success: Boolean, extra: String = "") {
        logPerfEvent(
            event = event,
            durationMs = durationMs,
            result = if (success) PerfEventResult.OK else PerfEventResult.ERROR,
            extra = extra
        )
    }

    private fun logPerfEvent(event: String, durationMs: Long, result: PerfEventResult, extra: String = "") {
        if (!shouldSamplePerf(result == PerfEventResult.OK)) return
        val meta = buildString {
            append("event=").append(event)
            append(";durationMs=").append(durationMs.coerceAtLeast(0L))
            append(";result=").append(result.wireValue)
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
        state = refreshConnectionHealthState(state.copy(startupDone = false, startupQuote = ""))
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
            lastApiSuccessAtMs = System.currentTimeMillis()
            lastApiFailureAtMs = 0L
            lastApiFailureMessage = ""
            state = refreshConnectionHealthState(state.copy(
                startupDone = false,
                startupQuote = startupQuote,
                serverConnected = true,
                serverVersion = health?.version ?: "nicht erreichbar",
                pushProvider = health?.provider ?: "unknown",
                chatDeleteSupported = health?.features?.chatDelete == true
            ))
            delay(1300)
        } else if (!healthOk) {
            lastApiFailureAtMs = System.currentTimeMillis()
            lastApiFailureMessage = "Server nicht erreichbar"
        }
        state = refreshConnectionHealthState(state.copy(
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
            bookmarkedPhotoPushEnabled = repo.bookmarkedPhotoPushLocalEnabled(),
            postChangePushEnabled = repo.postChangePushLocalEnabled(),
            autoSubscribeInteractedPostsEnabled = repo.autoSubscribeInteractedPostsLocalEnabled(),
            yoloModeEnabled = repo.yoloModeLocalEnabled(),
            showPublicPostNumbers = repo.showPublicPostNumbers(),
            preferSwipeForTwoImagePosts = repo.preferSwipeForTwoImagePosts(),
            customNotificationToneEnabled = repo.customNotificationToneEnabled(),
            customNotificationToneUri = repo.customNotificationToneUri(),
            showConnectionHealthIndicator = repo.showConnectionHealthIndicator(),
            diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled() && repo.diagnosticsConsentGrantedLocal(),
            diagnosticsConsentGranted = repo.diagnosticsConsentGrantedLocal(),
            debugLogs = repo.recentDebugLogs(),
            message = if (health?.ok == true) "" else "Server nicht erreichbar"
        ))
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
            yoloModeEnabled = repo.yoloModeLocalEnabled(),
            showConnectionHealthIndicator = repo.showConnectionHealthIndicator(),
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
        state = refreshConnectionHealthState(state)
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
        val responseBody = debugMetaSanitizeShared(peekHttpErrorBody(throwable), 240)
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
            if (responseBody.isNotBlank()) {
                append(";responseBody=").append(responseBody)
            }
            if (throwable is IllegalStateException && throwable.message == "missing_access_token") {
                append(";derivedFrom=").append(repo.authStateTransitionReason())
            }
            append(";").append(debugThrowableMetaShared(throwable))
        }
        val meta = if (extraMeta.isBlank()) base else "$base;$extraMeta"
        repo.logDebug(type = type, message = debugFailureMessage(throwable), meta = meta)
        val failureClass = network ?: (if (!repo.hasUsableNetwork()) "no_active_network" else throwable::class.java.simpleName)
        val advice = securityAdviceForFailure(failureClass)
        if (advice.isNotBlank() && shouldShowSecurityAdvice(failureClass)) {
            state = state.copy(message = advice, networkAdvice = advice)
        }
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

    fun setDebugMasterEnabled(enabled: Boolean) {
        repo.setDebugMasterEnabled(enabled)
        if (!enabled) {
            repo.setFeedDebugEnabled(false)
            repo.setNotificationDebugEnabled(false)
        }
        state = withNotificationDebugState(
            state.copy(
                debugMasterEnabled = enabled,
                feedDebugEnabled = if (enabled) state.feedDebugEnabled else false,
                debugLogs = repo.recentDebugLogs(),
                message = if (enabled) "Debug-Modus aktiviert" else "Debug-Modus deaktiviert"
            )
        )
    }

    fun setFeedDebugEnabled(enabled: Boolean) {
        if (enabled && !state.debugMasterEnabled) {
            state = state.copy(message = "Bitte zuerst den Debug-Modus aktivieren.")
            return
        }
        repo.setFeedDebugEnabled(enabled)
        state = state.copy(
            feedDebugEnabled = repo.feedDebugEnabled(),
            debugLogs = repo.recentDebugLogs(),
            message = if (enabled) "Feed-Debug aktiviert" else "Feed-Debug deaktiviert"
        )
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
        state = withNotificationDebugState(state.copy(
            debugMasterEnabled = repo.debugMasterEnabled(),
            feedDebugEnabled = repo.feedDebugEnabled(),
            debugLogs = repo.recentDebugLogs()
        ))
    }

    fun postDebugNotificationBurst() {
        repo.postDebugNotificationBurst()
        state = withNotificationDebugState(state.copy(
            debugLogs = repo.recentDebugLogs(),
            message = "Debug-Benachrichtigungen erzeugt"
        ))
    }

    fun captureNotificationDebugSnapshot() {
        repo.logNotificationDebugSnapshot("profile_debug_button")
        state = withNotificationDebugState(state.copy(
            debugLogs = repo.recentDebugLogs(),
            message = "Notification-Snapshot protokolliert"
        ))
    }

    fun setNotificationDebugEnabled(enabled: Boolean) {
        if (enabled && !state.debugMasterEnabled) {
            state = state.copy(message = "Bitte zuerst den Debug-Modus aktivieren.")
            return
        }
        repo.setNotificationDebugEnabled(enabled)
        if (enabled) {
            repo.refreshNotificationDebugEnvironment("ui_toggle_enabled")
        }
        state = withNotificationDebugState(
            state.copy(
                debugMasterEnabled = repo.debugMasterEnabled(),
                feedDebugEnabled = repo.feedDebugEnabled(),
                debugLogs = repo.recentDebugLogs(),
                message = if (enabled) "Notification-Debugmodus aktiviert" else "Notification-Debugmodus deaktiviert"
            )
        )
    }

    fun clearNotificationDebugData() {
        repo.clearNotificationDebugData(keepMode = true)
        state = withNotificationDebugState(
            state.copy(
                debugLogs = repo.recentDebugLogs(),
                message = "Notification-Debugdaten geloescht"
            )
        )
    }

    fun refreshNotificationDebugState() {
        repo.refreshNotificationDebugEnvironment("ui_manual_refresh")
        state = withNotificationDebugState(
            state.copy(
                debugLogs = repo.recentDebugLogs(),
                message = "Notification-Debug aktualisiert"
            )
        )
    }

    fun notificationDebugSnapshotAndReset() {
        repo.refreshNotificationDebugEnvironment("ui_snapshot_and_reset")
        repo.logNotificationDebugSnapshot("snapshot_and_reset")
        repo.clearNotificationDebugData(keepMode = true)
        state = withNotificationDebugState(
            state.copy(
                debugLogs = repo.recentDebugLogs(),
                message = "Notification-Snapshot erfasst und Debugdaten geloescht"
            )
        )
    }

    fun exportNotificationDebugBundle(): Uri? {
        return runCatching { repo.exportNotificationDebugBundle() }.getOrNull()
    }

    fun clearTrackedNotificationsForDebug() {
        repo.clearTrackedNotificationsForDebug()
        state = withNotificationDebugState(
            state.copy(
                debugLogs = repo.recentDebugLogs(),
                message = "Tracked Notifications geloescht"
            )
        )
    }

    fun clearAllNotificationsForDebug() {
        repo.clearAllNotificationsForDebug()
        state = withNotificationDebugState(
            state.copy(
                debugLogs = repo.recentDebugLogs(),
                message = "cancelAll() ausgefuehrt"
            )
        )
    }

    fun clearTrackedAndAllNotificationsForDebug() {
        repo.clearTrackedAndAllNotificationsForDebug()
        state = withNotificationDebugState(
            state.copy(
                debugLogs = repo.recentDebugLogs(),
                message = "Tracked + cancelAll() ausgefuehrt"
            )
        )
    }

    fun postNotificationDebugScenario(scenarioId: String) {
        repo.postNotificationDebugScenario(scenarioId)
        state = withNotificationDebugState(
            state.copy(
                debugLogs = repo.recentDebugLogs(),
                message = "Notification-Test '$scenarioId' erzeugt"
            )
        )
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
            viewModelScope.launch { ensureCalendarModeLoaded(state.calendarMode, force = false) }
            return
        }
        state = state.copy(activeTab = tab)
    }

    fun setCalendarPickerExpanded(expanded: Boolean) {
        state = state.copy(calendarPickerExpanded = expanded)
    }

    fun selectCalendarDay(day: String) {
        state = state.copy(calendarSelectedDay = day)
    }

    fun setCalendarMode(mode: CalendarMode) {
        state = state.copy(calendarMode = mode, calendarPickerExpanded = mode == CalendarMode.SEARCH)
        viewModelScope.launch { ensureCalendarModeLoaded(mode, force = false) }
    }

    fun setCalendarTimeCapsuleFilter(filter: TimeCapsuleFilter) {
        state = state.copy(calendarTimeCapsuleFilter = filter)
    }

    fun setCalendarBookmarksFilter(filter: BookmarkCalendarFilter) {
        if (state.calendarBookmarksFilter == filter) return
        state = state.copy(calendarBookmarksFilter = filter)
        viewModelScope.launch { ensureCalendarModeLoaded(CalendarMode.BOOKMARKS, force = true) }
    }

    fun setCalendarSearchQuery(query: String) {
        state = state.copy(calendarSearchQuery = query)
    }

    fun openCalendarSearch(query: String) {
        state = state.copy(
            activeTab = AppTab.CALENDAR,
            calendarMode = CalendarMode.SEARCH,
            calendarPickerExpanded = true,
            calendarSearchQuery = query
        )
        viewModelScope.launch { ensureCalendarModeLoaded(CalendarMode.SEARCH, force = true) }
    }

    fun submitCalendarSearch() {
        state = state.copy(calendarMode = CalendarMode.SEARCH)
        viewModelScope.launch { ensureCalendarModeLoaded(CalendarMode.SEARCH, force = true) }
    }

    private suspend fun ensureCalendarModeLoaded(mode: CalendarMode, force: Boolean) {
        if (state.calendarLoading && !force) return
        state = state.copy(calendarLoading = true)
        try {
            when (mode) {
                CalendarMode.PUBLIC -> {
                    if (!force && state.calendarPublicData.days.isNotEmpty()) {
                        applyCalendarDataset(state.calendarPublicData)
                    } else {
                        val payload = repo.calendarPublic()
                        val dataset = payload.toDataset()
                        state = state.copy(
                            calendarPublicData = dataset
                        )
                        applyCalendarDataset(dataset)
                    }
                }
                CalendarMode.BOOKMARKS -> {
                    if (!force && state.calendarBookmarksData.days.isNotEmpty()) {
                        applyCalendarDataset(state.calendarBookmarksData)
                    } else {
                        val payload = repo.calendarBookmarks(state.calendarBookmarksFilter)
                        val dataset = payload.toDataset()
                        state = state.copy(calendarBookmarksData = dataset)
                        applyCalendarDataset(dataset)
                    }
                }
                CalendarMode.SEARCH -> {
                    val query = state.calendarSearchQuery.trim()
                    if (query.isBlank()) {
                        val empty = CalendarSearchDataset()
                        state = state.copy(calendarSearchData = empty)
                        applyCalendarDataset(empty.dataset)
                    } else {
                        val cached = state.calendarSearchData.takeIf { it.normalizedQuery == query.lowercase() }
                        if (!force && cached != null && cached.dataset.days.isNotEmpty()) {
                            applyCalendarDataset(cached.dataset)
                        } else {
                            val payload = repo.calendarSearch(query)
                            val dataset = payload.toDataset()
                            state = state.copy(
                                calendarSearchData = dataset,
                                calendarSearchQuery = payload.query.ifBlank { query }
                            )
                            applyCalendarDataset(dataset.dataset)
                        }
                    }
                }
                CalendarMode.TIME_CAPSULES -> {
                    if (!force && state.calendarTimeCapsulesData.days.isNotEmpty()) {
                        applyCalendarDataset(state.calendarTimeCapsulesData)
                    } else {
                        val payload = repo.calendarTimeCapsules()
                        val dataset = payload.toDataset()
                        state = state.copy(calendarTimeCapsulesData = dataset)
                        applyCalendarDataset(dataset)
                    }
                }
            }
        } catch (t: Throwable) {
            state = state.copy(message = apiError(t, "Kalender laden fehlgeschlagen"))
        } finally {
            state = state.copy(calendarLoading = false)
        }
    }

    suspend fun toggleBookmark(photoId: Long, bookmarked: Boolean) {
        updateBookmarkStateLocally(photoId, bookmarked)
        adjustBookmarkCounters(photoId, if (bookmarked) 1 else -1)
        state.feedByDay.values.asSequence().flatten().firstOrNull { it.photo.id == photoId }?.photo?.let { photo ->
            upsertPendingFeedMutation(photoId) { it.copy(photoOverride = photo) }
        }
        try {
            val serverPhoto = if (bookmarked) {
                repo.bookmarkPhoto(photoId)
            } else {
                val updatedPhoto = repo.unbookmarkPhoto(photoId)
                removeBookmarkFromBookmarksDataset(photoId)
                updatedPhoto
            }
            serverPhoto?.let(::applyServerPhoto)
            if (state.calendarMode == CalendarMode.BOOKMARKS || state.calendarBookmarksData.days.isNotEmpty()) {
                ensureCalendarModeLoaded(CalendarMode.BOOKMARKS, force = true)
            }
        } catch (t: Throwable) {
            updateBookmarkStateLocally(photoId, !bookmarked)
            adjustBookmarkCounters(photoId, if (bookmarked) -1 else 1)
            clearPendingFeedMutation(photoId)
            state = state.copy(message = apiError(t, "Merken fehlgeschlagen"))
        }
    }

    suspend fun toggleMark(photoId: Long, marked: Boolean) {
        patchPhotoState(photoId) { photo -> photo.copy(markedByMe = marked) }
        state.feedByDay.values.asSequence().flatten().firstOrNull { it.photo.id == photoId }?.photo?.let { photo ->
            upsertPendingFeedMutation(photoId) { it.copy(photoOverride = photo) }
        }
        try {
            val serverPhoto = if (marked) {
                repo.markPhoto(photoId)
            } else {
                repo.unmarkPhoto(photoId, null)
            }
            clearPendingFeedMutation(photoId)
            serverPhoto?.let(::applyServerPhoto)
            refreshVisibleFeedAfterCreativeMutation(photoId, reason = "photo_mark")
        } catch (t: Throwable) {
            patchPhotoState(photoId) { photo -> photo.copy(markedByMe = !marked) }
            clearPendingFeedMutation(photoId)
            state = state.copy(message = apiError(t, "Markieren fehlgeschlagen"))
        }
    }

    suspend fun deletePhotoMark(photoId: Long, targetUserId: Long? = null) {
        runCatching { repo.unmarkPhoto(photoId, targetUserId) }
            .onSuccess { serverPhoto ->
                clearPendingFeedMutation(photoId)
                serverPhoto?.let(::applyServerPhoto)
                refreshVisibleFeedAfterCreativeMutation(photoId, reason = "photo_mark_delete")
                state = state.copy(message = "Markierung entfernt")
            }
            .onFailure {
                clearPendingFeedMutation(photoId)
                state = state.copy(message = apiError(it, "Markierung entfernen fehlgeschlagen"))
            }
    }

    suspend fun savePhotoPaint(photoId: Long, paths: List<PhotoPaintPath>, strokeWidth: Float) {
        runCatching { repo.savePhotoPaint(photoId, paths, strokeWidth) }
            .onSuccess { serverPhoto ->
                clearPendingFeedMutation(photoId)
                serverPhoto?.let(::applyServerPhoto)
                refreshVisibleFeedAfterCreativeMutation(photoId, reason = "photo_paint_save")
                state = state.copy(message = "Malerei gespeichert")
            }
            .onFailure {
                clearPendingFeedMutation(photoId)
                state = state.copy(message = apiError(it, "Malerei speichern fehlgeschlagen"))
            }
    }

    suspend fun deletePhotoPaint(photoId: Long, targetUserId: Long? = null) {
        runCatching { repo.deletePhotoPaint(photoId, targetUserId) }
            .onSuccess { serverPhoto ->
                clearPendingFeedMutation(photoId)
                serverPhoto?.let(::applyServerPhoto)
                refreshVisibleFeedAfterCreativeMutation(photoId, reason = "photo_paint_delete")
                state = state.copy(message = "Malerei entfernt")
            }
            .onFailure {
                clearPendingFeedMutation(photoId)
                state = state.copy(message = apiError(it, "Malerei entfernen fehlgeschlagen"))
            }
    }

    suspend fun reportPhoto(photoId: Long) {
        runCatching { repo.reportPhoto(photoId) }
            .onSuccess {
                state = state.copy(message = it.message?.takeIf(String::isNotBlank) ?: "Danke fuer dein Feedback, wir pruefen das.")
            }
            .onFailure {
                state = state.copy(message = apiError(it, "Melden fehlgeschlagen"))
            }
    }

    suspend fun togglePhotoNsfw(photoId: Long, nsfw: Boolean) {
        patchPhotoState(photoId) { photo ->
            photo.copy(
                nsfw = nsfw,
                nsfwMarkedByUserId = if (nsfw) state.user?.id else null,
                nsfwMarkedAt = if (nsfw) Instant.now().toString() else null
            )
        }
        try {
            val serverPhoto = if (nsfw) {
                repo.markPhotoNsfw(photoId)
            } else {
                repo.unmarkPhotoNsfw(photoId)
            }
            clearPendingFeedMutation(photoId)
            serverPhoto?.let(::applyServerPhoto)
            refreshVisibleFeedAfterCreativeMutation(photoId, reason = if (nsfw) "photo_nsfw_mark" else "photo_nsfw_unmark")
            state = state.copy(message = if (nsfw) "Beitrag als NSFW markiert" else "NSFW-Markierung entfernt")
        } catch (t: Throwable) {
            patchPhotoState(photoId) { photo ->
                photo.copy(
                    nsfw = !nsfw,
                    nsfwMarkedByUserId = if (!nsfw) state.user?.id else null,
                    nsfwMarkedAt = if (!nsfw) Instant.now().toString() else null
                )
            }
            state = state.copy(message = apiError(t, "NSFW-Aktion fehlgeschlagen"))
        }
    }

    suspend fun clearAllBookmarks() {
        state = state.copy(loading = true)
        runCatching { repo.clearBookmarks() }
            .onSuccess { deletedCount ->
                clearAllBookmarksLocally()
                state = state.copy(
                    loading = false,
                    message = if (deletedCount == 1) "1 gemerkter Beitrag entfernt" else "$deletedCount gemerkte Beitraege entfernt"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Bookmarks aufraeumen fehlgeschlagen"))
            }
    }

    fun clearFeedPhotoFocus() {
        if (state.feedFocusPhotoId != null || state.feedFocusDay != null || state.feedFocusBoundary != null) {
            state = state.copy(
                feedFocusPhotoId = null,
                feedFocusDay = null,
                feedFocusBoundary = null
            )
        }
    }

    fun updateFeedVisibleAnchor(day: String?) {
        updateFeedViewportAnchor(FeedViewportAnchor(day = day))
    }

    suspend fun jumpToDay(day: String) {
        clearHiddenNewerContentIfReached(day)
        val scrollRequestId = issueFeedScrollRequestId()
        state = state.copy(
            activeTab = AppTab.FEED,
            feedFocusDay = day,
            feedFocusPhotoId = null,
            feedFocusBoundary = FeedJumpBoundary.START,
            feedScrollRequestId = scrollRequestId
        )
        runCatching { loadFeedWindow(day, around = 2, forceReload = false) }
            .onFailure {
                state = state.copy(message = apiError(it, "Feed-Sprung fehlgeschlagen"))
            }
    }

    suspend fun jumpToPhoto(day: String, photoId: Long) {
        clearHiddenNewerContentIfReached(day)
        val scrollRequestId = issueFeedScrollRequestId()
        state = state.copy(
            activeTab = AppTab.FEED,
            feedFocusDay = day,
            feedFocusPhotoId = photoId,
            feedFocusBoundary = null,
            feedScrollRequestId = scrollRequestId
        )
        runCatching { loadFeedWindow(day, around = 2, forceReload = false) }
            .onFailure {
                state = state.copy(message = apiError(it, "Beitrag laden fehlgeschlagen"))
            }
    }

    suspend fun jumpToDayBoundary(day: String, boundary: FeedJumpBoundary) {
        clearHiddenNewerContentIfReached(day)
        val scrollRequestId = issueFeedScrollRequestId()
        state = state.copy(
            activeTab = AppTab.FEED,
            feedFocusDay = day,
            feedFocusPhotoId = null,
            feedFocusBoundary = boundary,
            feedScrollRequestId = scrollRequestId
        )
        runCatching { loadFeedWindow(day, around = 2, forceReload = false) }
            .onFailure {
                state = state.copy(message = apiError(it, "Feed-Sprung fehlgeschlagen"))
            }
    }

    private fun clearHiddenNewerContentIfReached(day: String?) {
        val targetDay = day?.trim().orEmpty()
        if (targetDay.isBlank()) return
        if (state.feedHiddenNewerAnchorDay == targetDay || targetDay == state.feedDays.firstOrNull()) {
            state = state.copy(feedHasHiddenNewerContent = false, feedHiddenNewerAnchorDay = null)
        }
    }

    private fun registerFeedInvalidation(
        day: String,
        photoId: Long? = null,
        reason: String,
        source: String,
        scheduledRefresh: Boolean
    ) {
        val cleanDay = day.trim()
        if (cleanDay.isBlank()) return
        staleFeedDays.add(cleanDay)
        repo.queueFeedInvalidation(cleanDay, photoId, reason = reason, source = source)
        logFeedDecision(
            type = "feed_push_invalidation",
            message = "feed invalidation queued",
            meta = "pushType=$reason;action=$source;targetDay=$cleanDay;targetPhotoId=${photoId ?: -1L};markedStale=true;scheduledRefresh=$scheduledRefresh;immediateRefreshEligible=${state.activeTab == AppTab.FEED && !state.feedRefreshing}"
        )
    }

    private fun consumePersistedFeedInvalidations(reason: String) {
        val pending = repo.consumePendingFeedInvalidations()
        if (pending.isEmpty()) return
        pending.forEach { item ->
            val day = item.targetDay.trim()
            if (day.isNotBlank()) {
                staleFeedDays.add(day)
                logFeedDecision(
                    type = "feed_push_invalidation",
                    message = "persisted feed invalidation consumed",
                    meta = "pushType=${item.type.ifBlank { "-" }};action=${item.action.ifBlank { "-" }};targetDay=$day;targetPhotoId=${item.targetPhotoId ?: -1L};markedStale=true;scheduledRefresh=${reason == "feed_auto"};immediateRefreshEligible=${state.activeTab == AppTab.FEED && !state.feedRefreshing}"
                )
            }
        }
    }

    private fun applyBackgroundFeedDay(day: String, result: DayFetchResult) {
        val cacheMap = state.feedByDay.toMutableMap()
        val promptMap = state.promptMetaByDay.toMutableMap()
        val recapMap = state.monthRecapByDay.toMutableMap()
        cacheMap[day] = result.items.map(::applyPendingFeedMutation)
        promptMap[day] = result.meta
        if (result.monthRecap != null) {
            recapMap[day] = result.monthRecap
        } else {
            recapMap.remove(day)
        }
        val mergedKnownDays = mergeDayIndex(state.calendarDays, listOf(day))
        val pruned = pruneFeedCaches(
            feedByDay = cacheMap,
            promptMetaByDay = promptMap,
            monthRecapByDay = recapMap,
            visibleDays = state.feedDays,
            anchorDay = state.feedVisibleAnchorDay ?: day
        )
        val hiddenNewerDay = when {
            state.feedDays.isEmpty() -> null
            compareDayStrings(day, state.feedDays.first()) > 0 -> maxOf(day, state.feedHiddenNewerAnchorDay ?: day)
            else -> state.feedHiddenNewerAnchorDay
        }
        state = state.copy(
            calendarDays = mergedKnownDays,
            feedByDay = pruned.feedByDay,
            promptMetaByDay = pruned.promptMetaByDay,
            monthRecapByDay = pruned.monthRecapByDay,
            feedHasHiddenNewerContent = hiddenNewerDay != null,
            feedHiddenNewerAnchorDay = hiddenNewerDay
        )
    }

    private fun compareDayStrings(a: String?, b: String?): Int {
        val av = a?.trim().orEmpty()
        val bv = b?.trim().orEmpty()
        return when {
            av > bv -> 1
            av < bv -> -1
            else -> 0
        }
    }

    private suspend fun refreshOffscreenStaleDays(trigger: String): Int {
        val visibleSet = state.feedDays.toSet()
        val hiddenDays = staleFeedDays.filter { it !in visibleSet }.distinct().sortedDescending()
        if (hiddenDays.isEmpty()) return 0
        var refreshed = 0
        hiddenDays.take(2).forEach { day ->
            val result = fetchDaySafe(day, forceReload = true)
            applyBackgroundFeedDay(day, result)
            staleFeedDays.remove(day)
            refreshed += 1
        }
        if (refreshed > 0) {
            logFeedDecision(
                type = "feed_refresh_result",
                message = "offscreen stale days refreshed",
                meta = "result=fetched_partial;trigger=$trigger;refreshed=$refreshed;hiddenNewer=${state.feedHasHiddenNewerContent};hiddenAnchor=${state.feedHiddenNewerAnchorDay ?: "-"}"
            )
        }
        return refreshed
    }

    suspend fun refreshAll(
        reason: String = "general",
        forceFeedReload: Boolean = false,
        refreshFeedWindow: Boolean = true,
        bypassCooldown: Boolean = false,
        showLoading: Boolean = true,
        respectCircuitBreaker: Boolean = true
    ): Boolean {
        consumePersistedFeedInvalidations(reason)
        if (repo.token().isBlank()) {
            lastRefreshExecutionDisposition = RefreshExecutionDisposition.NO_TOKEN
            return false
        }
        val priority = refreshPriorityFor(reason, forceFeedReload)
        val now = System.currentTimeMillis()
        if (respectCircuitBreaker) {
            val remaining = refreshCircuitOpenRemainingMs(now)
            if (remaining > 0L) {
                lastRefreshExecutionDisposition = RefreshExecutionDisposition.SKIPPED_CIRCUIT
                repo.logDebug(
                    type = "refresh_skipped",
                    message = "refresh circuit breaker open",
                    meta = "reason=$reason;remainingMs=$remaining;failureClass=$lastRefreshFailureClass;backoffStage=$consecutiveNetworkRefreshFailures"
                )
                return false
            }
        }
        if (!refreshAllMutex.tryLock()) {
            lastRefreshExecutionDisposition = RefreshExecutionDisposition.QUEUED
            queueRefreshRequest(
                QueuedRefreshRequest(
                    reason = reason,
                    forceFeedReload = forceFeedReload,
                    refreshFeedWindow = refreshFeedWindow,
                    bypassCooldown = bypassCooldown,
                    showLoading = showLoading,
                    respectCircuitBreaker = respectCircuitBreaker,
                    priority = priority
                )
            )
            repo.logDebug(
                type = "refresh_deferred",
                message = "refresh queued behind active refresh",
                meta = "reason=$reason;priority=${priority.name.lowercase()};forced=$forceFeedReload;refreshFeedWindow=$refreshFeedWindow;showLoading=$showLoading"
            )
            logFeedDecision(
                type = "feed_refresh_result",
                message = "feed refresh deferred",
                meta = "result=deferred;reason=$reason;priority=${priority.name.lowercase()};forced=$forceFeedReload"
            )
            return false
        }
        if (!bypassCooldown && now - lastRefreshAllStartedAt < refreshAllCooldownMs) {
            lastRefreshExecutionDisposition = RefreshExecutionDisposition.SKIPPED_COOLDOWN
            refreshAllMutex.unlock()
            repo.logDebug(
                type = "refresh_skipped",
                message = "refresh cooldown active",
                meta = "reason=$reason;cooldownMs=$refreshAllCooldownMs;elapsedSinceLastStartMs=${now - lastRefreshAllStartedAt}"
            )
            logFeedDecision(
                type = "feed_refresh_result",
                message = "feed refresh skipped by cooldown",
                meta = "result=noop_skipped;reason=$reason;elapsedSinceLastStartMs=${now - lastRefreshAllStartedAt}"
            )
            return false
        }
        lastRefreshAllStartedAt = now
        val viewportAnchorBeforeRefresh = effectiveRefreshViewportAnchor()
        if (showLoading) {
            state = state.copy(loading = true, communityStatsLoading = true)
        } else {
            state = state.copy(communityStatsLoading = true)
        }
        var success = false
        var refreshedFeedDays = 0
        var failedCall = "none"
        try {
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
                    bookmarksGivenCount = bootstrap.me.bookmarksGivenCount,
                    bookmarksReceivedCount = bootstrap.me.bookmarksReceivedCount,
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
                    if (!isBenignCancellation(meErr)) {
                        val failureClass = classifyFailure(meErr)
                        repo.logDebug(
                            type = "dashboard_refresh_degraded",
                            message = debugFailureMessage(meErr),
                            meta = "failedCall=me;fallback=cached_user;failureClass=$failureClass"
                        )
                    }
                    MeResponse(
                        user = cachedUser,
                        dailyMomentCount = state.dailyMomentCount,
                        streakDays = state.streakDays,
                        bookmarksGivenCount = state.bookmarksGivenCount,
                        bookmarksReceivedCount = state.bookmarksReceivedCount
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
                        chatMessageMaxLength = DEFAULT_CHAT_MESSAGE_MAX_LENGTH,
                        chatMessageUnlimited = false,
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
                val feedDays = runCatching { repo.feedDays(limit = 60) }.getOrElse {
                    failedCall = "feedDays"
                    DayListResponse(
                        items = state.calendarDays,
                        hasOlder = state.feedIndexHasOlder,
                        hasNewer = state.feedIndexHasNewer
                    )
                }
                val communityStats = runCatching { repo.communityStats() }.getOrElse {
                    failedCall = "communityStats"
                    state.communityStats
                }
                DashboardData(
                    me = meResp.user,
                    streakDays = meResp.streakDays,
                    dailyMomentCount = meResp.dailyMomentCount,
                    bookmarksGivenCount = meResp.bookmarksGivenCount,
                    bookmarksReceivedCount = meResp.bookmarksReceivedCount,
                    inviteCode = fetchedInviteCode,
                    prompt = fetchedPrompt,
                    rules = fetchedRules,
                    special = fetchedSpecial,
                    photos = fetchedPhotos,
                    chat = fetchedChat,
                    feedDays = feedDays.items,
                    communityStats = communityStats
                )
            }
            var me = payload.me
            val streakDays = payload.streakDays
            val dailyMomentCount = payload.dailyMomentCount
            val bookmarksGivenCount = payload.bookmarksGivenCount
            val bookmarksReceivedCount = payload.bookmarksReceivedCount
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
            val feedIndexHasOlder = calendarDays.size >= 60
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
            repo.setBookmarkedPhotoPushLocalEnabled(me.bookmarkedPhotoPushEnabled)
            repo.setPostChangePushLocalEnabled(me.postChangePushEnabled)
            repo.setAutoSubscribeInteractedPostsLocalEnabled(me.autoSubscribeInteractedPostsEnabled)
            repo.setOwnPostNumberInPushLocalEnabled(me.ownPostNumberInPushEnabled)
            repo.setPostNumberInPushLocalEnabled(me.postNumberInPushEnabled)
            repo.setYoloModeLocalEnabled(me.yoloModeEnabled)
            val notificationMaster = repo.notificationMasterEnabled()
            val feedPostPushEnabled = repo.feedPostPushEnabled()
            val pollPushEnabled = repo.pollPushLocalEnabled()
            val specialMomentPushEnabled = repo.specialMomentPushLocalEnabled()
            val inviteRegistrationPushEnabled = repo.inviteRegistrationPushLocalEnabled()
            val photoReactionPushEnabled = repo.photoReactionPushLocalEnabled()
            val photoCommentPushEnabled = repo.photoCommentPushLocalEnabled()
            val bookmarkedPhotoPushEnabled = repo.bookmarkedPhotoPushLocalEnabled()
            val postChangePushEnabled = repo.postChangePushLocalEnabled()
            val autoSubscribeInteractedPostsEnabled = repo.autoSubscribeInteractedPostsLocalEnabled()
            val ownPostNumberInPushEnabled = repo.ownPostNumberInPushLocalEnabled()
            val postNumberInPushEnabled = repo.postNumberInPushLocalEnabled()
            val autoUpdateEnabled = repo.autoUpdateEnabled()
            val profileSectionExpanded = profileSectionIds.associateWith { sectionId ->
                repo.getProfileSectionExpanded(me.id, sectionId)
            }

            state = refreshConnectionHealthState(state.copy(
                user = me,
                myInviteCode = inviteCode,
                prompt = prompt,
                promptRules = rules,
                specialMomentStatus = special,
                photos = photos,
                streakDays = streakDays,
                dailyMomentCount = dailyMomentCount,
                bookmarksGivenCount = bookmarksGivenCount,
                bookmarksReceivedCount = bookmarksReceivedCount,
                chat = chat,
                chatHasOtherMessages = true,
                chatHasUnreadMessages = hasUnreadChat,
                calendarDays = calendarDays,
                feedIndexHasOlder = feedIndexHasOlder,
                feedIndexHasNewer = false,
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
                bookmarkedPhotoPushEnabled = bookmarkedPhotoPushEnabled,
                postChangePushEnabled = postChangePushEnabled,
                autoSubscribeInteractedPostsEnabled = autoSubscribeInteractedPostsEnabled,
                ownPostNumberInPushEnabled = ownPostNumberInPushEnabled,
                postNumberInPushEnabled = postNumberInPushEnabled,
                yoloModeEnabled = me.yoloModeEnabled,
                showPublicPostNumbers = repo.showPublicPostNumbers(),
                preferSwipeForTwoImagePosts = repo.preferSwipeForTwoImagePosts(),
                notificationMasterEnabled = computeNotificationMaster(notificationMaster && autoUpdateEnabled, me.chatPushEnabled, feedPostPushEnabled, pollPushEnabled, inviteRegistrationPushEnabled, photoReactionPushEnabled, photoCommentPushEnabled, bookmarkedPhotoPushEnabled, postChangePushEnabled),
                debugMasterEnabled = repo.debugMasterEnabled(),
                feedDebugEnabled = repo.feedDebugEnabled(),
                diagnosticsUploadEnabled = repo.diagnosticsUploadEnabled() && me.diagnosticsConsentGranted,
                diagnosticsConsentGranted = me.diagnosticsConsentGranted,
                diagnosticsConsentUpdatedAt = me.diagnosticsConsentUpdatedAt,
                debugLogs = repo.recentDebugLogs(),
                showConnectionHealthIndicator = repo.showConnectionHealthIndicator(),
                profileSectionExpanded = profileSectionExpanded,
                loading = if (showLoading) false else state.loading,
                showPromptDialog = state.showPromptDialog || shouldPopup,
                message = ""
            ))
            if (me.yoloModeEnabled) {
                runCatching { applyYoloFeatures(forceAll = false, reason = "refresh_all") }
                    .onFailure { state = state.copy(message = apiError(it, "YOLO-Feature-Sync fehlgeschlagen")) }
            }
            runCatching { repo.calendarPublic() }.getOrNull()?.let { publicCalendar ->
                val dataset = publicCalendar.toDataset()
                state = state.copy(
                    calendarPublicData = dataset
                )
                if (state.calendarMode == CalendarMode.PUBLIC) {
                    applyCalendarDataset(dataset)
                }
            }
            repo.setDiagnosticsConsentLocal(me.diagnosticsConsentGranted)
            if (!me.diagnosticsConsentGranted && state.diagnosticsUploadEnabled) {
                repo.setDiagnosticsUploadEnabled(false)
                state = state.copy(diagnosticsUploadEnabled = false)
            }
            syncPendingDiagnosticsConsent(me)
            evaluateDiagnosticsConsentPrompt()
            applyPendingLaunchNavigation(prompt, calendarDays)
            maybeShowProfileSetupPrompt(me)
            if (refreshFeedWindow) {
                val viewportAnchor = viewportAnchorBeforeRefresh ?: state.feedViewportAnchor
                val focus = state.feedFocusDay.takeIf { hasPendingFeedNavigation() }
                val preferredAnchor = when {
                    !viewportAnchor.day.isNullOrBlank() && calendarDays.contains(viewportAnchor.day) -> viewportAnchor.day
                    !focus.isNullOrBlank() && calendarDays.contains(focus) -> focus
                    else -> prompt.day
                }
                val preserveVisibleWindow = viewportAnchorBeforeRefresh != null &&
                    state.feedDays.isNotEmpty() &&
                    state.feedDays.contains(preferredAnchor)
                val newestCalendarDay = calendarDays.firstOrNull()
                val loadedNewestDay = state.feedDays.firstOrNull()
                if (!newestCalendarDay.isNullOrBlank() && !loadedNewestDay.isNullOrBlank() && compareDayStrings(newestCalendarDay, loadedNewestDay) > 0) {
                    staleFeedDays.add(newestCalendarDay)
                }
                val offscreenStaleDays = staleFeedDays.filter { it !in state.feedDays.toSet() }.distinct().sortedDescending()
                val shouldFetchVisibleWindow = when {
                    state.feedDays.isEmpty() -> true
                    !state.feedDays.contains(preferredAnchor) -> true
                    forceFeedReload -> true
                    staleFeedDays.any { it in state.feedDays } -> true
                    reason == "feed_auto" && state.activeTab == AppTab.FEED -> true
                    else -> false
                }
                val replaceVisibleDays = state.feedDays.isEmpty() || !state.feedDays.contains(preferredAnchor) || !preserveVisibleWindow
                val showJumpLoading = state.feedDays.isEmpty() || !state.feedDays.contains(preferredAnchor) || !preserveVisibleWindow
                logFeedDecision(
                    type = "feed_refresh_plan",
                    message = "feed refresh planned",
                    meta = "reason=$reason;refreshMode=${if (refreshFeedWindow) "feed_window" else "silent"};preferredAnchor=$preferredAnchor;viewportAnchorBefore=${describeAnchor(viewportAnchorBeforeRefresh)};preserveVisibleWindow=$preserveVisibleWindow;replaceVisibleDays=$replaceVisibleDays;showJumpLoading=$showJumpLoading;offscreenStaleDays=${offscreenStaleDays.joinToString(",").ifBlank { "-" }};willFetch=$shouldFetchVisibleWindow"
                )
                logFeedDecision(
                    type = "feed_auto_decision",
                    message = if (shouldFetchVisibleWindow) "feed refresh will fetch" else "feed refresh may reuse cache",
                    meta = "reason=$reason;activeTab=${state.activeTab.name.lowercase()};feedRefreshing=${state.feedRefreshing};loading=${state.loading};hasPendingNavigation=${hasPendingFeedNavigation()};staleFeedDays=${staleFeedDays.joinToString(",").ifBlank { "-" }};forceFeedReload=$forceFeedReload;willFetch=$shouldFetchVisibleWindow;decisionReason=${if (shouldFetchVisibleWindow) "visible_window_refresh" else "cached_today_feed"}"
                )
                if (state.feedDays.isEmpty() || !state.feedDays.contains(preferredAnchor)) {
                    refreshedFeedDays = loadFeedWindow(
                        anchorDay = preferredAnchor,
                        around = 1,
                        forceReload = forceFeedReload,
                        replaceVisibleDays = true,
                        showJumpLoading = true
                    )
                } else {
                    refreshedFeedDays = if (shouldFetchVisibleWindow) {
                        loadFeedWindow(
                            anchorDay = preferredAnchor,
                            around = 1,
                            forceReload = forceFeedReload,
                            replaceVisibleDays = !preserveVisibleWindow,
                            showJumpLoading = !preserveVisibleWindow
                        )
                    } else {
                        val today = prompt.day
                        val hasVisibleTodayFeed = state.feedByDay[today].orEmpty().isNotEmpty()
                        state = state.copy(
                            feed = state.feedByDay[today].orEmpty(),
                            feedTodayLocked = !prompt.hasVisiblePostToday && !hasVisibleTodayFeed
                        )
                        logFeedDecision(
                            type = "feed_auto_decision",
                            message = "feed refresh resolved to noop",
                            meta = "reason=$reason;activeTab=${state.activeTab.name.lowercase()};feedRefreshing=${state.feedRefreshing};loading=${state.loading};hasPendingNavigation=${hasPendingFeedNavigation()};staleFeedDays=${staleFeedDays.joinToString(",").ifBlank { "-" }};forceFeedReload=$forceFeedReload;willFetch=false;decisionReason=cached_today_feed"
                        )
                        0
                    }
                }
                val partialRefreshDays = refreshOffscreenStaleDays(reason)
                if (partialRefreshDays > 0) {
                    refreshedFeedDays += partialRefreshDays
                }
                if (viewportAnchorBeforeRefresh != null && refreshedFeedDays > 0) {
                    requestFeedViewportRestore(viewportAnchorBeforeRefresh)
                }
            } else {
                val today = prompt.day
                val hasVisibleTodayFeed = state.feedByDay[today].orEmpty().isNotEmpty()
                state = state.copy(
                    feed = state.feedByDay[today].orEmpty(),
                    feedTodayLocked = !prompt.hasVisiblePostToday && !hasVisibleTodayFeed
                )
            }
            ensureCalendarStatsPrefix(2)
            success = true
            lastRefreshExecutionDisposition = RefreshExecutionDisposition.SUCCESS
            markRefreshSuccess()
            lastApiSuccessAtMs = System.currentTimeMillis()
            lastApiFailureAtMs = 0L
            lastApiFailureMessage = ""
            state = refreshConnectionHealthState(state)
            if (reason == "feed_pull" || reason == "feed_auto" || forceFeedReload) {
                val durationMs = System.currentTimeMillis() - now
                repo.logDebug(
                    type = "feed_refresh",
                    message = "feed refresh ${if (refreshedFeedDays > 0) "ok" else "noop"}",
                    meta = "reason=$reason;forced=$forceFeedReload;daysReloaded=$refreshedFeedDays;durationMs=$durationMs;refreshMode=${if (refreshFeedWindow) "feed_window" else "silent"};visibleAnchor=${state.feedVisibleAnchorDay ?: "-"};windowDays=${state.feedDays.joinToString(",").ifBlank { "-" }}"
                )
                logFeedDecision(
                    type = "feed_refresh_result",
                    message = "feed refresh finished",
                    meta = "result=${if (refreshedFeedDays > 0) "fetched_window" else "noop_skipped"};reason=$reason;forced=$forceFeedReload;daysReloaded=$refreshedFeedDays;durationMs=$durationMs;visibleAnchor=${state.feedVisibleAnchorDay ?: "-"}"
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
                lastRefreshExecutionDisposition = RefreshExecutionDisposition.CANCELLED
                state = refreshConnectionHealthState(state.copy(loading = if (showLoading) false else state.loading, communityStatsLoading = false))
                return false
            }
            val failureClass = classifyFailure(actual)
            lastRefreshExecutionDisposition = RefreshExecutionDisposition.FAILURE
            val (backoffStage, delayMs) = markRefreshFailure(failureClass, System.currentTimeMillis())
            if (isNetworkFailureClass(failureClass)) {
                repo.logDebug(
                    type = "network_snapshot",
                    message = "refresh failure network snapshot",
                    meta = "reason=$reason;failureClass=$failureClass;snapshot=${repo.networkSnapshotMeta()}"
                )
            }
            lastApiFailureAtMs = System.currentTimeMillis()
            lastApiFailureMessage = apiError(actual, "Laden fehlgeschlagen")
            state = refreshConnectionHealthState(state.copy(
                loading = if (showLoading) false else state.loading,
                communityStatsLoading = false,
                message = lastApiFailureMessage
            ))
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
                meta = "reason=$reason;forced=$forceFeedReload;durationMs=$durationMs;failedCall=$failedCall;failureClass=$failureClass;refreshMode=${if (refreshFeedWindow) "feed_window" else "silent"};backoffStage=$backoffStage;nextDelayMs=$delayMs;visibleAnchor=${state.feedVisibleAnchorDay ?: "-"};root=${rootCause(actual)::class.java.simpleName};derivedFrom=${if (actual is IllegalStateException && actual.message == "missing_access_token") repo.authStateTransitionReason() else "-"}"
            )
            logFeedDecision(
                type = "feed_refresh_result",
                message = "feed refresh failed",
                meta = "result=failed;reason=$reason;failedCall=$failedCall;failureClass=$failureClass;visibleAnchor=${state.feedVisibleAnchorDay ?: "-"}"
            )
        } finally {
            refreshAllMutex.unlock()
        }
        consumeQueuedRefreshRequest()?.let { queued ->
            return refreshAll(
                reason = queued.reason,
                forceFeedReload = queued.forceFeedReload,
                refreshFeedWindow = queued.refreshFeedWindow,
                bypassCooldown = true,
                showLoading = queued.showLoading,
                respectCircuitBreaker = queued.respectCircuitBreaker
            ) || success
        }
        return success
    }

    suspend fun refreshFeed(reason: String = "feed_pull") {
        if (state.feedRefreshing) {
            repo.logDebug(
                type = "refresh_deferred",
                message = "feed refresh ignored because one is already running",
                meta = "reason=$reason"
            )
            return
        }
        val now = System.currentTimeMillis()
        val isManual = reason == "feed_pull"
        val requiresHardReload = isManual || reason.startsWith("photo_") || reason == "comment_submit"
        if (isManual && isNetworkFailureClass(lastRefreshFailureClass) && now - lastManualRefreshAtMs < manualRefreshDuringNetworkFailureMinIntervalMs) {
            val waitMs = manualRefreshDuringNetworkFailureMinIntervalMs - (now - lastManualRefreshAtMs)
            repo.logDebug(
                type = "refresh_skipped",
                message = "manual refresh throttled during network failure window",
                meta = "reason=$reason;waitMs=$waitMs;lastFailureClass=$lastRefreshFailureClass"
            )
            state = state.copy(message = "Bitte kurz warten (${(waitMs / 1000L).coerceAtLeast(1L)}s), dann erneut aktualisieren.")
            return
        }
        if (isManual) {
            lastManualRefreshAtMs = now
        }
        state = state.copy(feedRefreshing = true)
        lastRefreshExecutionDisposition = RefreshExecutionDisposition.IDLE
        val started = System.currentTimeMillis()
        var ok = false
        try {
            ok = refreshAll(
                reason = reason,
                forceFeedReload = requiresHardReload,
                refreshFeedWindow = true,
                bypassCooldown = true,
                showLoading = false,
                respectCircuitBreaker = !isManual
            )
        } finally {
            val elapsed = System.currentTimeMillis() - started
            val result = when (lastRefreshExecutionDisposition) {
                RefreshExecutionDisposition.SUCCESS -> PerfEventResult.OK
                RefreshExecutionDisposition.FAILURE -> PerfEventResult.ERROR
                RefreshExecutionDisposition.QUEUED -> PerfEventResult.DEFERRED
                RefreshExecutionDisposition.SKIPPED_CIRCUIT,
                RefreshExecutionDisposition.SKIPPED_COOLDOWN,
                RefreshExecutionDisposition.NO_TOKEN -> PerfEventResult.SKIPPED
                RefreshExecutionDisposition.CANCELLED -> PerfEventResult.CANCELLED
                RefreshExecutionDisposition.IDLE -> if (ok) PerfEventResult.OK else PerfEventResult.SKIPPED
            }
            logPerfEvent(
                event = "refresh_feed",
                durationMs = elapsed,
                result = result,
                extra = "reason=$reason;disposition=${lastRefreshExecutionDisposition.name.lowercase()}"
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
        if (state.feedPaging) return
        if (state.feedOrderMode != FeedOrderMode.CHRONO) {
            if (!state.feedIndexHasOlder) return
            loadDiscoverFeed(offset = state.feedDiscoverNextOffset, limitDays = count, appendOlder = true)
            return
        }
        val base = state.feedDays.lastOrNull() ?: return
        var all = state.calendarDays
        var idx = all.indexOf(base)
        if ((idx < 0 || idx >= all.lastIndex) && state.feedIndexHasOlder) {
            val page = runCatching { repo.feedDays(beforeDay = all.lastOrNull(), limit = count) }.getOrNull() ?: return
            all = mergeDayIndex(all, page.items)
            idx = all.indexOf(base)
            state = state.copy(
                calendarDays = all,
                feedIndexHasOlder = page.hasOlder,
                feedIndexHasNewer = state.feedIndexHasNewer || page.hasNewer
            )
        }
        if (idx < 0) return
        val newDays = all.drop(idx + 1).take(count)
        if (newDays.isEmpty()) return
        loadFeedEdgeWindow(anchorDay = newDays.first(), beforeDays = 0, afterDays = newDays.size - 1, appendOlder = true)
    }

    suspend fun loadNewerFeedDays(count: Int = 3) {
        if (state.feedPaging) return
        if (state.feedOrderMode != FeedOrderMode.CHRONO) {
            if (!state.feedIndexHasNewer) return
            val offset = (state.feedDiscoverOffset - count).coerceAtLeast(0)
            loadDiscoverFeed(offset = offset, limitDays = count, appendOlder = false)
            return
        }
        val base = state.feedDays.firstOrNull() ?: return
        var all = state.calendarDays
        var idx = all.indexOf(base)
        if (idx <= 0 && state.feedIndexHasNewer) {
            val page = runCatching { repo.feedDays(afterDay = all.firstOrNull(), limit = count) }.getOrNull() ?: return
            all = mergeDayIndex(all, page.items)
            idx = all.indexOf(base)
            state = state.copy(
                calendarDays = all,
                feedIndexHasOlder = state.feedIndexHasOlder || page.hasOlder,
                feedIndexHasNewer = page.hasNewer
            )
        }
        if (idx <= 0) return
        val start = maxOf(0, idx - count)
        val prependDays = all.subList(start, idx)
        if (prependDays.isEmpty()) return
        loadFeedEdgeWindow(anchorDay = prependDays.last(), beforeDays = prependDays.size - 1, afterDays = 0, appendOlder = false)
    }

    private suspend fun loadFeedWindow(
        anchorDay: String,
        around: Int,
        forceReload: Boolean,
        replaceVisibleDays: Boolean = true,
        showJumpLoading: Boolean = true
    ): Int {
        if (state.feedOrderMode != FeedOrderMode.CHRONO) {
            return loadDiscoverFeed(
                offset = 0,
                limitDays = (around * 2 + 1).coerceAtLeast(5),
                appendOlder = false,
                anchorDay = anchorDay,
                focusPhotoId = state.feedFocusPhotoId,
                replaceVisibleDays = replaceVisibleDays,
                showJumpLoading = showJumpLoading
            )
        }
        if (state.calendarDays.isEmpty()) {
            runCatching { repo.feedDays(limit = 60) }.getOrNull()?.let { page ->
                state = state.copy(
                    calendarDays = mergeDayIndex(state.calendarDays, page.items),
                    feedIndexHasOlder = page.hasOlder,
                    feedIndexHasNewer = page.hasNewer
                )
            }
        }
        val target = anchorDay
        state = state.copy(
            feedDays = if (showJumpLoading) listOf(target) else state.feedDays,
            promptMetaByDay = if (showJumpLoading) state.promptMetaByDay + (target to PromptMeta(day = target)) else state.promptMetaByDay,
            feedJumpLoadingDay = target.takeIf { showJumpLoading },
            feedWindowReloadInFlight = !showJumpLoading,
            feedPaging = true
        )
        return try {
            val window = repo.feedWindow(
                anchorDay = target,
                beforeDays = around,
                afterDays = around,
                focusPhotoId = state.feedFocusPhotoId
            )
            applyFeedWindow(window, target, replaceVisibleDays = replaceVisibleDays, forceReload = forceReload)
        } catch (t: Throwable) {
            if (isFeedLockedTodayError(t, target)) {
                applyTodayFeedLockedState(target)
                return 0
            }
            state = state.copy(feedJumpLoadingDay = null, feedPaging = false, feedWindowReloadInFlight = false)
            throw t
        }
    }

    private suspend fun loadFeedEdgeWindow(anchorDay: String, beforeDays: Int, afterDays: Int, appendOlder: Boolean) {
        if (state.feedOrderMode != FeedOrderMode.CHRONO) {
            loadDiscoverFeed(
                offset = if (appendOlder) state.feedDiscoverNextOffset else (state.feedDiscoverOffset - maxOf(beforeDays, afterDays, 1)).coerceAtLeast(0),
                limitDays = maxOf(beforeDays, afterDays, 1),
                appendOlder = appendOlder,
                replaceVisibleDays = false
            )
            return
        }
        state = state.copy(feedPaging = true, feedWindowReloadInFlight = true)
        try {
            runCatching { repo.feedWindow(anchorDay = anchorDay, beforeDays = beforeDays, afterDays = afterDays) }
                .onSuccess { window ->
                    applyFeedWindow(window, anchorDay, replaceVisibleDays = false, forceReload = false, appendOlder = appendOlder)
                }
                .onFailure { throwable ->
                    if (isFeedLockedTodayError(throwable, anchorDay)) {
                        applyTodayFeedLockedState(anchorDay)
                    }
                }
        } finally {
            if (state.feedPaging) {
                state = state.copy(feedPaging = false, feedWindowReloadInFlight = false)
            }
        }
    }

    private suspend fun loadDiscoverFeed(
        offset: Int,
        limitDays: Int,
        appendOlder: Boolean,
        anchorDay: String? = null,
        focusPhotoId: Long? = null,
        replaceVisibleDays: Boolean = false,
        showJumpLoading: Boolean = false
    ): Int {
        state = state.copy(
            feedPaging = true,
            feedJumpLoadingDay = state.feedFocusDay.takeIf { showJumpLoading },
            feedWindowReloadInFlight = true
        )
        return try {
            val seed = if (state.feedOrderMode == FeedOrderMode.RANDOM) {
                if (offset == 0 && state.feedRefreshing) {
                    val next = System.currentTimeMillis()
                    repo.setRandomFeedSeed(next)
                    state = state.copy(randomFeedSeed = next)
                    next
                } else {
                    state.randomFeedSeed.takeIf { it != 0L } ?: System.currentTimeMillis().also { repo.setRandomFeedSeed(it) }
                }
            } else null
            val window = repo.feedDiscover(
                mode = state.feedOrderMode,
                offset = offset,
                limitDays = limitDays.coerceAtLeast(1),
                randomSeed = seed,
                anchorDay = anchorDay,
                focusPhotoId = focusPhotoId
            )
            applyFeedWindow(window, window.anchorDay.ifBlank { state.feedFocusDay.orEmpty() }, replaceVisibleDays = replaceVisibleDays, forceReload = false, appendOlder = appendOlder)
        } finally {
            if (state.feedPaging) {
                state = state.copy(feedPaging = false, feedJumpLoadingDay = null, feedWindowReloadInFlight = false)
            }
        }
    }

    private fun mergeVisibleFeedDays(windowDays: List<String>, replaceVisibleDays: Boolean, appendOlder: Boolean): List<String> {
        return when {
            replaceVisibleDays -> windowDays
            state.feedOrderMode == FeedOrderMode.CHRONO -> mergeDayIndex(state.feedDays, windowDays)
            appendOlder -> (state.feedDays + windowDays).distinct()
            else -> (windowDays + state.feedDays).distinct()
        }
    }

    private fun applyFeedWindow(
        window: FeedWindowResponse,
        requestedAnchorDay: String,
        replaceVisibleDays: Boolean,
        forceReload: Boolean,
        appendOlder: Boolean = false
    ): Int {
        val windowDays = window.days.mapNotNull { it.day }.distinct()
        if (windowDays.isEmpty()) {
            val today = state.prompt?.day ?: LocalDate.now().toString()
            state = state.copy(
                feedDays = emptyList(),
                feed = emptyList(),
                feedJumpLoadingDay = null,
                feedPaging = false,
                feedTodayLocked = state.prompt?.hasVisiblePostToday == false && today == requestedAnchorDay
            )
            return 0
        }
        val cacheMap = state.feedByDay.toMutableMap()
        val promptMap = state.promptMetaByDay.toMutableMap()
        val recapMap = state.monthRecapByDay.toMutableMap()
        window.days.forEach { dayPayload ->
            val day = dayPayload.day ?: return@forEach
            dayPayload.items.forEach(::reconcilePendingFeedMutation)
            cacheMap[day] = dayPayload.items.map(::applyPendingFeedMutation)
            promptMap[day] = PromptMeta(
                day = day,
                triggeredAt = dayPayload.triggeredAt,
                uploadUntil = dayPayload.uploadUntil,
                triggerSource = dayPayload.triggerSource,
                requestedByUser = dayPayload.requestedByUser,
                momentKind = dayPayload.momentKind,
                specialRequestedByUser = dayPayload.specialRequestedByUser,
                specialRequestedByUserColor = dayPayload.specialRequestedByUserColor
            )
            if (dayPayload.monthRecap != null) {
                recapMap[day] = dayPayload.monthRecap
            }
            staleFeedDays.remove(day)
        }
        val mergedKnownDays = mergeDayIndex(state.calendarDays, windowDays)
        val previousVisibleDays = state.feedDays
        val visibleDays = mergeVisibleFeedDays(windowDays, replaceVisibleDays = replaceVisibleDays, appendOlder = appendOlder)
        val prunedCache = pruneFeedCaches(cacheMap, promptMap, recapMap, visibleDays, requestedAnchorDay)
        val today = state.prompt?.day ?: LocalDate.now().toString()
        val postedToday = state.prompt?.hasVisiblePostToday == true
        val hasVisibleTodayFeed = postedToday && prunedCache.feedByDay[today].orEmpty().isNotEmpty()
        val todayLocked = !postedToday && !hasVisibleTodayFeed
        val finalFeedByDay = if (!postedToday) prunedCache.feedByDay - today else prunedCache.feedByDay
        val finalPromptMeta = if (!postedToday) prunedCache.promptMetaByDay + (today to PromptMeta(day = today)) else prunedCache.promptMetaByDay
        val finalRecapMap = if (!postedToday) prunedCache.monthRecapByDay - today else prunedCache.monthRecapByDay
        val finalVisibleDays = if (!postedToday) visibleDays.filter { it != today } else visibleDays
        val hiddenNewerCleared = state.feedHiddenNewerAnchorDay != null &&
            (finalVisibleDays.contains(state.feedHiddenNewerAnchorDay) || finalVisibleDays.firstOrNull() == state.feedHiddenNewerAnchorDay)
        state = state.copy(
            calendarDays = mergedKnownDays,
            feedIndexHasOlder = state.feedIndexHasOlder || window.hasOlder,
            feedIndexHasNewer = window.hasNewer,
            feedDays = finalVisibleDays,
            feedByDay = finalFeedByDay,
            monthRecapByDay = finalRecapMap,
            promptMetaByDay = finalPromptMeta,
            feed = if (postedToday) finalFeedByDay[today].orEmpty() else emptyList(),
            feedTodayLocked = todayLocked,
            feedFocusPhotoId = window.resolvedFocusPhotoId ?: state.feedFocusPhotoId,
            feedDiscoverOffset = window.offset,
            feedDiscoverNextOffset = window.nextOffset,
            randomFeedSeed = if (window.randomSeed != 0L) window.randomSeed else state.randomFeedSeed,
            feedJumpLoadingDay = null,
            feedPaging = false,
            feedWindowReloadInFlight = false,
            feedHasHiddenNewerContent = if (hiddenNewerCleared) false else state.feedHasHiddenNewerContent,
            feedHiddenNewerAnchorDay = if (hiddenNewerCleared) null else state.feedHiddenNewerAnchorDay
        )
        val anchorPresentAfterApply = finalVisibleDays.contains(requestedAnchorDay) || finalVisibleDays.contains(window.anchorDay)
        repo.logFeedDebug(
            type = "feed_window_apply",
            message = "feed window applied",
            meta = "requestedAnchor=$requestedAnchorDay;resolvedAnchor=${window.anchorDay.ifBlank { requestedAnchorDay }};visibleBefore=${previousVisibleDays.joinToString(",").ifBlank { "-" }};visibleAfter=${finalVisibleDays.joinToString(",").ifBlank { "-" }};windowDays=${windowDays.joinToString(",").ifBlank { "-" }};replaceVisibleDays=$replaceVisibleDays;appendOlder=$appendOlder;anchorPresentAfterApply=$anchorPresentAfterApply;hiddenNewerCleared=$hiddenNewerCleared"
        )
        if (forceReload) {
            repo.logDebug(
                type = "feed_window_refresh",
                message = "feed window loaded",
                meta = "requestedAnchor=$requestedAnchorDay;resolvedAnchor=${window.anchorDay.ifBlank { requestedAnchorDay }};visibleAnchor=${state.feedVisibleAnchorDay ?: "-"};daysLoaded=${windowDays.size};replaceVisibleDays=$replaceVisibleDays;appendOlder=$appendOlder;visibleBefore=${previousVisibleDays.joinToString(",").ifBlank { "-" }};visibleAfter=${finalVisibleDays.joinToString(",").ifBlank { "-" }}"
            )
        }
        return windowDays.size
    }

    private fun isFeedLockedTodayError(t: Throwable, targetDay: String): Boolean {
        val today = state.prompt?.day ?: LocalDate.now().toString()
        if (targetDay != today) return false
        if (t !is HttpException || t.code() != 403) return false
        val raw = runCatching { t.response()?.errorBody()?.string().orEmpty() }.getOrDefault("").lowercase()
        return raw.contains("feed_locked") || raw.contains("sichtbaren beitrag")
    }

    private fun applyTodayFeedLockedState(targetDay: String) {
        val today = state.prompt?.day ?: LocalDate.now().toString()
        val cleanedFeedByDay = state.feedByDay - today
        val cleanedPromptMeta = state.promptMetaByDay + (today to PromptMeta(day = today))
        val cleanedRecapMap = state.monthRecapByDay - today
        state = state.copy(
            feedDays = if (targetDay == today) emptyList() else state.feedDays.filter { it != today },
            feedByDay = cleanedFeedByDay,
            promptMetaByDay = cleanedPromptMeta,
            monthRecapByDay = cleanedRecapMap,
            feed = emptyList(),
            feedTodayLocked = true,
            feedJumpLoadingDay = null,
            feedPaging = false,
            feedWindowReloadInFlight = false,
            feedFocusDay = null,
            feedFocusPhotoId = null,
            feedFocusBoundary = null
        )
    }

    private data class FeedCacheBundle(
        val feedByDay: Map<String, List<FeedItem>>,
        val promptMetaByDay: Map<String, PromptMeta>,
        val monthRecapByDay: Map<String, MonthlyRecap>
    )

    private fun pruneFeedCaches(
        feedByDay: MutableMap<String, List<FeedItem>>,
        promptMetaByDay: MutableMap<String, PromptMeta>,
        monthRecapByDay: MutableMap<String, MonthlyRecap>,
        visibleDays: List<String>,
        anchorDay: String
    ): FeedCacheBundle {
        val keepDays = linkedSetOf<String>()
        keepDays.addAll(visibleDays)
        keepDays.add(anchorDay)
        val cachedOrdered = mergeDayIndex(state.calendarDays, feedByDay.keys.toList())
        for (day in cachedOrdered) {
            if (keepDays.size >= 30) break
            keepDays.add(day)
        }
        val keepSet = keepDays.toSet()
        val prunedFeed = feedByDay.filterKeys(keepSet::contains)
        val prunedPrompt = promptMetaByDay.filterKeys(keepSet::contains)
        val prunedRecap = monthRecapByDay.filterKeys(keepSet::contains)
        return FeedCacheBundle(prunedFeed, prunedPrompt, prunedRecap)
    }

    private fun mergeDayIndex(existing: List<String>, incoming: List<String>): List<String> =
        (existing + incoming)
            .filter { it.isNotBlank() }
            .distinct()
            .sortedDescending()

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
            val failureClass = classifyFailure(t)
            val advice = securityAdviceForFailure(failureClass)
            val message = if (advice.isNotBlank() && shouldShowSecurityAdvice(failureClass)) advice else apiError(t, "Upload fehlgeschlagen")
            state = state.copy(loading = false, message = message, networkAdvice = advice)
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

    suspend fun appendPhotoToLatestPost(
        photoId: Long,
        uri: Uri,
        shareLocation: Boolean
    ): Boolean {
        state = state.copy(loading = true)
        val freshPrompt = runCatching { repo.prompt() }.getOrNull()
        val targetPhotoId = freshPrompt?.appendTargetPhotoId ?: photoId
        repo.logDebug(
            type = "append_target_resolved",
            message = "append target resolved",
            meta = "requestedPhotoId=$photoId;resolvedPhotoId=$targetPhotoId;promptAvailable=${freshPrompt != null};canAppend=${freshPrompt?.canAppendToOwnLatestPost ?: false};promptDay=${freshPrompt?.day ?: "-"}"
        )
        if (freshPrompt != null && (!freshPrompt.canAppendToOwnLatestPost || freshPrompt.appendTargetPhotoId == null)) {
            repo.logDebug(
                type = "append_target_rejected",
                message = "append target rejected",
                meta = "requestedPhotoId=$photoId;resolvedPhotoId=$targetPhotoId;canAppend=${freshPrompt.canAppendToOwnLatestPost};appendTargetPhotoId=${freshPrompt.appendTargetPhotoId ?: -1};promptDay=${freshPrompt.day}"
            )
            state = state.copy(
                loading = false,
                prompt = freshPrompt,
                message = "Zum aktuellen letzten sichtbaren Beitrag von heute kann gerade kein Bild angehaengt werden."
            )
            return false
        }
        return runCatching {
            repo.enqueuePhotoAttachmentUpload(targetPhotoId, uri, shareLocation)
        }.onSuccess {
            repo.syncUploadQueueScheduler()
            repo.logDebug(
                type = "append_upload_enqueued",
                message = "append upload queued",
                meta = "targetPhotoId=$targetPhotoId;promptDay=${freshPrompt?.day ?: state.prompt?.day ?: "-"};shareLocation=$shareLocation"
            )
            state = state.copy(
                loading = false,
                prompt = freshPrompt ?: state.prompt,
                uploadQueue = repo.uploadQueue(),
                message = "Bild zum letzten Beitrag eingeplant."
            )
        }.onFailure {
            state = state.copy(loading = false, message = apiError(it, "Anhang fehlgeschlagen"))
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
            state = refreshConnectionHealthState(state.copy(uploadQueue = repo.uploadQueue(), message = "Upload aus Warteschlange entfernt"))
        }
    }

    fun refreshUploadQueueLocal() {
        if (repo.token().isBlank()) return
        state = refreshConnectionHealthState(state.copy(uploadQueue = repo.uploadQueue()))
    }

    suspend fun sendChat(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isBlank() || state.chatSending) return false
        if (!chatUnlimitedEnabled()) {
            val trimmedLength = textCodePointLength(trimmed)
            val maxLength = chatLimitValue()
            if (trimmedLength > maxLength) {
                repo.logDebug(
                    type = "chat_send_blocked_local",
                    message = "message too long",
                    meta = chatInputDebugMeta(body, trimmed)
                )
                state = state.copy(message = "Nachricht ist zu lang (max. $maxLength Zeichen).")
                return false
            }
        }
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
                    val rawError = peekHttpErrorBody(it)
                    val extraMeta = buildString {
                        append(chatInputDebugMeta(body, trimmed))
                        if (rawError.isNotBlank()) {
                            append(";serverError=").append(debugMetaSanitizeShared(rawError, 240))
                        }
                    }
                    logApiFailure("chat_send_failed", "/api/chat", it, extraMeta)
                    state = state.copy(message = apiError(it, "Chat senden fehlgeschlagen", rawError))
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
            .onSuccess {
                applyPhotoInteractionsToFeedState(photoId, it)
                upsertPendingFeedMutation(photoId) { pending ->
                    pending.copy(
                        commentsOverride = it.comments,
                        reactionsOverride = it.reactions,
                        photoMojisOverride = it.photoMojis
                    )
                }
                state = state.copy(interactionsLoading = false, photoInteractions = it)
            }
            .onFailure { state = state.copy(interactionsLoading = false, message = apiError(it, "Interaktionen laden fehlgeschlagen")) }
    }

    suspend fun reactPhoto(photoId: Long, emoji: String) {
        if (photoId <= 0 || emoji.isBlank()) return
        state = state.copy(interactionsLoading = true)
        runCatching { repo.reactPhoto(photoId, emoji) }
            .onSuccess {
                applyPhotoInteractionsToFeedState(photoId, it)
                upsertPendingFeedMutation(photoId) { pending ->
                    pending.copy(
                        commentsOverride = it.comments,
                        reactionsOverride = it.reactions,
                        photoMojisOverride = it.photoMojis
                    )
                }
                state = state.copy(interactionsLoading = false, photoInteractions = it)
            }
            .onFailure { state = state.copy(interactionsLoading = false, message = apiError(it, "Reaktion fehlgeschlagen")) }
    }

    suspend fun tryPhotoFotomojiFromTemplate(photoId: Long, emoji: String): Boolean {
        if (photoId <= 0 || emoji.isBlank()) return false
        state = state.copy(interactionsLoading = true)
        return try {
            val response = repo.reactPhotoFotomojiFromTemplate(photoId, emoji)
            applyPhotoInteractionsToFeedState(photoId, response)
            upsertPendingFeedMutation(photoId) { pending ->
                pending.copy(
                    commentsOverride = response.comments,
                    reactionsOverride = response.reactions,
                    photoMojisOverride = response.photoMojis
                )
            }
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
                applyPhotoInteractionsToFeedState(photoId, it)
                upsertPendingFeedMutation(photoId) { pending ->
                    pending.copy(
                        commentsOverride = it.comments,
                        reactionsOverride = it.reactions,
                        photoMojisOverride = it.photoMojis
                    )
                }
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
            val commentPatch = if (response.comments.isNotEmpty()) {
                response.comments
            } else {
                (state.photoInteractions?.takeIf { it.photoId == photoId }?.comments.orEmpty() + PhotoCommentItem(
                    id = -System.currentTimeMillis(),
                    body = trimmed,
                    createdAt = OffsetDateTime.now().toString(),
                    user = state.user ?: User(id = -1, username = "du", isAdmin = false)
                )).takeLast(40)
            }
            patchFeedItemState(photoId) { item ->
                item.copy(
                    reactions = response.reactions.ifEmpty { item.reactions.orEmpty() },
                    photoMojis = response.photoMojis.ifEmpty { item.photoMojis.orEmpty() },
                    comments = commentPatch
                )
            }
            upsertPendingFeedMutation(photoId) {
                it.copy(
                    commentsOverride = commentPatch,
                    reactionsOverride = response.reactions.takeIf { reactions -> reactions.isNotEmpty() },
                    photoMojisOverride = response.photoMojis.takeIf { photoMojis -> photoMojis.isNotEmpty() }
                )
            }
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
        state = refreshConnectionHealthState(state.copy(loading = true))
        val startedAt = System.currentTimeMillis()
        runCatching { repo.health() }
            .onSuccess { health ->
                val pingMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                lastApiSuccessAtMs = System.currentTimeMillis()
                lastApiFailureAtMs = 0L
                lastApiFailureMessage = ""
                state = refreshConnectionHealthState(state.copy(
                    loading = false,
                    serverConnected = health.ok,
                    serverVersion = health.version,
                    pushProvider = health.provider,
                    chatDeleteSupported = health.features.chatDelete,
                    lastPingMs = pingMs,
                    message = if (health.ok) "Verbindung erfolgreich geprueft" else "Server nicht erreichbar"
                ))
            }
            .onFailure { error ->
                lastApiFailureAtMs = System.currentTimeMillis()
                lastApiFailureMessage = apiError(error, "Verbindung pruefen fehlgeschlagen")
                state = refreshConnectionHealthState(state.copy(
                    loading = false,
                    serverConnected = false,
                    chatDeleteSupported = false,
                    lastPingMs = null,
                    message = lastApiFailureMessage
                ))
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
            bookmarkedPhotoPushEnabled = repo.bookmarkedPhotoPushLocalEnabled(),
            postChangePushEnabled = repo.postChangePushLocalEnabled(),
            autoSubscribeInteractedPostsEnabled = repo.autoSubscribeInteractedPostsLocalEnabled(),
            yoloModeEnabled = repo.yoloModeLocalEnabled(),
            showPublicPostNumbers = repo.showPublicPostNumbers(),
            preferSwipeForTwoImagePosts = repo.preferSwipeForTwoImagePosts(),
            showConnectionHealthIndicator = repo.showConnectionHealthIndicator(),
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
        state = refreshConnectionHealthState(state)
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
            bookmarkedPhotoPushEnabled = repo.bookmarkedPhotoPushLocalEnabled(),
            postChangePushEnabled = repo.postChangePushLocalEnabled(),
            autoSubscribeInteractedPostsEnabled = repo.autoSubscribeInteractedPostsLocalEnabled(),
            yoloModeEnabled = repo.yoloModeLocalEnabled(),
            showPublicPostNumbers = repo.showPublicPostNumbers(),
            preferSwipeForTwoImagePosts = repo.preferSwipeForTwoImagePosts(),
            showConnectionHealthIndicator = repo.showConnectionHealthIndicator(),
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
        state = refreshConnectionHealthState(state)
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

    fun setShowPublicPostNumbers(enabled: Boolean) {
        repo.setShowPublicPostNumbers(enabled)
        state = refreshConnectionHealthState(state.copy(showPublicPostNumbers = repo.showPublicPostNumbers()))
    }

    fun setPreferSwipeForTwoImagePosts(enabled: Boolean) {
        repo.setPreferSwipeForTwoImagePosts(enabled)
        state = refreshConnectionHealthState(state.copy(preferSwipeForTwoImagePosts = repo.preferSwipeForTwoImagePosts()))
    }

    fun setShowConnectionHealthIndicator(enabled: Boolean) {
        repo.setShowConnectionHealthIndicator(enabled)
        state = refreshConnectionHealthState(state.copy(showConnectionHealthIndicator = repo.showConnectionHealthIndicator()))
    }

    fun setFeedOrderMode(mode: FeedOrderMode) {
        repo.setFeedOrderMode(mode)
        val seed = if (mode == FeedOrderMode.RANDOM) {
            val next = System.currentTimeMillis()
            repo.setRandomFeedSeed(next)
            next
        } else {
            repo.randomFeedSeed()
        }
        state = state.copy(feedOrderMode = repo.feedOrderMode(), randomFeedSeed = seed)
        viewModelScope.launch {
            if (state.activeTab == AppTab.FEED || state.feedByDay.isNotEmpty()) {
                refreshFeed(reason = "feed_mode_switch")
            }
        }
    }

    suspend fun setOwnPostNumberInPushEnabled(enabled: Boolean) {
        repo.setOwnPostNumberInPushLocalEnabled(enabled)
        val current = state.user
        if (current == null) {
            state = state.copy(ownPostNumberInPushEnabled = repo.ownPostNumberInPushLocalEnabled())
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
                creativePostMode = current.creativePostMode,
                bookmarkedPhotoPushEnabled = current.bookmarkedPhotoPushEnabled,
                ownPostNumberInPushEnabled = enabled,
                postNumberInPushEnabled = current.postNumberInPushEnabled,
                specialMomentPushEnabled = current.specialMomentPushEnabled,
                locationFeatureEnabled = current.locationFeatureEnabled,
                locationShareDefaultEnabled = current.locationShareDefaultEnabled
            )
        }.onSuccess { user ->
            repo.setOwnPostNumberInPushLocalEnabled(user.ownPostNumberInPushEnabled)
            repo.setPostNumberInPushLocalEnabled(user.postNumberInPushEnabled)
            state = state.copy(
                user = user,
                ownPostNumberInPushEnabled = user.ownPostNumberInPushEnabled,
                postNumberInPushEnabled = user.postNumberInPushEnabled
            )
        }.onFailure {
            state = state.copy(message = apiError(it, "Push-Postnummer fuer eigene Beitraege konnte nicht gespeichert werden"))
        }
    }

    suspend fun setPostNumberInPushEnabled(enabled: Boolean) {
        repo.setPostNumberInPushLocalEnabled(enabled)
        val current = state.user
        if (current == null) {
            state = state.copy(postNumberInPushEnabled = repo.postNumberInPushLocalEnabled())
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
                creativePostMode = current.creativePostMode,
                bookmarkedPhotoPushEnabled = current.bookmarkedPhotoPushEnabled,
                ownPostNumberInPushEnabled = current.ownPostNumberInPushEnabled,
                postNumberInPushEnabled = enabled,
                specialMomentPushEnabled = current.specialMomentPushEnabled,
                locationFeatureEnabled = current.locationFeatureEnabled,
                locationShareDefaultEnabled = current.locationShareDefaultEnabled
            )
        }.onSuccess { user ->
            repo.setOwnPostNumberInPushLocalEnabled(user.ownPostNumberInPushEnabled)
            repo.setPostNumberInPushLocalEnabled(user.postNumberInPushEnabled)
            state = state.copy(
                user = user,
                ownPostNumberInPushEnabled = user.ownPostNumberInPushEnabled,
                postNumberInPushEnabled = user.postNumberInPushEnabled
            )
        }.onFailure {
            state = state.copy(message = apiError(it, "Push-Postnummer fuer gemerkte Beitraege konnte nicht gespeichert werden"))
        }
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
        val bookmarked = state.user?.bookmarkedPhotoPushEnabled ?: repo.bookmarkedPhotoPushLocalEnabled()
        val postChange = state.user?.postChangePushEnabled ?: repo.postChangePushLocalEnabled()
        val master = computeNotificationMaster(auto, chat, feed, poll, invite, reaction, comment, bookmarked, postChange)
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
        val bookmarked = state.user?.bookmarkedPhotoPushEnabled ?: repo.bookmarkedPhotoPushLocalEnabled()
        val postChange = state.user?.postChangePushEnabled ?: repo.postChangePushLocalEnabled()
        val master = computeNotificationMaster(auto, chat, feed, poll, invite, reaction, comment, bookmarked, postChange)
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

    private fun computeNotificationMaster(
        auto: Boolean,
        chat: Boolean,
        feed: Boolean,
        poll: Boolean,
        invite: Boolean,
        reaction: Boolean,
        comment: Boolean,
        bookmarked: Boolean,
        postChange: Boolean
    ): Boolean = auto && chat && feed && poll && invite && reaction && comment && bookmarked && postChange

    private fun syncInteractionPushPrefs(user: User) {
        repo.setChatPushLocalEnabled(user.chatPushEnabled)
        repo.setPollPushLocalEnabled(user.pollPushEnabled)
        repo.setSpecialMomentPushLocalEnabled(user.specialMomentPushEnabled)
        repo.setInviteRegistrationPushLocalEnabled(user.inviteRegistrationPushEnabled)
        repo.setPhotoReactionPushLocalEnabled(user.photoReactionPushEnabled)
        repo.setPhotoCommentPushLocalEnabled(user.photoCommentPushEnabled)
        repo.setBookmarkedPhotoPushLocalEnabled(user.bookmarkedPhotoPushEnabled)
        repo.setPostChangePushLocalEnabled(user.postChangePushEnabled)
        repo.setAutoSubscribeInteractedPostsLocalEnabled(user.autoSubscribeInteractedPostsEnabled)
        repo.setOwnPostNumberInPushLocalEnabled(user.ownPostNumberInPushEnabled)
        repo.setPostNumberInPushLocalEnabled(user.postNumberInPushEnabled)
        repo.setYoloModeLocalEnabled(user.yoloModeEnabled)
    }

    private fun currentYoloPreferenceState(): YoloPreferenceState {
        val current = state.user
        return YoloPreferenceState(
            autoUpdateEnabled = repo.autoUpdateEnabled(),
            feedPostPushEnabled = repo.feedPostPushEnabled(),
            useFotomojiReactions = repo.useFotomojiReactions(),
            showPublicPostNumbers = repo.showPublicPostNumbers(),
            preferSwipeForTwoImagePosts = repo.preferSwipeForTwoImagePosts(),
            chatPushEnabled = current?.chatPushEnabled ?: repo.chatPushLocalEnabled(),
            pollPushEnabled = current?.pollPushEnabled ?: repo.pollPushLocalEnabled(),
            specialMomentPushEnabled = current?.specialMomentPushEnabled ?: repo.specialMomentPushLocalEnabled(),
            inviteRegistrationPushEnabled = current?.inviteRegistrationPushEnabled ?: repo.inviteRegistrationPushLocalEnabled(),
            photoReactionPushEnabled = current?.photoReactionPushEnabled ?: repo.photoReactionPushLocalEnabled(),
            photoCommentPushEnabled = current?.photoCommentPushEnabled ?: repo.photoCommentPushLocalEnabled(),
            bookmarkedPhotoPushEnabled = current?.bookmarkedPhotoPushEnabled ?: repo.bookmarkedPhotoPushLocalEnabled(),
            postChangePushEnabled = current?.postChangePushEnabled ?: repo.postChangePushLocalEnabled(),
            autoSubscribeInteractedPostsEnabled = current?.autoSubscribeInteractedPostsEnabled ?: repo.autoSubscribeInteractedPostsLocalEnabled(),
            ownPostNumberInPushEnabled = current?.ownPostNumberInPushEnabled ?: repo.ownPostNumberInPushLocalEnabled(),
            postNumberInPushEnabled = current?.postNumberInPushEnabled ?: repo.postNumberInPushLocalEnabled(),
            yoloModeEnabled = current?.yoloModeEnabled ?: repo.yoloModeLocalEnabled(),
            allowPhotoDownload = current?.allowPhotoDownload ?: false,
            allowCommunityNsfwMarking = current?.allowCommunityNsfwMarking ?: false,
            showNsfwByDefault = current?.showNsfwByDefault ?: false,
            creativePostMode = current?.creativePostMode ?: "none",
            locationFeatureEnabled = current?.locationFeatureEnabled ?: false,
            locationShareDefaultEnabled = current?.locationShareDefaultEnabled ?: false
        )
    }

    private fun syncYoloLocalPrefs(preferences: YoloPreferenceState) {
        repo.setAutoUpdateEnabled(preferences.autoUpdateEnabled)
        repo.setFeedPostPushEnabled(preferences.feedPostPushEnabled)
        repo.setUseFotomojiReactions(preferences.useFotomojiReactions)
        repo.setShowPublicPostNumbers(preferences.showPublicPostNumbers)
        repo.setPreferSwipeForTwoImagePosts(preferences.preferSwipeForTwoImagePosts)
        repo.setChatPushLocalEnabled(preferences.chatPushEnabled)
        repo.setPollPushLocalEnabled(preferences.pollPushEnabled)
        repo.setSpecialMomentPushLocalEnabled(preferences.specialMomentPushEnabled)
        repo.setInviteRegistrationPushLocalEnabled(preferences.inviteRegistrationPushEnabled)
        repo.setPhotoReactionPushLocalEnabled(preferences.photoReactionPushEnabled)
        repo.setPhotoCommentPushLocalEnabled(preferences.photoCommentPushEnabled)
        repo.setBookmarkedPhotoPushLocalEnabled(preferences.bookmarkedPhotoPushEnabled)
        repo.setPostChangePushLocalEnabled(preferences.postChangePushEnabled)
        repo.setAutoSubscribeInteractedPostsLocalEnabled(preferences.autoSubscribeInteractedPostsEnabled)
        repo.setOwnPostNumberInPushLocalEnabled(preferences.ownPostNumberInPushEnabled)
        repo.setPostNumberInPushLocalEnabled(preferences.postNumberInPushEnabled)
        repo.setYoloModeLocalEnabled(preferences.yoloModeEnabled)
    }

    private suspend fun applyYoloFeatures(forceAll: Boolean, reason: String): Boolean {
        val eligible = yoloRegistry.filter { !isVersionNewer(it.introducedInVersion, BuildConfig.VERSION_NAME) }
        val pending = if (forceAll) {
            eligible
        } else {
            val applied = repo.appliedYoloFeatureIds()
            eligible.filterNot { applied.contains(it.id) }
        }
        if (pending.isEmpty()) {
            return false
        }
        val preferences = currentYoloPreferenceState()
        pending.forEach { it.apply(preferences) }
        preferences.yoloModeEnabled = true
        val current = state.user
        val updatedUser = if (current != null) {
            repo.updatePreferences(
                chatPushEnabled = preferences.chatPushEnabled,
                pollPushEnabled = preferences.pollPushEnabled,
                inviteRegistrationPushEnabled = preferences.inviteRegistrationPushEnabled,
                photoReactionPushEnabled = preferences.photoReactionPushEnabled,
                photoCommentPushEnabled = preferences.photoCommentPushEnabled,
                allowPhotoDownload = preferences.allowPhotoDownload,
                allowCommunityNsfwMarking = preferences.allowCommunityNsfwMarking,
                showNsfwByDefault = preferences.showNsfwByDefault,
                creativePostMode = preferences.creativePostMode,
                bookmarkedPhotoPushEnabled = preferences.bookmarkedPhotoPushEnabled,
                postChangePushEnabled = preferences.postChangePushEnabled,
                autoSubscribeInteractedPostsEnabled = preferences.autoSubscribeInteractedPostsEnabled,
                ownPostNumberInPushEnabled = preferences.ownPostNumberInPushEnabled,
                postNumberInPushEnabled = preferences.postNumberInPushEnabled,
                yoloModeEnabled = preferences.yoloModeEnabled,
                specialMomentPushEnabled = preferences.specialMomentPushEnabled,
                locationFeatureEnabled = preferences.locationFeatureEnabled,
                locationShareDefaultEnabled = preferences.locationShareDefaultEnabled
            )
        } else {
            null
        }
        val syncedPreferences = preferences.copy(
            chatPushEnabled = updatedUser?.chatPushEnabled ?: preferences.chatPushEnabled,
            pollPushEnabled = updatedUser?.pollPushEnabled ?: preferences.pollPushEnabled,
            specialMomentPushEnabled = updatedUser?.specialMomentPushEnabled ?: preferences.specialMomentPushEnabled,
            inviteRegistrationPushEnabled = updatedUser?.inviteRegistrationPushEnabled ?: preferences.inviteRegistrationPushEnabled,
            photoReactionPushEnabled = updatedUser?.photoReactionPushEnabled ?: preferences.photoReactionPushEnabled,
            photoCommentPushEnabled = updatedUser?.photoCommentPushEnabled ?: preferences.photoCommentPushEnabled,
            bookmarkedPhotoPushEnabled = updatedUser?.bookmarkedPhotoPushEnabled ?: preferences.bookmarkedPhotoPushEnabled,
            postChangePushEnabled = updatedUser?.postChangePushEnabled ?: preferences.postChangePushEnabled,
            autoSubscribeInteractedPostsEnabled = updatedUser?.autoSubscribeInteractedPostsEnabled ?: preferences.autoSubscribeInteractedPostsEnabled,
            ownPostNumberInPushEnabled = updatedUser?.ownPostNumberInPushEnabled ?: preferences.ownPostNumberInPushEnabled,
            postNumberInPushEnabled = updatedUser?.postNumberInPushEnabled ?: preferences.postNumberInPushEnabled,
            yoloModeEnabled = updatedUser?.yoloModeEnabled ?: preferences.yoloModeEnabled,
            allowPhotoDownload = updatedUser?.allowPhotoDownload ?: preferences.allowPhotoDownload,
            allowCommunityNsfwMarking = updatedUser?.allowCommunityNsfwMarking ?: preferences.allowCommunityNsfwMarking,
            showNsfwByDefault = updatedUser?.showNsfwByDefault ?: preferences.showNsfwByDefault,
            creativePostMode = updatedUser?.creativePostMode ?: preferences.creativePostMode,
            locationFeatureEnabled = updatedUser?.locationFeatureEnabled ?: preferences.locationFeatureEnabled,
            locationShareDefaultEnabled = updatedUser?.locationShareDefaultEnabled ?: preferences.locationShareDefaultEnabled
        )
        syncYoloLocalPrefs(syncedPreferences)
        if (updatedUser != null) {
            syncInteractionPushPrefs(updatedUser)
        }
        repo.markYoloFeatureIdsApplied(pending.map { it.id })
        val auto = repo.autoUpdateEnabled()
        val feed = repo.feedPostPushEnabled()
        val user = updatedUser ?: current
        val chat = user?.chatPushEnabled ?: repo.chatPushLocalEnabled()
        val poll = user?.pollPushEnabled ?: repo.pollPushLocalEnabled()
        val invite = user?.inviteRegistrationPushEnabled ?: repo.inviteRegistrationPushLocalEnabled()
        val reaction = user?.photoReactionPushEnabled ?: repo.photoReactionPushLocalEnabled()
        val comment = user?.photoCommentPushEnabled ?: repo.photoCommentPushLocalEnabled()
        val bookmarked = user?.bookmarkedPhotoPushEnabled ?: repo.bookmarkedPhotoPushLocalEnabled()
        val postChange = user?.postChangePushEnabled ?: repo.postChangePushLocalEnabled()
        val master = computeNotificationMaster(auto, chat, feed, poll, invite, reaction, comment, bookmarked, postChange)
        repo.setNotificationMasterEnabled(master)
        state = state.copy(
            user = updatedUser ?: state.user?.copy(yoloModeEnabled = true),
            autoUpdateEnabled = auto,
            feedPostPushEnabled = feed,
            useFotomojiReactions = repo.useFotomojiReactions(),
            pollPushEnabled = poll,
            specialMomentPushEnabled = user?.specialMomentPushEnabled ?: repo.specialMomentPushLocalEnabled(),
            inviteRegistrationPushEnabled = invite,
            photoReactionPushEnabled = reaction,
            photoCommentPushEnabled = comment,
            bookmarkedPhotoPushEnabled = bookmarked,
            ownPostNumberInPushEnabled = user?.ownPostNumberInPushEnabled ?: repo.ownPostNumberInPushLocalEnabled(),
            postNumberInPushEnabled = user?.postNumberInPushEnabled ?: repo.postNumberInPushLocalEnabled(),
            yoloModeEnabled = true,
            allowCommunityNsfwMarking = user?.allowCommunityNsfwMarking ?: false,
            showNsfwByDefault = user?.showNsfwByDefault ?: false,
            locationFeatureEnabled = user?.locationFeatureEnabled ?: false,
            locationShareDefaultEnabled = user?.locationShareDefaultEnabled ?: false,
            showPublicPostNumbers = repo.showPublicPostNumbers(),
            preferSwipeForTwoImagePosts = repo.preferSwipeForTwoImagePosts(),
            notificationMasterEnabled = master,
            message = when {
                pending.isEmpty() -> ""
                forceAll -> "YOLO-Modus aktiviert: ${pending.size} Features freigeschaltet"
                else -> "YOLO-Modus hat ${pending.size} neue Features aktiviert ($reason)"
            }
        )
        return true
    }

    suspend fun setNotificationPostNumbersEnabled(enabled: Boolean) {
        val current = state.user
        if (current == null) {
            repo.setOwnPostNumberInPushLocalEnabled(enabled)
            repo.setPostNumberInPushLocalEnabled(enabled)
            state = state.copy(
                ownPostNumberInPushEnabled = enabled,
                postNumberInPushEnabled = enabled
            )
            return
        }
        state = state.copy(loading = true)
        runCatching {
            repo.updatePreferences(
                chatPushEnabled = current.chatPushEnabled,
                pollPushEnabled = current.pollPushEnabled,
                inviteRegistrationPushEnabled = current.inviteRegistrationPushEnabled,
                photoReactionPushEnabled = current.photoReactionPushEnabled,
                photoCommentPushEnabled = current.photoCommentPushEnabled,
                allowPhotoDownload = current.allowPhotoDownload,
                creativePostMode = current.creativePostMode,
                bookmarkedPhotoPushEnabled = current.bookmarkedPhotoPushEnabled,
                ownPostNumberInPushEnabled = enabled,
                postNumberInPushEnabled = enabled,
                yoloModeEnabled = current.yoloModeEnabled,
                specialMomentPushEnabled = current.specialMomentPushEnabled,
                locationFeatureEnabled = current.locationFeatureEnabled,
                locationShareDefaultEnabled = current.locationShareDefaultEnabled
            )
        }.onSuccess { user ->
            syncInteractionPushPrefs(user)
            state = state.copy(
                user = user,
                ownPostNumberInPushEnabled = user.ownPostNumberInPushEnabled,
                postNumberInPushEnabled = user.postNumberInPushEnabled,
                loading = false,
                message = if (enabled) "Push-Postnummern aktiviert" else "Push-Postnummern deaktiviert"
            )
        }.onFailure {
            state = state.copy(loading = false, message = apiError(it, "Push-Postnummern konnten nicht gespeichert werden"))
        }
    }

    suspend fun setYoloModeEnabled(enabled: Boolean) {
        val current = state.user
        state = state.copy(loading = true)
        if (!enabled) {
            runCatching {
                if (current != null) {
                    repo.updatePreferences(
                        chatPushEnabled = current.chatPushEnabled,
                        pollPushEnabled = current.pollPushEnabled,
                        inviteRegistrationPushEnabled = current.inviteRegistrationPushEnabled,
                        photoReactionPushEnabled = current.photoReactionPushEnabled,
                        photoCommentPushEnabled = current.photoCommentPushEnabled,
                        allowPhotoDownload = current.allowPhotoDownload,
                        creativePostMode = current.creativePostMode,
                        bookmarkedPhotoPushEnabled = current.bookmarkedPhotoPushEnabled,
                        ownPostNumberInPushEnabled = current.ownPostNumberInPushEnabled,
                        postNumberInPushEnabled = current.postNumberInPushEnabled,
                        yoloModeEnabled = false,
                        specialMomentPushEnabled = current.specialMomentPushEnabled,
                        locationFeatureEnabled = current.locationFeatureEnabled,
                        locationShareDefaultEnabled = current.locationShareDefaultEnabled
                    )
                } else {
                    null
                }
            }.onSuccess { user ->
                repo.setYoloModeLocalEnabled(false)
                state = state.copy(
                    user = user ?: state.user?.copy(yoloModeEnabled = false),
                    yoloModeEnabled = false,
                    loading = false,
                    message = "YOLO-Modus deaktiviert"
                )
            }.onFailure {
                state = state.copy(loading = false, message = apiError(it, "YOLO-Modus konnte nicht gespeichert werden"))
            }
            return
        }
        runCatching {
            if (current != null) {
                repo.updatePreferences(
                    chatPushEnabled = current.chatPushEnabled,
                    pollPushEnabled = current.pollPushEnabled,
                    inviteRegistrationPushEnabled = current.inviteRegistrationPushEnabled,
                    photoReactionPushEnabled = current.photoReactionPushEnabled,
                    photoCommentPushEnabled = current.photoCommentPushEnabled,
                    allowPhotoDownload = current.allowPhotoDownload,
                    creativePostMode = current.creativePostMode,
                    bookmarkedPhotoPushEnabled = current.bookmarkedPhotoPushEnabled,
                    ownPostNumberInPushEnabled = current.ownPostNumberInPushEnabled,
                    postNumberInPushEnabled = current.postNumberInPushEnabled,
                    yoloModeEnabled = true,
                    specialMomentPushEnabled = current.specialMomentPushEnabled,
                    locationFeatureEnabled = current.locationFeatureEnabled,
                    locationShareDefaultEnabled = current.locationShareDefaultEnabled
                )
            } else {
                null
            }
        }.onSuccess { user ->
            if (user != null) {
                syncInteractionPushPrefs(user)
                state = state.copy(user = user, yoloModeEnabled = user.yoloModeEnabled)
            } else {
                repo.setYoloModeLocalEnabled(true)
                state = state.copy(yoloModeEnabled = true)
            }
            val applied = applyYoloFeatures(forceAll = true, reason = "manual_enable")
            if (!applied) {
                state = state.copy(loading = false, message = "YOLO-Modus aktiviert")
            } else {
                state = state.copy(loading = false)
            }
        }.onFailure {
            state = state.copy(loading = false, message = apiError(it, "YOLO-Modus konnte nicht gespeichert werden"))
        }
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
                syncInteractionPushPrefs(user)
                repo.setPollPushLocalEnabled(user.pollPushEnabled)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = computeNotificationMaster(auto, user.chatPushEnabled, feed, user.pollPushEnabled, user.inviteRegistrationPushEnabled, user.photoReactionPushEnabled, user.photoCommentPushEnabled, user.bookmarkedPhotoPushEnabled, user.postChangePushEnabled)
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
        val bookmarkedEnabled = state.user?.bookmarkedPhotoPushEnabled ?: repo.bookmarkedPhotoPushLocalEnabled()
        runCatching { repo.updatePreferences(enabled, pollEnabled, inviteEnabled, reactionEnabled, commentEnabled, allowDownload, bookmarkedPhotoPushEnabled = bookmarkedEnabled) }
            .onSuccess { user ->
                syncInteractionPushPrefs(user)
                repo.setPollPushLocalEnabled(user.pollPushEnabled)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = computeNotificationMaster(auto, user.chatPushEnabled, feed, user.pollPushEnabled, user.inviteRegistrationPushEnabled, user.photoReactionPushEnabled, user.photoCommentPushEnabled, user.bookmarkedPhotoPushEnabled, user.postChangePushEnabled)
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    pollPushEnabled = user.pollPushEnabled,
                    inviteRegistrationPushEnabled = user.inviteRegistrationPushEnabled,
                    photoReactionPushEnabled = user.photoReactionPushEnabled,
                    photoCommentPushEnabled = user.photoCommentPushEnabled,
                    bookmarkedPhotoPushEnabled = user.bookmarkedPhotoPushEnabled,
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
        repo.setBookmarkedPhotoPushLocalEnabled(enabled)
        repo.setPostChangePushLocalEnabled(enabled)
        var nextUser = state.user
        if (state.user != null) {
            val allowDownload = state.user?.allowPhotoDownload ?: false
            runCatching { repo.updatePreferences(enabled, enabled, enabled, enabled, enabled, allowDownload, bookmarkedPhotoPushEnabled = enabled, postChangePushEnabled = enabled) }
                .onSuccess {
                    nextUser = it
                    repo.setChatPushLocalEnabled(it.chatPushEnabled)
                    repo.setPollPushLocalEnabled(it.pollPushEnabled)
                    repo.setInviteRegistrationPushLocalEnabled(it.inviteRegistrationPushEnabled)
                    repo.setPhotoReactionPushLocalEnabled(it.photoReactionPushEnabled)
                    repo.setPhotoCommentPushLocalEnabled(it.photoCommentPushEnabled)
                    repo.setBookmarkedPhotoPushLocalEnabled(it.bookmarkedPhotoPushEnabled)
                    repo.setPostChangePushLocalEnabled(it.postChangePushEnabled)
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
            repo.setBookmarkedPhotoPushLocalEnabled(enabled)
            repo.setPostChangePushLocalEnabled(enabled)
        }
        val auto = repo.autoUpdateEnabled()
        val feed = repo.feedPostPushEnabled()
        val chat = nextUser?.chatPushEnabled ?: repo.chatPushLocalEnabled()
        val poll = nextUser?.pollPushEnabled ?: repo.pollPushLocalEnabled()
        val invite = nextUser?.inviteRegistrationPushEnabled ?: repo.inviteRegistrationPushLocalEnabled()
        val reaction = nextUser?.photoReactionPushEnabled ?: repo.photoReactionPushLocalEnabled()
        val comment = nextUser?.photoCommentPushEnabled ?: repo.photoCommentPushLocalEnabled()
        val bookmarked = nextUser?.bookmarkedPhotoPushEnabled ?: repo.bookmarkedPhotoPushLocalEnabled()
        val postChange = nextUser?.postChangePushEnabled ?: repo.postChangePushLocalEnabled()
        val masterEffective = computeNotificationMaster(auto, chat, feed, poll, invite, reaction, comment, bookmarked, postChange)
        repo.setNotificationMasterEnabled(masterEffective)
        state = state.copy(
            user = nextUser,
            autoUpdateEnabled = auto,
            feedPostPushEnabled = feed,
            pollPushEnabled = poll,
            inviteRegistrationPushEnabled = invite,
            photoReactionPushEnabled = reaction,
            photoCommentPushEnabled = comment,
            bookmarkedPhotoPushEnabled = bookmarked,
            postChangePushEnabled = postChange,
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

    suspend fun setAllowCommunityNsfwMarkingEnabled(enabled: Boolean) {
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
                allowCommunityNsfwMarking = enabled,
                showNsfwByDefault = current.showNsfwByDefault
            )
        }
            .onSuccess { user ->
                state = state.copy(
                    user = user,
                    loading = false,
                    message = if (enabled) "NSFW-Freigabe fuer andere aktiviert" else "NSFW-Freigabe fuer andere deaktiviert"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "NSFW-Freigabe speichern fehlgeschlagen"))
            }
    }

    suspend fun setShowNsfwByDefaultEnabled(enabled: Boolean) {
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
                allowCommunityNsfwMarking = current.allowCommunityNsfwMarking,
                showNsfwByDefault = enabled
            )
        }
            .onSuccess { user ->
                state = state.copy(
                    user = user,
                    loading = false,
                    message = if (enabled) "NSFW wird standardmaessig angezeigt" else "NSFW bleibt standardmaessig verdeckt"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "NSFW-Anzeige speichern fehlgeschlagen"))
            }
    }

    suspend fun setCreativePostMode(mode: String) {
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
                creativePostMode = mode
            )
        }
            .onSuccess { user ->
                state = state.copy(
                    user = user,
                    loading = false,
                    message = "Kreativfreigabe gespeichert"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Kreativfreigabe speichern fehlgeschlagen"))
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
                syncInteractionPushPrefs(user)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = computeNotificationMaster(auto, user.chatPushEnabled, feed, user.pollPushEnabled, user.inviteRegistrationPushEnabled, user.photoReactionPushEnabled, user.photoCommentPushEnabled, user.bookmarkedPhotoPushEnabled, user.postChangePushEnabled)
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    pollPushEnabled = user.pollPushEnabled,
                    inviteRegistrationPushEnabled = user.inviteRegistrationPushEnabled,
                    photoReactionPushEnabled = user.photoReactionPushEnabled,
                    photoCommentPushEnabled = user.photoCommentPushEnabled,
                    bookmarkedPhotoPushEnabled = user.bookmarkedPhotoPushEnabled,
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
                syncInteractionPushPrefs(user)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = computeNotificationMaster(auto, user.chatPushEnabled, feed, user.pollPushEnabled, user.inviteRegistrationPushEnabled, user.photoReactionPushEnabled, user.photoCommentPushEnabled, user.bookmarkedPhotoPushEnabled, user.postChangePushEnabled)
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    pollPushEnabled = user.pollPushEnabled,
                    bookmarkedPhotoPushEnabled = user.bookmarkedPhotoPushEnabled,
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
                syncInteractionPushPrefs(user)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = computeNotificationMaster(auto, user.chatPushEnabled, feed, user.pollPushEnabled, user.inviteRegistrationPushEnabled, user.photoReactionPushEnabled, user.photoCommentPushEnabled, user.bookmarkedPhotoPushEnabled, user.postChangePushEnabled)
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    pollPushEnabled = user.pollPushEnabled,
                    bookmarkedPhotoPushEnabled = user.bookmarkedPhotoPushEnabled,
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

    suspend fun setBookmarkedPhotoPushEnabled(enabled: Boolean) {
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
                bookmarkedPhotoPushEnabled = enabled
            )
        }
            .onSuccess { user ->
                syncInteractionPushPrefs(user)
                repo.setPollPushLocalEnabled(user.pollPushEnabled)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = computeNotificationMaster(auto, user.chatPushEnabled, feed, user.pollPushEnabled, user.inviteRegistrationPushEnabled, user.photoReactionPushEnabled, user.photoCommentPushEnabled, user.bookmarkedPhotoPushEnabled, user.postChangePushEnabled)
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    pollPushEnabled = user.pollPushEnabled,
                    bookmarkedPhotoPushEnabled = user.bookmarkedPhotoPushEnabled,
                    notificationMasterEnabled = master,
                    loading = false,
                    message = "Push bei gemerkten Beitraegen aktualisiert"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Push-Einstellung speichern fehlgeschlagen"))
            }
    }

    suspend fun setPostChangePushEnabled(enabled: Boolean) {
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
                postChangePushEnabled = enabled
            )
        }
            .onSuccess { user ->
                syncInteractionPushPrefs(user)
                repo.setPollPushLocalEnabled(user.pollPushEnabled)
                val auto = repo.autoUpdateEnabled()
                val feed = repo.feedPostPushEnabled()
                val master = computeNotificationMaster(auto, user.chatPushEnabled, feed, user.pollPushEnabled, user.inviteRegistrationPushEnabled, user.photoReactionPushEnabled, user.photoCommentPushEnabled, user.bookmarkedPhotoPushEnabled, user.postChangePushEnabled)
                repo.setNotificationMasterEnabled(master)
                state = state.copy(
                    user = user,
                    pollPushEnabled = user.pollPushEnabled,
                    postChangePushEnabled = user.postChangePushEnabled,
                    notificationMasterEnabled = master,
                    loading = false,
                    message = "Push bei Post-Aenderungen aktualisiert"
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Push-Einstellung speichern fehlgeschlagen"))
            }
    }

    suspend fun setAutoSubscribeInteractedPostsEnabled(enabled: Boolean) {
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
                autoSubscribeInteractedPostsEnabled = enabled
            )
        }
            .onSuccess { user ->
                syncInteractionPushPrefs(user)
                state = state.copy(
                    user = user,
                    autoSubscribeInteractedPostsEnabled = user.autoSubscribeInteractedPostsEnabled,
                    loading = false,
                    message = if (enabled) {
                        "Interaktions-Auto-Abo aktiviert"
                    } else {
                        "Interaktions-Auto-Abo deaktiviert"
                    }
                )
            }
            .onFailure {
                state = state.copy(loading = false, message = apiError(it, "Auto-Abo speichern fehlgeschlagen"))
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

            action == "open_feed" || type == "feed_post" || type == "post" || type == "extra_post" || type == "photo_reaction" || type == "photo_fotomoji" || type == "photo_comment" || type == "photo_nsfw_marked" || type == "photo_nsfw_unmarked" || type == "bookmarked_photo_reaction" || type == "bookmarked_photo_fotomoji" || type == "bookmarked_photo_comment" || type == "bookmarked_photo_media_appended" || type == "bookmarked_photo_nsfw_marked" || type == "bookmarked_photo_nsfw_unmarked" || targetDay.isNotBlank() || targetPhotoId != null -> {
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
                clearHiddenNewerContentIfReached(day)
                state = state.copy(
                    activeTab = AppTab.FEED,
                    feedFocusDay = day,
                    feedFocusPhotoId = targetPhotoId,
                    feedFocusBoundary = if (targetPhotoId == null) FeedJumpBoundary.START else null,
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
        PushNotificationDiagnostics.recordEvent(
            this,
            type = "push_activity_open",
            message = "onCreate",
            meta = "intentAction=${intent?.getStringExtra(EXTRA_LAUNCH_ACTION).orEmpty().ifBlank { "-" }};intentType=${intent?.getStringExtra(EXTRA_LAUNCH_TYPE).orEmpty().ifBlank { "-" }};snapshot=${PushNotificationDiagnostics.activeNotificationsSnapshot(this)}"
        )
        PushNotificationDiagnostics.recordLaunchIntent(this, "activity_on_create", intent)
        PushMessagingService.clearTrackedPushNotifications(this, reason = "activity_on_create")

        val httpClient = buildStandardHttpClient()
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
        PushNotificationDiagnostics.recordEvent(
            this,
            type = "push_activity_open",
            message = "onNewIntent",
            meta = "intentAction=${intent.getStringExtra(EXTRA_LAUNCH_ACTION).orEmpty().ifBlank { "-" }};intentType=${intent.getStringExtra(EXTRA_LAUNCH_TYPE).orEmpty().ifBlank { "-" }};snapshot=${PushNotificationDiagnostics.activeNotificationsSnapshot(this)}"
        )
        PushNotificationDiagnostics.recordLaunchIntent(this, "activity_on_new_intent", intent)
        PushMessagingService.clearTrackedPushNotifications(this, reason = "activity_on_new_intent")
        if (::repo.isInitialized) {
            repo.captureLaunchIntent(intent)
        }
        launchIntentTick += 1
    }

    override fun onResume() {
        super.onResume()
        PushNotificationDiagnostics.recordEvent(
            this,
            type = "push_activity_open",
            message = "onResume",
            meta = "snapshot=${PushNotificationDiagnostics.activeNotificationsSnapshot(this)}"
        )
        PushMessagingService.clearTrackedPushNotifications(this, reason = "activity_on_resume")
    }

}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var appendCapturePhotoId by remember { mutableStateOf<Long?>(null) }
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
                "append" -> {
                    if (shotUri != null) {
                        val targetPhotoId = appendCapturePhotoId
                        if (targetPhotoId != null) {
                            val shareLocation = cameraLocationShareEnabled && (state.user?.locationFeatureEnabled == true) && locationPermissionGranted
                            scope.launch {
                                val ok = vm.appendPhotoToLatestPost(targetPhotoId, shotUri, shareLocation)
                                if (ok) {
                                    vm.refreshAll(refreshFeedWindow = true)
                                    vm.setTab(AppTab.FEED)
                                }
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
        appendCapturePhotoId = null
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

    fun startAppendCapture(photoId: Long) {
        appendCapturePhotoId = photoId
        cameraUploadPercent = 0
        cameraUploadError = ""
        cameraUploadDone = false
        openCameraFor("append")
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

    LaunchedEffect(state.token, state.activeTab) {
        if (state.token.isBlank()) return@LaunchedEffect
        while (true) {
            vm.refreshAll(refreshFeedWindow = vm.state.activeTab != AppTab.FEED)
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
        vm.refreshAll(refreshFeedWindow = vm.state.activeTab != AppTab.FEED)
    }

    LaunchedEffect(state.token, state.startupDone, state.activeTab) {
        if (state.token.isBlank() || !state.startupDone) return@LaunchedEffect
        if (state.activeTab != AppTab.FEED) return@LaunchedEffect
        while (true) {
            delay(vm.feedAutoRefreshIntervalMs())
            if (vm.state.activeTab != AppTab.FEED) break
            if (!vm.state.feedRefreshing && !vm.state.loading && !vm.shouldPauseFeedAutoRefresh()) {
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
        val viewedProfileUsername = safeApiString(profile.user.username, "unbekannt")
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
            title = { Text("@$viewedProfileUsername") },
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
            onOpenHashtagSearch = { vm.openCalendarSearch(it) },
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

    var feedModePickerVisible by remember { mutableStateOf(false) }
    val feedTabLabel = when (state.feedOrderMode) {
        FeedOrderMode.CHRONO -> "Feed"
        FeedOrderMode.TREND -> "Trend"
        FeedOrderMode.RANDOM -> "Zufall"
    }

    if (feedModePickerVisible) {
        AlertDialog(
            onDismissRequest = { feedModePickerVisible = false },
            title = { Text("Feed-Modus wechseln") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedOrderMode.entries.forEach { mode ->
                        OutlinedButton(
                            onClick = {
                                vm.setFeedOrderMode(mode)
                                feedModePickerVisible = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                when (mode) {
                                    FeedOrderMode.CHRONO -> "Feed"
                                    FeedOrderMode.TREND -> "Trend"
                                    FeedOrderMode.RANDOM -> "Zufall"
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { feedModePickerVisible = false }) { Text("Schliessen") }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = state.activeTab == AppTab.CAMERA, onClick = { vm.setTab(AppTab.CAMERA) }, label = { Text("Kamera") }, icon = { Text("U") })
                FeedNavigationItem(
                    selected = state.activeTab == AppTab.FEED,
                    label = feedTabLabel,
                    onClick = { vm.setTab(AppTab.FEED) },
                    onLongClick = { feedModePickerVisible = true }
                )
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
                    showConnectionHealthIndicator = state.showConnectionHealthIndicator,
                    connectionHealthSnapshot = state.connectionHealthSnapshot,
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
                    onAppendToLatestPost = { photoId -> startAppendCapture(photoId) },
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
                    viewerId = state.user?.id,
                    days = state.feedDays,
                    allKnownDays = state.calendarDays,
                    byDay = state.feedByDay,
                    monthRecapByDay = state.monthRecapByDay,
                    promptMetaByDay = state.promptMetaByDay,
                    focusDay = state.feedFocusDay,
                    focusPhotoId = state.feedFocusPhotoId,
                    focusBoundary = state.feedFocusBoundary,
                    scrollRequestId = state.feedScrollRequestId,
                    viewportRestoreAnchor = state.feedViewportRestoreAnchor,
                    viewportRestoreRequestId = state.feedViewportRestoreRequestId,
                    jumpLoadingDay = state.feedJumpLoadingDay,
                    listState = feedListState,
                    refreshing = state.feedRefreshing,
                    todayLocked = state.feedTodayLocked,
                    hasHiddenNewerContent = state.feedHasHiddenNewerContent,
                    hiddenNewerAnchorDay = state.feedHiddenNewerAnchorDay,
                    paging = state.feedPaging,
                    feedWindowReloadInFlight = state.feedWindowReloadInFlight,
                    feedOrderMode = state.feedOrderMode,
                    showPublicPostNumbers = state.showPublicPostNumbers,
                    preferSwipeForTwoImagePosts = state.preferSwipeForTwoImagePosts,
                    showNsfwByDefault = state.user?.showNsfwByDefault ?: false,
                    onTakePhoto = { vm.setTab(AppTab.CAMERA) },
                    onRefresh = { scope.launch { vm.refreshFeed() } },
                    onLoadOlder = { scope.launch { vm.loadOlderFeedDays() } },
                    onLoadNewer = { scope.launch { vm.loadNewerFeedDays() } },
                    onViewportAnchorChanged = vm::updateFeedViewportAnchor,
                    onJumpToDay = { day -> scope.launch { vm.jumpToDay(day) } },
                    onJumpToBoundary = { day, boundary -> scope.launch { vm.jumpToDayBoundary(day, boundary) } },
                    onJumpToCapsule = { day, photoId -> scope.launch { vm.jumpToPhoto(day, photoId) } },
                    onShowHiddenNewerContent = { day -> scope.launch { vm.jumpToDay(day) } },
                    onScrollRequestConsumed = vm::consumeFeedScrollRequest,
                    onViewportRestoreConsumed = vm::consumeFeedViewportRestore,
                    onViewportRestoreResult = vm::reportFeedViewportRestoreResult,
                    onOpenUserProfile = { userId -> scope.launch { vm.loadUserProfile(userId) } },
                    onToggleBookmark = { photoId, bookmarked -> scope.launch { vm.toggleBookmark(photoId, bookmarked) } },
                    onToggleMark = { photoId, marked -> scope.launch { vm.toggleMark(photoId, marked) } },
                    onDeleteMark = { photoId, targetUserId -> scope.launch { vm.deletePhotoMark(photoId, targetUserId) } },
                    onApplyLocalPaint = { photoId, viewerId, username, color, paths, strokeWidth ->
                        vm.applyLocalPhotoPaint(photoId, viewerId, username, color, paths, strokeWidth)
                    },
                    onSavePaint = { photoId, paths, strokeWidth -> scope.launch { vm.savePhotoPaint(photoId, paths, strokeWidth) } },
                    onDeletePaint = { photoId, targetUserId -> scope.launch { vm.deletePhotoPaint(photoId, targetUserId) } },
                    onReportPhoto = { photoId -> scope.launch { vm.reportPhoto(photoId) } },
                    onToggleNsfw = { photoId, nsfw -> scope.launch { vm.togglePhotoNsfw(photoId, nsfw) } },
                    onOpenHashtagSearch = { vm.openCalendarSearch(it) },
                    onOpenViewer = { urls, photoId ->
                        viewerUrls = urls
                        viewerIndex = 0
                        viewerPhotoId = photoId
                        val isOwn = photoId != null && state.photos.any { it.id == photoId }
                        viewerOwnDownloadFallback = isOwn && (state.user?.allowPhotoDownload == true)
                    }
                )

                AppTab.CALENDAR -> CalendarTab(
                    mode = state.calendarMode,
                    days = state.calendarDays,
                    dayStats = state.calendarDayStats,
                    photosByDay = when (state.calendarMode) {
                        CalendarMode.PUBLIC -> state.calendarPublicData.photosByDay
                        CalendarMode.BOOKMARKS -> state.calendarBookmarksData.photosByDay
                        CalendarMode.TIME_CAPSULES -> state.calendarTimeCapsulesData.photosByDay
                        CalendarMode.SEARCH -> emptyMap()
                    },
                    bookmarkItems = state.calendarBookmarksData.feedItems,
                    bookmarkFilter = state.calendarBookmarksFilter,
                    timeCapsuleItems = state.calendarTimeCapsulesData.feedItems,
                    timeCapsuleFilter = state.calendarTimeCapsuleFilter,
                    timeCapsuleLockedCount = state.calendarTimeCapsulesData.lockedCount,
                    timeCapsuleReleasedCount = state.calendarTimeCapsulesData.releasedCount,
                    searchQuery = state.calendarSearchQuery,
                    searchResults = state.calendarSearchData.flatMatches,
                    selected = state.calendarSelectedDay ?: state.prompt?.day.orEmpty(),
                    pickerExpanded = state.calendarPickerExpanded,
                    loading = state.calendarLoading,
                    showPublicPostNumbers = state.showPublicPostNumbers,
                    onPickerExpandedChange = { vm.setCalendarPickerExpanded(it) },
                    onModeChange = { vm.setCalendarMode(it) },
                    onBookmarkFilterChange = { vm.setCalendarBookmarksFilter(it) },
                    onTimeCapsuleFilterChange = { vm.setCalendarTimeCapsuleFilter(it) },
                    onSearchQueryChange = { vm.setCalendarSearchQuery(it) },
                    onSearchSubmit = { vm.submitCalendarSearch() },
                    onOpenHashtagSearch = { vm.openCalendarSearch(it) },
                    onSelect = { day -> vm.selectCalendarDay(day) },
                    onOpenDayInFeed = { day -> scope.launch { vm.jumpToDay(day) } },
                    onOpenPhotoInFeed = { day, photoId -> scope.launch { vm.jumpToPhoto(day, photoId) } }
                )

                AppTab.CHAT -> ChatTab(
                    items = state.chat,
                    meId = state.user?.id,
                    isAdmin = state.user?.isAdmin == true,
                    chatDeleteSupported = state.chatDeleteSupported,
                    input = chatInput,
                    sending = state.chatSending,
                    chatMessageMaxLength = state.promptRules.effectiveChatMessageMaxLength(),
                    chatMessageUnlimited = state.promptRules.isChatMessageUnlimited(),
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
                    bookmarksGivenCount = state.bookmarksGivenCount,
                    bookmarksReceivedCount = state.bookmarksReceivedCount,
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
                    bookmarkedPhotoPushEnabled = state.user?.bookmarkedPhotoPushEnabled ?: state.bookmarkedPhotoPushEnabled,
                    postChangePushEnabled = state.user?.postChangePushEnabled ?: state.postChangePushEnabled,
                    autoSubscribeInteractedPostsEnabled = state.user?.autoSubscribeInteractedPostsEnabled ?: state.autoSubscribeInteractedPostsEnabled,
                    ownPostNumberInPushEnabled = state.user?.ownPostNumberInPushEnabled ?: state.ownPostNumberInPushEnabled,
                    postNumberInPushEnabled = state.user?.postNumberInPushEnabled ?: state.postNumberInPushEnabled,
                    yoloModeEnabled = state.user?.yoloModeEnabled ?: state.yoloModeEnabled,
                    allowPhotoDownload = state.user?.allowPhotoDownload ?: false,
                    allowCommunityNsfwMarking = state.user?.allowCommunityNsfwMarking ?: false,
                    showNsfwByDefault = state.user?.showNsfwByDefault ?: false,
                    creativePostMode = state.user?.creativePostMode ?: "none",
                    locationFeatureEnabled = state.user?.locationFeatureEnabled ?: false,
                    locationShareDefaultEnabled = state.user?.locationShareDefaultEnabled ?: false,
                    locationPermissionGranted = locationPermissionGranted,
                    feedPostPushEnabled = state.feedPostPushEnabled,
                    showPublicPostNumbers = state.showPublicPostNumbers,
                    preferSwipeForTwoImagePosts = state.preferSwipeForTwoImagePosts,
                    showConnectionHealthIndicator = state.showConnectionHealthIndicator,
                    customNotificationToneEnabled = state.customNotificationToneEnabled,
                    customNotificationToneUri = state.customNotificationToneUri,
                    debugMasterEnabled = state.debugMasterEnabled,
                    feedDebugEnabled = state.feedDebugEnabled,
                    diagnosticsUploadEnabled = state.diagnosticsUploadEnabled,
                    diagnosticsConsentGranted = state.diagnosticsConsentGranted,
                    debugLogs = state.debugLogs,
                    notificationDebugEnabled = state.notificationDebugEnabled,
                    notificationDebugExpiresAt = state.notificationDebugExpiresAt,
                    notificationDebugEvents = state.notificationDebugEvents,
                    notificationDebugLaunches = state.notificationDebugLaunches,
                    notificationDebugPayloads = state.notificationDebugPayloads,
                    notificationDebugActiveItems = state.notificationDebugActiveItems,
                    notificationDebugEnvironment = state.notificationDebugEnvironment,
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
                    onBookmarkedPhotoPushEnabledChange = { scope.launch { vm.setBookmarkedPhotoPushEnabled(it) } },
                    onPostChangePushEnabledChange = { scope.launch { vm.setPostChangePushEnabled(it) } },
                    onAutoSubscribeInteractedPostsEnabledChange = { scope.launch { vm.setAutoSubscribeInteractedPostsEnabled(it) } },
                    onNotificationPostNumbersEnabledChange = { scope.launch { vm.setNotificationPostNumbersEnabled(it) } },
                    onOwnPostNumberInPushEnabledChange = { scope.launch { vm.setOwnPostNumberInPushEnabled(it) } },
                    onPostNumberInPushEnabledChange = { scope.launch { vm.setPostNumberInPushEnabled(it) } },
                    onYoloModeEnabledChange = { scope.launch { vm.setYoloModeEnabled(it) } },
                    onAllowPhotoDownloadChange = { scope.launch { vm.setAllowPhotoDownloadEnabled(it) } },
                    onAllowCommunityNsfwMarkingChange = { scope.launch { vm.setAllowCommunityNsfwMarkingEnabled(it) } },
                    onShowNsfwByDefaultChange = { scope.launch { vm.setShowNsfwByDefaultEnabled(it) } },
                    onCreativePostModeChange = { scope.launch { vm.setCreativePostMode(it) } },
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
                    onDebugMasterEnabledChange = { vm.setDebugMasterEnabled(it) },
                    onFeedDebugEnabledChange = { vm.setFeedDebugEnabled(it) },
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
                    onNotificationDebugEnabledChange = { vm.setNotificationDebugEnabled(it) },
                    onExportNotificationDebug = {
                        val uri = vm.exportNotificationDebugBundle()
                        if (uri != null) {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "Daily Notification Debug")
                                putExtra(Intent.EXTRA_TEXT, "Notification-Debug-Export aus Daily")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "Notification-Diagnose teilen"))
                        }
                    },
                    onNotificationDebugPushMatrix = { vm.postNotificationDebugScenario("mixed_matrix") },
                    onNotificationDebugSnapshotAndReset = { vm.notificationDebugSnapshotAndReset() },
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
                    onCheckConnection = { scope.launch { vm.checkConnection() } },
                    onAllowInsecureHttpOverrideChange = { vm.setAllowInsecureHttpOverride(it) },
                    onApplyServerBaseUrlOverride = { input -> scope.launch { vm.applyServerBaseUrlOverride(input) } },
                    onShowPublicPostNumbersChange = { vm.setShowPublicPostNumbers(it) },
                    onPreferSwipeForTwoImagePostsChange = { vm.setPreferSwipeForTwoImagePosts(it) },
                    onShowConnectionHealthIndicatorChange = { vm.setShowConnectionHealthIndicator(it) },
                    onClearAllBookmarks = { scope.launch { vm.clearAllBookmarks() } },
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
    showConnectionHealthIndicator: Boolean,
    connectionHealthSnapshot: ConnectionHealthSnapshot,
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
    onAppendToLatestPost: (Long) -> Unit,
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
    val ownMedia = prompt?.ownPhoto?.mediaItems().orEmpty()
    var showCapsuleDialog by remember { mutableStateOf(false) }
    var pendingCapsule by remember { mutableStateOf<CapsuleUploadOptions?>(null) }
    var showConnectionHealthDialog by remember { mutableStateOf(false) }
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
                if (showConnectionHealthIndicator) {
                    ConnectionHealthDot(
                        snapshot = connectionHealthSnapshot,
                        onClick = { showConnectionHealthDialog = true }
                    )
                }
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
        val specialTriggeredAt = prompt?.specialTriggeredAt
        if (!specialTriggeredAt.isNullOrBlank()) {
            val requester = prompt.specialRequestedByUser
            if (!requester.isNullOrBlank()) {
                Text("Sondermoment heute um ${formatMomentTime(specialTriggeredAt)} von $requester.")
            } else {
                Text("Sondermoment heute um ${formatMomentTime(specialTriggeredAt)}.")
            }
        } else {
            Text("Sondermoment heute noch nicht ausgeloest.")
        }
        if (showConnectionHealthIndicator && showConnectionHealthDialog) {
            ConnectionHealthDialog(
                snapshot = connectionHealthSnapshot,
                onDismiss = { showConnectionHealthDialog = false }
            )
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
            val ownUrls = if (hasPromptPosted) ownMedia.map { it.url } else emptyList()
            if (ownUrls.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ownMedia.take(3).forEachIndexed { index, mediaItem ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(220.dp)
                                .clickable { onOpenViewer(ownUrls, prompt?.ownPhoto?.id) }
                        ) {
                            AsyncImage(
                                model = mediaItem.url,
                                contentDescription = "Mein heutiges Foto",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            if (index == 2 && ownMedia.size > 3) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("+${ownMedia.size - 2}", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
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
                if (prompt?.canAppendToOwnLatestPost == true && prompt.appendTargetPhotoId != null) {
                    OutlinedButton(
                        onClick = { onAppendToLatestPost(prompt.appendTargetPhotoId) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Dem eigenen letzten Beitrag Bild hinzufuegen") }
                } else if (hasVisiblePosted) {
                    Text(
                        "Weitere Bilder lassen sich nur am letzten sichtbaren Beitrag von heute anhaengen.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            progress = { uploadPercent / 100f },
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
                        Text("$kindLabel - ${queueStatusLabel(item)}", fontWeight = FontWeight.SemiBold)
                        if (item.status == UploadQueueStatus.RUNNING || item.status == UploadQueueStatus.AWAITING_SERVER_ACK) {
                            val p = item.transferProgressPercent.coerceIn(0, 100)
                            Text("Fortschritt: $p%")
                            LinearProgressIndicator(
                                progress = { p / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text("Versuche: ${item.attempts}")
                        item.capturedAtMs.takeIf { it > 0L }?.let {
                            Text("Aufgenommen: ${formatQueueTimestamp(it)}")
                        }
                        Text("In Warteschlange seit: ${formatQueueTimestamp(item.createdAtMs)}")
                        item.lastAttemptStartedAtMs.takeIf { it > 0L }?.let {
                            Text("Zuletzt versucht: ${formatQueueTimestamp(it)}")
                        }
                        nextRetryLabel(item)?.let { retryLabel ->
                            Text(retryLabel)
                        }
                        if (item.serverAckState == UploadQueueServerAckState.PENDING) {
                            Text("Warte auf Serverbestaetigung")
                        }
                        if (item.lastError.isNotBlank()) {
                            Text(item.lastError, color = Color(0xFF8B0000), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (queueManualRetryAllowed(item.status)) {
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
    viewerId: Long?,
    days: List<String>,
    allKnownDays: List<String>,
    byDay: Map<String, List<FeedItem>>,
    monthRecapByDay: Map<String, MonthlyRecap>,
    promptMetaByDay: Map<String, PromptMeta>,
    focusDay: String?,
    focusPhotoId: Long?,
    focusBoundary: FeedJumpBoundary?,
    scrollRequestId: Long,
    viewportRestoreAnchor: FeedViewportAnchor,
    viewportRestoreRequestId: Long,
    jumpLoadingDay: String?,
    listState: LazyListState,
    refreshing: Boolean,
    todayLocked: Boolean,
    hasHiddenNewerContent: Boolean,
    hiddenNewerAnchorDay: String?,
    paging: Boolean,
    feedWindowReloadInFlight: Boolean,
    feedOrderMode: FeedOrderMode,
    showPublicPostNumbers: Boolean,
    preferSwipeForTwoImagePosts: Boolean,
    showNsfwByDefault: Boolean,
    onTakePhoto: () -> Unit,
    onRefresh: () -> Unit,
    onLoadOlder: () -> Unit,
    onLoadNewer: () -> Unit,
    onViewportAnchorChanged: (FeedViewportAnchor) -> Unit,
    onJumpToDay: (String) -> Unit,
    onJumpToBoundary: (String, FeedJumpBoundary) -> Unit,
    onJumpToCapsule: (day: String, photoId: Long) -> Unit,
    onShowHiddenNewerContent: (String) -> Unit,
    onScrollRequestConsumed: () -> Unit,
    onViewportRestoreConsumed: () -> Unit,
    onViewportRestoreResult: (FeedViewportAnchor, FeedViewportAnchor?, String) -> Unit,
    onOpenUserProfile: (Long) -> Unit,
    onToggleBookmark: (photoId: Long, bookmarked: Boolean) -> Unit,
    onToggleMark: (photoId: Long, marked: Boolean) -> Unit,
    onDeleteMark: (photoId: Long, targetUserId: Long?) -> Unit,
    onApplyLocalPaint: (photoId: Long, viewerId: Long, username: String, color: String, paths: List<PhotoPaintPath>, strokeWidth: Float) -> Unit,
    onSavePaint: (photoId: Long, paths: List<PhotoPaintPath>, strokeWidth: Float) -> Unit,
    onDeletePaint: (photoId: Long, targetUserId: Long?) -> Unit,
    onReportPhoto: (photoId: Long) -> Unit,
    onToggleNsfw: (photoId: Long, nsfw: Boolean) -> Unit,
    onOpenHashtagSearch: (String) -> Unit,
    onOpenViewer: (List<String>, Long?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val primaryTextColor = MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pullRefreshState = rememberPullRefreshState(refreshing = refreshing, onRefresh = onRefresh)
    var paintEditorPhoto by remember { mutableStateOf<PaintEditorTarget?>(null) }
    var paintModerationPhoto by remember { mutableStateOf<FeedItem?>(null) }
    var markModerationPhoto by remember { mutableStateOf<FeedItem?>(null) }
    var revealedNsfwPhotoIds by remember { mutableStateOf(setOf<Long>()) }

    val rows = remember(days, byDay, monthRecapByDay, promptMetaByDay, jumpLoadingDay) {
        buildList {
            for (day in days) {
                add(FeedRow.DayHeader(day, promptMetaByDay[day]))
                byDay[day].orEmpty().forEach { add(FeedRow.PhotoItem(day, it)) }
                monthRecapByDay[day]?.let { add(FeedRow.MonthRecapItem(day, it)) }
                if (byDay[day].isNullOrEmpty() && jumpLoadingDay == day) {
                    add(FeedRow.LoadingItem(day))
                }
            }
        }
    }
    fun rowDayAt(index: Int): String? = when (val row = rows.getOrNull(index)) {
        is FeedRow.DayHeader -> row.day
        is FeedRow.PhotoItem -> row.day
        is FeedRow.MonthRecapItem -> row.day
        is FeedRow.LoadingItem -> row.day
        null -> null
    }
    fun rowAnchorAt(index: Int, offsetPx: Int, firstVisibleIndex: Int = index, lastVisibleIndex: Int = index): FeedViewportAnchor? = when (val row = rows.getOrNull(index)) {
        is FeedRow.PhotoItem -> FeedViewportAnchor(
            day = row.day,
            photoId = row.item.photo.id,
            rowOffsetPx = offsetPx,
            kind = FeedViewportAnchorKind.PHOTO,
            rowIndex = index,
            firstVisibleIndex = firstVisibleIndex,
            lastVisibleIndex = lastVisibleIndex,
            rowsSize = rows.size,
            presentInRows = true
        )
        is FeedRow.DayHeader -> FeedViewportAnchor(
            day = row.day,
            rowOffsetPx = offsetPx,
            kind = FeedViewportAnchorKind.DAY_HEADER,
            rowIndex = index,
            firstVisibleIndex = firstVisibleIndex,
            lastVisibleIndex = lastVisibleIndex,
            rowsSize = rows.size,
            presentInRows = true
        )
        is FeedRow.MonthRecapItem -> FeedViewportAnchor(
            day = row.day,
            rowOffsetPx = offsetPx,
            kind = FeedViewportAnchorKind.RECAP,
            rowIndex = index,
            firstVisibleIndex = firstVisibleIndex,
            lastVisibleIndex = lastVisibleIndex,
            rowsSize = rows.size,
            presentInRows = true
        )
        is FeedRow.LoadingItem -> FeedViewportAnchor(
            day = row.day,
            rowOffsetPx = offsetPx,
            kind = FeedViewportAnchorKind.LOADING,
            rowIndex = index,
            firstVisibleIndex = firstVisibleIndex,
            lastVisibleIndex = lastVisibleIndex,
            rowsSize = rows.size,
            presentInRows = true
        )
        null -> null
    }
    fun boundaryRowIndex(day: String, boundary: FeedJumpBoundary): Int {
        val matching = rows.withIndex().filter { rowDayAt(it.index) == day }
        if (matching.isEmpty()) return -1
        return when (boundary) {
            FeedJumpBoundary.START -> matching.firstOrNull { it.value is FeedRow.DayHeader }?.index ?: matching.first().index
            FeedJumpBoundary.END -> matching.last().index
        }
    }
    fun restoreRowIndex(anchor: FeedViewportAnchor): Int = when {
        anchor.photoId != null ->
            rows.indexOfFirst { it is FeedRow.PhotoItem && it.item.photo.id == anchor.photoId }
        anchor.day.isNullOrBlank() -> -1
        else -> when (anchor.kind) {
            FeedViewportAnchorKind.PHOTO ->
                rows.indexOfFirst { row -> row is FeedRow.PhotoItem && row.day == anchor.day }
            FeedViewportAnchorKind.DAY_HEADER ->
                rows.indexOfFirst { row -> row is FeedRow.DayHeader && row.day == anchor.day }
            FeedViewportAnchorKind.RECAP ->
                rows.indexOfFirst { row -> row is FeedRow.MonthRecapItem && row.day == anchor.day }
            FeedViewportAnchorKind.LOADING ->
                rows.indexOfFirst { row -> row is FeedRow.LoadingItem && row.day == anchor.day }
        }.takeIf { it >= 0 } ?: boundaryRowIndex(anchor.day, FeedJumpBoundary.START)
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
    val currentAnchorDay = remember(focusDay, prompt?.day, days) {
        when {
            !focusDay.isNullOrBlank() -> focusDay
            !prompt?.day.isNullOrBlank() -> prompt?.day
            else -> days.firstOrNull()
        }
    }
    val dayHeaderIndexByDay = remember(rows) {
        buildMap<String, Int> {
            rows.forEachIndexed { index, row ->
                if (row is FeedRow.DayHeader) put(row.day, index)
            }
        }
    }
    var highlightedDay by remember { mutableStateOf<String?>(null) }
    val visibleRange = remember(listState) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: -1
            val last = visible.lastOrNull()?.index ?: -1
            first to last
        }
    }
    val firstVisibleIndex by remember { derivedStateOf { visibleRange.value.first } }
    val lastVisibleIndex by remember { derivedStateOf { visibleRange.value.second } }
    val currentViewportAnchor by remember(rows, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        derivedStateOf {
            rowAnchorAt(
                index = listState.firstVisibleItemIndex,
                offsetPx = listState.firstVisibleItemScrollOffset,
                firstVisibleIndex = firstVisibleIndex,
                lastVisibleIndex = lastVisibleIndex
            )
        }
    }
    val newestKnownDay = remember(allKnownDays, days, prompt?.day) {
        allKnownDays.firstOrNull() ?: days.firstOrNull() ?: prompt?.day
    }
    val oldestKnownDay = remember(allKnownDays, days) {
        allKnownDays.lastOrNull() ?: days.lastOrNull()
    }
    val loadedNewestDay = remember(days) { days.firstOrNull() }
    val loadedOldestDay = remember(days) { days.lastOrNull() }
    val showScrollTop by remember(newestKnownDay, loadedNewestDay) {
        derivedStateOf {
            rows.isNotEmpty() && (firstVisibleIndex > 2 || (!newestKnownDay.isNullOrBlank() && newestKnownDay != loadedNewestDay))
        }
    }
    val showScrollBottom by remember(oldestKnownDay, loadedOldestDay) {
        derivedStateOf {
            rows.isNotEmpty() && (
                lastVisibleIndex in 0 until rows.lastIndex - 1 ||
                    (!oldestKnownDay.isNullOrBlank() && oldestKnownDay != loadedOldestDay)
                )
        }
    }
    val currentAnchorVisible by remember(currentAnchorDay, dayHeaderIndexByDay) {
        derivedStateOf {
            val anchorIndex = currentAnchorDay?.let(dayHeaderIndexByDay::get) ?: return@derivedStateOf true
            anchorIndex in firstVisibleIndex..lastVisibleIndex
        }
    }
    val showJumpToCurrentAnchor by remember(currentAnchorDay) {
        derivedStateOf { !currentAnchorDay.isNullOrBlank() && !currentAnchorVisible }
    }

    var handledScrollRequestId by remember { mutableLongStateOf(0L) }
    LaunchedEffect(scrollRequestId, rows.size) {
        if (scrollRequestId <= 0L || scrollRequestId == handledScrollRequestId) return@LaunchedEffect
        val idx = if (focusPhotoId != null) {
            rows.indexOfFirst { it is FeedRow.PhotoItem && it.item.photo.id == focusPhotoId }
        } else {
            val target = focusDay ?: return@LaunchedEffect
            boundaryRowIndex(target, focusBoundary ?: FeedJumpBoundary.START)
        }
        if (idx < 0) return@LaunchedEffect
        val distance = if (firstVisibleIndex >= 0) kotlin.math.abs(idx - firstVisibleIndex) else Int.MAX_VALUE
        if (distance <= 6) {
            listState.animateScrollToItem(idx)
        } else {
            listState.scrollToItem(idx)
        }
        handledScrollRequestId = scrollRequestId
        highlightedDay = focusDay ?: rowDayAt(idx)
        onScrollRequestConsumed()
    }
    var handledViewportRestoreRequestId by remember { mutableLongStateOf(0L) }
    LaunchedEffect(viewportRestoreRequestId, rows.size) {
        if (viewportRestoreRequestId <= 0L || viewportRestoreRequestId == handledViewportRestoreRequestId) return@LaunchedEffect
        val idx = restoreRowIndex(viewportRestoreAnchor)
        if (idx >= 0) {
            listState.scrollToItem(idx, viewportRestoreAnchor.rowOffsetPx.coerceAtLeast(0))
            onViewportRestoreResult(
                viewportRestoreAnchor,
                rowAnchorAt(
                    index = idx,
                    offsetPx = viewportRestoreAnchor.rowOffsetPx.coerceAtLeast(0),
                    firstVisibleIndex = idx,
                    lastVisibleIndex = idx
                ),
                ""
            )
        } else {
            onViewportRestoreResult(viewportRestoreAnchor, null, "anchor_not_found")
        }
        handledViewportRestoreRequestId = viewportRestoreRequestId
        onViewportRestoreConsumed()
    }
    LaunchedEffect(highlightedDay) {
        if (highlightedDay != null) {
            delay(1800)
            highlightedDay = null
        }
    }

    LaunchedEffect(currentViewportAnchor) {
        currentViewportAnchor?.let(onViewportAnchorChanged)
    }

    LaunchedEffect(listState, rows.size, paging, refreshing, feedWindowReloadInFlight) {
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()?.index ?: -1
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            Triple(first, last, pullRefreshState.progress)
        }.collect { (first, last, pullProgress) ->
            if (rows.isEmpty() || paging || refreshing || feedWindowReloadInFlight || pullProgress > 0f) return@collect
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
            if (false && false) item("feed-mode-header") {
                Card {
                    Text(
                        when (feedOrderMode) {
                            FeedOrderMode.CHRONO -> "Feed · Chronologisch"
                            FeedOrderMode.TREND -> "Feed · Trend"
                            FeedOrderMode.RANDOM -> "Feed · Zufall"
                        },
                        modifier = Modifier.padding(10.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
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
            if (hasHiddenNewerContent && !hiddenNewerAnchorDay.isNullOrBlank()) {
                item("hidden-newer-feed") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFD9F2E6))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Neue Beitraege verfuegbar", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F5132))
                                Text(
                                    "Oberhalb dieses Bereichs gibt es aktualisierte Feed-Inhalte. Deine aktuelle Position bleibt erhalten.",
                                    color = Color(0xFF0F5132)
                                )
                            }
                            TextButton(onClick = { onShowHiddenNewerContent(hiddenNewerAnchorDay) }) {
                                Text("Anzeigen")
                            }
                        }
                    }
                }
            }

        items(rows, key = {
            when (it) {
                is FeedRow.DayHeader -> "day-${it.day}"
                is FeedRow.PhotoItem -> "photo-${it.item.photo.id}"
                is FeedRow.MonthRecapItem -> "recap-${it.recap.month}"
                is FeedRow.LoadingItem -> "loading-${it.day}"
            }
        }) { row ->
            when (row) {
                is FeedRow.DayHeader -> {
                    val headerColor = weekdayRainbowColor(row.day)
                    val isHighlighted = highlightedDay == row.day
                    Card(
                        colors = CardDefaults.cardColors(if (isHighlighted) headerColor.copy(alpha = 0.88f) else headerColor),
                        modifier = if (isHighlighted) {
                            Modifier.border(2.dp, Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        } else {
                            Modifier
                        }
                    ) {
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
                    var menuExpanded by remember(item.photo.id, item.photo.bookmarkedByMe, item.photo.markedByMe, item.photo.paintedByMe, item.photo.paints) { mutableStateOf(false) }
                    val isMomentWindowPost = isWithinDailyMomentWindow(
                        item.photo.createdAt,
                        meta?.triggeredAt,
                        meta?.uploadUntil
                    )
                    val postMomentKind = normalizeMomentKind(item.momentKind ?: meta?.momentKind, item.triggerSource ?: meta?.triggerSource)
                    val requestedByUser = item.requestedByUser ?: meta?.specialRequestedByUser ?: meta?.requestedByUser
                    val requestedByUserColor = item.specialRequestedByUserColor ?: meta?.specialRequestedByUserColor
                    FeedPostCard(
                        item = item,
                        viewerId = viewerId,
                        secondaryTextColor = secondaryTextColor,
                        primaryTextColor = primaryTextColor,
                        showPublicPostNumbers = showPublicPostNumbers,
                        preferSwipeForTwoImagePosts = preferSwipeForTwoImagePosts,
                        showNsfwByDefault = showNsfwByDefault,
                        nsfwRevealed = revealedNsfwPhotoIds.contains(item.photo.id),
                        isMomentWindowPost = isMomentWindowPost,
                        postMomentKind = postMomentKind,
                        requestedByUser = requestedByUser,
                        requestedByUserColor = requestedByUserColor,
                        onOpenUserProfile = onOpenUserProfile,
                        onOpenViewer = onOpenViewer,
                        onOpenExternalUrl = { url -> openExternalUrl(context, url) },
                        onOpenHashtagSearch = onOpenHashtagSearch,
                        menuExpanded = menuExpanded,
                        onMenuExpandedChange = { menuExpanded = it },
                        onToggleBookmark = onToggleBookmark,
                        onToggleMark = onToggleMark,
                        onDeletePaint = onDeletePaint,
                        onReportPhoto = onReportPhoto,
                        onToggleNsfw = onToggleNsfw,
                        onRevealNsfw = { revealedNsfwPhotoIds = revealedNsfwPhotoIds + item.photo.id },
                        onOpenPaintEditor = {
                            paintEditorPhoto = PaintEditorTarget(
                                item = item,
                                isMomentWindowPost = isMomentWindowPost,
                                postMomentKind = postMomentKind,
                                requestedByUser = requestedByUser,
                                requestedByUserColor = requestedByUserColor
                            )
                        },
                        onOpenPaintModeration = { paintModerationPhoto = item },
                        onOpenMarkModeration = { markModerationPhoto = item }
                    )
                    
                }
                is FeedRow.MonthRecapItem -> {
                    MonthlyRecapCard(row.recap)
                }
                is FeedRow.LoadingItem -> {
                    Card {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Lade ${formatDayWithWeekday(row.day)} ...", fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "Wir holen direkt diesen Bereich und ein paar Tage drumherum.",
                                color = secondaryTextColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
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
        ScrollQuickActions(
            showTop = showScrollTop,
            showBottom = showScrollBottom,
            showAnchor = showJumpToCurrentAnchor,
            anchorLabel = if (currentAnchorDay == prompt?.day) "Heute" else "Zum Tag",
            onTopClick = {
                scope.launch {
                    val targetDay = newestKnownDay
                    if (!targetDay.isNullOrBlank() && targetDay != loadedNewestDay) {
                        onJumpToBoundary(targetDay, FeedJumpBoundary.START)
                    } else if (rows.isNotEmpty()) {
                        listState.animateScrollToItem(0)
                    }
                }
            },
            onBottomClick = {
                scope.launch {
                    val targetDay = oldestKnownDay
                    if (!targetDay.isNullOrBlank() && targetDay != loadedOldestDay) {
                        onJumpToBoundary(targetDay, FeedJumpBoundary.END)
                    } else if (rows.isNotEmpty()) {
                        val boundaryIndex = targetDay?.let { boundaryRowIndex(it, FeedJumpBoundary.END) } ?: rows.lastIndex
                        listState.animateScrollToItem(boundaryIndex.coerceAtLeast(0))
                    }
                }
            },
            onAnchorClick = {
                val anchorDay = currentAnchorDay
                if (anchorDay != null) {
                    val anchorIndex = dayHeaderIndexByDay[anchorDay]
                    scope.launch {
                        if (anchorIndex != null && anchorIndex in rows.indices) {
                            val distance = if (firstVisibleIndex >= 0) kotlin.math.abs(anchorIndex - firstVisibleIndex) else Int.MAX_VALUE
                            highlightedDay = anchorDay
                            if (distance <= 6) {
                                listState.animateScrollToItem(anchorIndex)
                            } else {
                                onJumpToDay(anchorDay)
                            }
                        } else {
                            onJumpToDay(anchorDay)
                        }
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 20.dp)
        )
    }

    paintEditorPhoto?.let { target ->
        PhotoPaintEditorDialog(
            target = target,
            viewerId = viewerId,
            onDismiss = { paintEditorPhoto = null },
            onSave = { paths, strokeWidth ->
                if (viewerId != null) {
                    onApplyLocalPaint(
                        target.item.photo.id,
                        viewerId,
                        target.item.user.username,
                        target.item.photo.paints.firstOrNull { it.userId == viewerId }?.color
                            ?: target.item.user.favoriteColor,
                        paths,
                        strokeWidth
                    )
                }
                onSavePaint(target.item.photo.id, paths, strokeWidth)
                paintEditorPhoto = null
            },
            onDelete = if (target.item.photo.paintedByMe) {
                {
                    if (viewerId != null) {
                        onApplyLocalPaint(
                            target.item.photo.id,
                            viewerId,
                            target.item.user.username,
                            target.item.photo.paints.firstOrNull { it.userId == viewerId }?.color
                                ?: target.item.user.favoriteColor,
                            emptyList(),
                            target.item.photo.paints.firstOrNull { it.userId == viewerId }?.strokeWidth ?: 0.035f
                        )
                    }
                    onDeletePaint(target.item.photo.id, null)
                    paintEditorPhoto = null
                }
            } else {
                null
            }
        )
    }

    paintModerationPhoto?.let { item ->
        AlertDialog(
            onDismissRequest = { paintModerationPhoto = null },
            confirmButton = {
                TextButton(onClick = { paintModerationPhoto = null }) { Text("Schliessen") }
            },
            title = { Text("Fremde Malereien") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val others = item.photo.paints.filter { it.userId != viewerId }
                    if (others.isEmpty()) {
                        Text("Keine fremden Malereien mehr vorhanden.")
                    } else {
                        others.forEach { paint ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    paint.username.ifBlank { "User ${paint.userId}" },
                                    color = parseUserColor(paint.color),
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(
                                    onClick = {
                                        onDeletePaint(item.photo.id, paint.userId)
                                        paintModerationPhoto = null
                                    }
                                ) { Text("Entfernen") }
                            }
                        }
                    }
                }
            }
        )
    }

    markModerationPhoto?.let { item ->
        AlertDialog(
            onDismissRequest = { markModerationPhoto = null },
            confirmButton = {
                TextButton(onClick = { markModerationPhoto = null }) { Text("Schliessen") }
            },
            title = { Text("Fremde Markierungen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val others = item.photo.marks.filter { it.userId != viewerId }
                    if (others.isEmpty()) {
                        Text("Keine fremden Markierungen mehr vorhanden.")
                    } else {
                        others.forEach { mark ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    mark.username.ifBlank { "User ${mark.userId}" },
                                    color = parseUserColor(mark.color),
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(
                                    onClick = {
                                        onDeleteMark(item.photo.id, mark.userId)
                                        markModerationPhoto = null
                                    }
                                ) { Text("Entfernen") }
                            }
                        }
                    }
                }
            }
        )
    }
}

private fun normalizeOverlaySurface(surface: String?): String =
    if (surface.equals("card", ignoreCase = true)) "card" else "frame"

@Composable
private fun FeedPostCard(
    item: FeedItem,
    viewerId: Long?,
    secondaryTextColor: Color,
    primaryTextColor: Color,
    showPublicPostNumbers: Boolean,
    preferSwipeForTwoImagePosts: Boolean,
    showNsfwByDefault: Boolean,
    nsfwRevealed: Boolean,
    isMomentWindowPost: Boolean,
    postMomentKind: String?,
    requestedByUser: String?,
    requestedByUserColor: String?,
    onOpenUserProfile: (Long) -> Unit,
    onOpenViewer: (List<String>, Long?) -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onOpenHashtagSearch: (String) -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onToggleBookmark: (Long, Boolean) -> Unit,
    onToggleMark: (Long, Boolean) -> Unit,
    onDeletePaint: (Long, Long?) -> Unit,
    onReportPhoto: (Long) -> Unit,
    onToggleNsfw: (Long, Boolean) -> Unit,
    onRevealNsfw: () -> Unit,
    onOpenPaintEditor: () -> Unit,
    onOpenPaintModeration: () -> Unit,
    onOpenMarkModeration: () -> Unit
) {
    PostCanvasCard(
        item = item,
        secondaryTextColor = secondaryTextColor,
        primaryTextColor = primaryTextColor,
        showPublicPostNumbers = showPublicPostNumbers,
        preferSwipeForTwoImagePosts = preferSwipeForTwoImagePosts,
        showNsfwByDefault = showNsfwByDefault,
        nsfwRevealed = nsfwRevealed,
        isMomentWindowPost = isMomentWindowPost,
        postMomentKind = postMomentKind,
        requestedByUser = requestedByUser,
        requestedByUserColor = requestedByUserColor,
        onOpenUserProfile = onOpenUserProfile,
        onOpenViewer = onOpenViewer,
        onOpenExternalUrl = onOpenExternalUrl,
        onOpenHashtagSearch = onOpenHashtagSearch,
        onRevealNsfw = onRevealNsfw,
        headerTrailing = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (showPublicPostNumbers && !item.photo.publicNumber.isNullOrBlank()) {
                    Text(
                        "#${item.photo.publicNumber}",
                        color = secondaryTextColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Box {
                    IconButton(onClick = { onMenuExpandedChange(true) }) {
                        Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "Beitragsaktionen")
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { onMenuExpandedChange(false) }
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(if (item.photo.bookmarkedByMe) "Nicht mehr merken" else "Merken") },
                            onClick = {
                                onMenuExpandedChange(false)
                                onToggleBookmark(item.photo.id, !item.photo.bookmarkedByMe)
                            }
                        )
                        if (item.photo.canMark) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (item.photo.markedByMe) "Markierung entfernen" else "Markieren") },
                                onClick = {
                                    onMenuExpandedChange(false)
                                    onToggleMark(item.photo.id, !item.photo.markedByMe)
                                }
                            )
                        }
                        if (viewerId == item.user.id && item.photo.marks.any { it.userId != viewerId }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Fremde Markierungen verwalten") },
                                onClick = {
                                    onMenuExpandedChange(false)
                                    onOpenMarkModeration()
                                }
                            )
                        }
                        if (item.photo.canPaint || item.photo.paintedByMe) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(if (item.photo.paintedByMe) "Malerei bearbeiten" else "Malen") },
                                onClick = {
                                    onMenuExpandedChange(false)
                                    onOpenPaintEditor()
                                }
                            )
                        }
                        if (item.photo.paintedByMe) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Eigene Malerei entfernen") },
                                onClick = {
                                    onMenuExpandedChange(false)
                                    onDeletePaint(item.photo.id, null)
                                }
                            )
                        }
                        if (viewerId == item.user.id && item.photo.paints.any { it.userId != viewerId }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Fremde Malereien verwalten") },
                                onClick = {
                                    onMenuExpandedChange(false)
                                    onOpenPaintModeration()
                                }
                            )
                        }
                        if (item.photo.nsfwMarkAllowed) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Als NSFW markieren") },
                                onClick = {
                                    onMenuExpandedChange(false)
                                    onToggleNsfw(item.photo.id, true)
                                }
                            )
                        }
                        if (item.photo.nsfwUnmarkAllowed && item.photo.nsfw) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("NSFW entfernen") },
                                onClick = {
                                    onMenuExpandedChange(false)
                                    onToggleNsfw(item.photo.id, false)
                                }
                            )
                        }
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Melden") },
                            onClick = {
                                onMenuExpandedChange(false)
                                onReportPhoto(item.photo.id)
                            }
                        )
                    }
                }
            }
        },
        overlay = { frameRect ->
            PhotoMarkLayer(item.photo, frameRect, Modifier.fillMaxSize())
            PhotoPaintLayer(item.photo, frameRect, Modifier.fillMaxSize())
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PostCanvasCard(
    modifier: Modifier = Modifier,
    item: FeedItem,
    secondaryTextColor: Color,
    primaryTextColor: Color,
    showPublicPostNumbers: Boolean,
    preferSwipeForTwoImagePosts: Boolean,
    showNsfwByDefault: Boolean,
    nsfwRevealed: Boolean,
    isMomentWindowPost: Boolean,
    postMomentKind: String?,
    requestedByUser: String?,
    requestedByUserColor: String?,
    onOpenUserProfile: ((Long) -> Unit)?,
    onOpenViewer: ((List<String>, Long?) -> Unit)?,
    onOpenExternalUrl: ((String) -> Unit)?,
    onOpenHashtagSearch: (String) -> Unit,
    onRevealNsfw: () -> Unit,
    headerTrailing: @Composable (() -> Unit)? = null,
    overlay: @Composable (Rect) -> Unit = {}
) {
    val mediaItems = remember(item.photo) { item.photo.mediaItems() }
    val urls = remember(mediaItems) { mediaItems.map { it.url } }
    val reactions = remember(item.reactions) { item.reactions.orEmpty() }
    val photoMojis = remember(item.photoMojis) {
        item.photoMojis.orEmpty().sortedWith(compareBy<PhotoMojiItem>({ parseOffsetOrLocalDateTime(it.createdAt) ?: LocalDateTime.MIN }, { it.id }))
    }
    val comments = remember(item.comments) {
        item.comments.orEmpty().sortedWith(compareBy<PhotoCommentItem>({ parseOffsetOrLocalDateTime(it.createdAt) ?: LocalDateTime.MIN }, { it.id }))
    }
    val nsfwHidden = item.photo.nsfw && !showNsfwByDefault && !nsfwRevealed
    val obscuredModifier = if (nsfwHidden) {
        Modifier
            .graphicsLayer(alpha = 0.88f)
            .blur(12.dp)
    } else {
        Modifier
    }
    val density = LocalDensity.current
    var rootOrigin by remember(item.photo.id) { mutableStateOf(Offset.Zero) }
    var rootSize by remember(item.photo.id) { mutableStateOf(IntSize.Zero) }
    var frameOrigin by remember(item.photo.id) { mutableStateOf(Offset.Zero) }
    var frameSize by remember(item.photo.id) { mutableStateOf(Size.Zero) }
    val frameRect = remember(rootOrigin, frameOrigin, frameSize) {
        Rect(
            left = frameOrigin.x - rootOrigin.x,
            top = frameOrigin.y - rootOrigin.y,
            right = frameOrigin.x - rootOrigin.x + frameSize.width,
            bottom = frameOrigin.y - rootOrigin.y + frameSize.height
        )
    }

    Card(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned {
                    rootOrigin = it.positionInRoot()
                    rootSize = it.size
                }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            item.user.username,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = parseUserColor(item.user.favoriteColor),
                            modifier = if (onOpenUserProfile != null) Modifier.clickable { onOpenUserProfile(item.user.id) } else Modifier
                        )
                        if (item.user.statusVisible && (item.user.statusText.isNotBlank() || item.user.statusEmoji.isNotBlank())) {
                            Text(
                                "${item.user.statusEmoji} ${item.user.statusText}".trim(),
                                color = secondaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "🕒 ${formatMomentTime(item.photo.createdAt)}",
                                color = secondaryTextColor,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (item.photo.bookmarkCount > 0) {
                                Text(
                                    "📌 ${item.photo.bookmarkCount}",
                                    color = secondaryTextColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (item.photo.bookmarkedByMe) {
                                Text(
                                    "Gemerkt",
                                    color = Color(0xFF1F5FBF),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    headerTrailing?.invoke() ?: if (showPublicPostNumbers && !item.photo.publicNumber.isNullOrBlank()) {
                        Text(
                            "#${item.photo.publicNumber}",
                            color = secondaryTextColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Spacer(Modifier)
                    }
                }

                if (isMomentWindowPost) {
                    if (postMomentKind == "special") {
                        SpecialMomentBadge(requestedByUser, requestedByUserColor)
                    } else {
                        DailyMomentBadge()
                    }
                } else {
                    uploadTimeHint(item.photo)?.let { hint ->
                        Text(
                            hint,
                            color = secondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (item.photo.locationShared && !item.photo.locationMapsUrl.isNullOrBlank() && onOpenExternalUrl != null) {
                    Text(
                        "📍 Standort",
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onOpenExternalUrl(item.photo.locationMapsUrl) }
                    )
                }

                if (item.capsuleLocked) {
                    Text(
                        "🧊 Oeffnet wieder am ${formatCapsuleOpenAt(item.photo.capsuleVisibleAt)}",
                        color = secondaryTextColor
                    )
                } else if (urls.isNotEmpty()) {
                    val frameShape = RoundedCornerShape(22.dp)
                    val imageShape = RoundedCornerShape(16.dp)
                    val usePagerLayout = urls.size > 2 || (urls.size == 2 && preferSwipeForTwoImagePosts)
                    val pagerState = if (usePagerLayout) rememberPagerState(pageCount = { urls.size }) else null
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(306.dp)
                            .clip(frameShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f))
                            .onGloballyPositioned {
                                frameOrigin = it.positionInRoot()
                                frameSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
                            }
                    ) {
                        if (usePagerLayout) {
                            val activePagerState = requireNotNull(pagerState)
                            HorizontalPager(
                                state = activePagerState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp)
                            ) {
                                AsyncImage(
                                    model = urls[it],
                                    contentDescription = "${item.user.username} Foto",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(obscuredModifier)
                                        .clip(imageShape)
                                        .then(
                                            if (onOpenViewer != null) Modifier.clickable { onOpenViewer(urls, item.photo.id) } else Modifier
                                        ),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                urls.forEach { url ->
                                    AsyncImage(
                                        model = url,
                                        contentDescription = "${item.user.username} Foto",
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .then(obscuredModifier)
                                            .clip(imageShape)
                                            .then(
                                                if (onOpenViewer != null) Modifier.clickable { onOpenViewer(urls, item.photo.id) } else Modifier
                                            ),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                        if (usePagerLayout && urls.size > 1 && pagerState != null) {
                            Card(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.55f))
                            ) {
                                Text(
                                    "${pagerState.currentPage + 1}/${urls.size}",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        if (nsfwHidden) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .background(Color.Black.copy(alpha = 0.22f))
                                    .clickable { onRevealNsfw() }
                                    .padding(18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Card(colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.62f))) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text("NSFW-Inhalt", color = Color.White, fontWeight = FontWeight.Bold)
                                        Text("Sensibilitaetshinweis · Tippen zum Anzeigen", color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }

                if (reactions.isNotEmpty()) {
                    Text(
                        reactions.joinToString("  ") { "${it.emoji} ${it.count}" },
                        color = primaryTextColor,
                        modifier = obscuredModifier
                    )
                }
                if (photoMojis.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().then(obscuredModifier)) {
                        items(photoMojis) { foto ->
                            Row(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large)
                                    .then(if (onOpenViewer != null) Modifier.clickable { onOpenViewer(listOf(foto.url), null) } else Modifier)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AsyncImage(
                                    model = foto.url,
                                    contentDescription = "FotoMoji",
                                    modifier = Modifier.size(34.dp).background(Color.LightGray, CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Text(foto.emoji, fontWeight = FontWeight.Bold, color = parseUserColor(foto.user.favoriteColor))
                            }
                        }
                    }
                }

                val hasCaption = !item.photo.caption.isNullOrBlank()
                if (hasCaption || comments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().then(obscuredModifier)) {
                        if (hasCaption) {
                            CompactCommentLine(
                                username = item.user.username,
                                usernameColor = parseUserColor(item.user.favoriteColor),
                                body = item.photo.caption.orEmpty(),
                                bodyColor = secondaryTextColor,
                                onOpenHashtagSearch = onOpenHashtagSearch
                            )
                        }
                        comments.forEach { comment ->
                            CompactCommentLine(
                                username = comment.user.username,
                                usernameColor = parseUserColor(comment.user.favoriteColor),
                                body = comment.body,
                                bodyColor = secondaryTextColor,
                                onOpenHashtagSearch = onOpenHashtagSearch
                            )
                        }
                    }
                }
            }
            if (rootSize != IntSize.Zero) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { rootSize.height.toDp() })
                ) {
                    overlay(frameRect)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.FeedNavigationItem(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = NavigationBarItemDefaults.colors()
    val iconColor = if (selected) colors.selectedIconColor else colors.unselectedIconColor
    val textColor = if (selected) colors.selectedTextColor else colors.unselectedTextColor
    val indicatorColor = if (selected) colors.selectedIndicatorColor else Color.Transparent

    Box(
        modifier = Modifier
            .weight(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(indicatorColor)
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "T",
                    color = iconColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                label,
                color = textColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CompactCommentLine(
    username: String,
    usernameColor: Color,
    body: String,
    bodyColor: Color,
    onOpenHashtagSearch: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "$username:",
            color = usernameColor,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall
        )
        HashtagText(
            text = body,
            color = bodyColor,
            modifier = Modifier.padding(start = 12.dp),
            onHashtagClick = onOpenHashtagSearch
        )
    }
}

private fun parsePhotoPaintPaths(raw: String): List<PhotoPaintPath> {
    val clean = raw.trim()
    if (clean.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(clean)
        buildList {
            for (i in 0 until arr.length()) {
                val pathObj = arr.optJSONObject(i) ?: continue
                val pointsArr = pathObj.optJSONArray("points") ?: continue
                val points = buildList {
                    for (j in 0 until pointsArr.length()) {
                        val pointObj = pointsArr.optJSONObject(j) ?: continue
                        add(PhotoPaintPoint(pointObj.optDouble("x", 0.0).toFloat().coerceIn(0f, 1f), pointObj.optDouble("y", 0.0).toFloat().coerceIn(0f, 1f)))
                    }
                }
                if (points.size >= 2) add(PhotoPaintPath(points))
            }
        }
    }.getOrDefault(emptyList())
}

private fun encodePhotoPaintPaths(paths: List<PhotoPaintPath>): String {
    val arr = JSONArray()
    paths.forEach { pathItem ->
        if (pathItem.points.size < 2) return@forEach
        val pathObj = JSONObject()
        val pointsArr = JSONArray()
        pathItem.points.forEach { point ->
            val pointObj = JSONObject()
            pointObj.put("x", point.x.coerceIn(0f, 1f))
            pointObj.put("y", point.y.coerceIn(0f, 1f))
            pointsArr.put(pointObj)
        }
        pathObj.put("points", pointsArr)
        arr.put(pathObj)
    }
    return arr.toString()
}

private fun mapOverlayX(value: Float, surface: String, width: Float, frameRect: Rect): Float =
    if (normalizeOverlaySurface(surface) == "card" || frameRect == Rect.Zero) width * value.coerceIn(0f, 1f)
    else frameRect.left + frameRect.width * value.coerceIn(0f, 1f)

private fun mapOverlayY(value: Float, surface: String, height: Float, frameRect: Rect): Float =
    if (normalizeOverlaySurface(surface) == "card" || frameRect == Rect.Zero) height * value.coerceIn(0f, 1f)
    else frameRect.top + frameRect.height * value.coerceIn(0f, 1f)

private fun mapOverlayWidth(value: Float, surface: String, width: Float, frameRect: Rect): Float =
    if (normalizeOverlaySurface(surface) == "card" || frameRect == Rect.Zero) width * value.coerceIn(0f, 1f)
    else frameRect.width * value.coerceIn(0f, 1f)

private fun mapOverlayHeight(value: Float, surface: String, height: Float, frameRect: Rect): Float =
    if (normalizeOverlaySurface(surface) == "card" || frameRect == Rect.Zero) height * value.coerceIn(0f, 1f)
    else frameRect.height * value.coerceIn(0f, 1f)

private fun normalizePointForSurface(offset: Offset, surface: String, width: Float, height: Float, frameRect: Rect): PhotoPaintPoint {
    val safeWidth = width.coerceAtLeast(1f)
    val safeHeight = height.coerceAtLeast(1f)
    return if (normalizeOverlaySurface(surface) == "card" || frameRect == Rect.Zero) {
        PhotoPaintPoint(
            x = (offset.x / safeWidth).coerceIn(0f, 1f),
            y = (offset.y / safeHeight).coerceIn(0f, 1f)
        )
    } else {
        val safeFrameWidth = frameRect.width.coerceAtLeast(1f)
        val safeFrameHeight = frameRect.height.coerceAtLeast(1f)
        PhotoPaintPoint(
            x = ((offset.x - frameRect.left) / safeFrameWidth).coerceIn(0f, 1f),
            y = ((offset.y - frameRect.top) / safeFrameHeight).coerceIn(0f, 1f)
        )
    }
}

private fun denormalizePointForSurface(point: PhotoPaintPoint, surface: String, width: Float, height: Float, frameRect: Rect): Offset =
    Offset(
        x = mapOverlayX(point.x, surface, width, frameRect),
        y = mapOverlayY(point.y, surface, height, frameRect)
    )

private fun convertPaintPathsSurface(
    paths: List<PhotoPaintPath>,
    fromSurface: String,
    toSurface: String,
    width: Float,
    height: Float,
    frameRect: Rect
): List<PhotoPaintPath> {
    if (paths.isEmpty()) return emptyList()
    if (normalizeOverlaySurface(fromSurface) == normalizeOverlaySurface(toSurface)) return paths
    return paths.map { pathItem ->
        PhotoPaintPath(
            points = pathItem.points.map { point ->
                val absolutePoint = denormalizePointForSurface(point, fromSurface, width, height, frameRect)
                normalizePointForSurface(absolutePoint, toSurface, width, height, frameRect)
            }
        )
    }
}

@Composable
private fun PhotoMarkLayer(photo: PromptPhoto, frameRect: Rect, modifier: Modifier = Modifier) {
    if (photo.marks.isEmpty()) return
    Canvas(modifier = modifier) {
        photo.marks.sortedBy { it.layer }.forEach { mark ->
            val baseColor = parseUserColor(mark.color)
            val width = mapOverlayWidth((mark.radiusX.coerceIn(0.05f, 0.28f) * 2f), mark.surface, size.width, frameRect)
            val height = mapOverlayHeight((mark.radiusY.coerceIn(0.05f, 0.24f) * 2f), mark.surface, size.height, frameRect)
            val cx = mapOverlayX(mark.centerX.coerceIn(0.05f, 0.95f), mark.surface, size.width, frameRect)
            val cy = mapOverlayY(mark.centerY.coerceIn(0.05f, 0.95f), mark.surface, size.height, frameRect)
            rotate(mark.rotation, pivot = Offset(cx, cy)) {
                repeat(3) { index ->
                    val scale = when (index) {
                        0 -> 1f
                        1 -> 0.76f
                        else -> 0.61f
                    }
                    drawOval(
                        color = baseColor.copy(alpha = 0.18f + index * 0.06f),
                        topLeft = Offset(
                            x = cx - (width * scale) / 2f + when (index) { 1 -> width * 0.13f; 2 -> -width * 0.12f; else -> 0f },
                            y = cy - (height * scale) / 2f + when (index) { 1 -> -height * 0.07f; 2 -> height * 0.1f; else -> 0f }
                        ),
                        size = Size(width * scale, height * scale)
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoMarkLayer(photo: PromptPhoto, modifier: Modifier = Modifier) {
    PhotoMarkLayer(photo, Rect.Zero, modifier)
}

@Composable
private fun PhotoPaintLayer(photo: PromptPhoto, frameRect: Rect, modifier: Modifier = Modifier) {
    if (photo.paints.isEmpty()) return
    val parsedPaints = remember(photo.paints) { photo.paints.map { it to parsePhotoPaintPaths(it.pathsJson) } }
    Canvas(modifier = modifier) {
        parsedPaints.forEach { (paint, paths) ->
            val strokeColor = parseUserColor(paint.color).copy(alpha = 0.72f)
            val basis = if (normalizeOverlaySurface(paint.surface) == "card" || frameRect == Rect.Zero) size.minDimension else minOf(frameRect.width, frameRect.height)
            val strokeWidthPx = (basis * paint.strokeWidth.coerceIn(0.01f, 0.12f)).coerceAtLeast(6.5f)
            for (pathItem in paths) {
                if (pathItem.points.size < 2) continue
                val drawPath = ComposePath().apply {
                    val start = pathItem.points.first()
                    moveTo(
                        mapOverlayX(start.x, paint.surface, size.width, frameRect),
                        mapOverlayY(start.y, paint.surface, size.height, frameRect)
                    )
                    pathItem.points.drop(1).forEach { point ->
                        lineTo(
                            mapOverlayX(point.x, paint.surface, size.width, frameRect),
                            mapOverlayY(point.y, paint.surface, size.height, frameRect)
                        )
                    }
                }
                drawPath(path = drawPath, color = strokeColor, style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun PhotoPaintLayer(photo: PromptPhoto, modifier: Modifier = Modifier) {
    PhotoPaintLayer(photo, Rect.Zero, modifier)
}

@Composable
private fun PhotoPaintPathsLayer(
    paths: List<PhotoPaintPath>,
    color: Color,
    strokeWidth: Float,
    surface: String,
    frameRect: Rect,
    modifier: Modifier = Modifier
) {
    if (paths.isEmpty()) return
    Canvas(modifier = modifier) {
        val basis = if (normalizeOverlaySurface(surface) == "card" || frameRect == Rect.Zero) size.minDimension else minOf(frameRect.width, frameRect.height)
        val strokeWidthPx = (basis * strokeWidth.coerceIn(0.01f, 0.12f)).coerceAtLeast(6.5f)
        paths.forEach { pathItem ->
            if (pathItem.points.size < 2) return@forEach
            val drawPath = ComposePath().apply {
                val start = pathItem.points.first()
                moveTo(mapOverlayX(start.x, surface, size.width, frameRect), mapOverlayY(start.y, surface, size.height, frameRect))
                pathItem.points.drop(1).forEach { point ->
                    lineTo(mapOverlayX(point.x, surface, size.width, frameRect), mapOverlayY(point.y, surface, size.height, frameRect))
                }
            }
            drawPath(path = drawPath, color = color, style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PhotoPaintEditorDialog(
    target: PaintEditorTarget,
    viewerId: Long?,
    onDismiss: () -> Unit,
    onSave: (List<PhotoPaintPath>, Float) -> Unit,
    onDelete: (() -> Unit)?
) {
    val photo = target.item.photo
    val existingPaint = remember(photo.id, viewerId, photo.paints) {
        photo.paints.firstOrNull { it.userId == viewerId }
    }
    val initialPaths = remember(photo.id, viewerId, photo.paints) {
        existingPaint?.let { parsePhotoPaintPaths(it.pathsJson) } ?: emptyList()
    }
    var draftPaths by remember(photo.id, viewerId, photo.paints) { mutableStateOf(initialPaths) }
    var currentStroke by remember(photo.id, viewerId) { mutableStateOf<List<PhotoPaintPoint>>(emptyList()) }
    var overlayCanvasSize by remember(photo.id) { mutableStateOf(IntSize.Zero) }
    var editorFrameRect by remember(photo.id) { mutableStateOf(Rect.Zero) }
    var draftSurface by remember(photo.id, viewerId, photo.paints) {
        mutableStateOf(normalizeOverlaySurface(existingPaint?.surface))
    }
    val paintColor = parseUserColor(photo.paints.firstOrNull { it.userId == viewerId }?.color ?: target.item.user.favoriteColor)
    val strokeWidth = photo.paints.firstOrNull { it.userId == viewerId }?.strokeWidth ?: 0.035f
    val previewItem = remember(target.item, viewerId, photo.paints) {
        target.item.copy(photo = target.item.photo.copy(paints = target.item.photo.paints.filter { it.userId != viewerId }))
    }
    var viewportHeightPx by remember(photo.id) { mutableStateOf(0f) }
    var contentHeightPx by remember(photo.id) { mutableStateOf(0f) }
    var scrollOffsetPx by remember(photo.id) { mutableStateOf(0f) }
    fun composedPaths(): List<PhotoPaintPath> =
        if (currentStroke.size >= 2) (draftPaths + PhotoPaintPath(currentStroke)).takeLast(12) else draftPaths
    val initialPathsSignature = remember(initialPaths) { encodePhotoPaintPaths(initialPaths) }
    val maxScrollOffsetPx = remember(viewportHeightPx, contentHeightPx) {
        (contentHeightPx - viewportHeightPx).coerceAtLeast(0f)
    }
    fun dismissWithAutosave() {
        val pathsToPersist = composedPaths()
        if (encodePhotoPaintPaths(pathsToPersist) != initialPathsSignature) {
            onSave(pathsToPersist, strokeWidth)
        } else {
            onDismiss()
        }
    }
    LaunchedEffect(maxScrollOffsetPx) {
        scrollOffsetPx = scrollOffsetPx.coerceIn(0f, maxScrollOffsetPx)
    }
    LaunchedEffect(draftSurface, overlayCanvasSize, editorFrameRect) {
        if (
            draftSurface == "card" &&
            draftPaths.isNotEmpty() &&
            overlayCanvasSize != IntSize.Zero &&
            editorFrameRect != Rect.Zero
        ) {
            draftPaths = convertPaintPathsSurface(
                paths = draftPaths,
                fromSurface = "card",
                toSurface = "frame",
                width = overlayCanvasSize.width.toFloat(),
                height = overlayCanvasSize.height.toFloat(),
                frameRect = editorFrameRect
            )
            draftSurface = "frame"
        }
    }

    Dialog(onDismissRequest = ::dismissWithAutosave, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Post bemalen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Du malst direkt ueber dem echten Post. Der Strich bleibt beim Zeichnen live sichtbar und wird beim Schliessen automatisch gespeichert.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Der Post bleibt immer an seiner echten Feed-Position. Gescrollt wird nur ueber den Balken rechts, damit Zeichnen stabil bleibt.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .onSizeChanged { viewportHeightPx = it.height.toFloat() }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { clip = true }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(end = 18.dp)
                                        .offset { IntOffset(0, -scrollOffsetPx.toInt()) }
                                        .onSizeChanged { contentHeightPx = it.height.toFloat() }
                                ) {
                                    PostCanvasCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        item = previewItem,
                                        secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        primaryTextColor = MaterialTheme.colorScheme.onSurface,
                                        showPublicPostNumbers = true,
                                        preferSwipeForTwoImagePosts = false,
                                        showNsfwByDefault = true,
                                        nsfwRevealed = true,
                                        isMomentWindowPost = target.isMomentWindowPost,
                                        postMomentKind = target.postMomentKind,
                                        requestedByUser = target.requestedByUser,
                                        requestedByUserColor = target.requestedByUserColor,
                                        onOpenUserProfile = null,
                                        onOpenViewer = null,
                                        onOpenExternalUrl = null,
                                        onOpenHashtagSearch = {},
                                        onRevealNsfw = {},
                                        headerTrailing = {
                                            if (!photo.publicNumber.isNullOrBlank()) {
                                                Text(
                                                    "#${photo.publicNumber}",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        },
                                        overlay = { frameRect ->
                                            editorFrameRect = frameRect
                                            PhotoMarkLayer(previewItem.photo, frameRect, Modifier.fillMaxSize())
                                            PhotoPaintLayer(previewItem.photo, frameRect, Modifier.fillMaxSize())
                                            PhotoPaintPathsLayer(draftPaths, paintColor.copy(alpha = 0.58f), strokeWidth, "frame", frameRect, Modifier.fillMaxSize())
                                            if (currentStroke.size >= 2) {
                                                PhotoPaintPathsLayer(listOf(PhotoPaintPath(currentStroke)), paintColor.copy(alpha = 0.82f), strokeWidth, "frame", frameRect, Modifier.fillMaxSize())
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .onSizeChanged { overlayCanvasSize = it }
                                                    .pointerInteropFilter { event ->
                                                        val width = overlayCanvasSize.width.toFloat().coerceAtLeast(1f)
                                                        val height = overlayCanvasSize.height.toFloat().coerceAtLeast(1f)
                                                        val insideFrame = frameRect != Rect.Zero &&
                                                            event.x >= frameRect.left &&
                                                            event.x <= frameRect.right &&
                                                            event.y >= frameRect.top &&
                                                            event.y <= frameRect.bottom
                                                        when (event.actionMasked) {
                                                            MotionEvent.ACTION_DOWN -> {
                                                                if (!insideFrame) return@pointerInteropFilter false
                                                                currentStroke = listOf(
                                                                    normalizePointForSurface(Offset(event.x, event.y), "frame", width, height, frameRect)
                                                                )
                                                                true
                                                            }
                                                            MotionEvent.ACTION_MOVE -> {
                                                                if (currentStroke.isEmpty()) return@pointerInteropFilter false
                                                                val historySize = event.historySize
                                                                var updatedStroke = currentStroke
                                                                for (index in 0 until historySize) {
                                                                    val historicalPoint = Offset(event.getHistoricalX(index), event.getHistoricalY(index))
                                                                    if (
                                                                        historicalPoint.x < frameRect.left ||
                                                                        historicalPoint.x > frameRect.right ||
                                                                        historicalPoint.y < frameRect.top ||
                                                                        historicalPoint.y > frameRect.bottom
                                                                    ) {
                                                                        continue
                                                                    }
                                                                    val point = normalizePointForSurface(
                                                                        historicalPoint,
                                                                        "frame",
                                                                        width,
                                                                        height,
                                                                        frameRect
                                                                    )
                                                                    val last = updatedStroke.lastOrNull()
                                                                    if (last == null || abs(last.x - point.x) > 0.0015f || abs(last.y - point.y) > 0.0015f) {
                                                                        updatedStroke = updatedStroke + point
                                                                    }
                                                                }
                                                                if (insideFrame) {
                                                                    val currentPoint = normalizePointForSurface(Offset(event.x, event.y), "frame", width, height, frameRect)
                                                                    val currentLast = updatedStroke.lastOrNull()
                                                                    if (currentLast == null || abs(currentLast.x - currentPoint.x) > 0.0015f || abs(currentLast.y - currentPoint.y) > 0.0015f) {
                                                                        updatedStroke = updatedStroke + currentPoint
                                                                    }
                                                                }
                                                                currentStroke = updatedStroke
                                                                true
                                                            }
                                                            MotionEvent.ACTION_UP -> {
                                                                if (currentStroke.isEmpty()) return@pointerInteropFilter false
                                                                val finalizedStroke = if (insideFrame) {
                                                                    val finalPoint = normalizePointForSurface(Offset(event.x, event.y), "frame", width, height, frameRect)
                                                                    if (
                                                                        currentStroke.lastOrNull()?.let { abs(it.x - finalPoint.x) > 0.0015f || abs(it.y - finalPoint.y) > 0.0015f }
                                                                            ?: true
                                                                    ) {
                                                                        currentStroke + finalPoint
                                                                    } else {
                                                                        currentStroke
                                                                    }
                                                                } else {
                                                                    currentStroke
                                                                }
                                                                if (finalizedStroke.size >= 2) {
                                                                    draftPaths = (draftPaths + PhotoPaintPath(finalizedStroke)).takeLast(12)
                                                                }
                                                                currentStroke = emptyList()
                                                                true
                                                            }
                                                            MotionEvent.ACTION_CANCEL -> {
                                                                currentStroke = emptyList()
                                                                true
                                                            }
                                                            else -> false
                                                        }
                                                    }
                                            )
                                        }
                                    )
                                }
                            }
                            EditorScrollbar(
                                scrollOffsetPx = scrollOffsetPx,
                                viewportHeightPx = viewportHeightPx,
                                contentHeightPx = contentHeightPx,
                                onScrollOffsetChange = { scrollOffsetPx = it.coerceIn(0f, maxScrollOffsetPx) },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .navigationBarsPadding()
                            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (currentStroke.isNotEmpty()) currentStroke = emptyList()
                                    else if (draftPaths.isNotEmpty()) draftPaths = draftPaths.dropLast(1)
                                },
                                enabled = draftPaths.isNotEmpty() || currentStroke.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Undo") }
                            OutlinedButton(
                                onClick = { draftPaths = emptyList(); currentStroke = emptyList() },
                                modifier = Modifier.weight(1f)
                            ) { Text("Leeren") }
                            if (onDelete != null) {
                                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Loeschen") }
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = ::dismissWithAutosave, modifier = Modifier.weight(1f)) { Text("Schliessen") }
                            Button(
                                onClick = { onSave(composedPaths(), strokeWidth) },
                                enabled = draftPaths.isNotEmpty() || currentStroke.size >= 2,
                                modifier = Modifier.weight(1f)
                            ) { Text("Speichern") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorScrollbar(
    scrollOffsetPx: Float,
    viewportHeightPx: Float,
    contentHeightPx: Float,
    onScrollOffsetChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollRangePx = (contentHeightPx - viewportHeightPx).coerceAtLeast(0f)
    val viewportDp = with(LocalDensity.current) { viewportHeightPx.toDp() }
    Box(
        modifier = modifier.widthIn(min = 12.dp, max = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .align(Alignment.Center)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.28f), RoundedCornerShape(999.dp))
        )
        if (viewportHeightPx > 0f && contentHeightPx > 0f) {
            val visibleRatio = (viewportHeightPx / contentHeightPx).coerceIn(0f, 1f)
            val thumbHeightPx = maxOf(viewportHeightPx * visibleRatio, 36f)
            val thumbTravelPx = (viewportHeightPx - thumbHeightPx).coerceAtLeast(0f)
            val thumbOffsetPx = if (scrollRangePx > 0f && thumbTravelPx > 0f) {
                (scrollOffsetPx / scrollRangePx) * thumbTravelPx
            } else {
                0f
            }
            var thumbGrabOffsetPx by remember(scrollRangePx, viewportHeightPx, contentHeightPx) { mutableStateOf(0f) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(viewportDp)
                    .pointerInput(scrollRangePx, viewportHeightPx, contentHeightPx) {
                        detectTapGestures { offset ->
                            if (thumbTravelPx <= 0f || scrollRangePx <= 0f) return@detectTapGestures
                            val desiredThumbTop = (offset.y - thumbHeightPx / 2f).coerceIn(0f, thumbTravelPx)
                            onScrollOffsetChange((desiredThumbTop / thumbTravelPx) * scrollRangePx)
                        }
                    }
                    .pointerInput(scrollRangePx, viewportHeightPx, contentHeightPx, thumbOffsetPx) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                thumbGrabOffsetPx = (offset.y - thumbOffsetPx).coerceIn(0f, thumbHeightPx)
                            },
                            onDrag = { change, _ ->
                                if (thumbTravelPx > 0f && scrollRangePx > 0f) {
                                    val desiredThumbTop = (change.position.y - thumbGrabOffsetPx).coerceIn(0f, thumbTravelPx)
                                    onScrollOffsetChange((desiredThumbTop / thumbTravelPx) * scrollRangePx)
                                }
                                change.consume()
                            }
                        )
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(LocalDensity.current) { thumbHeightPx.toDp() })
                        .offset { IntOffset(0, thumbOffsetPx.toInt()) }
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.88f), RoundedCornerShape(999.dp))
                )
            }
        }
    }
}

private sealed class FeedRow {
    data class DayHeader(val day: String, val meta: PromptMeta?) : FeedRow()
    data class PhotoItem(val day: String, val item: FeedItem) : FeedRow()
    data class MonthRecapItem(val day: String, val recap: MonthlyRecap) : FeedRow()
    data class LoadingItem(val day: String) : FeedRow()
}

private val hashtagRegex = Regex("""(?<![\p{L}\p{N}_])#[\p{L}\p{N}_]+""")

@Composable
private fun HashtagText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onHashtagClick: (String) -> Unit
) {
    val annotated = remember(text, color) {
        buildAnnotatedString {
            var cursor = 0
            hashtagRegex.findAll(text).forEach { match ->
                if (match.range.first > cursor) {
                    append(text.substring(cursor, match.range.first))
                }
                val tag = match.value
                pushStringAnnotation(tag = "hashtag", annotation = tag)
                withStyle(SpanStyle(color = Color(0xFF1F5FBF), fontWeight = FontWeight.SemiBold)) {
                    append(tag)
                }
                pop()
                cursor = match.range.last + 1
            }
            if (cursor < text.length) {
                append(text.substring(cursor))
            }
        }
    }
    ClickableText(
        text = annotated,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        style = MaterialTheme.typography.bodyLarge.copy(color = color),
        onClick = { offset ->
            annotated.getStringAnnotations("hashtag", offset, offset)
                .firstOrNull()
                ?.let { onHashtagClick(it.item) }
        }
    )
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
    mode: CalendarMode,
    days: List<String>,
    dayStats: Map<String, DayStatItem>,
    photosByDay: Map<String, List<CalendarPhotoItem>>,
    bookmarkItems: List<FeedItem>,
    bookmarkFilter: BookmarkCalendarFilter,
    timeCapsuleItems: List<FeedItem>,
    timeCapsuleFilter: TimeCapsuleFilter,
    timeCapsuleLockedCount: Int,
    timeCapsuleReleasedCount: Int,
    searchQuery: String,
    searchResults: List<CalendarSearchMatchItem>,
    selected: String,
    pickerExpanded: Boolean,
    loading: Boolean,
    showPublicPostNumbers: Boolean,
    onPickerExpandedChange: (Boolean) -> Unit,
    onModeChange: (CalendarMode) -> Unit,
    onBookmarkFilterChange: (BookmarkCalendarFilter) -> Unit,
    onTimeCapsuleFilterChange: (TimeCapsuleFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onOpenHashtagSearch: (String) -> Unit,
    onSelect: (String) -> Unit,
    onOpenDayInFeed: (String) -> Unit,
    onOpenPhotoInFeed: (String, Long) -> Unit
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val openPrimaryFeedTarget: (String, Long?) -> Unit = remember(onOpenDayInFeed, onOpenPhotoInFeed) {
        { day, featuredPhotoId ->
            if (featuredPhotoId != null) {
                onOpenPhotoInFeed(day, featuredPhotoId)
            } else {
                onOpenDayInFeed(day)
            }
        }
    }
    var tagJumpExpanded by remember(mode, days) { mutableStateOf(false) }
    val selectedIndex = remember(days, selected) { days.indexOf(selected).coerceAtLeast(0) }
    val dayListStartIndex = remember(mode, searchQuery, searchResults, days, bookmarkItems) {
        var prefix = 1
        if (mode == CalendarMode.SEARCH && searchQuery.isBlank()) prefix += 1
        if (mode == CalendarMode.SEARCH && searchResults.isNotEmpty()) prefix += 1
        if (mode == CalendarMode.BOOKMARKS && bookmarkItems.isNotEmpty()) prefix += 1
        if (days.isEmpty()) prefix += 1
        prefix
    }
    val visibleRange = remember(listState) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: -1
            val last = visible.lastOrNull()?.index ?: -1
            first to last
        }
    }
    val showScrollToTop by remember { derivedStateOf { visibleRange.value.first > 2 } }
    val selectedDayVisible by remember(days, selectedIndex) {
        derivedStateOf {
            if (selectedIndex !in days.indices) return@derivedStateOf true
            val listIndex = dayListStartIndex + selectedIndex
            listIndex in visibleRange.value.first..visibleRange.value.second
        }
    }
    val showScrollToSelected by remember(selected, selectedIndex) {
        derivedStateOf { selected.isNotBlank() && selectedIndex in days.indices && !selectedDayVisible }
    }
    LaunchedEffect(selectedIndex, days.size) {
        if (days.isNotEmpty() && selectedIndex in days.indices) {
            listState.animateScrollToItem(dayListStartIndex + selectedIndex)
        }
    }
    val modeLabel = when (mode) {
        CalendarMode.PUBLIC -> "Oeffentlich"
        CalendarMode.BOOKMARKS -> "Gemerkt"
        CalendarMode.TIME_CAPSULES -> "Timecapsules"
        CalendarMode.SEARCH -> "Suche"
    }
    val subtitle = when (mode) {
        CalendarMode.PUBLIC -> "Alle sichtbaren Posts im Kalender"
        CalendarMode.BOOKMARKS -> when (bookmarkFilter) {
            BookmarkCalendarFilter.MINE -> "Direkte Liste nur mit deinen gemerkten Beitraegen"
            BookmarkCalendarFilter.ALL -> "Kuratierte Liste aus allen Beitragen, die irgendwer gemerkt hat"
        }
        CalendarMode.TIME_CAPSULES -> "Globaler Capsule-Feed mit offenen und gesperrten Timecapsules"
        CalendarMode.SEARCH -> if (searchQuery.isBlank()) "Suche nach Caption, Kommentaren und Hashtags" else "Treffer fuer \"$searchQuery\""
    }
    val filteredBookmarkItems = remember(bookmarkItems) { bookmarkItems }
    val filteredTimeCapsules = remember(timeCapsuleItems, timeCapsuleFilter) {
        timeCapsuleItems.filter { item ->
            when (timeCapsuleFilter) {
                TimeCapsuleFilter.ALL -> true
                TimeCapsuleFilter.RELEASED -> item.capsuleReleased
                TimeCapsuleFilter.LOCKED -> item.capsuleLocked
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
        item("calendar-header") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onPickerExpandedChange(!pickerExpanded) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Kalender: $modeLabel", fontWeight = FontWeight.Bold)
                            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (pickerExpanded) "Weniger" else "Auswaehlen", color = Color(0xFF1F5FBF))
                    }
                    AnimatedVisibility(
                        visible = pickerExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                items(CalendarMode.entries) { entry ->
                                    FilterChip(
                                        selected = mode == entry,
                                        onClick = { onModeChange(entry) },
                                        label = {
                                            Text(
                                                when (entry) {
                                                    CalendarMode.PUBLIC -> "Oeffentlich"
                                                    CalendarMode.BOOKMARKS -> "Gemerkt"
                                                    CalendarMode.TIME_CAPSULES -> "Timecapsules"
                                                    CalendarMode.SEARCH -> "Suche"
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                            if (mode == CalendarMode.SEARCH) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    label = { Text("Text oder Hashtag") },
                                    placeholder = { Text("#klogrind oder see") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(onClick = onSearchSubmit, modifier = Modifier.weight(1f)) {
                                        Text("Suchen")
                                    }
                                    OutlinedButton(
                                        onClick = { onOpenHashtagSearch("#daily") },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("#daily")
                                    }
                                }
                            } else if (mode == CalendarMode.BOOKMARKS) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    item {
                                        FilterChip(
                                            selected = bookmarkFilter == BookmarkCalendarFilter.MINE,
                                            onClick = { onBookmarkFilterChange(BookmarkCalendarFilter.MINE) },
                                            label = { Text("Von mir gemerkt") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = bookmarkFilter == BookmarkCalendarFilter.ALL,
                                            onClick = { onBookmarkFilterChange(BookmarkCalendarFilter.ALL) },
                                            label = { Text("Von allen gemerkt") }
                                        )
                                    }
                                }
                            } else if (mode == CalendarMode.TIME_CAPSULES) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    item {
                                        FilterChip(
                                            selected = timeCapsuleFilter == TimeCapsuleFilter.ALL,
                                            onClick = { onTimeCapsuleFilterChange(TimeCapsuleFilter.ALL) },
                                            label = { Text("Alle") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = timeCapsuleFilter == TimeCapsuleFilter.RELEASED,
                                            onClick = { onTimeCapsuleFilterChange(TimeCapsuleFilter.RELEASED) },
                                            label = { Text("Offen $timeCapsuleReleasedCount") }
                                        )
                                    }
                                    item {
                                        FilterChip(
                                            selected = timeCapsuleFilter == TimeCapsuleFilter.LOCKED,
                                            onClick = { onTimeCapsuleFilterChange(TimeCapsuleFilter.LOCKED) },
                                            label = { Text("Gesperrt $timeCapsuleLockedCount") }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (mode != CalendarMode.SEARCH && mode != CalendarMode.BOOKMARKS || (mode == CalendarMode.SEARCH && days.isNotEmpty())) {
                        OutlinedButton(
                            onClick = { tagJumpExpanded = !tagJumpExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (tagJumpExpanded) "Tag-Sprung ausblenden" else "Tag-Sprung")
                        }
                        AnimatedVisibility(
                            visible = tagJumpExpanded,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                items(days) { day ->
                                    FilterChip(
                                        selected = day == selected,
                                        onClick = { onSelect(day) },
                                        label = { Text(formatDayLabel(day)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (mode == CalendarMode.SEARCH && searchQuery.isBlank()) {
            item("calendar-search-empty") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Suche starten", fontWeight = FontWeight.Bold)
                        Text("Finde alte Posts ueber Caption, Kommentare oder klickbare Hashtags.")
                        Text("Beispiele: #klogrind, see, sunset")
                    }
                }
            }
        }
        if (mode == CalendarMode.SEARCH && searchResults.isNotEmpty()) {
            item("calendar-search-results") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Treffer", fontWeight = FontWeight.Bold)
                        searchResults.take(24).forEach { result ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelect(result.photo.day)
                                        onOpenPhotoInFeed(result.photo.day, result.photo.id)
                                    },
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = result.photo.url,
                                    contentDescription = "Trefferbild",
                                    modifier = Modifier.size(58.dp),
                                    contentScale = ContentScale.Crop
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "@${result.user.username} · ${formatDayLabel(result.photo.day)}",
                                        fontWeight = FontWeight.SemiBold,
                                        color = parseUserColor(result.user.favoriteColor)
                                    )
                                    if (showPublicPostNumbers && !result.photo.publicNumber.isNullOrBlank()) {
                                        Text(
                                            "#${result.photo.publicNumber}",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    if (result.excerpt.isNotBlank()) {
                                        HashtagText(
                                            text = result.excerpt,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            onHashtagClick = onOpenHashtagSearch
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (mode == CalendarMode.BOOKMARKS && filteredBookmarkItems.isNotEmpty()) {
            item("calendar-bookmark-feed") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (bookmarkFilter == BookmarkCalendarFilter.MINE) "Deine gemerkten Beitraege" else "Von allen gemerkte Beitraege",
                            fontWeight = FontWeight.Bold
                        )
                        filteredBookmarkItems.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenPhotoInFeed(item.photo.day, item.photo.id) },
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                AsyncImage(
                                    model = item.photo.url,
                                    contentDescription = "Gemerkter Beitrag",
                                    modifier = Modifier.size(84.dp),
                                    contentScale = ContentScale.Crop
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        "@${item.user.username} · ${formatDayLabel(item.photo.day)}",
                                        fontWeight = FontWeight.SemiBold,
                                        color = parseUserColor(item.user.favoriteColor)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "📌 ${item.photo.bookmarkCount}",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Text(
                                            "${item.reactions?.size ?: 0} Reaktionen · ${item.comments?.size ?: 0} Kommentare",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (showPublicPostNumbers && !item.photo.publicNumber.isNullOrBlank()) {
                                        Text(
                                            "#${item.photo.publicNumber}",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                    if (!item.photo.caption.isNullOrBlank()) {
                                        HashtagText(
                                            text = item.photo.caption,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            onHashtagClick = onOpenHashtagSearch
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (mode == CalendarMode.TIME_CAPSULES && filteredTimeCapsules.isNotEmpty()) {
            item("calendar-timecapsules-results") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Globaler Capsule-Feed", fontWeight = FontWeight.Bold)
                        filteredTimeCapsules.take(32).forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (item.capsuleLocked) Modifier
                                        else Modifier.clickable { onOpenPhotoInFeed(item.photo.day, item.photo.id) }
                                    ),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("@${item.user.username} · ${formatDayLabel(item.photo.day)}", fontWeight = FontWeight.SemiBold, color = parseUserColor(item.user.favoriteColor))
                                    Text(
                                        if (item.capsuleLocked) "Oeffnet am ${formatCapsuleOpenAt(item.photo.capsuleVisibleAt)}" else "Offen seit ${formatCapsuleOpenAt(item.photo.capsuleVisibleAt)}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (showPublicPostNumbers && !item.photo.publicNumber.isNullOrBlank()) {
                                        Text("#${item.photo.publicNumber}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                                    }
                                    Text("${item.reactions?.size ?: 0} Reaktionen · ${item.comments?.size ?: 0} Kommentare", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (days.isEmpty()) {
            item("calendar-empty-state") {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            loading -> "Kalender wird geladen ..."
                            mode == CalendarMode.SEARCH -> "Keine Treffer gefunden"
                            mode == CalendarMode.BOOKMARKS -> if (bookmarkFilter == BookmarkCalendarFilter.MINE) "Du hast noch keine Beitraege gemerkt" else "Noch keine global gemerkten Beitraege gefunden"
                            mode == CalendarMode.TIME_CAPSULES -> "Keine Timecapsules gefunden"
                            else -> "Keine Tage mit Bildern vorhanden"
                        }
                    )
                }
            }
        }
        if (mode != CalendarMode.BOOKMARKS)
        items(days) { day ->
            val selectedDay = day == selected
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
                    if (showPublicPostNumbers && !featured?.publicNumber.isNullOrBlank()) {
                        Text(
                            "Top-Post #${featured?.publicNumber}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    featured?.let {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (mode == CalendarMode.TIME_CAPSULES && it.capsuleLocked) {
                                Text(
                                    "Oeffnet am ${formatCapsuleOpenAt(it.capsuleVisibleAt)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (!it.secondUrl.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSelect(day)
                                            onOpenPhotoInFeed(day, it.photoId)
                                        },
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
                                        .height(120.dp)
                                        .clickable {
                                            onSelect(day)
                                            onOpenPhotoInFeed(day, it.photoId)
                                        },
                                    contentScale = ContentScale.Crop
                                )
                            }
                            Text(
                                "${it.reactionCount} Reaktionen · ${it.commentCount} Kommentare",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (mode == CalendarMode.BOOKMARKS && selectedDay) {
                        val bookmarkedPosts = photosByDay[day].orEmpty()
                        if (bookmarkedPosts.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Gemerkt an diesem Tag", fontWeight = FontWeight.SemiBold)
                            bookmarkedPosts.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSelect(day)
                                            onOpenPhotoInFeed(day, entry.photo.id)
                                        },
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = entry.photo.url,
                                        contentDescription = "Gemerkter Beitrag",
                                        modifier = Modifier.size(58.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            "@${entry.user.username}",
                                            fontWeight = FontWeight.SemiBold,
                                            color = parseUserColor(entry.user.favoriteColor)
                                        )
                                        if (showPublicPostNumbers && !entry.photo.publicNumber.isNullOrBlank()) {
                                            Text(
                                                "#${entry.photo.publicNumber}",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                        if (!entry.photo.caption.isNullOrBlank()) {
                                            HashtagText(
                                                text = entry.photo.caption,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                onHashtagClick = onOpenHashtagSearch
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (selectedDay) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Ausgewaehlt", color = Color(0xFF1F5FBF))
                            TextButton(onClick = { openPrimaryFeedTarget(day, featured?.photoId) }) {
                                Text("Im Feed oeffnen")
                            }
                        }
                    } else {
                        TextButton(
                            onClick = {
                                onSelect(day)
                                openPrimaryFeedTarget(day, featured?.photoId)
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Im Feed oeffnen")
                        }
                    }
                }
            }
        }
        }
        CalendarQuickActions(
            showTop = showScrollToTop,
            showSelected = showScrollToSelected,
            selectedLabel = "Zum Tag",
            onTopClick = {
                scope.launch { listState.animateScrollToItem(0) }
            },
            onSelectedClick = {
                if (selectedIndex in days.indices) {
                    scope.launch { listState.animateScrollToItem(dayListStartIndex + selectedIndex) }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 20.dp)
        )
    }
}

@Composable
private fun ScrollQuickActions(
    showTop: Boolean,
    showBottom: Boolean,
    showAnchor: Boolean,
    anchorLabel: String,
    onTopClick: () -> Unit,
    onBottomClick: () -> Unit,
    onAnchorClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.End
    ) {
        AnimatedVisibility(visible = showBottom, enter = fadeIn(), exit = fadeOut()) {
            SmallFloatingActionButton(
                onClick = onBottomClick,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "Zum Listenende")
            }
        }
        AnimatedVisibility(visible = showAnchor, enter = fadeIn(), exit = fadeOut()) {
            SmallFloatingActionButton(
                onClick = onAnchorClick,
                containerColor = Color(0xFF1F5FBF),
                contentColor = Color.White
            ) {
                Icon(
                    imageVector = Icons.Filled.Today,
                    contentDescription = anchorLabel
                )
            }
        }
        AnimatedVisibility(visible = showTop, enter = fadeIn(), exit = fadeOut()) {
            SmallFloatingActionButton(
                onClick = onTopClick,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = "Zum Listenanfang")
            }
        }
    }
}

@Composable
private fun CalendarQuickActions(
    showTop: Boolean,
    showSelected: Boolean,
    selectedLabel: String,
    onTopClick: () -> Unit,
    onSelectedClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.End
    ) {
        AnimatedVisibility(visible = showSelected, enter = fadeIn(), exit = fadeOut()) {
            SmallFloatingActionButton(
                onClick = onSelectedClick,
                containerColor = Color(0xFF1F5FBF),
                contentColor = Color.White
            ) {
                Icon(Icons.Filled.Today, contentDescription = selectedLabel)
            }
        }
        AnimatedVisibility(visible = showTop, enter = fadeIn(), exit = fadeOut()) {
            SmallFloatingActionButton(
                onClick = onTopClick,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Icon(Icons.Filled.VerticalAlignTop, contentDescription = "Zum Kalenderanfang")
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
    chatMessageMaxLength: Int,
    chatMessageUnlimited: Boolean,
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
    val hasDraft = input.isNotEmpty()
    val trimmedInput = input.trim()
    val trimmedLength = textCodePointLength(trimmedInput)
    val overLimit = !chatMessageUnlimited && trimmedLength > chatMessageMaxLength
    val counterText = if (chatMessageUnlimited) {
        "${textCodePointLength(input)} Zeichen · Unbegrenzt"
    } else {
        "$trimmedLength / $chatMessageMaxLength"
    }
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
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                label = { Text("Nachricht") },
                placeholder = { Text("Schreibe eine Nachricht...") },
                enabled = !sending,
                isError = overLimit,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 8,
                supportingText = {
                    Text(
                        if (overLimit) "Nachricht ist zu lang. $counterText" else counterText,
                        color = if (overLimit) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!hasDraft) {
                    OutlinedButton(
                        onClick = { showPollDialog = true },
                        enabled = !sending
                    ) {
                        Text("Umfrage")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Button(
                    onClick = onSend,
                    enabled = !sending && trimmedInput.isNotEmpty() && !overLimit
                ) {
                    Text(if (sending) "Sende..." else "Senden")
                }
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
    bookmarksGivenCount: Int,
    bookmarksReceivedCount: Int,
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
    bookmarkedPhotoPushEnabled: Boolean,
    postChangePushEnabled: Boolean,
    autoSubscribeInteractedPostsEnabled: Boolean,
    ownPostNumberInPushEnabled: Boolean,
    postNumberInPushEnabled: Boolean,
    yoloModeEnabled: Boolean,
    allowPhotoDownload: Boolean,
    allowCommunityNsfwMarking: Boolean,
    showNsfwByDefault: Boolean,
    creativePostMode: String,
    locationFeatureEnabled: Boolean,
    locationShareDefaultEnabled: Boolean,
    locationPermissionGranted: Boolean,
    feedPostPushEnabled: Boolean,
    showPublicPostNumbers: Boolean,
    preferSwipeForTwoImagePosts: Boolean,
    showConnectionHealthIndicator: Boolean,
    customNotificationToneEnabled: Boolean,
    customNotificationToneUri: String,
    debugMasterEnabled: Boolean,
    feedDebugEnabled: Boolean,
    diagnosticsUploadEnabled: Boolean,
    diagnosticsConsentGranted: Boolean,
    debugLogs: List<DebugLogEntry>,
    notificationDebugEnabled: Boolean,
    notificationDebugExpiresAt: String,
    notificationDebugEvents: List<NotificationDebugEvent>,
    notificationDebugLaunches: List<NotificationDebugLaunch>,
    notificationDebugPayloads: List<NotificationDebugPayload>,
    notificationDebugActiveItems: List<NotificationDebugActiveItem>,
    notificationDebugEnvironment: NotificationDebugEnvironment,
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
    onBookmarkedPhotoPushEnabledChange: (Boolean) -> Unit,
    onPostChangePushEnabledChange: (Boolean) -> Unit,
    onAutoSubscribeInteractedPostsEnabledChange: (Boolean) -> Unit,
    onNotificationPostNumbersEnabledChange: (Boolean) -> Unit,
    onOwnPostNumberInPushEnabledChange: (Boolean) -> Unit,
    onPostNumberInPushEnabledChange: (Boolean) -> Unit,
    onYoloModeEnabledChange: (Boolean) -> Unit,
    onAllowPhotoDownloadChange: (Boolean) -> Unit,
    onAllowCommunityNsfwMarkingChange: (Boolean) -> Unit,
    onShowNsfwByDefaultChange: (Boolean) -> Unit,
    onCreativePostModeChange: (String) -> Unit,
    onLocationFeatureEnabledChange: (Boolean) -> Unit,
    onLocationShareDefaultEnabledChange: (Boolean) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onOpenLocationPermissionSettings: () -> Unit,
    onNotificationMasterEnabledChange: (Boolean) -> Unit,
    onFeedPostPushEnabledChange: (Boolean) -> Unit,
    onShowConnectionHealthIndicatorChange: (Boolean) -> Unit,
    onCustomNotificationToneEnabledChange: (Boolean) -> Unit,
    onPickCustomNotificationTone: () -> Unit,
    onClearCustomNotificationTone: () -> Unit,
    onTestCustomNotificationTone: () -> Unit,
    onDebugMasterEnabledChange: (Boolean) -> Unit,
    onFeedDebugEnabledChange: (Boolean) -> Unit,
    onDiagnosticsUploadEnabledChange: (Boolean) -> Unit,
    onDiagnosticsConsentChange: (Boolean) -> Unit,
    onRefreshDebugLogs: () -> Unit,
    onShareDebugLogs: () -> Unit,
    onNotificationDebugEnabledChange: (Boolean) -> Unit,
    onExportNotificationDebug: () -> Unit,
    onNotificationDebugPushMatrix: () -> Unit,
    onNotificationDebugSnapshotAndReset: () -> Unit,
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
    onCheckConnection: () -> Unit,
    onAllowInsecureHttpOverrideChange: (Boolean) -> Unit,
    onApplyServerBaseUrlOverride: (String) -> Unit,
    onShowPublicPostNumbersChange: (Boolean) -> Unit,
    onPreferSwipeForTwoImagePostsChange: (Boolean) -> Unit,
    onClearAllBookmarks: () -> Unit,
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
    var showYoloEnableWarning by remember { mutableStateOf(false) }
    var showYoloDisableWarning by remember { mutableStateOf(false) }
    var showClearBookmarksConfirm by remember { mutableStateOf(false) }
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("📌 bekommen $bookmarksReceivedCount", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("📍 gemerkt $bookmarksGivenCount", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                                label = "Andere duerfen meine Posts als NSFW markieren",
                                checked = allowCommunityNsfwMarking,
                                onCheckedChange = onAllowCommunityNsfwMarkingChange,
                                supportingText = "Wenn aktiv, erscheint bei deinen Posts fuer andere im Menue die NSFW-Markierung."
                            )
                            SettingsToggleRow(
                                label = "NSFW standardmaessig anzeigen",
                                checked = showNsfwByDefault,
                                onCheckedChange = onShowNsfwByDefaultChange,
                                supportingText = "Wenn aus, bleiben NSFW-Posts zuerst geblurrt und koennen pro Beitrag sichtbar gemacht werden."
                            )
                            Text(
                                "Kreativspuren auf meinen Posts",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Du entscheidest, ob andere deine Post-Rahmen markieren oder spaeter bemalen duerfen.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = creativePostMode == "none",
                                    onClick = { onCreativePostModeChange("none") },
                                    label = { Text("Nichts") }
                                )
                                FilterChip(
                                    selected = creativePostMode == "mark",
                                    onClick = { onCreativePostModeChange("mark") },
                                    label = { Text("Markieren") }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = creativePostMode == "paint",
                                    onClick = { onCreativePostModeChange("paint") },
                                    label = { Text("Malen") }
                                )
                                FilterChip(
                                    selected = creativePostMode == "both",
                                    onClick = { onCreativePostModeChange("both") },
                                    label = { Text("Beides") }
                                )
                            }
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
                title = "YOLO-Modus",
                subtitle = "Accountweite Feature-Automatik fuer bestehende und kuenftige Releases",
                expanded = sectionExpanded("yolo_mode"),
                onExpandedChange = { onProfileSectionExpandedChange("yolo_mode", it) },
                headerBrush = Brush.horizontalGradient(
                    listOf(
                        Color(0xFF8A1C1C),
                        Color(0xFFB63A14),
                        Color(0xFFDA7A14)
                    )
                )
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E8))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Automatisch alles Neue aktivieren", fontWeight = FontWeight.Bold)
                        Text(
                            "Dieser Modus haengt an deinem Profil. Wenn er aktiv ist, werden bestehende und kuenftige registrierte Features automatisch fuer deinen Account eingeschaltet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SettingsToggleRow(
                            label = "YOLO-Modus",
                            checked = yoloModeEnabled,
                            onCheckedChange = { checked ->
                                if (checked != yoloModeEnabled) {
                                    if (checked) {
                                        showYoloEnableWarning = true
                                    } else {
                                        showYoloDisableWarning = true
                                    }
                                }
                            },
                            supportingText = "Warnung: Das kann Benachrichtigungen, Anzeigeoptionen, Freigaben und andere App-Funktionen ohne spaeteres manuelles Einschalten veraendern."
                        )
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
                SettingsSubsection("Gemerkt", "Zusaetzliche Hinweise zu fremden Posts, die du gemerkt hast") {
                    SettingsToggleRow(
                        label = "Push bei Aktivitaet auf gemerkten Posts",
                        checked = bookmarkedPhotoPushEnabled,
                        onCheckedChange = onBookmarkedPhotoPushEnabledChange,
                        supportingText = "Kommentare, Reaktionen und FotoMojis auf gemerkten fremden Posts."
                    )
                    SettingsToggleRow(
                        label = "Push bei Post-Aenderungen",
                        checked = postChangePushEnabled,
                        onCheckedChange = onPostChangePushEnabledChange,
                        supportingText = "Zusaetzliche Bilder und NSFW-Hinweise auf gemerkten Posts sowie NSFW-Markierungen auf deinen eigenen Posts."
                    )
                    SettingsToggleRow(
                        label = "Interaktions-Auto-Abo",
                        checked = autoSubscribeInteractedPostsEnabled,
                        onCheckedChange = onAutoSubscribeInteractedPostsEnabledChange,
                        supportingText = "Wenn du auf fremden Posts kommentierst, reagierst, FotoMojis nutzt, markierst, malst oder NSFW setzt, werden sie automatisch gemerkt und nach 48h ohne neue Aktivitaet wieder entfernt."
                    )
                }
                SettingsSubsection("Postnummern in Pushes", "Sichtbar an einer Stelle fuer eigene und gemerkte Beitraege") {
                    SettingsToggleRow(
                        label = "Postnummern in Push-Benachrichtigungen",
                        checked = ownPostNumberInPushEnabled || postNumberInPushEnabled,
                        onCheckedChange = onNotificationPostNumbersEnabledChange,
                        supportingText = "Zeigt bei Reaktionen, Kommentaren und FotoMojis die konkrete Post-ID wie #260526001."
                    )
                    if (ownPostNumberInPushEnabled || postNumberInPushEnabled) {
                        SettingsToggleRow(
                            label = "Bei meinen Beitraegen",
                            checked = ownPostNumberInPushEnabled,
                            onCheckedChange = onOwnPostNumberInPushEnabledChange,
                            supportingText = "Ergaenzt Interaktions-Pushes zu deinen eigenen Beitraegen um die Post-ID."
                        )
                        SettingsToggleRow(
                            label = "Bei gemerkten Beitraegen",
                            checked = postNumberInPushEnabled,
                            onCheckedChange = onPostNumberInPushEnabledChange,
                            supportingText = "Ergaenzt Pushes zu gemerkten fremden Beitraegen um die Post-ID."
                        )
                    }
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
                title = "Kalenderfunktionen",
                subtitle = "Bookmarks und Aufraeumen",
                expanded = sectionExpanded("calendar_functions"),
                onExpandedChange = { onProfileSectionExpandedChange("calendar_functions", it) }
            ) {
                Text("Wenn du viele gemerkte Beitraege gesammelt hast, kannst du sie hier gesammelt entfernen.")
                OutlinedButton(
                    onClick = { showClearBookmarksConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Alle gemerkten Posts nicht mehr merken")
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
                                        if (showPublicPostNumbers && !photo.publicNumber.isNullOrBlank()) {
                                            Text("#${photo.publicNumber}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Text("Zeit ${formatMomentTime(photo.createdAt)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        uploadTimeHint(photo)?.let { hint ->
                                            Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
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
                      label = "Post-Nummer anzeigen",
                      checked = showPublicPostNumbers,
                      onCheckedChange = onShowPublicPostNumbersChange,
                      supportingText = "Zeigt die stabile Tages-ID wie #260526001 an sichtbaren Beitraegen."
                  )
                  SettingsToggleRow(
                      label = "2-Bild-Posts als Wischansicht",
                      checked = preferSwipeForTwoImagePosts,
                      onCheckedChange = onPreferSwipeForTwoImagePostsChange,
                      supportingText = "Bei genau zwei Bildern bleibt sonst die alte geteilte Ansicht aktiv. Ab drei Bildern wird immer gewischt."
                  )
                  SettingsToggleRow(
                      label = "Verbindungsstatus im Kamera-Reiter anzeigen",
                      checked = showConnectionHealthIndicator,
                      onCheckedChange = onShowConnectionHealthIndicatorChange,
                      supportingText = "Zeigt einen kleinen Punkt fuer Netz- und Sync-Zustand neben dem Daily-Logo."
                  )
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
                                  Text("Chat-Laenge: ${if (promptRules.chatMessageUnlimited) "Unbegrenzt" else "${promptRules.chatMessageMaxLength} Zeichen"}")
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
                          title = "Diagnose-Freigabe",
                          subtitle = "Rechtliche Freigabe fuer technische Diagnosedaten."
                      ) {
                          SettingsToggleRow(
                              label = "Technische Diagnose-Freigabe",
                              checked = diagnosticsConsentGranted,
                              onCheckedChange = onDiagnosticsConsentChange,
                              supportingText = "Erlaubt die Uebermittlung technischer Fehler- und Diagnosedaten. Jederzeit widerrufbar."
                          )
                      }
                      SettingsSubsection(
                          title = "Debug-Steuerung",
                          subtitle = "Erweiterte Diagnose nur bei aktivem Debug-Modus."
                      ) {
                          SettingsToggleRow(
                              label = "Debug-Modus aktivieren",
                              checked = debugMasterEnabled,
                              onCheckedChange = onDebugMasterEnabledChange,
                              supportingText = if (debugMasterEnabled) {
                                  "Erweiterte Diagnose ist aktiv. Feed- und Notification-Spezialtools koennen jetzt separat zugeschaltet werden."
                              } else {
                                  "Standard fuer normale Nutzer. Keine tiefen Feed- oder Notification-Debugsammler."
                              }
                          )
                          SettingsToggleRow(
                              label = "Diagnosedaten automatisch hochladen",
                              checked = diagnosticsUploadEnabled,
                              onCheckedChange = onDiagnosticsUploadEnabledChange,
                              supportingText = if (diagnosticsConsentGranted) {
                                  "Sendet lokale Diagnose-Logs bei App-Start und bei neuen Fehlern automatisch an den Server."
                              } else {
                                  "Erfordert zuerst die technische Diagnose-Freigabe."
                              }
                          )
                          SettingsToggleRow(
                              label = "Feed-Debug aktivieren",
                              checked = feedDebugEnabled,
                              onCheckedChange = onFeedDebugEnabledChange,
                              supportingText = if (debugMasterEnabled) {
                                  "Protokolliert Anchor, Viewport, Refresh-Entscheidungen, Restore-Verhalten und Sprung-Erkennung fuer den Feed."
                              } else {
                                  "Erfordert zuerst den Debug-Modus."
                              }
                          )
                          SettingsToggleRow(
                              label = "Notification-Debug aktivieren",
                              checked = notificationDebugEnabled,
                              onCheckedChange = onNotificationDebugEnabledChange,
                              supportingText = if (notificationDebugEnabled) {
                                  "Aktiv bis ${notificationDebugExpiresAt.take(16).ifBlank { "-" }}. Erfasst Push-Pfade, Payloads, Launch-Intents und aktive Notifications lokal."
                              } else {
                                  "Erfordert den Debug-Modus und ist nur fuer Push-/Notification-Probleme gedacht."
                              }
                          )
                      }
                      SettingsSubsection(
                          title = "Exporte",
                          subtitle = "Lokale Diagnose pruefen und gezielt teilen."
                      ) {
                          Row(
                              modifier = Modifier.fillMaxWidth(),
                              horizontalArrangement = Arrangement.spacedBy(8.dp)
                          ) {
                              Button(onClick = onRefreshDebugLogs, modifier = Modifier.weight(1f)) { Text("Letzte Fehler") }
                              Button(onClick = onShareDebugLogs, modifier = Modifier.weight(1f)) { Text("Diagnose-Export") }
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
                      SettingsSubsection(
                          title = "Notification-Debug",
                          subtitle = "Vereinfachte Push-Testwerkzeuge fuer reproduzierbare Notification-Faelle."
                      ) {
                          if (notificationDebugEnabled) {
                              Card(modifier = Modifier.fillMaxWidth()) {
                                  Column(
                                      modifier = Modifier.padding(10.dp),
                                      verticalArrangement = Arrangement.spacedBy(8.dp)
                                  ) {
                                      Text("Notification-Debug aktiv", fontWeight = FontWeight.SemiBold)
                                      Text("Umgebung: enabled=${notificationDebugEnvironment.notificationsEnabled}, permission=${notificationDebugEnvironment.postPermissionGranted}, active=${notificationDebugEnvironment.activeCount}, device=${notificationDebugEnvironment.manufacturer} ${notificationDebugEnvironment.model}, sdk=${notificationDebugEnvironment.sdkInt}")
                                      Text(
                                          "Kanaele: ${if (notificationDebugEnvironment.channels.isEmpty()) "-" else notificationDebugEnvironment.channels.take(4).joinToString(" | ") { "${it.id}:${it.importance}" }}",
                                          color = MaterialTheme.colorScheme.onSurfaceVariant
                                      )
                                      Row(
                                          modifier = Modifier.fillMaxWidth(),
                                          horizontalArrangement = Arrangement.spacedBy(8.dp)
                                      ) {
                                          Button(onClick = onNotificationDebugPushMatrix, modifier = Modifier.weight(1f)) { Text("Push-Testmatrix") }
                                          Button(onClick = onExportNotificationDebug, modifier = Modifier.weight(1f)) { Text("Debug-Export") }
                                      }
                                      Button(
                                          onClick = onNotificationDebugSnapshotAndReset,
                                          modifier = Modifier.fillMaxWidth()
                                      ) { Text("Snapshot & Reset") }
                                  }
                              }
                              Card(modifier = Modifier.fillMaxWidth()) {
                                  Column(
                                      modifier = Modifier.padding(10.dp),
                                      verticalArrangement = Arrangement.spacedBy(6.dp)
                                  ) {
                                      Text("Aktive Notifications", fontWeight = FontWeight.SemiBold)
                                      if (notificationDebugActiveItems.isEmpty()) {
                                          Text("Keine aktiven Notifications sichtbar")
                                      } else {
                                          notificationDebugActiveItems.take(6).forEach { item ->
                                              Text("id=${item.id} channel=${item.channelId.ifBlank { "-" }} group=${item.groupKey.ifBlank { "-" }} summary=${item.isGroupSummary}", fontWeight = FontWeight.SemiBold)
                                              Text("${item.title.ifBlank { "-" }} | ${item.text.ifBlank { "-" }}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                          }
                                      }
                                  }
                              }
                              Card(modifier = Modifier.fillMaxWidth()) {
                                  Column(
                                      modifier = Modifier.padding(10.dp),
                                      verticalArrangement = Arrangement.spacedBy(6.dp)
                                  ) {
                                      Text("Push-Historie", fontWeight = FontWeight.SemiBold)
                                      if (notificationDebugEvents.isEmpty()) {
                                          Text("Noch keine Notification-Debug-Events")
                                      } else {
                                          notificationDebugEvents.take(6).forEach { row ->
                                              Text("[${row.createdAt.take(16)}] ${row.type}", fontWeight = FontWeight.SemiBold)
                                              Text(row.message, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                              if (row.meta.isNotBlank()) {
                                                  Text(row.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                              }
                                          }
                                      }
                                  }
                              }
                              Card(modifier = Modifier.fillMaxWidth()) {
                                  Column(
                                      modifier = Modifier.padding(10.dp),
                                      verticalArrangement = Arrangement.spacedBy(6.dp)
                                  ) {
                                      Text("Letzte Payloads / Launches", fontWeight = FontWeight.SemiBold)
                                      if (notificationDebugPayloads.isEmpty() && notificationDebugLaunches.isEmpty()) {
                                          Text("Noch keine Notification-Payloads oder Launch-Intents protokolliert")
                                      } else {
                                          notificationDebugPayloads.take(3).forEach { row ->
                                              Text("[${row.createdAt.take(16)}] payload ${row.source}/${row.type.ifBlank { "unknown" }}", fontWeight = FontWeight.SemiBold)
                                              Text("notification=${row.hasNotificationPayload} data=${row.hasDataPayload} action=${row.action.ifBlank { "-" }} keys=${row.dataKeys.ifBlank { "-" }}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                          }
                                          notificationDebugLaunches.take(3).forEach { row ->
                                              Text("[${row.createdAt.take(16)}] launch ${row.source}", fontWeight = FontWeight.SemiBold)
                                              Text("action=${row.action.ifBlank { "-" }} type=${row.type.ifBlank { "-" }} day=${row.day.ifBlank { "-" }} photoId=${row.photoId.ifBlank { "-" }}", maxLines = 2, overflow = TextOverflow.Ellipsis)
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

    if (showClearBookmarksConfirm) {
        AlertDialog(
            onDismissRequest = { showClearBookmarksConfirm = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearBookmarksConfirm = false
                        onClearAllBookmarks()
                    }
                ) { Text("Alles entfernen") }
            },
            dismissButton = {
                TextButton(onClick = { showClearBookmarksConfirm = false }) { Text("Abbrechen") }
            },
            title = { Text("Bookmarks aufraeumen?") },
            text = { Text("Alle gemerkten Posts werden aus deiner Gemerkt-Liste entfernt. Die Posts selbst bleiben natuerlich bestehen.") }
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
      if (showYoloEnableWarning) {
          AlertDialog(
            onDismissRequest = { showYoloEnableWarning = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showYoloEnableWarning = false
                        onYoloModeEnabledChange(true)
                    }
                ) { Text("Aktivieren") }
            },
            dismissButton = {
                TextButton(onClick = { showYoloEnableWarning = false }) { Text("Abbrechen") }
            },
            title = { Text("YOLO-Modus aktivieren?") },
            text = {
                Text("Warnung: Wenn du das aktivierst, werden bestehende und kuenftige Features automatisch eingeschaltet, auch wenn du sie spaeter nicht manuell in den Einstellungen suchst. Das kann Benachrichtigungen, Anzeigeoptionen, Freigaben und andere App-Verhalten direkt veraendern.")
            }
          )
      }
      if (showYoloDisableWarning) {
          AlertDialog(
            onDismissRequest = { showYoloDisableWarning = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        showYoloDisableWarning = false
                        onYoloModeEnabledChange(false)
                    }
                ) { Text("Deaktivieren") }
            },
            dismissButton = {
                TextButton(onClick = { showYoloDisableWarning = false }) { Text("Abbrechen") }
            },
            title = { Text("YOLO-Modus deaktivieren?") },
            text = {
                Text("Ab jetzt werden in spaeteren Updates keine neuen Features mehr automatisch aktiviert. Bereits durch YOLO eingeschaltete Features bleiben aktiv, bis du sie selbst wieder ausschaltest.")
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

private fun uploadTimeHint(photo: PromptPhoto): String? {
    if (!photo.timeShifted) return null
    val uploadedAt = photo.uploadedAt ?: return null
    return "Spaeter hochgeladen: ${formatMomentTime(uploadedAt)}"
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

private fun queueStatusLabel(item: QueuedUploadItem): String {
    if (item.lastFailureClass == "server_ack_timeout") {
        return "gleicht Server-Bestaetigung erneut ab"
    }
    return when (item.status) {
        UploadQueueStatus.WAITING -> "wartend"
        UploadQueueStatus.RUNNING -> "wird hochgeladen"
        UploadQueueStatus.WAITING_FOR_NETWORK -> "wartet auf Verbindung"
        UploadQueueStatus.WAITING_FOR_SECURE_NETWORK -> "wartet auf sichere Verbindung"
        UploadQueueStatus.AWAITING_SERVER_ACK -> "wartet auf Bestaetigung"
        UploadQueueStatus.FAILED_TRANSIENT -> "wird automatisch erneut versucht"
        UploadQueueStatus.FAILED_PERMANENT -> "Aktion erforderlich"
        UploadQueueStatus.SUCCESS -> "erfolgreich hochgeladen"
        UploadQueueStatus.PAUSED -> "pausiert"
        else -> item.status
    }
}

private fun visibleQueueItems(items: List<QueuedUploadItem>, nowMs: Long = System.currentTimeMillis()): List<QueuedUploadItem> {
    val successKeepMs = 90_000L
    val retryKeepMs = 12 * 60 * 60 * 1000L
    return items
        .asSequence()
        .filter { item ->
            when (item.status) {
                UploadQueueStatus.SUCCESS -> (nowMs - item.updatedAtMs) <= successKeepMs
                UploadQueueStatus.FAILED_TRANSIENT -> (nowMs - item.updatedAtMs) <= retryKeepMs
                else -> true
            }
        }
        .sortedByDescending { it.updatedAtMs }
        .take(6)
        .toList()
}

private fun queueManualRetryAllowed(status: String): Boolean {
    return status == UploadQueueStatus.FAILED_TRANSIENT ||
        status == UploadQueueStatus.FAILED_PERMANENT ||
        status == UploadQueueStatus.WAITING_FOR_SECURE_NETWORK ||
        status == UploadQueueStatus.PAUSED
}

private fun nextRetryLabel(item: QueuedUploadItem, nowMs: Long = System.currentTimeMillis()): String? {
    if (item.status == UploadQueueStatus.SUCCESS || item.nextRetryAtMs <= 0L) return null
    if (item.status == UploadQueueStatus.PAUSED || item.status == UploadQueueStatus.FAILED_PERMANENT) return null
    return if (item.nextRetryAtMs <= nowMs) {
        "Naechster Versuch: jetzt"
    } else {
        "Naechster Versuch: ${formatQueueTimestamp(item.nextRetryAtMs)}"
    }
}

private fun formatQueueTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return "-"
    return runCatching {
        val dt = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
        dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    }.getOrDefault("-")
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

private fun apiError(t: Throwable, fallback: String, httpErrorRawOverride: String? = null): String {
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
        val rawBody = httpErrorRawOverride ?: peekHttpErrorBody(t)
        val raw = rawBody.lowercase()
        val errorCode = parseApiErrorCode(rawBody)?.lowercase().orEmpty()
        return when (t.code()) {
            400 -> when {
                raw.contains("message too long") -> {
                    val maxLength = extractJsonIntField(rawBody, "maxLength")
                    if (maxLength != null) "Nachricht ist zu lang (max. $maxLength Zeichen)." else "Nachricht ist zu lang."
                }
                raw.contains("message empty") -> "Nachricht ist leer."
                else -> "Ungueltige Eingabe"
            }
            401 -> when {
                errorCode == "session_revoked" || raw.contains("session_revoked") -> "Sitzung wurde beendet. Bitte erneut einloggen."
                raw.contains("invalid_credentials") -> "Login fehlgeschlagen"
                else -> "Nicht autorisiert. Bitte erneut einloggen."
            }
            404 -> when {
                raw.contains("invite code not found") -> "Invite-Code nicht gefunden oder bereits benutzt."
                else -> fallback
            }
            403 -> when {
                errorCode == "prompt_inactive" || raw.contains("prompt inactive") -> "Heute ist gerade kein aktiver Daily-Moment."
                errorCode == "extra_window_blocked" || raw.contains("extra unavailable during daily moment window") -> "Waehrend des aktiven Daily-Moments sind Extras gesperrt."
                errorCode == "upload_window_closed" || raw.contains("upload window closed") -> "Upload-Zeitfenster ist geschlossen."
                errorCode == "daily_required" || raw.contains("feed_locked") || raw.contains("sichtbaren beitrag") || raw.contains("poste zuerst dein tagesmoment") -> "Poste zuerst dein Tagesmoment."
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
        is CertPathValidatorException -> "Dieses Netzwerk vertraut dem Daily-Zertifikat nicht oder veraendert die Verbindung."
        is SSLHandshakeException -> "Sichere Verbindung fehlgeschlagen. Bitte anderes Netz oder mobile Daten versuchen."
        is SSLException -> "Sichere Verbindung fehlgeschlagen."
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
    onOpenHashtagSearch: (String) -> Unit,
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
                    onOpenLocation = onOpenLocation,
                    onOpenHashtagSearch = onOpenHashtagSearch
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
                                        change.consume()
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
    onOpenLocation: (String) -> Unit,
    onOpenHashtagSearch: (String) -> Unit
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
                    HashtagText(
                        text = item.body,
                        color = MaterialTheme.colorScheme.onSurface,
                        onHashtagClick = onOpenHashtagSearch
                    )
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
