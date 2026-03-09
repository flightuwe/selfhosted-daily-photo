package com.selfhosted.daily

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.random.Random

class PushMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("app", Context.MODE_PRIVATE)
            .edit()
            .putString("pending_fcm_token", token)
            .remove("last_synced_device_token")
            .remove("last_synced_device_token_at")
            .apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        val masterEnabled = prefs.getBoolean("notifications_master_enabled", true)
        if (!masterEnabled) return

        val type = message.data["type"]?.trim()?.lowercase().orEmpty()
        val chatEnabled = prefs.getBoolean("chat_push_enabled_local", false)
        val feedEnabled = prefs.getBoolean("feed_post_push_enabled", false)
        if ((type == "chat" || type == "chat_message") && !chatEnabled) return
        if ((type == "feed_post" || type == "post" || type == "extra_post") && !feedEnabled) return

        val tone = toneConfig(prefs)
        val channelId = ensurePromptChannel(this, tone)
        val title = message.notification?.title ?: "Daily Moment"
        val body = message.notification?.body ?: message.data["body"] ?: "Zeit fuer deinen taeglichen Moment."

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .apply {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && tone.enabled && tone.uri != null) {
                    setSound(tone.uri)
                }
            }
            .build()

        NotificationManagerCompat.from(this).notify(Random.nextInt(), notification)
    }

    companion object {
        private const val CHANNEL_PROMPT_ID = "daily_prompt"
        private const val CHANNEL_UPDATE_ID = "daily_updates"
        private const val PREF_CUSTOM_TONE_ENABLED = "custom_notification_tone_enabled"
        private const val PREF_CUSTOM_TONE_URI = "custom_notification_tone_uri"

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

        private data class ToneConfig(val enabled: Boolean, val uri: Uri?)

        private fun toneConfig(prefs: android.content.SharedPreferences): ToneConfig {
            val enabled = prefs.getBoolean(PREF_CUSTOM_TONE_ENABLED, false)
            val uriRaw = prefs.getString(PREF_CUSTOM_TONE_URI, "").orEmpty().trim()
            val uri = if (enabled && uriRaw.isNotBlank()) runCatching { Uri.parse(uriRaw) }.getOrNull() else null
            return ToneConfig(enabled, uri)
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
