package com.example.nova.ai

class AIProviderFactory {
    companion object {
        fun createProvider(
            providerType: String,
            apiKey: String?,
            modelName: String?,
            endpoint: String?,
            timeoutSeconds: Long = 60
        ): AIProviderAdapter {
            val key = apiKey?.trim().orEmpty()
            return when (providerType.lowercase()) {
                "gemini" -> {
                    val model = if (!modelName.isNullOrBlank()) modelName else "gemini-2.5-flash"
                    GeminiProvider(
                        customApiKey = key.ifBlank { null },
                        model = model,
                        timeoutSeconds = timeoutSeconds
                    )
                }
                "openai", "openai_compatible" -> {
                    val model = if (!modelName.isNullOrBlank()) modelName else "gpt-4o-mini"
                    val ep = if (!endpoint.isNullOrBlank()) endpoint else "https://api.openai.com/v1/chat/completions"
                    OpenAICompatibleProvider(
                        apiKey = key,
                        model = model,
                        endpoint = ep,
                        timeoutSeconds = timeoutSeconds
                    )
                }
                "anthropic" -> {
                    val model = if (!modelName.isNullOrBlank()) modelName else "claude-3-5-sonnet-20241022"
                    AnthropicProvider(
                        apiKey = key,
                        model = model,
                        timeoutSeconds = timeoutSeconds
                    )
                }
                "fake", "offline", "mock" -> {
                    FakeAIProvider()
                }
                else -> {
                    // Default to Gemini if key or buildconfig key is present, otherwise FakeAIProvider
                    val gemini = GeminiProvider(customApiKey = key.ifBlank { null })
                    if (gemini.getEffectiveApiKey().isNotBlank()) {
                        gemini
                    } else {
                        FakeAIProvider()
                    }
                }
            }
        }
    }
}
