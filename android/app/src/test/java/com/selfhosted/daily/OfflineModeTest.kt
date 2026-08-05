package com.selfhosted.daily

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineModeTest {
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun reset() {
        OfflineModeManager.setEnabled(context, false)
    }

    @Test
    fun persistsOfflineStateAndRejectsNetworkWork() {
        OfflineModeManager.setEnabled(context, true)

        assertTrue(OfflineModeManager.isEnabled(context))
        val rejected = runCatching { OfflineModeManager.requireOnline(context) }.exceptionOrNull()
        assertTrue(rejected is OfflineModeException)

        OfflineModeManager.setEnabled(context, false)
        assertFalse(OfflineModeManager.isEnabled(context))
    }
}
