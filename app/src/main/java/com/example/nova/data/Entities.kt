package com.example.nova.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val sender: String, // "user", "assistant", "system", "tool"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val toolCallsJson: String? = null,
    val toolResultsJson: String? = null,
    val attachedFilesJson: String? = null,
    val generatedFilesJson: String? = null,
    val isStreaming: Boolean = false
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String? = null,
    val status: String = "ACTIVE", // ACTIVE, PAUSED, COMPLETED, RETIRED
    val parentGoalId: String? = null, // One level of subgoal nesting
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "agent_tasks")
data class AgentTaskEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val prompt: String,
    val status: String = "PENDING", // PENDING, RUNNING, PAUSED, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED, SAFETY_STOPPED
    val currentOperation: String = "Initializing",
    val progressPercent: Int = -1, // -1 means indeterminate
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val conversationId: String? = null,
    val requiresApprovalAction: String? = null,
    val pendingApprovalToolName: String? = null,
    val pendingApprovalArgumentsJson: String? = null,
    val goalId: String? = null,
    val cadence: String = "RUN_ONCE", // RUN_ONCE, DAILY, WEEKLY, MONTHLY
    val nextRunTime: Long? = null,
    val citationsJson: String? = null, // JSON array of [{title, url}]
    val createdFilesJson: String? = null,
    val screenshotsJson: String? = null // JSON array of workspace-relative screenshot paths captured during the run
)

@Entity(tableName = "task_runs")
data class TaskRunEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val runTime: Long = System.currentTimeMillis(),
    val status: String, // SUCCESS, FAILED, CANCELLED
    val resultSummary: String? = null,
    val logFileRelPath: String? = null
)

@Entity(tableName = "agent_created_files")
data class AgentCreatedFileEntity(
    @PrimaryKey val relativePath: String,
    val name: String,
    val taskId: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "task_steps")
data class TaskStepEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val stepNumber: Int,
    val description: String,
    val toolName: String? = null,
    val argumentsJson: String? = null,
    val resultJson: String? = null,
    val status: String = "RUNNING", // SUCCESS, FAILED, SKIPPED, RUNNING
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "tool_executions")
data class ToolExecutionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val taskId: String? = null,
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String? = null,
    val status: String = "PENDING", // PENDING, ALLOWED, BLOCKED, ERROR, SUCCESS
    val durationMs: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "browser_tabs")
data class BrowserTabEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "New Tab",
    val url: String = "https://www.google.com",
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = false
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "browser_history")
data class BrowserHistoryEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_entries")
data class MemoryEntryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val key: String,
    val content: String,
    val category: String = "user_preference", // user_preference, project_context, agent_rule, custom
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "secret_metadata")
data class SecretMetadataEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String = "CUSTOM", // WEBSITE_LOGIN, API_KEY, ACCESS_TOKEN, CUSTOM
    val username: String? = null,
    val domain: String? = null, // e.g. "github.com"
    val permissionMode: String = "ASK_EVERY_TIME", // ALLOW_ONCE, ALWAYS_ALLOW, ASK_EVERY_TIME, NEVER_ALLOW
    val encryptedCiphertext: String, // Base64 AES-GCM ciphertext
    val iv: String, // Base64 initialization vector
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null
)

@Entity(tableName = "permission_rules")
data class PermissionRuleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val domain: String, // normalized domain e.g. "github.com" or "*"
    val action: String, // e.g. "browser:autofill", "tool:file_write"
    val target: String = "*", // secret id or resource path
    val policy: String = "ASK", // ALLOW, DENY, ASK
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val key: String,
    val value: String
)
