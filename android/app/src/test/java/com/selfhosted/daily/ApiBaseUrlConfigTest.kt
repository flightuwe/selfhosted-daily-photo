package com.selfhosted.daily

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiBaseUrlConfigTest {
    @Test
    fun `normalize appends api suffix`() {
        assertEquals("https://daily.example.com/api/", normalizeApiBaseUrl("https://daily.example.com"))
        assertEquals("https://daily.example.com/api/", normalizeApiBaseUrl("daily.example.com"))
    }

    @Test
    fun `normalize preserves explicit api path`() {
        assertEquals("https://demo.example.com/api/", normalizeApiBaseUrl("https://demo.example.com/api"))
    }

    @Test
    fun `placeholder hosts are detected`() {
        assertTrue(isPlaceholderApiBaseUrl("https://daily.example.tld/api/"))
        assertTrue(isPlaceholderApiBaseUrl("https://tenant.example.com/api/"))
        assertTrue(isPlaceholderApiBaseUrl("https://example.org/api/"))
    }

    @Test
    fun `real selfhost targets are not treated as placeholders`() {
        assertFalse(isPlaceholderApiBaseUrl("https://daily.broutschek.de/api/"))
        assertFalse(isPlaceholderApiBaseUrl("https://demo.selfhosted.invalid-host/api/"))
        assertFalse(isPlaceholderApiBaseUrl("http://192.168.178.80:13379/api/"))
    }
}
