package com.labelguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.labelguard.app.pipeline.Finding
import com.labelguard.app.pipeline.RuleStatus
import com.labelguard.app.pipeline.Verdict
import com.labelguard.app.report.ScanReport
import com.labelguard.app.ui.components.AppCard
import com.labelguard.app.ui.components.MetaRow
import com.labelguard.app.ui.components.PrimaryButton
import com.labelguard.app.ui.components.SecondaryButton
import com.labelguard.app.ui.components.SectionLabel
import com.labelguard.app.ui.components.StatusDot
import com.labelguard.app.ui.components.StatusPill
import com.labelguard.app.ui.components.accentFor
import com.labelguard.app.ui.components.paletteFor
import com.labelguard.app.ui.components.readable
import com.labelguard.app.ui.theme.AppColors
import com.labelguard.app.ui.theme.MetricStyle
import com.labelguard.app.ui.theme.StatusColors

/**
 * The report is the output format: a per-field verdict, the value read, and
 * the citation behind it. Deliberately not an overlay, and no longer carrying
 * image crops — a reviewer wants the clause and the reading, and the crops
 * made a short report long without adding a decision.
 *
 * The layout is ordered for a two-second decision then a slow check: the
 * verdict and what caused it at the top, the declarations under it, and the
 * scanner's raw output at the bottom for whoever doubts the reading. Export
 * and rescan are pinned to a dock so they do not move as the report grows.
 */
@Composable
fun ReportScreen(
    report: ScanReport,
    onExportPdf: () -> Unit,
    onRescan: () -> Unit,
    exportStatus: String? = null,
    onSharePdf: (() -> Unit)? = null,
    onOpenPdf: (() -> Unit)? = null,
    onExportResults: (() -> Unit)? = null,
    onShareResults: (() -> Unit)? = null,
    /**
     * Null for a shopper. Registering a reference asserts what a correct pack
     * says, which someone who bought a packet off a shelf cannot know.
     */
    onEnrol: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Canvas)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { VerdictHeader(report) }

            item { ProvenanceCard(report) }

            // Worst first, and separated from the declaration list: the
            // findings that caused the verdict are what an inspector acts on.
            val blocking = report.blockingChecks
            if (blocking.isNotEmpty()) {
                item { SectionLabel("Flagged", Modifier.padding(top = 4.dp)) }
                items(blocking, key = { it.ruleId + it.field }) { finding ->
                    FindingCard(finding)
                }
            }

            onEnrol?.let { enrol -> item { EnrolCard(report, enrol) } }

            item { SectionLabel("Declarations read", Modifier.padding(top = 4.dp)) }

            items(report.fields, key = { it.field }) { group -> FieldCard(group) }

            if (report.unresolved.isNotEmpty()) {
                item { UnresolvedSection(report) }
            }

            if (report.rawLines.isNotEmpty()) {
                item { RawTextSection(report) }
            }

            // Machine-readable results, for scoring accuracy against a
            // human-typed ground truth on a laptop. Inspector-only, and niche
            // enough to sit at the foot of the report rather than in the dock.
            onExportResults?.let { export ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SecondaryButton(
                            text = "Export results (JSON)",
                            onClick = export,
                            modifier = Modifier.weight(1f)
                        )
                        onShareResults?.let {
                            SecondaryButton(
                                text = "Share results",
                                onClick = it,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            item { SourceNote(report) }
        }

        ActionDock(
            onExportPdf = onExportPdf,
            onRescan = onRescan,
            onSharePdf = onSharePdf,
            onOpenPdf = onOpenPdf,
            exportStatus = exportStatus
        )
    }
}

/**
 * The two-second banner.
 *
 * The wording separates a finding from a failure to look: NOT_ASSESSABLE gets
 * neutral slate and the words "not assessable", never the amber of a real
 * review flag.
 */
@Composable
private fun VerdictHeader(report: ScanReport) {
    val palette = paletteFor(report.verdict)
    val accent = accentFor(report.verdict)

    val action = when (report.verdict) {
        Verdict.PASS -> "No action required"
        Verdict.FAIL -> "Statutory action required"
        Verdict.NEEDS_REVIEW -> "Human review required"
        Verdict.NOT_ASSESSABLE -> "Rescan required"
    }

    AppCard(borderColor = palette.border) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(accent)
                    Text(
                        text = readable(report.verdict).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.content,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                StatusPill(text = action, palette = palette)
            }

            Text(
                text = report.verdictExplanation,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.Ink
            )

            // Naming the responsible rules stops a NOT_ASSESSABLE headline
            // sitting above several PASS rows from looking self-contradictory.
            val blocking = report.blockingChecks
            if (blocking.isNotEmpty()) {
                Text(
                    text = "Because of: " + blocking.joinToString(", ") { it.ruleId },
                    style = MetricStyle,
                    color = palette.content
                )
            }

            val summary = report.counts.entries
                .sortedBy { it.key.ordinal }
                .joinToString("   ") { "${it.value} ${readable(it.key).lowercase()}" }
            if (summary.isNotBlank()) {
                Text(
                    text = "$summary  ·  ${report.counts.values.sum()} checks",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.InkMuted
                )
            }
        }
    }
}

