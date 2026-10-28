package com.example.nova.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "approval_records")
data class ApprovalRecordEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val actionName: String,
    val toolName: String,
    val argumentsSummary: String, // Redacted secrets
    val status: String, // PENDING, APPROVED, DENIED, TIMED_OUT
    val createdAt: Long = System.currentTimeMillis(),
    val decidedAt: Long? = null,
    val taskId: String? = null
)

@Dao
interface ApprovalDao {
    @Query("SELECT * FROM approval_records ORDER BY createdAt DESC")
    fun getAllApprovals(): Flow<List<ApprovalRecordEntity>>

    @Query("SELECT * FROM approval_records WHERE status = 'PENDING'")
    suspend fun getPendingApprovals(): List<ApprovalRecordEntity>

    @Query("SELECT * FROM approval_records WHERE id = :id LIMIT 1")
    suspend fun getApprovalById(id: String): ApprovalRecordEntity?

    @Query("SELECT * FROM approval_records WHERE taskId = :taskId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getApprovalForTask(taskId: String): ApprovalRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApproval(record: ApprovalRecordEntity)

    @Update
    suspend fun updateApproval(record: ApprovalRecordEntity)
}
