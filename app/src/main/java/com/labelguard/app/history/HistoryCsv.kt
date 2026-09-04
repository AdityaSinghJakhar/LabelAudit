package com.labelguard.app.history

import java.io.File

/**
 * The history as a spreadsheet.
 *
 * The PDF report is the record of one inspection; this is the working file —
 * the thing that gets sorted, filtered and pasted into a return. One row per
 * check rather than per scan, because a violation is what gets acted on and a
 * row per scan would bury the rule ids inside a cell.
 */
object HistoryCsv {

    private val COLUMNS = listOf(
        "scan_id", "scanned_at", "verdict", "ruleset_version", "product",
        "brand", "mrp", "net_quantity", "batch_number", "mfg_date",
        "rule_id", "rule_name", "field", "status", "observed_value", "message"
    )

    /**
     * Escape a value for CSV.
     *
     * Addresses and rule messages both contain commas and quotes, and a
     * consumer-care line can contain a newline. Any of them would silently
     * shift every later column into the wrong field, so quoting is not
     * optional here.
     */
    private fun cell(value: Any?): String {
        val s = value?.toString().orEmpty()
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
    }

    fun render(records: List<ScanRecord>): String = buildString {
        appendLine(COLUMNS.joinToString(","))
        for (record in records) {
            val timestamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.UK
            ).format(java.util.Date(record.scannedAt))

            val prefix = listOf(
                record.id, timestamp, record.verdict.name, record.rulesetVersion,
                record.title, record.brand, record.mrp, record.netQuantity,
                record.batchNumber, record.mfgDate
            )

            if (record.checks.isEmpty()) {
                appendLine((prefix + List(6) { null }).joinToString(",") { cell(it) })
                continue
            }

            for (check in record.checks) {
                appendLine(
                    (prefix + listOf(
                        check.ruleId, check.ruleName, check.field,
                        check.status.name, check.observedValue, check.message
                    )).joinToString(",") { cell(it) }
                )
            }
        }
    }

    fun write(records: List<ScanRecord>, destination: File): File {
        destination.parentFile?.mkdirs()
        destination.writeText(render(records))
        return destination
    }
}
