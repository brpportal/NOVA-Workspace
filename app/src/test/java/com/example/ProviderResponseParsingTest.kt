package com.example

import com.example.nova.ai.OpenAICompatibleProvider
import com.example.nova.ai.ProviderEmptyResponseException
import com.example.nova.ai.ProviderErrors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fix round 2, Defect E: empty provider responses at the parsing layer.
 *
 * Regression target: Al's task died with provider_request FAILED
 * "No value for choices" — org.json's raw exception for an HTTP-200 body
 * with no `choices`. The parser now throws the typed
 * [ProviderEmptyResponseException], which the agent loop treats as
 * retryable; a 429 is classified as a rate limit so the task can end with
 * the plain-language provider-limit summary + a PROVIDER_LIMIT lesson.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderResponseParsingTest {

    @Test
    fun `parses a normal text response`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"Facebook khul gaya hai."},"finish_reason":"stop"}]}"""
        val response = OpenAICompatibleProvider.parseChatCompletion(body)
        assertEquals("Facebook khul gaya hai.", response.text)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `parses tool calls with provider ids verbatim`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_9x","type":"function","function":{"name":"navigate","arguments":"{\"url\":\"https://www.facebook.com\"}"}}]},"finish_reason":"tool_calls"}]}"""
        val response = OpenAICompatibleProvider.parseChatCompletion(body)
        assertEquals(1, response.toolCalls.size)
        assertEquals("call_9x", response.toolCalls[0].id)
        assertEquals("navigate", response.toolCalls[0].name)
        assertTrue(response.toolCalls[0].argumentsJson.contains("facebook.com"))
    }

    @Test
    fun `missing choices throws the typed empty-response exception`() {
        assertEmptyResponseThrown("""{}""")
        assertEmptyResponseThrown("""{"id":"gen-123","object":"chat.completion"}""")
    }

    @Test
    fun `empty choices array throws the typed empty-response exception`() {
        assertEmptyResponseThrown("""{"choices":[]}""")
    }

    @Test
    fun `choice without a message throws the typed empty-response exception`() {
        assertEmptyResponseThrown("""{"choices":[{"finish_reason":"stop"}]}""")
    }

    @Test
    fun `message with neither text nor tool calls throws the typed empty-response exception`() {
        assertEmptyResponseThrown("""{"choices":[{"message":{"role":"assistant","content":""},"finish_reason":"stop"}]}""")
        assertEmptyResponseThrown("""{"choices":[{"message":{"role":"assistant"},"finish_reason":"stop"}]}""")
    }

    @Test
    fun `empty-response detection covers typed and raw org json failures`() {
        assertTrue(ProviderErrors.isEmptyResponseError(ProviderEmptyResponseException("Provider returned no choices")))
        // The exact raw failure text from Al's audit trail.
        assertTrue(ProviderErrors.isEmptyResponseError(org.json.JSONException("No value for choices")))
        assertTrue(ProviderErrors.isEmptyResponseError(IllegalStateException("Provider returned an empty response")))
        assertFalse(ProviderErrors.isEmptyResponseError(IllegalStateException("API Error (500): boom")))
    }

    @Test
    fun `rate limit detection covers the openrouter free-tier 429`() {
        // The exact shape from Al's audit trail (step result, raw form).
        val raw = """API Error (429): {"error":{"message":"Rate limit exceeded: free-models-per-day. Add 10 credits to unlock 1000 free model requests per day","code":429}}"""
        assertTrue(ProviderErrors.isRateLimitError(IllegalStateException(raw)))
        assertTrue(ProviderErrors.isRateLimitError(IllegalStateException("HTTP 429: too many requests")))
        assertFalse(ProviderErrors.isRateLimitError(IllegalStateException("API Error (400): bad request")))
        // And the summary the task ends with is the plain-language one.
        assertTrue(ProviderErrors.RATE_LIMIT_SUMMARY.startsWith("Provider limit:"))
        assertTrue(ProviderErrors.RATE_LIMIT_SUMMARY.contains("rozana limit"))
    }

    private fun assertEmptyResponseThrown(body: String) {
        try {
            OpenAICompatibleProvider.parseChatCompletion(body)
            fail("Expected ProviderEmptyResponseException for body: $body")
        } catch (expected: ProviderEmptyResponseException) {
            // expected
        }
    }
}
