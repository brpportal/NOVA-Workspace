package com.example.nova.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nova.data.ApprovalRecordEntity
import com.example.nova.data.NovaRepository
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmError
import com.example.ui.theme.WarmSuccess
import com.example.ui.theme.WarmWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalsScreen(
    repository: NovaRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val approvals by repository.approvals.collectAsState(initial = emptyList())
    var selectedRecord by remember { mutableStateOf<ApprovalRecordEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Approval History",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("approvals_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(approvals, key = { it.id }) { record ->
                    ApprovalCardItem(
                        record = record,
                        onClick = { selectedRecord = record }
                    )
                }

                if (approvals.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.VerifiedUser,
                                    contentDescription = null,
                                    tint = StoneMuted,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "No approval records yet",
                                    style = MaterialTheme.typography.bodyMedium.copy(color = StoneMuted)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail Modal
    if (selectedRecord != null) {
        val rec = selectedRecord!!
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val createdStr = sdf.format(Date(rec.createdAt))
        val decidedStr = rec.decidedAt?.let { sdf.format(Date(it)) } ?: "Pending decision"

        AlertDialog(
            onDismissRequest = { selectedRecord = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Approval Details", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Action: ${rec.actionName}", fontWeight = FontWeight.Bold)
                    Text("Tool Name: ${rec.toolName}", fontSize = 12.sp, color = StoneMuted)
                    Text("Status: ${rec.status}", fontWeight = FontWeight.SemiBold, color = getStatusColor(rec.status))
                    Text("Requested: $createdStr", fontSize = 12.sp, color = StoneMuted)
                    Text("Decided: $decidedStr", fontSize = 12.sp, color = StoneMuted)
                    if (!rec.taskId.isNullOrBlank()) {
                        Text("Task ID: ${rec.taskId}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Arguments Summary (Secrets Redacted):", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = StoneMuted))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = rec.argumentsSummary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedRecord = null }) { Text("Close") }
            }
        )
    }
}

@Composable
fun ApprovalCardItem(
    record: ApprovalRecordEntity,
    onClick: () -> Unit
) {
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val dateStr = sdf.format(Date(record.createdAt))

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag("approval_card_${record.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.actionName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ApprovalStatusChip(status = record.status)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = record.argumentsSummary.take(100),
                    style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted, fontSize = 11.sp),
                    maxLines = 1
                )
                Text(
                    text = "Requested $dateStr",
                    style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted, fontSize = 10.sp)
                )
            }
            Icon(Icons.Default.Info, contentDescription = "View Details", tint = StoneMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun ApprovalStatusChip(status: String) {
    val (bgColor, textColor) = when (status) {
        "APPROVED" -> Pair(WarmSuccess.copy(alpha = 0.15f), WarmSuccess)
        "DENIED" -> Pair(WarmError.copy(alpha = 0.15f), WarmError)
        "PENDING" -> Pair(WarmWarning.copy(alpha = 0.15f), WarmWarning)
        "TIMED_OUT" -> Pair(Color.Gray.copy(alpha = 0.15f), Color.Gray)
        else -> Pair(StoneMuted.copy(alpha = 0.15f), StoneMuted)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = textColor, fontSize = 10.sp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

fun getStatusColor(status: String): Color {
    return when (status) {
        "APPROVED" -> WarmSuccess
        "DENIED" -> WarmError
        "PENDING" -> WarmWarning
        else -> Color.Gray
    }
}
