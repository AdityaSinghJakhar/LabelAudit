package com.labelguard.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.labelguard.app.pipeline.Verdict
import com.labelguard.app.ui.components.AppCard
import com.labelguard.app.ui.components.SecondaryButton
import com.labelguard.app.ui.components.StatusPill
import com.labelguard.app.ui.components.paletteFor
import com.labelguard.app.ui.components.readable
import com.labelguard.app.ui.theme.AppColors
import com.labelguard.app.ui.theme.MetricStyle
import com.labelguard.app.ui.theme.StatusColors
import com.labelguard.app.ui.theme.StatusPalette
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
            .background(AppColors.Canvas),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            AppCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "Bulk scan",
                        style = MaterialTheme.typography.headlineSmall,
                        color = AppColors.Ink
                    )
                    Text(
                        text = "${run.processed} of ${run.total} images",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.InkMuted,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    if (!run.isComplete) {
                        LinearProgressIndicator(
                            progress = { run.processed.toFloat() / run.total },
                            color = AppColors.Navy,
                            trackColor = AppColors.DividerSoft,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        )
                    }

                    val summary = run.counts.entries
                        .sortedBy { severity(it.key) }
                        .joinToString("   ") { "${it.value} ${readable(it.key).lowercase()}" }
                    if (summary.isNotBlank()) {
                        Text(
                            text = summary,
                            style = MetricStyle,
                            color = AppColors.Ink,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }

                    // One image per product means no cross-frame corroboration,
                    // so this evidence is weaker than a five-frame camera scan.
                    Text(
                        text = "Each image is treated as one product and read " +
                            "once, so values are not corroborated across frames.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.InkMuted,
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
                    SecondaryButton(
                        text = "Export results",
                        onClick = onExportResults,
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

        exportStatus?.let {
            item {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.InkMuted
                )
            }
        }

        item {
            SecondaryButton(
                text = "Back to camera",
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            )
        }

        items(ordered) { item -> BulkRow(item, onOpen) }
    }
}

@Composable
private fun BulkRow(item: BulkItem, onOpen: (BulkItem) -> Unit) {
    val palette = paletteOf(item.verdict)

    AppCard(borderColor = palette.border) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = item.report != null) { onOpen(item) }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.InkMuted
                )
            }

            StatusPill(
                // A null verdict is an image that could not be read at all, not
                // a pack that passed or failed. It says so.
                text = item.verdict?.let { readable(it) } ?: "Could not read",
                palette = palette,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

private fun paletteOf(verdict: Verdict?): StatusPalette =
    verdict?.let { paletteFor(it) } ?: StatusColors.Neutral

/** Worst first. Errors sort alongside the unassessable. */
private fun severity(verdict: Verdict?): Int = when (verdict) {
    Verdict.FAIL -> 0
    Verdict.NEEDS_REVIEW -> 1
    null -> 2
    Verdict.NOT_ASSESSABLE -> 3
    Verdict.PASS -> 4
}
