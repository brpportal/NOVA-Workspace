package com.example.nova.data

import android.content.Context
import com.example.nova.agent.AgentLoop
import com.example.nova.agent.ChatIntent
import com.example.nova.agent.ContextBuilder
import com.example.nova.agent.PipelinePresence
import com.example.nova.agent.ToolExecutor
import com.example.nova.agent.ToolRegistry
import com.example.nova.ai.AIMessage
import com.example.nova.ai.AIProviderAdapter
import com.example.nova.ai.AIProviderFactory
import com.example.nova.ai.FakeAIProvider
import com.example.nova.ai.ProviderErrors
import com.example.nova.browser.BrowserController
import com.example.nova.notifications.TaskNotificationHelper
import com.example.nova.security.PermissionManager
import com.example.nova.security.VaultManager
import com.example.nova.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
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
    val approvalDao = db.approvalDao()
    val goalDao = db.goalDao()
    val taskRunDao = db.taskRunDao()
    val agentCreatedFileDao = db.agentCreatedFileDao()

    val vaultManager = VaultManager(secretDao)
    val toolRegistry = ToolRegistry()
    val toolExecutor = ToolExecutor(browserController, workspaceManager, memoryDao, vaultManager)
    val contextBuilder = ContextBuilder(workspaceManager, memoryDao, browserController)

    val pipelinePresence = MutableStateFlow(PipelinePresence.READY)

    /** State of a "Summarize into memory" request (see [requestConversationSummary]). */
    sealed interface SummarizeUiState {
        data object Idle : SummarizeUiState
        data object Loading : SummarizeUiState
        data class Ready(val text: String) : SummarizeUiState
        data class Failed(val message: String) : SummarizeUiState
    }

    val summarizeState = MutableStateFlow<SummarizeUiState>(SummarizeUiState.Idle)

    /**
     * Runs the conversation-summary provider call in the REPOSITORY's
     * application-lifetime scope — never in a Compose composition scope.
     * (Previously ChatScreen launched it from rememberCoroutineScope; when
     * the user switched tabs mid-call, the composition scope died and the
     * raw "The coroutine scope left the composition" exception surfaced as
     * a toast over another screen.) The result is delivered through
     * [summarizeState]: it survives tab switches, and failures are already
     * mapped to human text by [ProviderErrors].
     */
    fun requestConversationSummary(conversationId: String) {
        if (summarizeState.value is SummarizeUiState.Loading) return
        summarizeState.value = SummarizeUiState.Loading
        scope.launch {
            try {
                val msgs = chatDao.getMessagesForConversation(conversationId).firstOrNull().orEmpty()
                if (msgs.isEmpty()) {
                    summarizeState.value = SummarizeUiState.Failed("No messages in this chat to summarize")
                    return@launch
                }
                val unavailable = providerUnavailableReason()
                if (unavailable != null) {
                    summarizeState.value = SummarizeUiState.Failed(unavailable)
                    return@launch
                }
                val provider = getActiveProvider()
                val conversationText = msgs.joinToString("\n") { "${it.sender}: ${it.content}" }
                val prompt = "Extract durable facts, user preferences, and key context from this conversation into concise markdown bullet points. Do NOT copy verbatim messages. Return ONLY the bullet points:\n\n$conversationText"
                val response = provider.sendMessage(listOf(AIMessage(role = "user", content = prompt)), emptyList())
                val text = response.text.orEmpty().ifBlank { "- User preference logged from conversation" }
                summarizeState.value = SummarizeUiState.Ready(text)
            } catch (e: Exception) {
                summarizeState.value = SummarizeUiState.Failed(ProviderErrors.userMessage(e))
            }
        }
    }

    fun consumeSummarizeState() {
        summarizeState.value = SummarizeUiState.Idle
    }

    val agentLoop = AgentLoop(
        taskDao = taskDao,
        chatDao = chatDao,
        toolRegistry = toolRegistry,
        toolExecutor = toolExecutor,
        contextBuilder = contextBuilder,
        permissionManager = permissionManager,
        browserController = browserController,
        workspaceManager = workspaceManager,
        approvalDao = approvalDao,
        agentCreatedFileDao = agentCreatedFileDao,
        onPresenceChange = { pipelinePresence.value = it }
    )

    // Reactive Flows
    val conversations: Flow<List<ConversationEntity>> = chatDao.getActiveConversations()
    val tasks: Flow<List<AgentTaskEntity>> = taskDao.getAllTasks()
    val secrets: Flow<List<SecretMetadataEntity>> = vaultManager.allSecrets
    val memories: Flow<List<MemoryEntryEntity>> = memoryDao.getAllMemories()
    val bookmarks: Flow<List<BookmarkEntity>> = browserDao.getAllBookmarks()
    val history: Flow<List<BrowserHistoryEntry>> = browserDao.getHistory()
    val settings: Flow<List<AppSettingsEntity>> = settingsDao.getAllSettings()
    val approvals: Flow<List<ApprovalRecordEntity>> = approvalDao.getAllApprovals()
    val goals: Flow<List<GoalEntity>> = goalDao.getAllGoals()
    val upcomingTasks: Flow<List<AgentTaskEntity>> = taskDao.getUpcomingTasks()
    val createdFiles: Flow<List<AgentCreatedFileEntity>> = agentCreatedFileDao.getAllCreatedFiles()

    init {
        // Start background approval timeout loop (2-minute timeout)
        scope.launch {
            while (isActive) {
                try {
                    checkApprovalTimeouts()
                } catch (ignored: Exception) {}
                delay(5000)
            }
        }
        // Start background due scheduled tasks check loop
        scope.launch {
            while (isActive) {
                try {
                    executeDueScheduledTasks()
                } catch (ignored: Exception) {}
                delay(30_000)
            }
        }
        // Post a notification when a task transitions into a terminal or
        // needs-you state. The first emission only seeds the baseline so
        // existing tasks don't notify on app start.
        scope.launch {
            var previousStatuses: Map<String, String>? = null
            tasks.collect { taskList ->
                val current = taskList.associate { it.id to it.status }
                val previous = previousStatuses
                if (previous != null) {
                    for (task in taskList) {
                        if (previous[task.id] != task.status && task.status in TaskNotificationHelper.NOTIFY_STATUSES) {
                            try {
                                TaskNotificationHelper.notifyTaskTransition(context, task)
                            } catch (ignored: Exception) {}
                        }
                    }
                }
                previousStatuses = current
            }
        }
    }

    private suspend fun checkApprovalTimeouts() {
        val pending = approvalDao.getPendingApprovals()
        val now = System.currentTimeMillis()
        for (record in pending) {
            if (now - record.createdAt >= 120_000) { // 2 minutes
                val updatedRecord = record.copy(status = "TIMED_OUT", decidedAt = now)
                approvalDao.updateApproval(updatedRecord)

                if (!record.taskId.isNullOrBlank()) {
                    val task = taskDao.getTaskById(record.taskId)
                    if (task != null && task.status == "WAITING_APPROVAL") {
                        taskDao.updateTask(
                            task.copy(
                                status = "FAILED",
                                currentOperation = "Approval request timed out after 2 minutes. Action was NOT approved.",
                                errorMessage = "Approval timed out (2m limit)",
                                requiresApprovalAction = null,
                                pendingApprovalToolName = null,
                                pendingApprovalArgumentsJson = null,
                                updatedAt = now
                            )
                        )
                    }
                }
            }
        }
    }

    suspend fun getActiveProvider(): AIProviderAdapter {
        val providerType = settingsDao.getSetting("ai_provider_type") ?: "gemini"
        val apiKey = settingsDao.getSetting("ai_api_key")
        val model = settingsDao.getSetting("ai_model")
        val endpoint = settingsDao.getSetting("ai_endpoint")
        return AIProviderFactory.createProvider(providerType, apiKey, model, endpoint)
    }

    suspend fun initializeSeedData() {
        // Welcome conversation if none exists
        val existing = chatDao.getActiveConversations().firstOrNull()
        if (existing.isNullOrEmpty()) {
            val convId = UUID.randomUUID().toString()
            chatDao.insertConversation(
                ConversationEntity(
                    id = convId,
                    title = "Getting Started with Spark",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            chatDao.insertMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    sender = "assistant",
                    content = "Welcome to **Spark**.\n\nI am your native autonomous AI assistant. Here is what you can do:\n\n- **Chat**: Discuss ideas, ask questions, or run autonomous action tasks.\n- **Browser**: Browse the web with embedded tabs, or let the agent research and navigate with manual take-control safeguards.\n- **Files**: Access, edit, and organize files in your local workspace sandbox.\n- **Tasks**: Inspect live agent execution, tool calls, and approval records.\n- **Secret Vault**: Store passwords and API keys encrypted with Android Keystore.\n\nType a request below or ask me a question!",
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

    /**
     * Returns a human-readable reason when no REAL AI provider is usable —
     * either none is configured (the factory falls back to the scripted
     * FakeAIProvider) or the configured provider's settings are incomplete.
     * Restored from the fix round (5883560): the FakeAIProvider's canned
     * replies must never be presented as model answers.
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
     * Chat routing logic (classification rules live in [ChatIntent], restored
     * from the fix round — questions and greetings are chat even when they
     * mention an action, and Roman Urdu imperatives count as actions):
     * - Greetings & Questions: Answered by provider WITHOUT creating a task.
     * - Action Requests: Creates an AgentTask.
     * When no real provider is usable, an honestly labelled system notice is
     * stored instead of any canned reply and no task is launched.
     */
    fun handleUserChatMessage(
        conversationId: String,
        userPrompt: String,
        attachedFiles: List<String> = emptyList()
    ) {
        val cleanPrompt = userPrompt.trim()
        if (cleanPrompt.isBlank()) return

        val promptWithContext = if (attachedFiles.isNotEmpty()) {
            "$cleanPrompt\n\n[Attached workspace files: ${attachedFiles.joinToString(", ")}]"
        } else {
            cleanPrompt
        }

        scope.launch {
            val unavailable = providerUnavailableReason()
            if (unavailable != null) {
                // The notice is inserted AT MOST ONCE per user message: if a
                // notice already follows the latest user message (duplicate
                // invocation, double-tap, or a second repository instance),
                // don't stack another identical one.
                val recent = try {
                    chatDao.getRecentMessages(conversationId, 10)
                } catch (ignored: Exception) {
                    emptyList()
                }
                val lastUserIndex = recent.indexOfFirst { it.sender == "user" }
                val alreadyNoticed = recent
                    .take(if (lastUserIndex == -1) recent.size else lastUserIndex)
                    .any { it.sender == "assistant" && it.content.startsWith(SYSTEM_NOTICE_PREFIX) }
                if (!alreadyNoticed) {
                    chatDao.insertMessage(
                        ChatMessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            sender = "assistant",
                            content = "$SYSTEM_NOTICE_PREFIX $unavailable Add your Base URL and API key in **Settings → AI Engine**, then try again.",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                return@launch
            }

            if (ChatIntent.isActionRequest(cleanPrompt)) {
                launchTask(promptWithContext, title = cleanPrompt.take(30), conversationId = conversationId)
            } else {
                // Direct Q&A routing - no task created!
                pipelinePresence.value = PipelinePresence.COMPOSING
                try {
                    val provider = getActiveProvider()
                    val sysContext = contextBuilder.buildSystemContext()
                    val response = provider.sendMessage(
                        messages = listOf(sysContext, AIMessage(role = "user", content = promptWithContext)),
                        tools = emptyList()
                    )
                    pipelinePresence.value = PipelinePresence.RESPONDING
                    val answerText = response.text.orEmpty().ifBlank { "I received your message." }

                    chatDao.insertMessage(
                        ChatMessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            sender = "assistant",
                            content = answerText,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    val isQuota = e.message?.contains("429") == true || e.message?.contains("quota", ignoreCase = true) == true
                    if (isQuota) {
                        pipelinePresence.value = PipelinePresence.PROVIDER_LIMIT_REACHED
                    }
                    chatDao.insertMessage(
                        ChatMessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = conversationId,
                            sender = "assistant",
                            content = "Error answering request: ${ProviderErrors.userMessage(e)}",
                            timestamp = System.currentTimeMillis()
                        )
                    )
                } finally {
                    pipelinePresence.value = PipelinePresence.READY
                }
            }
        }
    }

    companion object {
        /** Prefix of the honest "no real provider" system notice in chat. */
        const val SYSTEM_NOTICE_PREFIX = "⚠️ **System notice — not an AI reply.**"

        @Volatile
        private var INSTANCE: NovaRepository? = null

        fun getInstance(context: Context, db: AppDatabase? = null): NovaRepository {
            return INSTANCE ?: synchronized(this) {
                val database = db ?: AppDatabase.getDatabase(context)
                val instance = NovaRepository(context.applicationContext, database)
                INSTANCE = instance
                instance
            }
        }
    }

    suspend fun createGoal(title: String, description: String? = null, parentGoalId: String? = null, status: String = "ACTIVE"): GoalEntity {
        val goal = GoalEntity(
            title = title,
            description = description,
            parentGoalId = parentGoalId,
            status = status
        )
        goalDao.insertGoal(goal)
        return goal
    }

    suspend fun updateGoal(goal: GoalEntity) {
        goalDao.updateGoal(goal.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteGoal(goalId: String) {
        goalDao.deleteGoalById(goalId)
    }

    suspend fun linkTaskToGoal(taskId: String, goalId: String?) {
        val task = taskDao.getTaskById(taskId)
        if (task != null) {
            taskDao.updateTask(task.copy(goalId = goalId, updatedAt = System.currentTimeMillis()))
        }
    }

    fun launchTask(
        prompt: String,
        title: String? = null,
        conversationId: String? = null,
        goalId: String? = null,
        cadence: String = "RUN_ONCE",
        firstRunTime: Long? = null
    ): String {
        val taskId = UUID.randomUUID().toString()
        val taskTitle = title ?: prompt.take(35).let { if (prompt.length > 35) "$it..." else it }
        val calculatedNextRun = if (cadence != "RUN_ONCE") {
            firstRunTime ?: calculateNextRunTime(cadence, System.currentTimeMillis())
        } else {
            null
        }

        val task = AgentTaskEntity(
            id = taskId,
            title = taskTitle,
            prompt = prompt,
            status = "PENDING",
            currentOperation = "Queued",
            conversationId = conversationId,
            goalId = goalId,
            cadence = cadence,
            nextRunTime = calculatedNextRun,
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

            if (cadence != "RUN_ONCE") {
                scheduleWorkManagerCadenceJob()
            }
        }

        return taskId
    }

    fun calculateNextRunTime(cadence: String, fromTime: Long): Long {
        val dayMs = 24 * 60 * 60 * 1000L
        return when (cadence) {
            "DAILY" -> fromTime + dayMs
            "WEEKLY" -> fromTime + 7 * dayMs
            "MONTHLY" -> fromTime + 30 * dayMs
            else -> fromTime + dayMs
        }
    }

    suspend fun executeDueScheduledTasks() {
        val now = System.currentTimeMillis()
        val dueTasks = taskDao.getDueTasks(now)
        for (task in dueTasks) {
            // Advance the schedule first so neither the in-app loop nor the
            // WorkManager worker can pick the same task up twice.
            val nextRun = calculateNextRunTime(task.cadence, now)
            taskDao.updateTask(task.copy(nextRunTime = nextRun, updatedAt = now))

            val provider = getActiveProvider()
            scope.launch {
                agentLoop.runTask(task, provider, task.conversationId)
                // Record the run only AFTER it finishes, with its real
                // outcome — never a pre-written SUCCESS (merge fix).
                val finished = taskDao.getTaskById(task.id)
                val runStatus = when (finished?.status) {
                    "COMPLETED" -> "SUCCESS"
                    "FAILED" -> "FAILED"
                    "CANCELLED" -> "CANCELLED"
                    else -> finished?.status ?: "FAILED"
                }
                taskRunDao.insertRun(
                    TaskRunEntity(
                        taskId = task.id,
                        runTime = System.currentTimeMillis(),
                        status = runStatus,
                        resultSummary = finished?.currentOperation ?: "Scheduled run (${task.cadence})"
                    )
                )
            }
        }
    }

    fun scheduleWorkManagerCadenceJob() {
        try {
            val workRequest = androidx.work.PeriodicWorkRequestBuilder<com.example.nova.worker.SparkTaskWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "SparkCadenceTasksWork",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (ignored: Exception) {}
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

            // Update pending approval record
            val appRecord = approvalDao.getApprovalForTask(taskId)
            if (appRecord != null && appRecord.status == "PENDING") {
                approvalDao.updateApproval(appRecord.copy(status = "APPROVED", decidedAt = System.currentTimeMillis()))
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

            // Update pending approval record
            val appRecord = approvalDao.getApprovalForTask(taskId)
            if (appRecord != null && appRecord.status == "PENDING") {
                approvalDao.updateApproval(appRecord.copy(status = "DENIED", decidedAt = System.currentTimeMillis()))
            }

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
