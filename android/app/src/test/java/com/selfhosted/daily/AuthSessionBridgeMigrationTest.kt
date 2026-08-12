package com.selfhosted.daily

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuthSessionBridgeMigrationTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear().commit()
        server = MockWebServer()
        server.start()
        setApiBaseUrlOverride(context, server.url("/api/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun successfulCoreBootstrapAddsDistributionIdentityToUpgradedSession() = runBlocking {
        context.getSharedPreferences("app", Context.MODE_PRIVATE)
            .edit()
            .putString("token", "legacy-access-token")
            .commit()
        assertEquals(0L, AuthSessionCoordinator.snapshot(context).userId)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
            """
            {
              "me":{"user":{"id":9,"username":"bridge-user","isAdmin":false}},
              "prompt":{"day":"2026-08-12","canUpload":false},
              "promptRules":{
                "promptWindowStartHour":8,
                "promptWindowEndHour":22,
                "uploadWindowMinutes":120,
                "maxUploadBytes":1048576,
                "timezone":"Europe/Berlin"
              },
              "specialMomentStatus":{
                "canRequest":false,
                "requestedThisWeek":false,
                "remainingSeconds":0
              }
            }
            """.trimIndent()
        ))

        AppRepo(context, OkHttpClient()).dashboardCore()

        assertEquals("/api/dashboard/core", server.takeRequest().path)
        assertEquals(9L, AuthSessionCoordinator.snapshot(context).userId)
    }
}
