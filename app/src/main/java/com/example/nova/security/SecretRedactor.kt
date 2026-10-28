package com.example.nova.security

object SecretRedactor {
    /**
     * Masks a secret value for UI display.
     * Keeps small hints if it looks like an API key or password, or returns bullets.
     */
    fun mask(secret: String): String {
        if (secret.isBlank()) return ""
        return if (secret.length > 8) {
            val prefix = secret.take(3)
            val suffix = secret.takeLast(4)
            "$prefix••••••••$suffix"
        } else {
            "••••••••"
        }
    }

    /**
     * Redacts known sensitive strings from arbitrary text (logs, prompts, UI previews).
     */
    fun redactText(text: String, knownSecrets: Collection<String>): String {
        if (text.isBlank() || knownSecrets.isEmpty()) return text
        var sanitized = text
        for (secret in knownSecrets) {
            if (secret.isNotBlank() && secret.length >= 3) {
                sanitized = sanitized.replace(secret, "[REDACTED_SECRET]")
            }
        }
        return sanitized
    }

    /**
     * Redacts common sensitive JSON key values in argument strings.
     */
    fun redactSecrets(json: String): String {
        if (json.isBlank()) return json
        return json.replace(Regex("(?i)\"(password|key|secret|token|api_key|credential)\"\\s*:\\s*\"[^\"]+\"")) { match ->
            val key = match.groupValues[1]
            "\"$key\":\"[REDACTED]\""
        }
    }

    /**
     * Creates a security audit line without ever exposing the sensitive payload.
     */
    fun auditEntry(action: String, secretName: String, domain: String?, success: Boolean): String {
        val domainInfo = if (!domain.isNullOrBlank()) " on domain [$domain]" else ""
        val status = if (success) "ALLOWED" else "DENIED"
        return "Vault Audit: Action [$action] for secret [$secretName]$domainInfo resulted in $status"
    }
}
