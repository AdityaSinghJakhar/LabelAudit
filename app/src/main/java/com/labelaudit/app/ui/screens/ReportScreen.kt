package com.labelaudit.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.labelaudit.app.pipeline.RuleStatus
import com.labelaudit.app.pipeline.Verdict
import com.labelaudit.app.report.ScanReport

/**
 * The report is the output format: a per-field verdict, the value read, and
 * the citation behind it. Deliberately not an overlay, and no longer carrying
 * image crops — a reviewer wants the clause and the reading, and the crops
 * made a short report long without adding a decision.
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
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { VerdictHeader(report) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onExportPdf, modifier = Modifier.weight(1f)) {
                    Text("Export PDF")
                }
                OutlinedButton(onClick = onRescan, modifier = Modifier.weight(1f)) {
                    Text("Scan again")
                }
            }
        }

        // Machine-readable results, for scoring accuracy against a
        // human-typed ground truth on a laptop.
        onExportResults?.let { export ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = export, modifier = Modifier.weight(1f)) {
                        Text("Export results (JSON)")
                    }
                    onShareResults?.let {
                        OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) {
                            Text("Share results")
                        }
                    }
                }
            }
        }

        exportStatus?.let {
            item {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Only shown once a PDF exists; a report that cannot leave the phone
        // is not much use to an inspector who has to file it.
        if (onSharePdf != null || onOpenPdf != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    onOpenPdf?.let {
                        OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) {
                            Text("Open PDF")
                        }
                    }
                    onSharePdf?.let {
                        Button(onClick = it, modifier = Modifier.weight(1f)) {
                            Text("Share PDF")
                        }
                    }
                }
            }
        }

        items(report.fields, key = { it.field }) { group -> FieldCard(group) }

        if (report.unresolved.isNotEmpty()) {
            item { UnresolvedSection(report) }
        }

        item { Provenance(report) }
    }
}

@Composable
private fun VerdictHeader(report: ScanReport) {
    val color = verdictColor(report.verdict)

    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = report.verdict.name.replace('_', ' '),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = report.verdictExplanation,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )

            // Naming the responsible rules stops a NOT_ASSESSABLE headline
            // sitting above several PASS rows from looking self-contradictory.
            val blocking = report.blockingChecks
            if (blocking.isNotEmpty()) {
                Text(
                    text = "Because of: " + blocking.joinToString(", ") { it.ruleId },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            val summary = report.counts.entries
                .sortedBy { it.key.ordinal }
                .joinToString("   ") { "${it.value} ${it.key.name.lowercase()}" }
            if (summary.isNotBlank()) {
                Text(
                    text = "$summary  (over ${report.counts.values.sum()} checks)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun FieldCard(group: ScanReport.FieldGroup) {
    val color = statusColor(group.status)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                StatusChip(group.status, color)
            }

            group.observedValue?.let {
                Text(
                    text = "Read: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            group.agreement?.let {
                Text(
                    text = "Agreement: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }


            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            Text(
                text = if (group.checks.size == 1) "1 check" else "${group.checks.size} checks",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Several checks on one field often rest on the same clause —
            // presence and correctness both sit under r. 6(1)(e) — so the
            // citation is printed once per distinct clause rather than
            // repeated under every check.
            var lastCitation: String? = null
            group.checks.forEach { check ->
                Column(Modifier.padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = check.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        StatusChip(check.status, statusColor(check.status))
                    }

                    Text(
                        text = check.ruleId,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (check.message.isNotBlank()) {
                        Text(
                            text = check.message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    if (check.citation != lastCitation) {
                        Text(
                            text = check.citation,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun StatusChip(status: RuleStatus, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = status.name.replace('_', ' '),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun UnresolvedSection(report: ScanReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = "Fields without consensus",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "The frames disagreed, so no value was accepted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )

            report.unresolved.forEach { item ->
                Text(
                    text = item.field.replace('_', ' '),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (item.candidates.isEmpty()) {
                        item.reason
                    } else {
                        item.candidates.joinToString(", ") { "${it.value} ×${it.votes}" }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun Provenance(report: ScanReport) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            text = "Scanned ${report.timestamp}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Ruleset ${report.rulesetVersion} · ${report.framesUsed} frames" +
                (if (report.framesGated > 0) " (${report.framesGated} gated)" else "") +
                " · ${report.elapsedMs} ms",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = report.sourceCitation,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private fun verdictColor(verdict: Verdict): Color = when (verdict) {
    Verdict.PASS -> Color(0xFF15803D)
    Verdict.FAIL -> Color(0xFFB91C1C)
    Verdict.NEEDS_REVIEW -> Color(0xFFB45309)
    Verdict.NOT_ASSESSABLE -> Color(0xFF475569)
}

private fun statusColor(status: RuleStatus): Color = when (status) {
    RuleStatus.PASS -> Color(0xFF15803D)
    RuleStatus.FAIL -> Color(0xFFB91C1C)
    RuleStatus.NEEDS_REVIEW -> Color(0xFFB45309)
    RuleStatus.NOT_ASSESSABLE -> Color(0xFF475569)
    RuleStatus.NOT_APPLICABLE -> Color(0xFF64748B)
    RuleStatus.EXEMPT -> Color(0xFF64748B)
}
