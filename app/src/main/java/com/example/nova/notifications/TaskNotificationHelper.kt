package com.example.nova.notifications

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.nova.data.AgentTaskEntity

/**
 * Posts task-state notifications (Phase 3).
 *
 * A notification is posted when a task transitions INTO one of the watched
 * states (COMPLETED, FAILED, WAITING_APPROVAL, SAFETY_STOPPED). The
 * notification id is derived from task id + status, so if two repository
 * instances (UI + WorkManager) observe the same transition, the second post
 * replaces the first instead of stacking a duplicate.
 */
object TaskNotificationHelper {

    const val CHANNEL_ID = "spark_task_updates"
    private const val CHANNEL_NAME = "Task updates"
    const val PERMISSION_REQUEST_CODE = 1001

    /** Task statuses that warrant a user-facing notification. */
    val NOTIFY_STATUSES = setOf("COMPLETED", "FAILED", "WAITING_APPROVAL", "SAFETY_STOPPED")

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Spark notifies you when a task finishes, fails, or needs your approval."
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    /** Android 13+ runtime permission flow; no-op on older versions. */
    fun requestPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    fun statusText(status: String): String = when (status) {
        "COMPLETED" -> "Task completed"
        "FAILED" -> "Task failed"
        "WAITING_APPROVAL" -> "Needs your approval"
        "SAFETY_STOPPED" -> "Stopped — safety could not be confirmed"
        else -> status
    }

    fun notifyTaskTransition(context: Context, task: AgentTaskEntity) {
        if (task.status !in NOTIFY_STATUSES) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(task.title)
            .setContentText(statusText(task.status))
            .setStyle(NotificationCompat.BigTextStyle().bigText("${statusText(task.status)} — ${task.currentOperation}"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context)
                .notify((task.id + task.status).hashCode(), notification)
        } catch (ignored: SecurityException) {
            // Permission was revoked between the check and the post; stay silent.
        }
    }
}
