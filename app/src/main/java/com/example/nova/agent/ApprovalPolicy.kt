package com.example.nova.agent

import com.example.nova.browser.PageElement
import org.json.JSONObject

/**
 * The risk class of one tool call. Only the consequential classes require
 * user approval; everything else executes without interrupting the task.
 */
enum class ApprovalClass {
    /** Pure reads / observations — never gated. */
    READ_ONLY,

    /** Ordinary reversible interactions (clicks, typing into normal fields). */
    ORDINARY,

    /** Submitting a form (search submits included only via explicit submit actions on real forms — see policy). */
    FORM_SUBMIT,

    /** Typing credentials/secrets, or injecting vault secrets into a page. */
    SENSITIVE_INPUT,

    /** Sending content out: posting, publishing, sharing, messaging. */
    POST_PUBLISH,

    /** Spending money: buy / pay / checkout / place order. */
    PURCHASE_PAYMENT,

    /** Deleting something (files, page items). */
    DELETION,

    /** Moving files across trust boundaries (downloads / uploads). */
    FILE_TRANSFER
}

/**
 * Risk-based approval policy for agent tool calls (fix round 2, Defect C).
 *
 * The old flow sent EVERY call through PermissionManager with no allow
 * rule on file, so even `get_page_title` — a pure read — paused the task
 * and demanded an approval, sometimes twice in one small task after the
 * resume-replay. This policy is the gate instead:
 *
 * - Read-only tools and ordinary navigation/typing NEVER require approval.
 * - Approval is required only for consequential classes: form submits,
 *   sensitive (password/payment) inputs, vault autofill, deletions,
 *   purchases/payments, posting/publishing/sending, file transfers.
 * - The unrecognized-action guardrail is separate (AgentLoop step 0) and
 *   unchanged — unknown tools never reach this policy.
 *
 * Pure and side-effect free: the target element (for index-based browser
 * tools) is passed in from the controller's last observation, so the whole
 * matrix is unit-testable on the JVM.
 */
object ApprovalPolicy {

    /** Tools that only read state. They can never change anything. */
    val READ_ONLY_TOOLS = setOf(
        // Browser reads
        "observe_page", "get_current_url", "get_page_title", "get_page_state",
        "wait_for_load", "take_screenshot", "get_visible_text", "find_element",
        "get_set_of_marks", "clear_set_of_marks",
        // Trained Task #1: read-only link collection (never submits/posts)
        "collect_facebook_post_links",
        "collect_screenshots",
        // Workspace / memory reads
        "file_list", "file_read", "file_search", "file_get_metadata",
        "memory_retrieve"
    )

    /** Browser navigation housekeeping: the agent's basic job, never gated. */
    val NAVIGATION_TOOLS = setOf(
        "navigate", "browser_back", "browser_forward", "browser_reload",
        "scroll", "scroll_down", "scroll_up", "scroll_to_element"
    )

    /** Classes that pause the task for the user's decision. */
    val APPROVAL_CLASSES = setOf(
        ApprovalClass.FORM_SUBMIT,
        ApprovalClass.SENSITIVE_INPUT,
        ApprovalClass.POST_PUBLISH,
        ApprovalClass.PURCHASE_PAYMENT,
        ApprovalClass.DELETION,
        ApprovalClass.FILE_TRANSFER
    )

    fun requiresApproval(toolName: String, arguments: JSONObject, element: PageElement?): Boolean =
        classify(toolName, arguments, element) in APPROVAL_CLASSES