/**
 * What was scanned and under what.
 *
 * All of it is needed to defend the verdict later — a result recorded under
 * one ruleset version cannot be argued from a later one — so it travels with
 * the verdict rather than being buried at the end.
 */
@Composable
private fun ProvenanceCard(report: ScanReport) {
    AppCard {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionLabel("Scanned pack")
            Text(
                text = report.matchedSkuId ?: "No registered SKU matched",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.Ink,
                modifier = Modifier.padding(top = 4.dp)
            )
            report.referenceNote?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.InkMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            HorizontalDivider(
                color = AppColors.DividerSoft,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaRow("Scanned", report.timestamp)
                MetaRow("Ruleset", report.rulesetVersion)
                MetaRow(
                    label = "Frames",
                    value = report.framesUsed.toString() +
                        (if (report.framesGated > 0) "  (${report.framesGated} gated)" else "")
                )
                MetaRow("Read in", "${report.elapsedMs} ms")
            }
        }
    }
}

/**
 * One finding that decided the verdict, with the clause behind it.
 *
 * The citation is not decoration: a violation an inspector cannot cite is one
 * they cannot act on.
 */
@Composable
private fun FindingCard(finding: Finding) {
    val palette = paletteFor(finding.status)
    val accent = accentFor(finding.status)

    AppCard(borderColor = palette.border) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    palette.container,
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(accent)
                Text(
                    text = finding.field.replace('_', ' ').uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.content,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            StatusPill(text = readable(finding.status), palette = palette)
        }

        HorizontalDivider(color = palette.border)

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = finding.label,
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.Ink
            )

            val body = finding.message.ifBlank { finding.reason.orEmpty() }
            if (body.isNotBlank()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.InkMuted
                )
            }

            finding.observedValue?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "Read: $it",
                    style = MetricStyle,
                    color = AppColors.Ink
                )
            }

            Text(
                text = finding.ruleId + " · " + finding.citation,
                style = MetricStyle,
                color = AppColors.InkFaint
            )
        }
    }
}

/**
 * Registering the scanned pack as the reference for a SKU.
 *
 * This is how the registry gets populated in practice: type every value by
 * hand and you are reading the same label the app just read, which proves
 * nothing. Enrolling from a pack you have already checked at least makes the
 * claim explicit and attributable.
 *
 * The card states plainly that the app cannot verify the pack is compliant.
 * It records who said so, not that it is true.
 */
