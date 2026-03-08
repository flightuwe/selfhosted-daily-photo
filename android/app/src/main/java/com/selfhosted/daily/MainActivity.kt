package com.selfhosted.daily

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
import java.io.File
import java.io.FileOutputStream

enum class AppTab { CAMERA, FEED, CHAT, PROFILE }

data class User(val id: Long, val username: String, val isAdmin: Boolean)
data class AuthResponse(val token: String, val user: User)
data class LoginRequest(val username: String, val password: String)
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
    val ownPhoto: PromptPhoto? = null
)
data class FeedItem(
    val isLate: Boolean = false,
    val photo: PromptPhoto,
    val user: User
)
data class FeedResponse(val items: List<FeedItem>)
data class MyPhotoResponse(val items: List<PromptPhoto>)
data class ChatItem(val id: Long, val body: String, val createdAt: String, val user: User)
data class ChatResponse(val items: List<ChatItem>)
data class UpdateInfo(val latestVersion: String, val releaseUrl: String, val apkUrl: String?)
data class HealthResponse(val ok: Boolean, val version: String = "unknown", val provider: String = "unknown")

interface Api {
    @GET("health")
    suspend fun health(): HealthResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @GET("prompt/current")
    suspend fun prompt(@Header("Authorization") token: String): PromptResponse

    @GET("feed")
    suspend fun feed(@Header("Authorization") token: String): FeedResponse

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
}

class AppRepo(private val api: Api, private val context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val http = OkHttpClient()

    fun token(): String = prefs.getString("token", "") ?: ""

    fun saveToken(token: String) {
        prefs.edit().putString("token", token).apply()
    }

    fun clearToken() {
        prefs.edit().remove("token").apply()
    }

    fun isDarkMode(): Boolean = prefs.getBoolean("dark_mode", false)

    fun setDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean("dark_mode", enabled).apply()
    }

    private fun lastSyncedDeviceToken(): String = prefs.getString("last_synced_device_token", "") ?: ""

    private fun setLastSyncedDeviceToken(token: String) {
        prefs.edit().putString("last_synced_device_token", token).apply()
    }

    fun seenPromptMarker(): String = prefs.getString("seen_prompt_marker", "") ?: ""

    fun setSeenPromptMarker(marker: String) {
        prefs.edit().putString("seen_prompt_marker", marker).apply()
    }

    suspend fun login(username: String, password: String): User {
        val res = api.login(LoginRequest(username, password))
        saveToken(res.token)
        return res.user
    }

    suspend fun health(): HealthResponse = api.health()

    suspend fun prompt(): PromptResponse = api.prompt("Bearer ${token()}")

    suspend fun feedToday(): List<FeedItem> {
        return try {
            api.feed("Bearer ${token()}").items
        } catch (e: HttpException) {
            if (e.code() == 403) {
                emptyList()
            } else {
                throw e
            }
        }
    }

    suspend fun myPhotos(): List<PromptPhoto> = api.myPhotos("Bearer ${token()}").items

    suspend fun listChat(): List<ChatItem> = api.chat("Bearer ${token()}").items

    suspend fun sendChat(body: String) {
        api.sendChat("Bearer ${token()}", ChatMessageRequest(body))
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        api.changePassword("Bearer ${token()}", PasswordChangeRequest(currentPassword, newPassword))
    }

    suspend fun syncDeviceTokenIfNeeded() {
        if (token().isBlank()) return
        val pending = prefs.getString("pending_fcm_token", "") ?: ""
        val fromFirebase = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull().orEmpty()
        val deviceToken = if (pending.isNotBlank()) pending else fromFirebase
        if (deviceToken.isBlank()) return
        if (deviceToken == lastSyncedDeviceToken()) return

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

    suspend fun uploadDual(backUri: Uri, frontUri: Uri, isPrompt: Boolean) {
        val backFile = copyUriToTemp(backUri)
        val frontFile = copyUriToTemp(frontUri)
        val backPart = MultipartBody.Part.createFormData(
            "photo_back",
            backFile.name,
            backFile.asRequestBody("image/*".toMediaTypeOrNull())
        )
        val frontPart = MultipartBody.Part.createFormData(
            "photo_front",
            frontFile.name,
            frontFile.asRequestBody("image/*".toMediaTypeOrNull())
        )
        val kind = (if (isPrompt) "prompt" else "extra").toRequestBody("text/plain".toMediaTypeOrNull())
        api.uploadDual("Bearer ${token()}", backPart, frontPart, kind)
    }

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/flightuwe/selfhosted-daily-photo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()

        http.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val tag = json.optString("tag_name").removePrefix("v")
            val releaseUrl = json.optString("html_url")
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val item = assets.getJSONObject(i)
                    if (item.optString("name").endsWith(".apk")) {
                        apkUrl = item.optString("browser_download_url")
                        break
                    }
                }
            }

            if (isVersionNewer(tag, currentVersion)) UpdateInfo(tag, releaseUrl, apkUrl) else null
        }
    }

    private fun copyUriToTemp(uri: Uri): File {
        val resolver = context.contentResolver
        val filename = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        } ?: "upload.jpg"
        val target = File(context.cacheDir, filename)
        resolver.openInputStream(uri).use { input ->
            FileOutputStream(target).use { out ->
                input?.copyTo(out)
            }
        }
        return target
    }
}

