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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

        var stepNumber = 1
        var duplicateCount = 0
        var lastToolCallSignature = ""

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

                // If response has assistant text
                val assistantText = response.text.orEmpty()
                if (assistantText.isNotBlank()) {
                    messages.add(AIMessage(role = "assistant", content = assistantText))
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
