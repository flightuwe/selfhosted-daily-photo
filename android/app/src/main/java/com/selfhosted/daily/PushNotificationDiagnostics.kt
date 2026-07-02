package com.selfhosted.daily

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

data class NotificationDebugEvent(
    val id: String,
    val type: String,
    val message: String,
    val meta: String,
    val createdAt: String
)

data class NotificationDebugLaunch(
    val id: String,
    val source: String,
    val action: String,
    val type: String,
    val day: String,
    val photoId: String,
    val extras: String,
    val createdAt: String
)

data class NotificationDebugPayload(
    val id: String,
    val source: String,
    val type: String,
    val action: String,
    val day: String,
    val photoId: String,
    val title: String,
    val body: String,
    val hasNotificationPayload: Boolean,
    val hasDataPayload: Boolean,
    val dataKeys: String,
    val createdAt: String
)

data class NotificationDebugActiveItem(
    val id: Int,
    val tag: String,
    val channelId: String,
    val groupKey: String,
    val isGroupSummary: Boolean,
    val title: String,
    val text: String,
    val postTime: String
)

data class NotificationDebugChannelInfo(
    val id: String,
    val name: String,
    val importance: Int,
    val description: String,
    val sound: String
)

data class NotificationDebugEnvironment(
    val notificationsEnabled: Boolean,
    val postPermissionGranted: Boolean,
    val activeCount: Int,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val release: String,
    val channels: List<NotificationDebugChannelInfo>
)

data class NotificationDebugState(
    val enabled: Boolean,
    val expiresAt: String,
    val events: List<NotificationDebugEvent>,
    val launches: List<NotificationDebugLaunch>,
    val payloads: List<NotificationDebugPayload>,
    val activeItems: List<NotificationDebugActiveItem>,
    val environment: NotificationDebugEnvironment
)

