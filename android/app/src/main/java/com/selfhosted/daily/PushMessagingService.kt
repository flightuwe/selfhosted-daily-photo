package com.selfhosted.daily

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.time.LocalDate
import java.time.LocalTime

class PushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("app", Context.MODE_PRIVATE)
            .edit()
            .putString("pending_fcm_token", token)
            .remove("last_synced_device_token")
            .remove("last_synced_device_token_at")
            .apply()
        PushNotificationDiagnostics.recordEvent(
            this,
            type = "push_new_token",
            message = "pending_fcm_token_updated",
            meta = "tokenLength=${token.length}"
        )
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        val type = PushNotificationRules.normalizeType(message.data["type"])
        val action = message.data["action"]?.trim().orEmpty()
        val day = message.data["day"]?.trim().orEmpty()
        val photoId = message.data["photoId"]?.trim().orEmpty()
        PushNotificationDiagnostics.recordEvent(
            this,
            type = "push_message_received",
            message = if (type.isBlank()) "unknown" else type,
            meta = "source=real_fcm;delivery=on_message_received;action=${if (action.isBlank()) "-" else action};day=${if (day.isBlank()) "-" else day};photoId=${if (photoId.isBlank()) "-" else photoId};hasNotificationPayload=${message.notification != null};dataKeys=${message.data.keys.sorted().joinToString(",")}"
        )
        PushNotificationDiagnostics.recordPayload(
            this,
            source = "real_fcm",
            type = type,
            action = action,
            day = day,
            photoId = photoId,
            title = message.notification?.title.orEmpty(),
            body = message.notification?.body ?: message.data["body"].orEmpty(),
            hasNotificationPayload = message.notification != null,
            hasDataPayload = message.data.isNotEmpty(),
            dataKeys = message.data.keys
        )
        if (isFeedRelatedPush(action, type, day, photoId)) {
            queuePendingFeedInvalidation(
                context = this,
                day = day.ifBlank { LocalDate.now().toString() },
                photoId = photoId.toLongOrNull()?.takeIf { it > 0L },
                reason = if (type.isBlank()) "feed_push" else type,
                source = if (action.isBlank()) "push" else action
            )
            PushNotificationDiagnostics.recordEvent(
                this,
                type = "feed_push_invalidation",
                message = if (type.isBlank()) "feed_push" else type,
                meta = "action=${if (action.isBlank()) "-" else action};day=${day.ifBlank { "-" }};photoId=${photoId.ifBlank { "-" }};queued=true"
            )
        }
        val prefsSnapshot = PushPreferenceSnapshot(
            masterEnabled = prefs.getBoolean("notifications_master_enabled", true),
            chatEnabled = prefs.getBoolean("chat_push_enabled_local", false),
            pollEnabled = prefs.getBoolean("poll_push_enabled_local", false),
            feedEnabled = prefs.getBoolean("feed_post_push_enabled", false),
            specialEnabled = prefs.getBoolean("special_moment_push_enabled_local", false),
            inviteEnabled = prefs.getBoolean("invite_registration_push_enabled_local", false),
            reactionEnabled = prefs.getBoolean("photo_reaction_push_enabled_local", false),
            commentEnabled = prefs.getBoolean("photo_comment_push_enabled_local", false),
            bookmarkedEnabled = prefs.getBoolean("bookmarked_photo_push_enabled_local", false),
        )
        if (!PushNotificationRules.shouldDisplay(type, prefsSnapshot)) return
        if (isBlockedByQuietHours(type, prefs)) return

        val tone = toneConfig(prefs)
        val title = message.notification?.title ?: "Daily Moment"
        val body = message.notification?.body ?: message.data["body"] ?: "Zeit fuer deinen taeglichen Moment."
        postTrackedNotification(this, tone, type, action, day, photoId, title, body, source = "real_fcm")
    }

    companion object {
        private const val CHANNEL_PROMPT_ID = "daily_prompt"
        private const val CHANNEL_UPDATE_ID = "daily_updates"
        private const val PUSH_NOTIFICATION_PREFS = "app"
        private const val PUSH_NOTIFICATION_IDS_KEY = "tracked_push_notification_ids_v1"
        private const val PREF_CUSTOM_TONE_ENABLED = "custom_notification_tone_enabled"
        private const val PREF_CUSTOM_TONE_URI = "custom_notification_tone_uri"
        private const val PREF_QUIET_HOURS_ENABLED = "quiet_hours_enabled"
        private const val PREF_QUIET_HOURS_START = "quiet_hours_start"
        private const val PREF_QUIET_HOURS_END = "quiet_hours_end"

        fun clearTrackedPushNotifications(context: Context, reason: String = "unknown", aggressive: Boolean = true) {
            val prefs = context.getSharedPreferences(PUSH_NOTIFICATION_PREFS, Context.MODE_PRIVATE)
            val ids = prefs.getStringSet(PUSH_NOTIFICATION_IDS_KEY, emptySet())
                ?.mapNotNull { it.toIntOrNull() }
                .orEmpty()
            val notifications = NotificationManagerCompat.from(context)
            val beforeSnapshot = PushNotificationDiagnostics.activeNotificationsSnapshot(context)
            PushNotificationDiagnostics.recordEvent(
                context,
                type = "push_clear_started",
                message = reason,
                meta = "trackedIds=${if (ids.isEmpty()) "-" else ids.joinToString(",")};before=$beforeSnapshot"
            )
            ids.forEach(notifications::cancel)
            val remainingBeforeFallback = PushNotificationDiagnostics.activeNotificationsCount(context)
            var usedCancelAll = false
            if (aggressive && remainingBeforeFallback > 0) {
                notifications.cancelAll()
                usedCancelAll = true
            }
            val afterSnapshot = PushNotificationDiagnostics.activeNotificationsSnapshot(context)
            PushNotificationDiagnostics.recordEvent(
                context,
                type = "push_clear_finished",
                message = reason,
                meta = "trackedIds=${if (ids.isEmpty()) "-" else ids.joinToString(",")};usedCancelAll=$usedCancelAll;after=$afterSnapshot"
            )
            prefs.edit().remove(PUSH_NOTIFICATION_IDS_KEY).apply()
        }

        fun clearAllNotifications(context: Context, reason: String = "unknown_cancel_all") {
            val notifications = NotificationManagerCompat.from(context)
            val beforeSnapshot = PushNotificationDiagnostics.activeNotificationsSnapshot(context)
            notifications.cancelAll()
            PushNotificationDiagnostics.recordEvent(
                context,
                type = "push_cancel_all",
                message = reason,
                meta = "before=$beforeSnapshot;after=${PushNotificationDiagnostics.activeNotificationsSnapshot(context)}"
            )
        }

        fun showDebugTrackedNotificationBurst(context: Context) {
            val prefs = context.getSharedPreferences(PUSH_NOTIFICATION_PREFS, Context.MODE_PRIVATE)
            val tone = toneConfig(prefs)
            repeat(3) { index ->
                postTrackedNotification(
                    context = context,
                    tone = tone,
                    type = "chat",
                    action = "open_chat",
                    day = "",
                    photoId = "",
                    title = "Daily Debug ${index + 1}",
                    body = "Test-Benachrichtigung ${index + 1} fuer die Aufraeum-Diagnose.",
                    source = "debug_burst"
                )
            }
            PushNotificationDiagnostics.recordEvent(
                context,
                type = "push_debug_burst_posted",
                message = "posted 3 debug notifications",
                meta = PushNotificationDiagnostics.activeNotificationsSnapshot(context)
            )
        }

        fun showDebugNotificationScenario(context: Context, scenarioId: String) {
            val prefs = context.getSharedPreferences(PUSH_NOTIFICATION_PREFS, Context.MODE_PRIVATE)
            val tone = toneConfig(prefs)
            when (scenarioId.trim().lowercase()) {
                "mixed_matrix" -> {
                    postTrackedNotification(context, tone, "chat", "open_chat", "", "", "Debug Chat", "Chat-Matrix 1", "debug_mixed")
                    postTrackedNotification(context, tone, "chat_poll", "open_chat", "", "", "Debug Poll", "Poll-Matrix 2", "debug_mixed")
                    postTrackedNotification(context, tone, "post", "open_feed", "2026-07-02", "321", "Debug Feed", "Feed-Matrix 3", "debug_mixed")
                    postTrackedNotification(context, tone, "special_request", "open_camera", "", "", "Debug Prompt", "Prompt-Matrix 4", "debug_mixed")
                }
                "same_id_matrix" -> {
                    repeat(3) {
                        postTrackedNotification(context, tone, "chat", "open_chat", "", "", "Debug Same ID", "Immer gleicher Inhalt", "debug_same_id")
                    }
                }
                "feed_matrix" -> {
                    postTrackedNotification(context, tone, "post", "open_feed", "2026-07-02", "501", "Debug Feed", "Feed-Test A", "debug_feed")
                    postTrackedNotification(context, tone, "photo_comment", "open_feed", "2026-07-01", "502", "Debug Kommentar", "Feed-Test B", "debug_feed")
                    postTrackedNotification(context, tone, "chat_poll", "open_chat", "", "", "Debug Poll", "Feed/Poll-Test", "debug_feed")
                }
                "summary_group_matrix" -> {
                    postTrackedNotification(context, tone, "chat", "open_chat", "", "", "Group Chat 1", "Gruppen-Test 1", "debug_group")
                    postTrackedNotification(context, tone, "chat_poll", "open_chat", "", "", "Group Chat 2", "Gruppen-Test 2", "debug_group")
                    postTrackedNotification(context, tone, "chat_message", "open_chat", "", "", "Group Chat 3", "Gruppen-Test 3", "debug_group")
                }
                else -> showDebugTrackedNotificationBurst(context)
            }
            PushNotificationDiagnostics.recordEvent(
                context,
                type = "push_debug_scenario",
                message = scenarioId,
                meta = PushNotificationDiagnostics.activeNotificationsSnapshot(context)
            )
        }

        fun showLocalUpdateNotification(context: Context, update: UpdateInfo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
            val tone = toneConfig(prefs)
            val channelId = ensureUpdateChannel(context, tone)

            val target = update.apkUrl ?: update.releaseUrl
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target))
            val pending = PendingIntent.getActivity(
                context,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Daily Update verfuegbar")
                .setContentText("Neue Version ${update.latestVersion} gefunden.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .apply {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && tone.enabled && tone.uri != null) {
                        setSound(tone.uri)
                    }
                }
                .build()

            NotificationManagerCompat.from(context).notify(2001, notification)
        }

        fun showLocalToneTestNotification(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
            val tone = toneConfig(prefs)
            val channelId = ensurePromptChannel(context, tone)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                3001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Daily Ton-Test")
                .setContentText("So klingt dein aktueller Benachrichtigungston.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .apply {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && tone.enabled && tone.uri != null) {
                        setSound(tone.uri)
                    }
                }
                .build()

            NotificationManagerCompat.from(context).notify(3001, notification)
        }

        private data class ToneConfig(val enabled: Boolean, val uri: Uri?)

        private fun postTrackedNotification(
            context: Context,
            tone: ToneConfig,
            type: String,
            action: String,
            day: String,
            photoId: String,
            title: String,
            body: String,
            source: String
        ) {
            val channelId = ensurePromptChannel(context, tone)
            val notificationId = PushNotificationRules.notificationId(type, action, day, photoId, title, body)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_LAUNCH_ACTION, action)
                putExtra(EXTRA_LAUNCH_TYPE, type)
                putExtra(EXTRA_LAUNCH_DAY, day)
                photoId.toLongOrNull()?.takeIf { it > 0L }?.let {
                    putExtra(EXTRA_LAUNCH_PHOTO_ID, it)
                }
            }
            val pending = PendingIntent.getActivity(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setGroup(PushNotificationRules.groupKey(type, action))
                .setContentIntent(pending)
                .apply {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && tone.enabled && tone.uri != null) {
                        setSound(tone.uri)
                    }
                }
                .build()

            NotificationManagerCompat.from(context).notify(notificationId, notification)
            trackPushNotificationId(context, notificationId)
            PushNotificationDiagnostics.recordPayload(
                context,
                source = source,
                type = type,
                action = action,
                day = day,
                photoId = photoId,
                title = title,
                body = body,
                hasNotificationPayload = true,
                hasDataPayload = true,
                dataKeys = listOf("type", "action", "day", "photoId", "body")
            )
            PushNotificationDiagnostics.recordEvent(
                context,
                type = "push_notification_posted",
                message = if (type.isBlank()) "unknown" else type,
                meta = "source=$source;id=$notificationId;action=${if (action.isBlank()) "-" else action};group=${PushNotificationRules.groupKey(type, action)};channel=$channelId;snapshot=${PushNotificationDiagnostics.activeNotificationsSnapshot(context)}"
            )
        }

        private fun trackPushNotificationId(context: Context, notificationId: Int) {
            val prefs = context.getSharedPreferences(PUSH_NOTIFICATION_PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getStringSet(PUSH_NOTIFICATION_IDS_KEY, emptySet()).orEmpty().toMutableSet()
            stored.add(notificationId.toString())
            val trimmed = if (stored.size > 64) stored.toList().takeLast(64).toSet() else stored
            if (!prefs.edit().putStringSet(PUSH_NOTIFICATION_IDS_KEY, trimmed).commit()) {
                Log.w("PushMessagingService", "Failed to persist tracked push notification id")
            }
        }

        private fun toneConfig(prefs: android.content.SharedPreferences): ToneConfig {
            val enabled = prefs.getBoolean(PREF_CUSTOM_TONE_ENABLED, false)
            val uriRaw = prefs.getString(PREF_CUSTOM_TONE_URI, "").orEmpty().trim()
            val uri = if (enabled && uriRaw.isNotBlank()) runCatching { Uri.parse(uriRaw) }.getOrNull() else null
            return ToneConfig(enabled, uri)
        }

        private fun isBlockedByQuietHours(type: String, prefs: android.content.SharedPreferences): Boolean {
            val quietEnabled = prefs.getBoolean(PREF_QUIET_HOURS_ENABLED, false)
            if (!quietEnabled) return false
            val normalized = type.trim().lowercase()
            if (normalized == "daily_prompt" || normalized == "daily_moment" || normalized == "special_moment" || normalized == "special_request") {
                return false
            }

            val startRaw = prefs.getString(PREF_QUIET_HOURS_START, "22:00").orEmpty()
            val endRaw = prefs.getString(PREF_QUIET_HOURS_END, "07:00").orEmpty()
            val start = runCatching { LocalTime.parse(startRaw) }.getOrElse { LocalTime.of(22, 0) }
            val end = runCatching { LocalTime.parse(endRaw) }.getOrElse { LocalTime.of(7, 0) }
            val now = LocalTime.now()
            return if (start == end) {
                true
            } else if (start < end) {
                now >= start && now < end
            } else {
                now >= start || now < end
            }
        }

        private fun ensurePromptChannel(context: Context, tone: ToneConfig): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return CHANNEL_PROMPT_ID
            val id = if (tone.enabled && tone.uri != null) {
                "${CHANNEL_PROMPT_ID}_custom_${tone.uri.toString().hashCode().toUInt().toString(16)}"
            } else {
                CHANNEL_PROMPT_ID
            }
            val channel = NotificationChannel(
                id,
                "Daily Prompt",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Benachrichtigungen fuer taegliche Foto-Prompts"
                if (tone.enabled && tone.uri != null) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(tone.uri, attrs)
                }
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            return id
        }

        private fun ensureUpdateChannel(context: Context, tone: ToneConfig): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return CHANNEL_UPDATE_ID
            val id = if (tone.enabled && tone.uri != null) {
                "${CHANNEL_UPDATE_ID}_custom_${tone.uri.toString().hashCode().toUInt().toString(16)}"
            } else {
                CHANNEL_UPDATE_ID
            }
            val channel = NotificationChannel(
                id,
                "Daily Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Benachrichtigungen fuer neue App-Versionen"
                if (tone.enabled && tone.uri != null) {
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(tone.uri, attrs)
                }
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            return id
        }
    }
}
