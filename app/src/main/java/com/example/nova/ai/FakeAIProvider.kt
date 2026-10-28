package com.example.nova.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

class FakeAIProvider(
    private val simulatedLatencyMs: Long = 200
) : AIProviderAdapter {
    override val providerId: String = "fake_local"
    override val displayName: String = "Spark Autonomous Offline Engine (Local)"

    var plannedToolCalls: MutableList<AIToolCall> = mutableListOf()
    var customResponseText: String? = null

    override fun validateConfiguration(): ProviderValidationResult {
        return ProviderValidationResult(isValid = true)
    }

    override suspend fun testConnection(): ConnectionResult {
        delay(simulatedLatencyMs)
        return ConnectionResult(
            isSuccess = true,
            latencyMs = simulatedLatencyMs,
            message = "Offline autonomous engine ready and validated."
        )
    }

    override suspend fun sendMessage(
        messages: List<AIMessage>,
        tools: List<AIToolDefinition>
    ): AIResponse {
        delay(simulatedLatencyMs)
        val lastUserMessage = messages.lastOrNull { it.role == "user" }?.content ?: ""

        // If specific tool calls were staged for testing
        if (plannedToolCalls.isNotEmpty()) {
            val call = plannedToolCalls.removeAt(0)
            return AIResponse(
                text = "Executing tool step: ${call.name}",
                toolCalls = listOf(call),
                finishReason = "tool_calls"
            )
        }

        // Heuristic autonomous responses for local demo/tests
        if (lastUserMessage.contains("browser", ignoreCase = true) || lastUserMessage.contains("navigate", ignoreCase = true) || lastUserMessage.contains("search", ignoreCase = true)) {
            val targetUrl = if (lastUserMessage.contains("wiki", ignoreCase = true)) "https://en.wikipedia.org" else "https://news.ycombinator.com"
            return AIResponse(
                text = "I will navigate to $targetUrl in the embedded browser to inspect the page.",
                toolCalls = listOf(
                    AIToolCall(
                        id = UUID.randomUUID().toString(),
                        name = "navigate",
                        argumentsJson = """{"url":"$targetUrl"}"""
                    )
                ),
                finishReason = "tool_calls"
            )
        }

        if (lastUserMessage.contains("file", ignoreCase = true) || lastUserMessage.contains("write", ignoreCase = true) || lastUserMessage.contains("create", ignoreCase = true)) {
            return AIResponse(
                text = "I am creating the project notes in the workspace.",
                toolCalls = listOf(
                    AIToolCall(
                        id = UUID.randomUUID().toString(),
                        name = "file_write",
                        argumentsJson = """{"path":"Projects/ProjectPlan.md","content":"# Autonomous Project Plan\n\nCreated safely by Spark Workspace."}"""
                    )
                ),
                finishReason = "tool_calls"
            )
        }

        val responseText = customResponseText ?: "Spark Agent received your request: '$lastUserMessage'. All systems (browser, files, vault, tasks) are fully operational and secured."
        return AIResponse(text = responseText, toolCalls = emptyList(), finishReason = "stop")
    }

    override suspend fun streamMessage(
        messages: List<AIMessage>,
        tools: List<AIToolDefinition>
    ): Flow<AIStreamChunk> = flow {
        val full = sendMessage(messages, tools)
        val words = (full.text ?: "Task processed.").split(" ")
        for (word in words) {
            delay(40)
            emit(AIStreamChunk(textDelta = "$word "))
        }
        if (full.toolCalls.isNotEmpty()) {
            for (call in full.toolCalls) {
                emit(AIStreamChunk(toolCallDelta = call))
            }
        }
        emit(AIStreamChunk(isFinished = true))
    }

    override fun getCapabilities(): ProviderCapabilities {
        return ProviderCapabilities(supportsStreaming = true, supportsTools = true, maxContextTokens = 32000)
    }
}
