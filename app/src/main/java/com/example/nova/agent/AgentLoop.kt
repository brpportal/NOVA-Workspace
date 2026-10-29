package com.example.nova.agent

import com.example.nova.ai.AIMessage
import com.example.nova.ai.AIProviderAdapter
import com.example.nova.ai.AIResponse
import com.example.nova.ai.AIToolCall
import com.example.nova.ai.AIToolDefinition
import com.example.nova.ai.ProviderEmptyResponseException
import com.example.nova.ai.ProviderErrors
import com.example.nova.browser.BrowserControlMode
import com.example.nova.browser.BrowserController
import com.example.nova.data.AgentTaskEntity
import com.example.nova.data.ChatDao
import com.example.nova.data.ChatMessageEntity
import com.example.nova.data.TaskDao
import com.example.nova.data.TaskStepEntity
import com.example.nova.data.ToolExecutionEntity
import com.example.nova.security.PermissionDecision
import com.example.nova.security.PermissionManager
import com.example.nova.security.PermissionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.example.nova.workspace.WorkspaceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import com.example.nova.data.AgentCreatedFileDao
import com.example.nova.data.AgentCreatedFileEntity
import com.example.nova.data.ApprovalDao
import com.example.nova.data.ApprovalRecordEntity
import com.example.nova.security.SecretRedactor

