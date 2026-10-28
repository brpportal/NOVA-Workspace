package com.novaworkspace.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.novaworkspace.app.navigation.NovaDestination
import com.novaworkspace.app.ui.browser.BrowserScreen
import com.novaworkspace.app.ui.chat.ChatScreen
import com.novaworkspace.app.ui.files.FilesScreen
import com.novaworkspace.app.ui.settings.SettingsScreen
import com.novaworkspace.app.ui.tasks.TasksScreen
import com.novaworkspace.app.ui.theme.NovaWorkspaceTheme
import com.novaworkspace.app.ui.theme.ThemePreference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themePreference by rememberSaveable { mutableStateOf(ThemePreference.SYSTEM) }
            NovaWorkspaceTheme(preference = themePreference) {
                NovaApp(
                    themePreference = themePreference,
                    onThemePreferenceChange = { themePreference = it }
                )
            }
        }
    }
}

@Composable
fun NovaApp(
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NovaDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NovaDestination.CHAT.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(NovaDestination.CHAT.route) {
                ChatScreen(
                    onOpenSettings = {
                        navController.navigate(NovaDestination.SETTINGS.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(NovaDestination.BROWSER.route) { BrowserScreen() }
            composable(NovaDestination.FILES.route) { FilesScreen() }
            composable(NovaDestination.TASKS.route) { TasksScreen() }
            composable(NovaDestination.SETTINGS.route) {
                SettingsScreen(
                    themePreference = themePreference,
                    onThemePreferenceChange = onThemePreferenceChange
                )
            }
        }
    }
}
