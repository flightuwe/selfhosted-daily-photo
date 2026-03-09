package com.selfhosted.daily

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

        ensurePromptChannel(this)
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

        val notification = NotificationCompat.Builder(this, CHANNEL_PROMPT_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        NotificationManagerCompat.from(this).notify(Random.nextInt(), notification)
    }

    companion object {
        private const val CHANNEL_PROMPT_ID = "daily_prompt"
        private const val CHANNEL_UPDATE_ID = "daily_updates"

        fun showLocalUpdateNotification(context: Context, update: UpdateInfo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ensureUpdateChannel(context)

            val target = update.apkUrl ?: update.releaseUrl
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(target))
            val pending = PendingIntent.getActivity(
                context,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_UPDATE_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Daily Update verfuegbar")
                .setContentText("Neue Version ${update.latestVersion} gefunden.")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()

            NotificationManagerCompat.from(context).notify(2001, notification)
        }

        private fun ensurePromptChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_PROMPT_ID,
                "Daily Prompt",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Benachrichtigungen fuer taegliche Foto-Prompts"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        private fun ensureUpdateChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_UPDATE_ID,
                "Daily Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Benachrichtigungen fuer neue App-Versionen"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
