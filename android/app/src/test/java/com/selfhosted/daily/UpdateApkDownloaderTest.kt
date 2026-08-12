package com.selfhosted.daily

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.util.concurrent.TimeUnit

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
    fun productionDownloadClientOverridesBaseTimeoutsAndDisablesRedirects() {
        val base = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        val client = buildApkDownloadClient(base)

        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(60_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(30 * 60 * 1_000, client.callTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test
    fun delayedStreamingSurvivesShortBaseCallTimeout() = runBlocking {
        val bytes = "slow-signed-apk".toByteArray()
        repeat(2) {
            server.enqueue(
                MockResponse()
                    .setBody(okio.Buffer().write(bytes))
                    .throttleBody(1, 50, TimeUnit.MILLISECONDS)
            )
        }
        val dir = temporaryFolder.newFolder("slow-download")
        val base = OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS).build()

        val baseError = runCatching {
            base.newCall(Request.Builder().url(server.url("/base-timeout")).build())
                .execute()
                .use { response -> response.body!!.bytes() }
        }.exceptionOrNull()

        assertTrue(baseError is java.io.InterruptedIOException)

        val downloader = UpdateApkDownloader(
            dir,
            base,
            maxBytes = 1024,
            timeoutProfile = testTimeouts(readMillis = 500, callMillis = 3_000)
        )

        val pending = downloader.download(update(bytes))

        assertEquals(bytes.size.toLong(), pending.sizeBytes)
        assertEquals(sha256(bytes), pending.sha256)
        assertTrue(pending.temporaryFile.exists())
    }

    @Test
    fun readStallFailsAndDeletesPartialFile() = runBlocking {
        val bytes = "stalled-apk".toByteArray()
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(bytes))
                .setBodyDelay(500, TimeUnit.MILLISECONDS)
        )
        val dir = temporaryFolder.newFolder("read-timeout")
        val downloader = UpdateApkDownloader(
            dir,
            OkHttpClient(),
            maxBytes = 1024,
            timeoutProfile = testTimeouts(readMillis = 100, callMillis = 2_000)
        )

        val error = runCatching { downloader.download(update(bytes)) }.exceptionOrNull()

        assertTrue(error is java.io.IOException)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun hashMismatchDeletesIncompleteFile() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("xxxxxxxx", 1).throttleBody(1, 5, TimeUnit.MILLISECONDS))
        val dir = temporaryFolder.newFolder("hash-mismatch")
        val downloader = UpdateApkDownloader(dir, OkHttpClient(), maxBytes = 1024)

        val error = runCatching { downloader.download(update("expected".toByteArray())) }.exceptionOrNull()

        assertEquals("hash_mismatch", (error as UpdateDownloadException).errorClass)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun streamedSizeLimitDeletesIncompleteFileEvenWithoutContentLength() = runBlocking {
        server.enqueue(MockResponse().setChunkedBody("0123456789", 1).throttleBody(1, 5, TimeUnit.MILLISECONDS))
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
    fun httpsToHttpRedirectIsRejected() {
        val error = runCatching {
            validateApkRedirect(
                "https://downloads.example.org/app.apk".toHttpUrl(),
                "http://downloads.example.org/app.apk".toHttpUrl()
            )
        }.exceptionOrNull()

        assertEquals("redirect_downgrade", (error as UpdateDownloadException).errorClass)
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

    private fun testTimeouts(readMillis: Long, callMillis: Long) = ApkDownloadTimeoutProfile(
        connectTimeoutMillis = 500,
        readTimeoutMillis = readMillis,
        writeTimeoutMillis = 500,
        callTimeoutMillis = callMillis
    )
}
