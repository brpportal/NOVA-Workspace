package com.example.nova.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        ChatMessageEntity::class,
        AgentTaskEntity::class,
        TaskStepEntity::class,
        ToolExecutionEntity::class,
        BrowserTabEntity::class,
        BookmarkEntity::class,
        BrowserHistoryEntry::class,
        MemoryEntryEntity::class,
        SecretMetadataEntity::class,
        PermissionRuleEntity::class,
        AppSettingsEntity::class,
        ApprovalRecordEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun taskDao(): TaskDao
    abstract fun browserDao(): BrowserDao
    abstract fun memoryDao(): MemoryDao
    abstract fun secretDao(): SecretDao
    abstract fun permissionDao(): PermissionDao
    abstract fun settingsDao(): SettingsDao
    abstract fun approvalDao(): ApprovalDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nova_workspace.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
