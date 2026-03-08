package com.selfhosted.bereal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.File
import java.io.FileOutputStream

data class User(val id: Long, val username: String, val isAdmin: Boolean)
data class AuthResponse(val token: String, val user: User)
data class LoginRequest(val username: String, val password: String)
data class PromptResponse(val day: String, val canUpload: Boolean, val triggered: String? = null)
data class FeedItem(val id: Long, val day: String, val promptOnly: Boolean, val caption: String?, val url: String, val user: User)
data class FeedResponse(val items: List<FeedItem>)
data class UpdateInfo(val latestVersion: String, val releaseUrl: String, val apkUrl: String?)

interface Api {
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @GET("prompt/current")
    suspend fun prompt(@Header("Authorization") token: String): PromptResponse

    @GET("feed")
    suspend fun feed(@Header("Authorization") token: String): FeedResponse

    @Multipart
    @POST("uploads")
    suspend fun upload(
        @Header("Authorization") token: String,
        @Part photo: MultipartBody.Part,
        @Part("kind") kind: RequestBody
    )
}

class AppRepo(private val api: Api, private val context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val http = OkHttpClient()

    fun token(): String = prefs.getString("token", "") ?: ""

    fun saveToken(token: String) {
        prefs.edit().putString("token", token).apply()
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

    suspend fun prompt(): PromptResponse = api.prompt("Bearer ${token()}")
    suspend fun feed(): List<FeedItem> = api.feed("Bearer ${token()}").items

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
                    val name = item.optString("name")
                    if (name.endsWith(".apk")) {
                        apkUrl = item.optString("browser_download_url")
                        break
                    }
                }
            }

            if (isVersionNewer(tag, currentVersion)) {
                UpdateInfo(tag, releaseUrl, apkUrl)
            } else {
                null
            }
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
    fun parse(v: String): List<Int> = v.split(".")
        .map { it.trim() }
        .mapNotNull { it.toIntOrNull() }

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
    val loading: Boolean = false,
    val message: String = "",
    val showPromptDialog: Boolean = false,
    val updateInfo: UpdateInfo? = null
)

class MainVm(private val repo: AppRepo) : ViewModel() {
    var state by mutableStateOf(UiState(token = repo.token()))
        private set

    suspend fun login(username: String, password: String) {
        state = state.copy(loading = true, message = "")
        runCatching { repo.login(username, password) }
            .onSuccess { user ->
                state = state.copy(user = user, token = repo.token(), loading = false)
                refresh()
            }
            .onFailure { state = state.copy(loading = false, message = it.message ?: "Login fehlgeschlagen") }
    }

    suspend fun refresh() {
        if (repo.token().isBlank()) return
        state = state.copy(loading = true)
        runCatching {
            val prompt = repo.prompt()
            val feed = repo.feed()
            prompt to feed
        }.onSuccess { (prompt, feed) ->
            val marker = "${prompt.day}:${prompt.triggered ?: ""}"
            val shouldPopup = prompt.canUpload && !prompt.triggered.isNullOrBlank() && marker != repo.seenPromptMarker()
            if (shouldPopup) {
                repo.setSeenPromptMarker(marker)
            }
            state = state.copy(
                prompt = prompt,
                feed = feed,
                loading = false,
                showPromptDialog = state.showPromptDialog || shouldPopup
            )
        }.onFailure {
            state = state.copy(loading = false, message = it.message ?: "Laden fehlgeschlagen")
        }
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
            .onFailure { state = state.copy(loading = false, message = it.message ?: "Update-Pruefung fehlgeschlagen") }
    }

    suspend fun upload(uri: Uri, prompt: Boolean) {
        state = state.copy(loading = true)
        runCatching { repo.upload(uri, prompt) }
            .onSuccess {
                state = state.copy(loading = false, message = "Upload erfolgreich")
                refresh()
            }
            .onFailure { state = state.copy(loading = false, message = it.message ?: "Upload fehlgeschlagen") }
    }

    fun dismissPromptDialog() {
        state = state.copy(showPromptDialog = false)
    }

    fun dismissUpdateDialog() {
        state = state.copy(updateInfo = null)
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
            MaterialTheme {
                val vm: MainVm = viewModel(factory = MainVmFactory(AppRepo(api, this)))
                AppScreen(vm)
            }
        }
    }
}

@Composable
fun AppScreen(vm: MainVm) {
    val state = vm.state
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var asPrompt by remember { mutableStateOf(true) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch { vm.upload(uri, asPrompt) }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) {
            scope.launch { vm.upload(uri, true) }
        }
        pendingCaptureUri = null
    }

    LaunchedEffect(state.token) {
        if (state.token.isBlank()) return@LaunchedEffect
        while (true) {
            vm.refresh()
            delay(15_000)
        }
    }

    if (state.showPromptDialog) {
        AlertDialog(
            onDismissRequest = { vm.dismissPromptDialog() },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissPromptDialog()
                    val uri = createTempImageUri(context)
                    pendingCaptureUri = uri
                    cameraLauncher.launch(uri)
                }) { Text("Kamera öffnen") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPromptDialog() }) { Text("Später") }
            },
            title = { Text("Daily Event gestartet") },
            text = { Text("Jetzt Foto aufnehmen und als Prompt-Bild hochladen.") }
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
                }) { Text("Download öffnen") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissUpdateDialog() }) { Text("Später") }
            },
            title = { Text("Update verfügbar") },
            text = { Text("Neue Version ${update.latestVersion} ist verfügbar.") }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Selfhosted Daily Photo", style = MaterialTheme.typography.headlineSmall)

        if (state.token.isBlank()) {
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") })
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Passwort") })
            Button(onClick = { scope.launch { vm.login(username, password) } }) {
                Text("Login")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val uri = createTempImageUri(context)
                    pendingCaptureUri = uri
                    cameraLauncher.launch(uri)
                }) { Text("Kamera (Prompt)") }
                Button(onClick = { galleryLauncher.launch("image/*") }) { Text("Bild waehlen") }
                Button(onClick = { scope.launch { vm.refresh() } }) { Text("Refresh") }
                Button(onClick = { scope.launch { vm.checkForUpdate() } }) { Text("Update prüfen") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = asPrompt, onCheckedChange = { asPrompt = it })
                Text("Als Prompt-Upload (Galerie)")
            }
            Text("Version: ${BuildConfig.VERSION_NAME}")
            Text("Heute: ${state.prompt?.day ?: "-"} | Prompt Upload offen: ${state.prompt?.canUpload ?: false}")
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.feed) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("@${item.user.username} (${if (item.promptOnly) "prompt" else "extra"})")
                            Text(item.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.caption ?: "")
                        }
                    }
                }
            }
        }

        if (state.loading) Text("Lade...")
        if (state.message.isNotBlank()) Text(state.message)
    }
}

private fun createTempImageUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File.createTempFile("prompt_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
