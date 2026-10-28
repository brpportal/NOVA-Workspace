package com.example.nova.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.nova.data.NovaRepository
import com.example.nova.workspace.FileMetadata
import com.example.nova.workspace.FileSortOption
import com.example.nova.workspace.WorkspaceFile
import com.example.ui.theme.Charcoal600
import com.example.ui.theme.Charcoal700
import com.example.ui.theme.Charcoal800
import com.example.ui.theme.Charcoal900
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.SafetyRed
import com.example.ui.theme.SlateMuted
import com.example.ui.theme.TitaniumSilver

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    repository: NovaRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val wm = repository.workspaceManager

    var currentDirectory by remember { mutableStateOf("") }
    var currentSort by remember { mutableStateOf(FileSortOption.NAME_ASC) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    // Dialog states
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var newFileNameInput by remember { mutableStateOf("") }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderNameInput by remember { mutableStateOf("") }
    var fileToDelete by remember { mutableStateOf<WorkspaceFile?>(null) }
    var fileToRename by remember { mutableStateOf<WorkspaceFile?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var metadataToShow by remember { mutableStateOf<FileMetadata?>(null) }

    // Text Editor/Viewer state
    var editingFilePath by remember { mutableStateOf<String?>(null) }
    var editorContent by remember { mutableStateOf("") }

    val files = remember(currentDirectory, currentSort, searchQuery, isSearching, editingFilePath) {
        if (isSearching && searchQuery.isNotBlank()) {
            wm.searchFiles(searchQuery, currentDirectory)
        } else {
            wm.listFiles(currentDirectory, currentSort)
        }
    }

    if (editingFilePath != null) {
        // --- IN-APP TEXT EDITOR / VIEWER ---
        val path = editingFilePath!!
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text(path, style = MaterialTheme.typography.labelSmall.copy(color = SlateMuted))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { editingFilePath = null }, modifier = Modifier.testTag("editor_back_button")) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TitaniumSilver)
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val ok = wm.writeFile(path, editorContent)
                                if (ok) {
                                    Toast.makeText(context, "Saved $path", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.testTag("save_file_button")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = "Save", tint = NovaCyan)
                        }
                        IconButton(
                            onClick = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, editorContent)
                                    putExtra(Intent.EXTRA_TITLE, path.substringAfterLast('/'))
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Export File Content"))
                            },
                            modifier = Modifier.testTag("share_file_button")
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Export/Share", tint = TitaniumSilver)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Charcoal800)
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(Charcoal900)
            ) {
                OutlinedTextField(
                    value = editorContent,
                    onValueChange = { editorContent = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .testTag("file_editor_content"),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = TitaniumSilver,
                        lineHeight = 20.sp
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Charcoal800,
                        unfocusedContainerColor = Charcoal800,
                        focusedIndicatorColor = NovaCyan,
                        unfocusedIndicatorColor = Charcoal600
                    )
                )
            }
        }
        return
    }

    // --- STANDARD FILE EXPLORER ---
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Workspace Files", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            text = if (currentDirectory.isBlank()) "Root (/)" else "/$currentDirectory",
                            style = MaterialTheme.typography.labelSmall.copy(color = NovaCyan)
                        )
                    }
                },
                navigationIcon = {
                    if (currentDirectory.isNotBlank()) {
                        IconButton(
                            onClick = {
                                val parent = currentDirectory.substringBeforeLast('/', "")
                                currentDirectory = parent
                            },
                            modifier = Modifier.testTag("files_back_button")
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Parent directory", tint = TitaniumSilver)
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { isSearching = !isSearching },
                        modifier = Modifier.testTag("files_search_button")
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = if (isSearching) NovaCyan else SlateMuted)
                    }
                    IconButton(
                        onClick = { showCreateFolderDialog = true },
                        modifier = Modifier.testTag("create_folder_button")
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder", tint = TitaniumSilver)
                    }
                    IconButton(
                        onClick = { showCreateFileDialog = true },
                        modifier = Modifier.testTag("create_file_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New file", tint = NovaCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Charcoal900)
        ) {
            // Search field if searching
            if (isSearching) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search files in workspace...", fontSize = 13.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .height(48.dp)
                        .testTag("files_search_input"),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Charcoal800,
                        unfocusedContainerColor = Charcoal800,
                        focusedIndicatorColor = NovaCyan,
                        unfocusedIndicatorColor = Charcoal600
                    )
                )
            }

            // Quick folder chips
            if (currentDirectory.isBlank() && !isSearching) {
                Text(
                    text = "Standard Directories",
                    style = MaterialTheme.typography.labelSmall.copy(color = SlateMuted, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // File items list
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(files, key = { it.relativePath }) { file ->
                    FileRowItem(
                        file = file,
                        onClick = {
                            if (file.isDirectory) {
                                currentDirectory = file.relativePath
                            } else {
                                if (wm.isTextExtension(file.extension)) {
                                    try {
                                        editorContent = wm.readFile(file.relativePath)
                                        editingFilePath = file.relativePath
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Read error: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Binary file: ${file.name}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onInfo = {
                            try {
                                metadataToShow = wm.getFileMetadata(file.relativePath)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Metadata error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRename = {
                            fileToRename = file
                            renameInput = file.name
                        },
                        onDelete = {
                            fileToDelete = file
                        }
                    )
                }

                if (files.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isSearching) "No matching files found." else "Directory is empty.",
                                style = MaterialTheme.typography.bodyMedium.copy(color = SlateMuted)
                            )
                        }
                    }
                }
            }
        }
    }

    // Create File Dialog
    if (showCreateFileDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFileDialog = false },
            title = { Text("Create New File") },
            text = {
                OutlinedTextField(
                    value = newFileNameInput,
                    onValueChange = { newFileNameInput = it },
                    label = { Text("File Name (e.g. notes.md, plan.txt)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_file_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newFileNameInput.trim()
                        if (name.isNotBlank()) {
                            val rel = if (currentDirectory.isBlank()) name else "$currentDirectory/$name"
                            wm.writeFile(rel, "")
                            showCreateFileDialog = false
                            newFileNameInput = ""
                            editorContent = ""
                            editingFilePath = rel
                        }
                    },
                    modifier = Modifier.testTag("confirm_create_file_button")
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFileDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Create Folder Dialog
    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("Create New Folder") },
            text = {
                OutlinedTextField(
                    value = newFolderNameInput,
                    onValueChange = { newFolderNameInput = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_folder_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newFolderNameInput.trim()
                        if (name.isNotBlank()) {
                            val rel = if (currentDirectory.isBlank()) name else "$currentDirectory/$name"
                            wm.createDirectory(rel)
                            showCreateFolderDialog = false
                            newFolderNameInput = ""
                        }
                    },
                    modifier = Modifier.testTag("confirm_create_folder_button")
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete Confirmation Dialog
    if (fileToDelete != null) {
        val f = fileToDelete!!
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = SafetyRed) },
            title = { Text("Delete Confirmation") },
            text = { Text("Are you sure you want to permanently delete '${f.name}'? This cannot be undone.", color = TitaniumSilver) },
            confirmButton = {
                Button(
                    onClick = {
                        wm.deleteFile(f.relativePath)
                        fileToDelete = null
                        Toast.makeText(context, "Deleted ${f.name}", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SafetyRed),
                    modifier = Modifier.testTag("confirm_delete_button")
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("Cancel") }
            }
        )
    }

    // Rename Dialog
    if (fileToRename != null) {
        val f = fileToRename!!
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            title = { Text("Rename File/Folder") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("rename_input_field")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newName = renameInput.trim()
                        if (newName.isNotBlank()) {
                            wm.renameFile(f.relativePath, newName)
                            fileToRename = null
                        }
                    }
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { fileToRename = null }) { Text("Cancel") }
            }
        )
    }

    // Metadata Dialog
    if (metadataToShow != null) {
        val m = metadataToShow!!
        AlertDialog(
            onDismissRequest = { metadataToShow = null },
            title = { Text(m.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Relative Path: ${m.relativePath}", style = MaterialTheme.typography.bodySmall)
                    Text("Size: ${m.readableSize} (${m.sizeBytes} bytes)", style = MaterialTheme.typography.bodySmall)
                    Text("Modified: ${m.lastModifiedDate}", style = MaterialTheme.typography.bodySmall)
                    Text("MIME Type: ${m.mimeType}", style = MaterialTheme.typography.bodySmall)
                    if (m.lineCount != null) {
                        Text("Lines: ${m.lineCount}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { metadataToShow = null }) { Text("Close") }
            }
        )
    }
}

@Composable
fun FileRowItem(
    file: WorkspaceFile,
    onClick: () -> Unit,
    onInfo: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = Charcoal800,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, Charcoal700, RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    file.isDirectory -> Icons.Default.Folder
                    file.extension in listOf("jpg", "jpeg", "png", "webp") -> Icons.Default.Image
                    file.extension in listOf("kt", "java", "js", "py", "html", "css", "json") -> Icons.Default.Code
                    else -> Icons.Default.Description
                },
                contentDescription = null,
                tint = if (file.isDirectory) NovaCyan else SlateMuted,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, color = TitaniumSilver)
                )
                if (!file.isDirectory) {
                    Text(
                        text = "${file.sizeBytes / 1024 + 1} KB",
                        style = MaterialTheme.typography.labelSmall.copy(color = SlateMuted)
                    )
                }
            }

            IconButton(onClick = onInfo, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Info, contentDescription = "Metadata", tint = SlateMuted, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onRename, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Rename", tint = SlateMuted, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = SafetyRed, modifier = Modifier.size(16.dp))
            }
        }
    }
}
