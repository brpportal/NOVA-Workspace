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

/**
 * A model offered by a provider's /models endpoint.
 *
 * [isFree] is derived ONLY from data the provider itself returns (see
 * OpenAICompatibleProvider.parseModelsJson): an id ending in ":free"
 * (OpenRouter convention) or an explicit pricing object whose prompt AND
 * completion prices are both zero. Providers that return no free signal
 * (e.g. Hugging Face's /models) always yield isFree = false — never guessed.
 */
data class ModelInfo(
    val id: String,
    val isFree: Boolean = false
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

data class ProviderProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val providerType: String = "openai_compatible", // openai_compatible, anthropic, gemini, ollama, custom
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val activeModel: String = "gpt-4o-mini",
    val customHeadersJson: String = "{}",
    val isVisionCapable: Boolean = true,
    val isDefault: Boolean = false
) {
    companion object {
        fun isVisionCapableModel(modelName: String): Boolean {
            val lower = modelName.lowercase()
            return lower.contains("vision") ||
                   lower.contains("4o") ||
                   lower.contains("flash") ||
                   lower.contains("pro") ||
                   lower.contains("claude-3") ||
                   lower.contains("opus") ||
                   lower.contains("sonnet") ||
                   lower.contains("llava") ||
                   lower.contains("qwen-vl") ||
                   lower.contains("pixtral")
        }
    }
}

