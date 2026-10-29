package com.example.nova.ai

/**
 * Cleans up user-pasted API keys.
 *
 * Keys are often pasted from browsers or documents and can carry invisible
 * characters (zero-width spaces, BOMs, stray newlines) or non-ASCII glyphs
 * picked up from RTL keyboards. Any of those makes OkHttp reject the
 * Authorization header with a raw "Unexpected char 0x... in Authorization
 * value" exception. Sanitizing at every entry point (Settings save, model
 * fetch, provider construction) means that raw exception can never occur —
 * and [ProviderErrors] guarantees it never reaches the UI if it somehow does.
 */
object ApiKeySanitizer {

    const val INVALID_KEY_MESSAGE = "The API key contains invalid characters — please paste it again"

    data class Result(
        val cleaned: String,
        /** How many characters were removed (edge whitespace + stripped chars). */
        val removedCount: Int
    )

    /**
     * Trims the key and strips every character that is not printable ASCII
     * (keeps 0x21 '!' through 0x7E '~': letters, digits and the punctuation
     * real API keys use). Spaces anywhere in the key are removed too — no
     * mainstream provider issues keys containing spaces.
     */
    fun sanitize(raw: String?): Result {
        if (raw.isNullOrEmpty()) return Result("", 0)
        val trimmed = raw.trim()
        var removed = raw.length - trimmed.length
        val sb = StringBuilder(trimmed.length)
        for (c in trimmed) {
            if (c.code in 0x21..0x7E) {
                sb.append(c)
            } else {
                removed++
            }
        }
        return Result(sb.toString(), removed)
    }

    /** True when the value can be placed in an HTTP header verbatim. */
    fun isHeaderSafe(value: String): Boolean {
        return value.all { it.code in 0x20..0x7E }
    }
}

/**
 * Maps low-level provider exceptions to messages that are safe to show.
 * Raw OkHttp header-validation strings (e.g. "Unexpected char 0x628 at 7 in
 * Authorization value") must never surface in the UI; they are translated to
 * [ApiKeySanitizer.INVALID_KEY_MESSAGE]. Provider HTTP errors ("HTTP 404: …",
 * "API Error (429): …") are real, user-actionable information and pass
 * through unchanged.
 */
object ProviderErrors {
    fun userMessage(e: Throwable): String {
        val raw = e.message.orEmpty()
        if (
            raw.contains("Unexpected char", ignoreCase = true) ||
            raw.contains("in Authorization value", ignoreCase = true) ||
            raw.contains("in header", ignoreCase = true) && raw.contains("char", ignoreCase = true)
        ) {
            return ApiKeySanitizer.INVALID_KEY_MESSAGE
        }
        // Compose/coroutine lifecycle internals are never user-facing copy.
        if (
            raw.contains("coroutine scope", ignoreCase = true) ||
            raw.contains("left the composition", ignoreCase = true) ||
            e is kotlinx.coroutines.CancellationException
        ) {
            return "That action was interrupted before it finished. Please try again."
        }
        return raw.ifBlank { "Unknown provider error (${e.javaClass.simpleName})" }
    }

    /**
     * One-line, human-readable summary of a provider failure for list/detail
     * UIs (task cards, chat notices). Raw provider bodies — especially nested
     * JSON such as OpenRouter's `{"error":{"message":"Provider returned
     * error", "metadata":{"raw":"{...inner Cohere error...}"}}}` — are parsed
     * down to the innermost human message. The full raw text belongs in the
     * task's audit trail, never on a card.
     */
    fun errorSummary(e: Throwable): String = summarizeErrorText(e.message.orEmpty())

    fun summarizeErrorText(raw: String): String {
        var text = raw.trim()
        if (text.isBlank()) return "Unknown provider error"

        // Our wrappers prefix errors ("Task failed: ", "API Error (400): ",
        // "HTTP 400: ", ...). Peel prefixes, remembering any HTTP status code.
        var httpCode: Int? = null
        while (true) {
            val codeMatch = Regex("""^(?:API Error|Anthropic Error|Gemini Error|HTTP) \((\d{3})\)\s*:\s*""").find(text)
                ?: Regex("""^HTTP (\d{3})\s*:\s*""").find(text)
            if (codeMatch != null) {
                httpCode = codeMatch.groupValues[1].toIntOrNull() ?: httpCode
                text = text.substring(codeMatch.range.last + 1).trim()
                continue
            }
            val prefixMatch = Regex("""^(Task failed|Error)\s*:\s*""").find(text)
            if (prefixMatch != null) {
                text = text.substring(prefixMatch.range.last + 1).trim()
                continue
            }
            break
        }

        // If the remainder is (or contains) a JSON error body, dig out the
        // deepest "message" — routers wrap the upstream error, whose own
        // "message"/"raw" fields can themselves be JSON strings.
        var message = text
        var depth = 0
        while (depth < 5) {
            val jsonStart = message.indexOf('{')
            if (jsonStart < 0) break
            val parsed = try {
                org.json.JSONObject(message.substring(jsonStart))
            } catch (ignored: Exception) {
                null
            } ?: break
            val inner = deepestErrorMessage(parsed)
            if (inner.isNullOrBlank()) break
            message = inner.trim()
            depth++
        }

        message = message.replace(Regex("\\s+"), " ").trim()
        if (message.length > 220) message = message.take(217).trimEnd() + "…"
        if (message.isBlank()) message = "Request rejected by the provider"
        return if (httpCode != null) "Provider error ($httpCode): $message" else message
    }

    /**
     * Shown on the task card when the provider's usage limit is hit
     * (HTTP 429 — e.g. OpenRouter's free-models-per-day cap). Roman
     * Urdu/English mix matching the app's user-facing copy for Al.
     */
    const val RATE_LIMIT_SUMMARY =
        "Provider limit: free models ki rozana limit khatam ho gayi hai — kal reset hogi, ya doosra model/profile try karo"

    /** True when [e] is a provider rate-limit / quota failure (HTTP 429). */
    fun isRateLimitError(e: Throwable): Boolean {
        val raw = e.message.orEmpty()
        return raw.contains("429") ||
            raw.contains("rate limit", ignoreCase = true) ||
            raw.contains("Rate limit exceeded", ignoreCase = true) ||
            raw.contains("free-models-per-day", ignoreCase = true) ||
            raw.contains("quota", ignoreCase = true)
    }

    /**
     * True when [e] means "the provider answered but produced nothing" —
     * the retryable empty-response case. Covers the typed
     * [ProviderEmptyResponseException] and the raw org.json failure text
     * ("No value for choices") other adapters can leak when a response has
     * no choices/content array at all.
     */
    fun isEmptyResponseError(e: Throwable): Boolean {
        if (e is ProviderEmptyResponseException) return true
        val raw = e.message.orEmpty()
        return raw.contains("No value for choices", ignoreCase = true) ||
            raw.contains("no choices", ignoreCase = true) ||
            raw.contains("empty response", ignoreCase = true) ||
            raw.contains("returned no content", ignoreCase = true)
    }

    /** Walks error -> message / metadata.raw chains to the innermost text. */
    private fun deepestErrorMessage(json: org.json.JSONObject): String? {
        val errorObj = json.optJSONObject("error") ?: json
        var candidate: String? = null
        val msg = errorObj.optString("message").ifBlank { json.optString("message") }
        if (msg.isNotBlank()) candidate = msg
        val rawField = errorObj.optJSONObject("metadata")?.optString("raw")
            ?: errorObj.optString("raw")
        if (!rawField.isNullOrBlank()) {
            // The upstream body beats the router's own generic message.
            candidate = rawField
        }
        return candidate
    }
}
