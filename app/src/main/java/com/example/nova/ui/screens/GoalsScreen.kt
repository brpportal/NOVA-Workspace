package com.example.nova.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nova.data.AgentTaskEntity
import com.example.nova.data.GoalEntity
import com.example.nova.data.NovaRepository
import com.example.nova.ui.components.NovaBadge
import com.example.ui.theme.StoneLight
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmAccent
import com.example.ui.theme.WarmAccentContainer
import com.example.ui.theme.WarmError
import com.example.ui.theme.WarmSuccess
import com.example.ui.theme.WarmWarning
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(
    repository: NovaRepository,
    modifier: Modifier = Modifier,
    onNavigateToTask: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val goals by repository.goals.collectAsState(initial = emptyList())
    val tasks by repository.tasks.collectAsState(initial = emptyList())

    var selectedStatusFilter by remember { mutableStateOf("ALL") } // ALL, ACTIVE, PAUSED, COMPLETED, RETIRED
    var showCreateGoalDialog by remember { mutableStateOf(false) }
    var parentGoalForSubgoal by remember { mutableStateOf<GoalEntity?>(null) }

    var goalTitleInput by remember { mutableStateOf("") }
    var goalDescInput by remember { mutableStateOf("") }
    var goalStatusInput by remember { mutableStateOf("ACTIVE") }

    // Dialog for creating task linked to goal
    var goalForNewTask by remember { mutableStateOf<GoalEntity?>(null) }
    var taskPromptInput by remember { mutableStateOf("") }
    var taskCadenceInput by remember { mutableStateOf("RUN_ONCE") }

    val filteredGoals = goals.filter { g ->
        if (selectedStatusFilter == "ALL") true else g.status.equals(selectedStatusFilter, ignoreCase = true)
    }

    val rootGoals = filteredGoals.filter { it.parentGoalId.isNullOrBlank() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Goals & Milestones", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
                actions = {
                    Button(
                        onClick = {
                            parentGoalForSubgoal = null
                            goalTitleInput = ""
                            goalDescInput = ""
                            goalStatusInput = "ACTIVE"
                            showCreateGoalDialog = true
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.testTag("create_goal_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Goal", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Filter Pills Row
            val statusFilters = listOf("ALL", "ACTIVE", "PAUSED", "COMPLETED", "RETIRED")
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(statusFilters) { filter ->
                    val isSelected = selectedStatusFilter == filter
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
                        modifier = Modifier.clickable { selectedStatusFilter = filter }.testTag("goal_filter_$filter")
                    ) {
                        Text(
                            text = filter.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            if (rootGoals.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Flag, contentDescription = null, tint = StoneLight, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No Goals Found", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Create a goal to align tasks and track progress.", style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted))
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(rootGoals, key = { it.id }) { goal ->
                        val subgoals = goals.filter { it.parentGoalId == goal.id }
                        val goalTasks = tasks.filter { it.goalId == goal.id }

                        GoalCardItem(
                            goal = goal,
                            subgoals = subgoals,
                            allTasks = tasks,
                            onAddSubgoal = {
                                parentGoalForSubgoal = goal
                                goalTitleInput = ""
                                goalDescInput = ""
                                goalStatusInput = "ACTIVE"
                                showCreateGoalDialog = true
                            },
                            onAddTask = {
                                goalForNewTask = goal
                                taskPromptInput = ""
                                taskCadenceInput = "RUN_ONCE"
                            },
                            onStatusChange = { newStatus ->
                                scope.launch { repository.updateGoal(goal.copy(status = newStatus)) }
                            },
                            onDeleteGoal = {
                                scope.launch { repository.deleteGoal(goal.id) }
                            }
                        )
                    }
                }
            }
        }
    }

    // Dialog: Create Goal / Subgoal
    if (showCreateGoalDialog) {
        AlertDialog(
            onDismissRequest = { showCreateGoalDialog = false },
            title = {
                Text(if (parentGoalForSubgoal != null) "New Subgoal for '${parentGoalForSubgoal?.title}'" else "New Goal")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = goalTitleInput,
                        onValueChange = { goalTitleInput = it },
                        label = { Text("Goal Title") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("goal_title_input")
                    )
                    OutlinedTextField(
                        value = goalDescInput,
                        onValueChange = { goalDescInput = it },
                        label = { Text("Description (optional)") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("goal_desc_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val title = goalTitleInput.trim()
                        if (title.isNotBlank()) {
                            scope.launch {
                                repository.createGoal(
                                    title = title,
                                    description = goalDescInput.trim().ifBlank { null },
                                    parentGoalId = parentGoalForSubgoal?.id,
                                    status = goalStatusInput
                                )
                                showCreateGoalDialog = false
                                Toast.makeText(context, "Goal created", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Save Goal") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateGoalDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Dialog: Create Task Linked to Goal
    if (goalForNewTask != null) {
        val targetGoal = goalForNewTask!!
        AlertDialog(
            onDismissRequest = { goalForNewTask = null },
            title = { Text("Add Task for Goal: '${targetGoal.title}'") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = taskPromptInput,
                        onValueChange = { taskPromptInput = it },
                        label = { Text("Task Prompt / Goal Action") },
                        placeholder = { Text("e.g. Research competitor market and save to /Research/summary.md") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("goal_task_prompt_input")
                    )
                    Text("Repeat", style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted, fontWeight = FontWeight.Bold))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "RUN_ONCE" to "Once",
                            "DAILY" to "Daily",
                            "WEEKLY" to "Weekly",
                            "MONTHLY" to "Monthly"
                        ).forEach { (value, label) ->
                            val isSelected = taskCadenceInput == value
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
                                modifier = Modifier.clickable { taskCadenceInput = value }.testTag("goal_task_cadence_$value")
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val prompt = taskPromptInput.trim()
                        if (prompt.isNotBlank()) {
                            repository.launchTask(
                                prompt = prompt,
                                title = prompt.take(30),
                                goalId = targetGoal.id,
                                cadence = taskCadenceInput
                            )
                            goalForNewTask = null
                            Toast.makeText(context, "Task created for Goal", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Launch Task") }
            },
            dismissButton = {
                TextButton(onClick = { goalForNewTask = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun GoalCardItem(
    goal: GoalEntity,
    subgoals: List<GoalEntity>,
    allTasks: List<AgentTaskEntity>,
    onAddSubgoal: () -> Unit,
    onAddTask: () -> Unit,
    onStatusChange: (String) -> Unit,
    onDeleteGoal: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }
    var statusMenuExpanded by remember { mutableStateOf(false) }

    val directTasks = allTasks.filter { it.goalId == goal.id }
    val subgoalIds = subgoals.map { it.id }.toSet()
    val subgoalTasks = allTasks.filter { it.goalId in subgoalIds }
    val totalGoalTasks = directTasks + subgoalTasks

    val completedCount = totalGoalTasks.count { it.status == "COMPLETED" }
    val totalCount = totalGoalTasks.size

    val (badgeColor, badgeContainer) = when (goal.status) {
        "ACTIVE" -> WarmAccent to WarmAccentContainer
        "PAUSED" -> WarmWarning to WarmWarning.copy(alpha = 0.15f)
        "COMPLETED" -> WarmSuccess to WarmSuccess.copy(alpha = 0.15f)
        else -> StoneMuted to MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Flag, contentDescription = null, tint = WarmAccent, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(goal.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }
                Spacer(modifier = Modifier.width(6.dp))
                NovaBadge(text = goal.status, color = badgeColor, containerColor = badgeContainer)
                IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle Expand"
                    )
                }
            }

            if (!goal.description.isNullOrBlank()) {
                Text(goal.description, style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted))
            }

            // Checkbox-style progress indicator (done / total of tasks)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (totalCount > 0 && completedCount == totalCount) Icons.Default.CheckCircle else Icons.Default.TaskAlt,
                    contentDescription = null,
                    tint = if (totalCount > 0 && completedCount == totalCount) WarmSuccess else WarmAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Progress: [✓] $completedCount / $totalCount tasks completed",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                )
            }

            // Actions Row
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onAddTask,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Task", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = onAddSubgoal,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Subgoal", fontSize = 11.sp)
                }
                Box {
                    OutlinedButton(
                        onClick = { statusMenuExpanded = true },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("goal_status_button")
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Status", fontSize = 11.sp)
                    }
                    DropdownMenu(
                        expanded = statusMenuExpanded,
                        onDismissRequest = { statusMenuExpanded = false }
                    ) {
                        listOf("ACTIVE", "PAUSED", "COMPLETED", "RETIRED").forEach { status ->
                            DropdownMenuItem(
                                text = { Text(status.lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    statusMenuExpanded = false
                                    if (!status.equals(goal.status, ignoreCase = true)) {
                                        onStatusChange(status)
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDeleteGoal, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Goal", tint = WarmError, modifier = Modifier.size(16.dp))
                }
            }

            // Expanded Subgoals & Linked Tasks
            AnimatedVisibility(visible = isExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    // Linked Tasks
                    if (directTasks.isNotEmpty()) {
                        Text("Linked Tasks (${directTasks.size}):", style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted, fontWeight = FontWeight.Bold))
                        for (task in directTasks) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(task.title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                                    NovaBadge(text = task.status, color = if (task.status == "COMPLETED") WarmSuccess else WarmAccent)
                                }
                            }
                        }
                    }

                    // Subgoals (One level of nesting)
                    if (subgoals.isNotEmpty()) {
                        Text("Subgoals (${subgoals.size}):", style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted, fontWeight = FontWeight.Bold))
                        for (subgoal in subgoals) {
                            val subTasks = allTasks.filter { it.goalId == subgoal.id }
                            val subDone = subTasks.count { it.status == "COMPLETED" }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.fillMaxWidth().padding(start = 8.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("↳ ${subgoal.title}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                        NovaBadge(text = subgoal.status, color = WarmAccent)
                                    }
                                    Text("Progress: $subDone/${subTasks.size} tasks done", fontSize = 11.sp, color = StoneMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
