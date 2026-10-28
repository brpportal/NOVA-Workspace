package com.example.nova.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.nova.data.AppDatabase
import com.example.nova.data.NovaRepository

class SparkTaskWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getDatabase(applicationContext)
            val repository = NovaRepository.getInstance(applicationContext, db)
            repository.executeDueScheduledTasks()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
