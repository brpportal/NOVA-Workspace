package com.example.nova.data

import android.content.Context
import com.example.nova.agent.AgentLoop
import com.example.nova.agent.ContextBuilder
import com.example.nova.agent.ToolExecutor
import com.example.nova.agent.ToolRegistry
import com.example.nova.ai.AIMessage
import com.example.nova.ai.AIProviderAdapter
import com.example.nova.ai.AIProviderFactory
import com.example.nova.ai.FakeAIProvider
import com.example.nova.browser.BrowserController
import com.example.nova.security.PermissionManager
import com.example.nova.security.VaultManager
import com.example.nova.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

class NovaRepository(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.getDatabase(context),
    val workspaceManager: WorkspaceManager = WorkspaceManager.createDefault(context),
    val browserController: BrowserController = BrowserController(),
    val permissionManager: PermissionManager = PermissionManager(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    val chatDao = db.chatDao()
    val taskDao = db.taskDao()
    val browserDao = db.browserDao()
    val memoryDao = db.memoryDao()
    val secretDao = db.secretDao()
    val permissionDao = db.permissionDao()
    val settingsDao = db.settingsDao()

    val vaultManager = VaultManager(secretDao)
    val toolRegistry = ToolRegistry()
    val toolExecutor = ToolExecutor(browserController, workspaceManager, memoryDao, vaultManager)
    val contextBuilder = ContextBuilder(workspaceManager, memoryDao, browserController)
    val agentLoop = AgentLoop(
        taskDao = taskDao,
        chatDao = chatDao,
        toolRegistry = toolRegistry,
        toolExecutor = toolExecutor,
        contextBuilder = contextBuilder,
        permissionManager = permissionManager,
        browserController = browserController,
        workspaceManager = workspaceManager
    )

    // Reactive Flows
    val conversations: Flow<List<ConversationEntity>> = chatDao.getActiveConversations()
    val tasks: Flow<List<AgentTaskEntity>> = taskDao.getAllTasks()
    val secrets: Flow<List<SecretMetadataEntity>> = vaultManager.allSecrets
    val memories: Flow<List<MemoryEntryEntity>> = memoryDao.getAllMemories()
    val bookmarks: Flow<List<BookmarkEntity>> = browserDao.getAllBookmarks()
    val history: Flow<List<BrowserHistoryEntry>> = browserDao.getHistory()
    val settings: Flow<List<AppSettingsEntity>> = settingsDao.getAllSettings()

    suspend fun getActiveProvider(): AIProviderAdapter {
        val providerType = settingsDao.getSetting("ai_provider_type") ?: "gemini"
        val apiKey = settingsDao.getSetting("ai_api_key")
        val model = settingsDao.getSetting("ai_model")
        val endpoint = settingsDao.getSetting("ai_endpoint")
        return AIProviderFactory.createProvider(providerType, apiKey, model, endpoint)
    }

    /**
     * Returns null when a REAL provider is configured and valid, otherwise a
     * short human-readable reason. The offline FakeAIProvider does not count
     * as "configured": its scripted replies must never be presented to the
     * user as if a real model answered (see FIX notes in ChatScreen).
     */
    suspend fun providerUnavailableReason(): String? {
        val provider = getActiveProvider()
        if (provider is FakeAIProvider) {
            val type = settingsDao.getSetting("ai_provider_type")?.lowercase()
            return if (type == "fake" || type == "offline" || type == "mock") {
                "Offline demo mode is selected — it does not run a real AI model and its scripted replies are not shown as answers."
            } else {
                "No AI provider is configured yet."
            }
        }
        val validation = provider.validateConfiguration()
        return if (validation.isValid) null else (validation.errorMessage ?: "The configured provider is incomplete.")
    }

    /**
     * Answers a plain chat message with the configured provider. No task is
     * created. When no real provider is available — or the provider call
     * fails — an honestly labelled system notice with the REAL state/error
     * is stored instead of any canned reply.
     */
    suspend fun sendChatReply(conversationId: String) {
        suspend fun insertNotice(text: String) {
            chatDao.insertMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    sender = "system",
                    content = text,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        val unavailable = providerUnavailableReason()
        if (unavailable != null) {
            insertNotice("⚠️ **System notice — not an AI reply.** $unavailable Add your Base URL and API key in **Settings → AI Engine**, then try again.")
            return
        }

        val provider = getActiveProvider()
        try {
            val history = chatDao.getMessagesForConversation(conversationId).firstOrNull().orEmpty()
                .filter { it.sender == "user" || it.sender == "assistant" }
                .takeLast(20)
            val messages = mutableListOf(
                AIMessage(
                    role = "system",
                    content = "You are NOVA, the assistant inside the NOVA Workspace Android app. " +
                        "This is plain chat: answer the user directly, helpfully and concisely, " +
                        "in the user's language and script (they may write Roman Urdu / Hinglish). " +
                        "You cannot run tools in chat mode. If the user asks for an action " +
                        "(open a site, search, create a file), tell them to phrase it as a direct " +
                        "request and the agent will pick it up as a task."
                )
            )
            for (m in history) {
                messages.add(AIMessage(role = if (m.sender == "user") "user" else "assistant", content = m.content))
            }
            val response = provider.sendMessage(messages, emptyList())
            val text = response.text?.takeIf { it.isNotBlank() }
                ?: "(The model returned an empty reply.)"
            chatDao.insertMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    sender = "assistant",
                    content = text,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            insertNotice("⚠️ **System notice — not an AI reply.** The AI provider could not answer: ${e.message ?: "unknown error"}")
        }
    }

    suspend fun initializeSeedData() {
        // Welcome conversation if none exists
        val existing = chatDao.getActiveConversations().firstOrNull()
        if (existing.isNullOrEmpty()) {
            val convId = UUID.randomUUID().toString()
            chatDao.insertConversation(
                ConversationEntity(
                    id = convId,
                    title = "Getting Started with NOVA",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            chatDao.insertMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    sender = "assistant",
                    content = "Welcome to **NOVA Workspace**.\n\nI am your native autonomous AI assistant. Here is what you can do:\n\n- **Chat**: Discuss ideas and plan tasks with full Markdown support.\n- **Browser**: Browse the web with embedded tabs, or let the agent research and navigate with manual take-control safeguards.\n- **Files**: Access, edit, and organize files in your local workspace sandbox.\n- **Tasks**: Inspect live agent execution, tool calls, and approval requests.\n- **Secret Vault**: Store passwords and API keys encrypted with Android Keystore with origin-scoped autofill.\n\nType a request below or ask me to research something in the browser!",
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        // Default memory rule
        val mems = memoryDao.getAllMemories().firstOrNull()
        if (mems.isNullOrEmpty()) {
            memoryDao.insertMemory(
                MemoryEntryEntity(
                    id = UUID.randomUUID().toString(),
                    key = "default_agent_rule",
                    content = "Always verify files after creation and respect user manual control in browser.",
                    category = "agent_rule"
                )
            )
        }

        // Default bookmark
        val bmarks = browserDao.getAllBookmarks().firstOrNull()
        if (bmarks.isNullOrEmpty()) {
            browserDao.insertBookmark(
                BookmarkEntity(
                    title = "Google Search",
                    url = "https://www.google.com"
                )
            )
            browserDao.insertBookmark(
                BookmarkEntity(
                    title = "Hacker News",
                    url = "https://news.ycombinator.com"
                )
            )
        }
    }

    suspend fun createConversation(title: String): ConversationEntity {
        val conv = ConversationEntity(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "New Conversation" },
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        chatDao.insertConversation(conv)
        return conv
    }

    fun launchTask(prompt: String, title: String? = null, conversationId: String? = null): String {
        val taskId = UUID.randomUUID().toString()
        val taskTitle = title ?: prompt.take(35).let { if (prompt.length > 35) "$it..." else it }
        val task = AgentTaskEntity(
            id = taskId,
            title = taskTitle,
            prompt = prompt,
            status = "PENDING",
            currentOperation = "Queued",
            conversationId = conversationId,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        scope.launch {
            taskDao.insertTask(task)
            val provider = getActiveProvider()
            val job = launch {
                agentLoop.runTask(task, provider, conversationId)
            }
            agentLoop.registerJob(taskId, job)
        }

        return taskId
    }

    fun approveTask(taskId: String, policy: String = "ALLOW_ONCE") {
        scope.launch {
            val task = taskDao.getTaskById(taskId) ?: return@launch
            val toolName = task.pendingApprovalToolName ?: return@launch

            val activeUrl = browserController.getCurrentUrl().data.orEmpty()
            val domain = PermissionManager.normalizeDomain(activeUrl)

            if (policy == "ALWAYS_ALLOW") {
                permissionManager.registerRule(domain, toolName, "ALLOW")
                permissionDao.insertRule(
                    PermissionRuleEntity(
                        domain = domain,
                        action = toolName,
                        policy = "ALLOW"
                    )
                )
            } else {
                permissionManager.grantOneTime(toolName, domain, toolName)
            }

            // Resume task
            val updated = task.copy(
                status = "RUNNING",
                currentOperation = "Resuming approved operation '$toolName'",
                requiresApprovalAction = null,
                pendingApprovalToolName = null,
                pendingApprovalArgumentsJson = null,
                updatedAt = System.currentTimeMillis()
            )
            taskDao.updateTask(updated)
            val provider = getActiveProvider()
            val job = launch {
                agentLoop.runTask(updated, provider, task.conversationId)
            }
            agentLoop.registerJob(taskId, job)
        }
    }

    fun rejectTask(taskId: String) {
        scope.launch {
            val task = taskDao.getTaskById(taskId) ?: return@launch
            val updated = task.copy(
                status = "CANCELLED",
                currentOperation = "Action rejected by user.",
                requiresApprovalAction = null,
                pendingApprovalToolName = null,
                pendingApprovalArgumentsJson = null,
                updatedAt = System.currentTimeMillis()
            )
            taskDao.updateTask(updated)
        }
    }

    fun cancelTask(taskId: String) {
        agentLoop.cancelTask(taskId)
        scope.launch {
            val task = taskDao.getTaskById(taskId)
            if (task != null) {
                taskDao.updateTask(task.copy(status = "CANCELLED", currentOperation = "Cancelled by user."))
            }
        }
    }

    fun retryTask(taskId: String) {
        scope.launch {
            val task = taskDao.getTaskById(taskId) ?: return@launch
            val retried = task.copy(
                status = "PENDING",
                currentOperation = "Restarting task",
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
            taskDao.updateTask(retried)
            val provider = getActiveProvider()
            val job = launch {
                agentLoop.runTask(retried, provider, retried.conversationId)
            }
            agentLoop.registerJob(taskId, job)
        }
    }

    suspend fun clearBrowsingData() {
        browserDao.clearHistory()
        browserController.navigateDirect("about:blank")
    }

    suspend fun clearAllData() {
        browserDao.clearHistory()
        // Delete all tasks, messages, conversations
        val convs = chatDao.getAllConversations().firstOrNull() ?: emptyList()
        for (c in convs) {
            chatDao.deleteMessagesForConversation(c.id)
            chatDao.deleteConversationById(c.id)
        }
        val ts = taskDao.getAllTasks().firstOrNull() ?: emptyList()
        for (t in ts) {
            taskDao.deleteTaskById(t.id)
        }
        initializeSeedData()
    }
}
