package com.example

import com.example.nova.ai.OpenAICompatibleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The FREE tag on a model may only come from the provider's own payload:
 * an id ending in ":free", or pricing whose prompt AND completion prices
 * are both zero (OpenRouter style). Payloads without pricing data must
 * never be tagged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelInfoParsingTest {

    @Test
    fun `openrouter free suffix and zero pricing are tagged free`() {
        val body = """
            {"data": [
              {"id": "meta-llama/llama-3.3-70b-instruct:free"},
              {"id": "deepseek/deepseek-r1", "pricing": {"prompt": "0", "completion": "0"}},
              {"id": "openai/gpt-4o-mini", "pricing": {"prompt": "0.00000015", "completion": "0.0000006"}},
              {"id": "x-ai/grok-4", "pricing": {"prompt": "3", "completion": "15"}}
            ]}
        """.trimIndent()

        val models = OpenAICompatibleProvider.parseModelsJson(body)
        val byId = models.associateBy { it.id }

        assertEquals(4, models.size)
        assertTrue(byId.getValue("meta-llama/llama-3.3-70b-instruct:free").isFree)
        assertTrue(byId.getValue("deepseek/deepseek-r1").isFree)
        assertFalse(byId.getValue("openai/gpt-4o-mini").isFree)
        assertFalse(byId.getValue("x-ai/grok-4").isFree)
    }

    @Test
    fun `numeric zero pricing also counts as free`() {
        val body = """{"data": [{"id": "some/model", "pricing": {"prompt": 0, "completion": 0}}]}"""
        val models = OpenAICompatibleProvider.parseModelsJson(body)
        assertEquals(1, models.size)
        assertTrue(models[0].isFree)
    }

    @Test
    fun `huggingface style payload without pricing gets no free tags`() {
        // router.huggingface.co/v1/models returns ids only — no pricing and
        // no reliable free flag, so nothing may be tagged.
        val body = """
            {"object": "list", "data": [
              {"id": "meta-llama/Llama-3.1-8B-Instruct", "object": "model", "created": 0, "owned_by": "meta-llama"},
              {"id": "mistralai/Mistral-7B-Instruct-v0.3", "object": "model", "created": 0, "owned_by": "mistralai"}
            ]}
        """.trimIndent()

        val models = OpenAICompatibleProvider.parseModelsJson(body)
        assertEquals(2, models.size)
        assertTrue(models.none { it.isFree })
    }

    @Test
    fun `empty data yields an empty list`() {
        assertTrue(OpenAICompatibleProvider.parseModelsJson("""{"data": []}""").isEmpty())
    }

    @Test
    fun `partial pricing does not count as free`() {
        // Free prompt but paid completion (and vice versa) is not free.
        val body = """{"data": [{"id": "a/b", "pricing": {"prompt": "0", "completion": "0.5"}}]}"""
        val models = OpenAICompatibleProvider.parseModelsJson(body)
        assertFalse(models[0].isFree)
    }
}
