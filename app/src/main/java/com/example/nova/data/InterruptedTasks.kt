package com.example.nova.data

/**
 * Round B — interrupted-task recovery. When the app process dies
 * mid-task, the task row stays in an in-flight status forever: its
 * agent loop / collector job lived in the dead process and will never
 * update it again. On the next start, such tasks are marked FAILED
 * with [INTERRUPTED_STEP] recorded — honestly interrupted, NEVER
 * completed. Partial collector artifacts written before the death stay
 * exactly as they are.
 *
 * Selection is deliberately narrow (pure + testable):
 * - RUNNING / WAITING / WAITING_APPROVAL / PAUSED exist only while a
 *   live process drives the task; after a restart nothing owns them.
 * - PENDING is NOT touched: a pending task may simply be queued or
 *   scheduled (recurring tasks sit in stable states between runs and
 *   keep their cadence/nextRunTime untouched either way).
 */
object InterruptedTasks {

    const val INTERRUPTED_STEP =
        "Interrupted — the app was closed or restarted before this task finished. " +
            "Partial results, if any, were saved."

    const val INTERRUPTED_OPERATION =
        "Interrupted — the app was closed or restarted before this task finished."

    val IN_FLIGHT_STATUSES = setOf("RUNNING", "WAITING", "WAITING_APPROVAL", "PAUSED")

    fun isOrphaned(task: AgentTaskEntity): Boolean = task.status in IN_FLIGHT_STATUSES

    fun selectOrphaned(tasks: List<AgentTaskEntity>): List<AgentTaskEntity> =
        tasks.filter(::isOrphaned)
}