class AgentLoop(
    private val taskDao: TaskDao,
    private val chatDao: ChatDao,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val contextBuilder: ContextBuilder,
    private val permissionManager: PermissionManager,
    private val browserController: BrowserController,
    private val workspaceManager: WorkspaceManager,
    private val maxStepsLimit: Int = 25,
    private val approvalDao: ApprovalDao? = null,
    private val agentCreatedFileDao: AgentCreatedFileDao? = null,
    private val onPresenceChange: (PipelinePresence) -> Unit = {}
) {
    // Map of active running jobs per taskId
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Persistent failure memory (Lessons.md in the workspace root). */
    private val lessonsStore = LessonsStore(workspaceManager)

    companion object {
        /** Bounds for stored browser-screenshot evidence per task. */
        const val MAX_TASK_SCREENSHOTS = 12
        const val MAX_SCREENSHOT_BYTES = 5L * 1024 * 1024

        /** Empty provider responses are retried this many times before failing the task. */
        const val MAX_EMPTY_RESPONSE_RETRIES = 2
        const val EMPTY_RETRY_BACKOFF_MS = 1200L

        /** Tools that drive the in-app browser (vs workspace/memory tools). */
        val BROWSER_TOOL_NAMES = setOf(
            "navigate", "get_current_url", "get_page_title", "get_visible_text",
            "find_element", "click", "type", "scroll", "take_screenshot",
            "scroll_down", "scroll_up", "scroll_to_element",
            "get_page_state", "get_set_of_marks", "clear_set_of_marks",
            "observe_page", "click_element", "type_text", "tap",
            "browser_back", "browser_forward", "browser_reload", "wait_for_load",
            "vault_autofill"
        )

        /**
         * Browser actions after which the loop attaches a fresh observation.
         * Scrolling counts (scroll addition): the viewport — and therefore
         * which elements are visible/actionable — changed, so the next
         * decision must be grounded in the post-scroll page.
         */
        val PAGE_CHANGING_TOOLS = setOf(
            "navigate", "click", "click_element", "tap",
            "browser_back", "browser_forward", "browser_reload",
            "scroll", "scroll_down", "scroll_up", "scroll_to_element"
        )
    }

    fun registerJob(taskId: String, job: Job) {
        activeJobs[taskId] = job
    }

    // --- Remembered approval grants (fix round 2, Defect C) ---
    // When a task pauses for approval, the pending action's ApprovalClass
    // is remembered here; when the user approves (NovaRepository calls
    // onApprovalGranted), the class moves into the task's grant set, and
    // later calls of the SAME class in the SAME task execute without
    // re-asking (a history record is still written, marked as a
    // remembered-grant decision).

    private val pendingApprovalClasses = ConcurrentHashMap<String, String>()
    private val taskApprovalGrants = ConcurrentHashMap<String, MutableSet<String>>()

    /** Called by the repository when the user approves a paused task. */
    fun onApprovalGranted(taskId: String) {
        val cls = pendingApprovalClasses.remove(taskId) ?: return
        taskApprovalGrants.getOrPut(taskId) { ConcurrentHashMap.newKeySet() }.add(cls)
    }

    /** Called by the repository when the user rejects a paused task. */
    fun onApprovalRejected(taskId: String) {
        pendingApprovalClasses.remove(taskId)
    }

    private fun hasTaskApprovalGrant(taskId: String, cls: ApprovalClass): Boolean =
        taskApprovalGrants[taskId]?.contains(cls.name) == true

    /**
     * Sends one provider request, retrying EMPTY responses (fix round 2,
     * Defect E): free router tiers sometimes answer HTTP 200 with no
     * choices at all, which used to kill the task with org.json's raw
     * "No value for choices". Empty responses — thrown
     * [ProviderEmptyResponseException], other adapters' equivalent error
     * text, or a returned response with neither text nor tool calls — are
     * retried up to [MAX_EMPTY_RESPONSE_RETRIES] times with a short
     * backoff; each retry is step-noted via [onRetry]. Any other error
     * propagates immediately.
     */
    private suspend fun sendMessageWithEmptyRetry(
        provider: AIProviderAdapter,
        messages: List<AIMessage>,
        tools: List<AIToolDefinition>,
        onRetry: suspend (attempt: Int) -> Unit
    ): AIResponse {
        var retries = 0
        while (true) {
            try {
                val response = provider.sendMessage(messages, tools)
                if (!response.text.isNullOrBlank() || response.toolCalls.isNotEmpty()) {
                    return response
                }
                if (retries >= MAX_EMPTY_RESPONSE_RETRIES) {
                    throw ProviderEmptyResponseException(
                        "Provider returned an empty response (no text, no tool calls) after $retries retries"
                    )
                }
                retries++
                onRetry(retries)
                delay(EMPTY_RETRY_BACKOFF_MS * retries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ProviderErrors.isEmptyResponseError(e) && retries < MAX_EMPTY_RESPONSE_RETRIES) {
                    retries++
                    onRetry(retries)
                    delay(EMPTY_RETRY_BACKOFF_MS * retries)
                } else {
                    throw e
                }
            }
        }
    }

    fun cancelTask(taskId: String) {
        val job = activeJobs.remove(taskId)
        job?.cancel()
    }

    suspend fun runTask(
        task: AgentTaskEntity,
        provider: AIProviderAdapter,
        conversationId: String? = null
    ) = withContext(Dispatchers.IO) {
        val taskId = task.id
        var currentTask = task.copy(
            status = "RUNNING",
            currentOperation = "Assembling context and workspace",
            progressPercent = 5,
            updatedAt = System.currentTimeMillis()
        )
        taskDao.updateTask(currentTask)

        val messages = mutableListOf<AIMessage>()
        messages.add(contextBuilder.buildSystemContext(forTask = true))
        messages.add(AIMessage(role = "user", content = task.prompt))

        // Failure learning: repeated tool errors and terminal task failures
        // are written to Lessons.md (deduplicated there) and read back into
        // the context of future tasks via ContextBuilder.
        val toolFailureCounts = mutableMapOf<String, Int>()
        val lessonRecordedForTools = mutableSetOf<String>()
        var lastFailedStep = ""
        var lastFailedTool: String? = null
        fun noteToolFailure(tool: String, stepDesc: String, errorText: String) {
            lastFailedStep = stepDesc
            lastFailedTool = tool
            val count = (toolFailureCounts[tool] ?: 0) + 1
            toolFailureCounts[tool] = count
            if (count >= 2 && lessonRecordedForTools.add(tool)) {
                lessonsStore.recordLesson(task.title, stepDesc, tool, errorText)
            }
        }

        var stepNumber = 1
        var duplicateCount = 0
        var executedToolCount = 0
        var lastToolCallSignature = ""
        val visitedCitations = mutableListOf<JSONObject>()
        val createdFilePaths = mutableListOf<String>()
        val screenshotPaths = mutableListOf<String>()
        val stepsRecord = mutableListOf<String>()
        val logBuffer = StringBuilder()

        browserController.setPlanAndStep("Autonomous Task: ${task.title}", "Starting execution")

        onPresenceChange(PipelinePresence.WORKING)

        try {
            while (stepNumber <= maxStepsLimit && currentCoroutineContext().isActive) {
                // If browser manual control was triggered, pause task honestly
                if (browserController.controlMode.value == BrowserControlMode.MANUAL_CONTROL) {
                    currentTask = currentTask.copy(
                        status = "PAUSED",
                        currentOperation = "Browser is under MANUAL CONTROL by user. Resume when ready.",
                        updatedAt = System.currentTimeMillis()
                    )
                    taskDao.updateTask(currentTask)
                    onPresenceChange(PipelinePresence.READY)
                    return@withContext
                }

                currentTask = currentTask.copy(
                    currentOperation = "Consulting AI model (Step $stepNumber/$maxStepsLimit)",
                    progressPercent = ((stepNumber.toDouble() / maxStepsLimit) * 90).toInt(),
                    updatedAt = System.currentTimeMillis()
                )
                taskDao.updateTask(currentTask)
                onPresenceChange(PipelinePresence.COMPOSING)

                // Repair the tool history before EVERY request: a tool result
                // whose tool_call id was never declared by the preceding
                // assistant message makes strict providers (Cohere via
                // OpenRouter) fail the whole task with HTTP 400. Repairs are
                // logged, never silent.
                val sanitized = com.example.nova.ai.ToolHistorySanitizer.sanitize(messages)
                if (sanitized.repairs > 0) {
                    val note = "Repaired tool history before the provider request: dropped/trimmed ${sanitized.repairs} invalid tool entr${if (sanitized.repairs == 1) "y" else "ies"} so every tool result matches a declared tool call."
                    stepsRecord.add(note)
                    logBuffer.append("[${Date()}] $note\n")
                    messages.clear()
                    messages.addAll(sanitized.messages)
                }

                val response = sendMessageWithEmptyRetry(
                    provider = provider,
                    messages = messages,
                    tools = toolRegistry.availableTools,
                    onRetry = { attempt ->
                        val note = "Provider returned an empty response — retrying (attempt $attempt of $MAX_EMPTY_RESPONSE_RETRIES)"
                        recordStep(taskId, stepNumber++, "provider_request", "", note, "FAILED")
                        stepsRecord.add(note)
                        logBuffer.append("[${Date()}] $note\n")
                    }
                )

                // Keep the assistant turn in the model history. When the model
                // calls tools, the history MUST contain ONE assistant message
                // carrying BOTH the text and the tool calls — with the
                // provider-issued ids verbatim — because the tool results
                // appended below reference those ids. (Previously only the
                // text was stored, and when the text was blank no assistant
                // message was stored at all, orphaning every tool result and
                // failing the next request.) Per-step narration belongs to
                // the task's step log, NOT the chat — only the final answer
                // is posted to chat, once (fix-round behaviour).
                val assistantText = response.text.orEmpty()
                if (response.toolCalls.isNotEmpty()) {
                    if (assistantText.isNotBlank()) {
                        onPresenceChange(PipelinePresence.RESPONDING)
                    }
                    messages.add(
                        AIMessage(
                            role = "assistant",
                            content = assistantText,
                            toolCalls = response.toolCalls
                        )
                    )
                } else if (assistantText.isNotBlank()) {
                    onPresenceChange(PipelinePresence.RESPONDING)
                    messages.add(AIMessage(role = "assistant", content = assistantText))
                }

                // If no tool calls requested, the model is ending the task.
                if (response.toolCalls.isEmpty()) {
                    // Hollow-completion guard (fix round 2, Defect F): a
                    // task may only be COMPLETED with a real final answer.
                    // Ending with no answer at all used to be stamped
                    // "Task finished successfully" even when no browser
                    // work had happened — now it fails honestly.
                    if (assistantText.isBlank()) {
                        val reason = "Task ended without a result"
                        recordStep(
                            taskId, stepNumber++, "task_result", "",
                            "$reason: the model stopped without producing a final answer ($executedToolCount tool calls executed).",
                            "FAILED"
                        )
                        lessonsStore.recordLesson(
                            goal = task.title,
                            failedStep = "final answer (step ${stepNumber - 1})",
                            toolName = lastFailedTool,
                            rawError = "$reason: empty final answer after $executedToolCount executed tool calls"
                        )
                        currentTask = currentTask.copy(
                            status = "FAILED",
                            currentOperation = "$reason — the model stopped without producing a final answer.",
                            errorMessage = reason,
                            completedAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        taskDao.updateTask(currentTask)
                        onPresenceChange(PipelinePresence.READY)
                        break
                    }
                    if (conversationId != null) {
                        chatDao.insertMessage(
                            ChatMessageEntity(
                                id = UUID.randomUUID().toString(),
                                conversationId = conversationId,
                                sender = "assistant",
                                content = assistantText,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                    currentTask = currentTask.copy(
                        status = "COMPLETED",
                        currentOperation = "Task finished successfully.",
                        progressPercent = 100,
                        completedAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    taskDao.updateTask(currentTask)
                    onPresenceChange(PipelinePresence.READY)
                    break
                }

                // Process tool calls
                for (toolCall in response.toolCalls) {
                    if (!currentCoroutineContext().isActive) break

                    val isFileTool = toolCall.name in listOf("write_file", "create_file", "append_file", "save_file")
                    if (isFileTool) {
                        onPresenceChange(PipelinePresence.MAKING_SOMETHING)
                    } else {
                        onPresenceChange(PipelinePresence.WORKING)
                    }

                    val signature = "${toolCall.name}|${toolCall.argumentsJson}"
                    if (signature == lastToolCallSignature) {
                        duplicateCount++
                        if (duplicateCount >= 3) {
                            throw IllegalStateException("Duplicate tool loop detected: identical action called 3 times in succession.")
                        }
                    } else {
                        duplicateCount = 0
                        lastToolCallSignature = signature
                    }

                    // 0. Unrecognized-action guardrail: a tool call naming a
                    // tool that does not exist is never executed and never
                    // offered for approval — the task halts in the distinct
                    // SAFETY_STOPPED terminal state ("Stopped — safety could
                    // not be confirmed"). The block is recorded in the
                    // approvals history with the standard warning text.
                    if (toolRegistry.getTool(toolCall.name) == null) {
                        val redactedArgs = SecretRedactor.redactSecrets(toolCall.argumentsJson)
                        val warningText = "This action wasn't recognized. Approve it only if you expected it.\nTool: ${toolCall.name}\nArguments: $redactedArgs"
                        try {
                            approvalDao?.insertApproval(
                                ApprovalRecordEntity(
                                    id = UUID.randomUUID().toString(),
                                    actionName = toolCall.name,
                                    toolName = toolCall.name,
                                    argumentsSummary = warningText,
                                    status = "DENIED",
                                    createdAt = System.currentTimeMillis(),
                                    decidedAt = System.currentTimeMillis(),
                                    taskId = taskId
                                )
                            )
                        } catch (ignored: Exception) {}
                        recordStep(taskId, stepNumber++, toolCall.name, toolCall.argumentsJson, "Blocked: unrecognized action was not executed", "BLOCKED")
                        currentTask = currentTask.copy(
                            status = "SAFETY_STOPPED",
                            currentOperation = "Stopped — safety could not be confirmed. Unrecognized action '${toolCall.name}' was blocked and was not executed.",
                            errorMessage = "Unrecognized action blocked by the safety guardrail",
                            completedAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        taskDao.updateTask(currentTask)
                        onPresenceChange(PipelinePresence.READY)
                        return@withContext
                    }

                    // 1. Validation
                    val validation = toolRegistry.validateCall(toolCall.name, toolCall.argumentsJson)
                    if (!validation.isValid) {
                        val errMsg = validation.errorMessage ?: "Invalid tool call arguments"
                        recordStep(taskId, stepNumber++, toolCall.name, toolCall.argumentsJson, errMsg, "FAILED")
                        noteToolFailure(toolCall.name, "${toolCall.name} (step ${stepNumber - 1})", errMsg)
                        messages.add(AIMessage(role = "tool", content = "Error: $errMsg", toolCallId = toolCall.id, name = toolCall.name))
                        continue
                    }

                    val parsedArgs = validation.parsedArguments ?: JSONObject()

                    // 2. Risk-based approval policy (fix round 2, Defect C).
                    // The old flow pushed EVERY call through
                    // PermissionManager with no allow rule on file, so even
                    // get_page_title paused the task for approval — twice in
                    // one small task after the resume replay. Now
                    // ApprovalPolicy is the gate: read-only tools and
                    // ordinary interactions execute directly; only
                    // consequential classes (form submits, sensitive inputs,
                    // purchases, posts, deletions, transfers) can pause —
                    // and only once per class per task (remembered grants).
                    val activeUrl = browserController.getCurrentUrl().data.orEmpty()
                    val domain = PermissionManager.normalizeDomain(activeUrl)
                    val targetElement = if (toolCall.name == "click_element" || toolCall.name == "type_text") {
                        val idx = parsedArgs.optInt("index", -1)
                        if (idx > 0) browserController.observedElement(idx) else null
                    } else {
                        null
                    }
                    val approvalClass = ApprovalPolicy.classify(toolCall.name, parsedArgs, targetElement)

                    if (ApprovalPolicy.requiresApproval(toolCall.name, parsedArgs, targetElement)) {
                        val redactedArgs = SecretRedactor.redactSecrets(toolCall.argumentsJson)
                        val summaryText = "Tool: ${toolCall.name}\nArguments: $redactedArgs"

                        if (hasTaskApprovalGrant(taskId, approvalClass)) {
                            // Remembered grant: execute without pausing,
                            // but the decision is still written to the
                            // approvals history, marked as remembered.
                            try {
                                approvalDao?.insertApproval(
                                    ApprovalRecordEntity(
                                        id = UUID.randomUUID().toString(),
                                        actionName = toolCall.name,
                                        toolName = toolCall.name,
                                        argumentsSummary = "$summaryText\nDecision: APPROVED (remembered grant — ${approvalClass.name} was already approved for this task)",
                                        status = "APPROVED",
                                        createdAt = System.currentTimeMillis(),
                                        decidedAt = System.currentTimeMillis(),
                                        taskId = taskId
                                    )
                                )
                            } catch (ignored: Exception) {}
                        } else {
                            // Persistent user rules (Always Allow / Never
                            // Allow) and one-time tokens still apply on top
                            // of the policy for consequential classes.
                            val permRequest = PermissionRequest(
                                action = toolCall.name,
                                domain = domain,
                                target = toolCall.name,
                                description = "Execute ${toolCall.name} on $domain",
                                isCriticalSecurityOrPayment = false
                            )
                            val decision = permissionManager.evaluate(permRequest)

                            if (decision == PermissionDecision.DENIED) {
                                recordStep(taskId, stepNumber++, toolCall.name, toolCall.argumentsJson, "Permission denied by security policy", "SKIPPED")
                                noteToolFailure(toolCall.name, "${toolCall.name} (step ${stepNumber - 1})", "Permission denied by security policy")
                                messages.add(AIMessage(role = "tool", content = "Permission Denied: Security policy blocks ${toolCall.name}.", toolCallId = toolCall.id, name = toolCall.name))
                                continue
                            }

                            if (decision == PermissionDecision.REQUIRES_USER_APPROVAL) {
                                // Remember the class so approving this
                                // pause grants the class for the whole task.
                                pendingApprovalClasses[taskId] = approvalClass.name

                                // Persist approval record in Room
                                approvalDao?.insertApproval(
                                    ApprovalRecordEntity(
                                        id = UUID.randomUUID().toString(),
                                        actionName = toolCall.name,
                                        toolName = toolCall.name,
                                        argumentsSummary = summaryText,
                                        status = "PENDING",
                                        createdAt = System.currentTimeMillis(),
                                        taskId = taskId
                                    )
                                )

                                // Pause task and request approval in UI
                                currentTask = currentTask.copy(
                                    status = "WAITING_APPROVAL",
                                    currentOperation = "Waiting for user approval to execute '${toolCall.name}'",
                                    requiresApprovalAction = "${toolCall.name} on $domain",
                                    pendingApprovalToolName = toolCall.name,
                                    pendingApprovalArgumentsJson = summaryText,
                                    updatedAt = System.currentTimeMillis()
                                )
                                taskDao.updateTask(currentTask)
                                onPresenceChange(PipelinePresence.NEEDS_APPROVAL)
                                // The Browser banner must show the truth here too:
                                // the agent is stopped and needs the user.
                                browserController.updateControlMode(BrowserControlMode.WAITING_APPROVAL)
                                return@withContext // Task paused until user clicks Approve in UI
                            }
                            // ALLOWED by a persistent rule / one-time token:
                            // fall through to execution.
                        }
                    }

                    // 3. Execution
                    currentTask = currentTask.copy(
                        currentOperation = "Executing ${toolCall.name}",
                        updatedAt = System.currentTimeMillis()
                    )
                    taskDao.updateTask(currentTask)

                    // Drive the browser control state from reality: the first
                    // browser action of a run means the AI is now controlling
                    // the browser, so the Browser tab banner switches from
                    // STANDBY to "AI IS CONTROLLING" and offers Take control.
                    // (Nothing set this before — the banner stayed on STANDBY
                    // for the whole task and Take control never appeared.)
                    if (toolCall.name in BROWSER_TOOL_NAMES &&
                        browserController.controlMode.value == BrowserControlMode.STANDBY
                    ) {
                        browserController.updateControlMode(BrowserControlMode.AGENT_ACTIVE)
                    }

                    val startTime = System.currentTimeMillis()
                    val result = toolExecutor.execute(toolCall.name, parsedArgs, domain)
                    val duration = System.currentTimeMillis() - startTime
                    executedToolCount++

                    // Record citations for browser work
                    val currentUrl = browserController.getCurrentUrl().data.orEmpty()
                    val currentTitle = browserController.getPageTitle().data.orEmpty().ifBlank { currentUrl }
                    if (currentUrl.isNotBlank() && currentUrl != "about:blank" && currentUrl != "none") {
                        if (visitedCitations.none { it.optString("url") == currentUrl }) {
                            visitedCitations.add(JSONObject().apply {
                                put("title", currentTitle)
                                put("url", currentUrl)
                            })
                        }
                    }

                    // Record files created by Spark
                    if (result.success && isFileTool) {
                        val filePath = parsedArgs.optString("path").ifBlank { parsedArgs.optString("relativePath") }
                        if (filePath.isNotBlank()) {
                            createdFilePaths.add(filePath)
                            try {
                                val meta = workspaceManager.getFileMetadata(filePath)
                                agentCreatedFileDao?.insertCreatedFile(
                                    AgentCreatedFileEntity(
                                        relativePath = filePath,
                                        name = meta.name,
                                        taskId = taskId,
                                        mimeType = meta.mimeType,
                                        sizeBytes = meta.sizeBytes,
                                        createdAt = System.currentTimeMillis()
                                    )
                                )
                            } catch (ignored: Exception) {}
                        }
                    }

                    // Collect browser screenshot evidence (real captures only,
                    // from the take_screenshot tool's output files). Bounded:
                    // at most MAX_TASK_SCREENSHOTS paths, each file <= 5 MB.
                    if (toolCall.name == "take_screenshot" && result.success) {
                        for (path in result.outputFiles) {
                            if (screenshotPaths.size >= MAX_TASK_SCREENSHOTS) break
                            if (path in screenshotPaths) continue
                            val sizeOk = try {
                                workspaceManager.getFileMetadata(path).sizeBytes <= MAX_SCREENSHOT_BYTES
                            } catch (ignored: Exception) {
                                false
                            }
                            if (sizeOk) screenshotPaths.add(path)
                        }
                    }

                    recordStep(
                        taskId = taskId,
                        stepNumber = stepNumber++,
                        toolName = toolCall.name,
                        argumentsJson = toolCall.argumentsJson,
                        resultJson = result.output,
                        status = if (result.success) "SUCCESS" else "FAILED"
                    )

                    if (!result.success) {
                        noteToolFailure(
                            toolCall.name,
                            "${toolCall.name} (step ${stepNumber - 1})",
                            result.error ?: result.output
                        )
                    }

                    stepsRecord.add("${toolCall.name}: ${if (result.success) "Success" else "Failed"} - ${result.output.take(80)}")
                    logBuffer.append("[${Date()}] Step ${stepNumber - 1}: ${toolCall.name} -> ${result.output.take(120)}\n")
                    browserController.setPlanAndStep("Task: ${task.title}", "Step ${stepNumber - 1}: ${toolCall.name}")

                    taskDao.insertToolExecution(
                        ToolExecutionEntity(
                            taskId = taskId,
                            toolName = toolCall.name,
                            argumentsJson = toolCall.argumentsJson,
                            resultJson = result.output,
                            status = if (result.success) "SUCCESS" else "ERROR",
                            durationMs = duration
                        )
                    )

                    // Feed tool result back to agent message chain. After a
                    // page-changing browser action, attach a fresh page
                    // observation (URL + title + numbered interactive
                    // elements + text excerpt) so the next decision is
                    // grounded in the real page instead of guessed selectors
                    // — the observe → act → verify loop from the playbook.
                    var feedback = result.output
                    val pageChanged = result.success && (
                        toolCall.name in PAGE_CHANGING_TOOLS ||
                            (toolCall.name == "type_text" && parsedArgs.optBoolean("submit", false))
                        )
                    if (pageChanged) {
                        val observation = try {
                            browserController.observePage()
                        } catch (ignored: Exception) {
                            null
                        }
                        if (observation != null) {
                            feedback += "\n\n" + observation.formatForModel()
                        }
                    }
                    messages.add(
                        AIMessage(
                            role = "tool",
                            content = feedback,
                            toolCallId = toolCall.id,
                            name = toolCall.name
                        )
                    )
                }

                if (stepNumber > maxStepsLimit) {
                    currentTask = currentTask.copy(
                        status = "FAILED",
                        currentOperation = "Reached maximum tool execution limit ($maxStepsLimit steps).",
                        errorMessage = "Step limit exceeded.",
                        updatedAt = System.currentTimeMillis()
                    )
                    taskDao.updateTask(currentTask)
                    lessonsStore.recordLesson(
                        goal = task.title,
                        failedStep = lastFailedStep.ifBlank { "step budget" },
                        toolName = lastFailedTool,
                        rawError = "Step limit exceeded: reached the maximum tool execution limit ($maxStepsLimit steps) without completing the task."
                    )
                }
            }
        } catch (e: CancellationException) {
            currentTask = currentTask.copy(
                status = "CANCELLED",
                currentOperation = "Task cancelled by user.",
                updatedAt = System.currentTimeMillis()
            )
            taskDao.updateTask(currentTask)
        } catch (e: Exception) {
            val isRateLimit = ProviderErrors.isRateLimitError(e)
            if (isRateLimit) {
                onPresenceChange(PipelinePresence.PROVIDER_LIMIT_REACHED)
            }
            // The card shows ONE short human summary. Rate limits (429 —
            // e.g. OpenRouter's free-models-per-day cap) get the dedicated
            // plain-language summary; everything else goes through the
            // nested-JSON drill-down summarizer. The audit-trail step gets
            // the SAME human summary (fix round 2, Defect E) — raw provider
            // JSON no longer appears in steps either.
            val summary = if (isRateLimit) {
                ProviderErrors.RATE_LIMIT_SUMMARY
            } else {
                ProviderErrors.errorSummary(e)
            }
            // Learn from the failure: a deduplicated lesson in Lessons.md,
            // read back into future tasks' context. A 429 classifies as
            // PROVIDER_LIMIT in LessonsStore.
            lessonsStore.recordLesson(
                goal = task.title,
                failedStep = lastFailedStep.ifBlank { "provider request (step $stepNumber)" },
                toolName = lastFailedTool,
                rawError = e.message ?: summary
            )
            try {
                recordStep(
                    taskId = taskId,
                    stepNumber = stepNumber,
                    toolName = "provider_request",
                    argumentsJson = "",
                    resultJson = summary,
                    status = "FAILED"
                )
            } catch (ignored: Exception) {}
            currentTask = currentTask.copy(
                status = "FAILED",
                currentOperation = "Task failed: $summary",
                errorMessage = summary,
                updatedAt = System.currentTimeMillis()
            )
            taskDao.updateTask(currentTask)
        } finally {
            // Return the browser to the user when the run truly ends. When
            // the task is only PAUSED (manual control / waiting approval) the
            // mode that describes that state is left untouched.
            val terminal = currentTask.status in listOf("COMPLETED", "FAILED", "CANCELLED", "SAFETY_STOPPED")
            if (terminal) {
                // The pause is over for good: drop the pending approval
                // class (remembered grants intentionally survive — they
                // belong to this task, e.g. across a user retry).
                pendingApprovalClasses.remove(taskId)
            }
            if (terminal && browserController.controlMode.value in listOf(
                    BrowserControlMode.AGENT_ACTIVE,
                    BrowserControlMode.WAITING_APPROVAL
                )
            ) {
                browserController.updateControlMode(BrowserControlMode.STANDBY)
            }
            onPresenceChange(PipelinePresence.READY)
            try {
                val citJsonStr = if (visitedCitations.isNotEmpty()) {
                    org.json.JSONArray().apply {
                        visitedCitations.forEach { put(it) }
                    }.toString()
                } else null

                val filesJsonStr = if (createdFilePaths.isNotEmpty()) {
                    org.json.JSONArray(createdFilePaths).toString()
                } else null

                val screenshotsJsonStr = if (screenshotPaths.isNotEmpty()) {
                    org.json.JSONArray(screenshotPaths).toString()
                } else null

                currentTask = currentTask.copy(
                    citationsJson = citJsonStr,
                    createdFilesJson = filesJsonStr,
                    screenshotsJson = screenshotsJsonStr,
                    updatedAt = System.currentTimeMillis()
                )
                taskDao.updateTask(currentTask)

                val datePrefix = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val cleanTitle = task.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(25).trimEnd('_')
                val folderName = "${datePrefix}_$cleanTitle"
                workspaceManager.saveTaskRun(
                    taskFolderName = folderName,
                    goal = task.prompt,
                    plan = browserController.currentPlan.value,
                    status = currentTask.status,
                    steps = stepsRecord,
                    logText = logBuffer.toString(),
                    resultSummary = currentTask.currentOperation
                )
                workspaceManager.updateTaskIndex(
                    taskFolderName = folderName,
                    status = currentTask.status,
                    summary = currentTask.currentOperation
                )
            } catch (ignored: Exception) {}
            activeJobs.remove(taskId)
        }
    }

    private suspend fun recordStep(
        taskId: String,
        stepNumber: Int,
        toolName: String,
        argumentsJson: String,
        resultJson: String,
        status: String
    ) {
        taskDao.insertStep(
            TaskStepEntity(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                stepNumber = stepNumber,
                description = "Executed $toolName",
                toolName = toolName,
                argumentsJson = argumentsJson,
                resultJson = resultJson,
                status = status,
                timestamp = System.currentTimeMillis()
            )
        )
    }
}
