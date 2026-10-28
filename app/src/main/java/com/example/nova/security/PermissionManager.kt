package com.example.nova.security

import java.net.URI

enum class PermissionDecision {
    ALLOWED,
    DENIED,
    REQUIRES_USER_APPROVAL
}

enum class PermissionMode(val label: String) {
    ALLOW_ONCE("Allow Once"),
    ALWAYS_ALLOW("Always Allow"),
    ASK_EVERY_TIME("Ask Every Time"),
    NEVER_ALLOW("Never Allow");

    companion object {
        fun fromString(value: String): PermissionMode {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: ASK_EVERY_TIME
        }
    }
}

data class PermissionRequest(
    val action: String,
    val domain: String?,
    val target: String,
    val description: String,
    val isCriticalSecurityOrPayment: Boolean = false
)

class PermissionManager {
    // Session-scoped temporary "ALLOW_ONCE" tokens
    private val oneTimeAuthorizations = mutableSetOf<String>()

    // Persistent in-memory cache of domain/action rules (backed by Room DB in repository)
    private val persistentRules = mutableMapOf<String, String>() // key: "$domain|$action", value: "ALLOW" or "DENY"

    companion object {
        fun normalizeDomain(urlOrDomain: String?): String {
            if (urlOrDomain.isNullOrBlank()) return "*"
            return try {
                val uri = if (!urlOrDomain.startsWith("http://") && !urlOrDomain.startsWith("https://")) {
                    URI("https://$urlOrDomain")
                } else {
                    URI(urlOrDomain)
                }
                val host = uri.host?.lowercase() ?: urlOrDomain.lowercase()
                // Strip common www. prefix for consistent matching
                if (host.startsWith("www.")) host.substring(4) else host
            } catch (e: Exception) {
                urlOrDomain.trim().lowercase()
            }
        }
    }

    fun registerRule(domain: String, action: String, policy: String) {
        val norm = normalizeDomain(domain)
        persistentRules["$norm|$action"] = policy.uppercase()
    }

    fun removeRule(domain: String, action: String) {
        val norm = normalizeDomain(domain)
        persistentRules.remove("$norm|$action")
    }

    fun grantOneTime(action: String, domain: String?, target: String) {
        val norm = normalizeDomain(domain)
        oneTimeAuthorizations.add("$norm|$action|$target")
    }

    fun revokeOneTime(action: String, domain: String?, target: String) {
        val norm = normalizeDomain(domain)
        oneTimeAuthorizations.remove("$norm|$action|$target")
    }

    /**
     * Evaluates permission for a requested action.
     * Payments and critical credential changes ALWAYS require explicit confirmation.
     */
    fun evaluate(
        request: PermissionRequest,
        secretMode: PermissionMode = PermissionMode.ASK_EVERY_TIME
    ): PermissionDecision {
        val normDomain = normalizeDomain(request.domain)
        val token = "$normDomain|${request.action}|${request.target}"

        // Critical safety rule: Payments and security changes can NEVER be silently automated
        if (request.isCriticalSecurityOrPayment) {
            return PermissionDecision.REQUIRES_USER_APPROVAL
        }

        // Check secret mode if defined
        when (secretMode) {
            PermissionMode.NEVER_ALLOW -> return PermissionDecision.DENIED
            PermissionMode.ALLOW_ONCE -> {
                if (oneTimeAuthorizations.contains(token)) {
                    // Consume the one-time token
                    oneTimeAuthorizations.remove(token)
                    return PermissionDecision.ALLOWED
                }
                return PermissionDecision.REQUIRES_USER_APPROVAL
            }
            PermissionMode.ALWAYS_ALLOW -> {
                // If scoped to domain, check match
                return PermissionDecision.ALLOWED
            }
            PermissionMode.ASK_EVERY_TIME -> {
                // Check if user already authorized in current session
                if (oneTimeAuthorizations.contains(token)) {
                    oneTimeAuthorizations.remove(token)
                    return PermissionDecision.ALLOWED
                }
            }
        }

        // Check persistent rules
        val ruleKey = "$normDomain|${request.action}"
        val wildcardKey = "*|${request.action}"
        val policy = persistentRules[ruleKey] ?: persistentRules[wildcardKey]

        return when (policy) {
            "ALLOW" -> PermissionDecision.ALLOWED
            "DENY" -> PermissionDecision.DENIED
            else -> PermissionDecision.REQUIRES_USER_APPROVAL
        }
    }
}
