package com.selfhosted.daily

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

class DistributionUrlPolicyTest {
    private val policy = DistributionUrlPolicy(::resolve)

    @Test
    fun originRequiresIdenticalSchemeCanonicalHostAndEffectivePort() {
        val origin = "https://PUBLIC.example/path".toHttpUrl()

        assertTrue(policy.sameOrigin(origin, "https://public.example/other".toHttpUrl()))
        assertEquals(false, policy.sameOrigin(origin, "http://public.example/other".toHttpUrl()))
        assertEquals(false, policy.sameOrigin(origin, "https://public.example:8443/other".toHttpUrl()))
    }

    @Test
    fun configuredPrivateOriginRetainsTrustOnlyForExactOriginOrStandardHttpUpgrade() {
        val httpsConfigured = policy.configured("https://private.example/index.json")
        assertEquals(
            "https://private.example/next.json",
            policy.redirect(httpsConfigured.url, httpsConfigured.url, "/next.json").url.toString()
        )
        assertEquals("private_target", assertUrlError {
            policy.redirect(httpsConfigured.url, httpsConfigured.url, "https://private.example:8443/next.json")
        })

        val httpConfigured = policy.configured("http://private.example/index.json")
        assertEquals(
            "https://private.example/next.json",
            policy.redirect(httpConfigured.url, httpConfigured.url, "https://private.example/next.json").url.toString()
        )
        assertTrue(policy.isStandardPortHttpUpgrade(httpConfigured.url, "https://private.example/next.json".toHttpUrl()))

        val customPort = policy.configured("http://private.example:8080/index.json")
        assertEquals("private_target", assertUrlError {
            policy.redirect(customPort.url, customPort.url, "https://private.example/next.json")
        })
    }

    @Test
    fun redirectsRejectDowngradeCredentialsFragmentsPrivateTargetsAndExcessiveLoops() {
        val configured = policy.configured("https://public.example/index.json")
        listOf(
            "http://public.example/next.json",
            "https://user@public.example/next.json",
            "https://public.example/next.json#fragment",
            "https://private.example/next.json"
        ).forEach { target ->
            assertThrows(target, DistributionUrlException::class.java) {
                policy.redirect(configured.url, configured.url, target)
            }
        }
        assertEquals("redirect_limit", assertUrlError {
            policy.redirect(
                configured.url,
                configured.url,
                "https://public.example/next.json",
                DistributionUrlPolicy.MAX_REDIRECTS
            )
        })
    }

    @Test
    fun manifestRejectsPrivateIpv4Ipv6AndMixedAnswers() {
        assertEquals("https://public.example/app.apk", policy.manifest("https://public.example/app.apk").url.toString())
        listOf(
            "http://public.example/app.apk",
            "https://private.example/app.apk",
            "https://mixed.example/app.apk",
            "https://10.0.0.8/app.apk",
            "https://[fd00::8]/app.apk",
            "https://mapped.example/app.apk"
        ).forEach { target -> assertThrows(target, DistributionUrlException::class.java) { policy.manifest(target) } }
    }

    @Test
    fun validatedAddressesAreTheOnlyDnsAnswersUsedByAnIsolatedProxyFreeClient() {
        var policyLookups = 0
        var baseLookups = 0
        val firstPublic = address("public.example", 93, 184, 216, 34)
        val laterPrivate = address("public.example", 10, 0, 0, 8)
        val rebindingPolicy = DistributionUrlPolicy { _ ->
            policyLookups += 1
            if (policyLookups == 1) arrayOf(firstPublic) else arrayOf(laterPrivate)
        }
        val base = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                baseLookups += 1
                return listOf(laterPrivate)
            }
        }).build()

        val destination = rebindingPolicy.manifest("https://public.example/app.apk")
        val pinned = buildPinnedDistributionClient(base, destination)

        assertEquals(listOf(firstPublic), pinned.dns.lookup("public.example"))
        assertEquals(listOf(firstPublic), pinned.dns.lookup("PUBLIC.EXAMPLE"))
        assertEquals(1, policyLookups)
        assertEquals(0, baseLookups)
        assertThrows(UnknownHostException::class.java) { pinned.dns.lookup("other.example") }
        assertEquals(Proxy.NO_PROXY, pinned.proxy)
        assertNotSame(base.connectionPool, pinned.connectionPool)
        assertEquals(false, pinned.followRedirects)
        assertEquals(false, pinned.followSslRedirects)
    }

    private fun resolve(host: String): Array<InetAddress> = when (host) {
        "public.example" -> arrayOf(address(host, 93, 184, 216, 34))
        "private.example", "10.0.0.8" -> arrayOf(address(host, 10, 20, 30, 40))
        "mixed.example" -> arrayOf(address(host, 93, 184, 216, 34), address(host, 192, 168, 1, 2))
        "fd00::8" -> arrayOf(InetAddress.getByAddress(host, byteArrayOf(
            0xfd.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 8
        )))
        "mapped.example" -> arrayOf(Inet6Address.getByAddress(host, byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 10, 0, 0, 8
        ), -1))
        else -> throw IllegalArgumentException("unknown host")
    }

    private fun address(host: String, a: Int, b: Int, c: Int, d: Int): InetAddress =
        InetAddress.getByAddress(host, byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))

    private fun assertUrlError(block: () -> Unit): String =
        assertThrows(DistributionUrlException::class.java, block).errorClass
}