object PushNotificationDiagnostics {
    private const val APP_PREFS = "app"
    private const val DEBUG_LOGS_PREF_KEY = "debug_logs_v1"
    private const val DEBUG_LOG_MAX_ENTRIES = 500
    private const val MODE_ENABLED_KEY = "push_debug_mode_enabled_v1"
    private const val MODE_EXPIRES_AT_KEY = "push_debug_mode_expires_at_v1"
    private const val EVENTS_KEY = "push_debug_events_v1"
    private const val LAUNCHES_KEY = "push_debug_launches_v1"
    private const val PAYLOADS_KEY = "push_debug_payloads_v1"
    private const val MAX_EVENTS = 160
    private const val MAX_LAUNCHES = 40
    private const val MAX_PAYLOADS = 40

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(MODE_ENABLED_KEY, false)
        if (!enabled) return false
        val expiresAt = prefs.getString(MODE_EXPIRES_AT_KEY, "").orEmpty().trim()
        if (expiresAt.isBlank()) return true
        val expired = runCatching { Instant.parse(expiresAt).isBefore(Instant.now()) }.getOrDefault(false)
        if (expired) {
            prefs.edit().putBoolean(MODE_ENABLED_KEY, false).apply()
            appendGeneralDebugLog(context, "push_debug_mode", "expired", "expiresAt=$expiresAt")
            return false
        }
        return true
    }

    fun setEnabled(context: Context, enabled: Boolean, durationHours: Long = 24L) {
        val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        if (!enabled) {
            prefs.edit()
                .putBoolean(MODE_ENABLED_KEY, false)
                .remove(MODE_EXPIRES_AT_KEY)
                .apply()
            appendGeneralDebugLog(context, "push_debug_mode", "disabled")
            return
        }
        val expiresAt = Instant.now().plus(durationHours, ChronoUnit.HOURS).toString()
        prefs.edit()
            .putBoolean(MODE_ENABLED_KEY, true)
            .putString(MODE_EXPIRES_AT_KEY, expiresAt)
            .apply()
        recordEvent(context, "push_debug_mode", "enabled", "expiresAt=$expiresAt")
        recordEnvironmentSnapshot(context, "mode_enabled")
    }

    fun expiresAt(context: Context): String =
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE).getString(MODE_EXPIRES_AT_KEY, "").orEmpty()

    fun readState(context: Context): NotificationDebugState {
        val enabled = isEnabled(context)
        return NotificationDebugState(
            enabled = enabled,
            expiresAt = expiresAt(context),
            events = readEvents(context).takeLast(60).reversed(),
            launches = readLaunches(context).takeLast(20).reversed(),
            payloads = readPayloads(context).takeLast(20).reversed(),
            activeItems = activeNotifications(context),
            environment = environment(context)
        )
    }

    fun clearStoredState(context: Context, keepMode: Boolean = true) {
        val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(EVENTS_KEY)
            .remove(LAUNCHES_KEY)
            .remove(PAYLOADS_KEY)
            .apply()
        if (!keepMode) {
            prefs.edit().remove(MODE_ENABLED_KEY).remove(MODE_EXPIRES_AT_KEY).apply()
        }
    }

    fun recordEvent(context: Context, type: String, message: String, meta: String = "") {
        if (!isEnabled(context)) return
        val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val current = readEventArray(prefs)
        current.put(
            JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("type", clean(type, 48))
                put("message", clean(message, 500))
                put("meta", clean(meta, 4000))
                put("createdAt", OffsetDateTime.now().toString())
            }
        )
        writeTrimmedArray(prefs, EVENTS_KEY, current, MAX_EVENTS)
        appendGeneralDebugLog(context, type, message, meta)
    }

    fun recordLaunchIntent(context: Context, source: String, intent: Intent?) {
        if (!isEnabled(context)) return
        val action = intent?.getStringExtra(EXTRA_LAUNCH_ACTION).orEmpty()
        val type = intent?.getStringExtra(EXTRA_LAUNCH_TYPE).orEmpty()
        val day = intent?.getStringExtra(EXTRA_LAUNCH_DAY).orEmpty()
        val photoId = if (intent?.hasExtra(EXTRA_LAUNCH_PHOTO_ID) == true) intent.getLongExtra(EXTRA_LAUNCH_PHOTO_ID, 0L).takeIf { it > 0L }?.toString().orEmpty() else ""
        val extras = intent?.extras?.keySet()?.sorted()?.joinToString(",").orEmpty()
        val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val current = readJsonArray(prefs, LAUNCHES_KEY)
        current.put(
            JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("source", clean(source, 32))
                put("action", clean(action, 120))
                put("type", clean(type, 120))
                put("day", clean(day, 32))
                put("photoId", clean(photoId, 32))
                put("extras", clean(extras, 500))
                put("createdAt", OffsetDateTime.now().toString())
            }
        )
        writeTrimmedArray(prefs, LAUNCHES_KEY, current, MAX_LAUNCHES)
        recordEvent(
            context,
            type = "push_launch_intent_captured",
            message = source,
            meta = "action=${action.ifBlank { "-" }};type=${type.ifBlank { "-" }};day=${day.ifBlank { "-" }};photoId=${photoId.ifBlank { "-" }};extras=${extras.ifBlank { "-" }}"
        )
        maybeRecordSystemNotificationSuspected(context, source, action, type)
    }

    fun recordPayload(
        context: Context,
        source: String,
        type: String,
        action: String,
        day: String,
        photoId: String,
        title: String,
        body: String,
        hasNotificationPayload: Boolean,
        hasDataPayload: Boolean,
        dataKeys: Collection<String>
    ) {
        if (!isEnabled(context)) return
        val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val current = readJsonArray(prefs, PAYLOADS_KEY)
        current.put(
            JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("source", clean(source, 32))
                put("type", clean(type, 120))
                put("action", clean(action, 120))
                put("day", clean(day, 32))
                put("photoId", clean(photoId, 32))
                put("title", clean(title, 160))
                put("body", clean(body, 240))
                put("hasNotificationPayload", hasNotificationPayload)
                put("hasDataPayload", hasDataPayload)
                put("dataKeys", clean(dataKeys.sorted().joinToString(","), 240))
                put("createdAt", OffsetDateTime.now().toString())
            }
        )
        writeTrimmedArray(prefs, PAYLOADS_KEY, current, MAX_PAYLOADS)
    }

    fun recordEnvironmentSnapshot(context: Context, reason: String) {
        if (!isEnabled(context)) return
        val env = environment(context)
        recordEvent(
            context,
            type = "push_environment_snapshot",
            message = reason,
            meta = "notificationsEnabled=${env.notificationsEnabled};postPermissionGranted=${env.postPermissionGranted};activeCount=${env.activeCount};manufacturer=${env.manufacturer};model=${env.model};sdk=${env.sdkInt};channels=${env.channels.joinToString("|") { "${it.id}:${it.importance}" }}"
        )
    }

    fun activeNotificationsCount(context: Context): Int = activeNotifications(context).size

    fun activeNotifications(context: Context): List<NotificationDebugActiveItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return emptyList()
        return manager.activeNotifications.map { sbn ->
            val extras = sbn.notification.extras
            NotificationDebugActiveItem(
                id = sbn.id,
                tag = sbn.tag ?: "",
                channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) sbn.notification.channelId.orEmpty() else "",
                groupKey = sbn.notification.group.orEmpty(),
                isGroupSummary = (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0,
                title = extras?.getCharSequence("android.title")?.toString().orEmpty(),
                text = extras?.getCharSequence("android.text")?.toString().orEmpty(),
                postTime = Instant.ofEpochMilli(sbn.postTime).toString()
            )
        }.sortedByDescending { it.postTime }
    }

    fun activeNotificationsSnapshot(context: Context): String {
        val items = activeNotifications(context)
        return "count=${items.size};items=${if (items.isEmpty()) "-" else items.joinToString(" || ") { "id=${it.id},tag=${it.tag.ifBlank { "-" }},channel=${it.channelId.ifBlank { "-" }},group=${it.groupKey.ifBlank { "-" }},summary=${it.isGroupSummary},title=${it.title.ifBlank { "-" }},text=${it.text.ifBlank { "-" }}" }}"
    }

    fun environment(context: Context): NotificationDebugEnvironment {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val channels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            manager.notificationChannels.map {
                NotificationDebugChannelInfo(
                    id = it.id,
                    name = it.name?.toString().orEmpty(),
                    importance = it.importance,
                    description = it.description.orEmpty(),
                    sound = it.sound?.toString().orEmpty()
                )
            }.sortedBy { it.id }
        } else {
            emptyList()
        }
        val permissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return NotificationDebugEnvironment(
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            postPermissionGranted = permissionGranted,
            activeCount = activeNotificationsCount(context),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE.orEmpty(),
            channels = channels
        )
    }

    fun exportBundle(context: Context): Uri {
        val state = readState(context)
        val exportDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(exportDir, "daily-notification-debug-${System.currentTimeMillis()}.txt")
        file.writeText(buildBundle(state), Charsets.UTF_8)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun buildBundle(state: NotificationDebugState): String = buildString {
        appendLine("Daily Notification Debug Bundle")
        appendLine("generatedAt=${OffsetDateTime.now()}")
        appendLine("enabled=${state.enabled}")
        appendLine("expiresAt=${state.expiresAt.ifBlank { "-" }}")
        appendLine()
        appendLine("[Environment]")
        appendLine("notificationsEnabled=${state.environment.notificationsEnabled}")
        appendLine("postPermissionGranted=${state.environment.postPermissionGranted}")
        appendLine("activeCount=${state.environment.activeCount}")
        appendLine("manufacturer=${state.environment.manufacturer}")
        appendLine("model=${state.environment.model}")
        appendLine("sdkInt=${state.environment.sdkInt}")
        appendLine("release=${state.environment.release}")
        appendLine("channels=${if (state.environment.channels.isEmpty()) "-" else state.environment.channels.joinToString(" | ") { "${it.id}:${it.importance}:${it.name}" }}")
        appendLine()
        appendLine("[Active Notifications]")
        if (state.activeItems.isEmpty()) appendLine("-")
        state.activeItems.forEach { appendLine("${it.postTime} | id=${it.id} | channel=${it.channelId.ifBlank { "-" }} | group=${it.groupKey.ifBlank { "-" }} | summary=${it.isGroupSummary} | ${it.title} | ${it.text}") }
        appendLine()
        appendLine("[Events]")
        if (state.events.isEmpty()) appendLine("-")
        state.events.forEach { appendLine("${it.createdAt} | ${it.type} | ${it.message} | ${it.meta}") }
        appendLine()
        appendLine("[Launches]")
        if (state.launches.isEmpty()) appendLine("-")
        state.launches.forEach { appendLine("${it.createdAt} | ${it.source} | action=${it.action.ifBlank { "-" }} | type=${it.type.ifBlank { "-" }} | day=${it.day.ifBlank { "-" }} | photoId=${it.photoId.ifBlank { "-" }} | extras=${it.extras.ifBlank { "-" }}") }
        appendLine()
        appendLine("[Payloads]")
        if (state.payloads.isEmpty()) appendLine("-")
        state.payloads.forEach { appendLine("${it.createdAt} | ${it.source} | type=${it.type.ifBlank { "-" }} | action=${it.action.ifBlank { "-" }} | hasNotification=${it.hasNotificationPayload} | hasData=${it.hasDataPayload} | dataKeys=${it.dataKeys.ifBlank { "-" }} | title=${it.title.ifBlank { "-" }} | body=${it.body.ifBlank { "-" }}") }
    }

    private fun maybeRecordSystemNotificationSuspected(context: Context, source: String, action: String, type: String) {
        if (action.isBlank() && type.isBlank()) return
        val now = Instant.now()
        val recentEvents = readEvents(context).takeLast(12)
        val recentServiceHit = recentEvents.any {
            (it.type == "push_message_received" || it.type == "push_notification_posted") &&
                runCatching { Instant.parse(it.createdAt).isAfter(now.minusSeconds(20)) }.getOrDefault(false)
        }
        if (!recentServiceHit) {
            recordEvent(
                context,
                type = "push_notification_system_suspect",
                message = source,
                meta = "action=${action.ifBlank { "-" }};type=${type.ifBlank { "-" }};reason=no_recent_service_event"
            )
        }
    }

    private fun appendGeneralDebugLog(context: Context, type: String, message: String, meta: String = "") {
        val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val current = readDebugLogsInternal(prefs)
        current.add(
            DebugLogEntry(
                id = UUID.randomUUID().toString(),
                type = clean(type, 32),
                message = clean(message, 500),
                meta = clean(meta, 4000),
                createdAt = OffsetDateTime.now().toString()
            )
        )
        writeDebugLogsInternal(prefs, current)
    }

    private fun readEvents(context: Context): List<NotificationDebugEvent> {
        val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        return readJsonArray(prefs, EVENTS_KEY).toObjectList { obj ->
            NotificationDebugEvent(
                id = obj.optString("id", ""),
                type = obj.optString("type", ""),
                message = obj.optString("message", ""),
                meta = obj.optString("meta", ""),
                createdAt = obj.optString("createdAt", "")
            )
        }
    }

    private fun readLaunches(context: Context): List<NotificationDebugLaunch> {
        val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        return readJsonArray(prefs, LAUNCHES_KEY).toObjectList { obj ->
            NotificationDebugLaunch(
                id = obj.optString("id", ""),
                source = obj.optString("source", ""),
                action = obj.optString("action", ""),
                type = obj.optString("type", ""),
                day = obj.optString("day", ""),
                photoId = obj.optString("photoId", ""),
                extras = obj.optString("extras", ""),
                createdAt = obj.optString("createdAt", "")
            )
        }
    }

    private fun readPayloads(context: Context): List<NotificationDebugPayload> {
        val prefs = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        return readJsonArray(prefs, PAYLOADS_KEY).toObjectList { obj ->
            NotificationDebugPayload(
                id = obj.optString("id", ""),
                source = obj.optString("source", ""),
                type = obj.optString("type", ""),
                action = obj.optString("action", ""),
                day = obj.optString("day", ""),
                photoId = obj.optString("photoId", ""),
                title = obj.optString("title", ""),
                body = obj.optString("body", ""),
                hasNotificationPayload = obj.optBoolean("hasNotificationPayload", false),
                hasDataPayload = obj.optBoolean("hasDataPayload", false),
                dataKeys = obj.optString("dataKeys", ""),
                createdAt = obj.optString("createdAt", "")
            )
        }
    }

    private fun readDebugLogsInternal(prefs: android.content.SharedPreferences): MutableList<DebugLogEntry> {
        val raw = prefs.getString(DEBUG_LOGS_PREF_KEY, "").orEmpty()
        if (raw.isBlank()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { idx ->
                val obj = arr.optJSONObject(idx) ?: JSONObject()
                DebugLogEntry(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    type = obj.optString("type", "unknown"),
                    message = obj.optString("message", ""),
                    meta = obj.optString("meta", ""),
                    createdAt = obj.optString("createdAt", OffsetDateTime.now().toString()),
                    aggregateCount = obj.optInt("aggregateCount", 1).coerceAtLeast(1),
                    firstSeenAt = obj.optString("firstSeenAt", obj.optString("createdAt", "")),
                    lastSeenAt = obj.optString("lastSeenAt", obj.optString("createdAt", ""))
                )
            }.filter { it.createdAt.isNotBlank() }.toMutableList()
        }.getOrElse { mutableListOf() }
    }

    private fun writeDebugLogsInternal(prefs: android.content.SharedPreferences, items: List<DebugLogEntry>) {
        val arr = JSONArray()
        items.takeLast(DEBUG_LOG_MAX_ENTRIES).forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("type", item.type)
                    put("message", item.message)
                    put("meta", item.meta)
                    put("createdAt", item.createdAt)
                    put("aggregateCount", item.aggregateCount)
                    put("firstSeenAt", item.firstSeenAt)
                    put("lastSeenAt", item.lastSeenAt)
                }
            )
        }
        prefs.edit().putString(DEBUG_LOGS_PREF_KEY, arr.toString()).apply()
    }

    private fun readEventArray(prefs: android.content.SharedPreferences): JSONArray = readJsonArray(prefs, EVENTS_KEY)

    private fun readJsonArray(prefs: android.content.SharedPreferences, key: String): JSONArray {
        val raw = prefs.getString(key, "").orEmpty()
        if (raw.isBlank()) return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun writeTrimmedArray(prefs: android.content.SharedPreferences, key: String, source: JSONArray, maxItems: Int) {
        val start = (source.length() - maxItems).coerceAtLeast(0)
        val trimmed = JSONArray()
        for (i in start until source.length()) {
            trimmed.put(source.opt(i))
        }
        prefs.edit().putString(key, trimmed.toString()).apply()
    }

    private fun clean(value: String, maxLen: Int): String = value.trim().replace('\n', ' ').replace('\r', ' ').take(maxLen)

    private fun <T> JSONArray.toObjectList(mapper: (JSONObject) -> T): List<T> {
        val out = mutableListOf<T>()
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            out.add(mapper(obj))
        }
        return out
    }
}
