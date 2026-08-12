package com.selfhosted.daily

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

internal class DistributionUrlPolicy(
    private val resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName
) {
    fun configured(raw: String): HttpUrl = parse(raw).also { url ->
        if (url.scheme !in setOf("https", "http")) reject("invalid_scheme")
    }

    fun manifestSyntax(raw: String): HttpUrl = parse(raw).also { url ->
        if (!url.isHttps) reject("insecure_manifest_url")
    }

    fun manifest(raw: String): HttpUrl = manifestSyntax(raw).also { url ->
        requirePublic(url)
    }

    fun redirect(configuredOrigin: HttpUrl, current: HttpUrl, location: String?, redirectCount: Int = 0): HttpUrl {
        if (redirectCount >= MAX_REDIRECTS) reject("redirect_limit")
        val next = location?.let(current::resolve) ?: reject("invalid_redirect")
        parse(next.toString())
        if (!next.isHttps) reject("redirect_not_https")
        if (!sameOrigin(configuredOrigin, next)) requirePublic(next)
        return next
    }

    private fun parse(raw: String): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: reject("invalid_url")
        if (url.host.isBlank() || url.username.isNotBlank() || url.password.isNotBlank() || url.fragment != null) {
            reject("invalid_url")
        }
        return url
    }

    private fun requirePublic(url: HttpUrl) {
        val addresses = runCatching { resolver(url.host) }.getOrElse { reject("dns_failure") }
        if (addresses.isEmpty() || addresses.any(::isBlocked)) reject("private_target")
    }

    private fun sameOrigin(left: HttpUrl, right: HttpUrl): Boolean =
        left.host.equals(right.host, ignoreCase = true)

    private fun isBlocked(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        if (bytes.size == 4) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            return a == 0 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) ||
                (a == 192 && b == 0 && c == 0) ||
                (a == 192 && b == 0 && c == 2) ||
                (a == 198 && b in 18..19) ||
                (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113)
        }
        return bytes.size == 16 && (
            (bytes[0] and 0xfe) == 0xfc ||
                (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8)
            )
    }

    private fun reject(errorClass: String): Nothing =
        throw DistributionUrlException(errorClass)

    companion object {
        const val MAX_REDIRECTS = 3
    }
}

internal class DistributionUrlException(val errorClass: String) : IllegalArgumentException(errorClass)
