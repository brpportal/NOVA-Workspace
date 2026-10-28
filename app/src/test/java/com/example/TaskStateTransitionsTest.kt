package com.example

import com.example.nova.data.AgentTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateTransitionsTest {

    @Test
    fun testTaskLifecycleTransitions() {
        var task = AgentTaskEntity(
            id = "task-100",
            title = "Research market",
            prompt = "Search news and save report",
            status = "PENDING"
        )
        assertEquals("PENDING", task.status)

        // Running
        task = task.copy(status = "RUNNING", currentOperation = "Navigating to website", progressPercent = 25)
        assertEquals("RUNNING", task.status)
        assertEquals(25, task.progressPercent)

        // Waiting Approval
        task = task.copy(
            status = "WAITING_APPROVAL",
            currentOperation = "Awaiting user confirmation",
            pendingApprovalToolName = "file_write"
        )
        assertEquals("WAITING_APPROVAL", task.status)
        assertEquals("file_write", task.pendingApprovalToolName)

        // Resumed and Completed
        task = task.copy(
            status = "COMPLETED",
            currentOperation = "Done",
            progressPercent = 100,
            completedAt = System.currentTimeMillis()
        )
        assertEquals("COMPLETED", task.status)
        assertEquals(100, task.progressPercent)
        assertTrue(task.completedAt != null)
    }

    @Test
    fun testCancellationTransition() {
        val task = AgentTaskEntity(
            id = "task-200",
            title = "Download batch",
            prompt = "Download files",
            status = "RUNNING"
        ).copy(status = "CANCELLED", currentOperation = "Cancelled by user.")

        assertEquals("CANCELLED", task.status)
    }
}
