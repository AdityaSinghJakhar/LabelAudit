package com.labelguard.app.history

import com.labelguard.app.pipeline.RuleStatus
import com.labelguard.app.pipeline.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history as CSV.
 *
 * The tests that matter are the escaping ones. A manufacturer address always
 * contains commas and a rule message often contains quotes; either would
 * shift every later column into the wrong field, and a compliance return
 * silently mis-aligned is worse than one that failed to export.
 */
class HistoryCsvTest {

    private fun record(
        checks: List<ScanRecord.Check> = emptyList(),
        brand: String? = "Gokul",
        mrp: String? = "140"
    ) = ScanRecord(
        id = "s1",
        scannedAt = 0L,
        verdict = Verdict.FAIL,
        rulesetVersion = "2026.1.0",
        skuId = null,
        brand = brand,
        mrp = mrp,
        netQuantity = "500 g",
        batchNumber = "B2411",
        mfgDate = "03/2025",
        framesUsed = 3,
        checks = checks,
        rawLines = emptyList()
    )

    private fun check(name: String = "Price is declared", message: String = "ok") =
        ScanRecord.Check("MRP-01", name, "mrp", RuleStatus.FAIL, message, "140")

    @Test
    fun `the header names every column`() {
        val header = HistoryCsv.render(emptyList()).trim()
        assertTrue(header.startsWith("scan_id,scanned_at,verdict"))
        assertTrue(header.endsWith("observed_value,message"))
    }

    @Test
    fun `one row per check, not per scan`() {
        val csv = HistoryCsv.render(listOf(record(checks = listOf(check(), check(), check()))))
        assertEquals(4, csv.trim().lines().size) // header + three checks
    }

    @Test
    fun `a scan with no checks still appears`() {
        // A scan that produced nothing is itself a fact about the inspection.
        val csv = HistoryCsv.render(listOf(record()))
        assertEquals(2, csv.trim().lines().size)
    }

    @Test
    fun `a value containing a comma is quoted`() {
        val csv = HistoryCsv.render(
            listOf(record(checks = listOf(check(message = "Plot 4, MIDC, Turbhe"))))
        )
        assertTrue(csv, csv.contains("\"Plot 4, MIDC, Turbhe\""))
    }

    @Test
    fun `a value containing a quote is doubled`() {
        val csv = HistoryCsv.render(
            listOf(record(checks = listOf(check(message = "read as \"140\""))))
        )
        assertTrue(csv, csv.contains("\"read as \"\"140\"\"\""))
    }

    @Test
    fun `a value containing a newline is quoted`() {
        val csv = HistoryCsv.render(
            listOf(record(checks = listOf(check(message = "line one\nline two"))))
        )
        assertTrue(csv, csv.contains("\"line one\nline two\""))
    }

    @Test
    fun `an absent value is an empty cell, not the word null`() {
        val csv = HistoryCsv.render(listOf(record(mrp = null, checks = listOf(check()))))
        assertTrue("no literal null should appear: $csv", !csv.contains(",null,"))
    }

    @Test
    fun `column count is constant across rows`() {
        // The property that makes the file loadable at all.
        val csv = HistoryCsv.render(
            listOf(
                record(checks = listOf(check(message = "a,b"), check(message = "plain"))),
                record(brand = null)
            )
        )
        val counts = csv.trim().lines().map { line ->
            var inQuotes = false
            var commas = 0
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> i++
                    c == '"' -> inQuotes = !inQuotes
                    c == ',' && !inQuotes -> commas++
                }
                i++
            }
            commas
        }
        assertEquals("every row must have the same number of fields", 1, counts.distinct().size)
    }
}
