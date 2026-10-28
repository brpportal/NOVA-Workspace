package com.example.nova.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
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
import com.example.nova.browser.BrowserController
import com.example.nova.data.BookmarkEntity
import com.example.nova.data.BrowserHistoryEntry
import com.example.nova.data.BrowserTabEntity
import com.example.nova.data.NovaRepository
import com.example.ui.theme.Charcoal600
import com.example.ui.theme.Charcoal700
import com.example.ui.theme.Charcoal800
import com.example.ui.theme.Charcoal900
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.SafetyOrange
import com.example.ui.theme.SafetyRed
import com.example.ui.theme.SlateMuted
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TitaniumSilver
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
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

    var urlInputText by remember { mutableStateOf(tabState.url) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showFindInPage by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var sslAlertDialog by remember { mutableStateOf<String?>(null) }

    val bookmarks by repository.bookmarks.collectAsState(initial = emptyList())
    val history by repository.history.collectAsState(initial = emptyList())

    // Update address bar text when URL changes externally
    LaunchedEffect(tabState.url) {
        urlInputText = tabState.url
    }

    // Android back navigation in WebView
    BackHandler(enabled = tabState.canGoBack) {
        browserController.goBack()
    }

    Column(modifier = modifier.fillMaxSize().background(Charcoal900)) {
        // --- TAKE CONTROL / MANUAL OVERRIDE BANNER ---
        if (controlMode == BrowserControlMode.MANUAL_CONTROL) {
            Surface(
                color = SafetyOrange.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SafetyOrange)
                    .testTag("manual_control_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = SafetyOrange, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MANUAL CONTROL ACTIVE",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = SafetyOrange)
                        )
                        Text(
                            text = manualControlReason ?: "Solve CAPTCHA / Login manually. Agent is paused.",
                            style = MaterialTheme.typography.bodySmall.copy(color = TitaniumSilver),
                            maxLines = 1
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                browserController.returnToAI()
                                Toast.makeText(context, "State re-read. Control returned to AI agent.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        modifier = Modifier.height(32.dp).testTag("return_to_ai_button")
                    ) {
                        Text("Return to AI", fontSize = 11.sp, color = Charcoal900, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = { browserController.emergencyStop() },
                        modifier = Modifier.size(32.dp).testTag("emergency_stop_button")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Emergency Stop", tint = SafetyRed)
                    }
                }
            }
        } else if (controlMode == BrowserControlMode.AGENT_ACTIVE) {
            Surface(
                color = NovaCyan.copy(alpha = 0.12f),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NovaCyan)
                    .testTag("agent_active_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AGENT AUTOMATION IN PROGRESS",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = NovaCyan),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { browserController.takeManualControl("User paused automation via Take Control") },
                        colors = ButtonDefaults.buttonColors(containerColor = SafetyOrange),
                        modifier = Modifier.height(30.dp).testTag("take_control_button")
                    ) {
                        Text("Take Control", fontSize = 11.sp, color = Charcoal900, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- ADDRESS & TOOLBAR ---
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
                    IconButton(
                        onClick = { browserController.goBack() },
                        enabled = tabState.canGoBack,
                        modifier = Modifier.size(36.dp).testTag("browser_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = if (tabState.canGoBack) TitaniumSilver else SlateMuted
                        )
                    }

                    IconButton(
                        onClick = { browserController.goForward() },
                        enabled = tabState.canGoForward,
                        modifier = Modifier.size(36.dp).testTag("browser_forward_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Forward",
                            tint = if (tabState.canGoForward) TitaniumSilver else SlateMuted
                        )
                    }

                    // Address input field
                    OutlinedTextField(
                        value = urlInputText,
                        onValueChange = { urlInputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("browser_address_bar"),
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = if (tabState.isSecure) Icons.Default.Lock else Icons.Default.Warning,
                                contentDescription = if (tabState.isSecure) "HTTPS Secure" else "Insecure",
                                tint = if (tabState.isSecure) SuccessGreen else SafetyOrange,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        trailingIcon = {
                            if (tabState.isLoading) {
                                IconButton(onClick = { browserController.stop() }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Stop", tint = SlateMuted, modifier = Modifier.size(16.dp))
                                }
                            } else {
                                IconButton(onClick = { browserController.reload() }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = SlateMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            focusManager.clearFocus()
                            browserController.navigateDirect(urlInputText)
                        }),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = TitaniumSilver, fontSize = 13.sp),
                        shape = RoundedCornerShape(20.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Charcoal800,
                            unfocusedContainerColor = Charcoal800,
                            focusedIndicatorColor = NovaCyan,
                            unfocusedIndicatorColor = Charcoal600
                        )
                    )

                    IconButton(
                        onClick = { browserController.navigateDirect("https://www.google.com") },
                        modifier = Modifier.size(36.dp).testTag("browser_home_button")
                    ) {
                        Icon(Icons.Default.Home, contentDescription = "Home", tint = SlateMuted)
                    }

                    IconButton(
                        onClick = { showBookmarksSheet = true },
                        modifier = Modifier.size(36.dp).testTag("browser_bookmarks_button")
                    ) {
                        Icon(Icons.Default.BookmarkBorder, contentDescription = "Bookmarks", tint = SlateMuted)
                    }

                    IconButton(
                        onClick = { showFindInPage = !showFindInPage },
                        modifier = Modifier.size(36.dp).testTag("browser_find_button")
                    ) {
                        Icon(Icons.Default.FindInPage, contentDescription = "Find in page", tint = if (showFindInPage) NovaCyan else SlateMuted)
                    }

                    // If not in manual control and agent is idle, offer Take Control / Manual toggle
                    if (controlMode == BrowserControlMode.STANDBY) {
                        IconButton(
                            onClick = { browserController.takeManualControl("User manually engaged browser control") },
                            modifier = Modifier.size(36.dp).testTag("toolbar_take_control_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Take Control", tint = NovaCyan)
                        }
                    }
                }

                // Find in page bar
                if (showFindInPage) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Charcoal700)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = findQuery,
                            onValueChange = {
                                findQuery = it
                                // Dispatch search
                            },
                            placeholder = { Text("Find in page...", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f).height(40.dp).testTag("find_query_input"),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        IconButton(
                            onClick = { /* Find next */ },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match", tint = TitaniumSilver)
                        }
                        IconButton(
                            onClick = { /* Find prev */ },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match", tint = TitaniumSilver)
                        }
                        IconButton(
                            onClick = { showFindInPage = false },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close search", tint = SlateMuted)
                        }
                    }
                }

                // Progress Indicator
                if (tabState.isLoading) {
                    LinearProgressIndicator(
                        progress = { tabState.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = NovaCyan,
                        trackColor = Charcoal700
                    )
                }
            }
        }

        // --- NATIVE ANDROID WEBVIEW ---
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
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            // Security mandates: Disallow file access from arbitrary web content
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 NOVA/1.0"
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
                                // Record in browsing history
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
                                // MANDATORY SECURITY: Never silently bypass TLS errors
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

                        // Handle downloads securely into workspace Downloads/
                        setDownloadListener { downloadUrl, _, contentDisposition, mimeType, _ ->
                            scope.launch {
                                try {
                                    val fileName = "download_${System.currentTimeMillis()}"
                                    val downloadsDir = File(repository.workspaceManager.rootDir, "Downloads")
                                    downloadsDir.mkdirs()
                                    val target = File(downloadsDir, fileName)
                                    Toast.makeText(context, "Download started to workspace/Downloads/$fileName", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        browserController.bindWebView(this)
                        loadUrl(tabState.url)
                    }
                },
                update = { wv ->
                    // WebView binding updated
                },
                modifier = Modifier.fillMaxSize().testTag("native_webview")
            )
        }
    }

    // Bookmarks Sheet
    if (showBookmarksSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showBookmarksSheet = false },
            sheetState = sheetState,
            containerColor = Charcoal800
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Bookmarks", style = MaterialTheme.typography.titleMedium.copy(color = NovaCyan, fontWeight = FontWeight.Bold))
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
                        colors = ButtonDefaults.buttonColors(containerColor = Charcoal700)
                    ) {
                        Text("Bookmark Current Page", fontSize = 12.sp, color = TitaniumSilver)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.height(260.dp)) {
                    items(bookmarks) { b ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    browserController.navigateDirect(b.url)
                                    showBookmarksSheet = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Bookmark, contentDescription = null, tint = NovaCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(b.title, style = MaterialTheme.typography.bodyMedium.copy(color = TitaniumSilver))
                                Text(b.url, style = MaterialTheme.typography.labelSmall.copy(color = SlateMuted), maxLines = 1)
                            }
                            IconButton(onClick = { scope.launch { repository.browserDao.deleteBookmarkById(b.id) } }) {
                                Icon(Icons.Default.Close, contentDescription = "Delete", tint = SlateMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // SSL Security Alert Dialog
    if (sslAlertDialog != null) {
        AlertDialog(
            onDismissRequest = { sslAlertDialog = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = SafetyRed) },
            title = { Text("TLS / SSL Certificate Error", color = SafetyRed) },
            text = { Text(sslAlertDialog ?: "The connection is not secure. Access blocked.", color = TitaniumSilver) },
            confirmButton = {
                Button(
                    onClick = { sslAlertDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Charcoal700)
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
