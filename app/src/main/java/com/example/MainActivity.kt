package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.nova.data.NovaRepository
import com.example.nova.notifications.TaskNotificationHelper
import com.example.nova.ui.NovaApp
import com.example.ui.theme.NovaWorkspaceTheme

class MainActivity : ComponentActivity() {
    private lateinit var repository: NovaRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Task-state notifications: create the channel and run the
        // Android 13+ POST_NOTIFICATIONS permission flow.
        TaskNotificationHelper.ensureChannel(applicationContext)
        TaskNotificationHelper.requestPermissionIfNeeded(this)

        // The getInstance singleton (fix round 2, Defect A): the UI, the
        // chat/agent stack and the WorkManager worker must all share ONE
        // repository — and with it ONE BrowserController and ONE WebView.
        // Constructing a second repository here used to strand every
        // chat-launched browser task on a controller with no WebView.
        repository = NovaRepository.getInstance(applicationContext)

        setContent {
            var isDarkTheme by remember { mutableStateOf(false) }

            NovaWorkspaceTheme(darkTheme = isDarkTheme) {
                NovaApp(
                    repository = repository,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { isDarkTheme = !isDarkTheme }
                )
            }
        }
    }
}

