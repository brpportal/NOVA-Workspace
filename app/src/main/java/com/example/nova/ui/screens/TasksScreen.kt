package com.example.nova.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.example.nova.ai.ProviderErrors
import com.example.nova.data.AgentTaskEntity
import com.example.nova.data.ApprovalRecordEntity
import com.example.nova.data.NovaRepository
import com.example.nova.data.TaskStepEntity
import androidx.compose.ui.text.input.KeyboardType
import com.example.nova.trained.FacebookExtractionMode
import com.example.nova.trained.FacebookPostLinks
import com.example.nova.trained.FacebookProfileStatus
import com.example.nova.trained.ScreenshotLineKind
import com.example.nova.trained.ScreenshotsCollection
import com.example.ui.theme.StoneLight
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmAccent
import com.example.ui.theme.WarmAccentContainer
import com.example.ui.theme.WarmError
import com.example.ui.theme.WarmSuccess
import com.example.ui.theme.WarmWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AuditTimelineEvent(
    val timestamp: Long,
    val title: String,
    val subtitle: String,
    val detail: String? = null,
    val status: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    repository: NovaRepository,
    onNavigateToApprovals: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tasks by repository.tasks.collectAsState(initial = emptyList())
    var selectedFilterIndex by remember { mutableIntStateOf(0) }
    val filterTabs = listOf("All", "Active", "Needs Approval", "Scheduled", "Completed")

    val filteredTasks = remember(tasks, selectedFilterIndex) {
        when (selectedFilterIndex) {
            1 -> tasks.filter { it.status == "RUNNING" || it.status == "PENDING" || it.status == "PAUSED" || it.status == "WAITING" }
            2 -> tasks.filter { it.status == "WAITING_APPROVAL" }
            3 -> tasks.filter { (it.cadence != "RUN_ONCE" || it.nextRunTime != null) && it.status != "CANCELLED" }
                .sortedBy { it.nextRunTime ?: Long.MAX_VALUE }
            4 -> tasks.filter { it.status == "COMPLETED" || it.status == "FAILED" || it.status == "CANCELLED" || it.status == "SAFETY_STOPPED" }
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
                actions = {
                    IconButton(
                        onClick = onNavigateToApprovals,
                        modifier = Modifier.testTag("open_approvals_button")
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = "Approval History",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
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
                if (selectedFilterIndex == 0) {
                    item {
                        TrainedTasksSection(repository = repository)
                    }
                }

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
    val approvals by repository.approvals.collectAsState(initial = emptyList())
    val taskApprovals = remember(approvals, task.id) { approvals.filter { it.taskId == task.id } }
    val runHistory by repository.taskRunDao.getRunsForTask(task.id).collectAsState(initial = emptyList())

    var isExpanded by remember { mutableStateOf(false) }
    var showScreenshots by remember { mutableStateOf(false) }

    // Parse stored browser screenshot evidence paths if present (real
    // captures recorded by the agent loop; absent when none were taken)
    val screenshotList = remember(task.screenshotsJson) {
        if (!task.screenshotsJson.isNullOrBlank()) {
            try {
                val array = org.json.JSONArray(task.screenshotsJson)
                val result = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val p = array.optString(i)
                    if (p.isNotBlank()) result.add(p)
                }
                result
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    // Parse citations if present
    val citationsList = remember(task.citationsJson) {
        if (!task.citationsJson.isNullOrBlank()) {
            try {
                val array = org.json.JSONArray(task.citationsJson)
                val result = mutableListOf<Pair<String, String>>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    result.add(Pair(obj.optString("title", "Page"), obj.optString("url", "")))
                }
                result
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    // Build unified audit trail timeline
    val auditEvents = remember(task, steps, taskApprovals) {
        val list = mutableListOf<AuditTimelineEvent>()

        // 1. Task Creation
        list.add(
            AuditTimelineEvent(
                timestamp = task.createdAt,
                title = "Task Created",
                subtitle = "Goal: ${task.prompt}",
                status = "CREATED"
            )
        )

        // 2. Plan Steps
        for (step in steps) {
            list.add(
                AuditTimelineEvent(
                    timestamp = step.timestamp,
                    title = "Step #${step.stepNumber}: ${step.toolName ?: "Execution"}",
                    subtitle = "Executed ${step.toolName ?: "tool"}",
                    detail = if (!step.argumentsJson.isNullOrBlank()) "Args: ${step.argumentsJson}\nResult: ${step.resultJson?.take(150)}" else step.resultJson?.take(150),
                    status = step.status
                )
            )
        }

        // 3. Approval Events
        for (app in taskApprovals) {
            list.add(
                AuditTimelineEvent(
                    timestamp = app.createdAt,
                    title = "Approval Requested: ${app.actionName}",
                    subtitle = "Status: ${app.status}",
                    detail = app.argumentsSummary,
                    status = app.status
                )
            )
            if (app.decidedAt != null) {
                list.add(
                    AuditTimelineEvent(
                        timestamp = app.decidedAt,
                        title = "Approval Decision: ${app.status}",
                        subtitle = "Action '${app.actionName}' was ${app.status.lowercase()}",
                        status = app.status
                    )
                )
            }
        }

        // 4. Completion or Failure
        if (task.status == "COMPLETED" || task.status == "FAILED" || task.status == "CANCELLED" || task.status == "SAFETY_STOPPED") {
            list.add(
                AuditTimelineEvent(
                    timestamp = task.completedAt ?: task.updatedAt,
                    title = "Task ${task.status}",
                    subtitle = task.currentOperation,
                    detail = task.errorMessage,
                    status = task.status
                )
            )
        }

        list.sortedBy { it.timestamp }
    }

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                        maxLines = 1
                    )
                    if (task.cadence != "RUN_ONCE" || task.nextRunTime != null) {
                        val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                        val nextRunStr = if (task.nextRunTime != null) sdf.format(Date(task.nextRunTime)) else "Pending"
                        Text(
                            text = "Schedule: ${task.cadence} • Next: $nextRunStr",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold),
                            fontSize = 11.sp
                        )
                        Text(
                            text = "Prompt: ${task.prompt}",
                            style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted),
                            fontSize = 11.sp,
                            maxLines = 2
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                TaskStatusBadge(status = task.status)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Current operation / failure summary. A failed task shows ONE
            // short human summary here (the summarizer also cleans up raw
            // provider JSON stored by older builds); the full raw error
            // lives in the audit trail below, and the separate error block
            // further down is skipped when it would repeat this text.
            val failureSummary = if (task.status == "FAILED") {
                ProviderErrors.summarizeErrorText(task.errorMessage ?: task.currentOperation)
            } else {
                null
            }
            if (failureSummary != null) {
                Surface(
                    color = WarmError.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.5.dp, WarmError.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = failureSummary,
                        style = MaterialTheme.typography.bodySmall.copy(color = WarmError, fontWeight = FontWeight.Medium),
                        maxLines = 3,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            } else {
                Text(
                    text = task.currentOperation,
                    style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted),
                    maxLines = 2
                )
            }

            // Sources / Citations Card if browser tasks visited pages
            if (citationsList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Sources Visited (${citationsList.size})",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        for (citation in citationsList.take(3)) {
                            Text(
                                text = "• ${citation.first} — ${citation.second}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

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
                    border = BorderStroke(1.dp, WarmWarning.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = WarmWarning, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Needs you: Action Requires Approval",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = WarmWarning)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = task.pendingApprovalArgumentsJson ?: "Tool: ${task.pendingApprovalToolName}",
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

            // Error message for non-FAILED terminal states. For FAILED tasks
            // the summary above already shows it — never print it twice.
            if (task.status != "FAILED" && !task.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Error: ${ProviderErrors.summarizeErrorText(task.errorMessage!!)}",
                    style = MaterialTheme.typography.bodySmall.copy(color = WarmError),
                    maxLines = 3
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer controls: Expand timeline toggle & Actions
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
                            text = "Audit Trail & Runs (${auditEvents.size} events, ${runHistory.size} runs)",
                            style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted, fontWeight = FontWeight.Medium)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Expand Audit Trail",
                            tint = StoneMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                if (task.status == "RUNNING" || task.status == "PENDING" || task.status == "WAITING") {
                    IconButton(
                        onClick = { repository.cancelTask(task.id) },
                        modifier = Modifier.size(32.dp).testTag("cancel_task_button_${task.id}")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Cancel", tint = WarmError)
                    }
                } else if (task.status == "FAILED" || task.status == "CANCELLED" || task.status == "SAFETY_STOPPED") {
                    IconButton(
                        onClick = { repository.retryTask(task.id) },
                        modifier = Modifier.size(32.dp).testTag("retry_task_button_${task.id}")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Unified Chronological Audit Trail Timeline & Run History when expanded
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (runHistory.isNotEmpty()) {
                        Text(
                            text = "Scheduled Run History",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        )
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        for (run in runHistory) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Run at ${sdf.format(Date(run.runTime))}", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    Text(run.status, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (run.status == "SUCCESS" || run.status == "COMPLETED") WarmSuccess else WarmError)
                                }
                            }
                        }
                    }

                    if (screenshotList.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { showScreenshots = !showScreenshots }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Browser screenshots (${screenshotList.size})",
                                    style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted, fontWeight = FontWeight.Medium)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (showScreenshots) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Toggle screenshots",
                                    tint = StoneMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        AnimatedVisibility(visible = showScreenshots) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                screenshotList.forEachIndexed { index, path ->
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "Browser screenshot ${index + 1} of ${screenshotList.size}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = StoneMuted)
                                        )
                                        AsyncImage(
                                            model = java.io.File(repository.workspaceManager.rootDir, path),
                                            contentDescription = "Browser screenshot ${index + 1} of ${screenshotList.size}",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(220.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = "Step Audit Trail",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    )
                    for (event in auditEvents) {
                        AuditTimelineRow(event = event)
                    }
                }
            }
        }
    }
}

@Composable
fun AuditTimelineRow(event: AuditTimelineEvent) {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeStr = sdf.format(Date(event.timestamp))

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "[$timeStr] ${event.title}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = event.status,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = when (event.status) {
                            "SUCCESS", "COMPLETED", "APPROVED" -> WarmSuccess
                            "FAILED", "DENIED", "TIMED_OUT" -> WarmError
                            "WAITING_APPROVAL" -> WarmWarning
                            else -> StoneMuted
                        },
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            if (event.subtitle.isNotBlank()) {
                Text(
                    text = event.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                )
            }
            if (!event.detail.isNullOrBlank()) {
                Text(
                    text = event.detail.take(250),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = StoneMuted, fontSize = 10.sp),
                    maxLines = 3
                )
            }
        }
    }
}

/**
 * TRAINED TASKS — pre-programmed, deterministic automations that run
 * with zero AI calls. Trained Task #1: the Facebook Post Links
 * Collector (Mode 1 form + live result view).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainedTasksSection(repository: NovaRepository) {
    // Spark's two CORE FEATURES, presented together: Links Collection
    // (Task #1) finds the posts; Screenshots Collection (Task #2)
    // captures the evidence. Chat/browser/goals remain the supporting
    // engine — nothing else in the app changes.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "🎯 Core Features",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "Links Collection & Screenshots Collection — Spark ka asal kaam. Pehla collector Facebook profiles ke latest post links nikaalta hai; dusra unhi profiles aur posts ke screenshots leta hai. Dono trained tasks hain aur bina AI ke chalte hain.",
                style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted)
            )
        }
        FacebookLinksCollectorCard(repository)
        ScreenshotsCollectorCard(repository)
    }
}

@Composable
private fun FacebookLinksCollectorCard(repository: NovaRepository) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val runState by repository.lastFacebookCollectRun.collectAsState()
    val shotsRunState by repository.lastScreenshotsRun.collectAsState()

    var profilesText by remember { mutableStateOf("") }
    // Round A: the per-profile limit is typed/chip-picked as text and
    // clamped to 1–100 (default 5); the clamp is shown, never silent.
    var countText by remember { mutableStateOf(FacebookPostLinks.DEFAULT_POSTS_PER_PROFILE.toString()) }
    var mode by remember { mutableStateOf(FacebookExtractionMode.HYBRID) }
    var saveList by remember { mutableStateOf(true) }
    var prefilled by remember { mutableStateOf(false) }
    var showInvalidConfirm by remember { mutableStateOf(false) }

    // Prefill from the saved list once (never overwrites typed text).
    LaunchedEffect(Unit) {
        if (!prefilled) {
            prefilled = true
            val saved = withContext(Dispatchers.IO) { repository.loadSavedFacebookProfiles() }
            if (saved.isNotBlank() && profilesText.isBlank()) {
                profilesText = saved
            }
        }
    }

    // Input hygiene, computed live from the raw text: valid unique
    // sources (labelled Link 1..N), invalid lines WITH line numbers,
    // and the duplicate count. Nothing is silently dropped.
    val inputParse = remember(profilesText) { FacebookPostLinks.parseCollectorInput(profilesText) }
    val parsedCount = countText.trim().toIntOrNull()
    val postsPerProfile = parsedCount?.let { FacebookPostLinks.clampPostsPerProfile(it) }
        ?: FacebookPostLinks.DEFAULT_POSTS_PER_PROFILE
    val countClamped = parsedCount != null && parsedCount != postsPerProfile
    // Both core collectors drive the one shared browser: while either
    // runs, the other's inputs stay disabled.
    val running = runState?.running == true || shotsRunState?.running == true

    fun startRun() {
        repository.launchFacebookPostLinksCollectorFromText(profilesText, postsPerProfile, saveList, mode)
        android.widget.Toast.makeText(context, "Collector started — watch the task below", android.widget.Toast.LENGTH_SHORT).show()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, StoneLight)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "🔗 Links Collection — Facebook Post Links",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "Core feature #1 — trained task, runs WITHOUT AI. It visits each profile in order and copies the links of its latest posts. If Facebook asks for a login, the run pauses as Needs you: log in in the Browser tab and hand control back.",
                style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted)
            )

            OutlinedTextField(
                value = profilesText,
                onValueChange = { profilesText = it },
                label = { Text("Profile URLs — one per line") },
                placeholder = { Text("https://www.facebook.com/some.page") },
                minLines = 4,
                maxLines = 8,
                enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag("fb_collector_profiles_field")
            )

            // ---- Per-source limit: presets + custom (Round A, 1–100) ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Posts per profile",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = postsPerProfile.toString(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.testTag("fb_collector_count")
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in listOf(10, 25, 50, 100)) {
                    FilterChip(
                        selected = parsedCount == preset,
                        onClick = { countText = preset.toString() },
                        enabled = !running,
                        label = { Text(preset.toString()) },
                        modifier = Modifier.testTag("fb_collector_preset_$preset")
                    )
                }
            }
            OutlinedTextField(
                value = countText,
                onValueChange = { countText = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text("Custom count (1–100)") },
                singleLine = true,
                enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("fb_collector_custom_count")
            )
            if (countClamped) {
                Text(
                    text = "Count will be clamped to $postsPerProfile (allowed range is 1–100).",
                    style = MaterialTheme.typography.bodySmall.copy(color = WarmWarning),
                    modifier = Modifier.testTag("fb_collector_clamp_note")
                )
            }

            // ---- Extraction method (Round A) ----
            Text(
                text = "Extraction method",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == FacebookExtractionMode.HYBRID,
                    onClick = { mode = FacebookExtractionMode.HYBRID },
                    enabled = !running,
                    label = { Text("Hybrid") },
                    modifier = Modifier.testTag("fb_collector_method_hybrid")
                )
                FilterChip(
                    selected = mode == FacebookExtractionMode.M1_ONLY,
                    onClick = { mode = FacebookExtractionMode.M1_ONLY },
                    enabled = !running,
                    label = { Text("M1 only") },
                    modifier = Modifier.testTag("fb_collector_method_m1")
                )
                FilterChip(
                    selected = mode == FacebookExtractionMode.M3_ONLY,
                    onClick = { mode = FacebookExtractionMode.M3_ONLY },
                    enabled = !running,
                    label = { Text("M3 only") },
                    modifier = Modifier.testTag("fb_collector_method_m3")
                )
            }
            Text(
                text = when (mode) {
                    FacebookExtractionMode.HYBRID ->
                        "Hybrid (recommended): visible links first, then structured-data mining and fallbacks (M1→M5)."
                    FacebookExtractionMode.M1_ONLY ->
                        "M1 only: links from the rendered page only (profile, Posts section, mbasic) — no source mining, no desktop variant."
                    FacebookExtractionMode.M3_ONLY ->
                        "M3 only: mines the page's embedded structured data (permalink_url and post IDs) while scrolling."
                },
                style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted)
            )

            // ---- Input hygiene: duplicates, invalid lines, labels ----
            if (inputParse.duplicatesRemoved > 0) {
                Text(
                    text = "${inputParse.duplicatesRemoved} duplicate line${if (inputParse.duplicatesRemoved == 1) "" else "s"} removed",
                    style = MaterialTheme.typography.bodySmall.copy(color = WarmWarning),
                    modifier = Modifier.testTag("fb_collector_duplicates_note")
                )
            }
            if (inputParse.invalidLines.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Invalid lines (not valid URLs — they will not run):",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, color = WarmError)
                    )
                    for (invalid in inputParse.invalidLines) {
                        Text(
                            text = "Line ${invalid.lineNumber}: ${invalid.raw}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace, color = WarmError, fontSize = 11.sp
                            )
                        )
                    }
                }
            }
            if (inputParse.sources.isNotEmpty()) {
                Text(
                    text = inputParse.labels.zip(inputParse.sources).joinToString("\n") { (label, src) -> "$label — $src" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = StoneMuted),
                    modifier = Modifier.testTag("fb_collector_labels")
                )
                // Pre-run confirmation: sources × limit (a TARGET, not a
                // guarantee), method, and the output formats.
                Text(
                    text = "Ready: ${inputParse.sources.size} source${if (inputParse.sources.size == 1) "" else "s"} × " +
                        "$postsPerProfile links each = up to ${inputParse.sources.size * postsPerProfile} total " +
                        "(target, not a guarantee) · Method: ${mode.displayName()} · Output: MD/TXT/CSV/JSON",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.testTag("fb_collector_confirm_line")
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = saveList,
                    onCheckedChange = { saveList = it },
                    enabled = !running,
                    modifier = Modifier.testTag("fb_collector_save_toggle")
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Save this list for next time",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = {
                    if (inputParse.invalidLines.isNotEmpty()) {
                        showInvalidConfirm = true
                    } else {
                        startRun()
                    }
                },
                enabled = inputParse.sources.isNotEmpty() && !running,
                modifier = Modifier.fillMaxWidth().testTag("fb_collector_run_button")
            ) {
                Text(if (runState?.running == true) "Collecting…" else "Run Collector")
            }

            if (showInvalidConfirm) {
                AlertDialog(
                    onDismissRequest = { showInvalidConfirm = false },
                    title = { Text("Invalid lines in the list") },
                    text = {
                        Text(
                            "These lines are not valid URLs and will be SKIPPED (not run):\n\n" +
                                inputParse.invalidLines.joinToString("\n") { "Line ${it.lineNumber}: ${it.raw}" } +
                                "\n\nRun the ${inputParse.sources.size} valid source(s) anyway?"
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            showInvalidConfirm = false
                            startRun()
                        }) { Text("Run anyway") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showInvalidConfirm = false }) { Text("Cancel") }
                    }
                )
            }

            val state = runState
            if (state != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = state.summaryLine
                        ?: "Collecting… ${state.results.size} of ${state.totalProfiles} profiles done",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.testTag("fb_collector_summary")
                )
                state.resultFilePath?.let { path ->
                    Text(
                        text = "Saved: $path",
                        style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted, fontFamily = FontFamily.Monospace)
                    )
                }
                val extraArtifacts = state.artifactPaths.filter { it != state.resultFilePath }
                if (extraArtifacts.isNotEmpty()) {
                    Text(
                        text = "Also saved: ${extraArtifacts.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        modifier = Modifier.testTag("fb_collector_artifacts")
                    )
                }
                if (!state.running && state.results.isNotEmpty()) {
                    Text(
                        text = "Method: ${state.mode.displayName()}",
                        style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted)
                    )
                }

                state.results.forEach { result ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FacebookStatusChip(result.status)
                            Text(
                                text = "${result.links.size} link${if (result.links.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Text(
                            text = result.normalizedUrl.ifBlank { result.profile },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = StoneMuted)
                        )
                        result.note?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted)
                            )
                        }
                        result.links.forEachIndexed { index, link ->
                            Text(
                                text = "${index + 1}. $link",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            )
                        }
                    }
                }

                val allLinks = state.results.flatMap { it.links }
                if (allLinks.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(allLinks.joinToString("\n")))
                            android.widget.Toast.makeText(context, "${allLinks.size} links copied", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().testTag("fb_collector_copy_button")
                    ) {
                        Text("Copy all links (${allLinks.size})")
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenshotsCollectorCard(repository: NovaRepository) {
    val context = LocalContext.current
    val runState by repository.lastScreenshotsRun.collectAsState()
    val linksRunState by repository.lastFacebookCollectRun.collectAsState()

    var sourcesText by remember { mutableStateOf("") }
    // Post screenshots per profile: presets 1/2/3/5 + custom, clamped
    // to 1–10 (DEFAULT 2 — Al's workflow: 1 profile + 2 post shots).
    var countText by remember {
        mutableStateOf(ScreenshotsCollection.DEFAULT_POSTS_PER_PROFILE.toString())
    }
    var mode by remember { mutableStateOf(FacebookExtractionMode.HYBRID) }
    var saveList by remember { mutableStateOf(true) }
    var prefilled by remember { mutableStateOf(false) }
    var showInvalidConfirm by remember { mutableStateOf(false) }

    // Prefill from the saved list once (never overwrites typed text).
    LaunchedEffect(Unit) {
        if (!prefilled) {
            prefilled = true
            val saved = withContext(Dispatchers.IO) { repository.loadSavedScreenshotProfiles() }
            if (saved.isNotBlank() && sourcesText.isBlank()) {
                sourcesText = saved
            }
        }
    }

    // Same input hygiene as the links card: valid unique sources
    // (labelled Link 1..N), invalid lines WITH line numbers, and the
    // duplicate count. Nothing is silently dropped.
    val inputParse = remember(sourcesText) { FacebookPostLinks.parseCollectorInput(sourcesText) }
    val parsedCount = countText.trim().toIntOrNull()
    val postsPerProfile = parsedCount?.let { ScreenshotsCollection.clampPostsPerProfile(it) }
        ?: ScreenshotsCollection.DEFAULT_POSTS_PER_PROFILE
    val countClamped = parsedCount != null && parsedCount != postsPerProfile
    // Both core collectors drive the one shared browser: while either
    // runs, the other's inputs stay disabled.
    val running = runState?.running == true || linksRunState?.running == true

    // Per-kind counts for the pre-run confirmation line.
    val profileSourceCount = inputParse.sources.count {
        ScreenshotsCollection.classifyLine(it) == ScreenshotLineKind.FACEBOOK_PROFILE
    }
    val directSourceCount = inputParse.sources.size - profileSourceCount
    val maxShots = profileSourceCount * (1 + postsPerProfile) + directSourceCount

    fun startRun() {
        repository.launchScreenshotsCollectorFromText(sourcesText, postsPerProfile, saveList, mode)
        android.widget.Toast.makeText(
            context, "Screenshots collection started — watch the task below",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, StoneLight)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "📸 Screenshots Collection",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = "Core feature #2 — trained task, runs WITHOUT AI. Har Facebook profile ka pehle profile page ka screenshot, phir us ke latest posts (Links Collection wali discovery se) khol kar har post ka screenshot. Koi bhi doosra URL do — us page ka ek direct screenshot milega. Login wall aaye to run Needs you par rukta hai: Browser tab me login karke control wapas do.",
                style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted)
            )

            OutlinedTextField(
                value = sourcesText,
                onValueChange = { sourcesText = it },
                label = { Text("URLs — one per line (Facebook profiles ya koi bhi page)") },
                placeholder = { Text("https://www.facebook.com/some.page") },
                minLines = 4,
                maxLines = 8,
                enabled = !running,
                modifier = Modifier.fillMaxWidth().testTag("shots_profiles_field")
            )

            // ---- Post screenshots per profile: presets + custom (1–10) ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Post screenshots per profile",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = postsPerProfile.toString(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.testTag("shots_count")
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in listOf(1, 2, 3, 5)) {
                    FilterChip(
                        selected = parsedCount == preset,
                        onClick = { countText = preset.toString() },
                        enabled = !running,
                        label = { Text(preset.toString()) },
                        modifier = Modifier.testTag("shots_preset_$preset")
                    )
                }
            }
            OutlinedTextField(
                value = countText,
                onValueChange = { countText = it.filter { c -> c.isDigit() }.take(2) },
                label = { Text("Custom count (1–10)") },
                singleLine = true,
                enabled = !running,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("shots_custom_count")
            )
            if (countClamped) {
                Text(
                    text = "Count will be clamped to $postsPerProfile (allowed range is 1–10).",
                    style = MaterialTheme.typography.bodySmall.copy(color = WarmWarning),
                    modifier = Modifier.testTag("shots_clamp_note")
                )
            }
            Text(
                text = "Profile page ka screenshot hamesha is ke ilawa hota hai — default 2 matlab kul 3 screenshots per profile (1 profile + 2 posts).",
                style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted)
            )

            // ---- Post discovery method (same ladder as Links Collection) ----
            Text(
                text = "Post discovery method",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == FacebookExtractionMode.HYBRID,
                    onClick = { mode = FacebookExtractionMode.HYBRID },
                    enabled = !running,
                    label = { Text("Hybrid") },
                    modifier = Modifier.testTag("shots_method_hybrid")
                )
                FilterChip(
                    selected = mode == FacebookExtractionMode.M1_ONLY,
                    onClick = { mode = FacebookExtractionMode.M1_ONLY },
                    enabled = !running,
                    label = { Text("M1 only") },
                    modifier = Modifier.testTag("shots_method_m1")
                )
                FilterChip(
                    selected = mode == FacebookExtractionMode.M3_ONLY,
                    onClick = { mode = FacebookExtractionMode.M3_ONLY },
                    enabled = !running,
                    label = { Text("M3 only") },
                    modifier = Modifier.testTag("shots_method_m3")
                )
            }
            Text(
                text = when (mode) {
                    FacebookExtractionMode.HYBRID ->
                        "Hybrid (recommended): posts dhoondhne ke liye poora M1→M5 ladder — pehle visible links, phir structured-data mining aur fallbacks."
                    FacebookExtractionMode.M1_ONLY ->
                        "M1 only: posts sirf rendered page ke visible links se — koi source mining nahi."
                    FacebookExtractionMode.M3_ONLY ->
                        "M3 only: posts page ke embedded structured data (permalink_url / post IDs) ki mining se."
                },
                style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted)
            )

            // ---- Input hygiene: duplicates, invalid lines, labels ----
            if (inputParse.duplicatesRemoved > 0) {
                Text(
                    text = "${inputParse.duplicatesRemoved} duplicate line${if (inputParse.duplicatesRemoved == 1) "" else "s"} removed",
                    style = MaterialTheme.typography.bodySmall.copy(color = WarmWarning),
                    modifier = Modifier.testTag("shots_duplicates_note")
                )
            }
            if (inputParse.invalidLines.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Invalid lines (not valid URLs — they will not run):",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, color = WarmError)
                    )
                    for (invalid in inputParse.invalidLines) {
                        Text(
                            text = "Line ${invalid.lineNumber}: ${invalid.raw}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace, color = WarmError, fontSize = 11.sp
                            )
                        )
                    }
                }
            }
            if (inputParse.sources.isNotEmpty()) {
                Text(
                    text = inputParse.labels.zip(inputParse.sources).joinToString("\n") { (label, src) -> "$label — $src" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = StoneMuted),
                    modifier = Modifier.testTag("shots_labels")
                )
                // Pre-run confirmation: per-kind shot math (a TARGET,
                // not a guarantee) + discovery method + output format.
                Text(
                    text = "Ready: $profileSourceCount Facebook profile${if (profileSourceCount == 1) "" else "s"} × " +
                        "(1 profile + up to $postsPerProfile post shots)" +
                        (if (directSourceCount > 0) " + $directSourceCount direct page${if (directSourceCount == 1) "" else "s"} × 1 shot" else "") +
                        " = up to $maxShots screenshots (target, not a guarantee) · Discovery: ${mode.displayName()} · Output: PNG (6 MB se bari PNG ke saath JPEG copy bhi)",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.testTag("shots_confirm_line")
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = saveList,
                    onCheckedChange = { saveList = it },
                    enabled = !running,
                    modifier = Modifier.testTag("shots_save_toggle")
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Save this list for next time",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = {
                    if (inputParse.invalidLines.isNotEmpty()) {
                        showInvalidConfirm = true
                    } else {
                        startRun()
                    }
                },
                enabled = inputParse.sources.isNotEmpty() && !running,
                modifier = Modifier.fillMaxWidth().testTag("shots_run_button")
            ) {
                Text(if (runState?.running == true) "Capturing…" else "Run Screenshots Collection")
            }

            if (showInvalidConfirm) {
                AlertDialog(
                    onDismissRequest = { showInvalidConfirm = false },
                    title = { Text("Invalid lines in the list") },
                    text = {
                        Text(
                            "These lines are not valid URLs and will be SKIPPED (not run):\n\n" +
                                inputParse.invalidLines.joinToString("\n") { "Line ${it.lineNumber}: ${it.raw}" } +
                                "\n\nRun the ${inputParse.sources.size} valid source(s) anyway?"
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            showInvalidConfirm = false
                            startRun()
                        }) { Text("Run anyway") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showInvalidConfirm = false }) { Text("Cancel") }
                    }
                )
            }

            val state = runState
            if (state != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = state.summaryLine
                        ?: "Capturing… ${state.results.size} of ${state.totalSources} sources done",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.testTag("shots_summary")
                )
                Text(
                    text = "Run folder: ${state.runFolderPath} — Files → Created by Spark me kholo",
                    style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.testTag("shots_run_folder")
                )
                state.indexFilePath?.let { path ->
                    Text(
                        text = "Index: $path",
                        style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted, fontFamily = FontFamily.Monospace)
                    )
                }
                if (!state.running && state.results.isNotEmpty()) {
                    Text(
                        text = "Discovery: ${state.mode.displayName()}",
                        style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted)
                    )
                }

                state.results.forEach { result ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FacebookStatusChip(result.status)
                            Text(
                                text = "${result.shots.size} of ${result.expectedShots} shot${if (result.expectedShots == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                        Text(
                            text = result.normalizedUrl.ifBlank { result.source },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = StoneMuted)
                        )
                        result.note?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted)
                            )
                        }
                        result.shots.forEach { shot ->
                            Text(
                                text = "• ${shot.fileName}" +
                                    (shot.postUrl?.let { " — post: $it" } ?: "") +
                                    (shot.jpegRelativePath?.let { " (+ JPEG copy)" } ?: ""),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FacebookStatusChip(status: FacebookProfileStatus) {
    val (bgColor, textColor) = when (status) {
        FacebookProfileStatus.OK -> WarmSuccess.copy(alpha = 0.15f) to WarmSuccess
        FacebookProfileStatus.PARTIAL -> WarmWarning.copy(alpha = 0.15f) to WarmWarning
        FacebookProfileStatus.NO_POSTS -> StoneLight.copy(alpha = 0.25f) to StoneMuted
        FacebookProfileStatus.LOGIN_REQUIRED -> WarmWarning.copy(alpha = 0.15f) to WarmWarning
        else -> WarmError.copy(alpha = 0.15f) to WarmError
    }
    Surface(color = bgColor, shape = RoundedCornerShape(12.dp)) {
        Text(
            text = status.name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = textColor),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun TaskStatusBadge(status: String) {
    val (bgColor, textColor, label) = when (status) {
        "RUNNING" -> Triple(WarmAccentContainer, WarmAccent, "RUNNING")
        "WAITING_APPROVAL" -> Triple(WarmWarning.copy(alpha = 0.15f), WarmWarning, "NEEDS YOU")
        "WAITING" -> Triple(WarmWarning.copy(alpha = 0.15f), WarmWarning, "NEEDS YOU")
        "COMPLETED" -> Triple(WarmSuccess.copy(alpha = 0.15f), WarmSuccess, "COMPLETED")
        "FAILED" -> Triple(WarmError.copy(alpha = 0.15f), WarmError, "FAILED")
        "SAFETY_STOPPED" -> Triple(WarmError.copy(alpha = 0.15f), WarmError, "Stopped — safety could not be confirmed")
        else -> Triple(StoneLight.copy(alpha = 0.2f), StoneMuted, status)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = textColor),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
