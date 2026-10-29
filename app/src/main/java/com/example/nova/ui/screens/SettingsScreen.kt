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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.ui.text.input.KeyboardType
import com.example.nova.ai.ApiKeySanitizer
import com.example.nova.ai.ConnectionResult
import com.example.nova.ai.ModelInfo
import com.example.nova.ai.OpenAICompatibleProvider
import com.example.nova.ai.ProviderErrors
import com.example.nova.ai.ProviderProfile
import com.example.nova.data.AppSettingsEntity
import com.example.nova.data.MemoryEntryEntity
import com.example.nova.data.NovaRepository
import com.example.nova.security.PermissionMode
import com.example.nova.trained.CollectorDefaults
import com.example.nova.trained.FacebookExtractionMode
import com.example.nova.trained.FacebookPostLinks
import com.example.nova.trained.ScreenshotsCollection
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

/** Derives a provider profile id from a stored provider type + base URL (used when no profile was persisted yet). */
fun deriveProfile(providerType: String, baseUrl: String): String {
    val host = try {
        java.net.URI(baseUrl).host?.lowercase() ?: ""
    } catch (e: Exception) {
        ""
    }
    return when {
        providerType == "anthropic" -> "anthropic"
        providerType == "gemini" -> "gemini"
        providerType == "fake" || providerType == "offline" || providerType == "mock" -> "fake"
        host.contains("openrouter.ai") -> "openrouter"
        host.contains("huggingface.co") -> "huggingface"
        host.contains("api.openai.com") -> "openai_compatible"
        baseUrl.isBlank() || baseUrl == "https://api.openai.com/v1" -> "openai_compatible"
        else -> "custom"
    }
}

/**
 * Returns a warning when the selected profile family and the Base URL host
 * belong to different API families (Anthropic vs OpenAI-compatible) — a
 * combination that can only fail.
 */
