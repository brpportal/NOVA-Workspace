package com.novaworkspace.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novaworkspace.app.ui.EmptyState

/**
 * Milestone 1 Chat screen.
 * Honest behaviour: there is no AI provider in M1 (that is Milestone 4) and
 * no secure key storage yet (Milestone 6), so sending does NOT simulate a
 * model reply. It explains exactly what is missing and offers a real
 * navigation action to Settings.
 */
@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        EmptyState(
            title = "No conversations yet",
            body = "AI chat arrives in Milestone 4, after the provider setup and secure API-key storage (Milestone 6) are in place. No message is sent to any model in Milestone 1.",
            modifier = Modifier.weight(1f)
        )
        notice?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text("Open Settings")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            Button(
                onClick = {
                    notice = if (draft.isBlank()) {
                        "Type a message first. Nothing was sent."
                    } else {
                        "AI is not configured yet: no provider is connected in Milestone 1, so this message was not sent anywhere."
                    }
                },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
