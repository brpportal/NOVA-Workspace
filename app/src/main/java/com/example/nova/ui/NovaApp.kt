package com.example.nova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import com.example.nova.data.NovaRepository
import com.example.nova.ui.components.NovaBottomBar
import com.example.nova.ui.components.NovaScreen
import com.example.nova.ui.screens.BrowserScreen
import com.example.nova.ui.screens.ChatScreen
import com.example.nova.ui.screens.FilesScreen
import com.example.nova.ui.screens.SettingsScreen
import com.example.nova.ui.screens.TasksScreen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nova.ui.components.CommandPaletteDialog
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmAccent

import com.example.nova.ui.screens.ApprovalsScreen

import com.example.nova.ui.screens.GoalsScreen

@Composable
fun NovaApp(
    repository: NovaRepository,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = false,
    onToggleTheme: () -> Unit = {}
) {
    var currentScreen by remember { mutableStateOf(NovaScreen.CHAT) }
    var isCommandPaletteOpen by remember { mutableStateOf(false) }
    var showApprovalsScreen by remember { mutableStateOf(false) }

    if (showApprovalsScreen) {
        BackHandler {
            showApprovalsScreen = false
        }
    } else if (currentScreen != NovaScreen.CHAT) {
        BackHandler {
            currentScreen = NovaScreen.CHAT
        }
    }

    LaunchedEffect(Unit) {
        repository.initializeSeedData()
    }

    if (showApprovalsScreen) {
        ApprovalsScreen(
            repository = repository,
            onBack = { showApprovalsScreen = false }
        )
    } else {
        Scaffold(
            bottomBar = {
                NovaBottomBar(
                    currentScreen = currentScreen,
                    onScreenSelected = { currentScreen = it }
                )
            },
            modifier = modifier.fillMaxSize()
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Top minimal header bar with Command Palette and Theme switcher
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(10.dp)
                            ) {}
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Spark",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Command Palette Trigger
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .clickable { isCommandPaletteOpen = true }
                                    .testTag("open_command_palette")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Command Palette",
                                        tint = StoneMuted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Cmd+K", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StoneMuted)
                                }
                            }

                            // Theme Toggle
                            IconButton(
                                onClick = onToggleTheme,
                                modifier = Modifier
                                    .size(34.dp)
                                    .testTag("toggle_theme_button")
                            ) {
                                Icon(
                                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = "Toggle Dark/Light Mode",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (currentScreen) {
                        NovaScreen.CHAT -> ChatScreen(
                            repository = repository,
                            onNavigateToScreen = { currentScreen = it }
                        )
                        NovaScreen.GOALS -> GoalsScreen(
                            repository = repository,
                            onNavigateToTask = { currentScreen = NovaScreen.TASKS }
                        )
                        NovaScreen.BROWSER -> BrowserScreen(
                            repository = repository
                        )
                        NovaScreen.FILES -> FilesScreen(
                            repository = repository
                        )
                        NovaScreen.TASKS -> TasksScreen(
                            repository = repository,
                            onNavigateToApprovals = { showApprovalsScreen = true }
                        )
                        NovaScreen.SETTINGS -> SettingsScreen(
                            repository = repository,
                            onNavigateToApprovals = { showApprovalsScreen = true }
                        )
                    }
                }
            }
        }
    }

    CommandPaletteDialog(
        isOpen = isCommandPaletteOpen,
        onDismiss = { isCommandPaletteOpen = false },
        onNavigate = { currentScreen = it },
        onToggleTheme = onToggleTheme,
        isDarkTheme = isDarkTheme,
        onActionSelected = { /* handled by navigation */ }
    )
}

