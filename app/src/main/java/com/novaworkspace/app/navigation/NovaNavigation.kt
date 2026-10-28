package com.novaworkspace.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.ui.graphics.vector.ImageVector

enum class NovaDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    CHAT("chat", "Chat", Icons.Filled.ChatBubble),
    BROWSER("browser", "Browser", Icons.Filled.Language),
    FILES("files", "Files", Icons.Filled.Folder),
    TASKS("tasks", "Tasks", Icons.Filled.TaskAlt),
    SETTINGS("settings", "Settings", Icons.Filled.Settings)
}
