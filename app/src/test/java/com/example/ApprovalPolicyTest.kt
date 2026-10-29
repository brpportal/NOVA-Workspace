package com.example

import com.example.nova.agent.ApprovalClass
import com.example.nova.agent.ApprovalPolicy
import com.example.nova.browser.PageElement
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fix round 2, Defect C: the risk-based approval matrix.
 *
 * Regression target: Al's small Facebook task demanded approvals for
 * `get_page_title` — a pure read — twice in one task. Read-only tools and
 * ordinary interactions must NEVER gate; only consequential classes
 * (form submits, sensitive inputs, purchases, posts, deletions,
 * transfers) may pause a task.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApprovalPolicyTest {

    private fun args(json: String = "{}") = JSONObject(json)

    private fun element(
        tag: String = "button",
        type: String? = null,
        label: String = "",
        name: String? = null,
        placeholder: String? = null
    ) = PageElement(index = 1, tag = tag, type = type, label = label, name = name, placeholder = placeholder)

    @Test
    fun `read-only tools never require approval`() {
        val readOnly = listOf(
            "observe_page", "get_current_url", "get_page_title", "get_page_state",
            "wait_for_load", "take_screenshot", "get_visible_text", "find_element",
            "get_set_of_marks", "clear_set_of_marks",
            "file_list", "file_read", "file_search", "file_get_metadata", "memory_retrieve"
        )
        for (tool in readOnly) {
            assertEquals("$tool must classify READ_ONLY", ApprovalClass.READ_ONLY, ApprovalPolicy.classify(tool, args(), null))
            assertFalse("$tool must never require approval", ApprovalPolicy.requiresApproval(tool, args(), null))
        }
    }

    @Test
    fun `navigation and scrolling never require approval`() {
        for (tool in listOf(
            "navigate", "browser_back", "browser_forward", "browser_reload",
            "scroll", "scroll_down", "scroll_up", "scroll_to_element"
        )) {
            assertFalse("$tool must never require approval", ApprovalPolicy.requiresApproval(tool, args("""{"url":"https://example.com"}"""), null))
        }
    }

    @Test
    fun `ordinary clicks and typing never require approval`() {
        // A plain link/button click.
        assertFalse(ApprovalPolicy.requiresApproval("click_element", args(), element(label = "Dawat-e-Islami")))
        assertEquals(ApprovalClass.ORDINARY, ApprovalPolicy.classify("click_element", args(), element(label = "Open profile")))
        // Typing into an ordinary text field.
        assertFalse(
            ApprovalPolicy.requiresApproval(
                "type_text",
                args("""{"index":2,"text":"hello"}"""),
                element(tag = "input", type = "text", label = "Comment")
            )
        )
        // Typing into a search box WITH submit — the canonical Google
        // search flow — must not stall either.
        assertFalse(
            ApprovalPolicy.requiresApproval(
                "type_text",
                args("""{"index":1,"text":"Pakistan","submit":true}"""),
                element(tag = "input", type = "search", name = "q", label = "Search")
            )
        )
        // Selector-based ordinary click/type.
        assertFalse(ApprovalPolicy.requiresApproval("click", args("""{"selector":"a.profile-link"}"""), null))
        assertFalse(ApprovalPolicy.requiresApproval("type", args("""{"selector":"input[name=q]","text":"x"}"""), null))
        assertFalse(ApprovalPolicy.requiresApproval("tap", args("""{"x":10,"y":10}"""), null))
    }

    @Test
    fun `form submit buttons require approval`() {
        val cls = ApprovalPolicy.classify("click_element", args(), element(type = "submit", label = "Submit"))
        assertEquals(ApprovalClass.FORM_SUBMIT, cls)
        assertTrue(ApprovalPolicy.requiresApproval("click_element", args(), element(type = "submit", label = "Submit")))
        assertEquals(
            ApprovalClass.FORM_SUBMIT,
            ApprovalPolicy.classify("click", args("""{"selector":"button[type=submit]"}"""), null)
        )
        // Typing + submitting a real (non-search) form gates too.
        assertEquals(
            ApprovalClass.FORM_SUBMIT,
            ApprovalPolicy.classify(
                "type_text",
                args("""{"index":3,"text":"a@b.c","submit":true}"""),
                element(tag = "input", type = "email", label = "Email address")
            )
        )
    }

    @Test
    fun `password and sensitive fields require approval`() {
        assertEquals(
            ApprovalClass.SENSITIVE_INPUT,
            ApprovalPolicy.classify(
                "type_text",
                args("""{"index":4,"text":"secret"}"""),
                element(tag = "input", type = "password", label = "Password")
            )
        )
        assertTrue(
            ApprovalPolicy.requiresApproval(
                "type",
                args("""{"selector":"input#card_cvv","text":"123"}"""),
                null
            )
        )
        // Vault autofill injects credentials — consequential.
        assertEquals(
            ApprovalClass.SENSITIVE_INPUT,
            ApprovalPolicy.classify("vault_autofill", args("""{"secret_id":"s1"}"""), null)
        )
    }

    @Test
    fun `purchases posts and deletions require approval`() {
        assertEquals(
            ApprovalClass.PURCHASE_PAYMENT,
            ApprovalPolicy.classify("click_element", args(), element(label = "Buy now"))
        )
        assertEquals(
            ApprovalClass.PURCHASE_PAYMENT,
            ApprovalPolicy.classify("click", args("""{"selector":"#checkout-button"}"""), null)
        )
        assertEquals(
            ApprovalClass.POST_PUBLISH,
            ApprovalPolicy.classify("click_element", args(), element(label = "Publish post"))
        )
        assertEquals(
            ApprovalClass.POST_PUBLISH,
            ApprovalPolicy.classify("click_element", args(), element(label = "Send message"))
        )
        assertEquals(
            ApprovalClass.DELETION,
            ApprovalPolicy.classify("click_element", args(), element(label = "Delete account"))
        )
        assertEquals(
            ApprovalClass.DELETION,
            ApprovalPolicy.classify("file_delete", args("""{"path":"Documents/a.txt"}"""), null)
        )
    }

    @Test
    fun `workspace writes and memory saves stay ungated`() {
        for (tool in listOf("file_write", "file_append", "file_create_directory", "memory_save", "task_record_artifact")) {
            assertFalse("$tool must not require approval", ApprovalPolicy.requiresApproval(tool, args(), null))
        }
    }

    @Test
    fun `linux status and inspection commands stay ungated`() {
        assertEquals(
            ApprovalClass.READ_ONLY,
            ApprovalPolicy.classify("linux_status", args(), null)
        )
        assertFalse(ApprovalPolicy.requiresApproval("linux_status", args(), null))

        for (command in listOf(
            "ls -la /workspace",
            "pwd && uname -m",
            "git --version",
            "python3 -m pip --version",
            "cd /workspace && find . -name '*.py'"
        )) {
            val commandArgs = args("""{"command":"$command"}""")
            assertEquals(
                "$command must classify READ_ONLY",
                ApprovalClass.READ_ONLY,
                ApprovalPolicy.classify("linux_run", commandArgs, null)
            )
            assertFalse(
                "$command must not require approval",
                ApprovalPolicy.requiresApproval("linux_run", commandArgs, null)
            )
        }
    }

    @Test
    fun `linux changes and tool installs require approval`() {
        for (command in listOf(
            "rm -rf /workspace/tmp",
            "apt-get install -y git",
            "echo hello > /workspace/hello.txt",
            "python3 /workspace/script.py",
            "find /workspace -delete"
        )) {
            val commandArgs = args("""{"command":"$command"}""")
            assertEquals(
                "$command must classify SYSTEM_CHANGE",
                ApprovalClass.SYSTEM_CHANGE,
                ApprovalPolicy.classify("linux_run", commandArgs, null)
            )
            assertTrue(
                "$command must require approval",
                ApprovalPolicy.requiresApproval("linux_run", commandArgs, null)
            )
        }

        assertEquals(
            ApprovalClass.SYSTEM_CHANGE,
            ApprovalPolicy.classify("linux_install_tool", args("""{"tool":"git"}"""), null)
        )
        assertTrue(
            ApprovalPolicy.requiresApproval("linux_install_tool", args("""{"tool":"git"}"""), null)
        )
    }
}
