package com.labelguard.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = AppColors.Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E3FF),
    onPrimaryContainer = Color(0xFF001B3B),
    secondary = AppColors.Teal,
    onSecondary = Color.White,
    tertiary = Color(0xFF2563EB),
    onTertiary = Color.White,
    background = AppColors.Canvas,
    onBackground = AppColors.Ink,
    surface = AppColors.Card,
    onSurface = AppColors.Ink,
    surfaceVariant = AppColors.Chip,
    onSurfaceVariant = AppColors.InkMuted,
    outline = AppColors.InkFaint,
    outlineVariant = AppColors.Divider,
    error = StatusColors.FailAccent,
    onError = Color.White,
    errorContainer = StatusColors.Fail.container,
    onErrorContainer = StatusColors.Fail.content
)

/** Cards at 16dp, controls at 8–10dp. Badges are drawn as pills where used. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * Light only, and no dynamic colour, both deliberately.
 *
 * Dynamic colour would let the device's wallpaper repaint a compliance
 * verdict — the one thing in this app whose colour carries meaning. A dark
 * scheme would have to re-derive every emerald and amber against a dark
 * ground to stay legible, and the app is used outdoors in daylight where the
 * light scheme is the easier read anyway.
 */
@Composable
fun LabelGuardTheme(content: @Composable () -> Unit) {
    val view = LocalView.current

    // The scheme is light whatever the device is set to, so the status bar
    // icons have to be dark whatever the device is set to. Without this, a
    // phone in dark mode draws white icons onto a white report.
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
