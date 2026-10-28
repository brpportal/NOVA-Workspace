package com.example.nova.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nova.ai.ConnectionResult
import com.example.nova.ai.OpenAICompatibleProvider
import com.example.nova.ai.ProviderProfile
import com.example.nova.data.AppSettingsEntity
import com.example.nova.data.NovaRepository
import com.example.nova.security.PermissionMode
import com.example.nova.ui.components.MarkdownText
import com.example.nova.ui.components.NovaBadge
import com.example.ui.theme.StoneLight
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmAccent
import com.example.ui.theme.WarmAccentContainer
import com.example.ui.theme.WarmBorder
import com.example.ui.theme.WarmError
import com.example.ui.theme.WarmPurple
import com.example.ui.theme.WarmSuccess
import com.example.ui.theme.WarmWarning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: NovaRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wm = repository.workspaceManager

    val secrets by repository.secrets.collectAsState(initial = emptyList())
    val settingsList by repository.settings.collectAsState(initial = emptyList())

    // Settings Top Tabs: 0: AI & Providers, 1: Memory & Training, 2: Secret Vault, 3: Storage
    var selectedTopTab by remember { mutableIntStateOf(0) }

    // --- 1. AI PROVIDER & PROFILES STATE ---
    var selectedProviderType by remember { mutableStateOf("openai_compatible") }
    var apiKeyInput by remember { mutableStateOf("") }
    var modelNameInput by remember { mutableStateOf("gpt-4o-mini") }
    var baseUrlInput by remember { mutableStateOf("https://api.openai.com/v1") }
    var customHeadersInput by remember { mutableStateOf("{}") }
    var isApiKeyRevealed by remember { mutableStateOf(false) }
    var isVisionModel by remember { mutableStateOf(true) }
    var testResult by remember { mutableStateOf<ConnectionResult?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }

    // Fetch models state
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchModelsError by remember { mutableStateOf<String?>(null) }
    var fetchModelsInfo by remember { mutableStateOf<String?>(null) }
    var modelSearchQuery by remember { mutableStateOf("") }
    val modelFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    var requestModelFocus by remember { mutableStateOf(false) }

    // When the provider returns 0 models, guide the user to the manual field.
    LaunchedEffect(requestModelFocus) {
        if (requestModelFocus) {
            try { modelFocusRequester.requestFocus() } catch (e: Exception) { }
            requestModelFocus = false
        }
    }

    // --- 2. MEMORY & TRAINING STATE ---
    var activeMemoryTab by remember { mutableIntStateOf(0) } // 0: Memory.md, 1: Training.md
    var memoryContent by remember { mutableStateOf("") }
    var trainingContent by remember { mutableStateOf("") }
    var isMemoryPreviewMode by remember { mutableStateOf(false) }
    var memorySaveStatus by remember { mutableStateOf("Saved") }
    var showBackupRestoreDialog by remember { mutableStateOf(false) }
    var backupList by remember { mutableStateOf<List<String>>(emptyList()) }
    var showProposedDiffDialog by remember { mutableStateOf(false) }
    var proposedAdditionText by remember { mutableStateOf("") }

    // --- 3. SECRET VAULT STATE ---
    var showAddSecretDialog by remember { mutableStateOf(false) }
    var newSecretName by remember { mutableStateOf("") }
    var newSecretUsername by remember { mutableStateOf("") }
    var newSecretDomain by remember { mutableStateOf("") }
    var newSecretValue by remember { mutableStateOf("") }
    var newSecretMode by remember { mutableStateOf(PermissionMode.ASK_EVERY_TIME) }
    var revealedSecretId by remember { mutableStateOf<String?>(null) }
    var revealedSecretValue by remember { mutableStateOf<String?>(null) }

    // Reset Confirm Dialog
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    // Load initial settings
    LaunchedEffect(settingsList) {
        selectedProviderType = settingsList.find { it.key == "ai_provider_type" }?.value ?: "openai_compatible"
        apiKeyInput = settingsList.find { it.key == "ai_api_key" }?.value ?: ""
        modelNameInput = settingsList.find { it.key == "ai_model" }?.value ?: "gpt-4o-mini"
        baseUrlInput = settingsList.find { it.key == "ai_endpoint" }?.value ?: "https://api.openai.com/v1"
        isVisionModel = ProviderProfile.isVisionCapableModel(modelNameInput)
    }

    // Load Memory.md and Training.md from Workspace
    LaunchedEffect(Unit) {
        try {
            memoryContent = wm.readFile("Memory/Memory.md")
        } catch (e: Exception) {
            wm.initializeDefaultStructure()
            memoryContent = try { wm.readFile("Memory/Memory.md") } catch (e2: Exception) { "" }
        }
        try {
            trainingContent = wm.readFile("Memory/Training.md")
        } catch (e: Exception) {
            trainingContent = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Vault", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
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
            // Section Tabs: AI & Profiles, Memory & Training, Secret Vault, Privacy
            TabRow(
                selectedTabIndex = selectedTopTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTopTab]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            ) {
                Tab(
                    selected = selectedTopTab == 0,
                    onClick = { selectedTopTab = 0 },
                    text = { Text("AI Engine", fontWeight = if (selectedTopTab == 0) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTopTab == 1,
                    onClick = { selectedTopTab = 1 },
                    text = { Text("Memory & Training", fontWeight = if (selectedTopTab == 1) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTopTab == 2,
                    onClick = { selectedTopTab = 2 },
                    text = { Text("Vault", fontWeight = if (selectedTopTab == 2) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTopTab == 3,
                    onClick = { selectedTopTab = 3 },
                    text = { Text("Privacy", fontWeight = if (selectedTopTab == 3) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp) }
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ==================== TAB 0: AI & PROVIDER PROFILES ====================
                if (selectedTopTab == 0) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "Provider Type",
                                    style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted, fontWeight = FontWeight.Bold)
                                )

                                // Provider selector pills
                                val providers = listOf(
                                    "openai_compatible" to "OpenAI-Compatible",
                                    "gemini" to "Google Gemini",
                                    "anthropic" to "Anthropic Claude",
                                    "fake" to "Offline Autonomous"
                                )

                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(providers) { (type, name) ->
                                        val isSel = selectedProviderType == type
                                        Surface(
                                            shape = RoundedCornerShape(18.dp),
                                            color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent),
                                            modifier = Modifier.clickable {
                                                selectedProviderType = type
                                                if (type == "openai_compatible") {
                                                    baseUrlInput = "https://api.openai.com/v1"
                                                    modelNameInput = "gpt-4o-mini"
                                                } else if (type == "gemini") {
                                                    modelNameInput = "gemini-2.5-flash"
                                                }
                                                scope.launch {
                                                    repository.settingsDao.setSetting(AppSettingsEntity("ai_provider_type", type))
                                                    repository.settingsDao.setSetting(AppSettingsEntity("ai_model", modelNameInput))
                                                    repository.settingsDao.setSetting(AppSettingsEntity("ai_endpoint", baseUrlInput))
                                                }
                                            }
                                        ) {
                                            Text(
                                                text = name,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                ),
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                            )
                                        }
                                    }
                                }

                                // Base URL Input
                                OutlinedTextField(
                                    value = baseUrlInput,
                                    onValueChange = {
                                        baseUrlInput = it
                                        val normalized = OpenAICompatibleProvider.normalizeBaseUrl(it)
                                        scope.launch {
                                            repository.settingsDao.setSetting(AppSettingsEntity("ai_endpoint", normalized))
                                        }
                                    },
                                    label = { Text("API Base URL") },
                                    placeholder = { Text("https://api.openai.com/v1") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("settings_base_url_input")
                                )

                                // API Key Input
                                OutlinedTextField(
                                    value = apiKeyInput,
                                    onValueChange = {
                                        apiKeyInput = it
                                        scope.launch {
                                            repository.settingsDao.setSetting(AppSettingsEntity("ai_api_key", it.trim()))
                                        }
                                    },
                                    label = { Text("API Key") },
                                    visualTransformation = if (isApiKeyRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { isApiKeyRevealed = !isApiKeyRevealed }) {
                                            Icon(
                                                imageVector = if (isApiKeyRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = "Toggle Key",
                                                tint = StoneMuted
                                            )
                                        }
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("api_key_input")
                                )

                                // Fetch Models Action Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Button(
                                        onClick = {
                                            isFetchingModels = true
                                            fetchModelsError = null
                                            fetchModelsInfo = null
                                            scope.launch {
                                                try {
                                                    when (val result = OpenAICompatibleProvider.fetchModelsDetailed(baseUrlInput, apiKeyInput)) {
                                                        is com.example.nova.ai.FetchModelsResult.Success -> {
                                                            fetchedModels = result.models
                                                            Toast.makeText(context, "Loaded ${result.models.size} models", Toast.LENGTH_SHORT).show()
                                                        }
                                                        is com.example.nova.ai.FetchModelsResult.Empty -> {
                                                            // HTTP 200 with an empty list is NOT an error:
                                                            // the provider/plan simply exposes no models.
                                                            fetchedModels = emptyList()
                                                            fetchModelsInfo = "Provider returned 0 models. You can type a model name manually below."
                                                            requestModelFocus = true
                                                        }
                                                        is com.example.nova.ai.FetchModelsResult.HttpError -> {
                                                            fetchedModels = emptyList()
                                                            fetchModelsError = "HTTP ${result.code}: ${result.reason}"
                                                        }
                                                        is com.example.nova.ai.FetchModelsResult.Malformed -> {
                                                            fetchedModels = emptyList()
                                                            fetchModelsError = "Could not read the models list — the response was not valid JSON. You can type a model name manually below."
                                                            requestModelFocus = true
                                                        }
                                                        is com.example.nova.ai.FetchModelsResult.NetworkError -> {
                                                            fetchedModels = emptyList()
                                                            fetchModelsError = result.message
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    fetchModelsError = e.message
                                                } finally {
                                                    isFetchingModels = false
                                                }
                                            }
                                        },
                                        enabled = !isFetchingModels,
                                        shape = RoundedCornerShape(20.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                        modifier = Modifier.testTag("fetch_models_button")
                                    ) {
                                        if (isFetchingModels) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                            Spacer(modifier = Modifier.width(6.dp))
                                        } else {
                                            Icon(Icons.Default.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                        }
                                        Text("Fetch Models", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    }

                                    if (fetchedModels.isNotEmpty()) {
                                        Text("${fetchedModels.size} loaded", style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted))
                                    }
                                }

                                if (fetchModelsError != null) {
                                    Surface(
                                        color = WarmError.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Error: $fetchModelsError", color = WarmError, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                                    }
                                }

                                if (fetchModelsInfo != null) {
                                    Surface(
                                        color = WarmWarning.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().testTag("fetch_models_info")
                                    ) {
                                        Text(fetchModelsInfo!!, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                                    }
                                }

                                // Fetched Models Chips
                                if (fetchedModels.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Select active model:", style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted))
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            items(fetchedModels) { m ->
                                                val isSel = m == modelNameInput
                                                Surface(
                                                    shape = RoundedCornerShape(14.dp),
                                                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                                    border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent),
                                                    modifier = Modifier.clickable {
                                                        modelNameInput = m
                                                        isVisionModel = ProviderProfile.isVisionCapableModel(m)
                                                        scope.launch {
                                                            repository.settingsDao.setSetting(AppSettingsEntity("ai_model", m))
                                                            Toast.makeText(context, "Active: $m", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }.testTag("model_chip_$m")
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        if (isSel) {
                                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                        }
                                                        Text(m, fontSize = 12.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Active Model field with Vision Toggle
                                OutlinedTextField(
                                    value = modelNameInput,
                                    onValueChange = {
                                        modelNameInput = it
                                        isVisionModel = ProviderProfile.isVisionCapableModel(it)
                                        scope.launch {
                                            repository.settingsDao.setSetting(AppSettingsEntity("ai_model", it))
                                        }
                                    },
                                    label = { Text("Active Model (type manually or pick from fetched list)") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                        .androidx.compose.ui.focus.focusRequester(modelFocusRequester)
                                        .testTag("model_name_input_field")
                                )

                                // Vision Capability Switch
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Vision-Capable Model", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                        Text("Required for browser agent visual screenshots", style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted, fontSize = 11.sp))
                                    }
                                    Switch(
                                        checked = isVisionModel,
                                        onCheckedChange = { isVisionModel = it },
                                        colors = SwitchDefaults.colors(checkedThumbColor = WarmAccent, checkedTrackColor = WarmAccentContainer)
                                    )
                                }

                                // Test Connection Button
                                Button(
                                    onClick = {
                                        isTestingConnection = true
                                        testResult = null
                                        scope.launch {
                                            val p = repository.getActiveProvider()
                                            testResult = p.testConnection()
                                            isTestingConnection = false
                                        }
                                    },
                                    enabled = !isTestingConnection,
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth().testTag("test_connection_button")
                                ) {
                                    if (isTestingConnection) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text("Test Connection", fontWeight = FontWeight.Bold, color = Color.White)
                                }

                                if (testResult != null) {
                                    val res = testResult!!
                                    Surface(
                                        color = if (res.isSuccess) WarmSuccess.copy(alpha = 0.1f) else WarmError.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(res.message, color = if (res.isSuccess) WarmSuccess else WarmError, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // ==================== TAB 1: MEMORY & TRAINING FILES ====================
                if (selectedTopTab == 1) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Memory vs Training selector
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (activeMemoryTab == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                        border = BorderStroke(1.dp, if (activeMemoryTab == 0) MaterialTheme.colorScheme.primary else Color.Transparent),
                                        modifier = Modifier.weight(1f).clickable { activeMemoryTab = 0 }
                                    ) {
                                        Text(
                                            "Memory.md",
                                            fontWeight = if (activeMemoryTab == 0) FontWeight.Bold else FontWeight.Medium,
                                            color = if (activeMemoryTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp)
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (activeMemoryTab == 1) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                        border = BorderStroke(1.dp, if (activeMemoryTab == 1) MaterialTheme.colorScheme.primary else Color.Transparent),
                                        modifier = Modifier.weight(1f).clickable { activeMemoryTab = 1 }
                                    ) {
                                        Text(
                                            "Training.md",
                                            fontWeight = if (activeMemoryTab == 1) FontWeight.Bold else FontWeight.Medium,
                                            color = if (activeMemoryTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp)
                                        )
                                    }
                                }

                                val currentFileName = if (activeMemoryTab == 0) "Memory.md" else "Training.md"
                                val currentDocContent = if (activeMemoryTab == 0) memoryContent else trainingContent
                                val estimatedTokens = (currentDocContent.length / 4)

                                // Header info & auto-save badge
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        NovaBadge(text = memorySaveStatus, color = WarmSuccess, containerColor = WarmSuccess.copy(alpha = 0.12f))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("~$estimatedTokens tokens", fontSize = 11.sp, color = StoneMuted)
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { isMemoryPreviewMode = !isMemoryPreviewMode }, modifier = Modifier.size(32.dp)) {
                                            Icon(if (isMemoryPreviewMode) Icons.Default.Edit else Icons.Default.Visibility, contentDescription = "Preview", tint = WarmAccent, modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(onClick = {
                                            wm.backupMemoryFile(currentFileName)
                                            backupList = wm.listMemoryBackups(currentFileName)
                                            showBackupRestoreDialog = true
                                        }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.History, contentDescription = "Backups", tint = StoneMuted, modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(onClick = {
                                            wm.initializeDefaultStructure()
                                            memoryContent = try { wm.readFile("Memory/Memory.md") } catch (e: Exception) { "" }
                                            trainingContent = try { wm.readFile("Memory/Training.md") } catch (e: Exception) { "" }
                                            Toast.makeText(context, "Reset $currentFileName to default", Toast.LENGTH_SHORT).show()
                                        }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.RestartAlt, contentDescription = "Reset", tint = StoneMuted, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }

                                // Editor / Preview Box
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(300.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                        .padding(8.dp)
                                ) {
                                    if (isMemoryPreviewMode) {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            item { MarkdownText(markdown = currentDocContent) }
                                        }
                                    } else {
                                        OutlinedTextField(
                                            value = currentDocContent,
                                            onValueChange = { newText ->
                                                if (activeMemoryTab == 0) memoryContent = newText else trainingContent = newText
                                                memorySaveStatus = "Saving..."
                                                scope.launch {
                                                    delay(500)
                                                    wm.writeFile("Memory/$currentFileName", newText)
                                                    memorySaveStatus = "Saved"
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                        )
                                    }
                                }

                                // Propose additions simulator / trigger
                                OutlinedButton(
                                    onClick = {
                                        proposedAdditionText = if (activeMemoryTab == 0) {
                                            "- New verified fact: User preferred OpenAI-compatible endpoints with Groq acceleration."
                                        } else {
                                            "### Learned Workflow: Auto-search\n1. Navigate to target URL\n2. Fill query and extract summary."
                                        }
                                        showProposedDiffDialog = true
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = WarmAccent, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Simulate AI Propose Learning", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // ==================== TAB 2: SECRET VAULT ====================
                if (selectedTopTab == 2) {
                    item {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("ENCRYPTED KEYSTORE VAULT", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = WarmPurple))
                                Text("Hardware AES-256-GCM authenticated secrets. Scoped domain autofill.", fontSize = 11.sp, color = StoneMuted)
                            }
                            IconButton(onClick = { showAddSecretDialog = true }, modifier = Modifier.testTag("add_secret_button")) {
                                Surface(shape = CircleShape, color = WarmPurple.copy(alpha = 0.15f), modifier = Modifier.size(32.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Add, contentDescription = "Add Secret", tint = WarmPurple, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (secret in secrets) {
                                val isRevealed = revealedSecretId == secret.id
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Lock, contentDescription = null, tint = WarmPurple, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(secret.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                            Spacer(modifier = Modifier.weight(1f))
                                            IconButton(onClick = { scope.launch { repository.vaultManager.deleteSecret(secret.id) } }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = WarmError, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                        if (!secret.domain.isNullOrBlank()) Text("Domain: ${secret.domain}", fontSize = 11.sp, color = WarmAccent)
                                        Text("Mode: ${secret.permissionMode}", fontSize = 11.sp, color = StoneMuted)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (isRevealed) (revealedSecretValue ?: "••••••••") else "••••••••", fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                            TextButton(onClick = {
                                                if (isRevealed) {
                                                    revealedSecretId = null
                                                    revealedSecretValue = null
                                                } else {
                                                    scope.launch {
                                                        revealedSecretValue = repository.vaultManager.revealSecretValue(secret.id)
                                                        revealedSecretId = secret.id
                                                    }
                                                }
                                            }) {
                                                Text(if (isRevealed) "Hide" else "Reveal", fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                            if (secrets.isEmpty()) {
                                Text("No secrets saved. Tap '+' to create credentials.", color = StoneMuted, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // ==================== TAB 3: PRIVACY & STORAGE ====================
                if (selectedTopTab == 3) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Local Sandbox & Safety", fontWeight = FontWeight.Bold)
                                Text(
                                    "All workspace files, task logs, and SQLite database tables reside strictly on this device. Encrypted credentials never reach AI models. Prompt text only leaves your device to connect to your configured provider.",
                                    fontSize = 12.sp,
                                    color = StoneMuted
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                repository.clearBrowsingData()
                                                Toast.makeText(context, "Browsing history cleared", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Clear History", fontSize = 12.sp) }

                                    Button(
                                        onClick = { showResetConfirmDialog = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = WarmError),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Reset Data", fontSize = 12.sp, color = Color.White) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Backup & Restore Dialog
    if (showBackupRestoreDialog) {
        val currentFileName = if (activeMemoryTab == 0) "Memory.md" else "Training.md"
        AlertDialog(
            onDismissRequest = { showBackupRestoreDialog = false },
            title = { Text("Version History for $currentFileName") },
            text = {
                Column(modifier = Modifier.height(200.dp)) {
                    if (backupList.isEmpty()) {
                        Text("No backups found. Backups are automatically created before modifications.", fontSize = 12.sp, color = StoneMuted)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(backupList) { b ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        wm.restoreMemoryBackup(b, currentFileName)
                                        if (activeMemoryTab == 0) memoryContent = wm.readFile("Memory/Memory.md") else trainingContent = wm.readFile("Memory/Training.md")
                                        Toast.makeText(context, "Restored $b", Toast.LENGTH_SHORT).show()
                                        showBackupRestoreDialog = false
                                    }
                                ) {
                                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(b, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                        Text("Restore", fontSize = 11.sp, color = WarmAccent, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBackupRestoreDialog = false }) { Text("Close") } }
        )
    }

    // Proposed Additions Diff Dialog
    if (showProposedDiffDialog) {
        val currentFileName = if (activeMemoryTab == 0) "Memory.md" else "Training.md"
        AlertDialog(
            onDismissRequest = { showProposedDiffDialog = false },
            title = { Text("AI Proposes Addition to $currentFileName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The AI agent learned the following during a task. Would you like to accept and save this into $currentFileName?", fontSize = 12.sp, color = StoneMuted)
                    Surface(
                        color = WarmSuccess.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, WarmSuccess.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(proposedAdditionText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(10.dp), fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        wm.appendFile("Memory/$currentFileName", "\n\n$proposedAdditionText")
                        if (activeMemoryTab == 0) memoryContent = wm.readFile("Memory/Memory.md") else trainingContent = wm.readFile("Memory/Training.md")
                        showProposedDiffDialog = false
                        Toast.makeText(context, "Added to $currentFileName", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Accept & Save") }
            },
            dismissButton = {
                TextButton(onClick = { showProposedDiffDialog = false }) { Text("Reject") }
            }
        )
    }

    // Add Secret Dialog
    if (showAddSecretDialog) {
        AlertDialog(
            onDismissRequest = { showAddSecretDialog = false },
            title = { Text("Add Secret to Vault") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newSecretName,
                        onValueChange = { newSecretName = it },
                        label = { Text("Secret Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("secret_name_input")
                    )
                    OutlinedTextField(
                        value = newSecretUsername,
                        onValueChange = { newSecretUsername = it },
                        label = { Text("Username / Email (optional)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("secret_user_input")
                    )
                    OutlinedTextField(
                        value = newSecretDomain,
                        onValueChange = { newSecretDomain = it },
                        label = { Text("Domain (e.g. github.com)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("secret_domain_input")
                    )
                    OutlinedTextField(
                        value = newSecretValue,
                        onValueChange = { newSecretValue = it },
                        label = { Text("Secret Value") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("secret_value_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newSecretName.isNotBlank() && newSecretValue.isNotBlank()) {
                            scope.launch {
                                repository.vaultManager.createSecret(
                                    name = newSecretName.trim(),
                                    type = "api_key",
                                    plaintextValue = newSecretValue.trim(),
                                    username = newSecretUsername.trim().ifBlank { null },
                                    domain = newSecretDomain.trim().ifBlank { null },
                                    permissionMode = newSecretMode
                                )
                                showAddSecretDialog = false
                                newSecretName = ""
                                newSecretValue = ""
                                newSecretUsername = ""
                                newSecretDomain = ""
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WarmPurple)
                ) { Text("Save to Vault") }
            },
            dismissButton = {
                TextButton(onClick = { showAddSecretDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Reset Confirm Dialog
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Reset All Data?") },
            text = { Text("This will permanently clear all SQLite database tables, conversations, vault keys, and local files.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.clearAllData()
                            showResetConfirmDialog = false
                            Toast.makeText(context, "All data reset.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarmError),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Confirm Reset", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }
}
