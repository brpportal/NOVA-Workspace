package com.example.nova.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.StoneMuted
import com.example.ui.theme.WarmAccent
import com.example.ui.theme.WarmAccentContainer
import com.example.ui.theme.WarmBorder

import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Terminal

enum class NovaScreen(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
) {
    CHAT("Chat", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat, "nav_chat"),
    GOALS("Goals", Icons.Filled.Flag, Icons.Outlined.Flag, "nav_goals"),
    BROWSER("Browser", Icons.Filled.Language, Icons.Outlined.Language, "nav_browser"),
    FILES("Files", Icons.Filled.Folder, Icons.Outlined.Folder, "nav_files"),
    TASKS("Tasks", Icons.Filled.TaskAlt, Icons.Outlined.TaskAlt, "nav_tasks"),
    LINUX("Terminal", Icons.Filled.Terminal, Icons.Outlined.Terminal, "nav_linux"),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
}

@Composable
fun NovaBottomBar(
    currentScreen: NovaScreen,
    onScreenSelected: (NovaScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = MaterialTheme.colorScheme.outline
    NavigationBar(
        modifier = modifier
            .testTag("nova_bottom_bar")
            .drawBehind {
                drawLine(
                    color = borderColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
            },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        windowInsets = WindowInsets.navigationBars,
        tonalElevation = 0.dp
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
                label = {
                    Text(
                        text = screen.title,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    )
                },
                modifier = Modifier.testTag(screen.tag),
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = WarmAccent,
                    selectedTextColor = WarmAccent,
                    indicatorColor = WarmAccentContainer,
                    unselectedIconColor = StoneMuted,
                    unselectedTextColor = StoneMuted
                )
            )
        }
    }
}
