package com.selfhosted.daily

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

private const val CHAT_URL_TAG = "chat-url"
private val chatUrlPattern = Regex("https?://[^\\s<>()]+", RegexOption.IGNORE_CASE)
private val trailingUrlPunctuation = ".,;:!?"

/** Removes the share-tracking `si` parameter from links without changing other link data. */
fun cleanChatLinks(message: String): String = chatUrlPattern.replace(message) { match ->
    val rawUrl = match.value
    val suffix = rawUrl.takeLastWhile { it in trailingUrlPunctuation }
    cleanChatUrl(rawUrl.dropLast(suffix.length)) + suffix
}

private fun cleanChatUrl(url: String): String {
    val fragmentIndex = url.indexOf('#')
    val beforeFragment = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
    val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
    val queryIndex = beforeFragment.indexOf('?')
    if (queryIndex < 0) return url
    val base = beforeFragment.substring(0, queryIndex)
    val kept = beforeFragment.substring(queryIndex + 1).split('&').filterNot {
        it.substringBefore('=').equals("si", ignoreCase = true)
    }
    return if (kept.isEmpty()) "$base$fragment" else "$base?${kept.joinToString("&")}$fragment"
}

@Composable
fun LinkedChatMessageText(message: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cleanMessage = cleanChatLinks(message)
    val annotatedMessage = buildAnnotatedString {
        var cursor = 0
        chatUrlPattern.findAll(cleanMessage).forEach { match ->
            append(cleanMessage.substring(cursor, match.range.first))
            val rawUrl = match.value
            val suffix = rawUrl.takeLastWhile { it in trailingUrlPunctuation }
            val link = rawUrl.dropLast(suffix.length)
            pushStringAnnotation(CHAT_URL_TAG, link)
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) { append(link) }
            pop()
            append(suffix)
            cursor = match.range.last + 1
        }
        append(cleanMessage.substring(cursor))
    }
    ClickableText(
        text = annotatedMessage,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        onClick = { offset ->
            annotatedMessage.getStringAnnotations(CHAT_URL_TAG, offset, offset).firstOrNull()?.let { openChatLink(context, it.item) }
        }
    )
}

private fun openChatLink(context: Context, url: String) {
    val uri = Uri.parse(url)
    val packageName = when (uri.host?.lowercase()) {
        "music.youtube.com" -> "com.google.android.apps.youtube.music"
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be" -> "com.google.android.youtube"
        else -> null
    }
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(if (packageName == null) intent else intent.setPackage(packageName))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(intent.setPackage(null))
    }
}
