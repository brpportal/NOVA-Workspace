package com.example.nova.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nova.data.AgentTaskEntity
import com.example.nova.data.NovaRepository
import com.example.nova.data.TaskStepEntity
import com.example.ui.theme.StoneLight
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmAccent
import com.example.ui.theme.WarmAccentContainer
import com.example.ui.theme.WarmBorder
import com.example.ui.theme.WarmCanvas
import com.example.ui.theme.WarmError
import com.example.ui.theme.WarmOffWhite
import com.example.ui.theme.WarmSuccess
import com.example.ui.theme.WarmSurface
import com.example.ui.theme.WarmWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    repository: NovaRepository,
    modifier: Modifier = Modifier
) {
    val tasks by repository.tasks.collectAsState(initial = emptyList())
    var selectedFilterIndex by remember { mutableIntStateOf(0) }
    val filterTabs = listOf("All", "Active", "Waiting Approval", "Completed")

    val filteredTasks = remember(tasks, selectedFilterIndex) {
        when (selectedFilterIndex) {
            1 -> tasks.filter { it.status == "RUNNING" || it.status == "PENDING" || it.status == "PAUSED" }
            2 -> tasks.filter { it.status == "WAITING_APPROVAL" }
            3 -> tasks.filter { it.status == "COMPLETED" || it.status == "FAILED" || it.status == "CANCELLED" }
            else -> tasks
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Agent Tasks",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    )
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
            // Filter Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedFilterIndex,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                edgePadding = 16.dp,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedFilterIndex]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                filterTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedFilterIndex == index,
                        onClick = { selectedFilterIndex = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (selectedFilterIndex == index) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selectedFilterIndex == index) MaterialTheme.colorScheme.primary else StoneMuted
                                )
                            )
                        },
                        modifier = Modifier.testTag("tab_filter_$index")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tasks List
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredTasks, key = { it.id }) { task ->
                    TaskCardItem(
                        task = task,
                        repository = repository
                    )
                }

                if (filteredTasks.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No tasks found in this section.",
                                style = MaterialTheme.typography.bodyMedium.copy(color = StoneMuted)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TaskCardItem(
    task: AgentTaskEntity,
    repository: NovaRepository
) {
    val steps by repository.taskDao.getStepsForTask(task.id).collectAsState(initial = emptyList())
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (task.status == "WAITING_APPROVAL") WarmWarning else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(16.dp)
            )
            .testTag("task_card_${task.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Title & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                StatusBadge(status = task.status)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Current operation text
            Text(
                text = task.currentOperation,
                style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted),
                maxLines = 2
            )

            // Measurable Progress Bar (when progressPercent >= 0)
            if (task.progressPercent >= 0 && (task.status == "RUNNING" || task.status == "PENDING")) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { task.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // Approval Card if WAITING_APPROVAL
            if (task.status == "WAITING_APPROVAL") {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = WarmWarning.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, WarmWarning.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = WarmWarning, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Action Requires Approval",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = WarmWarning)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tool: ${task.pendingApprovalToolName}\nArguments: ${task.pendingApprovalArgumentsJson}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { repository.approveTask(task.id, "ALLOW_ONCE") },
                                colors = ButtonDefaults.buttonColors(containerColor = WarmSuccess),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f).height(34.dp).testTag("approve_once_button")
                            ) {
                                Text("Approve", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { repository.approveTask(task.id, "ALWAYS_ALLOW") },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f).height(34.dp).testTag("always_allow_button")
                            ) {
                                Text("Always Allow", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { repository.rejectTask(task.id) },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.height(34.dp).testTag("reject_button")
                            ) {
                                Text("Reject", fontSize = 11.sp, color = WarmError)
                            }
                        }
                    }
                }
            }

            // Error message if failed
            if (!task.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Error: ${task.errorMessage}",
                    style = MaterialTheme.typography.bodySmall.copy(color = WarmError)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer controls: Expand steps toggle & Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { isExpanded = !isExpanded }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${steps.size} step(s)",
                            style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted, fontWeight = FontWeight.Medium)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Expand Steps",
                            tint = StoneMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                if (task.status == "RUNNING" || task.status == "PENDING") {
                    IconButton(
                        onClick = { repository.cancelTask(task.id) },
                        modifier = Modifier.size(32.dp).testTag("cancel_task_button_${task.id}")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Cancel", tint = WarmError)
                    }
                } else if (task.status == "FAILED" || task.status == "CANCELLED") {
                    IconButton(
                        onClick = { repository.retryTask(task.id) },
                        modifier = Modifier.size(32.dp).testTag("retry_task_button_${task.id}")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Steps Timeline when expanded
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (step in steps) {
                        StepTimelineRow(step = step)
                    }
                }
            }
        }
    }
}

@Composable
fun StepTimelineRow(step: TaskStepEntity) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${step.stepNumber} ${step.toolName ?: "Step"}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = step.status,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = when (step.status) {
                            "SUCCESS" -> WarmSuccess
                            "FAILED" -> WarmError
                            else -> StoneMuted
                        },
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            if (!step.argumentsJson.isNullOrBlank()) {
                Text(
                    text = "Args: ${step.argumentsJson}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                )
            }
            if (!step.resultJson.isNullOrBlank()) {
                Text(
                    text = "Result: ${step.resultJson.take(200)}",
                    style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted, fontSize = 11.sp),
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
fun StatusBadge(status: String) {
    val (bgColor, textColor) = when (status) {
        "RUNNING" -> Pair(WarmAccentContainer, WarmAccent)
        "WAITING_APPROVAL" -> Pair(WarmWarning.copy(alpha = 0.15f), WarmWarning)
        "COMPLETED" -> Pair(WarmSuccess.copy(alpha = 0.15f), WarmSuccess)
        "FAILED" -> Pair(WarmError.copy(alpha = 0.15f), WarmError)
        else -> Pair(StoneLight.copy(alpha = 0.2f), StoneMuted)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = status.replace('_', ' '),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = textColor),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
