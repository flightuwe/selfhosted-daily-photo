package com.selfhosted.daily

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress

class DistributionUrlPolicyTest {
    private val policy = DistributionUrlPolicy { host ->
        when (host) {
            "public.example" -> arrayOf(InetAddress.getByName("93.184.216.34"))
            "private.example" -> arrayOf(InetAddress.getByName("10.20.30.40"))
            else -> throw IllegalArgumentException("unknown host")
        }
    }

    @Test
    fun configuredSelfhostOriginMayRemainPrivateButRedirectMayNotIntroduceOne() {
        val configured = policy.configured("http://private.example/index.json")
        assertEquals(
            "https://private.example/next.json",
            policy.redirect(configured, configured, "https://private.example/next.json").toString()
        )

        assertThrows(DistributionUrlException::class.java) {
            policy.redirect(configured, configured, "https://10.0.0.8/index.json")
        }
    }

    @Test
    fun redirectsRejectDowngradeCredentialsFragmentsPrivateDnsAndLoopsAreBoundedByCaller() {
        val configured = "https://public.example/index.json".toHttpUrl()
        listOf(
            "http://public.example/next.json",
            "https://user@public.example/next.json",
            "https://public.example/next.json#fragment",
            "https://private.example/next.json"
        ).forEach { target ->
            assertThrows(target, DistributionUrlException::class.java) {
                policy.redirect(configured, configured, target)
            }
        }
        val limit = assertThrows(DistributionUrlException::class.java) {
            policy.redirect(configured, configured, "https://public.example/next.json", DistributionUrlPolicy.MAX_REDIRECTS)
        }
        assertEquals("redirect_limit", limit.errorClass)
    }

    @Test
    fun manifestUrlsMustBeHttpsAndPublic() {
        assertEquals("https://public.example/app.apk", policy.manifest("https://public.example/app.apk").toString())
        assertThrows(DistributionUrlException::class.java) { policy.manifest("http://public.example/app.apk") }
        assertThrows(DistributionUrlException::class.java) { policy.manifest("https://private.example/app.apk") }
    }
}