    fun classify(toolName: String, arguments: JSONObject, element: PageElement?): ApprovalClass {
        if (toolName in READ_ONLY_TOOLS) return ApprovalClass.READ_ONLY
        if (toolName in NAVIGATION_TOOLS) return ApprovalClass.ORDINARY
        return when (toolName) {
            "click_element" -> classifyClickTarget(
                haystack = listOfNotNull(
                    element?.label, element?.name, element?.placeholder,
                    element?.type, element?.tag, element?.href
                ).joinToString(" ")
            )

            "click" -> classifyClickTarget(haystack = arguments.optString("selector"))

            "tap" -> ApprovalClass.ORDINARY

            "type_text" -> when {
                element?.type.equals("password", ignoreCase = true) -> ApprovalClass.SENSITIVE_INPUT
                isSensitiveFieldHint(element?.name, element?.label, element?.placeholder) ->
                    ApprovalClass.SENSITIVE_INPUT
                // A search box submit is the agent's bread and butter (the
                // "search Pakistan on Google" flow) — not a consequential
                // form submit. Real form submits (anything else) do gate.
                arguments.optBoolean("submit", false) && !isSearchField(element) ->
                    ApprovalClass.FORM_SUBMIT
                else -> ApprovalClass.ORDINARY
            }

            "type" -> {
                val selector = arguments.optString("selector")
                if (isSensitiveSelector(selector)) ApprovalClass.SENSITIVE_INPUT
                else ApprovalClass.ORDINARY
            }

            // Injecting stored credentials into a page is consequential even
            // though the model never sees the secret.
            "vault_autofill" -> ApprovalClass.SENSITIVE_INPUT

            "file_delete" -> ApprovalClass.DELETION

            // Workspace-local writes and memory saves are the agent's normal
            // work product; they stay ungated (unchanged behaviour).
            "file_write", "file_append", "file_create_directory",
            "memory_save", "task_record_artifact" -> ApprovalClass.ORDINARY

            else -> ApprovalClass.ORDINARY
        }
    }

    /**
     * Decides whether clicking a target with this label/selector text is
     * consequential. Order matters: destructive and payment intents are
     * checked before generic submit, so "Delete account" is a DELETION and
     * "Pay now" a PURCHASE, not a plain form submit.
     */
    private fun classifyClickTarget(haystack: String): ApprovalClass {
        val text = haystack.lowercase()
        if (text.isBlank()) return ApprovalClass.ORDINARY
        return when {
            containsAny(text, "delete", "remove forever", "destroy", "trash") ->
                ApprovalClass.DELETION
            containsAny(text, "buy", "pay now", "pay ", "checkout", "check out", "place order", "purchase", "donate") ->
                ApprovalClass.PURCHASE_PAYMENT
            containsAny(text, "post", "publish", "send", "share", "tweet", "comment") ->
                ApprovalClass.POST_PUBLISH
            containsAny(text, "submit", "confirm order", "save changes", "apply changes") ->
                ApprovalClass.FORM_SUBMIT
            else -> ApprovalClass.ORDINARY
        }
    }

    private fun isSearchField(element: PageElement?): Boolean {
        if (element == null) return false
        if (element.type.equals("search", ignoreCase = true)) return true
        val name = element.name?.lowercase().orEmpty()
        if (name == "q" || name == "query" || name == "search" || name.contains("search")) return true
        val text = listOfNotNull(element.label, element.placeholder).joinToString(" ").lowercase()
        return text.contains("search")
    }

    private fun isSensitiveSelector(selector: String): Boolean {
        val lower = selector.lowercase()
        return lower.contains("password") || lower.contains("passwd") ||
            lower.contains("card") || lower.contains("cvv") || lower.contains("cvc") ||
            lower.contains("pin") && lower.contains("input")
    }

    private fun isSensitiveFieldHint(vararg hints: String?): Boolean {
        val text = hints.filterNotNull().joinToString(" ").lowercase()
        if (text.isBlank()) return false
        return text.contains("password") || text.contains("card number") ||
            text.contains("cvv") || text.contains("cvc") || text.contains("otp") ||
            text.contains("one-time code") || text.contains("passcode")
    }

    private fun containsAny(text: String, vararg needles: String): Boolean =
        needles.any { text.contains(it) }
}
