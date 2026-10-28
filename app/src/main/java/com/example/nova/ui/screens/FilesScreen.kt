package com.example.nova.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.nova.ai.ProviderErrors
import com.example.nova.data.NovaRepository
import com.example.nova.ui.components.MarkdownText
import com.example.nova.ui.components.NovaBadge
import com.example.nova.ui.components.NovaSkeleton
import com.example.nova.workspace.FileCategory
import com.example.nova.workspace.FileMetadata
import com.example.nova.workspace.FileSortOption
import com.example.nova.workspace.FileViewMode
import com.example.nova.workspace.WorkspaceFile
import com.example.ui.theme.StoneLight
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmAccent
import com.example.ui.theme.WarmAccentContainer
import com.example.ui.theme.WarmBorder
import com.example.ui.theme.WarmError
import com.example.ui.theme.WarmSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    repository: NovaRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wm = repository.workspaceManager

    var currentDirectory by remember { mutableStateOf("") }
    var currentSort by remember { mutableStateOf(FileSortOption.NAME_ASC) }
    var viewMode by remember { mutableStateOf(FileViewMode.LIST) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    // Multi-select state
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // Robust File State: guarantees zero UI thread crashes
    var fileList by remember { mutableStateOf<List<WorkspaceFile>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var fileLoadError by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableIntStateOf(0) }

    // Dialog states
    var showCreateFileDialog by remember { mutableStateOf(false) }
    var newFileNameInput by remember { mutableStateOf("") }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderNameInput by remember { mutableStateOf("") }
    var fileToDelete by remember { mutableStateOf<WorkspaceFile?>(null) }
    var fileToRename by remember { mutableStateOf<WorkspaceFile?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var metadataToShow by remember { mutableStateOf<FileMetadata?>(null) }

    // Previews & In-App Editor
    var editingFilePath by remember { mutableStateOf<String?>(null) }
    var editorContent by remember { mutableStateOf("") }
    var editorIsMarkdownPreview by remember { mutableStateOf(false) }
    var imagePreviewFile by remember { mutableStateOf<File?>(null) }

    // Document Picker for Upload
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val contentResolver = context.contentResolver
                    var displayName = "imported_file_${System.currentTimeMillis()}"
                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            displayName = cursor.getString(nameIndex)
                        }
                    }
                    val targetRel = if (currentDirectory.isBlank()) displayName else "$currentDirectory/$displayName"
                    contentResolver.openInputStream(uri)?.use { stream ->
                        wm.importFromInputStream(stream, targetRel)
                    }
                    Toast.makeText(context, "Uploaded $displayName", Toast.LENGTH_SHORT).show()
                    reloadTrigger++
                } catch (e: Exception) {
                    Toast.makeText(context, "Upload failed: ${ProviderErrors.userMessage(e)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Coroutine loader with stale request cancellation
    LaunchedEffect(currentDirectory, currentSort, searchQuery, isSearching, reloadTrigger) {
        isLoadingFiles = true
        fileLoadError = null
        try {
            val list = withContext(Dispatchers.IO) {
                if (isSearching && searchQuery.isNotBlank()) {
                    wm.searchFiles(searchQuery, currentDirectory)
                } else {
                    wm.listFiles(currentDirectory, currentSort)
                }
            }
            fileList = list.distinctBy { it.relativePath }
        } catch (e: Exception) {
            fileLoadError = e.message ?: "Failed to read files."
            fileList = emptyList()
        } finally {
            isLoadingFiles = false
        }
    }

    // Section toggle: 0 = Folder Tree, 1 = Created by Spark
    var selectedSectionIndex by remember { mutableIntStateOf(0) }
    val createdFiles by repository.createdFiles.collectAsState(initial = emptyList())
    var viewingHtmlPath by remember { mutableStateOf<Pair<String, String>?>(null) }
    var viewingTextPath by remember { mutableStateOf<Pair<String, String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isMultiSelectMode) "${selectedPaths.size} Selected" else if (selectedSectionIndex == 1) "Created by Spark" else "Workspace Files",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (selectedSectionIndex == 1) "${createdFiles.size} agent-generated files" else if (currentDirectory.isBlank()) "Root (/)" else "/$currentDirectory",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary)
                        )
                    }
                },
                navigationIcon = {
                    if (isMultiSelectMode) {
                        IconButton(onClick = {
                            isMultiSelectMode = false
                            selectedPaths = emptySet()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit Selection")
                        }
                    } else if (currentDirectory.isNotBlank() && selectedSectionIndex == 0) {
                        IconButton(
                            onClick = {
                                val parent = currentDirectory.substringBeforeLast('/', "")
                                currentDirectory = parent
                            },
                            modifier = Modifier.testTag("files_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Parent directory")
                        }
                    }
                },
                actions = {
                    if (!isMultiSelectMode) {
                        // Search Toggle
                        IconButton(
                            onClick = { isSearching = !isSearching },
                            modifier = Modifier.testTag("files_search_button")
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = if (isSearching) MaterialTheme.colorScheme.primary else StoneMuted)
                        }

                        // View Mode Toggle (Grid/List)
                        IconButton(
                            onClick = {
                                viewMode = if (viewMode == FileViewMode.LIST) FileViewMode.GRID else FileViewMode.LIST
                            },
                            modifier = Modifier.testTag("files_view_mode_button")
                        ) {
                            Icon(
                                imageVector = if (viewMode == FileViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList,
                                contentDescription = "Toggle Grid/List View",
                                tint = StoneMuted
                            )
                        }

                        // Upload / Import File
                        IconButton(
                            onClick = { importLauncher.launch("*/*") },
                            modifier = Modifier.testTag("files_upload_button")
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = "Upload Document", tint = MaterialTheme.colorScheme.onSurface)
                        }

                        // New Folder
                        IconButton(
                            onClick = { showCreateFolderDialog = true },
                            modifier = Modifier.testTag("create_folder_button")
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder", tint = MaterialTheme.colorScheme.onSurface)
                        }

                        // New File
                        IconButton(
                            onClick = { showCreateFileDialog = true },
                            modifier = Modifier.testTag("create_file_button")
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Add, contentDescription = "New file", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    } else {
                        // Multi-select select all
                        IconButton(onClick = {
                            selectedPaths = if (selectedPaths.size == fileList.size) emptySet() else fileList.map { it.relativePath }.toSet()
                        }) {
                            Icon(
                                imageVector = if (selectedPaths.size == fileList.size && fileList.isNotEmpty()) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = "Select All"
                            )
                        }
                        // Delete selected
                        IconButton(
                            onClick = { showBatchDeleteConfirm = true },
                            enabled = selectedPaths.isNotEmpty(),
                            modifier = Modifier.testTag("batch_delete_button")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = if (selectedPaths.isNotEmpty()) WarmError else StoneLight)
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
            // Section Switcher: Workspace Files vs Created by Spark
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selectedSectionIndex == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { selectedSectionIndex = 0 }
                ) {
                    Text(
                        text = "Folder Tree",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedSectionIndex == 0) Color.White else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (selectedSectionIndex == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { selectedSectionIndex = 1 }
                ) {
                    Text(
                        text = "Created by Spark (${createdFiles.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedSectionIndex == 1) Color.White else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            if (selectedSectionIndex == 1) {
                // Render "Created by Spark" Section
                if (createdFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("✨", fontSize = 36.sp)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("No files created by Spark yet.", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("When Spark writes or generates files during tasks, they appear here.", style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(createdFiles, key = { it.relativePath }) { agentFile ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        try {
                                            val content = wm.readFileWithLimit(agentFile.relativePath)
                                            val ext = agentFile.relativePath.substringAfterLast('.').lowercase()
                                            if (ext == "html" || ext == "htm") {
                                                viewingHtmlPath = Pair(agentFile.relativePath, content)
                                            } else {
                                                viewingTextPath = Pair(agentFile.relativePath, content)
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Read error: ${ProviderErrors.userMessage(e)}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(agentFile.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                        Text(agentFile.relativePath, style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted, fontSize = 11.sp))
                                    }
                                    val ext = agentFile.relativePath.substringAfterLast('.').uppercase()
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(ext, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Interactive Breadcrumbs Bar
                BreadcrumbBar(
                    currentDirectory = currentDirectory,
                    onNavigate = { currentDirectory = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )

            // Install Environment Card (only at root)
            if (currentDirectory.isBlank() && !isLoadingFiles && fileLoadError == null) {
                val envInstalled = remember(reloadTrigger) { wm.isEnvironmentInstalled() }
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("install_environment_card")
                ) {
                    Row(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Environment Files",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                if (envInstalled) "Environment installed ✓ — reinstalling won't overwrite your files." else "Install agent templates (${wm.getEnvironmentPackSize()}). Won't overwrite user files.",
                                style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted, fontSize = 11.sp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val count = wm.installEnvironmentPack()
                                Toast.makeText(context, "Installed $count environment file(s)", Toast.LENGTH_SHORT).show()
                                reloadTrigger++
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(if (envInstalled) "Reinstall Environment" else "Install Environment", fontSize = 11.sp)
                        }
                    }
                }
            }

            // Search input if open
            if (isSearching) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .height(48.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = StoneMuted, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search files in workspace...", fontSize = 13.sp, color = StoneLight) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("files_search_input")
                        )
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = StoneMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // Error Boundary Fallback Card with explicit Retry button
            if (fileLoadError != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, WarmError.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag("files_error_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = WarmError, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "File Operation Failed",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = WarmError)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = fileLoadError ?: "An unexpected error occurred while accessing files.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                fileLoadError = null
                                reloadTrigger++
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.testTag("retry_files_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Loading Skeletons
            if (isLoadingFiles) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    repeat(4) {
                        NovaSkeleton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }
                }
            }

            // Render Files List or Grid
            if (!isLoadingFiles && fileLoadError == null) {
                if (fileList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = StoneLight, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isSearching) "No files match \"$searchQuery\"" else "Folder is empty",
                                style = MaterialTheme.typography.bodyMedium.copy(color = StoneMuted)
                            )
                        }
                    }
                } else if (viewMode == FileViewMode.LIST) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(fileList, key = { it.relativePath }) { file ->
                            val isSelected = selectedPaths.contains(file.relativePath)
                            FileListItemRow(
                                file = file,
                                isMultiSelectMode = isMultiSelectMode,
                                isSelected = isSelected,
                                onSelectToggle = {
                                    selectedPaths = if (isSelected) selectedPaths - file.relativePath else selectedPaths + file.relativePath
                                },
                                onLongPress = {
                                    if (!isMultiSelectMode) {
                                        isMultiSelectMode = true
                                        selectedPaths = setOf(file.relativePath)
                                    }
                                },
                                onClick = {
                                    if (isMultiSelectMode) {
                                        selectedPaths = if (isSelected) selectedPaths - file.relativePath else selectedPaths + file.relativePath
                                    } else {
                                        handleFileClick(
                                            file = file,
                                            wm = wm,
                                            context = context,
                                            onOpenFolder = { currentDirectory = it },
                                            onOpenEditor = { path, content ->
                                                editingFilePath = path
                                                editorContent = content
                                                editorIsMarkdownPreview = path.endsWith(".md", ignoreCase = true)
                                            },
                                            onOpenImage = { f -> imagePreviewFile = f }
                                        )
                                    }
                                },
                                onInfo = {
                                    try {
                                        metadataToShow = wm.getFileMetadata(file.relativePath)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Metadata error: ${ProviderErrors.userMessage(e)}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onRename = {
                                    fileToRename = file
                                    renameInput = file.name
                                },
                                onDelete = { fileToDelete = file },
                                onShare = { shareFile(context, wm.resolve(file.relativePath)) }
                            )
                        }
                    }
                } else {
                    // Grid View
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(fileList, key = { it.relativePath }) { file ->
                            val isSelected = selectedPaths.contains(file.relativePath)
                            FileGridItemCard(
                                file = file,
                                isMultiSelectMode = isMultiSelectMode,
                                isSelected = isSelected,
                                onSelectToggle = {
                                    selectedPaths = if (isSelected) selectedPaths - file.relativePath else selectedPaths + file.relativePath
                                },
                                onLongPress = {
                                    if (!isMultiSelectMode) {
                                        isMultiSelectMode = true
                                        selectedPaths = setOf(file.relativePath)
                                    }
                                },
                                onClick = {
                                    if (isMultiSelectMode) {
                                        selectedPaths = if (isSelected) selectedPaths - file.relativePath else selectedPaths + file.relativePath
                                    } else {
                                        handleFileClick(
                                            file = file,
                                            wm = wm,
                                            context = context,
                                            onOpenFolder = { currentDirectory = it },
                                            onOpenEditor = { path, content ->
                                                editingFilePath = path
                                                editorContent = content
                                                editorIsMarkdownPreview = path.endsWith(".md", ignoreCase = true)
                                            },
                                            onOpenImage = { f -> imagePreviewFile = f }
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    }

    // Simple HTML Viewer Dialog
    if (viewingHtmlPath != null) {
        val (path, content) = viewingHtmlPath!!
        AlertDialog(
            onDismissRequest = { viewingHtmlPath = null },
            title = {
                Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                ) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                loadDataWithBaseURL(null, content, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingHtmlPath = null }) { Text("Close") }
            }
        )
    }

    // Simple Text/CSV/Markdown Viewer Dialog
    if (viewingTextPath != null) {
        val (path, content) = viewingTextPath!!
        AlertDialog(
            onDismissRequest = { viewingTextPath = null },
            title = {
                Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Text(
                                text = content,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingTextPath = null }) { Text("Close") }
            }
        )
    }

    // --- DIALOGS & PREVIEW MODALS ---

    // 1. Text & Code In-App Editor / Markdown Preview
    if (editingFilePath != null) {
        val rel = editingFilePath!!
        val isMd = rel.endsWith(".md", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { editingFilePath = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rel.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(rel, style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted), fontSize = 11.sp)
                    }
                    if (isMd) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .clickable { editorIsMarkdownPreview = !editorIsMarkdownPreview }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (editorIsMarkdownPreview) Icons.Default.Edit else Icons.Default.Visibility,
                                    contentDescription = "Toggle Preview",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (editorIsMarkdownPreview) "Edit" else "Preview", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                ) {
                    if (isMd && editorIsMarkdownPreview) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item { MarkdownText(markdown = editorContent) }
                        }
                    } else {
                        OutlinedTextField(
                            value = editorContent,
                            onValueChange = { editorContent = it },
                            modifier = Modifier.fillMaxSize().testTag("file_editor_textarea"),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            wm.writeFile(rel, editorContent)
                            Toast.makeText(context, "Saved $rel", Toast.LENGTH_SHORT).show()
                            editingFilePath = null
                            reloadTrigger++
                        } catch (e: Exception) {
                            Toast.makeText(context, "Save error: ${ProviderErrors.userMessage(e)}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("save_file_button")
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingFilePath = null }) { Text("Close") }
            }
        )
    }

    // 2. Image Fullscreen Preview Modal
    if (imagePreviewFile != null) {
        val img = imagePreviewFile!!
        AlertDialog(
            onDismissRequest = { imagePreviewFile = null },
            title = {
                Text(img.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = img,
                        contentDescription = img.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { shareFile(context, img) },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share")
                }
            },
            dismissButton = {
                TextButton(onClick = { imagePreviewFile = null }) { Text("Close") }
            }
        )
    }

    // 3. Create File Dialog
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
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("new_file_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newFileNameInput.trim()
                        if (name.isNotBlank()) {
                            try {
                                val rel = if (currentDirectory.isBlank()) name else "$currentDirectory/$name"
                                wm.writeFile(rel, "")
                                showCreateFileDialog = false
                                newFileNameInput = ""
                                editorContent = ""
                                editingFilePath = rel
                                reloadTrigger++
                            } catch (e: Exception) {
                                Toast.makeText(context, "Create error: ${ProviderErrors.userMessage(e)}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("confirm_create_file_button")
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFileDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 4. Create Folder Dialog
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
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("new_folder_name_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newFolderNameInput.trim()
                        if (name.isNotBlank()) {
                            try {
                                val rel = if (currentDirectory.isBlank()) name else "$currentDirectory/$name"
                                wm.createDirectory(rel)
                                showCreateFolderDialog = false
                                newFolderNameInput = ""
                                reloadTrigger++
                            } catch (e: Exception) {
                                Toast.makeText(context, "Create folder error: ${ProviderErrors.userMessage(e)}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("confirm_create_folder_button")
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 5. Delete Single File Dialog
    if (fileToDelete != null) {
        val target = fileToDelete!!
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete ${if (target.isDirectory) "Folder" else "File"}?") },
            text = { Text("Are you sure you want to permanently delete '${target.name}'? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            wm.deleteFile(target.relativePath)
                            fileToDelete = null
                            reloadTrigger++
                        } catch (e: Exception) {
                            Toast.makeText(context, "Delete error: ${ProviderErrors.userMessage(e)}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarmError),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("confirm_delete_file_button")
                ) { Text("Delete", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("Cancel") }
            }
        )
    }

    // 6. Batch Delete Confirmation
    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("Delete ${selectedPaths.size} Items?") },
            text = { Text("This will permanently delete the selected files from your workspace.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            selectedPaths.forEach { rel ->
                                try { wm.deleteFile(rel) } catch (ignored: Exception) {}
                            }
                            selectedPaths = emptySet()
                            isMultiSelectMode = false
                            showBatchDeleteConfirm = false
                            reloadTrigger++
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarmError),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Delete All", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // 7. Rename Dialog
    if (fileToRename != null) {
        val target = fileToRename!!
        AlertDialog(
            onDismissRequest = { fileToRename = null },
            title = { Text("Rename Item") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text("New Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("rename_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newN = renameInput.trim()
                        if (newN.isNotBlank()) {
                            try {
                                wm.renameFile(target.relativePath, newN)
                                fileToRename = null
                                reloadTrigger++
                            } catch (e: Exception) {
                                Toast.makeText(context, "Rename error: ${ProviderErrors.userMessage(e)}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.testTag("confirm_rename_button")
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { fileToRename = null }) { Text("Cancel") }
            }
        )
    }

    // 8. Metadata Inspector Dialog
    if (metadataToShow != null) {
        val meta = metadataToShow!!
        AlertDialog(
            onDismissRequest = { metadataToShow = null },
            title = { Text("File Properties", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Name: ${meta.name}", fontWeight = FontWeight.SemiBold)
                    Text("Path: /${meta.relativePath}", fontSize = 12.sp, color = StoneMuted)
                    Text("Size: ${meta.readableSize} (${meta.sizeBytes} bytes)", fontSize = 13.sp)
                    Text("Modified: ${meta.lastModifiedDate}", fontSize = 13.sp)
                    Text("MIME Type: ${meta.mimeType}", fontSize = 13.sp)
                    if (meta.lineCount != null) {
                        Text("Lines: ${meta.lineCount}", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { metadataToShow = null }) { Text("Done") }
            }
        )
    }
}

private fun handleFileClick(
    file: WorkspaceFile,
    wm: com.example.nova.workspace.WorkspaceManager,
    context: android.content.Context,
    onOpenFolder: (String) -> Unit,
    onOpenEditor: (String, String) -> Unit,
    onOpenImage: (File) -> Unit
) {
    if (file.isDirectory) {
        onOpenFolder(file.relativePath)
    } else {
        val ext = file.extension.lowercase()
        if (ext in listOf("jpg", "jpeg", "png", "webp")) {
            onOpenImage(wm.resolve(file.relativePath))
        } else if (wm.isTextExtension(ext)) {
            try {
                val content = wm.readFileWithLimit(file.relativePath)
                onOpenEditor(file.relativePath, content)
            } catch (e: Exception) {
                Toast.makeText(context, "Read error: ${ProviderErrors.userMessage(e)}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "File: ${file.name} (${file.extension.uppercase()})", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun shareFile(context: android.content.Context, file: File) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
            putExtra(Intent.EXTRA_TEXT, "Shared from NOVA Workspace: ${file.name}")
        }
        context.startActivity(Intent.createChooser(intent, "Share file"))
    } catch (e: Exception) {
        Toast.makeText(context, "Share: ${file.name}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun BreadcrumbBar(
    currentDirectory: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val segments = remember(currentDirectory) {
        if (currentDirectory.isBlank()) emptyList() else currentDirectory.split('/')
    }

    LazyRow(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (currentDirectory.isBlank()) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                modifier = Modifier.clickable { onNavigate("") }
            ) {
                Text(
                    text = "Root",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (currentDirectory.isBlank()) MaterialTheme.colorScheme.primary else StoneMuted,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        items(segments.indices.toList()) { index ->
            Text("/", color = StoneLight, fontSize = 12.sp)
            val subPath = segments.take(index + 1).joinToString("/")
            val isLast = index == segments.size - 1
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isLast) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                modifier = Modifier.clickable { onNavigate(subPath) }
            ) {
                Text(
                    text = segments[index],
                    fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.sp,
                    color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun FileListItemRow(
    file: WorkspaceFile,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onSelectToggle: () -> Unit,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
    onInfo: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("file_item_${file.name}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isMultiSelectMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onSelectToggle() },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
            }

            // File Icon
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (file.isDirectory) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getFileIcon(file),
                        contentDescription = null,
                        tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else StoneMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (file.isDirectory) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1
                )
                Text(
                    text = if (file.isDirectory) "Folder" else formatSize(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted, fontSize = 11.sp)
                )
            }

            if (!isMultiSelectMode) {
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Actions", tint = StoneMuted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Properties") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { showMenu = false; onInfo() }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { showMenu = false; onRename() }
                        )
                        if (!file.isDirectory) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Default.Share, null) },
                                onClick = { showMenu = false; onShare() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete", color = WarmError) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = WarmError) },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileGridItemCard(
    file: WorkspaceFile,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onSelectToggle: () -> Unit,
    onLongPress: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .height(115.dp)
            .clickable { onClick() }
            .testTag("file_grid_${file.name}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (file.isDirectory) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = getFileIcon(file),
                            contentDescription = null,
                            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else StoneMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (isMultiSelectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onSelectToggle() },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    text = if (file.isDirectory) "Folder" else formatSize(file.sizeBytes),
                    style = MaterialTheme.typography.labelSmall.copy(color = StoneLight, fontSize = 10.sp)
                )
            }
        }
    }
}

private fun getFileIcon(file: WorkspaceFile): ImageVector {
    if (file.isDirectory) return Icons.Default.Folder
    return when (file.extension.lowercase()) {
        "md" -> Icons.Default.Description
        "kt", "java", "py", "js", "html", "css", "json", "xml" -> Icons.Default.Code
        "jpg", "jpeg", "png", "webp" -> Icons.Default.Image
        else -> Icons.Default.Description
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}
