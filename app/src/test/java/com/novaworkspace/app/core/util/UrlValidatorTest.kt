package com.novaworkspace.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun `bare domain is normalized to https`() {
        val result = UrlValidator.validate("example.com")
        assertTrue(result is UrlValidator.ValidationResult.Valid)
        result as UrlValidator.ValidationResult.Valid
        assertEquals("https://example.com", result.normalizedUrl)
        assertEquals(false, result.isHttp)
    }

    @Test
    fun `http is accepted but flagged`() {
        val result = UrlValidator.validate("http://example.com/page")
        assertTrue(result is UrlValidator.ValidationResult.Valid)
        assertEquals(true, (result as UrlValidator.ValidationResult.Valid).isHttp)
    }

    @Test
    fun `blank input is invalid`() {
        assertTrue(UrlValidator.validate("   ") is UrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `non web schemes are rejected`() {
        assertTrue(UrlValidator.validate("ftp://example.com") is UrlValidator.ValidationResult.Invalid)
        assertTrue(UrlValidator.validate("javascript://example.com") is UrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `spaces and traversal-like hosts are rejected`() {
        assertTrue(UrlValidator.validate("exa mple.com") is UrlValidator.ValidationResult.Invalid)
        assertTrue(UrlValidator.validate("https://..example..com") is UrlValidator.ValidationResult.Invalid)
        assertTrue(UrlValidator.validate("https://-bad-.com") is UrlValidator.ValidationResult.Invalid)
    }

    @Test
    fun `path traversal strings are not treated as urls`() {
        // A relative filesystem path has no valid host and must be rejected.
        assertTrue(UrlValidator.validate("../../etc/passwd") is UrlValidator.ValidationResult.Invalid)
    }
}
