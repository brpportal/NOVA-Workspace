package com.example

import com.example.nova.agent.ToolRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolArgumentValidationTest {
    private lateinit var registry: ToolRegistry

    @Before
    fun setup() {
        registry = ToolRegistry()
    }

    @Test
    fun testValidToolWithRequiredParameters() {
        val res = registry.validateCall("navigate", """{"url":"https://example.com"}""")
        assertTrue(res.isValid)
    }

    @Test
    fun testMissingRequiredParameterFails() {
        val res = registry.validateCall("navigate", """{}""")
        assertFalse(res.isValid)
        assertTrue(res.errorMessage?.contains("url") == true)
    }

    @Test
    fun testFileWriteValidation() {
        // Valid
        val valid = registry.validateCall("file_write", """{"path":"Projects/test.txt","content":"hello"}""")
        assertTrue(valid.isValid)

        // Missing content
        val missingContent = registry.validateCall("file_write", """{"path":"Projects/test.txt"}""")
        assertFalse(missingContent.isValid)

        // Missing path
        val missingPath = registry.validateCall("file_write", """{"content":"hello"}""")
        assertFalse(missingPath.isValid)
    }

    @Test
    fun testUnknownToolReturnsInvalid() {
        val res = registry.validateCall("unknown_command", """{}""")
        assertFalse(res.isValid)
        assertTrue(res.errorMessage?.contains("Unknown tool") == true)
    }

    @Test
    fun testMalformedJsonReturnsInvalid() {
        val res = registry.validateCall("navigate", "not_a_valid_json")
        assertFalse(res.isValid)
    }

    @Test
    fun testSensitivityDetection() {
        assertTrue(registry.isSensitiveOperation("file_delete", """{"path":"test.txt"}"""))
        assertTrue(registry.isSensitiveOperation("vault_autofill", """{"secret_id":"123"}"""))
        assertTrue(registry.isSensitiveOperation("type", """{"selector":"input[name=password]","text":"secret"}"""))
        assertFalse(registry.isSensitiveOperation("get_visible_text", """{}"""))
    }
}
