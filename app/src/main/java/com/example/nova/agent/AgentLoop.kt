package com.example.nova.agent

import com.example.nova.ai.AIMessage
import com.example.nova.ai.AIProviderAdapter
import com.example.nova.ai.AIToolCall
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
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.example.nova.workspace.WorkspaceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AgentLoop(
    private val taskDao: TaskDao,
    private val chatDao: ChatDao,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val contextBuilder: ContextBuilder,
    private val permissionManager: PermissionManager,
    private val browserController: BrowserController,
    private val workspaceManager: WorkspaceManager,
    private val maxStepsLimit: Int = 25
) {
    // Map of active running jobs per taskId
    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun registerJob(taskId: String, job: Job) {
        activeJobs[taskId] = job
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
        messages.add(contextBuilder.buildSystemContext())
        messages.add(AIMessage(role = "user", content = task.prompt))

        // Chat gets ONE concise status line per task run — step-by-step
        // narration belongs in the task detail/log, not as chat bubbles.
        if (conversationId != null) {
            postToChatOnce(conversationId, "system", "🛠 Working on it — follow progress in the **Tasks** tab.")
        }

        var stepNumber = 1
        var duplicateCount = 0
        var lastToolCallSignature = ""
        val stepsRecord = mutableListOf<String>()
        val logBuffer = StringBuilder()

        browserController.setPlanAndStep("Autonomous Task: ${task.title}", "Starting execution")

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
                    return@withContext
                }

                currentTask = currentTask.copy(
                    currentOperation = "Consulting AI model (Step $stepNumber/$maxStepsLimit)",
                    progressPercent = ((stepNumber.toDouble() / maxStepsLimit) * 90).toInt(),
                    updatedAt = System.currentTimeMillis()
                )
                taskDao.updateTask(currentTask)

                val response = provider.sendMessage(messages, toolRegistry.availableTools)

                // Assistant text stays in the agent's own message chain. It is
                // NOT mirrored into chat per step (that produced repeated
                // narration bubbles); only the final answer is posted, once.
                val assistantText = response.text.orEmpty()
                if (assistantText.isNotBlank()) {
                    messages.add(AIMessage(role = "assistant", content = assistantText))
                }

                // If no tool calls requested, the agent completed the task!
                if (response.toolCalls.isEmpty()) {
                    currentTask = currentTask.copy(
                        status = "COMPLETED",
                        currentOperation = "Task finished successfully.",
                        progressPercent = 100,
                        completedAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    taskDao.updateTask(currentTask)
                    if (conversationId != null && assistantText.isNotBlank()) {
                        postToChatOnce(conversationId, "assistant", assistantText)
                    }
                    break
                }

                // Process tool calls
                for (toolCall in response.toolCalls) {
                    if (!currentCoroutineContext().isActive) break

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

                    // 1. Validation
                    val validation = toolRegistry.validateCall(toolCall.name, toolCall.argumentsJson)
                    if (!validation.isValid) {
                        val errMsg = validation.errorMessage ?: "Invalid tool call arguments"
                        recordStep(taskId, stepNumber++, toolCall.name, toolCall.argumentsJson, errMsg, "FAILED")
                        messages.add(AIMessage(role = "tool", content = "Error: $errMsg", toolCallId = toolCall.id, name = toolCall.name))
                        continue
                    }

                    val parsedArgs = validation.parsedArguments ?: JSONObject()

                    // 2. Permission Evaluation
                    val activeUrl = browserController.getCurrentUrl().data.orEmpty()
                    val domain = PermissionManager.normalizeDomain(activeUrl)
                    val isSensitive = toolRegistry.isSensitiveOperation(toolCall.name, toolCall.argumentsJson)

                    val permRequest = PermissionRequest(
                        action = toolCall.name,
                        domain = domain,
                        target = toolCall.name,
                        description = "Execute ${toolCall.name} on $domain",
                        isCriticalSecurityOrPayment = isSensitive
                    )

                    val decision = permissionManager.evaluate(permRequest)

                    if (decision == PermissionDecision.DENIED) {
                        recordStep(taskId, stepNumber++, toolCall.name, toolCall.argumentsJson, "Permission denied by security policy", "SKIPPED")
                        messages.add(AIMessage(role = "tool", content = "Permission Denied: Security policy blocks ${toolCall.name}.", toolCallId = toolCall.id, name = toolCall.name))
                        continue
                    }

                    if (decision == PermissionDecision.REQUIRES_USER_APPROVAL) {
                        // Pause task and request approval in UI
                        currentTask = currentTask.copy(
                            status = "WAITING_APPROVAL",
                            currentOperation = "Waiting for user approval to execute '${toolCall.name}'",
                            requiresApprovalAction = "${toolCall.name} on $domain",
                            pendingApprovalToolName = toolCall.name,
                            pendingApprovalArgumentsJson = toolCall.argumentsJson,
                            updatedAt = System.currentTimeMillis()
                        )
                        taskDao.updateTask(currentTask)
                        return@withContext // Task paused until user clicks Approve in UI
                    }

                    // 3. Execution
                    currentTask = currentTask.copy(
                        currentOperation = "Executing ${toolCall.name}",
                        updatedAt = System.currentTimeMillis()
                    )
                    taskDao.updateTask(currentTask)

                    val startTime = System.currentTimeMillis()
                    val result = toolExecutor.execute(toolCall.name, parsedArgs, domain)
                    val duration = System.currentTimeMillis() - startTime

                    recordStep(
                        taskId = taskId,
                        stepNumber = stepNumber++,
                        toolName = toolCall.name,
                        argumentsJson = toolCall.argumentsJson,
                        resultJson = result.output,
                        status = if (result.success) "SUCCESS" else "FAILED"
                    )

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

                    // Feed tool result back to agent message chain
                    messages.add(
                        AIMessage(
                            role = "tool",
                            content = result.output,
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
            currentTask = currentTask.copy(
                status = "FAILED",
                currentOperation = "Task failed: ${e.message}",
                errorMessage = e.message,
                updatedAt = System.currentTimeMillis()
            )
            taskDao.updateTask(currentTask)
        } finally {
            try {
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

    /**
     * Posts a message to the conversation, skipping it when the immediately
     * preceding non-user message already has identical content (prevents the
     * duplicate-bubble problem, e.g. the same status line on task resume).
     */
    private suspend fun postToChatOnce(conversationId: String, sender: String, content: String) {
        try {
            val last = chatDao.getMessagesForConversation(conversationId)
                .firstOrNull()
                ?.lastOrNull { it.sender != "user" }
            if (last != null && last.content == content) return
            chatDao.insertMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    sender = sender,
                    content = content,
                    timestamp = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            // Chat mirroring must never fail the task itself.
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
