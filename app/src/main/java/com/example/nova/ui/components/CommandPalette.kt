package com.example.nova.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Task
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.StoneLight
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmAccent

data class PaletteAction(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onSelect: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPaletteDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onNavigate: (NovaScreen) -> Unit,
    onToggleTheme: () -> Unit,
    isDarkTheme: Boolean,
    onActionSelected: (String) -> Unit
) {
    if (!isOpen) return

    var query by remember { mutableStateOf("") }

    val allActions = listOf(
        PaletteAction("Go to AI Chat", "Open conversations and agent prompts", Icons.Default.Chat) {
            onNavigate(NovaScreen.CHAT)
            onDismiss()
        },
        PaletteAction("Go to Vision Browser", "Web navigation and agent automation", Icons.Default.Public) {
            onNavigate(NovaScreen.BROWSER)
            onDismiss()
        },
        PaletteAction("Go to Workspace Files", "Browse, edit, and manage documents", Icons.Default.Folder) {
            onNavigate(NovaScreen.FILES)
            onDismiss()
        },
        PaletteAction("Go to Background Tasks", "Monitor autonomous agent jobs", Icons.Default.Task) {
            onNavigate(NovaScreen.TASKS)
            onDismiss()
        },
        PaletteAction("Go to Settings & Vault", "Configure AI models, keys, and memory", Icons.Default.Settings) {
            onNavigate(NovaScreen.SETTINGS)
            onDismiss()
        },
        PaletteAction(
            title = if (isDarkTheme) "Switch to Light Mode" else "Switch to Dark Mode",
            subtitle = "Toggle app visual appearance",
            icon = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode
        ) {
            onToggleTheme()
            onDismiss()
        },
        PaletteAction("Edit Memory.md", "Personal facts & preferences", Icons.Default.Psychology) {
            onNavigate(NovaScreen.SETTINGS)
            onActionSelected("open_memory")
            onDismiss()
        },
        PaletteAction("Edit Training.md", "Workflows & taught procedures", Icons.Default.AutoAwesome) {
            onNavigate(NovaScreen.SETTINGS)
            onActionSelected("open_training")
            onDismiss()
        }
    )

    val filtered = if (query.isBlank()) allActions else {
        allActions.filter {
            it.title.contains(query, ignoreCase = true) ||
            it.subtitle.contains(query, ignoreCase = true)
        }
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("command_palette_modal")
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Header & Search Input
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = WarmAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search actions or jump to tab...", fontSize = 14.sp, color = StoneLight) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("command_palette_search_input")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filtered) { action ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { action.onSelect() }
                                .padding(vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = action.icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = action.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                    Text(
                                        text = action.subtitle,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = StoneMuted
                                        )
                                    )
                                }
                            }
                        }
                    }

                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No commands matching \"$query\"", color = StoneMuted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
