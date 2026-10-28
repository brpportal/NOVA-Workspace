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
import com.example.nova.ui.NovaApp
import com.example.ui.theme.NovaWorkspaceTheme

class MainActivity : ComponentActivity() {
    private lateinit var repository: NovaRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = NovaRepository(applicationContext)

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

