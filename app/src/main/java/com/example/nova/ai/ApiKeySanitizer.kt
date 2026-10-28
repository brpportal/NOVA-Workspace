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
        return raw.ifBlank { "Unknown provider error (${e.javaClass.simpleName})" }
    }
}
