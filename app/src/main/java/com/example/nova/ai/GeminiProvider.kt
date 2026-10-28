package com.example.nova.ai

import com.example.BuildConfig
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

class GeminiProvider(
    private val customApiKey: String? = null,
    private val model: String = "gemini-2.5-flash",
    private val timeoutSeconds: Long = 60
) : AIProviderAdapter {
    override val providerId: String = "gemini"
    override val displayName: String = "Google Gemini ($model)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    fun getEffectiveApiKey(): String {
        val userKey = customApiKey?.trim().orEmpty()
        if (userKey.isNotBlank()) return userKey
        return try {
            val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
            field.get(null) as? String ?: ""
        } catch (e: Throwable) {
            ""
        }
    }

    override fun validateConfiguration(): ProviderValidationResult {
        val key = getEffectiveApiKey()
        return if (key.isBlank()) {
            ProviderValidationResult(
                isValid = false,
                errorMessage = "Gemini API key is required. Configure it in Settings or via AI Studio Secrets."
            )
        } else {
            ProviderValidationResult(isValid = true)
        }
    }

    override suspend fun testConnection(): ConnectionResult = withContext(Dispatchers.IO) {
        val key = getEffectiveApiKey()
        if (key.isBlank()) {
            return@withContext ConnectionResult(false, 0, "No API key configured.")
        }

        val startTime = System.currentTimeMillis()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", "Ping test. Reply 'PONG'.") })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    ConnectionResult(true, latency, "Connected to Google Gemini ($model) successfully in ${latency}ms.")
                } else {
                    val errorBody = response.body?.string().orEmpty()
                    ConnectionResult(false, latency, "HTTP ${response.code}: $errorBody")
                }
            }
        } catch (e: Exception) {
            ConnectionResult(false, 0, "Connection error: ${e.message}")
        }
    }

    override suspend fun sendMessage(
        messages: List<AIMessage>,
        tools: List<AIToolDefinition>
    ): AIResponse = withContext(Dispatchers.IO) {
        val key = getEffectiveApiKey()
        if (key.isBlank()) {
            throw IllegalStateException("Gemini API key is missing. Set it in Settings.")
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
        val requestJson = buildGeminiRequestBody(messages, tools)

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Gemini Error (${response.code}): $responseBody")
            }
            parseGeminiResponse(JSONObject(responseBody))
        }
    }

    override suspend fun streamMessage(
        messages: List<AIMessage>,
        tools: List<AIToolDefinition>
    ): Flow<AIStreamChunk> = flow {
        // Fallback to sending message and streaming words if SSE stream endpoint is not available
        val response = sendMessage(messages, tools)
        val text = response.text.orEmpty()
        val tokens = text.split(" ")
        for (token in tokens) {
            kotlinx.coroutines.delay(20)
            emit(AIStreamChunk(textDelta = "$token "))
        }
        for (call in response.toolCalls) {
            emit(AIStreamChunk(toolCallDelta = call))
        }
        emit(AIStreamChunk(isFinished = true))
    }

    override fun getCapabilities(): ProviderCapabilities {
        return ProviderCapabilities(supportsStreaming = true, supportsTools = true, maxContextTokens = 1000000)
    }

    private fun buildGeminiRequestBody(messages: List<AIMessage>, tools: List<AIToolDefinition>): JSONObject {
        val json = JSONObject()
        val contentsArray = JSONArray()

        for (msg in messages) {
            val contentObj = JSONObject()
            val role = when (msg.role) {
                "user" -> "user"
                "assistant" -> "model"
                "system" -> "user" // Gemini contents accepts 'user' and 'model'
                else -> "user"
            }
            contentObj.put("role", role)

            val partsArray = JSONArray()
            if (msg.content.isNotBlank()) {
                partsArray.put(JSONObject().apply { put("text", msg.content) })
            }
            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
        }
        json.put("contents", contentsArray)

        // Add function declarations if tools exist
        if (tools.isNotEmpty()) {
            val funcsArray = JSONArray()
            for (t in tools) {
                val fObj = JSONObject().apply {
                    put("name", t.name)
                    put("description", t.description)
                    try {
                        put("parameters", JSONObject(t.parametersSchemaJson))
                    } catch (e: Exception) {
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject())
                        })
                    }
                }
                funcsArray.put(fObj)
            }
            val toolsArray = JSONArray().apply {
                put(JSONObject().apply { put("functionDeclarations", funcsArray) })
            }
            json.put("tools", toolsArray)
        }

        return json
    }

    private fun parseGeminiResponse(json: JSONObject): AIResponse {
        val candidates = json.optJSONArray("candidates") ?: return AIResponse(text = null)
        if (candidates.length() == 0) return AIResponse(text = null)

        val candidate = candidates.getJSONObject(0)
        val content = candidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts")

        var textContent: String? = null
        val toolCalls = mutableListOf<AIToolCall>()

        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) {
                    textContent = (textContent ?: "") + part.getString("text")
                }
                if (part.has("functionCall")) {
                    val fn = part.getJSONObject("functionCall")
                    val name = fn.getString("name")
                    val argsObj = fn.optJSONObject("args") ?: JSONObject()
                    toolCalls.add(
                        AIToolCall(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            argumentsJson = argsObj.toString()
                        )
                    )
                }
            }
        }

        val finishReason = candidate.optString("finishReason", "stop")
        return AIResponse(
            text = textContent,
            toolCalls = toolCalls,
            finishReason = finishReason
        )
    }
}
