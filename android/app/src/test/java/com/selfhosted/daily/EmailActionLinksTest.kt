package com.selfhosted.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmailActionLinksTest {
    private val hosts = setOf("daily.broutschek.de", "daily.harzcloud.de")
    private val token = "a".repeat(43)

    @Test fun parsesBothConfiguredHostsAndPurposes() {
        assertEquals("verify_email", EmailActionLinks.parse("https://daily.broutschek.de/email-action/verify#token=$token", hosts)?.purpose)
        assertEquals("newsletter_optin", EmailActionLinks.parse("https://daily.harzcloud.de/email-action/verify#token=$token&purpose=newsletter_optin", hosts)?.purpose)
        assertEquals("password_reset", EmailActionLinks.parse("https://daily.harzcloud.de/email-action/reset#token=$token", hosts)?.purpose)
    }

    @Test fun rejectsWrongSchemeHostPathAndToken() {
        assertNull(EmailActionLinks.parse("http://daily.broutschek.de/email-action/verify#token=$token", hosts))
        assertNull(EmailActionLinks.parse("https://evil.example/email-action/verify#token=$token", hosts))
        assertNull(EmailActionLinks.parse("https://daily.broutschek.de/other#token=$token", hosts))
        assertNull(EmailActionLinks.parse("https://daily.broutschek.de/email-action/verify#token=short", hosts))
    }
}
