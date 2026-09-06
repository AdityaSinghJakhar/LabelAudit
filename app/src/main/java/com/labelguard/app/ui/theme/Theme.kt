package com.labelguard.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = LightAppColors.navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001B3B),
    secondary = LightAppColors.teal,
    onSecondary = Color.White,
    tertiary = Color(0xFF2563EB),
    onTertiary = Color.White,
    background = LightAppColors.canvas,
    onBackground = LightAppColors.ink,
    surface = LightAppColors.card,
    onSurface = LightAppColors.ink,
    surfaceVariant = LightAppColors.chip,
    onSurfaceVariant = LightAppColors.inkMuted,
    outline = LightAppColors.inkFaint,
    outlineVariant = LightAppColors.divider,
    error = LightAppColors.failAccent,
    onError = Color.White,
    errorContainer = LightAppColors.statusFail.container,
    onErrorContainer = LightAppColors.statusFail.content
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkAppColors.navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E293B),
    onPrimaryContainer = Color(0xFF93C5FD),
    secondary = DarkAppColors.teal,
    onSecondary = Color.Black,
    tertiary = Color(0xFF60A5FA),
    onTertiary = Color.Black,
    background = DarkAppColors.canvas,
    onBackground = DarkAppColors.ink,
    surface = DarkAppColors.card,
    onSurface = DarkAppColors.ink,
    surfaceVariant = DarkAppColors.chip,
    onSurfaceVariant = DarkAppColors.inkMuted,
    outline = DarkAppColors.inkFaint,
    outlineVariant = DarkAppColors.divider,
    error = DarkAppColors.failAccent,
    onError = Color.White,
    errorContainer = DarkAppColors.statusFail.container,
    onErrorContainer = DarkAppColors.statusFail.content
)

/** Cards at 16dp, controls at 8–10dp. Badges are drawn as pills where used. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun LabelGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}
