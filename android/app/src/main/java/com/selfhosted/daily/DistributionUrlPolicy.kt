package com.selfhosted.daily

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

internal data class ValidatedDistributionDestination(
    val url: HttpUrl,
    val addresses: List<InetAddress>
)

internal class DistributionUrlPolicy(
    private val resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName
) {
    fun configured(raw: String): ValidatedDistributionDestination = parse(raw).let { url ->
        if (url.scheme !in setOf("https", "http")) reject("invalid_scheme")
        validate(url, allowPrivate = true)
    }

    fun manifestSyntax(raw: String): HttpUrl = parse(raw).also { url ->
        if (!url.isHttps) reject("insecure_manifest_url")
    }

    fun manifest(raw: String): ValidatedDistributionDestination =
        validate(manifestSyntax(raw), allowPrivate = false)

    fun redirect(
        configuredOrigin: HttpUrl,
        current: HttpUrl,
        location: String?,
        redirectCount: Int = 0
    ): ValidatedDistributionDestination {
        if (redirectCount >= MAX_REDIRECTS) reject("redirect_limit")
        val next = location?.let(current::resolve) ?: reject("invalid_redirect")
        parse(next.toString())
        if (!next.isHttps) reject("redirect_not_https")
        val retainsExplicitTrust = sameOrigin(configuredOrigin, next) ||
            isStandardPortHttpUpgrade(configuredOrigin, next)
        return validate(next, allowPrivate = retainsExplicitTrust)
    }

    internal fun sameOrigin(left: HttpUrl, right: HttpUrl): Boolean =
        left.scheme.equals(right.scheme, ignoreCase = true) &&
            canonicalHost(left.host) == canonicalHost(right.host) &&
            left.port == right.port

    internal fun isStandardPortHttpUpgrade(left: HttpUrl, right: HttpUrl): Boolean =
        left.scheme.equals("http", ignoreCase = true) &&
            right.scheme.equals("https", ignoreCase = true) &&
            canonicalHost(left.host) == canonicalHost(right.host) &&
            left.port == 80 && right.port == 443

    private fun parse(raw: String): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: reject("invalid_url")
        if (url.host.isBlank() || url.username.isNotBlank() || url.password.isNotBlank() || url.fragment != null) {
            reject("invalid_url")
        }
        return url
    }

    private fun validate(url: HttpUrl, allowPrivate: Boolean): ValidatedDistributionDestination {
        val host = canonicalHost(url.host)
        val resolved = runCatching { resolver(host) }.getOrElse { reject("dns_failure") }
        val addresses = resolved.map { normalizeAddress(host, it) }.distinctBy { it.address.toList() }
        if (addresses.isEmpty()) reject("dns_failure")
        if (!allowPrivate && addresses.any(::isBlocked)) reject("private_target")
        return ValidatedDistributionDestination(url, addresses)
    }

    private fun normalizeAddress(host: String, address: InetAddress): InetAddress {
        val bytes = address.address
        val normalized = if (bytes.size == 16 && bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        ) {
            bytes.copyOfRange(12, 16)
        } else {
            bytes
        }
        return InetAddress.getByAddress(host, normalized)
    }

    private fun isBlocked(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        if (bytes.size == 4) {
            val a = bytes[0]
            val b = bytes[1]
            val c = bytes[2]
            return a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 0 && c == 0) ||
                (a == 192 && b == 0 && c == 2) ||
                (a == 192 && b == 88 && c == 99) ||
                (a == 192 && b == 168) ||
                (a == 198 && b in 18..19) ||
                (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113)
        }
        if (bytes.size != 16) return true
        val globalUnicast = bytes[0] in 0x20..0x3f
        val ietfSpecial = bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] <= 0x01
        val documentation2001 = bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8
        val sixToFour = bytes[0] == 0x20 && bytes[1] == 0x02
        val documentation3fff = bytes[0] == 0x3f && bytes[1] == 0xff && (bytes[2] and 0xf0) == 0
        return !globalUnicast || ietfSpecial || documentation2001 || sixToFour || documentation3fff
    }

    private fun canonicalHost(host: String): String = host.trim().trimEnd('.').lowercase()

    private fun reject(errorClass: String): Nothing =
        throw DistributionUrlException(errorClass)

    companion object {
        const val MAX_REDIRECTS = 3
    }
}

internal class PinnedDistributionDns(
    expectedHost: String,
    private val addresses: List<InetAddress>
) : Dns {
    private val expectedHost = expectedHost.trim().trimEnd('.').lowercase()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.trim().trimEnd('.').lowercase() != expectedHost) {
            throw UnknownHostException("unexpected distribution DNS lookup")
        }
        return addresses
    }
}

internal fun buildPinnedDistributionClient(
    baseClient: OkHttpClient,
    destination: ValidatedDistributionDestination
): OkHttpClient = baseClient.newBuilder()
    .connectionPool(ConnectionPool())
    .dns(PinnedDistributionDns(destination.url.host, destination.addresses))
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

internal class DistributionUrlException(val errorClass: String) : IllegalArgumentException(errorClass)
