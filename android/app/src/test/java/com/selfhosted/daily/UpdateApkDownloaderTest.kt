package com.selfhosted.daily

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class UpdateApkDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun validDownloadStreamsHashAndFinalizesPrivately() = runBlocking {
        val bytes = "signed-apk-placeholder".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes)))
        val dir = temporaryFolder.newFolder("updates")
        val downloader = UpdateApkDownloader(dir, OkHttpClient(), maxBytes = 1024)

        val pending = downloader.download(update(bytes))
        val final = downloader.finalizeVerified(pending, "0.8.29")

        assertEquals(bytes.size.toLong(), final.length())
        assertEquals("daily-v0.8.29.apk", final.name)
        assertFalse(pending.temporaryFile.exists())
    }

    @Test
    fun hashMismatchDeletesIncompleteFile() = runBlocking {
        server.enqueue(MockResponse().setBody("xxxxxxxx"))
        val dir = temporaryFolder.newFolder("hash-mismatch")
        val downloader = UpdateApkDownloader(dir, OkHttpClient(), maxBytes = 1024)

        val error = runCatching { downloader.download(update("expected".toByteArray())) }.exceptionOrNull()

        assertEquals("hash_mismatch", (error as UpdateDownloadException).errorClass)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun streamedSizeLimitDeletesIncompleteFileEvenWithoutContentLength() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("0123456789", 2))
        val dir = temporaryFolder.newFolder("too-large")
        val downloader = UpdateApkDownloader(dir, OkHttpClient(), maxBytes = 5)
        val update = baseUpdate(apkSize = null, apkSha256 = sha256("0123456789".toByteArray()))

        val error = runCatching { downloader.download(update) }.exceptionOrNull()

        assertEquals("size_limit", (error as UpdateDownloadException).errorClass)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun moreThanThreeRedirectsAreRejectedAndCleanedUp() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next$it")) }
        val dir = temporaryFolder.newFolder("redirects")
        val downloader = UpdateApkDownloader(dir, OkHttpClient(), maxBytes = 1024)

        val error = runCatching { downloader.download(baseUpdate(apkSize = null)) }.exceptionOrNull()

        assertEquals("redirect_limit", (error as UpdateDownloadException).errorClass)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun releaseVersionCannotEscapePrivateUpdateDirectory() {
        val dir = temporaryFolder.newFolder("path-traversal")
        val temporary = java.io.File(dir, ".update-test.part").apply { writeText("verified") }
        val pending = PendingUpdateApk(temporary, "ab".repeat(32), temporary.length(), server.url("/app.apk").toString())
        val downloader = UpdateApkDownloader(dir, OkHttpClient(), maxBytes = 1024)

        val final = downloader.finalizeVerified(pending, "../../outside\\evil")

        assertEquals(dir.canonicalFile, final.parentFile!!.canonicalFile)
        assertFalse(final.name.contains(".."))
        assertFalse(final.name.contains('/'))
        assertFalse(final.name.contains('\\'))
    }

    private fun update(bytes: ByteArray) = baseUpdate(apkSize = bytes.size.toLong(), apkSha256 = sha256(bytes))

    private fun baseUpdate(apkSize: Long?, apkSha256: String = "ab".repeat(32)) = UpdateInfo(
        latestVersion = "0.8.29",
        versionCode = 142029,
        releaseUrl = server.url("/release").toString(),
        apkUrl = server.url("/app.apk").toString(),
        apkSha256 = apkSha256,
        apkSize = apkSize,
        packageName = "com.selfhosted.daily",
        signingCertSha256 = "cd".repeat(32),
        profilePackageName = "com.selfhosted.daily",
        profileSigningCertSha256 = "cd".repeat(32)
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
