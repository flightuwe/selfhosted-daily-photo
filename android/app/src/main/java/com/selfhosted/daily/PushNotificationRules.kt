package com.selfhosted.daily

import kotlin.math.abs

data class PushPreferenceSnapshot(
    val masterEnabled: Boolean,
    val chatEnabled: Boolean,
    val pollEnabled: Boolean,
    val feedEnabled: Boolean,
    val specialEnabled: Boolean,
    val inviteEnabled: Boolean,
    val reactionEnabled: Boolean,
    val commentEnabled: Boolean,
    val bookmarkedEnabled: Boolean,
)

object PushNotificationRules {
    fun normalizeType(rawType: String?): String = rawType?.trim()?.lowercase().orEmpty()

    fun notificationIdForKey(rawKey: String?): Int {
        val key = rawKey?.trim().orEmpty()
        if (key.isBlank()) return 0
        val hash = "key|$key".hashCode()
        return if (hash == Int.MIN_VALUE) 0 else abs(hash)
    }

    fun shouldDisplay(rawType: String?, prefs: PushPreferenceSnapshot): Boolean {
        if (!prefs.masterEnabled) return false
        return when (normalizeType(rawType)) {
            "notification_cancel" -> false
            "chat", "chat_message" -> prefs.chatEnabled
            "chat_poll" -> prefs.pollEnabled
            "feed_post", "post", "extra_post" -> prefs.feedEnabled
            "special_request", "special_moment" -> prefs.specialEnabled
            "invite_registered", "invite_registration" -> prefs.inviteEnabled
            "photo_reaction", "photo_fotomoji" -> prefs.reactionEnabled
            "photo_comment" -> prefs.commentEnabled
            "bookmarked_photo_reaction",
            "bookmarked_photo_fotomoji",
            "bookmarked_photo_comment",
            "bookmarked_photo_media_appended",
            "bookmarked_photo_nsfw_marked",
            "bookmarked_photo_nsfw_unmarked" -> prefs.bookmarkedEnabled
            else -> true
        }
    }

    fun notificationId(
        notificationKey: String?,
        rawType: String?,
        rawAction: String?,
        rawDay: String?,
        photoId: String?,
        title: String,
        body: String,
    ): Int {
        notificationIdForKey(notificationKey).takeIf { it != 0 }?.let { return it }
        val seed = listOf(
            normalizeType(rawType),
            rawAction?.trim().orEmpty(),
            rawDay?.trim().orEmpty(),
            photoId?.trim().orEmpty(),
            title.trim(),
            body.trim(),
        ).joinToString("|")
        val hash = seed.hashCode()
        return if (hash == Int.MIN_VALUE) 0 else abs(hash)
    }

    fun groupKey(rawType: String?, rawAction: String?): String {
        val type = normalizeType(rawType)
        val action = rawAction?.trim()?.lowercase().orEmpty()
        return when {
            type == "chat" || type == "chat_message" || type == "chat_poll" || action == "open_chat" -> "daily.chat"
            type == "special_request" || type == "special_moment" || type == "daily_prompt" || type == "daily_moment" || action == "open_camera" -> "daily.camera"
            type == "feed_post" ||
                type == "post" ||
                type == "extra_post" ||
                type.startsWith("photo_") ||
                type.startsWith("bookmarked_photo_") ||
                action == "open_feed" -> "daily.feed"
            type == "invite_registered" || type == "invite_registration" -> "daily.invites"
            else -> "daily.misc"
        }
    }
}
