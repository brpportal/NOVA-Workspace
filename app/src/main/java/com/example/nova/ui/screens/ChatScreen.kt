package com.example.nova.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nova.data.ChatMessageEntity
import com.example.nova.data.ConversationEntity
import com.example.nova.data.NovaRepository
import com.example.nova.ui.components.MarkdownText
import com.example.nova.ui.components.NovaScreen
import com.example.ui.theme.StoneLight
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmAccent
import com.example.ui.theme.WarmAccentContainer
import com.example.ui.theme.WarmAccentDark
import com.example.ui.theme.WarmBorder
import com.example.ui.theme.WarmError
import com.example.ui.theme.WarmSuccess
import com.example.ui.theme.WarmWarning
import kotlinx.coroutines.launch
import java.util.UUID

import com.example.nova.agent.PipelinePresence
import com.example.nova.ai.AIMessage
import com.example.nova.data.MemoryEntryEntity
import androidx.compose.material.icons.filled.Psychology
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    repository: NovaRepository,
    onNavigateToScreen: (NovaScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversations by repository.conversations.collectAsState(initial = emptyList())
    val activeTasks by repository.tasks.collectAsState(initial = emptyList())
    val presence by repository.pipelinePresence.collectAsState()

    var activeConversationId by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    var showConversationsSheet by remember { mutableStateOf(false) }
    var showAttachFileDialog by remember { mutableStateOf(false) }
    var attachedFilePaths by remember { mutableStateOf(listOf<String>()) }
    var showRenameDialog by remember { mutableStateOf<ConversationEntity?>(null) }
    var renameTitleInput by remember { mutableStateOf("") }

    // Summarize Into Memory state
    var showSummarizeDialog by remember { mutableStateOf(false) }
    var isSummarizingMemory by remember { mutableStateOf(false) }
    var proposedMemorySummary by remember { mutableStateOf("") }

    // Select first conversation if none selected
    LaunchedEffect(conversations) {
        if (activeConversationId == null && conversations.isNotEmpty()) {
            activeConversationId = conversations.first().id
        }
    }

    val currentConversation = conversations.find { it.id == activeConversationId }
    val messages by remember(activeConversationId) {
        if (activeConversationId != null) {
            repository.chatDao.getMessagesForConversation(activeConversationId!!)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.collectAsState(initial = emptyList())

    val runningTask = activeTasks.find { it.conversationId == activeConversationId && (it.status == "RUNNING" || it.status == "PENDING" || it.status == "WAITING_APPROVAL") }
    val listState = rememberLazyListState()

    // Auto-scroll on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = currentConversation?.title ?: "Spark Chat",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface),
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(8.dp))

                            // Presence Indicator Badge
                            PresenceBadge(presence = presence)
                        }

                        if (runningTask != null) {
                            Text(
                                text = runningTask.currentOperation,
                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary),
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { showConversationsSheet = true },
                        modifier = Modifier.testTag("chat_menu_button")
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = "Conversations", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    // Summarize into memory action
                    IconButton(
                        onClick = {
                            scope.launch {
                                if (messages.isEmpty()) {
                                    Toast.makeText(context, "No messages in this chat to summarize", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                isSummarizingMemory = true
                                try {
                                    val provider = repository.getActiveProvider()
                                    val conversationText = messages.joinToString("\n") { "${it.sender}: ${it.content}" }
                                    val prompt = "Extract durable facts, user preferences, and key context from this conversation into concise markdown bullet points. Do NOT copy verbatim messages. Return ONLY the bullet points:\n\n$conversationText"
                                    val response = provider.sendMessage(listOf(AIMessage(role = "user", content = prompt)), emptyList())
                                    proposedMemorySummary = response.text.orEmpty().ifBlank { "- User preference logged from conversation" }
                                    showSummarizeDialog = true
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Summarize failed: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isSummarizingMemory = false
                                }
                            }
                        },
                        enabled = !isSummarizingMemory,
                        modifier = Modifier.testTag("summarize_into_memory_button")
                    ) {
                        if (isSummarizingMemory) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Psychology, contentDescription = "Summarize into memory", tint = WarmAccent)
                        }
                    }

                    IconButton(
                        onClick = {
                            scope.launch {
                                val newConv = repository.createConversation("New Chat")
                                activeConversationId = newConv.id
                            }
                        },
                        modifier = Modifier.testTag("new_chat_button")
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, contentDescription = "New Chat", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
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
            // Task status card if running
            if (runningTask != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 1.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (runningTask.status == "WAITING_APPROVAL") WarmWarning else MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (runningTask.status == "RUNNING" || runningTask.status == "PENDING") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Status",
                                tint = WarmWarning,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (runningTask.status == "WAITING_APPROVAL") "Approval Required: ${runningTask.pendingApprovalToolName}" else runningTask.currentOperation,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface),
                                maxLines = 1
                            )
                        }
                        if (runningTask.status == "WAITING_APPROVAL") {
                            Button(
                                onClick = { onNavigateToScreen(NovaScreen.TASKS) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.height(32.dp).testTag("review_task_button")
                            ) {
                                Text("Review", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            IconButton(
                                onClick = { repository.cancelTask(runningTask.id) },
                                modifier = Modifier.size(32.dp).testTag("cancel_task_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Cancel task", tint = WarmError)
                            }
                        }
                    }
                }
            }

            // Messages LazyColumn
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageItem(
                        message = msg,
                        onOpenAttachment = { onNavigateToScreen(NovaScreen.FILES) }
                    )
                }

                // If empty conversation
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("✨", fontSize = 26.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "How can Spark help today?",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Ask questions, browse the web, organize files, or inspect autonomous tasks.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )

                                Spacer(modifier = Modifier.height(20.dp))

                                // Quick suggestion pills
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    listOf(
                                        "Research latest tech news in the browser",
                                        "Draft a project outline in Documents/",
                                        "Check available models in Settings"
                                    ).forEach { prompt ->
                                        Surface(
                                            shape = RoundedCornerShape(20.dp),
                                            color = MaterialTheme.colorScheme.surface,
                                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                            modifier = Modifier.clickable { inputText = prompt }
                                        ) {
                                            Text(
                                                text = prompt,
                                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Attached files indicator
            if (attachedFilePaths.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (path in attachedFilePaths) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = path.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium))
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = StoneMuted,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { attachedFilePaths = attachedFilePaths.filter { it != path } }
                                )
                            }
                        }
                    }
                }
            }

            // Pill-shaped chat input bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shadowElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { showAttachFileDialog = true },
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("attach_file_button")
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = "Attach File", tint = StoneMuted, modifier = Modifier.size(20.dp))
                        }

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Ask Spark or type a task...", fontSize = 14.sp, color = StoneLight) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_input_field"),
                            maxLines = 4,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            )
                        )

                        val canSend = inputText.isNotBlank() && runningTask == null
                        IconButton(
                            onClick = {
                                if (canSend) {
                                    val text = inputText.trim()
                                    val convId = activeConversationId ?: UUID.randomUUID().toString()
                                    inputText = ""
                                    val attached = attachedFilePaths
                                    attachedFilePaths = emptyList()

                                    // Save user message
                                    scope.launch {
                                        repository.chatDao.insertMessage(
                                            ChatMessageEntity(
                                                id = UUID.randomUUID().toString(),
                                                conversationId = convId,
                                                sender = "user",
                                                content = text,
                                                attachedFilesJson = if (attached.isNotEmpty()) org.json.JSONArray(attached).toString() else null,
                                                timestamp = System.currentTimeMillis()
                                            )
                                        )
                                        repository.handleUserChatMessage(convId, text, attached)
                                    }
                                }
                            },
                            enabled = canSend,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .testTag("send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary else StoneLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Conversations Drawer / Bottom Sheet
    if (showConversationsSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showConversationsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Conversations",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            scope.launch {
                                val newConv = repository.createConversation("New Chat")
                                activeConversationId = newConv.id
                                showConversationsSheet = false
                            }
                        },
                        modifier = Modifier.testTag("drawer_new_chat_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New Conversation", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        val isSelected = conv.id == activeConversationId
                        Surface(
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    activeConversationId = conv.id
                                    showConversationsSheet = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = conv.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        showRenameDialog = conv
                                        renameTitleInput = conv.title
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Rename", tint = StoneMuted, modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            repository.chatDao.deleteMessagesForConversation(conv.id)
                                            repository.chatDao.deleteConversationById(conv.id)
                                            if (activeConversationId == conv.id) {
                                                activeConversationId = null
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = WarmError, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Attach File Dialog
    if (showAttachFileDialog) {
        val workspaceFiles = remember {
            try {
                repository.workspaceManager.listFiles("")
            } catch (e: Exception) {
                emptyList()
            }
        }
        AlertDialog(
            onDismissRequest = { showAttachFileDialog = false },
            title = { Text("Attach Workspace File") },
            text = {
                LazyColumn(modifier = Modifier.height(200.dp)) {
                    items(workspaceFiles) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!attachedFilePaths.contains(file.relativePath)) {
                                        attachedFilePaths = attachedFilePaths + file.relativePath
                                    }
                                    showAttachFileDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = file.relativePath, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAttachFileDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Rename Dialog
    if (showRenameDialog != null) {
        val conv = showRenameDialog!!
        AlertDialog(
            onDismissRequest = { showRenameDialog = null },
            title = { Text("Rename Conversation") },
            text = {
                OutlinedTextField(
                    value = renameTitleInput,
                    onValueChange = { renameTitleInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.chatDao.updateConversation(conv.copy(title = renameTitleInput.trim().ifBlank { conv.title }))
                            showRenameDialog = null
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = null }) { Text("Cancel") }
            }
        )
    }

    // Summarize Into Memory Confirmation Dialog
    if (showSummarizeDialog) {
        AlertDialog(
            onDismissRequest = { showSummarizeDialog = false },
            title = { Text("Confirm Memory Summary") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The AI extracted the following durable facts from your current conversation. Confirm or edit before appending to Memory.md:", style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted))
                    OutlinedTextField(
                        value = proposedMemorySummary,
                        onValueChange = { proposedMemorySummary = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                            val formattedEntry = "\n\n### Chat Summary ($timeStr)\n$proposedMemorySummary"
                            repository.workspaceManager.appendFile("Memory/Memory.md", formattedEntry)
                            repository.memoryDao.insertMemory(
                                MemoryEntryEntity(
                                    id = UUID.randomUUID().toString(),
                                    key = "summary_${System.currentTimeMillis()}",
                                    content = proposedMemorySummary,
                                    category = "user_preference"
                                )
                            )
                            Toast.makeText(context, "Saved to Memory.md", Toast.LENGTH_SHORT).show()
                            showSummarizeDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Confirm & Save to Memory")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSummarizeDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun ChatMessageItem(
    message: ChatMessageEntity,
    onOpenAttachment: (String) -> Unit
) {
    val isUser = message.sender == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = if (isUser) {
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
            } else {
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 4.dp)
            },
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .border(
                    width = 1.dp,
                    color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outline,
                    shape = if (isUser) {
                        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
                    } else {
                        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 4.dp)
                    }
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isUser) 0.dp else 1.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header (sender indicator)
                Text(
                    text = if (isUser) "You" else "Spark",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isUser) MaterialTheme.colorScheme.primary else StoneMuted,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Message Markdown content
                MarkdownText(markdown = message.content)

                // Attached files if any
                if (!message.attachedFilesJson.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Attached: ${message.attachedFilesJson}",
                        style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted)
                    )
                }
            }
        }
    }
}

@Composable
fun PresenceBadge(presence: PipelinePresence) {
    val (color, containerColor) = when (presence) {
        PipelinePresence.READY -> Pair(WarmSuccess, WarmSuccess.copy(alpha = 0.15f))
        PipelinePresence.WORKING -> Pair(WarmAccent, WarmAccentContainer)
        PipelinePresence.COMPOSING -> Pair(WarmAccentDark, WarmAccentContainer)
        PipelinePresence.RESPONDING -> Pair(WarmAccent, WarmAccentContainer)
        PipelinePresence.MAKING_SOMETHING -> Pair(WarmAccent, WarmAccentContainer)
        PipelinePresence.NEEDS_APPROVAL -> Pair(WarmWarning, WarmWarning.copy(alpha = 0.15f))
        PipelinePresence.PROVIDER_LIMIT_REACHED -> Pair(WarmError, WarmError.copy(alpha = 0.15f))
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, color.copy(alpha = 0.4f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = presence.label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            )
        }
    }
}

