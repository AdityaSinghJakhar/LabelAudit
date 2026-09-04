package com.labelguard.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.labelguard.app.pipeline.Verdict
import com.labelguard.app.viewmodel.BulkItem
import com.labelguard.app.viewmodel.BulkRun

/**
 * Results of a bulk run, one row per image.
 *
 * Worst verdicts first: the point of scanning a batch is to find the packs
 * that need attention, not to read through the compliant ones.
 */
@Composable
fun BulkScreen(
    run: BulkRun,
    onOpen: (BulkItem) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onExportResults: (() -> Unit)? = null,
    onShareResults: (() -> Unit)? = null,
    exportStatus: String? = null
) {
    val ordered = run.items.sortedBy { severity(it.verdict) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Bulk scan",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${run.processed} of ${run.total} images",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    if (!run.isComplete) {
                        LinearProgressIndicator(
                            progress = { run.processed.toFloat() / run.total },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                        )
                    }

                    val summary = run.counts.entries
                        .sortedBy { severity(it.key) }
                        .joinToString("   ") { "${it.value} ${label(it.key)}" }
                    if (summary.isNotBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }

                    // One image per product means no cross-frame corroboration,
                    // so this evidence is weaker than a five-frame camera scan.
                    Text(
                        text = "Each image is treated as one product and read " +
                            "once, so values are not corroborated across frames.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }

        if (run.isComplete && onExportResults != null) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onExportResults,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Export results")
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

        item {
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Back to camera")
            }
        }

        items(ordered) { item -> BulkRow(item, onOpen) }
    }
}

@Composable
private fun BulkRow(item: BulkItem, onOpen: (BulkItem) -> Unit) {
    val color = verdictColour(item.verdict)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.report != null) { onOpen(item) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.padding(end = 12.dp)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
                Text(
                    text = item.verdict?.let { label(it) } ?: "error",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

private fun label(verdict: Verdict) = verdict.name.replace('_', ' ').lowercase()

/** Worst first. Errors sort alongside the unassessable. */
private fun severity(verdict: Verdict?): Int = when (verdict) {
    Verdict.FAIL -> 0
    Verdict.NEEDS_REVIEW -> 1
    null -> 2
    Verdict.NOT_ASSESSABLE -> 3
    Verdict.PASS -> 4
}

private fun verdictColour(verdict: Verdict?): Color = when (verdict) {
    Verdict.PASS -> Color(0xFF15803D)
    Verdict.FAIL -> Color(0xFFB91C1C)
    Verdict.NEEDS_REVIEW -> Color(0xFFB45309)
    Verdict.NOT_ASSESSABLE -> Color(0xFF475569)
    null -> Color(0xFF7F1D1D)
}
