package com.labelguard.app.viewmodel

import com.labelguard.app.pipeline.Verdict
import com.labelguard.app.report.ScanReport

/** One image's outcome in a bulk run. */
data class BulkItem(
    val name: String,
    val report: ScanReport?,
    val error: String?
) {
    val verdict: Verdict? get() = report?.verdict

    val summary: String
        get() = when {
            error != null -> error
            report == null -> "No result"
            else -> {
                val failed = report.fields.flatMap { it.checks }
                    .count { it.status.name == "FAIL" }
                if (failed == 0) "No violations found" else "$failed failing checks"
            }
        }
}

/**
 * A bulk run over selected images.
 *
 * Each image is treated as a separate product, which is what an inspector
 * auditing a shelf of photos actually has — not several frames of one pack.
 * That means no cross-frame corroboration, so every report in a bulk run
 * carries a single-frame evidence warning rather than borrowing the
 * confidence that multi-frame consensus would earn.
 */
data class BulkRun(
    val items: List<BulkItem>,
    val processed: Int,
    val total: Int
) {
    val isComplete: Boolean get() = processed >= total

    val counts: Map<Verdict, Int>
        get() = items.mapNotNull { it.verdict }.groupingBy { it }.eachCount()

    val failures: Int get() = items.count { it.verdict == Verdict.FAIL }
}
