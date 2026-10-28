package com.example.nova.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class AnthropicProvider(
    private val apiKey: String,
    private val model: String = "claude-3-5-sonnet-20241022",
    private val timeoutSeconds: Long = 60
) : AIProviderAdapter {
    override val providerId: String = "anthropic"
    override val displayName: String = "Anthropic Claude ($model)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    override fun validateConfiguration(): ProviderValidationResult {
        return if (apiKey.isBlank()) {
            ProviderValidationResult(false, "Anthropic API key is required.")
        } else {
            ProviderValidationResult(true)
        }
    }

    override suspend fun testConnection(): ConnectionResult = withContext(Dispatchers.IO) {
        val validation = validateConfiguration()
        if (!validation.isValid) return@withContext ConnectionResult(false, 0, validation.errorMessage.orEmpty())

        val startTime = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", 10)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "ping")
                })
            })
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    ConnectionResult(true, latency, "Connected to Anthropic API ($model) in ${latency}ms.")
                } else {
                    val err = response.body?.string().orEmpty()
                    ConnectionResult(false, latency, "HTTP ${response.code}: $err")
                }
            }
        } catch (e: Exception) {
            ConnectionResult(false, 0, "Anthropic connection failed: ${e.message}")
        }
    }

    override suspend fun sendMessage(
        messages: List<AIMessage>,
        tools: List<AIToolDefinition>
    ): AIResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)

            val msgsArr = JSONArray()
            var systemPrompt = ""

            for (m in messages) {
                if (m.role == "system") {
                    systemPrompt = m.content
                } else {
                    msgsArr.put(JSONObject().apply {
                        put("role", if (m.role == "assistant") "assistant" else "user")
                        put("content", m.content)
                    })
                }
            }
            if (systemPrompt.isNotBlank()) {
                put("system", systemPrompt)
            }
            put("messages", msgsArr)

            if (tools.isNotEmpty()) {
                val toolsArr = JSONArray()
                for (t in tools) {
                    toolsArr.put(JSONObject().apply {
                        put("name", t.name)
                        put("description", t.description)
                        try {
                            put("input_schema", JSONObject(t.parametersSchemaJson))
                        } catch (e: Exception) {
                            put("input_schema", JSONObject())
                        }
                    })
                }
                put("tools", toolsArr)
            }
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Anthropic Error (${response.code}): $body")
            }
            val json = JSONObject(body)
            val contentArr = json.getJSONArray("content")
            var text: String? = null
            val toolCalls = mutableListOf<AIToolCall>()

            for (i in 0 until contentArr.length()) {
                val item = contentArr.getJSONObject(i)
                val type = item.getString("type")
                if (type == "text") {
                    text = (text ?: "") + item.getString("text")
                } else if (type == "tool_use") {
                    toolCalls.add(
                        AIToolCall(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            argumentsJson = item.getJSONObject("input").toString()
                        )
                    )
                }
            }
            AIResponse(text = text, toolCalls = toolCalls, finishReason = json.optString("stop_reason", "stop"))
        }
    }

    override suspend fun streamMessage(
        messages: List<AIMessage>,
        tools: List<AIToolDefinition>
    ): Flow<AIStreamChunk> = flow {
        val full = sendMessage(messages, tools)
        val text = full.text.orEmpty()
        for (word in text.split(" ")) {
            kotlinx.coroutines.delay(20)
            emit(AIStreamChunk(textDelta = "$word "))
        }
        for (call in full.toolCalls) {
            emit(AIStreamChunk(toolCallDelta = call))
        }
        emit(AIStreamChunk(isFinished = true))
    }

    override fun getCapabilities(): ProviderCapabilities {
        return ProviderCapabilities(supportsStreaming = true, supportsTools = true, maxContextTokens = 200000)
    }
}
