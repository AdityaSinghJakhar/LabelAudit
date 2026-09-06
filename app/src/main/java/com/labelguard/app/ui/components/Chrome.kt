package com.labelguard.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.labelguard.app.pipeline.RuleStatus
import com.labelguard.app.pipeline.Verdict
import com.labelguard.app.ui.theme.AppColors
import com.labelguard.app.ui.theme.StatusColors
import com.labelguard.app.ui.theme.StatusPalette

/**
 * The palette for a rule outcome.
 *
 * NOT_ASSESSABLE deliberately does not get the amber of NEEDS_REVIEW. One
 * says the label may be wrong; the other says the photograph did not settle
 * it. Painting them the same colour is the exact confusion the pipeline is
 * built to prevent.
 */
fun paletteFor(status: RuleStatus): StatusPalette = when (status) {
    RuleStatus.PASS -> StatusColors.Pass
    RuleStatus.FAIL -> StatusColors.Fail
    RuleStatus.NEEDS_REVIEW -> StatusColors.Review
    RuleStatus.NOT_ASSESSABLE, RuleStatus.NOT_APPLICABLE, RuleStatus.EXEMPT ->
        StatusColors.Neutral
}

fun paletteFor(verdict: Verdict): StatusPalette = when (verdict) {
    Verdict.PASS -> StatusColors.Pass
    Verdict.FAIL -> StatusColors.Fail
    Verdict.NEEDS_REVIEW -> StatusColors.Review
    Verdict.NOT_ASSESSABLE -> StatusColors.Neutral
}

fun accentFor(status: RuleStatus): Color = when (status) {
    RuleStatus.PASS -> StatusColors.PassAccent
    RuleStatus.FAIL -> StatusColors.FailAccent
    RuleStatus.NEEDS_REVIEW -> StatusColors.ReviewAccent
    RuleStatus.NOT_ASSESSABLE, RuleStatus.NOT_APPLICABLE, RuleStatus.EXEMPT ->
        StatusColors.NeutralAccent
}

fun accentFor(verdict: Verdict): Color = when (verdict) {
    Verdict.PASS -> StatusColors.PassAccent
    Verdict.FAIL -> StatusColors.FailAccent
    Verdict.NEEDS_REVIEW -> StatusColors.ReviewAccent
    Verdict.NOT_ASSESSABLE -> StatusColors.NeutralAccent
}

/** Statuses as the reader should see them, not as the enum spells them. */
fun readable(status: RuleStatus): String = when (status) {
    RuleStatus.PASS -> "Pass"
    RuleStatus.FAIL -> "Fail"
    RuleStatus.NEEDS_REVIEW -> "Needs review"
    RuleStatus.NOT_ASSESSABLE -> "Not assessable"
    RuleStatus.NOT_APPLICABLE -> "Not applicable"
    RuleStatus.EXEMPT -> "Exempt"
}

fun readable(verdict: Verdict): String = when (verdict) {
    Verdict.PASS -> "Compliant"
    Verdict.FAIL -> "Non-compliant"
    Verdict.NEEDS_REVIEW -> "Needs review"
    Verdict.NOT_ASSESSABLE -> "Not assessable"
}

/** A capsule status badge. The word carries the meaning; the colour repeats it. */
@Composable
fun StatusPill(
    text: String,
    palette: StatusPalette,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = palette.content,
        modifier = modifier
            .background(palette.container, RoundedCornerShape(percent = 50))
            .border(1.dp, palette.border, RoundedCornerShape(percent = 50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** A small filled circle in the status colour, for the head of a banner. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

/**
 * The standard surface: white, 16dp radius, one hard 1px stroke, no shadow.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    borderColor: Color = AppColors.Divider,
    background: Color = AppColors.Card,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(16.dp))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
        content = content
    )
}

/** Small caps heading above a card or a group of rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = AppColors.InkFaint,
        modifier = modifier
    )
}

/** The one dominant action on a screen. 48dp so it is hittable one-handed. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.Navy,
            contentColor = Color.White
        )
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Equal visual weight to the primary, without competing for the tap. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.Navy)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

/** A label / value pair, used down the evidence and provenance columns. */
@Composable
fun MetaRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = AppColors.InkMuted
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.InkFaint
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
