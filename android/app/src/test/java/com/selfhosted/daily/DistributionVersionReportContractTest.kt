package com.selfhosted.daily

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DistributionVersionReportContractTest {
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
    fun authenticatedDistributionRequestReportsExactInstalledVersion() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"schemaVersion":1,"enabled":false}""")
        )
        val api = buildApiService(server.url("/api/").toString(), OkHttpClient())

        api.appDistribution("Bearer session", "0.8.30", 142030)

        val request = server.takeRequest()
        assertEquals("Bearer session", request.getHeader("Authorization"))
        assertTrue(request.requestUrl?.encodedPath == "/api/app-distribution")
        assertEquals("0.8.30", request.requestUrl?.queryParameter("versionName"))
        assertEquals("142030", request.requestUrl?.queryParameter("versionCode"))
    }
}
