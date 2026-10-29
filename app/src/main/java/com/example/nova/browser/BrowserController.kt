package com.example.nova.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.net.URI
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The ONE browser controller for the whole app.
 *
 * Fix round 2 (Defect A): this used to be created per-NovaRepository
 * (MainActivity built its own repository, the WorkManager worker used the
 * getInstance singleton — each with its OWN controller), and the only
 * WebView in existence was the one BrowserScreen composed. A chat-launched
 * task therefore drove a controller with no WebView at all: navigate /
 * reload / screenshot failed with "WebView not available", while the
 * JS-based reads substituted "{}" and reported SUCCESS with empty data.
 *
 * Now: [getInstance] is the single shared controller (wired into
 * NovaRepository), it OWNS a WebView created from the application context
 * on the main thread, created lazily the first time anything needs the
 * browser — no Browser tab visit required. The Browser screen re-parents
 * that very WebView into its container ([attachToDisplay] /
 * [detachFromDisplay]) so the user watches the page the agent drives, and
 * detaching never destroys it.
 */
class BrowserController(private val appContext: Context? = null) {

    companion object {
        /** Blocked-result message for agent browser actions under manual control. */
        const val USER_IN_CONTROL_MESSAGE = "User is in control of the browser"

        @Volatile
        private var INSTANCE: BrowserController? = null

        fun getInstance(context: Context): BrowserController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BrowserController(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private var webViewRef: WeakReference<WebView>? = null

    /** Strong reference to the controller-owned WebView (survives screen detach). */
    private var ownedWebView: WebView? = null

    /**
     * Invoked from the WebViewClient on every finished page load with
     * (title, url) for non-blank pages. NovaRepository wires this to its
     * browsing-history table so history works for agent-driven loads too,
     * not just loads that happened while the Browser screen was open.
     */
    @Volatile
    var onPageFinishedListener: ((title: String, url: String) -> Unit)? = null

    /**
     * The most recent MAIN-FRAME load error (WebViewClient.onReceivedError),
     * cleared whenever a new navigation starts. Deterministic automations
     * read this to tell an OPEN_FAILED page apart from a loaded one.
     */
    @Volatile
    var lastLoadError: LoadErrorInfo? = null
        private set

    /**
     * True after the WebView's renderer process died
     * (onRenderProcessGone). The dead view is detached and destroyed in
     * the callback; the next [obtainWebViewLocked] builds a fresh one.
     * Cleared when a fresh view is created or via [recreateWebView].
     */
    @Volatile
    var rendererCrashed: Boolean = false
        private set

    /** Most recent [observePage] result — click/type targets resolve against it. */
    @Volatile
    var lastObservation: PageObservation? = null
        private set

    /** Cache key (url + DOM size) of [lastObservation] for the unchanged-page shortcut. */
    @Volatile
    private var lastObservationKey: String? = null

    private val _controlMode = MutableStateFlow(BrowserControlMode.STANDBY)
    val controlMode: StateFlow<BrowserControlMode> = _controlMode.asStateFlow()

    private val _currentTabState = MutableStateFlow(BrowserTabState(id = "default"))
    val currentTabState: StateFlow<BrowserTabState> = _currentTabState.asStateFlow()

    private val _manualControlMessage = MutableStateFlow<String?>(null)
    val manualControlMessage: StateFlow<String?> = _manualControlMessage.asStateFlow()

    // AI Virtual Cursor & Keystroke live overlay
    private val _aiCursorState = MutableStateFlow(AICursorState())
    val aiCursorState: StateFlow<AICursorState> = _aiCursorState.asStateFlow()

    private val _aiKeystrokeState = MutableStateFlow(AIKeystrokeState())
    val aiKeystrokeState: StateFlow<AIKeystrokeState> = _aiKeystrokeState.asStateFlow()

    // Live Agent Plan & Reasoning Log for side panel
    private val _currentPlan = MutableStateFlow("Ready for autonomous task.")
    val currentPlan: StateFlow<String> = _currentPlan.asStateFlow()

    private val _currentStep = MutableStateFlow("Idle")
    val currentStep: StateFlow<String> = _currentStep.asStateFlow()

    private val _actionLogs = MutableStateFlow<List<String>>(emptyList())
    val actionLogs: StateFlow<List<String>> = _actionLogs.asStateFlow()

    // Teach Mode state
    private val _isTeachModeActive = MutableStateFlow(false)
    val isTeachModeActive: StateFlow<Boolean> = _isTeachModeActive.asStateFlow()

    private val _recordedActions = MutableStateFlow<List<TeachModeAction>>(emptyList())
    val recordedActions: StateFlow<List<TeachModeAction>> = _recordedActions.asStateFlow()

    fun bindWebView(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    fun unbindWebView() {
        webViewRef = null
    }

    // --- OWNED WEBVIEW LIFECYCLE (Defect A) ---

    /**
     * Returns the live WebView, creating the controller-owned one first if
     * needed. MUST be called on the main thread. Returns null only when the
     * controller was built without a context (unit tests) and nothing was
     * bound via [bindWebView].
     */
    private fun obtainWebViewLocked(): WebView? {
        webViewRef?.get()?.let { return it }
        val ctx = appContext ?: return null
        // A fresh view replaces any renderer-crashed predecessor.
        rendererCrashed = false
        val wv = createConfiguredWebView(ctx)
        ownedWebView = wv
        webViewRef = WeakReference(wv)
        // Give the offscreen view a real viewport so layout-dependent DOM
        // reads (bounding rects, visibility) and screenshots have pixels to
        // work with before the Browser screen ever attaches it.
        if (wv.width == 0 || wv.height == 0) {
            val widthSpec = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
            val heightSpec = android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
            wv.measure(widthSpec, heightSpec)
            wv.layout(0, 0, 1080, 1920)
        }
        val initialUrl = _currentTabState.value.url
        if (initialUrl.isNotBlank()) {
            wv.loadUrl(initialUrl)
        }
        return wv
    }

    /** Suspend entry point: guarantees a live WebView on the main thread. */
    suspend fun ensureWebView(): Boolean = withContext(Dispatchers.Main) {
        obtainWebViewLocked() != null
    }

    /**
     * Destroys the current WebView (if any) and builds a fresh one — the
     * recovery path after a renderer crash (onRenderProcessGone). Returns
     * true when a live replacement exists afterwards. If the destroyed
     * view was on display in the Browser screen, the screen re-attaches
     * the replacement on its next recomposition.
     */
    suspend fun recreateWebView(): Boolean = withContext(Dispatchers.Main) {
        try {
            val old = ownedWebView ?: webViewRef?.get()
            if (old != null) {
                (old.parent as? ViewGroup)?.removeView(old)
                old.destroy()
            }
        } catch (ignored: Exception) {}
        ownedWebView = null
        webViewRef = null
        lastLoadError = null
        rendererCrashed = false
        obtainWebViewLocked() != null
    }

    /**
     * All anchor hrefs of the current page (absolute, DOM order), for
     * deterministic link collectors. Null when no page could be read.
     */
    suspend fun extractAnchorHrefs(): List<String>? {
        val raw = evaluateJs(ANCHORS_JS) ?: return null
        return try {
            val arr = JSONArray(unescapeJsString(raw))
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val h = arr.optString(i)
                if (h.isNotBlank()) out.add(h)
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The page-probe JSON ({url, title, hasPassword, hasLoginForm, text})
     * consumed by FacebookPostLinks.signalsFromProbeJson. Null when no
     * page could be read at all.
     */
    suspend fun extractPageProbeJson(): String? {
        val raw = evaluateJs(PAGE_PROBE_JS) ?: return null
        val clean = unescapeJsString(raw)
        return if (clean.isBlank() || clean == "null") null else clean
    }

    /** The WebView for agent actions: ensures existence, null only if impossible. */
    private suspend fun liveWebView(): WebView? = withContext(Dispatchers.Main) {
        obtainWebViewLocked()
    }

    /**
     * Parents the shared WebView into the Browser screen's container so the
     * user watches the exact page the agent drives. Main thread only.
     */
    fun attachToDisplay(container: ViewGroup) {
        val wv = obtainWebViewLocked() ?: return
        if (wv.parent === container) return
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(
            wv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    /**
     * Removes the shared WebView from its display container WITHOUT
     * destroying it — the agent keeps its page when the user leaves the
     * Browser screen. Main thread only.
     */
    fun detachFromDisplay(container: ViewGroup) {
        val wv = webViewRef?.get() ?: return
        if (wv.parent === container) {
            container.removeView(wv)
        }
    }

    /** The element with [index] from the most recent observation, or null. */
    fun observedElement(index: Int): PageElement? =
        lastObservation?.elements?.firstOrNull { it.index == index }

    /** Drops the observation cache (page is about to change). */
    fun invalidateObservationCache() {
        lastObservationKey = null
    }

    /**
     * Builds the shared WebView with the exact settings and clients the
     * Browser screen used to install on its own throwaway instance, so
     * behaviour (tab state, progress, SSL handling) is identical whether or
     * not the screen is open.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(ctx: Context): WebView {
        val wv = WebView(ctx)
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 Spark/1.0"
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // A new navigation supersedes any previous load error.
                lastLoadError = null
                updateTabState {
                    it.copy(
                        isLoading = true,
                        url = url ?: it.url,
                        canGoBack = view?.canGoBack() == true,
                        canGoForward = view?.canGoForward() == true,
                        isSecure = url?.startsWith("https://") == true
                    )
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val title = view?.title ?: "Webpage"
                updateTabState {
                    it.copy(
                        isLoading = false,
                        title = title,
                        url = url ?: it.url,
                        canGoBack = view?.canGoBack() == true,
                        canGoForward = view?.canGoForward() == true
                    )
                }
                if (!url.isNullOrBlank() && url != "about:blank") {
                    try {
                        onPageFinishedListener?.invoke(title, url)
                    } catch (ignored: Exception) {}
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                updateTabState {
                    it.copy(
                        isSecure = false,
                        sslErrorDesc = "The webpage certificate could not be verified (${error?.primaryError}). Connection cancelled for your protection."
                    )
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                // Main-frame failures only: subresource errors (a broken
                // image, a blocked tracker) must not condemn the page.
                if (request?.isForMainFrame == true) {
                    lastLoadError = LoadErrorInfo(
                        errorCode = error?.errorCode ?: -1,
                        description = error?.description?.toString() ?: "Unknown load error",
                        failingUrl = request.url?.toString() ?: "",
                        atMs = System.currentTimeMillis()
                    )
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // The renderer process died: this WebView instance is
                // unusable. Detach + destroy it so the next obtain builds
                // a fresh one, and flag the crash for the caller (the
                // trained collector maps this to FB_ERROR recovery).
                rendererCrashed = true
                try {
                    if (view != null) {
                        (view.parent as? ViewGroup)?.removeView(view)
                        if (ownedWebView === view) ownedWebView = null
                        if (webViewRef?.get() === view) webViewRef = null
                        view.destroy()
                    }
                } catch (ignored: Exception) {}
                updateTabState { it.copy(isLoading = false) }
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                updateTabState {
                    it.copy(progress = newProgress, isLoading = newProgress < 100)
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    updateTabState { it.copy(title = title) }
                }
            }
        }
        return wv
    }

    fun updateTabState(update: (BrowserTabState) -> BrowserTabState) {
        _currentTabState.value = update(_currentTabState.value)
    }

    fun updateControlMode(mode: BrowserControlMode) {
        _controlMode.value = mode
    }

    fun setPlanAndStep(plan: String, step: String) {
        _currentPlan.value = plan
        _currentStep.value = step
    }

    fun logAction(entry: String) {
        val current = _actionLogs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 50) current.removeAt(current.size - 1)
        _actionLogs.value = current
    }

    fun updateCursor(x: Float, y: Float, isClicking: Boolean, label: String = "AI") {
        _aiCursorState.value = AICursorState(isVisible = true, x = x, y = y, isClicking = isClicking, label = label)
    }

    fun hideCursor() {
        _aiCursorState.value = AICursorState(isVisible = false)
    }

    fun showKeystrokes(text: String) {
        _aiKeystrokeState.value = AIKeystrokeState(text = text, timestamp = System.currentTimeMillis(), isVisible = true)
    }

    fun hideKeystrokes() {
        _aiKeystrokeState.value = AIKeystrokeState(isVisible = false)
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
        val wv = obtainWebViewLocked()
        val url = wv?.url ?: _currentTabState.value.url
        BrowserActionResult(success = true, message = "Current URL retrieved", data = url)
    }

    suspend fun getPageTitle(): BrowserActionResult = withContext(Dispatchers.Main) {
        val wv = obtainWebViewLocked()
        val title = wv?.title ?: _currentTabState.value.title
        BrowserActionResult(success = true, message = "Page title retrieved", data = title)
    }

    suspend fun navigate(url: String, timeoutMs: Long = 15000): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "$USER_IN_CONTROL_MESSAGE — the agent cannot navigate until control is handed back.")
        }
        val safe = sanitizeUrl(url)
        return withContext(Dispatchers.Main) {
            val wv = obtainWebViewLocked() ?: return@withContext BrowserActionResult(false, "WebView not available")
            invalidateObservationCache()
            wv.loadUrl(safe)
            BrowserActionResult(success = true, message = "Navigated to $safe", data = safe)
        }
    }

    suspend fun goBackAction(): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "$USER_IN_CONTROL_MESSAGE — the agent cannot navigate until control is handed back.")
        }
        return withContext(Dispatchers.Main) {
            val wv = obtainWebViewLocked() ?: return@withContext BrowserActionResult(false, "WebView not available")
            if (wv.canGoBack()) {
                invalidateObservationCache()
                wv.goBack()
                BrowserActionResult(success = true, message = "Navigated back")
            } else {
                BrowserActionResult(false, "Cannot navigate back (history stack is empty)")
            }
        }
    }

    suspend fun goForwardAction(): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "$USER_IN_CONTROL_MESSAGE — the agent cannot navigate until control is handed back.")
        }
        return withContext(Dispatchers.Main) {
            val wv = obtainWebViewLocked() ?: return@withContext BrowserActionResult(false, "WebView not available")
            if (wv.canGoForward()) {
                invalidateObservationCache()
                wv.goForward()
                BrowserActionResult(success = true, message = "Navigated forward")
            } else {
                BrowserActionResult(false, "Cannot navigate forward (history stack is empty)")
            }
        }
    }

    /**
     * Polls document.readyState until "complete" or the timeout elapses.
     * Honest results (fix round 2): a missing WebView, or a readyState that
     * never becomes readable, is an ERROR — the old code reported SUCCESS
     * with "readyState=unknown" for a browser that did not exist.
     */
    suspend fun waitForLoad(timeoutMs: Long = 8000): BrowserActionResult {
        if (liveWebView() == null) {
            return BrowserActionResult(false, "WebView not available — the browser could not be started")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var state = ""
        while (System.currentTimeMillis() < deadline) {
            val raw = evaluateJs("document.readyState")
            if (raw == null) {
                return BrowserActionResult(false, "WebView not available — the page state could not be read")
            }
            state = unescapeJsString(raw)
            if (state == "complete") {
                return BrowserActionResult(success = true, message = "Page finished loading (readyState=complete)")
            }
            delay(250)
        }
        if (state.isBlank()) {
            return BrowserActionResult(false, "Page load state could not be determined (no readable readyState)")
        }
        return BrowserActionResult(
            success = true,
            message = "Waited ${timeoutMs}ms for page load (readyState=$state)"
        )
    }

    /**
     * Set-of-marks observation: waits briefly for the page to settle, then
     * extracts the interactive elements from the live DOM. Each element gets
     * a stable [PageElement.index] (stamped into the DOM as data-spark-idx)
     * that click_element / type_text act on. Pure parsing lives in
     * [PageElementsParser].
     *
     * Loop economy (fix round 2): a cheap probe (URL + DOM size) keys the
     * last observation — when the page is unchanged, the cached observation
     * is returned with `fromCache = true` instead of re-extracting.
     *
     * Honesty (fix round 2): when no live page can be read at all, the
     * returned observation carries [PageObservation.error] instead of a
     * plausible-looking empty SUCCESS ("URL: unknown").
     */
    suspend fun observePage(): PageObservation {
        if (liveWebView() == null) {
            return PageObservation(error = "WebView not available — the browser could not be started")
        }
        waitForLoad(3500)

        // Cheap change probe before paying for a full extraction.
        val probeRaw = evaluateJs(PROBE_JS)
        if (probeRaw != null) {
            try {
                val probe = JSONObject(unescapeJsString(probeRaw))
                val key = PageElementsParser.cacheKey(
                    probe.optString("url"),
                    probe.optInt("domLength", -1)
                )
                val cached = lastObservation
                if (cached != null && key == lastObservationKey && probe.optInt("domLength", -1) >= 0) {
                    return cached.copy(fromCache = true)
                }
            } catch (ignored: Exception) {}
        }

        val raw = evaluateJs(OBSERVE_JS)
            ?: return PageObservation(error = "Browser page not loaded yet — the page could not be read")
        val observation = PageElementsParser.parse(unescapeJsString(raw))
        if (observation.url.isBlank() && observation.elements.isEmpty() && observation.textExcerpt.isBlank()) {
            return PageObservation(error = "Browser page not loaded yet — no page content could be read")
        }
        lastObservation = observation
        // Refresh the cache key from the extracted page itself so the next
        // probe compares against what was actually observed.
        val probeRaw2 = evaluateJs(PROBE_JS)
        if (probeRaw2 != null) {
            try {
                val probe = JSONObject(unescapeJsString(probeRaw2))
                lastObservationKey = PageElementsParser.cacheKey(
                    probe.optString("url"),
                    probe.optInt("domLength", -1)
                )
            } catch (ignored: Exception) {
                lastObservationKey = null
            }
        } else {
            lastObservationKey = null
        }
        return observation
    }

    /** Clicks the element with the given observation index. */
    suspend fun clickElement(index: Int): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "$USER_IN_CONTROL_MESSAGE — the agent cannot act until control is handed back.")
        }
        animateCursorToElement(index)
        val js = """
            (function() {
                try {
                    var el = document.querySelector('[data-spark-idx="$index"]');
                    if (!el) return JSON.stringify({success: false, error: 'Element $index not found on this page. The page changed — call observe_page for fresh indices.'});
                    el.scrollIntoView({behavior: 'instant', block: 'center'});
                    if (el.focus) { try { el.focus({preventScroll: true}); } catch(e) {} }
                    el.dispatchEvent(new MouseEvent('mousedown', {bubbles: true, cancelable: true}));
                    el.dispatchEvent(new MouseEvent('mouseup', {bubbles: true, cancelable: true}));
                    el.click();
                    var label = (el.innerText || el.getAttribute('aria-label') || el.placeholder || el.name || '').trim();
                    return JSON.stringify({success: true, tag: el.tagName, label: label.substring(0, 60)});
                } catch(e) {
                    return JSON.stringify({success: false, error: e.toString()});
                }
            })();
        """.trimIndent()
        val raw = evaluateJs(js) ?: "{}"
        val clean = unescapeJsString(raw)
        val success = clean.contains("\"success\":true")
        if (success) invalidateObservationCache()
        logAction(if (success) "AI clicked element [$index]" else "AI failed click on element [$index]")
        delay(100)
        hideCursor()
        return BrowserActionResult(
            success = success,
            message = if (success) "Clicked element [$index]" else "Failed to click element [$index]",
            data = clean
        )
    }

    /**
     * Types into the element with the given observation index using the
     * native value setter, so React-style controlled inputs actually
     * register the change (a plain `el.value = ...` assignment is invisible
     * to React's value tracking and the form stays empty). Optionally
     * submits: Enter key events plus form requestSubmit/submit.
     */
    suspend fun typeText(index: Int, text: String, clearFirst: Boolean = true, submit: Boolean = false): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "$USER_IN_CONTROL_MESSAGE — the agent cannot act until control is handed back.")
        }
        showKeystrokes(text)
        val escapedText = JSONObject.quote(text)
        val js = """
            (function() {
                try {
                    var el = document.querySelector('[data-spark-idx="$index"]');
                    if (!el) return JSON.stringify({success: false, error: 'Element $index not found on this page. The page changed — call observe_page for fresh indices.'});
                    el.scrollIntoView({behavior: 'instant', block: 'center'});
                    el.focus();
                    var newVal = '';
                    if (el.isContentEditable) {
                        document.execCommand('selectAll', false, null);
                        document.execCommand('insertText', false, $escapedText);
                        newVal = el.innerText || '';
                    } else {
                        var proto = el instanceof HTMLTextAreaElement ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                        var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                        var base = $clearFirst ? '' : (el.value || '');
                        newVal = base + $escapedText;
                        if (desc && desc.set) { desc.set.call(el, newVal); } else { el.value = newVal; }
                        el.dispatchEvent(new Event('input', {bubbles: true}));
                        el.dispatchEvent(new Event('change', {bubbles: true}));
                    }
                    var submitted = false;
                    if ($submit) {
                        var opts = {bubbles: true, cancelable: true, key: 'Enter', code: 'Enter', keyCode: 13, which: 13};
                        el.dispatchEvent(new KeyboardEvent('keydown', opts));
                        el.dispatchEvent(new KeyboardEvent('keypress', opts));
                        el.dispatchEvent(new KeyboardEvent('keyup', opts));
                        var form = el.form || (el.closest ? el.closest('form') : null);
                        if (form) {
                            if (form.requestSubmit) { form.requestSubmit(); } else { form.submit(); }
                            submitted = true;
                        }
                    }
                    var shown = el.type === 'password' ? '(hidden)' : (el.value || newVal || '').substring(0, 60);
                    return JSON.stringify({success: true, value: shown, submitted: submitted});
                } catch(e) {
                    return JSON.stringify({success: false, error: e.toString()});
                }
            })();
        """.trimIndent()
        val raw = evaluateJs(js) ?: "{}"
        val clean = unescapeJsString(raw)
        val success = clean.contains("\"success\":true")
        if (success && submit) invalidateObservationCache()
        logAction(if (success) "AI typed into element [$index]: \"$text\"" else "AI failed typing into element [$index]")
        delay(300)
        hideKeystrokes()
        return BrowserActionResult(
            success = success,
            message = if (success) "Typed into element [$index]" else "Failed to type into element [$index]",
            data = clean
        )
    }

    /** Coordinate fallback: taps whatever element is at viewport (x, y). */
    suspend fun tapAt(x: Int, y: Int): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "$USER_IN_CONTROL_MESSAGE — the agent cannot act until control is handed back.")
        }
        updateCursor(x.toFloat(), y.toFloat(), isClicking = false)
        delay(120)
        updateCursor(x.toFloat(), y.toFloat(), isClicking = true)
        delay(80)
        val js = """
            (function() {
                try {
                    var el = document.elementFromPoint($x, $y);
                    if (!el) return JSON.stringify({success: false, error: 'No element at ($x, $y)'});
                    el.dispatchEvent(new MouseEvent('mousedown', {bubbles: true, cancelable: true}));
                    el.dispatchEvent(new MouseEvent('mouseup', {bubbles: true, cancelable: true}));
                    el.click();
                    var label = (el.innerText || el.getAttribute('aria-label') || '').trim();
                    return JSON.stringify({success: true, tag: el.tagName, label: label.substring(0, 60)});
                } catch(e) {
                    return JSON.stringify({success: false, error: e.toString()});
                }
            })();
        """.trimIndent()
        val raw = evaluateJs(js) ?: "{}"
        val clean = unescapeJsString(raw)
        val success = clean.contains("\"success\":true")
        if (success) invalidateObservationCache()
        logAction(if (success) "AI tapped at ($x, $y)" else "AI failed tap at ($x, $y)")
        delay(100)
        hideCursor()
        return BrowserActionResult(
            success = success,
            message = if (success) "Tapped at ($x, $y)" else "Failed to tap at ($x, $y)",
            data = clean
        )
    }

    private suspend fun animateCursorToElement(index: Int) {
        val coordJs = """
            (function() {
                try {
                    var el = document.querySelector('[data-spark-idx="$index"]');
                    if (!el) return null;
                    var r = el.getBoundingClientRect();
                    return JSON.stringify({ x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) });
                } catch(e) { return null; }
            })();
        """.trimIndent()
        val coordRaw = evaluateJs(coordJs)
        if (!coordRaw.isNullOrBlank() && coordRaw != "null") {
            try {
                val cObj = JSONObject(unescapeJsString(coordRaw))
                val cx = cObj.getInt("x").toFloat()
                val cy = cObj.getInt("y").toFloat()
                updateCursor(cx, cy, isClicking = false)
                delay(120)
                updateCursor(cx, cy, isClicking = true)
                delay(80)
            } catch (e: Exception) {}
        }
    }

    suspend fun reloadAction(): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "$USER_IN_CONTROL_MESSAGE — the agent cannot reload until control is handed back.")
        }
        return withContext(Dispatchers.Main) {
            val wv = obtainWebViewLocked() ?: return@withContext BrowserActionResult(false, "WebView not available")
            invalidateObservationCache()
            wv.reload()
            BrowserActionResult(success = true, message = "Page reloaded")
        }
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
        val raw = evaluateJs(js)
            ?: return BrowserActionResult(false, "WebView not available — no page text could be read")
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
        val res = evaluateJs(js)
            ?: return BrowserActionResult(false, "WebView not available — element query could not run")
        val clean = unescapeJsString(res)
        return BrowserActionResult(success = clean.contains("\"found\":true"), message = "Element query executed", data = clean)
    }

    suspend fun click(selector: String): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "$USER_IN_CONTROL_MESSAGE — the agent cannot act until control is handed back.")
        }
        val escaped = JSONObject.quote(selector)
        // First locate target coordinate to animate the virtual AI cursor
        val coordJs = """
            (function() {
                try {
                    var el = document.querySelector($escaped);
                    if (!el) return null;
                    var r = el.getBoundingClientRect();
                    return JSON.stringify({ x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2) });
                } catch(e) { return null; }
            })();
        """.trimIndent()
        val coordRaw = evaluateJs(coordJs)
        if (!coordRaw.isNullOrBlank() && coordRaw != "null") {
            try {
                val cleanCoord = unescapeJsString(coordRaw)
                val cObj = JSONObject(cleanCoord)
                val cx = cObj.getInt("x").toFloat()
                val cy = cObj.getInt("y").toFloat()
                updateCursor(cx, cy, isClicking = false)
                delay(120)
                updateCursor(cx, cy, isClicking = true)
                delay(80)
            } catch (e: Exception) {}
        }

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
        logAction(if (success) "AI clicked $selector" else "AI failed click on $selector")
        delay(100)
        hideCursor()
        return BrowserActionResult(success = success, message = if (success) "Clicked $selector" else "Failed to click $selector", data = clean)
    }

    suspend fun type(selector: String, text: String, clearFirst: Boolean = true): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "$USER_IN_CONTROL_MESSAGE — the agent cannot act until control is handed back.")
        }
        showKeystrokes(text)
        val escapedSel = JSONObject.quote(selector)
        val escapedText = JSONObject.quote(text)
        val js = """
            (function() {
                try {
                    var el = document.querySelector($escapedSel);
                    if (!el) return JSON.stringify({success: false, error: 'Input element not found'});
                    el.scrollIntoView({behavior: 'instant', block: 'center'});
                    el.focus();
                    var proto = el instanceof HTMLTextAreaElement ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                    var base = $clearFirst ? '' : (el.value || '');
                    var nv = base + $escapedText;
                    if (desc && desc.set) { desc.set.call(el, nv); } else { el.value = nv; }
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
        logAction(if (success) "AI typed into $selector: \"$text\"" else "AI failed typing into $selector")
        delay(300)
        hideKeystrokes()
        return BrowserActionResult(success = success, message = if (success) "Typed into $selector" else "Failed to type into $selector", data = clean)
    }

    suspend fun injectSetOfMarks(): List<InteractiveElement> = withContext(Dispatchers.Main) {
        val js = """
            (function() {
                try {
                    var old = document.querySelectorAll('.nova-som-marker');
                    for (var i = 0; i < old.length; i++) old[i].remove();
                    
                    var selectors = 'a, button, input, select, textarea, [role=button], [role=link], [onclick]';
                    var elements = document.querySelectorAll(selectors);
                    var results = [];
                    var markId = 1;
                    for (var i = 0; i < elements.length && markId <= 40; i++) {
                        var el = elements[i];
                        var rect = el.getBoundingClientRect();
                        if (rect.width > 12 && rect.height > 12 && rect.top >= -50 && rect.top <= window.innerHeight + 50) {
                            el.setAttribute('data-nova-id', markId);
                            var badge = document.createElement('div');
                            badge.className = 'nova-som-marker';
                            badge.innerText = markId;
                            badge.style.position = 'absolute';
                            badge.style.left = (rect.left + window.scrollX) + 'px';
                            badge.style.top = (rect.top + window.scrollY) + 'px';
                            badge.style.backgroundColor = '#D96B27';
                            badge.style.color = '#FFFFFF';
                            badge.style.fontSize = '10px';
                            badge.style.fontWeight = 'bold';
                            badge.style.padding = '1px 5px';
                            badge.style.borderRadius = '8px';
                            badge.style.boxShadow = '0 2px 4px rgba(0,0,0,0.3)';
                            badge.style.zIndex = '999999';
                            badge.style.pointerEvents = 'none';
                            document.body.appendChild(badge);
                            
                            var txt = (el.innerText || el.value || el.getAttribute('aria-label') || el.placeholder || '').trim();
                            results.push({
                                id: markId,
                                tagName: el.tagName,
                                text: txt.substring(0, 50),
                                x: Math.round(rect.left + rect.width / 2),
                                y: Math.round(rect.top + rect.height / 2),
                                width: Math.round(rect.width),
                                height: Math.round(rect.height),
                                selector: '[data-nova-id="' + markId + '"]'
                            });
                            markId++;
                        }
                    }
                    return JSON.stringify(results);
                } catch(e) {
                    return JSON.stringify([]);
                }
            })();
        """.trimIndent()
        val raw = evaluateJs(js) ?: "[]"
        val clean = unescapeJsString(raw)
        val list = mutableListOf<InteractiveElement>()
        try {
            val arr = org.json.JSONArray(clean)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    InteractiveElement(
                        id = obj.getInt("id"),
                        tagName = obj.getString("tagName"),
                        text = obj.optString("text", ""),
                        x = obj.getInt("x"),
                        y = obj.getInt("y"),
                        width = obj.getInt("width"),
                        height = obj.getInt("height"),
                        selector = obj.getString("selector")
                    )
                )
            }
        } catch (e: Exception) {}
        list
    }

    suspend fun clearSetOfMarks(): BrowserActionResult {
        val js = """
            (function() {
                try {
                    var old = document.querySelectorAll('.nova-som-marker');
                    for (var i = 0; i < old.length; i++) old[i].remove();
                    return JSON.stringify({success: true});
                } catch(e) {
                    return JSON.stringify({success: false, error: e.toString()});
                }
            })();
        """.trimIndent()
        evaluateJs(js)
        return BrowserActionResult(true, "Set-of-Marks cleared")
    }

    suspend fun startTeachMode(): BrowserActionResult {
        _isTeachModeActive.value = true
        _recordedActions.value = emptyList()
        val js = """
            (function() {
                if (window._novaTeachInjected) return 'already_injected';
                window._novaTeachInjected = true;
                window._novaRecorded = [];
                document.addEventListener('click', function(e) {
                    var el = e.target;
                    var sel = el.id ? '#' + el.id : (el.className ? '.' + el.className.split(' ')[0] : el.tagName.toLowerCase());
                    window._novaRecorded.push({
                        type: 'CLICK',
                        target: sel,
                        value: (el.innerText || '').substring(0, 30),
                        timestamp: Date.now()
                    });
                }, true);
                document.addEventListener('change', function(e) {
                    var el = e.target;
                    var sel = el.id ? '#' + el.id : (el.name ? '[name="' + el.name + '"]' : el.tagName.toLowerCase());
                    window._novaRecorded.push({
                        type: 'TYPE',
                        target: sel,
                        value: el.value,
                        timestamp: Date.now()
                    });
                }, true);
                return 'injected';
            })();
        """.trimIndent()
        evaluateJs(js)
        logAction("Teach Mode activated. Recording interactions.")
        return BrowserActionResult(true, "Teach Mode activated.")
    }

    suspend fun stopTeachMode(workspaceManager: com.example.nova.workspace.WorkspaceManager): BrowserActionResult {
        _isTeachModeActive.value = false
        val js = """
            (function() {
                var rec = window._novaRecorded || [];
                window._novaRecorded = [];
                return JSON.stringify(rec);
            })();
        """.trimIndent()
        val raw = evaluateJs(js) ?: "[]"
        val clean = unescapeJsString(raw)
        val actions = mutableListOf<TeachModeAction>()
        try {
            val arr = org.json.JSONArray(clean)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                actions.add(
                    TeachModeAction(
                        type = obj.getString("type"),
                        target = obj.getString("target"),
                        value = obj.optString("value", null),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {}

        _recordedActions.value = actions
        if (actions.isNotEmpty()) {
            val workflowContent = buildString {
                append("\n\n### Taught Workflow (${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())})\n")
                actions.forEachIndexed { idx, act ->
                    append("${idx + 1}. ${act.type} on `${act.target}`")
                    if (!act.value.isNullOrBlank()) append(" with value \"${act.value}\"")
                    append("\n")
                }
            }
            workspaceManager.appendFile("Memory/Training.md", workflowContent)
            logAction("Saved ${actions.size} actions to Memory/Training.md")
        }
        return BrowserActionResult(true, "Teach Mode stopped. Recorded ${actions.size} actions.")
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
        evaluateJs(js) ?: return BrowserActionResult(false, "WebView not available — could not scroll")
        invalidateObservationCache()
        val state = readScrollState()?.describe().orEmpty()
        return BrowserActionResult(
            success = true,
            message = "Scrolled by ($dx, $dy)" + if (state.isNotBlank()) ". $state" else ""
        )
    }

    /**
     * First-class scrolling (scroll addition): moves [pages] screens down
     * (fractional allowed, e.g. 0.5) with a ~10% overlap so chunked reading
     * never skips a strip of the page. The result reports where the
     * viewport landed and whether more content exists below — the agent's
     * stopping signal on long/infinite pages.
     */
    suspend fun scrollDown(pages: Double = 1.0): BrowserActionResult = scrollByPages(direction = 1, pages = pages)

    /** Mirror of [scrollDown] towards the top of the page. */
    suspend fun scrollUp(pages: Double = 1.0): BrowserActionResult = scrollByPages(direction = -1, pages = pages)

    private suspend fun scrollByPages(direction: Int, pages: Double): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "$USER_IN_CONTROL_MESSAGE — the agent cannot scroll until control is handed back.")
        }
        val safePages = pages.coerceIn(0.1, 10.0)
        val before = readScrollState()
        val js = """
            (function() {
                var step = Math.round(window.innerHeight * 0.9 * $safePages) * $direction;
                window.scrollBy(0, step);
                return 'ok';
            })();
        """.trimIndent()
        evaluateJs(js) ?: return BrowserActionResult(false, "WebView not available — could not scroll")
        delay(250)
        invalidateObservationCache()
        val after = readScrollState()
        val state = after?.describe().orEmpty()
        val dirWord = if (direction > 0) "down" else "up"
        val moved = if (before != null && after != null) kotlin.math.abs(after.scrollY - before.scrollY) else -1
        val message = when {
            after == null -> "Scrolled $dirWord ${safePages} page(s)"
            moved == 0 && direction > 0 && !after.canScrollDown ->
                "Already at the bottom — scrolling down moved nothing. $state"
            moved == 0 && direction < 0 && !after.canScrollUp ->
                "Already at the top — scrolling up moved nothing. $state"
            else -> "Scrolled $dirWord ${safePages} page(s). $state"
        }
        logAction("AI scrolled $dirWord ${safePages} page(s)")
        return BrowserActionResult(success = true, message = message, data = state.ifBlank { null })
    }

    /**
     * Scrolls the stamped element [index] (from the latest observation)
     * into the centre of the viewport. AgentLoop treats this as a
     * page-changing action, so a fresh observation follows automatically
     * and the element can be clicked/typed into with current coordinates.
     */
    suspend fun scrollToElement(index: Int): BrowserActionResult {
        if (_controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
            return BrowserActionResult(false, "$USER_IN_CONTROL_MESSAGE — the agent cannot scroll until control is handed back.")
        }
        val js = """
            (function() {
                try {
                    var el = document.querySelector('[data-spark-idx="$index"]');
                    if (!el) return JSON.stringify({success: false, error: 'Element $index not found on this page. The page changed — call observe_page for fresh indices.'});
                    el.scrollIntoView({behavior: 'instant', block: 'center'});
                    var label = (el.innerText || el.getAttribute('aria-label') || el.placeholder || el.name || '').trim();
                    return JSON.stringify({success: true, label: label.substring(0, 60)});
                } catch(e) {
                    return JSON.stringify({success: false, error: e.toString()});
                }
            })();
        """.trimIndent()
        val raw = evaluateJs(js) ?: return BrowserActionResult(false, "WebView not available — could not scroll to element [$index]")
        val clean = unescapeJsString(raw)
        val success = clean.contains("\"success\":true")
        if (!success) {
            return BrowserActionResult(false, "Failed to scroll to element [$index]", data = clean, error = clean)
        }
        delay(200)
        invalidateObservationCache()
        val label = try { JSONObject(clean).optString("label") } catch (e: Exception) { "" }
        val state = readScrollState()?.describe().orEmpty()
        logAction("AI scrolled element [$index] into view")
        return BrowserActionResult(
            success = true,
            message = "Element [$index]${if (label.isNotBlank()) " \"$label\"" else ""} scrolled into view." +
                if (state.isNotBlank()) " $state" else "",
            data = clean
        )
    }

    /** Reads the current viewport position, or null when no page can be read. */
    suspend fun readScrollState(): ScrollState? {
        val raw = evaluateJs(SCROLL_STATE_JS) ?: return null
        return try {
            val obj = JSONObject(unescapeJsString(raw))
            val pageHeight = obj.optInt("pageHeight", 0)
            val viewportHeight = obj.optInt("viewportHeight", 0)
            if (pageHeight <= 0 || viewportHeight <= 0) null
            else ScrollState(
                scrollY = obj.optInt("scrollY", 0).coerceAtLeast(0),
                viewportHeight = viewportHeight,
                pageHeight = pageHeight
            )
        } catch (e: Exception) {
            null
        }
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
        val raw = evaluateJs(js)
            ?: return BrowserActionResult(false, "WebView not available — page state could not be read")
        val clean = unescapeJsString(raw)
        // Honest results (fix round 2): the old code reported SUCCESS with
        // data "{}" when the JS failed or never ran. A real state always
        // carries the page URL; anything else is an error result.
        val parsed = try { JSONObject(clean) } catch (e: Exception) { null }
        val pageUrl = parsed?.optString("url").orEmpty()
        val jsError = parsed?.optString("error").orEmpty()
        return if (parsed == null || jsError.isNotBlank() || pageUrl.isBlank()) {
            BrowserActionResult(
                success = false,
                message = "Page state unavailable${if (jsError.isNotBlank()) ": $jsError" else " — the browser page is not loaded"}",
                data = clean
            )
        } else {
            BrowserActionResult(success = true, message = "Page state retrieved", data = clean)
        }
    }

    suspend fun takeScreenshot(destinationDir: File): BrowserActionResult = withContext(Dispatchers.Main) {
        val wv = obtainWebViewLocked() ?: return@withContext BrowserActionResult(false, "WebView not available for capture")
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

    /** Anchor harvest for deterministic collectors: every a[href], absolute, DOM order (capped). */
    private val ANCHORS_JS = """
        (function() {
            try {
                var nodes = document.querySelectorAll('a[href]');
                var out = [];
                for (var i = 0; i < nodes.length && out.length < 3000; i++) {
                    out.push(nodes[i].href);
                }
                return JSON.stringify(out);
            } catch (e) {
                return JSON.stringify([]);
            }
        })();
    """.trimIndent()

    /**
     * Page probe for trained classifiers: identity (url/title), login-wall
     * signals (password field / login form) and a visible-text excerpt for
     * phrase matching. Values are read live; nothing is stored.
     */
    private val PAGE_PROBE_JS = """
        (function() {
            try {
                var hasPassword = !!document.querySelector('input[type="password"]');
                var hasLoginForm = hasPassword;
                if (!hasLoginForm) {
                    var forms = document.querySelectorAll('form');
                    for (var i = 0; i < forms.length; i++) {
                        var action = (forms[i].getAttribute('action') || '').toLowerCase();
                        var id = (forms[i].id || '').toLowerCase();
                        if (action.indexOf('login') >= 0 || id.indexOf('login') >= 0) { hasLoginForm = true; break; }
                    }
                }
                var text = '';
                try { text = document.body ? document.body.innerText.substring(0, 4000) : ''; } catch (e) {}
                return JSON.stringify({
                    url: window.location.href,
                    title: document.title || '',
                    hasPassword: hasPassword,
                    hasLoginForm: hasLoginForm,
                    text: text
                });
            } catch (e) {
                return JSON.stringify({url: '', title: '', hasPassword: false, hasLoginForm: false, text: ''});
            }
        })();
    """.trimIndent()

    /**
     * Cheap page-identity probe for the observation cache: current URL and
     * DOM size. Two probes with the same pair mean the page state the agent
     * already observed — the full extraction can be skipped.
     */
    private val PROBE_JS = """
        (function() {
            try {
                return JSON.stringify({
                    url: window.location.href,
                    domLength: document.documentElement ? document.documentElement.innerHTML.length : -1
                });
            } catch (e) {
                return JSON.stringify({url: '', domLength: -1});
            }
        })();
    """.trimIndent()

    /** Viewport position probe: feeds [ScrollState] after scroll actions and observations. */
    private val SCROLL_STATE_JS = """
        (function() {
            try {
                return JSON.stringify({
                    scrollY: Math.round(window.scrollY || window.pageYOffset || 0),
                    viewportHeight: window.innerHeight || 0,
                    pageHeight: Math.max(
                        document.documentElement ? document.documentElement.scrollHeight : 0,
                        document.body ? document.body.scrollHeight : 0
                    )
                });
            } catch (e) {
                return JSON.stringify({scrollY: 0, viewportHeight: 0, pageHeight: 0});
            }
        })();
    """.trimIndent()

    /**
     * DOM extraction for [observePage]: collects visible interactive
     * elements in document order, stamps each with data-spark-idx, and
     * returns URL/title/elements/text as a JSON string. Password values are
     * never read; other field values are echoed truncated so the model can
     * verify typing actually landed.
     */
    private val OBSERVE_JS = """
        (function() {
            try {
                var selectors = 'a[href], button, input, select, textarea, [role="button"], [role="link"], [onclick], [tabindex]';
                var nodes = document.querySelectorAll(selectors);
                var results = [];
                var idx = 0;
                for (var i = 0; i < nodes.length && idx < 60; i++) {
                    var el = nodes[i];
                    if (el.getAttribute('type') === 'hidden') continue;
                    var rect = el.getBoundingClientRect();
                    if (rect.width <= 0 || rect.height <= 0) continue;
                    var style = window.getComputedStyle(el);
                    if (style.display === 'none' || style.visibility === 'hidden') continue;
                    idx++;
                    el.setAttribute('data-spark-idx', idx);
                    var label = '';
                    var text = (el.innerText || el.textContent || '').trim().replace(/\s+/g, ' ');
                    if (text) { label = text; }
                    else if (el.getAttribute('aria-label')) { label = el.getAttribute('aria-label').trim(); }
                    else if (el.placeholder) { label = el.placeholder; }
                    else if (el.title) { label = el.title; }
                    else if (el.getAttribute('name')) { label = el.getAttribute('name'); }
                    else if (el.value && (el.tagName === 'BUTTON' || el.getAttribute('type') === 'submit' || el.getAttribute('type') === 'button')) { label = el.value; }
                    var val = '';
                    if ((el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') && el.getAttribute('type') !== 'password') {
                        val = (el.value || '').substring(0, 60);
                    }
                    var inVp = rect.bottom >= 0 && rect.right >= 0 && rect.top <= window.innerHeight && rect.left <= window.innerWidth;
                    results.push({
                        index: idx,
                        tag: el.tagName.toLowerCase(),
                        type: el.getAttribute('type') || '',
                        label: label.substring(0, 120),
                        placeholder: el.placeholder || '',
                        name: el.getAttribute('name') || '',
                        href: el.tagName === 'A' ? (el.href || '') : '',
                        value: val,
                        disabled: !!el.disabled,
                        x: Math.round(rect.left + rect.width / 2),
                        y: Math.round(rect.top + rect.height / 2),
                        width: Math.round(rect.width),
                        height: Math.round(rect.height),
                        inViewport: inVp
                    });
                }
                var textExcerpt = '';
                try { textExcerpt = document.body ? document.body.innerText.substring(0, 2500) : ''; } catch (e) {}
                return JSON.stringify({
                    url: window.location.href,
                    title: document.title || '',
                    elements: results,
                    text: textExcerpt,
                    scrollY: Math.round(window.scrollY || window.pageYOffset || 0),
                    viewportHeight: window.innerHeight || 0,
                    pageHeight: Math.max(
                        document.documentElement ? document.documentElement.scrollHeight : 0,
                        document.body ? document.body.scrollHeight : 0
                    )
                });
            } catch (e) {
                return JSON.stringify({url: '', title: '', elements: [], text: ''});
            }
        })();
    """.trimIndent()

    private suspend fun evaluateJs(script: String, timeoutMs: Long = 5000): String? {
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                // Create the owned WebView on first use so agent work never
                // depends on the Browser screen having been opened.
                val wv = obtainWebViewLocked() ?: return@withContext null
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
