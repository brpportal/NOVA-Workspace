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

class OpenAICompatibleProvider(
    private val apiKey: String,
    private val model: String = "gpt-4o-mini",
    endpointOrBaseUrl: String = "https://api.openai.com/v1",
    private val timeoutSeconds: Long = 60
) : AIProviderAdapter {
    override val providerId: String = "openai_compatible"
    override val displayName: String = "OpenAI / Compatible ($model)"

    val baseUrl: String = normalizeBaseUrl(endpointOrBaseUrl)
    val endpoint: String = if (endpointOrBaseUrl.trim().endsWith("/chat/completions")) {
        endpointOrBaseUrl.trim()
    } else {
        "$baseUrl/chat/completions"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    companion object {
        fun normalizeBaseUrl(url: String): String {
            var clean = url.trim().trimEnd('/')
            if (clean.isBlank()) clean = "https://api.openai.com/v1"
            if (clean.endsWith("/chat/completions")) {
                clean = clean.removeSuffix("/chat/completions").trimEnd('/')
            }
            return clean
        }

        suspend fun fetchModels(baseUrl: String, apiKey: String, timeoutSeconds: Long = 15): List<String> = withContext(Dispatchers.IO) {
            val cleanBase = normalizeBaseUrl(baseUrl)
            val modelsUrl = "$cleanBase/models"
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()

            val reqBuilder = Request.Builder().url(modelsUrl)
            if (apiKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: $body")
                }

                val modelList = mutableListOf<String>()
                try {
                    val root = JSONObject(body)
                    val dataArr = root.optJSONArray("data") ?: root.optJSONArray("models")
                    if (dataArr != null) {
                        for (i in 0 until dataArr.length()) {
                            val item = dataArr.get(i)
                            if (item is JSONObject) {
                                val id = item.optString("id").ifBlank { item.optString("name") }
                                if (id.isNotBlank()) modelList.add(id)
                            } else if (item is String && item.isNotBlank()) {
                                modelList.add(item)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Try parsing as array directly
                    try {
                        val arr = JSONArray(body)
                        for (i in 0 until arr.length()) {
                            val item = arr.get(i)
                            if (item is JSONObject) {
                                val id = item.optString("id").ifBlank { item.optString("name") }
                                if (id.isNotBlank()) modelList.add(id)
                            } else if (item is String && item.isNotBlank()) {
                                modelList.add(item)
                            }
                        }
                    } catch (e2: Exception) {
                        throw IllegalStateException("Failed to parse models JSON: ${e.message}")
                    }
                }

                if (modelList.isEmpty()) {
                    throw IllegalStateException("No models returned by $modelsUrl")
                }
                modelList.distinct().sorted()
            }
        }
    }

    override fun validateConfiguration(): ProviderValidationResult {
        return if (apiKey.isBlank()) {
            ProviderValidationResult(false, "API key is required.")
        } else if (baseUrl.isBlank() || !baseUrl.startsWith("http")) {
            ProviderValidationResult(false, "Valid HTTP(S) Base URL is required.")
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
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "ping")
                })
            })
            put("max_tokens", 5)
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    ConnectionResult(true, latency, "Connected to $endpoint ($model) successfully in ${latency}ms.")
                } else {
                    val err = response.body?.string().orEmpty()
                    ConnectionResult(false, latency, "HTTP ${response.code}: $err")
                }
            }
        } catch (e: Exception) {
            ConnectionResult(false, 0, "Connection failed: ${e.message}")
        }
    }

    override suspend fun sendMessage(
        messages: List<AIMessage>,
        tools: List<AIToolDefinition>
    ): AIResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", model)
            val msgsArr = JSONArray()
            for (m in messages) {
                val mObj = JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                    if (m.toolCalls != null && m.toolCalls.isNotEmpty()) {
                        val tcArr = JSONArray()
                        for (tc in m.toolCalls) {
                            tcArr.put(JSONObject().apply {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tc.name)
                                    put("arguments", tc.argumentsJson)
                                })
                            })
                        }
                        put("tool_calls", tcArr)
                    }
                    if (m.toolCallId != null) {
                        put("tool_call_id", m.toolCallId)
                    }
                }
                msgsArr.put(mObj)
            }
            put("messages", msgsArr)

            if (tools.isNotEmpty()) {
                val toolsArr = JSONArray()
                for (t in tools) {
                    toolsArr.put(JSONObject().apply {
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", t.name)
                            put("description", t.description)
                            try {
                                put("parameters", JSONObject(t.parametersSchemaJson))
                            } catch (e: Exception) {
                                put("parameters", JSONObject())
                            }
                        })
                    })
                }
                put("tools", toolsArr)
            }
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("API Error (${response.code}): $body")
            }
            val json = JSONObject(body)
            val choices = json.getJSONArray("choices")
            if (choices.length() == 0) return@use AIResponse(text = null)

            val firstChoice = choices.getJSONObject(0)
            val messageObj = firstChoice.getJSONObject("message")
            val text = messageObj.optString("content", "").takeIf { it.isNotBlank() }
            val finishReason = firstChoice.optString("finish_reason", "stop")

            val toolCalls = mutableListOf<AIToolCall>()
            if (messageObj.has("tool_calls")) {
                val tcArr = messageObj.getJSONArray("tool_calls")
                for (i in 0 until tcArr.length()) {
                    val tc = tcArr.getJSONObject(i)
                    val fn = tc.getJSONObject("function")
                    toolCalls.add(
                        AIToolCall(
                            id = tc.optString("id", UUID.randomUUID().toString()),
                            name = fn.getString("name"),
                            argumentsJson = fn.getString("arguments")
                        )
                    )
                }
            }

            AIResponse(text = text, toolCalls = toolCalls, finishReason = finishReason)
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
        return ProviderCapabilities(supportsStreaming = true, supportsTools = true, maxContextTokens = 128000)
    }
}
