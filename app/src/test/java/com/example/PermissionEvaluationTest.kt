package com.example

import com.example.nova.security.PermissionDecision
import com.example.nova.security.PermissionManager
import com.example.nova.security.PermissionMode
import com.example.nova.security.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PermissionEvaluationTest {
    private lateinit var permissionManager: PermissionManager

    @Before
    fun setup() {
        permissionManager = PermissionManager()
    }

    @Test
    fun testDomainNormalization() {
        assertEquals("github.com", PermissionManager.normalizeDomain("https://github.com/settings"))
        assertEquals("github.com", PermissionManager.normalizeDomain("http://www.github.com/profile"))
        assertEquals("google.com", PermissionManager.normalizeDomain("google.com"))
        assertEquals("example.org", PermissionManager.normalizeDomain("https://www.example.org/"))
    }

    @Test
    fun testNeverAllowBlocksExecution() {
        val request = PermissionRequest(
            action = "vault_autofill",
            domain = "https://untrusted-site.com",
            target = "secret-1",
            description = "Autofill password"
        )
        val decision = permissionManager.evaluate(request, secretMode = PermissionMode.NEVER_ALLOW)
        assertEquals(PermissionDecision.DENIED, decision)
    }

    @Test
    fun testAlwaysAllowGrantsPermission() {
        val request = PermissionRequest(
            action = "navigate",
            domain = "https://safe.org",
            target = "nav",
            description = "Navigate"
        )
        val decision = permissionManager.evaluate(request, secretMode = PermissionMode.ALWAYS_ALLOW)
        assertEquals(PermissionDecision.ALLOWED, decision)
    }

    @Test
    fun testAllowOnceIsConsumedImmediately() {
        val action = "click"
        val domain = "https://portal.example.com"
        val target = "button#login"

        val request = PermissionRequest(
            action = action,
            domain = domain,
            target = target,
            description = "Click submit button"
        )

        // Before grant: requires approval
        val decision1 = permissionManager.evaluate(request, secretMode = PermissionMode.ALLOW_ONCE)
        assertEquals(PermissionDecision.REQUIRES_USER_APPROVAL, decision1)

        // Grant one-time token
        permissionManager.grantOneTime(action, domain, target)

        // 1st check: Allowed
        val decision2 = permissionManager.evaluate(request, secretMode = PermissionMode.ALLOW_ONCE)
        assertEquals(PermissionDecision.ALLOWED, decision2)

        // 2nd check: Token consumed, must ask user again
        val decision3 = permissionManager.evaluate(request, secretMode = PermissionMode.ALLOW_ONCE)
        assertEquals(PermissionDecision.REQUIRES_USER_APPROVAL, decision3)
    }

    @Test
    fun testCriticalSecurityOrPaymentAlwaysRequiresApproval() {
        val request = PermissionRequest(
            action = "type",
            domain = "https://store.com",
            target = "input#credit_card",
            description = "Enter payment card",
            isCriticalSecurityOrPayment = true
        )
        // Even if mode is set to ALWAYS_ALLOW, security policy enforces approval for payment
        val decision = permissionManager.evaluate(request, secretMode = PermissionMode.ALWAYS_ALLOW)
        assertEquals(PermissionDecision.REQUIRES_USER_APPROVAL, decision)
    }
}
