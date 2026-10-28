package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = NovaCyan,
    onPrimary = Charcoal900,
    primaryContainer = NovaCyanContainer,
    onPrimaryContainer = NovaCyan,
    secondary = SlateMuted,
    onSecondary = TitaniumSilver,
    secondaryContainer = Charcoal700,
    onSecondaryContainer = TitaniumSilver,
    tertiary = PurpleAccent,
    onTertiary = TitaniumSilver,
    background = Charcoal900,
    onBackground = TitaniumSilver,
    surface = Charcoal800,
    onSurface = TitaniumSilver,
    surfaceVariant = Charcoal700,
    onSurfaceVariant = SlateMuted,
    outline = Charcoal600,
    outlineVariant = Charcoal500,
    error = SafetyRed,
    onError = PureWhite
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = PureWhite,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0369A1),
    secondary = Color(0xFF475569),
    onSecondary = PureWhite,
    secondaryContainer = PlatinumSurface,
    onSecondaryContainer = SlateDarkText,
    tertiary = PurpleAccent,
    onTertiary = PureWhite,
    background = SnowLight,
    onBackground = SlateDarkText,
    surface = PureWhite,
    onSurface = SlateDarkText,
    surfaceVariant = PlatinumSurface,
    onSurfaceVariant = Color(0xFF64748B),
    outline = SlateBorder,
    outlineVariant = Color(0xFFE2E8F0),
    error = SafetyRed,
    onError = PureWhite
)

@Composable
fun NovaWorkspaceTheme(
    darkTheme: Boolean = true, // Default to sleek obsidian charcoal
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
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
