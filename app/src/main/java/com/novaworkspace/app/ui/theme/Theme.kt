package com.novaworkspace.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = NovaAccentDark,
    secondary = NovaAccentDark,
    background = CharcoalBackground,
    surface = CharcoalSurface,
    onBackground = Color.White,
    onSurface = Color.White
)

private val LightColors = lightColorScheme(
    primary = NovaAccent,
    secondary = NovaAccent,
    background = LightBackground,
    surface = LightSurface,
    onBackground = Color(0xFF121212),
    onSurface = Color(0xFF121212)
)

enum class ThemePreference { SYSTEM, LIGHT, DARK }

@Composable
fun NovaWorkspaceTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = NovaTypography,
        content = content
    )
}
