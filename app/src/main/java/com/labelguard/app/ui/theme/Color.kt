package com.labelguard.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The neutral scale and the two brand accents, from DESIGN.md.
 *
 * Depth comes from tonal surfaces and 1px strokes, never from shadows: a
 * diffuse drop shadow disappears under direct sunlight, a hard border does
 * not, and this app is read on a shop floor at midday.
 */
object AppColors {
    val Navy = Color(0xFF0F294A)
    val NavyPressed = Color(0xFF091B33)

    /** The computer-vision accent: reticles, capture state, measured values. */
    val Teal = Color(0xFF0D9488)

    val Canvas = Color(0xFFFAFBFD)
    val Card = Color(0xFFFFFFFF)
    val Divider = Color(0xFFE2E8F0)
    val DividerSoft = Color(0xFFF1F5F9)
    val Ink = Color(0xFF0F172A)
    val InkMuted = Color(0xFF475569)
    val InkFaint = Color(0xFF94A3B8)
    val Chip = Color(0xFFF1F5F9)
}

/**
 * A status's three colours: fill, stroke, text.
 *
 * Three rather than one because a badge that relied on fill alone washes out
 * at arm's length in daylight, and because the text colour has to clear
 * contrast on its own fill.
 */
data class StatusPalette(
    val container: Color,
    val border: Color,
    val content: Color
)

/**
 * The palette per outcome.
 *
 * The five states of the pipeline map onto four visual treatments, and the
 * pairing is deliberate:
 *
 *   PASS            emerald
 *   FAIL            red — the only treatment that reads as an accusation
 *   NEEDS_REVIEW    amber
 *   NOT_ASSESSABLE  slate, *not* amber. "We could not read this" is not a
 *                   weaker kind of violation; colouring it as one is exactly
 *                   the confusion the pipeline works to avoid.
 *   NOT_APPLICABLE  slate, likewise
 *   EXEMPT          slate
 *
 * Colour never carries a status by itself — every badge prints the word too.
 */
object StatusColors {
    val Pass = StatusPalette(
        container = Color(0xFFECFDF5),
        border = Color(0xFFA7F3D0),
        content = Color(0xFF065F46)
    )
    val Fail = StatusPalette(
        container = Color(0xFFFEF2F2),
        border = Color(0xFFFECACA),
        content = Color(0xFF991B1B)
    )
    val Review = StatusPalette(
        container = Color(0xFFFFFBEB),
        border = Color(0xFFFDE68A),
        content = Color(0xFF92400E)
    )
    val Neutral = StatusPalette(
        container = AppColors.Chip,
        border = AppColors.Divider,
        content = AppColors.InkMuted
    )

    /** The saturated accents, for dots, rules and headline text. */
    val PassAccent = Color(0xFF059669)
    val FailAccent = Color(0xFFDC2626)
    val ReviewAccent = Color(0xFFD97706)
    val NeutralAccent = Color(0xFF64748B)
}
