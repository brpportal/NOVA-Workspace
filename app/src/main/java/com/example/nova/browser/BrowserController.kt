package com.example.nova.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.ValueCallback
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.URI
import java.util.Locale
import kotlin.coroutines.resume

class BrowserController {
    private var webViewRef: WeakReference<WebView>? = null

    private val _controlMode = MutableStateFlow(BrowserControlMode.STANDBY)
    val controlMode: StateFlow<BrowserControlMode> = _controlMode.asStateFlow()

    private val _currentTabState = MutableStateFlow(BrowserTabState(id = "default"))
    val currentTabState: StateFlow<BrowserTabState> = _currentTabState.asStateFlow()

    private val _manualControlMessage = MutableStateFlow<String?>(null)
    val manualControlMessage: StateFlow<String?> = _manualControlMessage.asStateFlow()

    fun bindWebView(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    fun unbindWebView() {
        webViewRef = null
    }

    fun updateTabState(update: (BrowserTabState) -> BrowserTabState) {
        _currentTabState.value = update(_currentTabState.value)
    }

    fun updateControlMode(mode: BrowserControlMode) {
        _controlMode.value = mode
    }

    // --- MANUAL CONTROL & TAKE CONTROL LIFECYCLE ---

    fun takeManualControl(reason: String = "User took manual control (CAPTCHA/Login)") {
        _controlMode.value = BrowserControlMode.MANUAL_CONTROL
        _manualControlMessage.value = reason
    }

    suspend fun returnToAI(): BrowserActionResult {
        _controlMode.value = BrowserControlMode.AGENT_ACTIVE
        _manualControlMessage.value = null
        // Re-read current verified state
        val state = getPageState()
        return BrowserActionResult(
            success = true,
            message = "Control returned to AI agent. Current page re-validated.",
            data = state.data
        )
    }

    fun emergencyStop() {
        _controlMode.value = BrowserControlMode.STANDBY
        _manualControlMessage.value = "Emergency stop executed."
        withWebView { webView ->
            webView.stopLoading()
        }
    }

    // --- BROWSER NAVIGATION UTILITIES ---

    fun sanitizeUrl(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return "https://www.google.com"
        // Reject unsafe or restricted schemes
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:")) {
            return "https://www.google.com"
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        // If looks like a domain name with dot
        return if (trimmed.contains(".") && !trimmed.contains(" ")) {
            "https://$trimmed"
        } else {
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
        }
    }

    fun navigateDirect(url: String) {
        val safe = sanitizeUrl(url)
        withWebView { webView ->
            webView.loadUrl(safe)
        }
    }

    fun goBack() {
        withWebView { webView ->
            if (webView.canGoBack()) webView.goBack()
        }
    }

    fun goForward() {
        withWebView { webView ->
            if (webView.canGoForward()) webView.goForward()
        }
    }

    fun reload() {
        withWebView { webView ->
            webView.reload()
        }
    }

    fun stop() {
        withWebView { webView ->
            webView.stopLoading()
        }
    }

    // --- AUTOMATION ACTIONS FOR AGENT LOOP ---

    suspend fun getCurrentUrl(): BrowserActionResult = withContext(Dispatchers.Main) {
        val url = webViewRef?.get()?.url ?: _currentTabState.value.url
        BrowserActionResult(success = true, message = "Current URL retrieved", data = url)
    }

    suspend fun getPageTitle(): BrowserActionResult = withContext(Dispatchers.Main) {
        val title = webViewRef?.get()?.title ?: _currentTabState.value.title
        BrowserActionResult(success = true, message = "Page title retrieved", data = title)
    }

    suspend fun navigate(url: String, timeoutMs: Long = 15000): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "Action blocked: Browser is under MANUAL CONTROL by user.")
        }
        val safe = sanitizeUrl(url)
        return withContext(Dispatchers.Main) {
            val wv = webViewRef?.get() ?: return@withContext BrowserActionResult(false, "WebView not available")
            wv.loadUrl(safe)
            BrowserActionResult(success = true, message = "Navigated to $safe", data = safe)
        }
    }

    suspend fun goBackAction(): BrowserActionResult = withContext(Dispatchers.Main) {
        val wv = webViewRef?.get() ?: return@withContext BrowserActionResult(false, "WebView not available")
        if (wv.canGoBack()) {
            wv.goBack()
            BrowserActionResult(success = true, message = "Navigated back")
        } else {
            BrowserActionResult(false, "Cannot navigate back (history stack is empty)")
        }
    }

    suspend fun reloadAction(): BrowserActionResult = withContext(Dispatchers.Main) {
        val wv = webViewRef?.get() ?: return@withContext BrowserActionResult(false, "WebView not available")
        wv.reload()
        BrowserActionResult(success = true, message = "Page reloaded")
    }

    suspend fun getVisibleText(maxChars: Int = 8000): BrowserActionResult {
        val js = """
            (function() {
                try {
                    return document.body ? document.body.innerText.substring(0, $maxChars) : '';
                } catch(e) {
                    return '';
                }
            })();
        """.trimIndent()
        val raw = evaluateJs(js) ?: ""
        val clean = unescapeJsString(raw)
        return BrowserActionResult(success = true, message = "Extracted visible page text", data = clean)
    }

    suspend fun findElement(selector: String): BrowserActionResult {
        val escaped = JSONObject.quote(selector)
        val js = """
            (function() {
                try {
                    var el = document.querySelector($escaped);
                    if (!el) return JSON.stringify({found: false});
                    var rect = el.getBoundingClientRect();
                    return JSON.stringify({
                        found: true,
                        tagName: el.tagName,
                        id: el.id,
                        className: el.className,
                        text: el.innerText ? el.innerText.substring(0, 100) : '',
                        visible: rect.width > 0 && rect.height > 0,
                        x: Math.round(rect.left),
                        y: Math.round(rect.top)
                    });
                } catch(e) {
                    return JSON.stringify({found: false, error: e.toString()});
                }
            })();
        """.trimIndent()
        val res = evaluateJs(js) ?: "{}"
        val clean = unescapeJsString(res)
        return BrowserActionResult(success = clean.contains("\"found\":true"), message = "Element query executed", data = clean)
    }

    suspend fun click(selector: String): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "Blocked: Manual control active.")
        }
        val escaped = JSONObject.quote(selector)
        val js = """
            (function() {
                try {
                    var el = document.querySelector($escaped);
                    if (!el) return JSON.stringify({success: false, error: 'Element not found'});
                    el.scrollIntoView({behavior: 'instant', block: 'center'});
                    el.click();
                    return JSON.stringify({success: true});
                } catch(e) {
                    return JSON.stringify({success: false, error: e.toString()});
                }
            })();
        """.trimIndent()
        val raw = evaluateJs(js) ?: "{}"
        val clean = unescapeJsString(raw)
        val success = clean.contains("\"success\":true")
        return BrowserActionResult(success = success, message = if (success) "Clicked $selector" else "Failed to click $selector", data = clean)
    }

    suspend fun type(selector: String, text: String, clearFirst: Boolean = true): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "Blocked: Manual control active.")
        }
        val escapedSel = JSONObject.quote(selector)
        val escapedText = JSONObject.quote(text)
        val js = """
            (function() {
                try {
                    var el = document.querySelector($escapedSel);
                    if (!el) return JSON.stringify({success: false, error: 'Input element not found'});
                    el.scrollIntoView({behavior: 'instant', block: 'center'});
                    el.focus();
                    if ($clearFirst) { el.value = ''; }
                    el.value += $escapedText;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return JSON.stringify({success: true});
                } catch(e) {
                    return JSON.stringify({success: false, error: e.toString()});
                }
            })();
        """.trimIndent()
        val raw = evaluateJs(js) ?: "{}"
        val clean = unescapeJsString(raw)
        val success = clean.contains("\"success\":true")
        return BrowserActionResult(success = success, message = if (success) "Typed into $selector" else "Failed to type into $selector", data = clean)
    }

    suspend fun selectOption(selector: String, optionValue: String): BrowserActionResult {
        val escapedSel = JSONObject.quote(selector)
        val escapedVal = JSONObject.quote(optionValue)
        val js = """
            (function() {
                try {
                    var el = document.querySelector($escapedSel);
                    if (!el) return JSON.stringify({success: false, error: 'Select element not found'});
                    el.value = $escapedVal;
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return JSON.stringify({success: true});
                } catch(e) {
                    return JSON.stringify({success: false, error: e.toString()});
                }
            })();
        """.trimIndent()
        val raw = evaluateJs(js) ?: "{}"
        val clean = unescapeJsString(raw)
        return BrowserActionResult(success = clean.contains("\"success\":true"), message = "Option selection applied", data = clean)
    }

    suspend fun scroll(dx: Int, dy: Int): BrowserActionResult {
        val js = "window.scrollBy($dx, $dy);"
        evaluateJs(js)
        return BrowserActionResult(success = true, message = "Scrolled by ($dx, $dy)")
    }

    suspend fun getPageState(): BrowserActionResult {
        val js = """
            (function() {
                try {
                    var forms = document.querySelectorAll('form').length;
                    var inputs = document.querySelectorAll('input, textarea, select').length;
                    var buttons = document.querySelectorAll('button, input[type=submit]').length;
                    var links = document.querySelectorAll('a').length;
                    return JSON.stringify({
                        url: window.location.href,
                        title: document.title,
                        formsCount: forms,
                        inputsCount: inputs,
                        buttonsCount: buttons,
                        linksCount: links,
                        readyState: document.readyState
                    });
                } catch(e) {
                    return JSON.stringify({error: e.toString()});
                }
            })();
        """.trimIndent()
        val raw = evaluateJs(js) ?: "{}"
        val clean = unescapeJsString(raw)
        return BrowserActionResult(success = true, message = "Page state retrieved", data = clean)
    }

    suspend fun takeScreenshot(destinationDir: File): BrowserActionResult = withContext(Dispatchers.Main) {
        val wv = webViewRef?.get() ?: return@withContext BrowserActionResult(false, "WebView not available for capture")
        try {
            val width = wv.width.takeIf { it > 0 } ?: 1080
            val height = wv.height.takeIf { it > 0 } ?: 1920
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            wv.draw(canvas)

            destinationDir.mkdirs()
            val fileName = "screenshot_${System.currentTimeMillis()}.png"
            val file = File(destinationDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            bitmap.recycle()

            BrowserActionResult(
                success = true,
                message = "Screenshot captured to ${file.name}",
                data = "Images/${file.name}"
            )
        } catch (e: Exception) {
            BrowserActionResult(false, "Screenshot error: ${e.message}")
        }
    }

    /**
     * Injects credentials directly into form elements without passing raw credentials to the AI model.
     */
    suspend fun injectAutofill(
        usernameSelector: String?,
        username: String?,
        passwordSelector: String?,
        password: String?
    ): BrowserActionResult {
        val uSel = usernameSelector ?: "input[type=email], input[type=text], input[name*=user], input[name*=email]"
        val pSel = passwordSelector ?: "input[type=password], input[name*=pass]"

        val escapedUSel = JSONObject.quote(uSel)
        val escapedUVal = username?.let { JSONObject.quote(it) } ?: "null"
        val escapedPSel = JSONObject.quote(pSel)
        val escapedPVal = password?.let { JSONObject.quote(it) } ?: "null"

        val js = """
            (function() {
                try {
                    var uField = document.querySelector($escapedUSel);
                    var pField = document.querySelector($escapedPSel);
                    var filledUser = false;
                    var filledPass = false;

                    if (uField && $escapedUVal !== null) {
                        uField.value = $escapedUVal;
                        uField.dispatchEvent(new Event('input', { bubbles: true }));
                        uField.dispatchEvent(new Event('change', { bubbles: true }));
                        filledUser = true;
                    }
                    if (pField && $escapedPVal !== null) {
                        pField.value = $escapedPVal;
                        pField.dispatchEvent(new Event('input', { bubbles: true }));
                        pField.dispatchEvent(new Event('change', { bubbles: true }));
                        filledPass = true;
                    }
                    // NEVER auto-submit logins or payments
                    return JSON.stringify({
                        success: filledUser || filledPass,
                        filledUser: filledUser,
                        filledPass: filledPass
                    });
                } catch(e) {
                    return JSON.stringify({success: false, error: e.toString()});
                }
            })();
        """.trimIndent()

        val raw = evaluateJs(js) ?: "{}"
        val clean = unescapeJsString(raw)
        val success = clean.contains("\"success\":true")
        return BrowserActionResult(
            success = success,
            message = if (success) "Autofill applied securely to input fields (never auto-submitted)." else "Could not locate autofill target fields.",
            data = clean
        )
    }

    private suspend fun evaluateJs(script: String, timeoutMs: Long = 5000): String? {
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                val wv = webViewRef?.get() ?: return@withContext null
                suspendCancellableCoroutine { continuation ->
                    wv.evaluateJavascript(script) { result ->
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                }
            }
        }
    }

    private fun unescapeJsString(str: String): String {
        var s = str.trim()
        if (s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) {
            s = s.substring(1, s.length - 1)
            s = s.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
        }
        return s
    }

    private inline fun withWebView(action: (WebView) -> Unit) {
        val wv = webViewRef?.get()
        if (wv != null) {
            action(wv)
        }
    }
}