fun providerMismatchWarning(providerType: String, baseUrl: String): String? {
    val host = try {
        java.net.URI(OpenAICompatibleProvider.normalizeBaseUrl(baseUrl)).host?.lowercase() ?: ""
    } catch (e: Exception) {
        ""
    }
    val openAiStyleHosts = setOf("openrouter.ai", "router.huggingface.co", "api.openai.com")
    return when {
        providerType == "anthropic" && host in openAiStyleHosts ->
            "Anthropic Claude is selected, but the Base URL points to an OpenAI-compatible service ($host). Requests will fail — select the matching provider profile or change the Base URL."
        providerType == "openai_compatible" && host == "api.anthropic.com" ->
            "An OpenAI-compatible profile is selected, but the Base URL points to api.anthropic.com. Select the Anthropic Claude profile instead."
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: NovaRepository,
    modifier: Modifier = Modifier,
    onNavigateToApprovals: () -> Unit = {}
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
    var hasFetchedModels by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var fetchModelsError by remember { mutableStateOf<String?>(null) }
    var modelSearchQuery by remember { mutableStateOf("") }

    // Provider profile (Phase 3): a named preset on top of the raw provider
    // type. Persisted under "ai_provider_profile"; derived from the saved
    // type/endpoint when no profile was stored yet.
    var selectedProfile by remember { mutableStateOf("openai_compatible") }
    var keySanitizeNote by remember { mutableStateOf<String?>(null) }

    // Sanitizes the key currently in the field; shows the one-line note and
    // persists the cleaned key when anything had to be stripped.
    val sanitizeKeyField: () -> String = {
        val result = ApiKeySanitizer.sanitize(apiKeyInput)
        if (result.removedCount > 0) {
            apiKeyInput = result.cleaned
            keySanitizeNote = "Removed ${result.removedCount} invalid characters from the key"
            scope.launch {
                repository.settingsDao.setSetting(AppSettingsEntity("ai_api_key", result.cleaned))
            }
        }
        result.cleaned
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
    var showAddMemoryDialog by remember { mutableStateOf(false) }
    var newMemoryTextInput by remember { mutableStateOf("") }

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

    // --- 4. COLLECTORS DEFAULTS STATE (Round B) ---
    // Persisted via CollectorDefaults keys in the same settings store;
    // both Trained Tasks cards initialize from these values.
    var collectorMethod by remember { mutableStateOf(FacebookExtractionMode.HYBRID) }
    var collectorLinksText by remember {
        mutableStateOf(FacebookPostLinks.DEFAULT_POSTS_PER_PROFILE.toString())
    }
    var collectorShotsText by remember {
        mutableStateOf(ScreenshotsCollection.DEFAULT_POSTS_PER_PROFILE.toString())
    }

    // Loaded ONCE (a settings emission must never clobber a field the
    // user is mid-way through typing).
    var collectorDefaultsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!collectorDefaultsLoaded) {
            collectorDefaultsLoaded = true
            val defaults = repository.loadCollectorDefaults()
            collectorMethod = defaults.method
            collectorLinksText = defaults.linksPerSource.toString()
            collectorShotsText = defaults.screenshotPostsPerProfile.toString()
        }
    }

    // Each control persists ONLY its own key (clamped), and only when
    // the field parses — clearing a field to retype never writes a
    // fallback over the stored value.
    val persistCollectorMethod: (FacebookExtractionMode) -> Unit = { method ->
        collectorMethod = method
        scope.launch {
            repository.settingsDao.setSetting(
                AppSettingsEntity(CollectorDefaults.KEY_METHOD, method.toolArg())
            )
        }
    }
    val persistCollectorLinks: (String) -> Unit = { text ->
        collectorLinksText = text
        text.trim().toIntOrNull()?.let { parsed ->
            scope.launch {
                repository.settingsDao.setSetting(
                    AppSettingsEntity(
                        CollectorDefaults.KEY_LINKS_PER_SOURCE,
                        FacebookPostLinks.clampPostsPerProfile(parsed).toString()
                    )
                )
            }
        }
    }
    val persistCollectorShots: (String) -> Unit = { text ->
        collectorShotsText = text
        text.trim().toIntOrNull()?.let { parsed ->
            scope.launch {
                repository.settingsDao.setSetting(
                    AppSettingsEntity(
                        CollectorDefaults.KEY_SCREENSHOT_POSTS,
                        ScreenshotsCollection.clampPostsPerProfile(parsed).toString()
                    )
                )
            }
        }
    }

    // Load initial settings
    LaunchedEffect(settingsList) {
        selectedProviderType = settingsList.find { it.key == "ai_provider_type" }?.value ?: "openai_compatible"
        apiKeyInput = settingsList.find { it.key == "ai_api_key" }?.value ?: ""
        modelNameInput = settingsList.find { it.key == "ai_model" }?.value ?: "gpt-4o-mini"
        baseUrlInput = settingsList.find { it.key == "ai_endpoint" }?.value ?: "https://api.openai.com/v1"
        isVisionModel = ProviderProfile.isVisionCapableModel(modelNameInput)
        selectedProfile = settingsList.find { it.key == "ai_provider_profile" }?.value
            ?: deriveProfile(selectedProviderType, baseUrlInput)
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
                actions = {
                    TextButton(onClick = onNavigateToApprovals, modifier = Modifier.testTag("settings_approvals_history_button")) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Approvals", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                Tab(
                    selected = selectedTopTab == 4,
                    onClick = { selectedTopTab = 4 },
                    text = { Text("Collectors", fontWeight = if (selectedTopTab == 4) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp) }
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
                                    text = "Provider Profile",
                                    style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted, fontWeight = FontWeight.Bold)
                                )

                                // Provider profile selector (Phase 3): each profile maps to a
                                // provider type and auto-fills its Base URL on selection.
                                // "Custom" keeps the Base URL freely editable below.
                                val profiles = listOf(
                                    Triple("openrouter", "OpenRouter", "openai_compatible"),
                                    Triple("huggingface", "Hugging Face", "openai_compatible"),
                                    Triple("openai_compatible", "OpenAI-Compatible", "openai_compatible"),
                                    Triple("anthropic", "Anthropic Claude", "anthropic"),
                                    Triple("gemini", "Google Gemini", "gemini"),
                                    Triple("custom", "Custom", "openai_compatible"),
                                    Triple("fake", "Offline Demo", "fake")
                                )

                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(profiles) { (profileId, name, type) ->
                                        val isSel = selectedProfile == profileId
                                        Surface(
                                            shape = RoundedCornerShape(18.dp),
                                            color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent),
                                            modifier = Modifier.clickable {
                                                selectedProfile = profileId
                                                selectedProviderType = type
                                                when (profileId) {
                                                    "openrouter" -> baseUrlInput = "https://openrouter.ai/api/v1"
                                                    "huggingface" -> baseUrlInput = "https://router.huggingface.co/v1"
                                                    "openai_compatible" -> {
                                                        baseUrlInput = "https://api.openai.com/v1"
                                                        modelNameInput = "gpt-4o-mini"
                                                    }
                                                    "gemini" -> modelNameInput = "gemini-2.5-flash"
                                                    // "custom": keep current Base URL (free edit)
                                                    // "anthropic" / "fake": Base URL not used by the adapter
                                                }
                                                scope.launch {
                                                    repository.settingsDao.setSetting(AppSettingsEntity("ai_provider_profile", profileId))
                                                    repository.settingsDao.setSetting(AppSettingsEntity("ai_provider_type", type))
                                                    repository.settingsDao.setSetting(AppSettingsEntity("ai_model", modelNameInput))
                                                    repository.settingsDao.setSetting(AppSettingsEntity("ai_endpoint", baseUrlInput))
                                                }
                                            }.testTag("provider_profile_$profileId")
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

                                // Profile/endpoint family mismatch warning
                                val mismatchWarning = providerMismatchWarning(selectedProviderType, baseUrlInput)
                                if (mismatchWarning != null) {
                                    Surface(
                                        color = WarmWarning.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().testTag("provider_mismatch_warning")
                                    ) {
                                        Text(
                                            text = "⚠️ $mismatchWarning",
                                            color = WarmWarning,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }

                                // API Key Input (sanitized on change: invisible and
                                // non-ASCII characters from a paste are stripped so
                                // they can never break the Authorization header)
                                OutlinedTextField(
                                    value = apiKeyInput,
                                    onValueChange = { raw ->
                                        val result = ApiKeySanitizer.sanitize(raw)
                                        apiKeyInput = result.cleaned
                                        keySanitizeNote = if (result.removedCount > 0) {
                                            "Removed ${result.removedCount} invalid characters from the key"
                                        } else {
                                            null
                                        }
                                        scope.launch {
                                            repository.settingsDao.setSetting(AppSettingsEntity("ai_api_key", result.cleaned))
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

                                if (keySanitizeNote != null) {
                                    Text(
                                        text = keySanitizeNote!!,
                                        color = WarmWarning,
                                        fontSize = 11.sp,
                                        modifier = Modifier.testTag("api_key_sanitize_note")
                                    )
                                }

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
                                            hasFetchedModels = false
                                            val cleanKey = sanitizeKeyField()
                                            scope.launch {
                                                try {
                                                    val models = OpenAICompatibleProvider.fetchModels(baseUrlInput, cleanKey)
                                                    fetchedModels = models
                                                    hasFetchedModels = true
                                                    Toast.makeText(context, "Loaded ${models.size} models", Toast.LENGTH_SHORT).show()
                                                } catch (e: Exception) {
                                                    fetchModelsError = ProviderErrors.userMessage(e)
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

                                if (hasFetchedModels && fetchedModels.isEmpty() && fetchModelsError == null) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().testTag("zero_models_info_card")
                                    ) {
                                        Text(
                                            "Provider returned 0 models. You can type a model name manually below.",
                                            style = MaterialTheme.typography.bodySmall.copy(color = StoneMuted),
                                            modifier = Modifier.padding(10.dp)
                                        )
                                    }
                                }

                                // Fetched Models — selectable list (tap sets the Active
                                // Model). The FREE tag appears only when the provider's
                                // own data marks the model free (see ModelInfo).
                                if (fetchedModels.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Select active model:", style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted))
                                        LazyColumn(
                                            modifier = Modifier.fillMaxWidth().height(240.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            items(fetchedModels) { m ->
                                                val isSel = m.id == modelNameInput
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                                    border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.primary else Color.Transparent),
                                                    modifier = Modifier.fillMaxWidth().clickable {
                                                        modelNameInput = m.id
                                                        isVisionModel = ProviderProfile.isVisionCapableModel(m.id)
                                                        scope.launch {
                                                            repository.settingsDao.setSetting(AppSettingsEntity("ai_model", m.id))
                                                            Toast.makeText(context, "Active: ${m.id}", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }.testTag("model_chip_${m.id}")
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        if (isSel) {
                                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                        }
                                                        Text(
                                                            m.id,
                                                            fontSize = 12.sp,
                                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        if (m.isFree) {
                                                            Surface(
                                                                shape = RoundedCornerShape(6.dp),
                                                                color = WarmSuccess.copy(alpha = 0.15f),
                                                                border = BorderStroke(0.5.dp, WarmSuccess.copy(alpha = 0.5f))
                                                            ) {
                                                                Text(
                                                                    "FREE",
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = WarmSuccess,
                                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                        }
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
                                    label = { Text("Active Model") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("model_name_input_field")
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
                                        sanitizeKeyField()
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

                                // Add memory & Propose learning action row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            newMemoryTextInput = ""
                                            showAddMemoryDialog = true
                                        },
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.weight(1f).testTag("add_memory_button")
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add memory", fontSize = 12.sp)
                                    }

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
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = WarmAccent, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("AI Propose", fontSize = 12.sp)
                                    }
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

                // ==================== TAB 4: COLLECTORS ====================
                if (selectedTopTab == 4) {
                    item {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "🎯 Core Features — Defaults",
                                    style = MaterialTheme.typography.labelSmall.copy(color = StoneMuted, fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    "These defaults pre-fill the Links Collection and Screenshots Collection cards on the Tasks screen. Every run can still override them on the card itself.",
                                    fontSize = 12.sp,
                                    color = StoneMuted
                                )

                                Text(
                                    text = "Default extraction method",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = collectorMethod == FacebookExtractionMode.HYBRID,
                                        onClick = { persistCollectorMethod(FacebookExtractionMode.HYBRID) },
                                        label = { Text("Hybrid") }
                                    )
                                    FilterChip(
                                        selected = collectorMethod == FacebookExtractionMode.M1_ONLY,
                                        onClick = { persistCollectorMethod(FacebookExtractionMode.M1_ONLY) },
                                        label = { Text("M1 only") }
                                    )
                                    FilterChip(
                                        selected = collectorMethod == FacebookExtractionMode.M3_ONLY,
                                        onClick = { persistCollectorMethod(FacebookExtractionMode.M3_ONLY) },
                                        label = { Text("M3 only") }
                                    )
                                }
                                Text(
                                    text = when (collectorMethod) {
                                        FacebookExtractionMode.HYBRID -> "Hybrid (M1+M3 ladder) — visible links first, structured data mining as backup."
                                        FacebookExtractionMode.M1_ONLY -> "M1 only (visible links) — rendered page links; no structured-data mining."
                                        FacebookExtractionMode.M3_ONLY -> "M3 only (structured data) — page-source/JSON mining only."
                                    },
                                    fontSize = 12.sp,
                                    color = StoneMuted
                                )

                                Text(
                                    text = "Default post links per source — Links Collection (1–100)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                OutlinedTextField(
                                    value = collectorLinksText,
                                    onValueChange = persistCollectorLinks,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                val linksParsed = collectorLinksText.trim().toIntOrNull()
                                val linksClamped = linksParsed?.let { FacebookPostLinks.clampPostsPerProfile(it) }
                                if (linksParsed != null && linksClamped != null && linksParsed != linksClamped) {
                                    Text(
                                        text = "Will be clamped to $linksClamped (allowed range is 1–100).",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                Text(
                                    text = "Default post screenshots per profile — Screenshots Collection (1–10)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                OutlinedTextField(
                                    value = collectorShotsText,
                                    onValueChange = persistCollectorShots,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                val shotsParsed = collectorShotsText.trim().toIntOrNull()
                                val shotsClamped = shotsParsed?.let { ScreenshotsCollection.clampPostsPerProfile(it) }
                                if (shotsParsed != null && shotsClamped != null && shotsParsed != shotsClamped) {
                                    Text(
                                        text = "Will be clamped to $shotsClamped (allowed range is 1–10).",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
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

    // Add Memory Dialog
    if (showAddMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddMemoryDialog = false },
            title = { Text("Add Memory Entry") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Appends a new dated fact or preference entry to Memory.md", fontSize = 12.sp, color = StoneMuted)
                    OutlinedTextField(
                        value = newMemoryTextInput,
                        onValueChange = { newMemoryTextInput = it },
                        label = { Text("Memory text") },
                        placeholder = { Text("e.g. User prefers concise bullet summaries.") },
                        modifier = Modifier.fillMaxWidth().testTag("add_memory_input"),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val entry = newMemoryTextInput.trim()
                        if (entry.isNotBlank()) {
                            scope.launch {
                                val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                                val formatted = "- [$dateStr] $entry"
                                wm.appendFile("Memory/Memory.md", "\n$formatted")
                                repository.memoryDao.insertMemory(
                                    MemoryEntryEntity(
                                        key = "manual_memory_${System.currentTimeMillis()}",
                                        content = entry,
                                        category = "user_preference"
                                    )
                                )
                                memoryContent = try { wm.readFile("Memory/Memory.md") } catch (e: Exception) { "" }
                                showAddMemoryDialog = false
                                Toast.makeText(context, "Memory added", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Save Memory") }
            },
            dismissButton = {
                TextButton(onClick = { showAddMemoryDialog = false }) { Text("Cancel") }
            }
        )
    }
}
