package com.selfhosted.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushNotificationRulesTest {
    private val enabledPrefs = PushPreferenceSnapshot(
        masterEnabled = true,
        chatEnabled = true,
        pollEnabled = true,
        feedEnabled = true,
        specialEnabled = true,
        inviteEnabled = true,
        reactionEnabled = true,
        commentEnabled = true,
        bookmarkedEnabled = true,
    )

    @Test
    fun `chat poll respects poll setting`() {
        val prefs = enabledPrefs.copy(pollEnabled = false)

        assertFalse(PushNotificationRules.shouldDisplay("chat_poll", prefs))
        assertTrue(PushNotificationRules.shouldDisplay("chat", prefs))
    }

    @Test
    fun `bookmarked variants respect bookmarked setting`() {
        val prefs = enabledPrefs.copy(bookmarkedEnabled = false)

        assertFalse(PushNotificationRules.shouldDisplay("bookmarked_photo_comment", prefs))
        assertFalse(PushNotificationRules.shouldDisplay("bookmarked_photo_media_appended", prefs))
        assertTrue(PushNotificationRules.shouldDisplay("photo_comment", prefs))
    }

    @Test
    fun `unknown type falls back to enabled`() {
        assertTrue(PushNotificationRules.shouldDisplay("broadcast", enabledPrefs))
        assertFalse(PushNotificationRules.shouldDisplay("broadcast", enabledPrefs.copy(masterEnabled = false)))
    }

    @Test
    fun `notification id is stable for same payload`() {
        val first = PushNotificationRules.notificationId(
            rawType = "chat",
            rawAction = "open_chat",
            rawDay = "",
            photoId = "",
            title = "Daily Chat",
            body = "Neue Chat-Nachricht von ferb",
        )
        val second = PushNotificationRules.notificationId(
            rawType = "chat",
            rawAction = "open_chat",
            rawDay = "",
            photoId = "",
            title = "Daily Chat",
            body = "Neue Chat-Nachricht von ferb",
        )

        assertEquals(first, second)
    }

    @Test
    fun `notification id changes for different payload`() {
        val first = PushNotificationRules.notificationId(
            rawType = "chat",
            rawAction = "open_chat",
            rawDay = "",
            photoId = "",
            title = "Daily Chat",
            body = "Neue Chat-Nachricht von ferb",
        )
        val second = PushNotificationRules.notificationId(
            rawType = "chat_poll",
            rawAction = "open_chat",
            rawDay = "",
            photoId = "",
            title = "Neue Umfrage",
            body = "ferb hat eine Umfrage gestartet",
        )

        assertNotEquals(first, second)
    }

    @Test
    fun `group key keeps chat notifications together`() {
        assertEquals("daily.chat", PushNotificationRules.groupKey("chat", "open_chat"))
        assertEquals("daily.chat", PushNotificationRules.groupKey("chat_poll", "open_chat"))
        assertEquals("daily.feed", PushNotificationRules.groupKey("photo_comment", "open_feed"))
    }
}
