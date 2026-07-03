package com.selfhosted.daily

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
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
                uploadQueue = emptyList(),
                networkRecoveryActive = false,
                networkRecoveryReason = "",
                lastNetworkTransitionAtMs = 0L
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
                uploadQueue = emptyList(),
                networkRecoveryActive = false,
                networkRecoveryReason = "",
                lastNetworkTransitionAtMs = 0L
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
                uploadQueue = queue,
                networkRecoveryActive = false,
                networkRecoveryReason = "",
                lastNetworkTransitionAtMs = 0L
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
                uploadQueue = emptyList(),
                networkRecoveryActive = false,
                networkRecoveryReason = "",
                lastNetworkTransitionAtMs = 0L
            )
        )
        assertEquals(ConnectionHealthLevel.YELLOW, snapshot.level)
        assertTrue(snapshot.reasonLines.any { it.contains("Circuit", ignoreCase = true) })
    }

    @Test
    fun evaluateConnectionHealthReturnsYellowDuringRecoveryPhase() {
        val snapshot = evaluateConnectionHealth(
            ConnectionHealthInputs(
                nowMs = 30_000L,
                startupDone = true,
                serverConnected = true,
                lastPingMs = 180L,
                lastApiSuccessAtMs = 24_000L,
                lastApiFailureAtMs = 25_000L,
                lastApiFailureMessage = "Servername konnte nicht aufgeloest werden",
                networkSnapshot = "activeNetwork=true;capabilities=true;internet=true;validated=true;metered=true;transport=cellular;downKbps=24000;upKbps=7000",
                refreshCircuitRemainingMs = 0L,
                lastRefreshFailureClass = "dns",
                uploadQueue = emptyList(),
                networkRecoveryActive = true,
                networkRecoveryReason = "transport_changed",
                lastNetworkTransitionAtMs = 24_500L
            )
        )
        assertEquals(ConnectionHealthLevel.YELLOW, snapshot.level)
        assertTrue(snapshot.reasonLines.any { it.contains("Netzwerktyp", ignoreCase = true) })
    }

    @Test
    fun resilientDnsFallsBackToCachedAddressAfterUnknownHost() {
        val first = InetAddress.getByAddress("daily.broutschek.de", byteArrayOf(10, 20, 30, 40))
        val delegate = object : Dns {
            private var count = 0

            override fun lookup(hostname: String): List<InetAddress> {
                count += 1
                if (count == 1) {
                    return listOf(first)
                }
                throw UnknownHostException(hostname)
            }
        }
        val clock = AtomicInteger(0)
        val dns = ResilientDns(delegate = delegate, nowMs = { clock.get().toLong() })

        assertEquals(listOf(first), dns.lookup("daily.broutschek.de"))
        clock.set(3 * 60 * 1000)
        assertEquals(listOf(first), dns.lookup("daily.broutschek.de"))
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
