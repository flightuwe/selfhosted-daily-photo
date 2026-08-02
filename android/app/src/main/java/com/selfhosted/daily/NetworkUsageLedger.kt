package com.selfhosted.daily

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Calendar

data class NetworkUsageSummary(
    val rxBytesToday: Long = 0L,
    val txBytesToday: Long = 0L,
    val rxBytesSevenDays: Long = 0L,
    val txBytesSevenDays: Long = 0L,
    val cellularBytesSevenDays: Long = 0L,
    val wifiBytesSevenDays: Long = 0L,
    val cellularRxBytesToday: Long = 0L,
    val cellularTxBytesToday: Long = 0L,
    val wifiRxBytesToday: Long = 0L,
    val wifiTxBytesToday: Long = 0L,
    val cellularRxBytesSevenDays: Long = 0L,
    val cellularTxBytesSevenDays: Long = 0L,
    val wifiRxBytesSevenDays: Long = 0L,
    val wifiTxBytesSevenDays: Long = 0L,
    val cacheHitsSevenDays: Int = 0,
    val requestCountSevenDays: Int = 0,
    val retryCountSevenDays: Int = 0,
    val uidRxBytesSevenDays: Long = 0L,
    val uidTxBytesSevenDays: Long = 0L,
    val topEndpoints: List<Pair<String, Long>> = emptyList()
)

private data class NetworkUsageEntry(
    val atMs: Long,
    val endpoint: String,
    val context: String,
    val network: String,
    val txBytes: Long,
    val rxBytes: Long,
    val status: Int,
    val durationMs: Long,
    val cacheHit: Boolean,
    val retries: Int,
    val source: String
)

object NetworkUsageLedger {
    private const val PREF_NAME = "network_usage_ledger_v1"
    private const val KEY_UID_RX = "uid_rx"
    private const val KEY_UID_TX = "uid_tx"
    private const val KEY_UID_LABEL = "uid_label"
    private const val KEY_LAST_CLEANUP = "last_cleanup"
    private const val RETENTION_MS = 14L * 24L * 60L * 60L * 1000L

    @Volatile
    private var databaseHelper: NetworkUsageDbHelper? = null

    @Volatile
    private var appInForeground: Boolean = false

    fun setAppInForeground(context: Context, foreground: Boolean) {
        appInForeground = foreground
        recordUidBoundary(context, if (foreground) "app_foreground" else "app_background")
    }

    fun currentRequestContext(fallback: String = "app"): String =
        if (fallback == "worker") "worker" else if (appInForeground) "foreground" else "background"

    fun isAppInForeground(): Boolean = appInForeground

    fun isMetered(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return manager.isActiveNetworkMetered
    }

    fun recordUploadRetry(context: Context) {
        record(
            context,
            NetworkUsageEntry(
                atMs = System.currentTimeMillis(),
                endpoint = "upload",
                context = currentRequestContext("worker"),
                network = networkClass(context),
                txBytes = 0L,
                rxBytes = 0L,
                status = 0,
                durationMs = 0L,
                cacheHit = false,
                retries = 1,
                source = "retry"
            )
        )
    }

    fun interceptor(context: Context, requestContext: String = "app"): Interceptor = Interceptor { chain ->
        val startedAt = System.currentTimeMillis()
        val request = chain.request()
        val txBytes = runCatching { estimateRequestBytes(request) }.getOrDefault(0L)
        try {
            val response = chain.proceed(request)
            val fixedRxBytes = estimateResponseHeaderBytes(response)
            val retries = generateSequence(response.priorResponse) { it.priorResponse }.count()
            val cacheHit = response.cacheResponse != null || response.code == 304
            val body = response.body
            if (body == null) {
                record(
                    context,
                    NetworkUsageEntry(
                        atMs = startedAt,
                        endpoint = endpointClass(request.url.encodedPath, request.method),
                        context = currentRequestContext(requestContext),
                        network = networkClass(context),
                        txBytes = txBytes,
                        rxBytes = fixedRxBytes,
                        status = response.code,
                        durationMs = System.currentTimeMillis() - startedAt,
                        cacheHit = cacheHit,
                        retries = retries,
                        source = "http"
                    )
                )
                response
            } else {
                response.newBuilder().body(
                    CountingResponseBody(body) { consumed ->
                        record(
                            context,
                            NetworkUsageEntry(
                                atMs = startedAt,
                                endpoint = endpointClass(request.url.encodedPath, request.method),
                                context = currentRequestContext(requestContext),
                                network = networkClass(context),
                                txBytes = txBytes,
                                rxBytes = fixedRxBytes + consumed,
                                status = response.code,
                                durationMs = System.currentTimeMillis() - startedAt,
                                cacheHit = cacheHit,
                                retries = retries,
                                source = "http"
                            )
                        )
                    }
                ).build()
            }
        } catch (throwable: Throwable) {
            record(
                context,
                NetworkUsageEntry(
                    atMs = startedAt,
                    endpoint = endpointClass(request.url.encodedPath, request.method),
                    context = currentRequestContext(requestContext),
                    network = networkClass(context),
                    txBytes = txBytes,
                    rxBytes = 0L,
                    status = -1,
                    durationMs = System.currentTimeMillis() - startedAt,
                    cacheHit = false,
                    retries = 0,
                    source = "http"
                )
            )
            throw throwable
        }
    }

