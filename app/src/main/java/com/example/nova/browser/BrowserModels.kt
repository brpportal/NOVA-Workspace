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
    PAUSED
}
