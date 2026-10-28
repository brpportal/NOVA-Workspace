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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nova.ai.AIProviderFactory
import com.example.nova.ai.ConnectionResult
import com.example.nova.data.AppSettingsEntity
import com.example.nova.data.MemoryEntryEntity
import com.example.nova.data.NovaRepository
import com.example.nova.data.SecretMetadataEntity
import com.example.nova.security.PermissionMode
import com.example.nova.security.SecretRedactor
import com.example.ui.theme.Charcoal600
import com.example.ui.theme.Charcoal700
import com.example.ui.theme.Charcoal800
import com.example.ui.theme.Charcoal900
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.PurpleAccent
import com.example.ui.theme.SafetyOrange
import com.example.ui.theme.SafetyRed
import com.example.ui.theme.SlateMuted
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TitaniumSilver
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: NovaRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val secrets by repository.secrets.collectAsState(initial = emptyList())
    val memories by repository.memories.collectAsState(initial = emptyList())
    val settingsList by repository.settings.collectAsState(initial = emptyList())

    // Provider state
    val providerOptions = listOf(
        Pair("gemini", "Google Gemini"),
        Pair("openai_compatible", "OpenAI / Compatible"),
        Pair("anthropic", "Anthropic Claude"),
        Pair("fake", "Offline Autonomous Engine (No Key Needed)")
    )
    var selectedProviderType by remember { mutableStateOf("gemini") }
    var apiKeyInput by remember { mutableStateOf("") }
    var modelNameInput by remember { mutableStateOf("gemini-2.5-flash") }
    var endpointInput by remember { mutableStateOf("") }
    var isApiKeyRevealed by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectionResult?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }

    // Secret Vault dialog state
    var showAddSecretDialog by remember { mutableStateOf(false) }
    var newSecretName by remember { mutableStateOf("") }
    var newSecretType by remember { mutableStateOf("WEBSITE_LOGIN") }
    var newSecretUsername by remember { mutableStateOf("") }
    var newSecretDomain by remember { mutableStateOf("") }
    var newSecretValue by remember { mutableStateOf("") }
    var newSecretMode by remember { mutableStateOf(PermissionMode.ASK_EVERY_TIME) }
    var revealedSecretId by remember { mutableStateOf<String?>(null) }
    var revealedSecretValue by remember { mutableStateOf<String?>(null) }

    // Memory dialog state
    var showAddMemoryDialog by remember { mutableStateOf(false) }
    var newMemoryKey by remember { mutableStateOf("") }
    var newMemoryContent by remember { mutableStateOf("") }
    var newMemoryCategory by remember { mutableStateOf("user_preference") }

    // App reset dialog
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    // Sync settings on load
    LaunchedEffect(settingsList) {
        val type = settingsList.find { it.key == "ai_provider_type" }?.value ?: "gemini"
        selectedProviderType = type
        apiKeyInput = settingsList.find { it.key == "ai_api_key" }?.value ?: ""
        modelNameInput = settingsList.find { it.key == "ai_model" }?.value ?: if (type == "gemini") "gemini-2.5-flash" else "gpt-4o-mini"
        endpointInput = settingsList.find { it.key == "ai_endpoint" }?.value ?: ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Vault", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Charcoal900)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- SECTION 1: AI PROVIDER ---
            item {
                Text(
                    text = "AI PROVIDER CONFIGURATION",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = NovaCyan)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Charcoal800),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Charcoal700, RoundedCornerShape(10.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Select Engine",
                            style = MaterialTheme.typography.bodySmall.copy(color = SlateMuted)
                        )

                        // Provider selector buttons
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            providerOptions.take(2).forEach { (id, name) ->
                                val isSel = selectedProviderType == id
                                Button(
                                    onClick = {
                                        selectedProviderType = id
                                        modelNameInput = if (id == "gemini") "gemini-2.5-flash" else "gpt-4o-mini"
                                        scope.launch {
                                            repository.settingsDao.setSetting(AppSettingsEntity("ai_provider_type", id))
                                            repository.settingsDao.setSetting(AppSettingsEntity("ai_model", modelNameInput))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSel) NovaCyan else Charcoal700,
                                        contentColor = if (isSel) Charcoal900 else TitaniumSilver
                                    ),
                                    modifier = Modifier.weight(1f).height(36.dp).testTag("select_provider_$id")
                                ) {
                                    Text(name.substringBefore(" "), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            providerOptions.drop(2).forEach { (id, name) ->
                                val isSel = selectedProviderType == id
                                Button(
                                    onClick = {
                                        selectedProviderType = id
                                        modelNameInput = if (id == "anthropic") "claude-3-5-sonnet-20241022" else "local"
                                        scope.launch {
                                            repository.settingsDao.setSetting(AppSettingsEntity("ai_provider_type", id))
                                            repository.settingsDao.setSetting(AppSettingsEntity("ai_model", modelNameInput))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSel) NovaCyan else Charcoal700,
                                        contentColor = if (isSel) Charcoal900 else TitaniumSilver
                                    ),
                                    modifier = Modifier.weight(1f).height(36.dp).testTag("select_provider_$id")
                                ) {
                                    Text(name.substringBefore(" "), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // API Key Input
                        if (selectedProviderType != "fake") {
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = {
                                    apiKeyInput = it
                                    scope.launch {
                                        repository.settingsDao.setSetting(AppSettingsEntity("ai_api_key", it))
                                    }
                                },
                                label = { Text("API Key (Stored locally in sandbox)") },
                                visualTransformation = if (isApiKeyRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { isApiKeyRevealed = !isApiKeyRevealed }, modifier = Modifier.testTag("toggle_api_key_visibility")) {
                                        Icon(
                                            imageVector = if (isApiKeyRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = "Toggle visibility",
                                            tint = SlateMuted
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("api_key_input_field"),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Charcoal700,
                                    unfocusedContainerColor = Charcoal700,
                                    focusedIndicatorColor = NovaCyan,
                                    unfocusedIndicatorColor = Charcoal600
                                )
                            )
                        }

                        // Model Name
                        OutlinedTextField(
                            value = modelNameInput,
                            onValueChange = {
                                modelNameInput = it
                                scope.launch {
                                    repository.settingsDao.setSetting(AppSettingsEntity("ai_model", it))
                                }
                            },
                            label = { Text("Model Identifier") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("model_name_input_field"),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Charcoal700,
                                unfocusedContainerColor = Charcoal700,
                                focusedIndicatorColor = NovaCyan,
                                unfocusedIndicatorColor = Charcoal600
                            )
                        )

                        // Endpoint for custom/OpenAI compatible
                        if (selectedProviderType == "openai_compatible") {
                            OutlinedTextField(
                                value = endpointInput,
                                onValueChange = {
                                    endpointInput = it
                                    scope.launch {
                                        repository.settingsDao.setSetting(AppSettingsEntity("ai_endpoint", it))
                                    }
                                },
                                label = { Text("Endpoint URL (e.g. https://api.openai.com/v1/chat/completions)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("endpoint_input_field"),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Charcoal700,
                                    unfocusedContainerColor = Charcoal700,
                                    focusedIndicatorColor = NovaCyan,
                                    unfocusedIndicatorColor = Charcoal600
                                )
                            )
                        }

                        // Test Connection Button
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                                colors = ButtonDefaults.buttonColors(containerColor = NovaCyan),
                                modifier = Modifier.testTag("test_connection_button")
                            ) {
                                if (isTestingConnection) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Charcoal900, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text("Test Connection", color = Charcoal900, fontWeight = FontWeight.Bold)
                            }
                        }

                        // Test Result Banner
                        if (testResult != null) {
                            val res = testResult!!
                            Surface(
                                color = if (res.isSuccess) SuccessGreen.copy(alpha = 0.15f) else SafetyRed.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Text(
                                    text = res.message,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = if (res.isSuccess) SuccessGreen else SafetyRed
                                    ),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // --- SECTION 2: ENCRYPTED SECRET VAULT ---
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ENCRYPTED SECRET VAULT",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = PurpleAccent)
                        )
                        Text(
                            text = "Hardware KeyStore-backed AES-256-GCM. Scoped autofill only.",
                            style = MaterialTheme.typography.labelSmall.copy(color = SlateMuted)
                        )
                    }
                    IconButton(
                        onClick = { showAddSecretDialog = true },
                        modifier = Modifier.testTag("add_secret_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Secret", tint = PurpleAccent)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (secret in secrets) {
                        val isRevealed = revealedSecretId == secret.id
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Charcoal800),
                            modifier = Modifier.fillMaxWidth().border(1.dp, Charcoal700, RoundedCornerShape(8.dp))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = PurpleAccent, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = secret.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, color = TitaniumSilver)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(color = Charcoal700, shape = RoundedCornerShape(4.dp)) {
                                        Text(
                                            text = secret.type,
                                            style = MaterialTheme.typography.labelSmall.copy(color = SlateMuted, fontSize = 9.sp),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = { scope.launch { repository.vaultManager.deleteSecret(secret.id) } },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = SafetyRed, modifier = Modifier.size(14.dp))
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                if (!secret.username.isNullOrBlank()) {
                                    Text("User: ${secret.username}", style = MaterialTheme.typography.labelSmall.copy(color = SlateMuted))
                                }
                                if (!secret.domain.isNullOrBlank()) {
                                    Text("Domain: ${secret.domain}", style = MaterialTheme.typography.labelSmall.copy(color = NovaCyan))
                                }
                                Text("Mode: ${secret.permissionMode}", style = MaterialTheme.typography.labelSmall.copy(color = SlateMuted))

                                Spacer(modifier = Modifier.height(6.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (isRevealed) (revealedSecretValue ?: "••••••••") else "••••••••",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall.copy(color = TitaniumSilver),
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = {
                                            if (isRevealed) {
                                                revealedSecretId = null
                                                revealedSecretValue = null
                                            } else {
                                                scope.launch {
                                                    revealedSecretValue = repository.vaultManager.revealSecretValue(secret.id)
                                                    revealedSecretId = secret.id
                                                }
                                            }
                                        }
                                    ) {
                                        Text(if (isRevealed) "Hide" else "Reveal", fontSize = 11.sp, color = NovaCyan)
                                    }
                                }
                            }
                        }
                    }

                    if (secrets.isEmpty()) {
                        Text("No secrets in vault. Tap '+' to create credentials for automated login.", style = MaterialTheme.typography.bodySmall.copy(color = SlateMuted))
                    }
                }
            }

            // --- SECTION 3: PERSISTENT MEMORIES ---
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "USER-INSPECTABLE MEMORY",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = NovaCyan)
                        )
                        Text(
                            text = "Explicit preferences and context. Browsing data is never auto-saved.",
                            style = MaterialTheme.typography.labelSmall.copy(color = SlateMuted)
                        )
                    }
                    IconButton(
                        onClick = { showAddMemoryDialog = true },
                        modifier = Modifier.testTag("add_memory_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Memory", tint = NovaCyan)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (mem in memories) {
                        Surface(
                            color = Charcoal800,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().border(1.dp, Charcoal700, RoundedCornerShape(8.dp))
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mem.key, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = TitaniumSilver))
                                    Text(mem.content, style = MaterialTheme.typography.bodySmall.copy(color = SlateMuted))
                                }
                                IconButton(
                                    onClick = { scope.launch { repository.memoryDao.deleteMemoryById(mem.id) } },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete memory", tint = SlateMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            // --- SECTION 4: PRIVACY & DATA CLEARING ---
            item {
                Text(
                    text = "PRIVACY & STORAGE MANAGEMENT",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = SlateMuted)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Charcoal800),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Charcoal700, RoundedCornerShape(10.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Local Sandboxing Disclosure:\nAll workspace documents, research files, task records, and SQLite database tables reside strictly on this device. Encrypted vault secrets are guarded by the Android KeyStore. Only message prompts intended for AI processing are transmitted to your configured provider endpoint.",
                            style = MaterialTheme.typography.bodySmall.copy(color = SlateMuted, lineHeight = 18.sp)
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        repository.clearBrowsingData()
                                        Toast.makeText(context, "Browsing history cleared", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f).testTag("clear_history_button")
                            ) {
                                Text("Clear History", fontSize = 12.sp, color = TitaniumSilver)
                            }

                            Button(
                                onClick = { showResetConfirmDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = SafetyRed),
                                modifier = Modifier.weight(1f).testTag("reset_app_button")
                            ) {
                                Text("Reset All Data", fontSize = 12.sp, color = Charcoal900, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
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
                        label = { Text("Name (e.g. GitHub Login)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("secret_name_input")
                    )
                    OutlinedTextField(
                        value = newSecretUsername,
                        onValueChange = { newSecretUsername = it },
                        label = { Text("Username / Email (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("secret_user_input")
                    )
                    OutlinedTextField(
                        value = newSecretDomain,
                        onValueChange = { newSecretDomain = it },
                        label = { Text("Allowed Domain (e.g. github.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("secret_domain_input")
                    )
                    OutlinedTextField(
                        value = newSecretValue,
                        onValueChange = { newSecretValue = it },
                        label = { Text("Secret Value (Password / Token)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("secret_value_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newSecretName.trim()
                        val value = newSecretValue.trim()
                        if (name.isNotBlank() && value.isNotBlank()) {
                            scope.launch {
                                repository.vaultManager.createSecret(
                                    name = name,
                                    type = newSecretType,
                                    plaintextValue = value,
                                    username = newSecretUsername.ifBlank { null },
                                    domain = newSecretDomain.ifBlank { null },
                                    permissionMode = newSecretMode
                                )
                                showAddSecretDialog = false
                                newSecretName = ""
                                newSecretValue = ""
                                newSecretUsername = ""
                                newSecretDomain = ""
                                Toast.makeText(context, "Secret saved to vault", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.testTag("save_secret_button")
                ) { Text("Save to Vault") }
            },
            dismissButton = {
                TextButton(onClick = { showAddSecretDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Add Memory Dialog
    if (showAddMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddMemoryDialog = false },
            title = { Text("Add Context Memory") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newMemoryKey,
                        onValueChange = { newMemoryKey = it },
                        label = { Text("Memory Key (e.g. coding_preference)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("memory_key_input")
                    )
                    OutlinedTextField(
                        value = newMemoryContent,
                        onValueChange = { newMemoryContent = it },
                        label = { Text("Content") },
                        modifier = Modifier.fillMaxWidth().testTag("memory_content_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val k = newMemoryKey.trim()
                        val c = newMemoryContent.trim()
                        if (k.isNotBlank() && c.isNotBlank()) {
                            scope.launch {
                                repository.memoryDao.insertMemory(
                                    MemoryEntryEntity(
                                        id = UUID.randomUUID().toString(),
                                        key = k,
                                        content = c,
                                        category = newMemoryCategory
                                    )
                                )
                                showAddMemoryDialog = false
                                newMemoryKey = ""
                                newMemoryContent = ""
                            }
                        }
                    },
                    modifier = Modifier.testTag("save_memory_button")
                ) { Text("Save Memory") }
            },
            dismissButton = {
                TextButton(onClick = { showAddMemoryDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Reset Confirmation Dialog
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = SafetyRed) },
            title = { Text("Reset NOVA Workspace", color = SafetyRed) },
            text = { Text("This will delete all conversations, task histories, and browsing history. Vault credentials will remain intact in KeyStore.", color = TitaniumSilver) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.clearAllData()
                            showResetConfirmDialog = false
                            Toast.makeText(context, "Workspace reset completed.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SafetyRed),
                    modifier = Modifier.testTag("confirm_reset_button")
                ) { Text("Confirm Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }
}
