package com.novaworkspace.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novaworkspace.app.ui.theme.ThemePreference

/**
 * Milestone 1 Settings.
 * Real in M1: theme preference (applied immediately by MainActivity, held
 * in saved instance state for the session).
 * Not in M1, stated honestly: AI provider / API key configuration. Keys must
 * never be stored unencrypted, and the Keystore-backed Secret Vault is
 * Milestone 6 - so M1 deliberately provides no key entry field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        Text(
            "Theme applies immediately.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemePreference.entries.forEachIndexed { index, pref ->
                SegmentedButton(
                    selected = themePreference == pref,
                    onClick = { onThemePreferenceChange(pref) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemePreference.entries.size)
                ) {
                    Text(pref.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
        }

        Text("AI Provider", style = MaterialTheme.typography.titleLarge)
        Text(
            "No provider is configured. Provider setup arrives in Milestone 4 and secure, Keystore-backed API-key storage in Milestone 6. For your security, Milestone 1 has no API-key field and stores no credentials.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("About", style = MaterialTheme.typography.titleLarge)
        Text(
            "NOVA Workspace 0.1.0 - Milestone 1. Native Android, Kotlin and Jetpack Compose. Local-first by design; see README and docs/SPEC.md in the source repository.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
