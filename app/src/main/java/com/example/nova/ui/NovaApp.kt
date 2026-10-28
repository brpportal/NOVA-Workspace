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
import com.example.nova.data.NovaRepository
import com.example.nova.ui.components.NovaBottomBar
import com.example.nova.ui.components.NovaScreen
import com.example.nova.ui.screens.BrowserScreen
import com.example.nova.ui.screens.ChatScreen
import com.example.nova.ui.screens.FilesScreen
import com.example.nova.ui.screens.SettingsScreen
import com.example.nova.ui.screens.TasksScreen
import com.example.ui.theme.Charcoal900

@Composable
fun NovaApp(
    repository: NovaRepository,
    modifier: Modifier = Modifier
) {
    var currentScreen by remember { mutableStateOf(NovaScreen.CHAT) }

    LaunchedEffect(Unit) {
        repository.initializeSeedData()
    }

    Scaffold(
        bottomBar = {
            NovaBottomBar(
                currentScreen = currentScreen,
                onScreenSelected = { currentScreen = it }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Charcoal900)
        ) {
            when (currentScreen) {
                NovaScreen.CHAT -> ChatScreen(
                    repository = repository,
                    onNavigateToScreen = { currentScreen = it }
                )
                NovaScreen.BROWSER -> BrowserScreen(
                    repository = repository
                )
                NovaScreen.FILES -> FilesScreen(
                    repository = repository
                )
                NovaScreen.TASKS -> TasksScreen(
                    repository = repository
                )
                NovaScreen.SETTINGS -> SettingsScreen(
                    repository = repository
                )
            }
        }
    }
}
