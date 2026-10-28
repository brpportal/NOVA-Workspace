package com.example

import com.example.nova.ai.ProviderErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task cards must show ONE short human summary of a provider failure —
 * never the raw (nested) provider JSON that used to be printed twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderErrorSummaryTest {

    @Test
    fun `openrouter cohere nested error drills down to the innermost message`() {
        // The exact shape from Al's failed task: OpenRouter wrapping
        // Cohere's error inside metadata.raw as a JSON string.
        val raw = """API Error (400): {"error":{"message":"Provider returned error","code":400,"metadata":{"raw":"{\"id\":\"d7d514f5\",\"message\":\"invalid tool message at messages[3]: tool call id 'navigate_q3zgwemc1mym' not found in previous tool calls\"}","provider_name":"Cohere","is_byok":false},"user_id":"user_3LJO9X2c"}"""
        val summary = ProviderErrors.summarizeErrorText(raw)
        assertTrue(summary.startsWith("Provider error (400): "))
        assertTrue(summary.contains("invalid tool message at messages[3]"))
        assertFalse(summary.contains("Provider returned error"))
        assertFalse(summary.contains("user_id"))
        assertFalse(summary.contains("{"))
    }

    @Test
    fun `task failed prefix is peeled before summarizing`() {
        val raw = "Task failed: API Error (429): {\"error\":{\"message\":\"Rate limit exceeded\"}}"
        val summary = ProviderErrors.summarizeErrorText(raw)
        assertEquals("Provider error (429): Rate limit exceeded", summary)
    }

    @Test
    fun `plain messages pass through cleaned and capped`() {
        val summary = ProviderErrors.summarizeErrorText("Duplicate tool loop detected: identical action called 3 times in succession.")
        assertEquals("Duplicate tool loop detected: identical action called 3 times in succession.", summary)
        val long = ProviderErrors.summarizeErrorText("x".repeat(500))
        assertTrue(long.length <= 220)
    }

    @Test
    fun `blank error becomes a generic honest summary`() {
        assertEquals("Unknown provider error", ProviderErrors.summarizeErrorText(""))
        assertEquals("Unknown provider error", ProviderErrors.summarizeErrorText("   "))
    }

    @Test
    fun `errorSummary uses exception message`() {
        val e = IllegalStateException("API Error (401): {\"error\":{\"message\":\"Invalid API key\"}}")
        assertEquals("Provider error (401): Invalid API key", ProviderErrors.errorSummary(e))
    }

    @Test
    fun `userMessage hides compose lifecycle internals`() {
        val e = IllegalStateException("The coroutine scope left the composition")
        val msg = ProviderErrors.userMessage(e)
        assertFalse(msg.contains("coroutine scope"))
        assertTrue(msg.contains("interrupted"))
    }
}
