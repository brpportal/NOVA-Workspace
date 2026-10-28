package com.example.nova.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nova.data.ChatMessageEntity
import com.example.nova.data.ConversationEntity
import com.example.nova.data.NovaRepository
import com.example.nova.ui.components.MarkdownText
import com.example.nova.ui.components.NovaScreen
import com.example.ui.theme.Charcoal600
import com.example.ui.theme.Charcoal700
import com.example.ui.theme.Charcoal800
import com.example.ui.theme.Charcoal900
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.SafetyOrange
import com.example.ui.theme.SafetyRed
import com.example.ui.theme.SlateMuted
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TitaniumSilver
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

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

    var activeConversationId by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    var showConversationsSheet by remember { mutableStateOf(false) }
    var showAttachFileDialog by remember { mutableStateOf(false) }
    var attachedFilePaths by remember { mutableStateOf(listOf<String>()) }
    var showRenameDialog by remember { mutableStateOf<ConversationEntity?>(null) }
    var renameTitleInput by remember { mutableStateOf("") }

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
                        Text(
                            text = currentConversation?.title ?: "NOVA Chat",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        if (runningTask != null) {
                            Text(
                                text = "Agent: ${runningTask.currentOperation}",
                                style = MaterialTheme.typography.labelSmall.copy(color = NovaCyan),
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
                        Icon(Icons.Default.Menu, contentDescription = "Conversations", tint = NovaCyan)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                val newConv = repository.createConversation("New Chat")
                                activeConversationId = newConv.id
                            }
                        },
                        modifier = Modifier.testTag("new_chat_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New Chat", tint = TitaniumSilver)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
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
                    color = Charcoal700,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .border(1.dp, if (runningTask.status == "WAITING_APPROVAL") SafetyOrange else NovaCyan, RoundedCornerShape(8.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (runningTask.status == "RUNNING" || runningTask.status == "PENDING") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = NovaCyan
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Status",
                                tint = SafetyOrange,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (runningTask.status == "WAITING_APPROVAL") "Waiting Approval: ${runningTask.pendingApprovalToolName}" else runningTask.currentOperation,
                                style = MaterialTheme.typography.bodySmall.copy(color = TitaniumSilver),
                                maxLines = 1
                            )
                        }
                        if (runningTask.status == "WAITING_APPROVAL") {
                            Button(
                                onClick = { onNavigateToScreen(NovaScreen.TASKS) },
                                colors = ButtonDefaults.buttonColors(containerColor = SafetyOrange),
                                modifier = Modifier.height(32.dp).testTag("review_task_button")
                            ) {
                                Text("Review", fontSize = 12.sp, color = Charcoal900)
                            }
                        } else {
                            IconButton(
                                onClick = { repository.cancelTask(runningTask.id) },
                                modifier = Modifier.size(32.dp).testTag("cancel_task_button")
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Cancel task", tint = SafetyRed)
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
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageItem(
                        message = msg,
                        onOpenAttachment = { relPath ->
                            onNavigateToScreen(NovaScreen.FILES)
                        }
                    )
                }

                // If empty conversation
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Ready to assist",
                                    style = MaterialTheme.typography.titleMedium.copy(color = NovaCyan, fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Ask questions, automate the browser, edit files, or manage encrypted secrets.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = SlateMuted),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
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
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (path in attachedFilePaths) {
                        Surface(
                            color = Charcoal700,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Charcoal600)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AttachFile, contentDescription = null, tint = NovaCyan, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = path.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall.copy(color = TitaniumSilver))
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint = SlateMuted,
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { attachedFilePaths = attachedFilePaths.filter { it != path } }
                                )
                            }
                        }
                    }
                }
            }

            // Input bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showAttachFileDialog = true },
                        modifier = Modifier.testTag("attach_file_button")
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach File", tint = SlateMuted)
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Message NOVA agent or instruct a task...", fontSize = 14.sp, color = SlateMuted) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_input_field"),
                        maxLines = 4,
                        shape = RoundedCornerShape(20.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Charcoal800,
                            unfocusedContainerColor = Charcoal800,
                            focusedTextColor = TitaniumSilver,
                            unfocusedTextColor = TitaniumSilver,
                            focusedIndicatorColor = NovaCyan,
                            unfocusedIndicatorColor = Charcoal600
                        )
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    val canSend = inputText.isNotBlank() && runningTask == null
                    IconButton(
                        onClick = {
                            if (canSend) {
                                val text = inputText.trim()
                                val convId = activeConversationId ?: UUID.randomUUID().toString()
                                inputText = ""
                                val attached = attachedFilePaths
                                attachedFilePaths = emptyList()

                                scope.launch {
                                    // Save user message
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

                                    // Launch agent task
                                    val promptWithContext = if (attached.isNotEmpty()) {
                                        "$text\n\n[Attached workspace files: ${attached.joinToString(", ")}]"
                                    } else {
                                        text
                                    }
                                    repository.launchTask(
                                        prompt = promptWithContext,
                                        title = text.take(30),
                                        conversationId = convId
                                    )
                                }
                            }
                        },
                        enabled = canSend,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (canSend) NovaCyan else Charcoal700)
                            .testTag("send_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (canSend) Charcoal900 else SlateMuted
                        )
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
            containerColor = Charcoal800
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Conversations",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = NovaCyan)
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
                        Icon(Icons.Default.Add, contentDescription = "New Conversation", tint = TitaniumSilver)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        val isSelected = conv.id == activeConversationId
                        Surface(
                            color = if (isSelected) Charcoal700 else Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable {
                                    activeConversationId = conv.id
                                    showConversationsSheet = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = conv.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = if (isSelected) NovaCyan else TitaniumSilver,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
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
                                    Icon(Icons.Default.Edit, contentDescription = "Rename", tint = SlateMuted, modifier = Modifier.size(16.dp))
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
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = SlateMuted, modifier = Modifier.size(16.dp))
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
        val workspaceFiles = remember { repository.workspaceManager.listFiles("") }
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
                            Icon(Icons.Default.Folder, contentDescription = null, tint = NovaCyan, modifier = Modifier.size(18.dp))
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
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) Charcoal700 else Charcoal800
            ),
            modifier = Modifier
                .widthIn(max = 320.dp)
                .border(
                    width = 1.dp,
                    color = if (isUser) Charcoal600 else Charcoal700,
                    shape = RoundedCornerShape(12.dp)
                )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Header (sender)
                Text(
                    text = if (isUser) "You" else "NOVA Agent",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isUser) NovaCyan else SlateMuted,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Message Markdown content
                MarkdownText(markdown = message.content)

                // Attached files if any
                if (!message.attachedFilesJson.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Attached: ${message.attachedFilesJson}",
                        style = MaterialTheme.typography.labelSmall.copy(color = SlateMuted)
                    )
                }
            }
        }
    }
}
