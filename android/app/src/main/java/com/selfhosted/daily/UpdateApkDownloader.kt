package com.selfhosted.daily

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class PendingUpdateApk(
    val temporaryFile: File,
    val sha256: String,
    val sizeBytes: Long,
    val finalUrl: String
)

class UpdateDownloadException(val errorClass: String, message: String) : IOException(message)

internal data class ApkDownloadTimeoutProfile(
    val connectTimeoutMillis: Long,
    val readTimeoutMillis: Long,
    val writeTimeoutMillis: Long,
    val callTimeoutMillis: Long
)

internal val ProductionApkDownloadTimeoutProfile = ApkDownloadTimeoutProfile(
    connectTimeoutMillis = 15_000,
    readTimeoutMillis = 60_000,
    writeTimeoutMillis = 30_000,
    callTimeoutMillis = 30L * 60L * 1_000L
)

internal fun buildApkDownloadClient(
    baseClient: OkHttpClient,
    timeoutProfile: ApkDownloadTimeoutProfile = ProductionApkDownloadTimeoutProfile
): OkHttpClient = baseClient.newBuilder()
    .connectTimeout(timeoutProfile.connectTimeoutMillis, TimeUnit.MILLISECONDS)
    .readTimeout(timeoutProfile.readTimeoutMillis, TimeUnit.MILLISECONDS)
    .writeTimeout(timeoutProfile.writeTimeoutMillis, TimeUnit.MILLISECONDS)
    .callTimeout(timeoutProfile.callTimeoutMillis, TimeUnit.MILLISECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

internal fun validateApkRedirect(current: HttpUrl, next: HttpUrl) {
    if (current.isHttps && !next.isHttps) {
        throw UpdateDownloadException("redirect_downgrade", "HTTPS-zu-HTTP-Weiterleitung wurde blockiert.")
    }
}

internal class UpdateApkDownloader(
    private val updatesDir: File,
    httpClient: OkHttpClient,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    timeoutProfile: ApkDownloadTimeoutProfile = ProductionApkDownloadTimeoutProfile,
    private val urlPolicy: DistributionUrlPolicy = DistributionUrlPolicy()
) {
    private val client = buildApkDownloadClient(httpClient, timeoutProfile)

    suspend fun download(update: UpdateInfo): PendingUpdateApk = withContext(Dispatchers.IO) {
        val announcedUrl = update.apkUrl?.trim().orEmpty()
        if (announcedUrl.isBlank()) throw UpdateDownloadException("missing_apk_url", "Keine APK-URL vorhanden.")
        if (update.apkSize != null && (update.apkSize <= 0 || update.apkSize > maxBytes)) {
            throw UpdateDownloadException("invalid_announced_size", "Die angekuendigte APK-Groesse ist ungueltig.")
        }
        val expectedHash = update.apkSha256?.trim()?.lowercase().orEmpty()
        if (expectedHash.isNotBlank() && !SHA256.matches(expectedHash)) {
            throw UpdateDownloadException("invalid_announced_hash", "Der angekuendigte APK-Hash ist ungueltig.")
        }
        if (expectedHash.isBlank() && !update.legacyOfficialArtifact) {
            throw UpdateDownloadException("missing_apk_hash", "Fuer diese APK fehlt ein SHA-256-Hash.")
        }

        updatesDir.mkdirs()
        if (!updatesDir.isDirectory) throw UpdateDownloadException("storage_unavailable", "Privater Update-Speicher ist nicht verfuegbar.")
        cleanupTemporaryFiles()
        val temporary = File(updatesDir, ".update-${UUID.randomUUID()}.part")
        try {
            val announcedOrigin = if (update.apkUrlExplicitlyConfigured) {
                urlPolicy.configured(announcedUrl)
            } else {
                urlPolicy.manifest(announcedUrl)
            }
            var current = announcedOrigin
            var redirects = 0
            while (true) {
                val request = Request.Builder()
                    .url(current)
                    .header("Accept", "application/vnd.android.package-archive, application/octet-stream")
                    .build()
                val response = client.newCall(request).execute()
                if (response.code in REDIRECT_CODES) {
                    val location = response.header("Location")
                    response.close()
                    val next = runCatching { urlPolicy.redirect(announcedOrigin, current, location, redirects) }
                        .getOrElse {
                            val errorClass = (it as? DistributionUrlException)?.errorClass ?: "invalid_redirect"
                            throw UpdateDownloadException(errorClass, "APK-Weiterleitung hat ein unzulaessiges Ziel.")
                        }
                    validateApkRedirect(current, next)
                    current = next
                    redirects += 1
                    continue
                }

                response.use { finalResponse ->
                    if (update.legacyOfficialArtifact && current.host != LEGACY_OFFICIAL_HOST) {
                        throw UpdateDownloadException("legacy_host_mismatch", "Die temporaere Legacy-Ausnahme gilt nur fuer den offiziellen APK-Host.")
                    }
                    if (!finalResponse.isSuccessful) {
                        throw UpdateDownloadException("http_status", "APK-Download fehlgeschlagen (HTTP ${finalResponse.code}).")
                    }
                    val body = finalResponse.body
                        ?: throw UpdateDownloadException("empty_response", "APK-Download enthielt keine Daten.")
                    val contentLength = body.contentLength()
                    if (contentLength > maxBytes) {
                        throw UpdateDownloadException("size_limit", "APK ueberschreitet das Downloadlimit.")
                    }
                    update.apkSize?.let { expected ->
                        if (contentLength >= 0 && contentLength != expected) {
                            throw UpdateDownloadException("size_mismatch", "APK-Groesse stimmt nicht mit dem Releaseeintrag ueberein.")
                        }
                    }

                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    temporary.outputStream().buffered().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maxBytes || (update.apkSize != null && total > update.apkSize)) {
                                    throw UpdateDownloadException("size_limit", "APK ueberschreitet die erlaubte Groesse.")
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (total == 0L) throw UpdateDownloadException("empty_response", "APK-Datei ist leer.")
                    if (update.apkSize != null && total != update.apkSize) {
                        throw UpdateDownloadException("size_mismatch", "APK-Groesse stimmt nicht mit dem Releaseeintrag ueberein.")
                    }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (expectedHash.isNotBlank() && actualHash != expectedHash) {
                        throw UpdateDownloadException("hash_mismatch", "SHA-256-Pruefung der APK ist fehlgeschlagen.")
                    }
                    return@withContext PendingUpdateApk(temporary, actualHash, total, current.toString())
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw UpdateDownloadException("download_state", "APK-Download wurde unerwartet beendet.")
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun finalizeVerified(pending: PendingUpdateApk, versionName: String): File {
        require(pending.temporaryFile.parentFile?.canonicalFile == updatesDir.canonicalFile) { "unexpected update path" }
        val safeVersion = versionName.trim().removePrefix("v")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("\\.{2,}"), "_")
            .trim('.')
            .ifBlank { "update" }
        val finalFile = File(updatesDir, "daily-v$safeVersion.apk")
        if (finalFile.exists() && !finalFile.delete()) {
            throw UpdateDownloadException("storage_finalize", "Vorherige Update-Datei konnte nicht ersetzt werden.")
        }
        try {
            Files.move(
                pending.temporaryFile.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pending.temporaryFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return finalFile
    }

    fun discard(pending: PendingUpdateApk) {
        pending.temporaryFile.delete()
    }

    private fun cleanupTemporaryFiles() {
        val staleBefore = System.currentTimeMillis() - TEMP_FILE_MAX_AGE_MS
        updatesDir.listFiles()?.filter {
            it.isFile && it.name.startsWith(".update-") && it.name.endsWith(".part") && it.lastModified() < staleBefore
        }
            ?.forEach(File::delete)
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 250L * 1024L * 1024L
        private const val LEGACY_OFFICIAL_HOST = "releases.daily.harzcloud.de"
        private const val TEMP_FILE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}
