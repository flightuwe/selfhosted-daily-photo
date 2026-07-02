package com.selfhosted.daily

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ConnectionHealthTest {

    @Test
    fun parseApiErrorCodeReadsJsonField() {
        val raw = """{"error":"upload window closed","errorCode":"upload_window_closed"}"""
        assertEquals("upload_window_closed", parseApiErrorCode(raw))
    }

    @Test
    fun evaluateConnectionHealthReturnsRedWhenOffline() {
        val snapshot = evaluateConnectionHealth(
            ConnectionHealthInputs(
                nowMs = 10_000L,
                startupDone = true,
                serverConnected = false,
                lastPingMs = null,
                lastApiSuccessAtMs = 0L,
                lastApiFailureAtMs = 0L,
                lastApiFailureMessage = "",
                networkSnapshot = "activeNetwork=false;capabilities=false;reason=no_active_network",
                refreshCircuitRemainingMs = 0L,
                lastRefreshFailureClass = "",
                uploadQueue = emptyList()
            )
        )
        assertEquals(ConnectionHealthLevel.RED, snapshot.level)
        assertTrue(snapshot.reasonLines.any { it.contains("Kein aktives Netzwerk", ignoreCase = true) })
    }

    @Test
    fun evaluateConnectionHealthReturnsGreenWhenValidatedAndHealthy() {
        val snapshot = evaluateConnectionHealth(
            ConnectionHealthInputs(
                nowMs = 30_000L,
                startupDone = true,
                serverConnected = true,
                lastPingMs = 120L,
                lastApiSuccessAtMs = 29_000L,
                lastApiFailureAtMs = 0L,
                lastApiFailureMessage = "",
                networkSnapshot = "activeNetwork=true;capabilities=true;internet=true;validated=true;metered=false;transport=wifi;downKbps=50000;upKbps=15000",
                refreshCircuitRemainingMs = 0L,
                lastRefreshFailureClass = "",
                uploadQueue = emptyList()
            )
        )
        assertEquals(ConnectionHealthLevel.GREEN, snapshot.level)
        assertTrue(snapshot.serverLine.contains("letzter erfolgreicher Kontakt", ignoreCase = true))
    }

    @Test
    fun evaluateConnectionHealthReturnsYellowForRetryingQueue() {
        val queue = listOf(
            queuedItem(status = UploadQueueStatus.FAILED_TRANSIENT, lastFailureClass = "timeout")
        )
        val snapshot = evaluateConnectionHealth(
            ConnectionHealthInputs(
                nowMs = 30_000L,
                startupDone = true,
                serverConnected = true,
                lastPingMs = 200L,
                lastApiSuccessAtMs = 29_000L,
                lastApiFailureAtMs = 0L,
                lastApiFailureMessage = "",
                networkSnapshot = "activeNetwork=true;capabilities=true;internet=true;validated=true;metered=false;transport=wifi;downKbps=50000;upKbps=15000",
                refreshCircuitRemainingMs = 0L,
                lastRefreshFailureClass = "",
                uploadQueue = queue
            )
        )
        assertEquals(ConnectionHealthLevel.YELLOW, snapshot.level)
        assertTrue(snapshot.uploadLine.contains("Retry", ignoreCase = true))
    }

    @Test
    fun evaluateConnectionHealthReturnsYellowForOpenRefreshCircuit() {
        val snapshot = evaluateConnectionHealth(
            ConnectionHealthInputs(
                nowMs = 30_000L,
                startupDone = true,
                serverConnected = true,
                lastPingMs = 200L,
                lastApiSuccessAtMs = 29_000L,
                lastApiFailureAtMs = 0L,
                lastApiFailureMessage = "",
                networkSnapshot = "activeNetwork=true;capabilities=true;internet=true;validated=true;metered=false;transport=wifi;downKbps=50000;upKbps=15000",
                refreshCircuitRemainingMs = 15_000L,
                lastRefreshFailureClass = "timeout",
                uploadQueue = emptyList()
            )
        )
        assertEquals(ConnectionHealthLevel.YELLOW, snapshot.level)
        assertTrue(snapshot.reasonLines.any { it.contains("Circuit", ignoreCase = true) })
    }

    @Test
    fun refreshLockCoordinatorSerializesConcurrentBlocks() = runBlocking {
        val coordinator = RefreshLockCoordinator()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)

        val jobs = (1..3).map { index ->
            async {
                coordinator.withLock {
                    val nowActive = active.incrementAndGet()
                    maxActive.set(maxOf(maxActive.get(), nowActive))
                    delay(20)
                    active.decrementAndGet()
                    index
                }
            }
        }

        assertEquals(listOf(1, 2, 3), jobs.awaitAll())
        assertEquals(1, maxActive.get())
    }

    @Test
    fun parseApiErrorCodeReturnsNullWithoutField() {
        assertTrue(parseApiErrorCode("""{"error":"forbidden"}""") == null)
    }

    private fun queuedItem(
        status: String,
        lastFailureClass: String = ""
    ): QueuedUploadItem = QueuedUploadItem(
        id = "q1",
        backPath = "",
        frontPath = "",
        uploadClientId = "up1",
        uploadMode = UploadQueueMode.DUAL,
        appendTargetPhotoId = null,
        isPrompt = true,
        capsuleMode = "",
        capsulePrivate = false,
        capsuleGroupRemind = false,
        locationShared = false,
        locationLatitude = null,
        locationLongitude = null,
        status = status,
        attempts = 1,
        lastError = "",
        transferProgressPercent = 0,
        serverAckState = UploadQueueServerAckState.NONE,
        nextRetryAtMs = 0L,
        capturedAtMs = 0L,
        createdAtMs = 0L,
        updatedAtMs = 0L,
        lastAttemptStartedAtMs = 0L,
        lastAttemptFinishedAtMs = 0L,
        lastFailureClass = lastFailureClass,
        lastHttpCode = null,
        retentionUntilMs = 0L,
        leaseExpiresAtMs = 0L
    )
}
