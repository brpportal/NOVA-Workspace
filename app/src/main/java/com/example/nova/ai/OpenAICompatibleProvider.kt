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
    apiKey: String,
    private val model: String = "gpt-4o-mini",
    endpointOrBaseUrl: String = "https://api.openai.com/v1",
    private val timeoutSeconds: Long = 60
) : AIProviderAdapter {
    // The key is sanitized at construction so a pasted key carrying invisible
    // or non-ASCII characters can never make OkHttp throw its raw
    // "Unexpected char ... in Authorization value" exception.
    private val keySanitization = ApiKeySanitizer.sanitize(apiKey)
    private val apiKey: String = keySanitization.cleaned
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

        suspend fun fetchModels(baseUrl: String, apiKey: String, timeoutSeconds: Long = 15): List<ModelInfo> = withContext(Dispatchers.IO) {
            val cleanBase = normalizeBaseUrl(baseUrl)
            val modelsUrl = "$cleanBase/models"
            val client = OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()

            // Sanitize before the key ever touches a header: a pasted key with
            // invisible/non-ASCII characters must produce the friendly
            // invalid-key message, never OkHttp's raw exception text.
            val cleanKey = ApiKeySanitizer.sanitize(apiKey).cleaned
            val request = try {
                val reqBuilder = Request.Builder().url(modelsUrl)
                if (cleanKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $cleanKey")
                }
                reqBuilder.build()
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException(ProviderErrors.userMessage(e))
            }

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: $body")
                }
                parseModelsJson(body)
            }
        }

        /**
         * Parses a /models response body into [ModelInfo] entries.
         * The free flag comes only from the payload itself: an id ending in
         * ":free", or a `pricing` object whose prompt AND completion prices
         * are both zero (OpenRouter returns string prices like "0").
         * Payloads without pricing data (e.g. Hugging Face) never get the tag.
         */
        fun parseModelsJson(body: String): List<ModelInfo> {
            val result = mutableListOf<ModelInfo>()

            fun addItem(item: Any?) {
                when (item) {
                    is JSONObject -> {
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isNotBlank()) {
                            result.add(ModelInfo(id = id, isFree = isFreeModel(item, id)))
                        }
                    }
                    is String -> {
                        if (item.isNotBlank()) {
                            result.add(ModelInfo(id = item, isFree = item.endsWith(":free")))
                        }
                    }
                }
            }

            try {
                val root = JSONObject(body)
                val dataArr = root.optJSONArray("data") ?: root.optJSONArray("models")
                if (dataArr != null) {
                    for (i in 0 until dataArr.length()) {
                        addItem(dataArr.get(i))
                    }
                }
            } catch (e: Exception) {
                // Try parsing as a bare JSON array
                try {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        addItem(arr.get(i))
                    }
                } catch (e2: Exception) {
                    throw IllegalStateException("Failed to parse models JSON: ${ProviderErrors.userMessage(e)}")
                }
            }

            return result.distinctBy { it.id }.sortedBy { it.id }
        }

        private fun isFreeModel(item: JSONObject, id: String): Boolean {
            if (id.endsWith(":free")) return true
            val pricing = item.optJSONObject("pricing") ?: return false
            fun isZero(value: Any?): Boolean {
                if (value == null) return false
                val s = value.toString().trim()
                if (s == "0") return true
                return s.toDoubleOrNull() == 0.0
            }
            return isZero(pricing.opt("prompt")) && isZero(pricing.opt("completion"))
        }
    }

    override fun validateConfiguration(): ProviderValidationResult {
        return if (apiKey.isBlank() && keySanitization.removedCount > 0) {
            // The pasted key consisted only of characters that had to be stripped.
            ProviderValidationResult(false, ApiKeySanitizer.INVALID_KEY_MESSAGE)
        } else if (apiKey.isBlank()) {
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

        try {
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
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
            ConnectionResult(false, 0, "Connection failed: ${ProviderErrors.userMessage(e)}")
        }
    }

    override suspend fun sendMessage(
        messages: List<AIMessage>,
        tools: List<AIToolDefinition>
    ): AIResponse = withContext(Dispatchers.IO) {
        // Strict backends behind OpenAI-compatible routers (e.g. Cohere via
        // OpenRouter) reject histories whose tool messages reference tool_call
        // ids the preceding assistant message never declared (HTTP 400).
        // Repair the sequence at request time; ids are never invented here.
        val safeMessages = ToolHistorySanitizer.sanitize(messages).messages
        val payload = JSONObject().apply {
            put("model", model)
            val msgsArr = JSONArray()
            for (m in safeMessages) {
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

        val rawResponse = try {
            client.newCall(request).execute()
        } catch (e: IllegalArgumentException) {
            // Header validation failures must never leak OkHttp's raw text.
            throw IllegalStateException(ProviderErrors.userMessage(e))
        }
        rawResponse.use { response ->
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
