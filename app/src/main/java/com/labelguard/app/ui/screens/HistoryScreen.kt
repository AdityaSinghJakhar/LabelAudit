package com.labelguard.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.labelguard.app.history.HistoryStore
import com.labelguard.app.history.ScanRecord
import com.labelguard.app.pipeline.RuleStatus
import com.labelguard.app.pipeline.Verdict
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
    onExportCsv: () -> Unit,
    onShareCsv: (() -> Unit)? = null,
    exportStatus: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Inspection history", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onBack) { Text("Close") }
            }
        }

        item { SummaryCard(summary) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onExportCsv) { Text("Export CSV") }
                onShareCsv?.let { TextButton(onClick = it) { Text("Share") } }
            }
            exportStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Summary", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Figure("Scans", summary.total.toString())
                Figure("Failed", summary.failed.toString())
                Figure("Passed", summary.passed.toString())
                Figure("Products", summary.distinctProducts.toString())
            }

            // A scan that reached neither PASS nor FAIL is not a neutral
            // result: it means the photographs did not settle the question,
            // and an enforcement user needs to see how often that happens.
            Text(
                text = "%.0f%% of scans reached a definitive verdict"
                    .format(summary.conclusiveRate * 100),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )

            if (summary.topViolations.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("Most frequent violations", style = MaterialTheme.typography.titleSmall)
                summary.topViolations.forEach { (ruleId, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(ruleId, style = MaterialTheme.typography.bodyMedium)
                        Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Conflicting declarations", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Scans of the same product that do not agree. No reference " +
                    "value is involved — these packs contradict one another.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            conflicts.forEach { conflict ->
                Text(
                    text = conflict.product + " — " + conflict.scans + " scans",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (conflict.conflictingPrices.isNotEmpty()) {
                    Text(
                        "Price read as: " + conflict.conflictingPrices.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (conflict.conflictingQuantities.isNotEmpty()) {
                    Text(
                        "Quantity read as: " + conflict.conflictingQuantities.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryRow(record: ScanRecord, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    record.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    record.verdict.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (record.verdict) {
                        Verdict.FAIL -> MaterialTheme.colorScheme.error
                        Verdict.PASS -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Text(
                text = TIMESTAMP.format(Date(record.scannedAt)) +
                    "  ·  ruleset " + record.rulesetVersion,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            listOfNotNull(
                record.mrp?.let { "MRP " + it },
                record.netQuantity,
                record.batchNumber?.let { "Batch " + it }
            ).takeIf { it.isNotEmpty() }?.let {
                Text(
                    it.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (record.violations.isNotEmpty()) {
                Text(
                    text = record.violations.joinToString(", ") { it.ruleId },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Details")
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }

            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                // Every check, not only the violations. A report that listed
                // failures alone could not show that anything was examined,
                // which is what a retrieved inspection record has to prove.
                record.checks.forEach { check ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            check.ruleId + " — " + check.ruleName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            check.status.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (check.status) {
                                RuleStatus.FAIL -> MaterialTheme.colorScheme.error
                                RuleStatus.PASS -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                if (record.rawLines.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("What the scanner read", style = MaterialTheme.typography.titleSmall)
                    Text(
                        record.rawLines.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

private val TIMESTAMP = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.UK)
