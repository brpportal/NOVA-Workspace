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

/**
 * Structured outcome of a GET {baseUrl}/models call, so the UI can react
 * honestly instead of collapsing every case into a generic "error".
 */
sealed interface FetchModelsResult {
    /** HTTP 200 and at least one model id was returned. */
    data class Success(val models: List<String>) : FetchModelsResult

    /**
     * HTTP 200 but the provider returned an empty model list. This is NOT an
     * error: some providers/plans (e.g. an exhausted free plan) legitimately
     * return `{"data": []}`. The user can still type a model name manually.
     */
    data class Empty(val modelsUrl: String) : FetchModelsResult

    /** The endpoint answered with a non-2xx HTTP status. */
    data class HttpError(val code: Int, val reason: String, val modelsUrl: String) : FetchModelsResult

    /** HTTP 200 but the body was not parseable as a models list. */
    data class Malformed(val modelsUrl: String, val detail: String) : FetchModelsResult

    /** The request never completed (DNS, timeout, TLS, ...). */
    data class NetworkError(val message: String) : FetchModelsResult
}

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

        /**
         * Detailed variant of [fetchModels]: never throws for provider-side
         * outcomes; every case is reported as a [FetchModelsResult] so callers
         * can distinguish "no models on this plan" (info) from real errors.
         */
        suspend fun fetchModelsDetailed(baseUrl: String, apiKey: String, timeoutSeconds: Long = 15): FetchModelsResult = withContext(Dispatchers.IO) {
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

            try {
                client.newCall(reqBuilder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val reason = body.trim().ifBlank { response.message }.take(200)
                        return@withContext FetchModelsResult.HttpError(response.code, reason, modelsUrl)
                    }
                    when (val parsed = parseModelsBody(body)) {
                        null -> FetchModelsResult.Malformed(modelsUrl, "Response was not a JSON models list.")
                        else -> if (parsed.isEmpty()) {
                            FetchModelsResult.Empty(modelsUrl)
                        } else {
                            FetchModelsResult.Success(parsed.distinct().sorted())
                        }
                    }
                }
            } catch (e: Exception) {
                FetchModelsResult.NetworkError(e.message ?: "Network request failed")
            }
        }

        /**
         * Extracts model ids from a /models response body.
         * Returns null when the body is not parseable as a models list at all,
         * and an empty list when it parses but contains zero models.
         */
        private fun parseModelsBody(body: String): List<String>? {
            if (body.isBlank()) return null
            val modelList = mutableListOf<String>()
            fun collect(arr: JSONArray) {
                for (i in 0 until arr.length()) {
                    val item = arr.get(i)
                    if (item is JSONObject) {
                        val id = item.optString("id").ifBlank { item.optString("name") }
                        if (id.isNotBlank()) modelList.add(id)
                    } else if (item is String && item.isNotBlank()) {
                        modelList.add(item)
                    }
                }
            }
            // Object form: {"data": [...]} or {"models": [...]}
            try {
                val root = JSONObject(body)
                val dataArr = root.optJSONArray("data") ?: root.optJSONArray("models")
                if (dataArr != null) {
                    collect(dataArr)
                    return modelList
                }
                return null
            } catch (e: Exception) {
                // fall through to bare-array form
            }
            // Bare array form: [...]
            return try {
                collect(JSONArray(body))
                modelList
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Backwards-compatible helper: returns the model list (possibly empty
         * when the provider legitimately reports zero models) and throws with
         * the real HTTP status / parse problem for genuine failures.
         */
        suspend fun fetchModels(baseUrl: String, apiKey: String, timeoutSeconds: Long = 15): List<String> {
            return when (val result = fetchModelsDetailed(baseUrl, apiKey, timeoutSeconds)) {
                is FetchModelsResult.Success -> result.models
                is FetchModelsResult.Empty -> emptyList()
                is FetchModelsResult.HttpError -> throw IllegalStateException("HTTP ${result.code}: ${result.reason}")
                is FetchModelsResult.Malformed -> throw IllegalStateException("Failed to parse models response from ${result.modelsUrl}: ${result.detail}")
                is FetchModelsResult.NetworkError -> throw IllegalStateException(result.message)
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

    /**
     * Lightweight connection check: a single GET {baseUrl}/models call.
     * Reports the REAL outcome — HTTP status and how many models the provider
     * actually returned — instead of burning a chat completion on a "ping".
     * Note: an HTTP 200 with 0 models still means "connected"; it usually
     * means the plan exposes no models, in which case a manually typed model
     * name may or may not work — chat will surface the provider's real error.
     */
    override suspend fun testConnection(): ConnectionResult {
        val validation = validateConfiguration()
        if (!validation.isValid) return ConnectionResult(false, 0, validation.errorMessage.orEmpty())

        val startTime = System.currentTimeMillis()
        val result = fetchModelsDetailed(baseUrl, apiKey, timeoutSeconds = 15)
        val latency = System.currentTimeMillis() - startTime
        return when (result) {
            is FetchModelsResult.Success -> ConnectionResult(
                true, latency,
                "Connected — HTTP 200 in ${latency}ms. ${result.models.size} model(s) available at $baseUrl."
            )
            is FetchModelsResult.Empty -> ConnectionResult(
                true, latency,
                "Connected — HTTP 200 in ${latency}ms, but the provider returned 0 models. " +
                    "The active model '$model' is not confirmed by this provider; if chat fails, " +
                    "type a model name that exists on your plan."
            )
            is FetchModelsResult.HttpError -> ConnectionResult(
                false, latency,
                "Connection failed — HTTP ${result.code}: ${result.reason}"
            )
            is FetchModelsResult.Malformed -> ConnectionResult(
                false, latency,
                "Reached $baseUrl but the /models response was not valid JSON — check the Base URL."
            )
            is FetchModelsResult.NetworkError -> ConnectionResult(
                false, latency,
                "Connection failed: ${result.message}"
            )
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
