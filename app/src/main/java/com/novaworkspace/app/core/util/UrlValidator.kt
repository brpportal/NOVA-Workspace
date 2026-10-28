package com.novaworkspace.app.core.util

import java.net.URI
import java.util.Locale

/**
 * URL validation for the future embedded browser (Milestone 3).
 *
 * Rules (secure by default, per SPEC Sec. 6):
 * - Blank input is invalid.
 * - If no scheme is present, https is assumed.
 * - Only http and https schemes are accepted. HTTP is flagged via
 *   [ValidationResult.Valid.isHttp] so the UI can require explicit
 *   user approval before loading it.
 * - A host is required. Lookalike / malformed hosts are rejected by
 *   java.net.URI parsing plus a simple label check.
 */
object UrlValidator {

    sealed interface ValidationResult {
        data class Valid(val normalizedUrl: String, val isHttp: Boolean) : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    fun validate(rawInput: String): ValidationResult {
        val input = rawInput.trim()
        if (input.isEmpty()) return ValidationResult.Invalid("URL is blank")
        if (input.any { it.isWhitespace() }) return ValidationResult.Invalid("URL contains spaces")

        val withScheme = if (input.contains("://")) input else "https://$input"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return ValidationResult.Invalid("Malformed URL: ${e.message}")
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: return ValidationResult.Invalid("Missing scheme")
        if (scheme != "https" && scheme != "http") {
            return ValidationResult.Invalid("Unsupported scheme: $scheme")
        }

        val host = uri.host ?: return ValidationResult.Invalid("Missing or invalid host")
        if (!isPlausibleHost(host)) return ValidationResult.Invalid("Invalid host: $host")

        return ValidationResult.Valid(
            normalizedUrl = uri.toString(),
            isHttp = scheme == "http"
        )
    }

    private fun isPlausibleHost(host: String): Boolean {
        if (host.isBlank() || host.length > 253) return false
        if (host.startsWith(".") || host.endsWith(".")) return false
        if (host.contains("..")) return false
        // Allow localhost explicitly; otherwise require at least one dot OR a plain label host.
        return host.split(".").all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.all { it.isLetterOrDigit() || it == '-' } &&
                !label.startsWith("-") && !label.endsWith("-")
        }
    }
}
