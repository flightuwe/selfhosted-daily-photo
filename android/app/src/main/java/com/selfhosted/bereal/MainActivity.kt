package com.selfhosted.bereal

import android.content.Context
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
data class PromptResponse(val day: String, val canUpload: Boolean)
data class FeedItem(val id: Long, val day: String, val promptOnly: Boolean, val caption: String?, val url: String, val user: User)
data class FeedResponse(val items: List<FeedItem>)

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

    fun token(): String = prefs.getString("token", "") ?: ""

    fun saveToken(token: String) {
        prefs.edit().putString("token", token).apply()
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

data class UiState(
    val token: String = "",
    val user: User? = null,
    val prompt: PromptResponse? = null,
    val feed: List<FeedItem> = emptyList(),
    val loading: Boolean = false,
    val message: String = ""
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
            state = state.copy(prompt = prompt, feed = feed, loading = false)
        }.onFailure {
            state = state.copy(loading = false, message = it.message ?: "Laden fehlgeschlagen")
        }
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

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var asPrompt by remember { mutableStateOf(true) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch { vm.upload(uri, asPrompt) }
        }
    }

    LaunchedEffect(state.token) {
        if (state.token.isNotBlank()) vm.refresh()
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
                Button(onClick = { launcher.launch("image/*") }) { Text("Bild waehlen") }
                Button(onClick = { scope.launch { vm.refresh() } }) { Text("Refresh") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = asPrompt, onCheckedChange = { asPrompt = it })
                Text("Als Prompt-Upload")
            }
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
