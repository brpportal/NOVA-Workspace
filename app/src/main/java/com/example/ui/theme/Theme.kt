package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = WarmAccent,
    onPrimary = WarmSurface,
    primaryContainer = WarmAccentContainer,
    onPrimaryContainer = WarmAccentDark,
    secondary = StoneMuted,
    onSecondary = WarmSurface,
    secondaryContainer = WarmCanvas,
    onSecondaryContainer = CharcoalInk,
    tertiary = WarmPurple,
    onTertiary = WarmSurface,
    background = WarmOffWhite,
    onBackground = CharcoalInk,
    surface = WarmSurface,
    onSurface = CharcoalInk,
    surfaceVariant = WarmCanvas,
    onSurfaceVariant = StoneMuted,
    outline = WarmBorder,
    outlineVariant = WarmBorderSubtle,
    error = WarmError,
    onError = WarmSurface
)

private val DarkColorScheme = darkColorScheme(
    primary = WarmAccentMuted,
    onPrimary = Espresso900,
    primaryContainer = Espresso700,
    onPrimaryContainer = WarmAccentContainer,
    secondary = StoneLight,
    onSecondary = Espresso900,
    secondaryContainer = Espresso800,
    onSecondaryContainer = WarmTitanium,
    tertiary = WarmPurple,
    onTertiary = WarmTitanium,
    background = Espresso900,
    onBackground = WarmTitanium,
    surface = Espresso800,
    onSurface = WarmTitanium,
    surfaceVariant = Espresso700,
    onSurfaceVariant = StoneLight,
    outline = EspressoBorder,
    outlineVariant = Espresso700,
    error = WarmError,
    onError = WarmTitanium
)

@Composable
fun NovaWorkspaceTheme(
    darkTheme: Boolean = false, // Clean warm off-white by default as requested
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.surface.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
