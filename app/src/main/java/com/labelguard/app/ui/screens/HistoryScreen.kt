package com.labelguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.labelguard.app.history.HistoryStore
import com.labelguard.app.history.ScanRecord
import com.labelguard.app.ui.components.AppCard
import com.labelguard.app.ui.components.SecondaryButton
import com.labelguard.app.ui.components.SectionLabel
import com.labelguard.app.ui.components.StatusPill
import com.labelguard.app.ui.components.accentFor
import com.labelguard.app.ui.components.paletteFor
import com.labelguard.app.ui.components.readable
import com.labelguard.app.ui.theme.AppColors
import com.labelguard.app.ui.theme.MetricStyle
import com.labelguard.app.ui.theme.StatusColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Inspection history, and the summary an enforcement user actually asks of it.
 *
 * The dashboard figures are counted over scans rather than over checks. "How
 * many packs failed" is not "how many rules fired", and conflating the two
 * would multiply every number by the size of the ruleset — which would read as
 * a far bigger enforcement problem than the data shows.
 */
@Composable
fun HistoryScreen(
    records: List<ScanRecord>,
    summary: HistoryStore.Summary,
    conflicts: List<HistoryStore.Conflict>,
    query: String,
    onQueryChange: (String) -> Unit,
    onDelete: (String) -> Unit,
    /** Null for a shopper: the aggregate is an enforcement artefact. */
    onExportCsv: (() -> Unit)? = null,
    onShareCsv: (() -> Unit)? = null,
    exportStatus: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Canvas),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Inspection history",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppColors.Ink
                )
                SecondaryButton(text = "Close", onClick = onBack)
            }
        }

        item { SummaryCard(summary) }

        if (onExportCsv != null || onShareCsv != null || exportStatus != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        onExportCsv?.let {
                            SecondaryButton(text = "Export CSV", onClick = it)
                        }
                        onShareCsv?.let {
                            SecondaryButton(text = "Share", onClick = it)
                        }
                    }
                    exportStatus?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.InkMuted
                        )
                    }
                }
            }
        }

        if (conflicts.isNotEmpty()) {
            item { ConflictsCard(conflicts) }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Search product, batch, price or rule") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (records.isEmpty()) {
            item {
                Text(
                    text = if (query.isBlank()) {
                        "No scans recorded yet."
                    } else {
                        "No scan matches that search."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.InkMuted,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }

        items(records, key = { it.id }) { record ->
            HistoryRow(record, onDelete = { onDelete(record.id) })
        }
    }
}

@Composable
private fun SummaryCard(summary: HistoryStore.Summary) {
    AppCard {
        Column(Modifier.padding(16.dp)) {
            SectionLabel("Summary")

            Row(modifier = Modifier.padding(top = 12.dp)) {
                Figure("Scans", summary.total.toString(), AppColors.Ink, Modifier.weight(1f))
                FigureDivider()
                Figure(
                    label = "Failed",
                    value = summary.failed.toString(),
                    color = StatusColors.FailAccent,
                    modifier = Modifier.weight(1f)
                )
                FigureDivider()
                Figure(
                    label = "Passed",
                    value = summary.passed.toString(),
                    color = StatusColors.PassAccent,
                    modifier = Modifier.weight(1f)
                )
                FigureDivider()
                Figure(
                    label = "Products",
                    value = summary.distinctProducts.toString(),
                    color = AppColors.InkMuted,
                    modifier = Modifier.weight(1f)
                )
            }

            // A scan that reached neither PASS nor FAIL is not a neutral
            // result: it means the photographs did not settle the question,
            // and an enforcement user needs to see how often that happens.
            Text(
                text = "%.0f%% of scans reached a definitive verdict"
                    .format(summary.conclusiveRate * 100),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.InkMuted,
                modifier = Modifier.padding(top = 14.dp)
            )

            if (summary.topViolations.isNotEmpty()) {
                HorizontalDivider(
                    color = AppColors.DividerSoft,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                SectionLabel("Most frequent violations")
                summary.topViolations.forEach { (ruleId, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = ruleId,
                            style = MetricStyle,
                            color = AppColors.Ink
                        )
                        Text(
                            text = count.toString(),
                            style = MetricStyle,
                            color = AppColors.InkMuted
                        )
                    }
                }
            }
        }
    }
}

/**
 * Packs of the same product whose declarations disagree.
 *
 * The one signal here that requires trusting nobody: no reference value was
 * supplied, the packs simply contradict each other.
 */
@Composable
private fun ConflictsCard(conflicts: List<HistoryStore.Conflict>) {
    AppCard(borderColor = StatusColors.Review.border) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Conflicting declarations",
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.Ink
            )
            Text(
                text = "Scans of the same product that do not agree. No reference " +
                    "value is involved — these packs contradict one another.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.InkMuted,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            conflicts.forEach { conflict ->
                Text(
                    text = conflict.product + " — " + conflict.scans + " scans",
                    style = MaterialTheme.typography.titleSmall,
                    color = AppColors.Ink,
                    modifier = Modifier.padding(top = 6.dp)
                )
                if (conflict.conflictingPrices.isNotEmpty()) {
                    Text(
                        text = "Price read as: " + conflict.conflictingPrices.joinToString(", "),
                        style = MetricStyle,
                        color = StatusColors.Review.content
                    )
                }
                if (conflict.conflictingQuantities.isNotEmpty()) {
                    Text(
                        text = "Quantity read as: " +
                            conflict.conflictingQuantities.joinToString(", "),
                        style = MetricStyle,
                        color = StatusColors.Review.content
                    )
                }
            }
        }
    }
}

@Composable
private fun Figure(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.InkMuted,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun FigureDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(34.dp)
            .background(AppColors.DividerSoft)
    )
}

@Composable
private fun HistoryRow(record: ScanRecord, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    AppCard {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.Ink
                    )
                    Text(
                        text = TIMESTAMP.format(Date(record.scannedAt)) +
                            "  ·  ruleset " + record.rulesetVersion,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.InkMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                StatusPill(
                    text = readable(record.verdict),
                    palette = paletteFor(record.verdict),
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            listOfNotNull(
                record.mrp?.let { "MRP " + it },
                record.netQuantity,
                record.batchNumber?.let { "Batch " + it }
            ).takeIf { it.isNotEmpty() }?.let {
                Text(
                    text = it.joinToString("  ·  "),
                    style = MetricStyle,
                    color = AppColors.Ink,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (record.violations.isNotEmpty()) {
                Text(
                    text = record.violations.joinToString(", ") { it.ruleId },
                    style = MetricStyle,
                    color = StatusColors.Fail.content,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryButton(
                    text = if (expanded) "Hide" else "Details",
                    onClick = { expanded = !expanded }
                )
                TextButton(onClick = onDelete) {
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusColors.Fail.content
                    )
                }
            }

            if (expanded) {
                HorizontalDivider(
                    color = AppColors.DividerSoft,
                    modifier = Modifier.padding(vertical = 10.dp)
                )

                // Every check, not only the violations. A report that listed
                // failures alone could not show that anything was examined,
                // which is what a retrieved inspection record has to prove.
                record.checks.forEach { check ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = check.ruleId + " — " + check.ruleName,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.InkMuted,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = readable(check.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = accentFor(check.status),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                if (record.rawLines.isNotEmpty()) {
                    HorizontalDivider(
                        color = AppColors.DividerSoft,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                    SectionLabel("What the scanner read")
                    Text(
                        text = record.rawLines.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.InkMuted,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

private val TIMESTAMP = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK)