private fun isVersionNewer(latest: String, current: String): Boolean {
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

data class UiState(
    val token: String = "",
    val user: User? = null,
    val prompt: PromptResponse? = null,
    val feed: List<FeedItem> = emptyList(),
    val photos: List<PromptPhoto> = emptyList(),
    val chat: List<ChatItem> = emptyList(),
    val loading: Boolean = false,
    val message: String = "",
    val activeTab: AppTab = AppTab.CAMERA,
    val startupDone: Boolean = false,
    val serverConnected: Boolean = false,
    val serverVersion: String = "unbekannt",
    val pushProvider: String = "unknown",
    val showPromptDialog: Boolean = false,
    val updateInfo: UpdateInfo? = null,
    val darkMode: Boolean = false
)

data class DashboardData(
    val prompt: PromptResponse,
    val feed: List<FeedItem>,
    val photos: List<PromptPhoto>,
    val chat: List<ChatItem>
)

class MainVm(private val repo: AppRepo) : ViewModel() {
    var state by mutableStateOf(UiState(token = repo.token(), darkMode = repo.isDarkMode()))
        private set

    suspend fun bootstrap() {
        if (state.startupDone) return
        state = state.copy(startupDone = false)
        val started = System.currentTimeMillis()
        val health = runCatching { repo.health() }.getOrNull()
        val elapsed = System.currentTimeMillis() - started
        if (elapsed < 900) {
            delay(900 - elapsed)
        }
        state = state.copy(
            startupDone = true,
            serverConnected = health?.ok == true,
            serverVersion = health?.version ?: "nicht erreichbar",
            pushProvider = health?.provider ?: "unknown",
            message = if (health?.ok == true) "" else "Server nicht erreichbar"
        )
    }

    suspend fun login(username: String, password: String) {
        state = state.copy(loading = true, message = "")
        try {
            val user = repo.login(username, password)
            state = state.copy(user = user, token = repo.token(), loading = false)
            runCatching { repo.syncDeviceTokenIfNeeded() }
            refreshAll()
        } catch (t: Throwable) {
            state = state.copy(loading = false, message = apiError(t, "Login fehlgeschlagen"))
        }
    }

    fun logout() {
        repo.clearToken()
        state = UiState(
            startupDone = true,
            serverConnected = state.serverConnected,
            serverVersion = state.serverVersion,
            pushProvider = state.pushProvider,
            darkMode = state.darkMode
        )
    }

    fun setTab(tab: AppTab) {
        state = state.copy(activeTab = tab)
    }

    suspend fun refreshAll() {
        if (repo.token().isBlank()) return
        state = state.copy(loading = true)
        runCatching {
            repo.syncDeviceTokenIfNeeded()
            val prompt = repo.prompt()
            val feed = if (prompt.hasPosted) repo.feedToday() else emptyList()
            val photos = repo.myPhotos()
            val chat = repo.listChat()
            DashboardData(prompt, feed, photos, chat)
        }.onSuccess { payload ->
            val prompt = payload.prompt
            val feed = payload.feed
            val photos = payload.photos
            val chat = payload.chat
            val marker = "${prompt.day}:${prompt.triggered ?: ""}"
            val shouldPopup = prompt.canUpload && !prompt.triggered.isNullOrBlank() && !prompt.hasPosted && marker != repo.seenPromptMarker()
            if (shouldPopup) repo.setSeenPromptMarker(marker)

            state = state.copy(
                prompt = prompt,
                feed = feed,
                photos = photos,
                chat = chat,
                loading = false,
                showPromptDialog = state.showPromptDialog || shouldPopup,
                message = ""
            )
        }.onFailure {
            state = state.copy(loading = false, message = apiError(it, "Laden fehlgeschlagen"))
        }
    }

    suspend fun uploadDual(back: Uri, front: Uri, asPrompt: Boolean): Boolean {
        state = state.copy(loading = true)
        return try {
            repo.uploadDual(back, front, asPrompt)
            state = state.copy(loading = false, message = "Fotos gepostet")
            refreshAll()
            true
        } catch (t: Throwable) {
            state = state.copy(loading = false, message = apiError(t, "Upload fehlgeschlagen"))
            false
        }
    }

    suspend fun sendChat(body: String) {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return
        runCatching { repo.sendChat(trimmed) }
            .onSuccess { refreshAll() }
            .onFailure { state = state.copy(message = apiError(it, "Chat senden fehlgeschlagen")) }
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

    fun dismissPromptDialog() {
        state = state.copy(showPromptDialog = false)
    }

    fun dismissUpdateDialog() {
        state = state.copy(updateInfo = null)
    }

    fun setDarkMode(enabled: Boolean) {
        repo.setDarkMode(enabled)
        state = state.copy(darkMode = enabled)
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
            MaterialTheme(colorScheme = if (useDark) darkColorScheme() else lightColorScheme()) {
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

    var captureUri by remember { mutableStateOf<Uri?>(null) }
    var captureTarget by remember { mutableStateOf<String?>(null) }
    var backPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var frontPreviewUri by remember { mutableStateOf<Uri?>(null) }

    var pwCurrent by remember { mutableStateOf("") }
    var pwNext by remember { mutableStateOf("") }
    var chatInput by remember { mutableStateOf("") }
    var viewerUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewerIndex by remember { mutableStateOf(0) }
    var requestFrontCapture by remember { mutableStateOf(false) }
    var cameraUploading by remember { mutableStateOf(false) }

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
                        val canPrompt = state.prompt?.canUpload == true && state.prompt.hasPosted.not()
                        scope.launch {
                            val ok = vm.uploadDual(back, front, canPrompt)
                            cameraUploading = false
                            if (ok) {
                                backPreviewUri = null
                                frontPreviewUri = null
                                vm.setTab(AppTab.FEED)
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

    if (state.showPromptDialog) {
        AlertDialog(
            onDismissRequest = { vm.dismissPromptDialog() },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissPromptDialog()
                    openCameraFor("back")
                }) { Text("Kamera oeffnen") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPromptDialog() }) { Text("Spaeter") }
            },
            title = { Text("Zeit fuer deinen taeglichen Moment") },
            text = { Text("Nimm jetzt Rueckkamera und Frontkamera auf.") }
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

    if (viewerUrls.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                viewerUrls = emptyList()
                viewerIndex = 0
            },
            confirmButton = {
                if (viewerUrls.size > 1 && viewerIndex < viewerUrls.lastIndex) {
                    TextButton(onClick = { viewerIndex += 1 }) { Text("Naechstes Bild") }
                } else {
                    TextButton(onClick = {
                        viewerUrls = emptyList()
                        viewerIndex = 0
                    }) { Text("Schliessen") }
                }
            },
            dismissButton = {
                if (viewerUrls.size > 1 && viewerIndex > 0) {
                    TextButton(onClick = { viewerIndex -= 1 }) { Text("Vorheriges Bild") }
                }
            },
            text = {
                AsyncImage(
                    model = viewerUrls[viewerIndex],
                    contentDescription = "Vollbild",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    contentScale = ContentScale.Fit
                )
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
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Passwort") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scope.launch { vm.login(username, password) } }, modifier = Modifier.fillMaxWidth()) { Text("Einloggen") }
            if (state.message.isNotBlank()) Text(state.message, color = Color.Red)
        }
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = state.activeTab == AppTab.CAMERA, onClick = { vm.setTab(AppTab.CAMERA) }, label = { Text("Kamera") }, icon = { Text("C") })
                NavigationBarItem(selected = state.activeTab == AppTab.FEED, onClick = { vm.setTab(AppTab.FEED) }, label = { Text("Feed") }, icon = { Text("F") })
                NavigationBarItem(selected = state.activeTab == AppTab.CHAT, onClick = { vm.setTab(AppTab.CHAT) }, label = { Text("Chat") }, icon = { Text("M") })
                NavigationBarItem(selected = state.activeTab == AppTab.PROFILE, onClick = { vm.setTab(AppTab.PROFILE) }, label = { Text("Profil") }, icon = { Text("P") })
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
                    backPreviewUri = backPreviewUri,
                    frontPreviewUri = frontPreviewUri,
                    onCaptureBack = { openCameraFor("back") },
                    onReset = {
                        backPreviewUri = null
                        frontPreviewUri = null
                        cameraUploading = false
                    },
                    onGoFeed = { vm.setTab(AppTab.FEED) },
                    uploading = cameraUploading,
                    onOpenViewer = { urls ->
                        viewerUrls = urls
                        viewerIndex = 0
                    }
                )

                AppTab.FEED -> FeedTab(
                    prompt = state.prompt,
                    items = state.feed,
                    onTakePhoto = { vm.setTab(AppTab.CAMERA) },
                    onOpenViewer = { urls ->
                        viewerUrls = urls
                        viewerIndex = 0
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
                    photos = state.photos,
                    darkMode = state.darkMode,
                    currentPassword = pwCurrent,
                    newPassword = pwNext,
                    onDarkModeChange = { vm.setDarkMode(it) },
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
                    onLogout = { vm.logout() },
                    onOpenViewer = { urls ->
                        viewerUrls = urls
                        viewerIndex = 0
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
    backPreviewUri: Uri?,
    frontPreviewUri: Uri?,
    onCaptureBack: () -> Unit,
    onReset: () -> Unit,
    onGoFeed: () -> Unit,
    uploading: Boolean,
    onOpenViewer: (List<String>) -> Unit
) {
    val hasPosted = prompt?.hasPosted == true
    val canUpload = prompt?.canUpload == true

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Heutiger Moment", style = MaterialTheme.typography.titleLarge)
        Text(prompt?.day ?: "-")

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
                                .clickable { onOpenViewer(ownUrls) },
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
            Button(onClick = onGoFeed, modifier = Modifier.fillMaxWidth()) { Text("Heutige Beitraege ansehen") }
        } else {
            Text("Heute sind zwei Fotos noetig: Rueckkamera und Frontkamera.")
            if (canUpload) {
                Text("Zeitfenster ist offen.")
            } else {
                Text("Du hast den heutigen Moment verpasst. Du kannst trotzdem posten.")
            }

            if (backPreviewUri == null) {
                Button(onClick = onCaptureBack, modifier = Modifier.fillMaxWidth()) { Text("Rueckkamera aufnehmen") }
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
                        Text("Upload laeuft ...")
                    } else {
                        Text("Upload wurde automatisch gestartet.")
                    }
                    Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Erneut aufnehmen") }
                }
            }
        }
    }
}

@Composable
fun FeedTab(prompt: PromptResponse?, items: List<FeedItem>, onTakePhoto: () -> Unit, onOpenViewer: (List<String>) -> Unit) {
    val hasPosted = prompt?.hasPosted == true

    if (!hasPosted) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Poste zuerst dein Foto, um die Beitraege der anderen zu sehen",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onTakePhoto) { Text("Foto aufnehmen") }
        }
        return
    }

    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Heute noch keine Beitraege")
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items) { item ->
            val urls = listOfNotNull(item.photo.url, item.photo.secondUrl)
            Card {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.user.username, fontWeight = FontWeight.SemiBold)
                    if (item.isLate) {
                        Text("Spaeter gepostet", color = Color(0xFF8B0000))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        urls.forEach { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = "${item.user.username} Foto",
                                modifier = Modifier
                                    .weight(1f)
                                    .height(180.dp)
                                    .clickable { onOpenViewer(urls) },
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
    }
}

@Composable
fun ChatTab(items: List<ChatItem>, input: String, onInput: (String) -> Unit, onSend: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Chat", style = MaterialTheme.typography.titleLarge)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items.size) { idx ->
                val item = items[idx]
                Card {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(item.user.username, fontWeight = FontWeight.SemiBold)
                        Text(item.body)
                        Text(item.createdAt, color = Color.Gray)
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
    photos: List<PromptPhoto>,
    darkMode: Boolean,
    currentPassword: String,
    newPassword: String,
    onDarkModeChange: (Boolean) -> Unit,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onChangePassword: () -> Unit,
    onCheckUpdate: () -> Unit,
    onLogout: () -> Unit,
    onOpenViewer: (List<String>) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text("@$username", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCheckUpdate) { Text("Update pruefen") }
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
                                    .clickable { onOpenViewer(urls) },
                                contentScale = ContentScale.Crop
                            )
                            if (photo.secondUrl != null) {
                                Text("2 Bilder", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(photo.day, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private fun apiError(t: Throwable, fallback: String): String {
    if (t is HttpException) {
        return when (t.code()) {
            400 -> "Ungueltige Eingabe"
            401 -> "Login fehlgeschlagen"
            403 -> "Aktion nicht erlaubt"
            409 -> "Du hast heute bereits gepostet"
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

