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
import com.example.nova.browser.BrowserControlMode
import com.example.nova.browser.BrowserController
import com.example.nova.linux.LinuxManager
import com.example.nova.notifications.TaskNotificationHelper
import com.example.nova.trained.FacebookCollectRunResult
import com.example.nova.trained.FacebookExtractionMode
import com.example.nova.trained.FacebookLinksCollector
import com.example.nova.trained.FacebookPostLinks
import com.example.nova.trained.FacebookProfileResult
import com.example.nova.trained.FacebookProfileStatus
import com.example.nova.trained.FacebookRunArtifacts
import com.example.nova.trained.ScreenshotSourceResult
import com.example.nova.trained.ScreenshotsCollection
import com.example.nova.trained.ScreenshotsCollector
import com.example.nova.trained.ScreenshotsRunResult
import com.example.nova.security.PermissionManager
import com.example.nova.security.VaultManager
import com.example.nova.workspace.WorkspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class NovaRepository(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.getDatabase(context),
    val workspaceManager: WorkspaceManager = WorkspaceManager.createDefault(context),
    // ONE app-wide browser controller (fix round 2, Defect A): the UI
    // repository and the WorkManager singleton used to each build their own
    // BrowserController() — and with it, their own (missing) WebView.
    val browserController: BrowserController = BrowserController.getInstance(context),
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
    // Linux Mode: one app-wide manager for the PRoot/Ubuntu environment
    // (rootfs lives in filesDir/linux, outside the workspace; the
    // workspace itself is bind-mounted into the guest at /workspace).
    val linuxManager = LinuxManager(context, workspaceManager.rootDir)
    val toolExecutor = ToolExecutor(browserController, workspaceManager, memoryDao, vaultManager, linuxManager)
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
        // Browsing history for every finished page load, including
        // agent-driven loads while the Browser screen is closed (the
        // controller owns the WebView now; the screen no longer records it).
        browserController.onPageFinishedListener = { title, url ->
            scope.launch {
                try {
                    browserDao.insertHistory(
                        BrowserHistoryEntry(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            url = url,
                            visitedAt = System.currentTimeMillis()
                        )
                    )
                } catch (ignored: Exception) {}
            }
        }
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
                    agentLoop.onApprovalRejected(record.taskId)
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

        /** Workspace file holding the collector's saved profile list. */
        const val FACEBOOK_PROFILES_FILE = "AI-Tasks/facebook-profiles.txt"
        const val SCREENSHOT_PROFILES_FILE = "AI-Tasks/screenshot-profiles.txt"

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

            // "Approve & remember for this task": the approved action CLASS
            // is granted for the rest of this task, so the same kind of
            // action does not pause it again (history records are still
            // written, marked as remembered-grant decisions).
            agentLoop.onApprovalGranted(taskId)

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
            agentLoop.onApprovalRejected(taskId)

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

    /** Live state of the latest Trained Task #1 run (Mode 1), for the Tasks UI. */
    val lastFacebookCollectRun = MutableStateFlow<FacebookCollectRunResult?>(null)

    /** The saved profile list for the collector form (blank when none saved yet). */
    fun loadSavedFacebookProfiles(): String =
        try {
            workspaceManager.readFile(FACEBOOK_PROFILES_FILE)
        } catch (e: Exception) {
            ""
        }

    /** Live state of the latest Trained Task #2 run (Mode 1), for the Tasks UI. */
    val lastScreenshotsRun = MutableStateFlow<ScreenshotsRunResult?>(null)

    /** The saved source list for the screenshots form (blank when none saved yet). */
    fun loadSavedScreenshotProfiles(): String =
        try {
            workspaceManager.readFile(SCREENSHOT_PROFILES_FILE)
        } catch (e: Exception) {
            ""
        }

    /**
     * TRAINED TASK #1 (Mode 1) — Facebook Post Links Collector, launched
     * from the Tasks screen. Fully deterministic: ZERO provider/LLM
     * calls. The chain visits every profile in order and records the
     * exact per-profile status; it pauses ONLY on a login wall (task
     * WAITING / Needs you + notification, until the user logs in and
     * hands control back). The result is written to
     * AI-Tasks/facebook-post-links-<yyyyMMdd-HHmm>.md and registered
     * under Files → Created by Spark. Returns the task id immediately;
     * the run is cancellable via the normal Stop Task path.
     */
    fun launchFacebookPostLinksCollector(
        rawProfiles: List<String>,
        count: Int,
        saveList: Boolean
    ): String = launchFacebookPostLinksCollectorFromText(
        rawProfiles.joinToString("\n"),
        count,
        saveList,
        FacebookExtractionMode.HYBRID
    )

    /**
     * Round A entry: the raw multiline source text is parsed HERE
     * (trim, validate, dedupe, label) so invalid lines are surfaced in
     * the audit trail with their line numbers — never silently dropped
     * — and the measured invalid/duplicate counts flow into the run's
     * summary.json. [mode] selects the extraction methods (Hybrid /
     * M1 only / M3 only). A finished run writes FIVE artifacts (the
     * results .md plus results.txt/.csv, results.json, summary.json),
     * all registered in Created by Spark.
     */
    fun launchFacebookPostLinksCollectorFromText(
        rawText: String,
        count: Int,
        saveList: Boolean,
        mode: FacebookExtractionMode
    ): String {
        val parse = FacebookPostLinks.parseCollectorInput(rawText)
        val profiles = parse.sources
        val safeCount = FacebookPostLinks.clampPostsPerProfile(count)
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val fileStamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        val resultRelPath = "AI-Tasks/facebook-post-links-$fileStamp.md"
        val artifactRelPaths = listOf(
            "AI-Tasks/facebook-post-links-$fileStamp-results.txt",
            "AI-Tasks/facebook-post-links-$fileStamp-results.csv",
            "AI-Tasks/facebook-post-links-$fileStamp-results.json",
            "AI-Tasks/facebook-post-links-$fileStamp-summary.json"
        )

        val task = AgentTaskEntity(
            id = taskId,
            title = "Facebook Post Links Collector",
            prompt = "Trained task (no AI) — Facebook Post Links Collector: collect the latest $safeCount post links from ${profiles.size} Facebook profile(s), method ${mode.displayName()}. Runs deterministically without AI; pauses as Needs you only on a Facebook login wall.",
            status = "PENDING",
            progressPercent = 0,
            currentOperation = "Starting collector",
            createdAt = now,
            updatedAt = now
        )

        lastFacebookCollectRun.value = FacebookCollectRunResult(
            taskId = taskId,
            requestedCount = safeCount,
            totalProfiles = profiles.size,
            running = true,
            mode = mode
        )

        val collector = FacebookLinksCollector(browserController)

        val job = scope.launch {
            val stepCounter = AtomicInteger(0)
            val collected = mutableListOf<FacebookProfileResult>()
            var terminalStatus = "COMPLETED"
            var terminalError: String? = null
            var stopNote: String? = null
            var loginStoppedLabel: String? = null

            suspend fun recordStep(message: String) {
                try {
                    taskDao.insertStep(
                        TaskStepEntity(
                            taskId = taskId,
                            stepNumber = stepCounter.incrementAndGet(),
                            description = message,
                            toolName = "facebook_post_links_collector",
                            status = "COMPLETED"
                        )
                    )
                    val t = taskDao.getTaskById(taskId)
                    if (t != null && (t.status == "RUNNING" || t.status == "PENDING")) {
                        taskDao.updateTask(t.copy(currentOperation = message, updatedAt = System.currentTimeMillis()))
                    }
                } catch (ignored: Exception) {
                }
            }

            suspend fun setTaskStatus(status: String, operation: String? = null) {
                val t = taskDao.getTaskById(taskId) ?: return
                taskDao.updateTask(
                    t.copy(
                        status = status,
                        currentOperation = operation ?: t.currentOperation,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            // Enters the Needs-you state: task WAITING (fires the "Needs
            // you" notification) and, unless the user already holds the
            // browser, the control mode that makes the Browser screen
            // offer its hand-back bar.
            suspend fun enterNeedsYou(operation: String) {
                setTaskStatus("WAITING", operation)
                if (browserController.controlMode.value != BrowserControlMode.MANUAL_CONTROL) {
                    browserController.updateControlMode(BrowserControlMode.WAITING_APPROVAL)
                }
            }

            val hooks = object : FacebookLinksCollector.Hooks {
                override suspend fun onStep(message: String) = recordStep(message)

                override suspend fun onWaitStart(reason: String) {
                    recordStep(reason)
                    enterNeedsYou("Needs you — hand the browser back to continue collecting")
                    // The collector itself polls for the hand-back.
                }

                override suspend fun onWaitEnd(resumed: Boolean) {
                    if (resumed) setTaskStatus("RUNNING", "Resumed after hand-back")
                }

                override suspend fun onLoginWall(profileUrl: String): FacebookLinksCollector.LoginWallDecision {
                    recordStep("Login wall at $profileUrl — Needs you: log in to Facebook in the Browser tab, then hand control back")
                    enterNeedsYou("Needs you — log in to Facebook")
                    // Poll here (the collector does not poll for login):
                    // hand-back → retry this profile; cancel/timeout →
                    // stop the run honestly.
                    val deadline = System.currentTimeMillis() + FacebookLinksCollector.CONTROL_WAIT_TIMEOUT_MS
                    while (System.currentTimeMillis() < deadline) {
                        val t = taskDao.getTaskById(taskId)
                        if (t == null || t.status == "CANCELLED") {
                            return FacebookLinksCollector.LoginWallDecision.STOP_RUN
                        }
                        when (browserController.controlMode.value) {
                            BrowserControlMode.AGENT_ACTIVE -> {
                                setTaskStatus("RUNNING", "Login done — resuming collection")
                                return FacebookLinksCollector.LoginWallDecision.RETRY_PROFILE
                            }
                            BrowserControlMode.STANDBY ->
                                return FacebookLinksCollector.LoginWallDecision.STOP_RUN
                            else -> delay(600)
                        }
                    }
                    return FacebookLinksCollector.LoginWallDecision.STOP_RUN
                }
            }

            try {
                taskDao.insertTask(task)
                if (saveList) {
                    try {
                        workspaceManager.createDirectory("AI-Tasks")
                        workspaceManager.writeFile(FACEBOOK_PROFILES_FILE, profiles.joinToString("\n"))
                    } catch (ignored: Exception) {
                    }
                }
                setTaskStatus("RUNNING", "Collecting post links")
                recordStep("Collector started: ${profiles.size} profile(s), $safeCount link(s) each, method ${mode.displayName()} — no AI, deterministic run")
                if (parse.duplicatesRemoved > 0) {
                    recordStep("${parse.duplicatesRemoved} duplicate source line(s) removed before the run")
                }
                for (invalidLine in parse.invalidLines) {
                    recordStep("Line ${invalidLine.lineNumber} is not a valid URL — skipped, not run: ${invalidLine.raw}")
                }

                val outcome = collector.collectAll(profiles, safeCount, hooks, mode) { result ->
                    collected.add(result)
                    val t = taskDao.getTaskById(taskId)
                    if (t != null && t.status != "CANCELLED") {
                        taskDao.updateTask(
                            t.copy(
                                progressPercent = collected.size * 100 / profiles.size.coerceAtLeast(1),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    lastFacebookCollectRun.value = lastFacebookCollectRun.value?.copy(results = collected.toList())
                }
                stopNote = outcome.stopReason
                if (outcome.stoppedEarly) {
                    terminalStatus = "FAILED"
                    terminalError = outcome.stopReason
                    if (outcome.stoppedForLogin) {
                        // FIX 4: a login-stopped run must SAY so on the
                        // task's final line — the profile waiting on the
                        // login plus every profile never attempted count
                        // as pending.
                        val pending = profiles.size - collected.count {
                            it.status != FacebookProfileStatus.LOGIN_REQUIRED
                        }
                        loginStoppedLabel = "Stopped — waiting for login " +
                            "($pending profile${if (pending == 1) "" else "s"} pending)"
                    }
                }
            } catch (e: CancellationException) {
                // Stop Task: whatever was collected is still written below.
                terminalStatus = "FAILED"
                stopNote = "Run cancelled before finishing."
            } catch (e: Exception) {
                terminalStatus = "FAILED"
                terminalError = ProviderErrors.userMessage(e)
            }

            // Result files — always written once the task exists (partial
            // results included), and registered in Created by Spark. The
            // .md is the original artifact (format unchanged); Round A
            // adds results.txt/.csv + results.json + summary.json next
            // to it, all from the same collected results.
            val summary = FacebookPostLinks.statusSummaryLine(collected)
            var writtenPath: String? = null
            val writtenArtifacts = mutableListOf<String>()
            try {
                if (taskDao.getTaskById(taskId) != null) {
                    workspaceManager.createDirectory("AI-Tasks")
                    suspend fun writeAndRegister(relPath: String, content: String): Boolean {
                        if (!workspaceManager.writeFile(relPath, content)) return false
                        try {
                            val meta = workspaceManager.getFileMetadata(relPath)
                            agentCreatedFileDao.insertCreatedFile(
                                AgentCreatedFileEntity(
                                    relativePath = relPath,
                                    name = meta.name,
                                    taskId = taskId,
                                    mimeType = meta.mimeType,
                                    sizeBytes = meta.sizeBytes,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                        } catch (ignored: Exception) {
                        }
                        return true
                    }
                    val md = FacebookPostLinks.formatResultsMarkdown(collected, safeCount, Date(now), stopNote)
                    if (writeAndRegister(resultRelPath, md)) {
                        writtenPath = resultRelPath
                        writtenArtifacts += resultRelPath
                    }
                    val config = FacebookRunArtifacts.RunConfig(
                        sources = profiles,
                        perSourceLimit = safeCount,
                        mode = mode,
                        startedAtMs = now,
                        finishedAtMs = System.currentTimeMillis(),
                        invalidSourceLines = parse.invalidLines.size,
                        duplicateSourceLines = parse.duplicatesRemoved
                    )
                    val artifactContents = listOf(
                        artifactRelPaths[0] to
                            FacebookRunArtifacts.buildResultsTxt(collected),
                        artifactRelPaths[1] to
                            FacebookRunArtifacts.buildResultsCsv(collected, config),
                        artifactRelPaths[2] to
                            FacebookRunArtifacts.buildResultsJson(collected, config),
                        artifactRelPaths[3] to
                            FacebookRunArtifacts.buildSummaryJson(collected, config)
                    )
                    for ((relPath, content) in artifactContents) {
                        if (writeAndRegister(relPath, content)) {
                            writtenArtifacts += relPath
                        }
                    }
                    if (writtenArtifacts.size > 1) {
                        recordStep("Artifacts written: ${writtenArtifacts.joinToString(", ")}")
                    }
                }
            } catch (ignored: Exception) {
            }

            val current = taskDao.getTaskById(taskId)
            if (current != null && current.status != "CANCELLED") {
                taskDao.updateTask(
                    current.copy(
                        status = terminalStatus,
                        progressPercent = if (terminalStatus == "COMPLETED") 100 else current.progressPercent,
                        currentOperation = if (terminalStatus == "COMPLETED") {
                            "Done — $summary"
                        } else {
                            loginStoppedLabel ?: terminalError ?: stopNote ?: "Stopped"
                        },
                        errorMessage = if (terminalStatus == "FAILED") (terminalError ?: stopNote) else null,
                        createdFilesJson = writtenArtifacts.takeIf { it.isNotEmpty() }
                            ?.let { org.json.JSONArray(it).toString() },
                        updatedAt = System.currentTimeMillis(),
                        completedAt = System.currentTimeMillis()
                    )
                )
            }
            // The run is over: release the browser back to standby (never
            // yank it out of the user's hands if they hold it).
            when (browserController.controlMode.value) {
                BrowserControlMode.AGENT_ACTIVE,
                BrowserControlMode.WAITING_APPROVAL,
                BrowserControlMode.PAUSED ->
                    browserController.updateControlMode(BrowserControlMode.STANDBY)
                else -> Unit
            }
            lastFacebookCollectRun.value = FacebookCollectRunResult(
                taskId = taskId,
                requestedCount = safeCount,
                totalProfiles = profiles.size,
                results = collected.toList(),
                resultFilePath = writtenPath,
                running = false,
                summaryLine = loginStoppedLabel
                    ?: (summary + (stopNote?.let { " Stopped early: $it" } ?: "")),
                mode = mode,
                artifactPaths = writtenArtifacts.toList()
            )
        }
        agentLoop.registerJob(taskId, job)
        return taskId
    }

    /**
     * TRAINED TASK #2 (Mode 1) — Screenshots Collection, launched from
     * the Tasks screen. Spark's second core feature and the sibling of
     * the links collector: for every Facebook profile it captures the
     * profile page, discovers the latest posts with Task #1's core,
     * then captures each post; any other URL gets a single direct
     * screenshot. Fully deterministic: ZERO provider/LLM calls. The
     * chain visits every source in order and pauses ONLY on a login
     * wall (task WAITING / Needs you, until the user logs in and hands
     * control back). Everything lands in
     * AI-Tasks/screenshots-<yyyyMMdd-HHmm>/ (per-source subfolders,
     * index.md + summary.json) and every file is registered under
     * Files → Created by Spark. Returns the task id immediately; the
     * run is cancellable via the normal Stop Task path (captured shots
     * and the index/summary are still written for what exists).
     */
    fun launchScreenshotsCollectorFromText(
        rawText: String,
        postsPerProfile: Int,
        saveList: Boolean,
        mode: FacebookExtractionMode
    ): String {
        val parse = FacebookPostLinks.parseCollectorInput(rawText)
        val sources = parse.sources
        val labels = parse.labels
        val safePosts = ScreenshotsCollection.clampPostsPerProfile(postsPerProfile)
        val taskId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val fileStamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        val runRel = "AI-Tasks/screenshots-$fileStamp"
        val indexRelPath = "$runRel/index.md"
        val summaryRelPath = "$runRel/summary.json"

        val task = AgentTaskEntity(
            id = taskId,
            title = "Screenshots Collector",
            prompt = "Trained task (no AI) — Screenshots Collection: capture the profile page plus the latest $safePosts post screenshot(s) for ${sources.size} source(s), discovery method ${mode.displayName()}. Runs deterministically without AI; pauses as Needs you only on a Facebook login wall.",
            status = "PENDING",
            progressPercent = 0,
            currentOperation = "Starting screenshots collection",
            createdAt = now,
            updatedAt = now
        )

        lastScreenshotsRun.value = ScreenshotsRunResult(
            taskId = taskId,
            postsPerProfile = safePosts,
            totalSources = sources.size,
            runFolderPath = runRel,
            running = true,
            mode = mode
        )

        val linksCollector = FacebookLinksCollector(browserController)
        val runDir = workspaceManager.resolve(runRel)
        val collector = ScreenshotsCollector(browserController, linksCollector, runDir, runRel)

        val job = scope.launch {
            val stepCounter = AtomicInteger(0)
            val collected = mutableListOf<ScreenshotSourceResult>()
            var terminalStatus = "COMPLETED"
            var terminalError: String? = null
            var stopNote: String? = null
            var loginStoppedLabel: String? = null

            suspend fun recordStep(message: String) {
                try {
                    taskDao.insertStep(
                        TaskStepEntity(
                            taskId = taskId,
                            stepNumber = stepCounter.incrementAndGet(),
                            description = message,
                            toolName = "screenshots_collector",
                            status = "COMPLETED"
                        )
                    )
                    val t = taskDao.getTaskById(taskId)
                    if (t != null && (t.status == "RUNNING" || t.status == "PENDING")) {
                        taskDao.updateTask(t.copy(currentOperation = message, updatedAt = System.currentTimeMillis()))
                    }
                } catch (ignored: Exception) {
                }
            }

            suspend fun setTaskStatus(status: String, operation: String? = null) {
                val t = taskDao.getTaskById(taskId) ?: return
                taskDao.updateTask(
                    t.copy(
                        status = status,
                        currentOperation = operation ?: t.currentOperation,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            // Enters the Needs-you state: task WAITING (fires the "Needs
            // you" notification) and, unless the user already holds the
            // browser, the control mode that makes the Browser screen
            // offer its hand-back bar.
            suspend fun enterNeedsYou(operation: String) {
                setTaskStatus("WAITING", operation)
                if (browserController.controlMode.value != BrowserControlMode.MANUAL_CONTROL) {
                    browserController.updateControlMode(BrowserControlMode.WAITING_APPROVAL)
                }
            }

            val hooks = object : FacebookLinksCollector.Hooks {
                override suspend fun onStep(message: String) = recordStep(message)

                override suspend fun onWaitStart(reason: String) {
                    recordStep(reason)
                    enterNeedsYou("Needs you — hand the browser back to continue capturing")
                    // The collector itself polls for the hand-back.
                }

                override suspend fun onWaitEnd(resumed: Boolean) {
                    if (resumed) setTaskStatus("RUNNING", "Resumed after hand-back")
                }

                override suspend fun onLoginWall(profileUrl: String): FacebookLinksCollector.LoginWallDecision {
                    recordStep("Login wall at $profileUrl — Needs you: log in to Facebook in the Browser tab, then hand control back")
                    enterNeedsYou("Needs you — log in to Facebook")
                    // Poll here (the collector does not poll for login):
                    // hand-back → retry this source; cancel/timeout →
                    // stop the run honestly.
                    val deadline = System.currentTimeMillis() + FacebookLinksCollector.CONTROL_WAIT_TIMEOUT_MS
                    while (System.currentTimeMillis() < deadline) {
                        val t = taskDao.getTaskById(taskId)
                        if (t == null || t.status == "CANCELLED") {
                            return FacebookLinksCollector.LoginWallDecision.STOP_RUN
                        }
                        when (browserController.controlMode.value) {
                            BrowserControlMode.AGENT_ACTIVE -> {
                                setTaskStatus("RUNNING", "Login done — resuming capture")
                                return FacebookLinksCollector.LoginWallDecision.RETRY_PROFILE
                            }
                            BrowserControlMode.STANDBY ->
                                return FacebookLinksCollector.LoginWallDecision.STOP_RUN
                            else -> delay(600)
                        }
                    }
                    return FacebookLinksCollector.LoginWallDecision.STOP_RUN
                }
            }

            try {
                taskDao.insertTask(task)
                workspaceManager.createDirectory(runRel)
                if (saveList) {
                    try {
                        workspaceManager.createDirectory("AI-Tasks")
                        workspaceManager.writeFile(SCREENSHOT_PROFILES_FILE, sources.joinToString("\n"))
                    } catch (ignored: Exception) {
                    }
                }
                setTaskStatus("RUNNING", "Collecting screenshots")
                recordStep("Screenshots collection started: ${sources.size} source(s), profile + $safePosts post screenshot(s) per profile, discovery ${mode.displayName()} — no AI, deterministic run")
                if (parse.duplicatesRemoved > 0) {
                    recordStep("${parse.duplicatesRemoved} duplicate source line(s) removed before the run")
                }
                for (invalidLine in parse.invalidLines) {
                    recordStep("Line ${invalidLine.lineNumber} is not a valid URL — skipped, not run: ${invalidLine.raw}")
                }

                val outcome = collector.collectAll(sources, labels, safePosts, hooks, mode) { result ->
                    collected.add(result)
                    val t = taskDao.getTaskById(taskId)
                    if (t != null && t.status != "CANCELLED") {
                        taskDao.updateTask(
                            t.copy(
                                progressPercent = collected.size * 100 / sources.size.coerceAtLeast(1),
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    lastScreenshotsRun.value = lastScreenshotsRun.value?.copy(results = collected.toList())
                }
                stopNote = outcome.stopReason
                if (outcome.stoppedEarly) {
                    terminalStatus = "FAILED"
                    terminalError = outcome.stopReason
                    if (outcome.stoppedForLogin) {
                        val pending = sources.size - collected.count {
                            it.status != FacebookProfileStatus.LOGIN_REQUIRED
                        }
                        loginStoppedLabel = "Stopped — waiting for login " +
                            "($pending source${if (pending == 1) "" else "s"} pending)"
                    }
                }
            } catch (e: CancellationException) {
                // Stop Task: whatever was captured is still indexed below.
                terminalStatus = "FAILED"
                stopNote = "Run cancelled before finishing."
            } catch (e: Exception) {
                terminalStatus = "FAILED"
                terminalError = ProviderErrors.userMessage(e)
            }

            // Index + summary — always written once the task exists
            // (partial results included), and EVERY file of the run
            // (each image, its JPEG copy when present, the index and
            // the summary) is registered in Created by Spark.
            val summary = ScreenshotsCollection.statusSummaryLine(collected)
            val shotsTaken = ScreenshotsCollection.totalShotsTaken(collected)
            var writtenIndex: String? = null
            val writtenArtifacts = mutableListOf<String>()
            try {
                if (taskDao.getTaskById(taskId) != null) {
                    workspaceManager.createDirectory(runRel)
                    fun registerExisting(relPath: String): Boolean {
                        return try {
                            val meta = workspaceManager.getFileMetadata(relPath)
                            agentCreatedFileDao.insertCreatedFile(
                                AgentCreatedFileEntity(
                                    relativePath = relPath,
                                    name = meta.name,
                                    taskId = taskId,
                                    mimeType = meta.mimeType,
                                    sizeBytes = meta.sizeBytes,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                            true
                        } catch (ignored: Exception) {
                            false
                        }
                    }
                    // Images first (the collector wrote them to disk).
                    for (result in collected) {
                        for (shot in result.shots) {
                            if (registerExisting(shot.relativePath)) {
                                writtenArtifacts += shot.relativePath
                            }
                            shot.jpegRelativePath?.let { jpeg ->
                                if (registerExisting(jpeg)) {
                                    writtenArtifacts += jpeg
                                }
                            }
                        }
                    }
                    val config = ScreenshotsCollection.RunConfig(
                        sources = sources,
                        postsPerProfile = safePosts,
                        mode = mode,
                        runFolder = runRel,
                        startedAtMs = now,
                        finishedAtMs = System.currentTimeMillis(),
                        invalidSourceLines = parse.invalidLines.size,
                        duplicateSourceLines = parse.duplicatesRemoved
                    )
                    val indexMd = ScreenshotsCollection.buildIndexMarkdown(collected, config, stopNote)
                    if (workspaceManager.writeFile(indexRelPath, indexMd) && registerExisting(indexRelPath)) {
                        writtenIndex = indexRelPath
                        writtenArtifacts += indexRelPath
                    }
                    val summaryJson = ScreenshotsCollection.buildSummaryJson(collected, config)
                    if (workspaceManager.writeFile(summaryRelPath, summaryJson) && registerExisting(summaryRelPath)) {
                        writtenArtifacts += summaryRelPath
                    }
                    if (writtenArtifacts.isNotEmpty()) {
                        recordStep("Artifacts written: ${writtenArtifacts.joinToString(", ")}")
                    }
                }
            } catch (ignored: Exception) {
            }

            val current = taskDao.getTaskById(taskId)
            if (current != null && current.status != "CANCELLED") {
                taskDao.updateTask(
                    current.copy(
                        status = terminalStatus,
                        progressPercent = if (terminalStatus == "COMPLETED") 100 else current.progressPercent,
                        currentOperation = if (terminalStatus == "COMPLETED") {
                            "Done — $summary · $shotsTaken screenshot${if (shotsTaken == 1) "" else "s"}"
                        } else {
                            loginStoppedLabel ?: terminalError ?: stopNote ?: "Stopped"
                        },
                        errorMessage = if (terminalStatus == "FAILED") (terminalError ?: stopNote) else null,
                        createdFilesJson = writtenArtifacts.takeIf { it.isNotEmpty() }
                            ?.let { org.json.JSONArray(it).toString() },
                        updatedAt = System.currentTimeMillis(),
                        completedAt = System.currentTimeMillis()
                    )
                )
            }
            // The run is over: release the browser back to standby (never
            // yank it out of the user's hands if they hold it).
            when (browserController.controlMode.value) {
                BrowserControlMode.AGENT_ACTIVE,
                BrowserControlMode.WAITING_APPROVAL,
                BrowserControlMode.PAUSED ->
                    browserController.updateControlMode(BrowserControlMode.STANDBY)
                else -> Unit
            }
            lastScreenshotsRun.value = ScreenshotsRunResult(
                taskId = taskId,
                postsPerProfile = safePosts,
                totalSources = sources.size,
                results = collected.toList(),
                runFolderPath = runRel,
                indexFilePath = writtenIndex,
                running = false,
                summaryLine = loginStoppedLabel
                    ?: (summary + " · $shotsTaken screenshot${if (shotsTaken == 1) "" else "s"}" +
                        (stopNote?.let { " Stopped early: $it" } ?: "")),
                mode = mode,
                artifactPaths = writtenArtifacts.toList()
            )
        }
        agentLoop.registerJob(taskId, job)
        return taskId
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
