package com.example.nova.ai

import kotlinx.coroutines.flow.Flow

data class AIMessage(
    val role: String, // "system", "user", "assistant", "tool"
    val content: String,
    val toolCalls: List<AIToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null
)

data class AIToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class AIToolDefinition(
    val name: String,
    val description: String,
    val parametersSchemaJson: String
)

data class AIResponse(
    val text: String?,
    val toolCalls: List<AIToolCall> = emptyList(),
    val finishReason: String? = null
)

data class AIStreamChunk(
    val textDelta: String = "",
    val toolCallDelta: AIToolCall? = null,
    val isFinished: Boolean = false
)

data class ProviderCapabilities(
    val supportsStreaming: Boolean = true,
    val supportsTools: Boolean = true,
    val maxContextTokens: Int = 128000
)

data class ProviderValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

data class ConnectionResult(
    val isSuccess: Boolean,
    val latencyMs: Long = 0,
    val message: String
)

interface AIProviderAdapter {
    val providerId: String
    val displayName: String

    fun validateConfiguration(): ProviderValidationResult
    suspend fun testConnection(): ConnectionResult
    suspend fun sendMessage(
        messages: List<AIMessage>,
        tools: List<AIToolDefinition>
    ): AIResponse
    suspend fun streamMessage(
        messages: List<AIMessage>,
        tools: List<AIToolDefinition>
    ): Flow<AIStreamChunk>
    fun getCapabilities(): ProviderCapabilities
}
