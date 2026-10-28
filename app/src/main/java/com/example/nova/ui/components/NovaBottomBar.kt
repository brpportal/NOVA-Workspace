package com.example.nova.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.example.ui.theme.Charcoal700
import com.example.ui.theme.Charcoal800
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.SlateMuted
import com.example.ui.theme.TitaniumSilver

enum class NovaScreen(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
) {
    CHAT("Chat", Icons.Filled.Chat, Icons.Outlined.ChatBubbleOutline, "nav_chat"),
    BROWSER("Browser", Icons.Filled.Language, Icons.Outlined.Language, "nav_browser"),
    FILES("Files", Icons.Filled.Folder, Icons.Outlined.Folder, "nav_files"),
    TASKS("Tasks", Icons.Filled.TaskAlt, Icons.Outlined.TaskAlt, "nav_tasks"),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
}

@Composable
fun NovaBottomBar(
    currentScreen: NovaScreen,
    onScreenSelected: (NovaScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier.testTag("nova_bottom_bar"),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        windowInsets = WindowInsets.navigationBars
    ) {
        NovaScreen.entries.forEach { screen ->
            val isSelected = screen == currentScreen
            NavigationBarItem(
                selected = isSelected,
                onClick = { onScreenSelected(screen) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) screen.selectedIcon else screen.unselectedIcon,
                        contentDescription = screen.title
                    )
                },
                label = { Text(screen.title) },
                modifier = Modifier.testTag(screen.tag),
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NovaCyan,
                    selectedTextColor = NovaCyan,
                    indicatorColor = Charcoal700,
                    unselectedIconColor = SlateMuted,
                    unselectedTextColor = SlateMuted
                )
            )
        }
    }
}
