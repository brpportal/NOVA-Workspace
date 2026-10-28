package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
            NovaWorkspaceTheme(darkTheme = true) {
                NovaApp(repository = repository)
            }
        }
    }
}
