package com.selfhosted.daily

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class EmailActionLink(val token: String, val purpose: String, val origin: String)

object EmailActionLinks {
    fun parse(rawUri: String?, allowedHosts: Set<String>): EmailActionLink? {
        val uri = runCatching { URI(rawUri?.trim().orEmpty()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase()?.trim().orEmpty()
        if (host.isBlank() || allowedHosts.none { it.equals(host, ignoreCase = true) }) return null
        val purpose = when (uri.path) {
            "/email-action/reset" -> "password_reset"
            "/email-action/verify" -> uri.fragmentParameter("purpose") ?: "verify_email"
            else -> return null
        }
        if (purpose !in setOf("password_reset", "verify_email", "newsletter_optin")) return null
        val token = uri.fragmentParameter("token")?.trim().orEmpty()
        if (token.length !in 40..128 || token.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) return null
        return EmailActionLink(token, purpose, "https://$host")
    }

    private fun URI.fragmentParameter(name: String): String? =
        rawFragment?.split('&')?.asSequence()?.mapNotNull { item ->
            val parts = item.split('=', limit = 2)
            if (parts.size == 2) URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to URLDecoder.decode(parts[1], StandardCharsets.UTF_8) else null
        }?.firstOrNull { it.first == name }?.second
}
