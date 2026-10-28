package com.example

import com.example.nova.ai.AIMessage
import com.example.nova.ai.AIToolCall
import com.example.nova.ai.ToolHistorySanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Cohere-via-OpenRouter failure (HTTP 400 "invalid tool message at
 * messages[3]: tool call id '...' not found in previous tool calls") must be
 * impossible: the sanitizer guarantees every tool message answers a tool
 * call declared by the immediately preceding assistant message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolHistorySanitizerTest {

    private fun assertSpecValid(messages: List<AIMessage>) {
        var declared: Set<String> = emptySet()
        var awaitingResults = false
        for (m in messages) {
            if (m.role == "tool") {
                assertTrue(
                    "tool message '${m.toolCallId}' has no declaring assistant message",
                    awaitingResults && declared.contains(m.toolCallId)
                )
            } else {
                declared = m.toolCalls?.map { it.id }?.toSet() ?: emptySet()
                awaitingResults = m.role == "assistant" && !m.toolCalls.isNullOrEmpty()
            }
        }
    }

    @Test
    fun `cohere-shaped history - assistant text without tool calls plus orphan tool result`() {
        // Exactly what the old AgentLoop built: assistant text stored WITHOUT
        // its tool calls, then a tool result referencing the provider's id.
        val history = listOf(
            AIMessage(role = "system", content = "sys"),
            AIMessage(role = "user", content = "google Per Search Kero Pakista"),
            AIMessage(role = "assistant", content = "I will search for that."),
            AIMessage(role = "tool", content = "Navigated", toolCallId = "navigate_q3zgwemc1mym", name = "navigate")
        )
        val result = ToolHistorySanitizer.sanitize(history)
        assertSpecValid(result.messages)
        assertTrue(result.repairs >= 1)
        assertTrue(result.messages.none { it.role == "tool" })
    }

    @Test
    fun `blank assistant text with tool calls - orphan results are dropped`() {
        // The other old shape: no assistant message at all, only results.
        val history = listOf(
            AIMessage(role = "system", content = "sys"),
            AIMessage(role = "user", content = "search"),
            AIMessage(role = "tool", content = "ok", toolCallId = "call_1", name = "navigate"),
            AIMessage(role = "tool", content = "ok", toolCallId = "call_2", name = "click")
        )
        val result = ToolHistorySanitizer.sanitize(history)
        assertSpecValid(result.messages)
        assertEquals(2, result.messages.size) // system + user only
        assertEquals(2, result.repairs)
    }

    @Test
    fun `valid pair passes through untouched with ids verbatim`() {
        val calls = listOf(AIToolCall(id = "navigate_q3zgwemc1mym", name = "navigate", argumentsJson = "{}"))
        val history = listOf(
            AIMessage(role = "system", content = "sys"),
            AIMessage(role = "user", content = "search"),
            AIMessage(role = "assistant", content = "", toolCalls = calls),
            AIMessage(role = "tool", content = "Navigated", toolCallId = "navigate_q3zgwemc1mym", name = "navigate"),
            AIMessage(role = "assistant", content = "Done")
        )
        val result = ToolHistorySanitizer.sanitize(history)
        assertEquals(0, result.repairs)
        assertEquals(history, result.messages)
        assertSpecValid(result.messages)
    }

    @Test
    fun `parallel tool calls - one assistant message, all results kept in order`() {
        val calls = listOf(
            AIToolCall(id = "a1", name = "navigate", argumentsJson = "{}"),
            AIToolCall(id = "a2", name = "get_page_title", argumentsJson = "{}")
        )
        val history = listOf(
            AIMessage(role = "user", content = "go"),
            AIMessage(role = "assistant", content = "", toolCalls = calls),
            AIMessage(role = "tool", content = "nav ok", toolCallId = "a1", name = "navigate"),
            AIMessage(role = "tool", content = "title", toolCallId = "a2", name = "get_page_title"),
            AIMessage(role = "assistant", content = "done")
        )
        val result = ToolHistorySanitizer.sanitize(history)
        assertEquals(0, result.repairs)
        assertEquals(history, result.messages)
        assertSpecValid(result.messages)
    }

    @Test
    fun `unanswered tool call is trimmed from the assistant message`() {
        val calls = listOf(
            AIToolCall(id = "a1", name = "navigate", argumentsJson = "{}"),
            AIToolCall(id = "a2", name = "click", argumentsJson = "{}")
        )
        val history = listOf(
            AIMessage(role = "user", content = "go"),
            AIMessage(role = "assistant", content = "", toolCalls = calls),
            AIMessage(role = "tool", content = "nav ok", toolCallId = "a1", name = "navigate"),
            AIMessage(role = "user", content = "next question")
        )
        val result = ToolHistorySanitizer.sanitize(history)
        assertSpecValid(result.messages)
        val assistant = result.messages.first { it.role == "assistant" }
        assertEquals(listOf("a1"), assistant.toolCalls?.map { it.id })
        assertTrue(result.repairs >= 1)
    }

    @Test
    fun `tool result after a user message is orphaned and dropped`() {
        val history = listOf(
            AIMessage(role = "assistant", content = "", toolCalls = listOf(AIToolCall("a1", "navigate", "{}"))),
            AIMessage(role = "tool", content = "ok", toolCallId = "a1", name = "navigate"),
            AIMessage(role = "user", content = "thanks"),
            AIMessage(role = "tool", content = "late", toolCallId = "a1", name = "navigate")
        )
        val result = ToolHistorySanitizer.sanitize(history)
        assertSpecValid(result.messages)
        assertEquals(1, result.messages.count { it.role == "tool" })
    }
}
