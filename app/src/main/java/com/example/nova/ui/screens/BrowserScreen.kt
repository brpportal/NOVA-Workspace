package com.example.nova.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.nova.browser.BrowserControlMode
import com.example.nova.data.BookmarkEntity
import com.example.nova.data.BrowserHistoryEntry
import com.example.nova.data.NovaRepository
import com.example.nova.ui.components.AICursorOverlay
import com.example.nova.ui.components.AIKeystrokeOverlay
import com.example.ui.theme.StoneLight
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmAccent
import com.example.ui.theme.WarmAccentContainer
import com.example.ui.theme.WarmBorder
import com.example.ui.theme.WarmError
import com.example.ui.theme.WarmSuccess
import com.example.ui.theme.WarmWarning
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    repository: NovaRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val browserController = repository.browserController
    val tabState by browserController.currentTabState.collectAsState()
    val controlMode by browserController.controlMode.collectAsState()
    val manualControlReason by browserController.manualControlMessage.collectAsState()

    val aiCursorState by browserController.aiCursorState.collectAsState()
    val aiKeystrokeState by browserController.aiKeystrokeState.collectAsState()
    val currentPlan by browserController.currentPlan.collectAsState()
    val currentStep by browserController.currentStep.collectAsState()
    val actionLogs by browserController.actionLogs.collectAsState()
    val isTeachMode by browserController.isTeachModeActive.collectAsState()

    var urlInputText by remember { mutableStateOf(tabState.url) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showFindInPage by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var sslAlertDialog by remember { mutableStateOf<String?>(null) }
    var showAgentLogPanel by remember { mutableStateOf(false) }
    var isSetOfMarksActive by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val bookmarks by repository.bookmarks.collectAsState(initial = emptyList())
    val tasks by repository.tasks.collectAsState(initial = emptyList())
    val runningBrowserTask = tasks.firstOrNull { it.status == "RUNNING" || it.status == "PENDING" }

    LaunchedEffect(tabState.url) {
        urlInputText = tabState.url
    }

    BackHandler(enabled = tabState.canGoBack) {
        browserController.goBack()
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // --- BROWSER HEADER ROW: "On this phone" chip + true control
        // state + 3-dots overflow menu. Everything that used to sit on
        // this screen (back/forward/reload, View Log, agent plan,
        // Set-of-Marks, Teach Mode, Bookmarks) lives in the menu now;
        // the page itself is WebView + this header + the 2-button
        // bottom bar. ---
        val controlStateText = when (controlMode) {
            BrowserControlMode.AGENT_ACTIVE -> "AI is controlling the browser"
            BrowserControlMode.WAITING_APPROVAL -> "Waiting for your approval"
            BrowserControlMode.MANUAL_CONTROL -> "You're in control"
            BrowserControlMode.PAUSED -> "Paused \u2014 you're in control"
            BrowserControlMode.STANDBY -> "Standby \u2014 you're in control"
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = "On this phone",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = controlStateText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.weight(1f).testTag("browser_control_state_text")
            )
            Box {
                IconButton(
                    onClick = { showOverflowMenu = true },
                    modifier = Modifier.size(36.dp).testTag("browser_overflow_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Browser menu",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Back") },
                        enabled = tabState.canGoBack,
                        onClick = { showOverflowMenu = false; browserController.goBack() }
                    )
                    DropdownMenuItem(
                        text = { Text("Forward") },
                        enabled = tabState.canGoForward,
                        onClick = { showOverflowMenu = false; browserController.goForward() }
                    )
                    DropdownMenuItem(
                        text = { Text("Reload") },
                        onClick = { showOverflowMenu = false; browserController.reload() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (showAgentLogPanel) "Hide Log" else "View Log") },
                        onClick = { showOverflowMenu = false; showAgentLogPanel = !showAgentLogPanel }
                    )
                    DropdownMenuItem(
                        text = { Text("Agent Plan") },
                        enabled = false,
                        onClick = {}
                    )
                    DropdownMenuItem(
                        text = { Text(currentPlan, fontSize = 12.sp, color = StoneMuted) },
                        enabled = false,
                        onClick = {}
                    )
                    DropdownMenuItem(
                        text = { Text(if (isSetOfMarksActive) "Hide Set-of-Marks" else "Show Set-of-Marks") },
                        onClick = {
                            showOverflowMenu = false
                            scope.launch {
                                if (isSetOfMarksActive) {
                                    browserController.clearSetOfMarks()
                                    isSetOfMarksActive = false
                                } else {
                                    browserController.injectSetOfMarks()
                                    isSetOfMarksActive = true
                                    Toast.makeText(context, "Set-of-Marks injected", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isTeachMode) "Stop Teach Mode" else "Start Teach Mode") },
                        onClick = {
                            showOverflowMenu = false
                            scope.launch {
                                if (isTeachMode) {
                                    browserController.stopTeachMode(repository.workspaceManager)
                                    Toast.makeText(context, "Teach workflow recorded into Training.md", Toast.LENGTH_SHORT).show()
                                } else {
                                    browserController.startTeachMode()
                                    Toast.makeText(context, "Teach Mode: recording your actions", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Bookmarks") },
                        onClick = { showOverflowMenu = false; showBookmarksSheet = true }
                    )
                }
            }
        }

        // Expandable Agent Log Panel (toggled from the overflow menu)
        AnimatedVisibility(visible = showAgentLogPanel) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "Plan: $currentPlan",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Current Step: $currentStep",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Recent Agent Actions:",
                        style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted, fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    LazyColumn(modifier = Modifier.height(100.dp)) {
                        if (actionLogs.isEmpty()) {
                            item { Text("No agent actions recorded in this session.", fontSize = 11.sp, color = StoneLight) }
                        } else {
                            items(actionLogs) { log ->
                                Text("\u2022 $log", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        // --- BROWSER NAVIGATION BAR ---
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pill-shaped address bar
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .padding(horizontal = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (tabState.isSecure) Icons.Default.Lock else Icons.Default.Warning,
                                contentDescription = if (tabState.isSecure) "HTTPS Secure" else "Insecure",
                                tint = if (tabState.isSecure) WarmSuccess else WarmWarning,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))

                            OutlinedTextField(
                                value = urlInputText,
                                onValueChange = { urlInputText = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("browser_address_bar"),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = {
                                    focusManager.clearFocus()
                                    browserController.navigateDirect(urlInputText)
                                }),
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                )
                            )

                            if (tabState.isLoading) {
                                IconButton(onClick = { browserController.stop() }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Stop", tint = StoneMuted, modifier = Modifier.size(15.dp))
                                }
                            } else {
                                IconButton(onClick = { browserController.reload() }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = StoneMuted, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                }

                // Progress Indicator
                if (tabState.isLoading) {
                    LinearProgressIndicator(
                        progress = { tabState.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }

        // --- NATIVE ANDROID WEBVIEW & LIVE OVERLAYS ---
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
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

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                browserController.updateTabState {
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
                                browserController.updateTabState {
                                    it.copy(
                                        isLoading = false,
                                        title = title,
                                        url = url ?: it.url,
                                        canGoBack = view?.canGoBack() == true,
                                        canGoForward = view?.canGoForward() == true
                                    )
                                }
                                if (!url.isNullOrBlank() && url != "about:blank") {
                                    scope.launch {
                                        repository.browserDao.insertHistory(
                                            BrowserHistoryEntry(
                                                id = UUID.randomUUID().toString(),
                                                title = title,
                                                url = url,
                                                visitedAt = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                }
                            }

                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                handler?.cancel()
                                browserController.updateTabState {
                                    it.copy(isSecure = false, sslErrorDesc = "SSL/TLS Certificate verification failed.")
                                }
                                sslAlertDialog = "The webpage certificate could not be verified (${error?.primaryError}). Connection cancelled for your protection."
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                browserController.updateTabState {
                                    it.copy(progress = newProgress, isLoading = newProgress < 100)
                                }
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) {
                                    browserController.updateTabState { it.copy(title = title) }
                                }
                            }
                        }

                        browserController.bindWebView(this)
                        loadUrl(tabState.url)
                    }
                },
                update = {},
                modifier = Modifier.fillMaxSize().testTag("native_webview")
            )

            // Visual AI Cursor Overlay
            AICursorOverlay(
                cursorState = aiCursorState,
                modifier = Modifier.fillMaxSize()
            )

            // Live Keystroke Overlay Chip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                AIKeystrokeOverlay(keystrokeState = aiKeystrokeState)
            }
        }

        // --- BROWSER BOTTOM BAR: exactly two buttons, state-driven ---
        // Button 1 flips with the real control state (Take control <->
        // Hand back to the agent); button 2 stops the running task.
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val userInControl = controlMode == BrowserControlMode.MANUAL_CONTROL
                Button(
                    onClick = {
                        if (userInControl) {
                            scope.launch {
                                browserController.returnToAI()
                                Toast.makeText(context, "Handed back to the agent.", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            browserController.takeManualControl("User took control from the Browser screen")
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("browser_control_toggle_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (userInControl) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (userInControl) "Hand back to the agent" else "Take control",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                OutlinedButton(
                    onClick = {
                        runningBrowserTask?.let { repository.cancelTask(it.id) }
                    },
                    enabled = runningBrowserTask != null,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("browser_stop_task_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Stop Task", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }

    // Bookmarks Sheet
    if (showBookmarksSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showBookmarksSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bookmarks", style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            scope.launch {
                                repository.browserDao.insertBookmark(
                                    BookmarkEntity(
                                        title = tabState.title,
                                        url = tabState.url
                                    )
                                )
                                Toast.makeText(context, "Bookmark added", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Add Current", fontSize = 12.sp, color = Color.White)
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                LazyColumn(modifier = Modifier.height(260.dp)) {
                    items(bookmarks) { b ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    browserController.navigateDirect(b.url)
                                    showBookmarksSheet = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(b.title, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium))
                                    Text(b.url, style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted), maxLines = 1)
                                }
                                IconButton(onClick = { scope.launch { repository.browserDao.deleteBookmarkById(b.id) } }) {
                                    Icon(Icons.Default.Close, contentDescription = "Delete", tint = StoneMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // SSL Alert
    if (sslAlertDialog != null) {
        AlertDialog(
            onDismissRequest = { sslAlertDialog = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = WarmError) },
            title = { Text("TLS / SSL Certificate Error", color = WarmError) },
            text = { Text(sslAlertDialog ?: "The connection is not secure. Access blocked.") },
            confirmButton = {
                Button(
                    onClick = { sslAlertDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("OK (Safe Return)")
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            browserController.unbindWebView()
        }
    }
}
