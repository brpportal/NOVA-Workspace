package com.example.nova.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY updatedAt DESC")
    fun getActiveConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<ChatMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Update
    suspend fun updateMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY updatedAt DESC")
    fun getAllTasks(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'PAUSED') ORDER BY updatedAt DESC")
    fun getActiveTasks(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): AgentTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: AgentTaskEntity)

    @Update
    suspend fun updateTask(task: AgentTaskEntity)

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: String)

    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY stepNumber ASC")
    fun getStepsForTask(taskId: String): Flow<List<TaskStepEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: TaskStepEntity)

    @Update
    suspend fun updateStep(step: TaskStepEntity)

    @Query("SELECT * FROM tool_executions ORDER BY timestamp DESC LIMIT 100")
    fun getAllToolExecutions(): Flow<List<ToolExecutionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertToolExecution(execution: ToolExecutionEntity)
}

@Dao
interface BrowserDao {
    @Query("SELECT * FROM browser_tabs ORDER BY lastActiveAt DESC")
    fun getAllTabs(): Flow<List<BrowserTabEntity>>

    @Query("SELECT * FROM browser_tabs WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveTab(): BrowserTabEntity?

    @Query("SELECT * FROM browser_tabs WHERE id = :id LIMIT 1")
    suspend fun getTabById(id: String): BrowserTabEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTab(tab: BrowserTabEntity)

    @Update
    suspend fun updateTab(tab: BrowserTabEntity)

    @Query("DELETE FROM browser_tabs WHERE id = :id")
    suspend fun deleteTabById(id: String)

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: String)

    @Query("SELECT * FROM browser_history ORDER BY visitedAt DESC LIMIT 200")
    fun getHistory(): Flow<List<BrowserHistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: BrowserHistoryEntry)

    @Query("DELETE FROM browser_history")
    suspend fun clearHistory()
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_entries ORDER BY updatedAt DESC")
    fun getAllMemories(): Flow<List<MemoryEntryEntity>>

    @Query("SELECT * FROM memory_entries WHERE `key` = :key LIMIT 1")
    suspend fun getMemoryByKey(key: String): MemoryEntryEntity?

    @Query("SELECT * FROM memory_entries WHERE content LIKE '%' || :query || '%' OR `key` LIKE '%' || :query || '%'")
    suspend fun searchMemories(query: String): List<MemoryEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntryEntity)

    @Update
    suspend fun updateMemory(memory: MemoryEntryEntity)

    @Query("DELETE FROM memory_entries WHERE id = :id")
    suspend fun deleteMemoryById(id: String)
}

@Dao
interface SecretDao {
    @Query("SELECT * FROM secret_metadata ORDER BY updatedAt DESC")
    fun getAllSecrets(): Flow<List<SecretMetadataEntity>>

    @Query("SELECT * FROM secret_metadata WHERE id = :id LIMIT 1")
    suspend fun getSecretById(id: String): SecretMetadataEntity?

    @Query("SELECT * FROM secret_metadata WHERE domain = :domain")
    suspend fun getSecretsForDomain(domain: String): List<SecretMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSecret(secret: SecretMetadataEntity)

    @Update
    suspend fun updateSecret(secret: SecretMetadataEntity)

    @Query("DELETE FROM secret_metadata WHERE id = :id")
    suspend fun deleteSecretById(id: String)
}

@Dao
interface PermissionDao {
    @Query("SELECT * FROM permission_rules ORDER BY createdAt DESC")
    fun getAllRules(): Flow<List<PermissionRuleEntity>>

    @Query("SELECT * FROM permission_rules WHERE (domain = :domain OR domain = '*') AND action = :action LIMIT 1")
    suspend fun findRule(domain: String, action: String): PermissionRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: PermissionRuleEntity)

    @Query("DELETE FROM permission_rules WHERE id = :id")
    suspend fun deleteRuleById(id: String)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings")
    fun getAllSettings(): Flow<List<AppSettingsEntity>>

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun getSetting(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setSetting(setting: AppSettingsEntity)

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    suspend fun deleteSetting(key: String)
}
