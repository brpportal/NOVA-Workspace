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

