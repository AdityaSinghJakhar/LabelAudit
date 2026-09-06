package com.labelguard.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * A status's three colours: fill, stroke, text.
 */
data class StatusPalette(
    val container: Color,
    val border: Color,
    val content: Color
)

/**
 * Full color scheme definition supporting Light and Dark modes.
 */
data class AppColorScheme(
    val navy: Color,
    val navyPressed: Color,
    val teal: Color,
    val canvas: Color,
    val card: Color,
    val divider: Color,
    val dividerSoft: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val chip: Color,
    val statusPass: StatusPalette,
    val statusFail: StatusPalette,
    val statusReview: StatusPalette,
    val statusNeutral: StatusPalette,
    val passAccent: Color,
    val failAccent: Color,
    val reviewAccent: Color,
    val neutralAccent: Color,
    val isDark: Boolean = false
)

val LightAppColors = AppColorScheme(
    navy = Color(0xFF0F294A),
    navyPressed = Color(0xFF091B33),
    teal = Color(0xFF0D9488),
    canvas = Color(0xFFFAFBFD),
    card = Color(0xFFFFFFFF),
    divider = Color(0xFFE2E8F0),
    dividerSoft = Color(0xFFF1F5F9),
    ink = Color(0xFF0F172A),
    inkMuted = Color(0xFF475569),
    inkFaint = Color(0xFF94A3B8),
    chip = Color(0xFFF1F5F9),
    statusPass = StatusPalette(
        container = Color(0xFFECFDF5),
        border = Color(0xFFA7F3D0),
        content = Color(0xFF065F46)
    ),
    statusFail = StatusPalette(
        container = Color(0xFFFEF2F2),
        border = Color(0xFFFECACA),
        content = Color(0xFF991B1B)
    ),
    statusReview = StatusPalette(
        container = Color(0xFFFFFBEB),
        border = Color(0xFFFDE68A),
        content = Color(0xFF92400E)
    ),
    statusNeutral = StatusPalette(
        container = Color(0xFFF1F5F9),
        border = Color(0xFFE2E8F0),
        content = Color(0xFF475569)
    ),
    passAccent = Color(0xFF059669),
    failAccent = Color(0xFFDC2626),
    reviewAccent = Color(0xFFD97706),
    neutralAccent = Color(0xFF64748B),
    isDark = false
)

val DarkAppColors = AppColorScheme(
    navy = Color(0xFF3B82F6),
    navyPressed = Color(0xFF2563EB),
    teal = Color(0xFF2DD4BF),
    canvas = Color(0xFF0B0F19),
    card = Color(0xFF161E2E),
    divider = Color(0xFF283548),
    dividerSoft = Color(0xFF1E293B),
    ink = Color(0xFFF8FAFC),
    inkMuted = Color(0xFF94A3B8),
    inkFaint = Color(0xFF64748B),
    chip = Color(0xFF1E293B),
    statusPass = StatusPalette(
        container = Color(0xFF064E3B).copy(alpha = 0.55f),
        border = Color(0xFF059669),
        content = Color(0xFF34D399)
    ),
    statusFail = StatusPalette(
        container = Color(0xFF450A0A).copy(alpha = 0.55f),
        border = Color(0xFFDC2626),
        content = Color(0xFFF87171)
    ),
    statusReview = StatusPalette(
        container = Color(0xFF451A03).copy(alpha = 0.55f),
        border = Color(0xFFD97706),
        content = Color(0xFFFBBF24)
    ),
    statusNeutral = StatusPalette(
        container = Color(0xFF1E293B),
        border = Color(0xFF334155),
        content = Color(0xFF94A3B8)
    ),
    passAccent = Color(0xFF10B981),
    failAccent = Color(0xFFEF4444),
    reviewAccent = Color(0xFFF59E0B),
    neutralAccent = Color(0xFF94A3B8),
    isDark = true
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/**
 * Accessors that resolve dynamically to the active theme's colors.
 */
object AppColors {
    val Navy: Color @Composable get() = LocalAppColors.current.navy
    val NavyPressed: Color @Composable get() = LocalAppColors.current.navyPressed
    val Teal: Color @Composable get() = LocalAppColors.current.teal
    val Canvas: Color @Composable get() = LocalAppColors.current.canvas
    val Card: Color @Composable get() = LocalAppColors.current.card
    val Divider: Color @Composable get() = LocalAppColors.current.divider
    val DividerSoft: Color @Composable get() = LocalAppColors.current.dividerSoft
    val Ink: Color @Composable get() = LocalAppColors.current.ink
    val InkMuted: Color @Composable get() = LocalAppColors.current.inkMuted
    val InkFaint: Color @Composable get() = LocalAppColors.current.inkFaint
    val Chip: Color @Composable get() = LocalAppColors.current.chip
}

object StatusColors {
    val Pass: StatusPalette @Composable get() = LocalAppColors.current.statusPass
    val Fail: StatusPalette @Composable get() = LocalAppColors.current.statusFail
    val Review: StatusPalette @Composable get() = LocalAppColors.current.statusReview
    val Neutral: StatusPalette @Composable get() = LocalAppColors.current.statusNeutral

    val PassAccent: Color @Composable get() = LocalAppColors.current.passAccent
    val FailAccent: Color @Composable get() = LocalAppColors.current.failAccent
    val ReviewAccent: Color @Composable get() = LocalAppColors.current.reviewAccent
    val NeutralAccent: Color @Composable get() = LocalAppColors.current.neutralAccent
}