    @Synchronized
    fun recordUidBoundary(context: Context, label: String) {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0L || tx < 0L) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val previousRx = prefs.getLong(KEY_UID_RX, -1L)
        val previousTx = prefs.getLong(KEY_UID_TX, -1L)
        val previousLabel = prefs.getString(KEY_UID_LABEL, "start").orEmpty()
        if (previousRx >= 0L && previousTx >= 0L && rx >= previousRx && tx >= previousTx) {
            record(
                context,
                NetworkUsageEntry(
                    atMs = System.currentTimeMillis(),
                    endpoint = "uid:$previousLabel-$label",
                    context = currentRequestContext(),
                    network = networkClass(context),
                    txBytes = tx - previousTx,
                    rxBytes = rx - previousRx,
                    status = 0,
                    durationMs = 0L,
                    cacheHit = false,
                    retries = 0,
                    source = "uid"
                )
            )
        }
        prefs.edit().putLong(KEY_UID_RX, rx).putLong(KEY_UID_TX, tx).putString(KEY_UID_LABEL, label).apply()
    }

    @Synchronized
    fun summary(context: Context, nowMs: Long = System.currentTimeMillis()): NetworkUsageSummary {
        val all = read(context)
        val details = all.filter { it.source == "http" }
        val uidDetails = all.filter { it.source == "uid" }
        val retryDetails = all.filter { it.source == "retry" }
        val todayStart = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val sevenStart = nowMs - 7L * 24L * 60L * 60L * 1000L
        val today = details.filter { it.atMs >= todayStart }
        val seven = details.filter { it.atMs >= sevenStart }
        val top = seven.groupBy { it.endpoint }
            .mapValues { (_, entries) -> entries.sumOf { it.rxBytes + it.txBytes } }
            .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        return NetworkUsageSummary(
            rxBytesToday = today.sumOf { it.rxBytes },
            txBytesToday = today.sumOf { it.txBytes },
            rxBytesSevenDays = seven.sumOf { it.rxBytes },
            txBytesSevenDays = seven.sumOf { it.txBytes },
            cellularBytesSevenDays = seven.filter { it.network == "cellular" }.sumOf { it.rxBytes + it.txBytes },
            wifiBytesSevenDays = seven.filter { it.network == "wifi" }.sumOf { it.rxBytes + it.txBytes },
            cellularRxBytesToday = today.filter { it.network == "cellular" }.sumOf { it.rxBytes },
            cellularTxBytesToday = today.filter { it.network == "cellular" }.sumOf { it.txBytes },
            wifiRxBytesToday = today.filter { it.network == "wifi" }.sumOf { it.rxBytes },
            wifiTxBytesToday = today.filter { it.network == "wifi" }.sumOf { it.txBytes },
            cellularRxBytesSevenDays = seven.filter { it.network == "cellular" }.sumOf { it.rxBytes },
            cellularTxBytesSevenDays = seven.filter { it.network == "cellular" }.sumOf { it.txBytes },
            wifiRxBytesSevenDays = seven.filter { it.network == "wifi" }.sumOf { it.rxBytes },
            wifiTxBytesSevenDays = seven.filter { it.network == "wifi" }.sumOf { it.txBytes },
            cacheHitsSevenDays = seven.count { it.cacheHit },
            requestCountSevenDays = seven.size,
            retryCountSevenDays = seven.sumOf { it.retries } + retryDetails.filter { it.atMs >= sevenStart }.sumOf { it.retries },
            uidRxBytesSevenDays = uidDetails.filter { it.atMs >= sevenStart }.sumOf { it.rxBytes },
            uidTxBytesSevenDays = uidDetails.filter { it.atMs >= sevenStart }.sumOf { it.txBytes },
            topEndpoints = top
        )
    }

    fun diagnosticSummary(context: Context): String {
        val value = summary(context)
        return buildString {
            append("networkUsageTodayRx=").append(value.rxBytesToday)
            append(";networkUsageTodayTx=").append(value.txBytesToday)
            append(";networkUsage7dRx=").append(value.rxBytesSevenDays)
            append(";networkUsage7dTx=").append(value.txBytesSevenDays)
            append(";networkUsage7dWifi=").append(value.wifiBytesSevenDays)
            append(";networkUsage7dCellular=").append(value.cellularBytesSevenDays)
            append(";networkUsageTodayWifiRx=").append(value.wifiRxBytesToday)
            append(";networkUsageTodayWifiTx=").append(value.wifiTxBytesToday)
            append(";networkUsageTodayCellularRx=").append(value.cellularRxBytesToday)
            append(";networkUsageTodayCellularTx=").append(value.cellularTxBytesToday)
            append(";networkUsage7dWifiRx=").append(value.wifiRxBytesSevenDays)
            append(";networkUsage7dWifiTx=").append(value.wifiTxBytesSevenDays)
            append(";networkUsage7dCellularRx=").append(value.cellularRxBytesSevenDays)
            append(";networkUsage7dCellularTx=").append(value.cellularTxBytesSevenDays)
            append(";networkUsage7dRequests=").append(value.requestCountSevenDays)
            append(";networkUsage7dCacheHits=").append(value.cacheHitsSevenDays)
            append(";networkUsage7dRetries=").append(value.retryCountSevenDays)
            append(";networkUsageUid7dRx=").append(value.uidRxBytesSevenDays)
            append(";networkUsageUid7dTx=").append(value.uidTxBytesSevenDays)
            append(";networkUsageTop=").append(value.topEndpoints.joinToString(",") { "${it.first}:${it.second}" })
        }
    }

    @Synchronized
    private fun record(context: Context, entry: NetworkUsageEntry) {
        val db = database(context).writableDatabase
        db.insert("usage_entries", null, ContentValues().apply {
            put("at_ms", entry.atMs)
            put("endpoint", entry.endpoint)
            put("context_name", entry.context)
            put("network", entry.network)
            put("tx_bytes", entry.txBytes)
            put("rx_bytes", entry.rxBytes)
            put("status", entry.status)
            put("duration_ms", entry.durationMs)
            put("cache_hit", if (entry.cacheHit) 1 else 0)
            put("retries", entry.retries)
            put("source", entry.source)
        })
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (now - prefs.getLong(KEY_LAST_CLEANUP, 0L) >= 6L * 60L * 60L * 1000L) {
            compactExpiredDetails(db, now)
            prefs.edit().putLong(KEY_LAST_CLEANUP, now).apply()
        }
    }

    private fun read(context: Context): List<NetworkUsageEntry> {
        val cursor = database(context).readableDatabase.query(
            "usage_entries",
            arrayOf("at_ms", "endpoint", "context_name", "network", "tx_bytes", "rx_bytes", "status", "duration_ms", "cache_hit", "retries", "source"),
            "at_ms >= ?",
            arrayOf((System.currentTimeMillis() - RETENTION_MS).toString()),
            null,
            null,
            "at_ms ASC"
        )
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        NetworkUsageEntry(
                            atMs = it.getLong(0), endpoint = it.getString(1).orEmpty(), context = it.getString(2).orEmpty(),
                            network = it.getString(3).orEmpty(), txBytes = it.getLong(4), rxBytes = it.getLong(5),
                            status = it.getInt(6), durationMs = it.getLong(7), cacheHit = it.getInt(8) != 0,
                            retries = it.getInt(9), source = it.getString(10).orEmpty().ifBlank { "http" }
                        )
                    )
                }
            }
        }
    }

    private fun database(context: Context): NetworkUsageDbHelper {
        databaseHelper?.let { return it }
        return synchronized(this) {
            databaseHelper ?: NetworkUsageDbHelper(context.applicationContext).also { databaseHelper = it }
        }
    }

    private fun compactExpiredDetails(db: SQLiteDatabase, nowMs: Long) {
        val cutoff = Calendar.getInstance().apply {
            timeInMillis = nowMs - RETENTION_MS
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        db.rawQuery(
            """
            SELECT (at_ms / 86400000) * 86400000 AS day_start, network, source,
                   SUM(tx_bytes), SUM(rx_bytes), COUNT(*), SUM(cache_hit), SUM(retries)
            FROM usage_entries WHERE at_ms < ?
            GROUP BY day_start, network, source
            """.trimIndent(),
            arrayOf(cutoff.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                db.insertWithOnConflict("usage_daily_aggregates", null, ContentValues().apply {
                    put("day_start", cursor.getLong(0))
                    put("network", cursor.getString(1).orEmpty())
                    put("source", cursor.getString(2).orEmpty())
                    put("tx_bytes", cursor.getLong(3))
                    put("rx_bytes", cursor.getLong(4))
                    put("request_count", cursor.getLong(5))
                    put("cache_hits", cursor.getLong(6))
                    put("retries", cursor.getLong(7))
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
        db.delete("usage_entries", "at_ms < ?", arrayOf(cutoff.toString()))
    }

    private fun endpointClass(path: String, method: String): String = when {
        path.contains("/feed/window") -> "feed_window"
        path.contains("/hub/timeline") -> "hub_timeline"
        path.contains("/hub/bootstrap") -> "hub_bootstrap"
        path.contains("/uploads") && method.equals("GET", ignoreCase = true) -> "media_download"
        path.contains("/uploads") || path.contains("/attachments") -> "upload"
        path.contains("/calendar") -> "calendar"
        path.contains("/chat") -> "chat"
        path.contains("/photos") -> "photo_interaction"
        path.contains("/health") -> "health"
        else -> "other_api"
    }

    private fun networkClass(context: Context): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "unknown"
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "offline"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun estimateRequestBytes(request: okhttp3.Request): Long {
        val headers = request.headers.sumOf { it.first.length + it.second.length + 4 }.toLong()
        return headers + request.method.length + request.url.encodedPath.length + (request.body?.contentLength()?.coerceAtLeast(0L) ?: 0L)
    }

    private fun estimateResponseHeaderBytes(response: Response): Long =
        response.headers.sumOf { it.first.length + it.second.length + 4 }.toLong() + 16L
}

private class NetworkUsageDbHelper(context: Context) : SQLiteOpenHelper(context, "network_usage_ledger.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE usage_entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at_ms INTEGER NOT NULL,
                endpoint TEXT NOT NULL,
                context_name TEXT NOT NULL,
                network TEXT NOT NULL,
                tx_bytes INTEGER NOT NULL,
                rx_bytes INTEGER NOT NULL,
                status INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                cache_hit INTEGER NOT NULL,
                retries INTEGER NOT NULL,
                source TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_usage_entries_at_ms ON usage_entries(at_ms)")
        createAggregateTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createAggregateTable(db)
    }

    private fun createAggregateTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS usage_daily_aggregates (
                day_start INTEGER NOT NULL,
                network TEXT NOT NULL,
                source TEXT NOT NULL,
                tx_bytes INTEGER NOT NULL,
                rx_bytes INTEGER NOT NULL,
                request_count INTEGER NOT NULL,
                cache_hits INTEGER NOT NULL,
                retries INTEGER NOT NULL,
                PRIMARY KEY(day_start, network, source)
            )
            """.trimIndent()
        )
    }
}

private class CountingResponseBody(
    private val delegate: ResponseBody,
    private val onComplete: (Long) -> Unit
) : ResponseBody() {
    private val completed = AtomicBoolean(false)
    private var consumed = 0L
    private val countedSource: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0L) consumed += read
                if (read == -1L) finish()
                return read
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun source(): BufferedSource = countedSource
    override fun close() {
        try {
            super.close()
        } finally {
            finish()
        }
    }

    private fun finish() {
        if (completed.compareAndSet(false, true)) onComplete(consumed)
    }
}
