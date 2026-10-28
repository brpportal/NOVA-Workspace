package com.novaworkspace.app.ui.tasks

import androidx.compose.runtime.Composable
import com.novaworkspace.app.ui.EmptyState

/**
 * Milestone 1 Tasks screen: honest empty state only.
 * The agent runtime and persisted task list arrive in Milestones 5 and 7
 * (SPEC Sec. 9 and 16).
 */
@Composable
fun TasksScreen() {
    EmptyState(
        title = "Tasks",
        body = "No tasks yet. Agent tasks will appear here once the agent runtime is implemented in Milestone 5."
    )
}
