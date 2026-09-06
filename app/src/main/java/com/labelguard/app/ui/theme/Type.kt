package com.labelguard.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.labelguard.app.R

/**
 * The two families DESIGN.md specifies.
 *
 * Plus Jakarta Sans carries headings — institutional without looking
 * bureaucratic. Inter carries body and every number: tall x-height, and a zero
 * that cannot be mistaken for a capital O, which matters when the number being
 * read is a net quantity being checked against a pack.
 *
 * Both are bundled rather than fetched. The app is fully offline by design; a
 * font that arrived over the network would be the one thing in it that did.
 */
val Jakarta = FontFamily(
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_extrabold, FontWeight.ExtraBold)
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

/** Sizes, weights and tracking come straight from DESIGN.md. */
val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Bold,
        fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.64).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.36).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.09).sp
    ),
    titleMedium = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.08).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.12.sp
    ),
    labelLarge = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.24.sp
    ),
    labelSmall = TextStyle(
        fontFamily = Inter, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.33.sp
    )
)

/**
 * Rule ids, citations, measurements, timestamps.
 *
 * Medium weight and tight tracking so a column of them scans vertically —
 * which is how a reviewer reads a report, down the citations rather than
 * across the prose.
 */
val MetricStyle = TextStyle(
    fontFamily = Inter, fontWeight = FontWeight.Medium,
    fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = (-0.26).sp
)
