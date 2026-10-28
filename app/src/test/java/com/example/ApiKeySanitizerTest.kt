package com.example

import com.example.nova.ai.ApiKeySanitizer
import com.example.nova.ai.ProviderErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sanitizer is what stands between a sloppy paste (invisible or
 * non-ASCII characters picked up from other apps/keyboards) and OkHttp's
 * raw "Unexpected char ... in Authorization value" exception.
 * All special characters below are written as unicode escapes on purpose.
 */
class ApiKeySanitizerTest {

    @Test
    fun `clean key passes through untouched`() {
        val result = ApiKeySanitizer.sanitize("sk-or-v1-abc123XYZ")
        assertEquals("sk-or-v1-abc123XYZ", result.cleaned)
        assertEquals(0, result.removedCount)
    }

    @Test
    fun `arabic character from the reported 0x628 error is stripped`() {
        // U+0628 is the exact character from the user's error report:
        // "Unexpected char 0x628 at 7 in Authorization value".
        val result = ApiKeySanitizer.sanitize("sk-\u0628livekey")
        assertEquals("sk-livekey", result.cleaned)
        assertTrue(result.removedCount >= 1)
        assertTrue(ApiKeySanitizer.isHeaderSafe(result.cleaned))
    }

    @Test
    fun `invisible characters and whitespace are stripped and counted`() {
        val dirty = "  sk-key​\n" // zero-width space U+200B + trailing newline
        val result = ApiKeySanitizer.sanitize(dirty)
        assertEquals("sk-key", result.cleaned)
        assertTrue("expected removals, got ${result.removedCount}", result.removedCount >= 3)
    }

    @Test
    fun `null and blank keys sanitize to empty`() {
        assertEquals("", ApiKeySanitizer.sanitize(null).cleaned)
        assertEquals("", ApiKeySanitizer.sanitize("   ").cleaned)
    }

    @Test
    fun `header safety check rejects non-ascii`() {
        assertTrue(ApiKeySanitizer.isHeaderSafe("Bearer abc-123"))
        assertFalse(ApiKeySanitizer.isHeaderSafe("Bearer \u0628key"))
    }

    @Test
    fun `provider errors translate the raw okhttp header exception`() {
        val raw = IllegalArgumentException("Unexpected char 0x628 at 7 in Authorization value")
        assertEquals(ApiKeySanitizer.INVALID_KEY_MESSAGE, ProviderErrors.userMessage(raw))
    }

    @Test
    fun `provider errors keep real http errors intact`() {
        val http = IllegalStateException("HTTP 404: model not found")
        assertEquals("HTTP 404: model not found", ProviderErrors.userMessage(http))
    }
}
