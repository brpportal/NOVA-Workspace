package com.example.nova.agent

import com.example.nova.browser.BrowserController
import com.example.nova.data.MemoryDao
import com.example.nova.data.MemoryEntryEntity
import com.example.nova.security.SecretRedactor
import com.example.nova.security.VaultManager
import com.example.nova.trained.FacebookLinksCollector
import com.example.nova.trained.FacebookPostLinks
import com.example.nova.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ToolExecutionResult(
    val success: Boolean,
    val output: String,
    val outputFiles: List<String> = emptyList(),
    val error: String? = null
)

class ToolExecutor(
    private val browserController: BrowserController,
    private val workspaceManager: WorkspaceManager,
    private val memoryDao: MemoryDao,
    private val vaultManager: VaultManager
) {
    suspend fun execute(
        toolName: String,
        arguments: JSONObject,
        currentWebDomain: String? = null
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        // Single-controller rule (fix round 2, Defect B): while the USER
        // holds the browser, agent browser tools do not execute at all —
        // they return one clear blocked result instead of acting on a page
        // the user is driving. (When the AI holds it, the Browser screen's
        // touch shield is the mirror-image lock.)
        if (toolName in AgentLoop.BROWSER_TOOL_NAMES &&
            browserController.controlMode.value == com.example.nova.browser.BrowserControlMode.MANUAL_CONTROL
        ) {
            return@withContext ToolExecutionResult(
                success = false,
                output = "${BrowserController.USER_IN_CONTROL_MESSAGE} — agent browser tools are paused until control is handed back to the agent.",
                error = BrowserController.USER_IN_CONTROL_MESSAGE
            )
        }
        try {
            when (toolName) {
                // Browser tools
                "navigate" -> {
                    val url = arguments.getString("url")
                    val res = browserController.navigate(url)
                    ToolExecutionResult(res.success, res.message + (res.data?.let { " ($it)" } ?: ""), error = res.error)
                }
                "get_current_url" -> {
                    val res = browserController.getCurrentUrl()
                    ToolExecutionResult(res.success, res.data ?: "unknown", error = res.error)
                }
                "get_page_title" -> {
                    val res = browserController.getPageTitle()
                    ToolExecutionResult(res.success, res.data ?: "", error = res.error)
                }
                "get_visible_text" -> {
                    val maxChars = arguments.optInt("max_chars", 8000)
                    val res = browserController.getVisibleText(maxChars)
                    ToolExecutionResult(res.success, res.data ?: "", error = res.error)
                }
                "find_element" -> {
                    val sel = arguments.getString("selector")
                    val res = browserController.findElement(sel)
                    ToolExecutionResult(res.success, res.data ?: "{}", error = res.error)
                }
                "click" -> {
                    val sel = arguments.getString("selector")
                    val res = browserController.click(sel)
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "type" -> {
                    val sel = arguments.getString("selector")
                    val text = arguments.getString("text")
                    val clearFirst = arguments.optBoolean("clear_first", true)
                    val res = browserController.type(sel, text, clearFirst)
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "scroll" -> {
                    val dx = arguments.getInt("dx")
                    val dy = arguments.getInt("dy")
                    val res = browserController.scroll(dx, dy)
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "scroll_down" -> {
                    // Pixel amount wins when given; otherwise whole/fractional pages.
                    val res = if (arguments.has("pixels")) {
                        browserController.scroll(0, arguments.getInt("pixels"))
                    } else {
                        browserController.scrollDown(arguments.optDouble("pages", 1.0))
                    }
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "scroll_up" -> {
                    val res = if (arguments.has("pixels")) {
                        browserController.scroll(0, -kotlin.math.abs(arguments.getInt("pixels")))
                    } else {
                        browserController.scrollUp(arguments.optDouble("pages", 1.0))
                    }
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "scroll_to_element" -> {
                    val index = arguments.getInt("index")
                    val res = browserController.scrollToElement(index)
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "take_screenshot" -> {
                    val imagesDir = File(workspaceManager.rootDir, "Images")
                    val res = browserController.takeScreenshot(imagesDir)
                    val generated = if (res.success && res.data != null) listOf(res.data) else emptyList()
                    ToolExecutionResult(res.success, res.message, outputFiles = generated, error = res.error)
                }
                "get_page_state" -> {
                    val res = browserController.getPageState()
                    ToolExecutionResult(res.success, res.data ?: "{}", error = res.error)
                }
                "observe_page" -> {
                    val observation = browserController.observePage()
                    // Honest results: an observation that could not read a
                    // real page is an ERROR, not a successful empty page.
                    val obsError = observation.error
                    if (obsError != null) {
                        ToolExecutionResult(false, obsError, error = obsError)
                    } else {
                        ToolExecutionResult(true, observation.formatForModel())
                    }
                }
                "click_element" -> {
                    val index = arguments.getInt("index")
                    val res = browserController.clickElement(index)
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "type_text" -> {
                    val index = arguments.getInt("index")
                    val text = arguments.getString("text")
                    val clearFirst = arguments.optBoolean("clear_first", true)
                    val submit = arguments.optBoolean("submit", false)
                    val res = browserController.typeText(index, text, clearFirst, submit)
                    ToolExecutionResult(res.success, res.message + (res.data?.let { " ($it)" } ?: ""), error = res.error)
                }
                "tap" -> {
                    val x = arguments.getInt("x")
                    val y = arguments.getInt("y")
                    val res = browserController.tapAt(x, y)
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "browser_back" -> {
                    val res = browserController.goBackAction()
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "browser_forward" -> {
                    val res = browserController.goForwardAction()
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "browser_reload" -> {
                    val res = browserController.reloadAction()
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "wait_for_load" -> {
                    val timeout = arguments.optLong("timeout_ms", 8000)
                    val res = browserController.waitForLoad(timeout)
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "get_set_of_marks" -> {
                    val marks = browserController.injectSetOfMarks()
                    val jsonArr = org.json.JSONArray()
                    for (m in marks) {
                        jsonArr.put(JSONObject().apply {
                            put("id", m.id)
                            put("tag", m.tagName)
                            put("text", m.text)
                            put("x", m.x)
                            put("y", m.y)
                            put("selector", m.selector)
                        })
                    }
                    ToolExecutionResult(true, jsonArr.toString())
                }
                "clear_set_of_marks" -> {
                    val res = browserController.clearSetOfMarks()
                    ToolExecutionResult(res.success, res.message, error = res.error)
                }
                "collect_facebook_post_links" -> {
                    // Trained Task #1 (AI mode): the SAME deterministic
                    // core as the Tasks-screen collector, for ONE profile.
                    // AI-mode hooks never pause the run: a login wall
                    // returns LOGIN_REQUIRED at once — the model must hand
                    // it to the user; credentials are never touched.
                    val profileUrl = arguments.optString("profile_url").trim()
                    val requested = arguments.optInt("count", FacebookPostLinks.DEFAULT_POSTS_PER_PROFILE)
                        .coerceIn(FacebookPostLinks.MIN_POSTS_PER_PROFILE, FacebookPostLinks.MAX_POSTS_PER_PROFILE)
                    if (profileUrl.isBlank()) {
                        ToolExecutionResult(false, "profile_url is required", error = "profile_url is required")
                    } else {
                        val aiHooks = object : FacebookLinksCollector.Hooks {
                            override suspend fun onStep(message: String) {}
                            override suspend fun onWaitStart(reason: String) {}
                            override suspend fun onWaitEnd(resumed: Boolean) {}
                            override suspend fun onLoginWall(profileUrl: String) =
                                FacebookLinksCollector.LoginWallDecision.SKIP_PROFILE
                        }
                        val result = FacebookLinksCollector(browserController)
                            .collectOne(profileUrl, requested, aiHooks)
                        val nowDate = java.util.Date()
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(nowDate)
                        val rel = "AI-Tasks/facebook-post-links-$stamp.md"
                        workspaceManager.createDirectory("AI-Tasks")
                        val saved = workspaceManager.writeFile(
                            rel,
                            FacebookPostLinks.formatResultsMarkdown(listOf(result), requested, nowDate, null)
                        )
                        val sb = StringBuilder()
                        sb.append("Profile: ").append(result.normalizedUrl.ifBlank { result.profile }).append('\n')
                        sb.append("Status: ").append(result.status.name)
                        result.note?.let { sb.append(" — ").append(it) }
                        sb.append('\n')
                        sb.append("Links (").append(result.links.size).append("):\n")
                        result.links.forEachIndexed { i, link ->
                            sb.append(i + 1).append(". ").append(link).append('\n')
                        }
                        if (saved) sb.append("Saved: ").append(rel)
                        ToolExecutionResult(true, sb.toString(), outputFiles = if (saved) listOf(rel) else emptyList())
                    }
                }
                "task_record_artifact" -> {
                    val fileName = arguments.getString("file_name")
                    val content = arguments.getString("content")
                    val rel = "AI-Tasks/$fileName"
                    val ok = workspaceManager.writeFile(rel, content)
                    ToolExecutionResult(ok, if (ok) "Saved task artifact: $rel" else "Failed to save artifact", outputFiles = if (ok) listOf(rel) else emptyList())
                }

                // File tools
                "file_list" -> {
                    val dir = arguments.optString("directory", "")
                    val files = workspaceManager.listFiles(dir)
                    val jsonArr = org.json.JSONArray()
                    for (f in files) {
                        jsonArr.put(JSONObject().apply {
                            put("name", f.name)
                            put("relativePath", f.relativePath)
                            put("isDirectory", f.isDirectory)
                            put("sizeBytes", f.sizeBytes)
                        })
                    }
                    ToolExecutionResult(true, jsonArr.toString())
                }
                "file_create_directory" -> {
                    val path = arguments.getString("path")
                    val ok = workspaceManager.createDirectory(path)
                    ToolExecutionResult(ok, if (ok) "Created directory: $path" else "Failed to create directory: $path")
                }
                "file_read" -> {
                    val path = arguments.getString("path")
                    val content = workspaceManager.readFile(path)
                    ToolExecutionResult(true, content)
                }
                "file_write" -> {
                    val path = arguments.getString("path")
                    val content = arguments.getString("content")
                    val ok = workspaceManager.writeFile(path, content)
                    if (ok) {
                        ToolExecutionResult(true, "Successfully wrote file: $path", outputFiles = listOf(path))
                    } else {
                        ToolExecutionResult(false, "File write verification failed: $path", error = "Could not verify created file on disk")
                    }
                }
                "file_append" -> {
                    val path = arguments.getString("path")
                    val content = arguments.getString("content")
                    val ok = workspaceManager.appendFile(path, content)
                    ToolExecutionResult(ok, if (ok) "Appended to file: $path" else "Failed to append to file: $path", outputFiles = listOf(path))
                }
                "file_search" -> {
                    val query = arguments.getString("query")
                    val dir = arguments.optString("directory", "")
                    val results = workspaceManager.searchFiles(query, dir)
                    val jsonArr = org.json.JSONArray()
                    for (r in results) {
                        jsonArr.put(r.relativePath)
                    }
                    ToolExecutionResult(true, jsonArr.toString())
                }
                "file_delete" -> {
                    val path = arguments.getString("path")
                    val ok = workspaceManager.deleteFile(path)
                    ToolExecutionResult(ok, if (ok) "Deleted file: $path" else "File deletion failed or file not found: $path")
                }
                "file_get_metadata" -> {
                    val path = arguments.getString("path")
                    val meta = workspaceManager.getFileMetadata(path)
                    val json = JSONObject().apply {
                        put("path", meta.relativePath)
                        put("name", meta.name)
                        put("size", meta.readableSize)
                        put("sizeBytes", meta.sizeBytes)
                        put("lastModified", meta.lastModifiedDate)
                        put("mimeType", meta.mimeType)
                        if (meta.lineCount != null) put("lineCount", meta.lineCount)
                    }
                    ToolExecutionResult(true, json.toString())
                }

                // Memory tools
                "memory_save" -> {
                    val key = arguments.getString("key")
                    val content = arguments.getString("content")
                    val category = arguments.optString("category", "user_preference")
                    val entry = MemoryEntryEntity(
                        id = UUID.randomUUID().toString(),
                        key = key,
                        content = content,
                        category = category,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    memoryDao.insertMemory(entry)
                    ToolExecutionResult(true, "Memory saved under key: '$key'")
                }
                "memory_retrieve" -> {
                    val query = arguments.getString("query")
                    val entries = memoryDao.searchMemories(query)
                    val jsonArr = org.json.JSONArray()
                    for (m in entries) {
                        jsonArr.put(JSONObject().apply {
                            put("key", m.key)
                            put("content", m.content)
                            put("category", m.category)
                        })
                    }
                    ToolExecutionResult(true, jsonArr.toString())
                }

                // Vault Autofill (strictly guarded: model never sees the secret)
                "vault_autofill" -> {
                    val secretId = arguments.getString("secret_id")
                    val uSel = if (arguments.has("username_selector")) arguments.getString("username_selector") else null
                    val pSel = if (arguments.has("password_selector")) arguments.getString("password_selector") else null

                    val domain = currentWebDomain ?: browserController.getCurrentUrl().data.orEmpty()
                    val pair = vaultManager.getSecretForAutofill(secretId, domain)
                    if (pair == null) {
                        ToolExecutionResult(
                            false,
                            "Autofill denied: Secret not found, or domain mismatch with current site origin.",
                            error = "Domain verification failed"
                        )
                    } else {
                        val (secret, plainValue) = pair
                        val result = browserController.injectAutofill(
                            usernameSelector = uSel,
                            username = secret.username,
                            passwordSelector = pSel,
                            password = plainValue
                        )
                        ToolExecutionResult(result.success, result.message, error = result.error)
                    }
                }

                else -> {
                    ToolExecutionResult(false, "Unimplemented tool: $toolName", error = "Tool handler missing")
                }
            }
        } catch (e: Exception) {
            val safeMsg = SecretRedactor.redactText(e.message ?: "Execution error", emptyList())
            ToolExecutionResult(false, "Tool error: $safeMsg", error = safeMsg)
        }
    }
}
