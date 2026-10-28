package com.example

import com.example.nova.security.SecretRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactionTest {

    @Test
    fun testSecretMasking() {
        val shortSecret = "secret"
        assertEquals("••••••••", SecretRedactor.mask(shortSecret))

        val apiKey = "sk-proj-1234567890abcdef"
        val masked = SecretRedactor.mask(apiKey)
        assertTrue(masked.startsWith("sk-"))
        assertTrue(masked.contains("••••••••"))
        assertTrue(masked.endsWith("cdef"))
        assertFalse(masked.contains("1234567890"))
    }

    @Test
    fun testRedactTextRemovesKnownSecrets() {
        val rawPassword = "SuperSecretPassword123!"
        val rawToken = "ghp_998877665544332211"
        val logOutput = "Attempting connection with password $rawPassword and token $rawToken to server."

        val redacted = SecretRedactor.redactText(logOutput, listOf(rawPassword, rawToken))

        assertFalse(redacted.contains(rawPassword))
        assertFalse(redacted.contains(rawToken))
        assertTrue(redacted.contains("[REDACTED_SECRET]"))
    }

    @Test
    fun testAuditRecordExcludesSecretValue() {
        val audit = SecretRedactor.auditEntry(
            action = "autofill",
            secretName = "Work GitHub",
            domain = "github.com",
            success = true
        )

        assertTrue(audit.contains("Work GitHub"))
        assertTrue(audit.contains("github.com"))
        assertTrue(audit.contains("ALLOWED"))
        assertFalse(audit.contains("password"))
    }
}
