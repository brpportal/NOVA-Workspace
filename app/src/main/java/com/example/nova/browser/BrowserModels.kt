package com.example.nova.browser

data class BrowserActionResult(
    val success: Boolean,
    val message: String,
    val data: String? = null,
    val error: String? = null
)

data class BrowserTabState(
    val id: String,
    val title: String = "New Tab",
    val url: String = "https://www.google.com",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isSecure: Boolean = true,
    val sslErrorDesc: String? = null
)

enum class BrowserControlMode {
    STANDBY,
    AGENT_ACTIVE,
    MANUAL_CONTROL,
    PAUSED,
    WAITING_APPROVAL
}

data class InteractiveElement(
    val id: Int,
    val tagName: String,
    val text: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val selector: String
)

data class AICursorState(
    val isVisible: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val isClicking: Boolean = false,
    val label: String = "AI"
)

data class AIKeystrokeState(
    val text: String = "",
    val timestamp: Long = 0L,
    val isVisible: Boolean = false
)

data class TeachModeAction(
    val type: String, // "NAVIGATE", "CLICK", "TYPE"
    val target: String,
    val value: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * One interactive element of the page, as extracted from the live DOM
 * (set-of-marks grounding). [index] is the number the model uses with
 * click_element / type_text; it is only valid for the page state it was
 * observed in. [label] is the best human-readable name available (visible
 * text, aria-label, placeholder, title, or name) — never a password value.
 */
data class PageElement(
    val index: Int,
    val tag: String,
    val type: String? = null,
    val label: String = "",
    val placeholder: String? = null,
    val name: String? = null,
    val href: String? = null,
    val currentValue: String? = null,
    val disabled: Boolean = false,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val inViewport: Boolean = true
)

/**
 * Where the viewport sits on the page (scroll addition): [scrollY] is the
 * current offset, [viewportHeight] one screen, [pageHeight] the full
 * document height. Pure value math — the human/model-facing summary and
 * the "is there more below" judgement live here so they are unit-testable
 * without a WebView.
 */
data class ScrollState(
    val scrollY: Int = 0,
    val viewportHeight: Int = 0,
    val pageHeight: Int = 0
) {
    /** True when content exists below the current viewport. */
    val canScrollDown: Boolean
        get() = pageHeight > 0 && viewportHeight > 0 && scrollY + viewportHeight < pageHeight - 8

    /** True when the page is scrolled away from the top. */
    val canScrollUp: Boolean
        get() = scrollY > 8

    /** Approximate position 0–100 (100 when the page fits on one screen). */
    val percentDown: Int
        get() {
            val scrollable = pageHeight - viewportHeight
            if (scrollable <= 0) return 100
            return ((scrollY.toDouble() / scrollable) * 100).toInt().coerceIn(0, 100)
        }

    /** One-line summary for tool results and observations. Empty when no real metrics exist. */
    fun describe(): String {
        if (pageHeight <= 0 || viewportHeight <= 0) return ""
        val tail = when {
            canScrollDown -> "more content below — scroll_down to keep reading"
            canScrollUp -> "at the bottom of the page"
            else -> "whole page fits on one screen"
        }
        return "Scroll: ~$percentDown% down (y=${scrollY}px of ${pageHeight}px) — $tail"
    }
}

/** A grounded snapshot of the current page for the agent's next decision. */
data class PageObservation(
    val url: String = "",
    val title: String = "",
    val elements: List<PageElement> = emptyList(),
    val textExcerpt: String = "",
    /** Viewport scroll position at observation time (0 when unknown). */
    val scrollY: Int = 0,
    val viewportHeight: Int = 0,
    val pageHeight: Int = 0,
    /** True when this is the cached copy of an unchanged page (fix round 2 loop economy). */
    val fromCache: Boolean = false,
    /** Set when no real page could be read at all — callers must treat this as an error, never as data. */
    val error: String? = null
) {
    /** Scroll metrics of this snapshot, for state summaries. */
    val scrollState: ScrollState
        get() = ScrollState(scrollY = scrollY, viewportHeight = viewportHeight, pageHeight = pageHeight)
    /** Compact text form fed to the model after browser actions. */
    fun formatForModel(maxElements: Int = 40, maxChars: Int = 6000): String {
        val sb = StringBuilder()
        sb.append("--- Page observation ---\n")
        if (fromCache) {
            sb.append("(page unchanged since the last observation — the indices and content below are still valid; act on them instead of observing again)\n")
        }
        sb.append("URL: ").append(url.ifBlank { "unknown" }).append('\n')
        sb.append("Title: ").append(title.ifBlank { "(none)" }).append('\n')
        val scrollLine = scrollState.describe()
        if (scrollLine.isNotBlank()) sb.append(scrollLine).append('\n')
        if (elements.isEmpty()) {
            sb.append("Interactive elements: none found on this page (it may still be loading, or be canvas/image-only — use take_screenshot and tap(x, y) if needed).\n")
        } else {
            sb.append("Interactive elements (use these indices with click_element / type_text; indices belong to THIS page state — re-observe after the page changes):\n")
            for (el in elements.take(maxElements)) {
                sb.append('[').append(el.index).append("] <").append(el.tag)
                if (!el.type.isNullOrBlank()) sb.append(" type=\"").append(el.type).append('"')
                sb.append('>')
                if (el.label.isNotBlank()) sb.append(" \"").append(el.label.take(60)).append('"')
                if (!el.placeholder.isNullOrBlank() && el.placeholder != el.label) {
                    sb.append(" placeholder=\"").append(el.placeholder.take(60)).append('"')
                }
                if (!el.name.isNullOrBlank()) sb.append(" name=\"").append(el.name).append('"')
                if (!el.currentValue.isNullOrBlank()) sb.append(" value=\"").append(el.currentValue.take(40)).append('"')
                if (!el.href.isNullOrBlank()) sb.append(" -> ").append(el.href.take(80))
                if (el.disabled) sb.append(" (disabled)")
                if (!el.inViewport) sb.append(" (off-screen, will scroll into view)")
                sb.append('\n')
            }
            if (elements.size > maxElements) {
                sb.append("… and ").append(elements.size - maxElements).append(" more elements (scroll and observe again to work with them).\n")
            }
        }
        if (textExcerpt.isNotBlank()) {
            sb.append("Page text excerpt:\n").append(textExcerpt.take(2000)).append('\n')
        }
        val out = sb.toString()
        return if (out.length > maxChars) out.take(maxChars) + "\n…(observation truncated)" else out
    }
}