@Composable
private fun EnrolCard(report: ScanReport, onEnrol: (String) -> Unit) {
    var skuId by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    AppCard {
        Column(Modifier.padding(16.dp)) {
            if (report.matchedSkuId == null) {
                Text(
                    text = "No registered SKU matched this pack",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.Ink
                )
                Text(
                    text = "Registry comparisons cannot run without a reference.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.InkMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            } else {
                Text(
                    text = "Compared against registered SKU: " + report.matchedSkuId,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.Ink
                )
            }

            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    text = if (expanded) "Cancel" else "Register this pack as a reference",
                    style = MaterialTheme.typography.labelMedium
                )
            }

            if (expanded) {
                Text(
                    text = "Only do this for a pack you have checked yourself. " +
                        "The app cannot tell whether it is compliant — it records " +
                        "that you said so, and every later comparison inherits " +
                        "any error in it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Fail.content,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(StatusColors.Fail.container, RoundedCornerShape(10.dp))
                        .border(1.dp, StatusColors.Fail.border, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                )
                OutlinedTextField(
                    value = skuId,
                    onValueChange = { skuId = it },
                    label = { Text("SKU name, e.g. Gokul Namkeen 500g") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
                PrimaryButton(
                    text = "Save reference",
                    onClick = {
                        onEnrol(skuId.trim())
                        expanded = false
                        skuId = ""
                    },
                    enabled = skuId.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
            }
        }
    }
}

/** One declaration, with every rule that bears on it. */
@Composable
private fun FieldCard(group: ScanReport.FieldGroup) {
    val palette = paletteFor(group.status)

    AppCard {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.InkFaint
                    )
                    Text(
                        text = group.observedValue?.takeIf { it.isNotBlank() }
                            ?: "Not read from this pack",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (group.observedValue.isNullOrBlank()) {
                            AppColors.InkMuted
                        } else {
                            AppColors.Ink
                        },
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    group.agreement?.let {
                        Text(
                            text = "Frames agreeing: $it",
                            style = MetricStyle,
                            color = AppColors.InkMuted,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                }
                StatusPill(
                    text = readable(group.status),
                    palette = palette,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            HorizontalDivider(
                color = AppColors.DividerSoft,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            SectionLabel(
                if (group.checks.size == 1) "1 check" else "${group.checks.size} checks"
            )

            // Several checks on one field often rest on the same clause —
            // presence and correctness both sit under r. 6(1)(e) — so the
            // citation is printed once per distinct clause rather than
            // repeated under every check.
            var lastCitation: String? = null
            group.checks.forEach { check ->
                Column(Modifier.padding(top = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = check.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.Ink,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        StatusPill(
                            text = readable(check.status),
                            palette = paletteFor(check.status),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    Text(
                        text = check.ruleId,
                        style = MetricStyle,
                        color = AppColors.InkFaint
                    )

                    if (check.message.isNotBlank()) {
                        Text(
                            text = check.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.InkMuted,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    if (check.citation != lastCitation) {
                        Text(
                            text = check.citation,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.InkFaint,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        lastCitation = check.citation
                    }
                }
            }
        }
    }
}

@Composable
private fun UnresolvedSection(report: ScanReport) {
    AppCard {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Fields without consensus",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.Ink
            )
            Text(
                text = "The frames disagreed, so no value was accepted.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.InkMuted,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )

            report.unresolved.forEach { item ->
                Text(
                    text = item.field.replace('_', ' ').uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.InkFaint,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = if (item.candidates.isEmpty()) {
                        item.reason
                    } else {
                        item.candidates.joinToString(", ") { "${it.value} ×${it.votes}" }
                    },
                    style = MetricStyle,
                    color = AppColors.InkMuted
                )
            }
        }
    }
}

/**
 * Everything OCR returned.
 *
 * A reader who sees a declaration marked missing that is visibly on the pack
 * needs to know whether the text was read and not matched, or never read.
 * Only the first tells them the rules need work; the second tells them to
 * retake the photo.
 */
@Composable
private fun RawTextSection(report: ScanReport) {
    var expanded by remember { mutableStateOf(false) }

    AppCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "What the scanner read",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.Ink
            )
            Text(
                text = if (expanded) "Hide" else "${report.rawLines.size} lines",
                style = MaterialTheme.typography.labelMedium,
                color = AppColors.Navy
            )
        }

        if (expanded) {
            HorizontalDivider(color = AppColors.DividerSoft)
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "If a declaration above is marked missing but appears " +
                        "here, the rules need work. If it is not here at all, " +
                        "the photo could not resolve it — retake it closer, " +
                        "with the torch on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.InkMuted,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                report.rawLines.forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = AppColors.InkMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceNote(report: ScanReport) {
    Text(
        text = report.sourceCitation,
        style = MaterialTheme.typography.bodySmall,
        color = AppColors.InkFaint,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
    )
}

/**
 * Pinned to the bottom so the two things done with a report — file it, or
 * scan the next pack — stay in the same place however long the report is.
 */
@Composable
private fun ActionDock(
    onExportPdf: () -> Unit,
    onRescan: () -> Unit,
    onSharePdf: (() -> Unit)?,
    onOpenPdf: (() -> Unit)?,
    exportStatus: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Card)
    ) {
        HorizontalDivider(color = AppColors.Divider)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            exportStatus?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.InkMuted
                )
            }

            PrimaryButton(
                text = "Export PDF report",
                onClick = onExportPdf,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(
                    text = "Scan again",
                    onClick = onRescan,
                    modifier = Modifier.weight(1f)
                )
                // Only once a PDF exists; a report that cannot leave the phone
                // is not much use to an inspector who has to file it.
                onOpenPdf?.let {
                    SecondaryButton(
                        text = "Open PDF",
                        onClick = it,
                        modifier = Modifier.weight(1f)
                    )
                }
                onSharePdf?.let {
                    SecondaryButton(
                        text = "Share PDF",
                        onClick = it,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
