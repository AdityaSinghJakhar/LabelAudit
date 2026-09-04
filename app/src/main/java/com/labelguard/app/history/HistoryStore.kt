package com.labelguard.app.history

import android.content.Context
import com.labelguard.app.pipeline.RuleStatus
import com.labelguard.app.pipeline.Verdict
import org.json.JSONArray
import java.io.File

/**
 * The inspection history: every scan this device has completed.
 *
 * Newest first, capped. The cap is not arbitrary — an unbounded file is read
 * and rewritten on every scan, so it would slow each scan down in proportion
 * to how much work the device had already done. When the history needs to
 * outlive the cap it belongs on a server, which is also where it stops being
 * one inspector's private record.
 *
 * Kept alongside the SKU registry as plain JSON, for the same reason: it can
 * be pulled off the device and read by a person, which is what evidence has
 * to allow.
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "scan_history.json")

    fun load(): List<ScanRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { ScanRecord.fromJson(array.getJSONObject(it)) }
        }.getOrElse {
            // An unreadable history must not stop the next scan from running.
            emptyList()
        }
    }

    fun add(record: ScanRecord): List<ScanRecord> {
        val kept = (listOf(record) + load())
            .distinctBy { it.id }
            .sortedByDescending { it.scannedAt }
            .take(MAX_RECORDS)
        save(kept)
        return kept
    }

    fun remove(id: String): List<ScanRecord> = save(load().filterNot { it.id == id })

    fun clear(): List<ScanRecord> = save(emptyList())

    private fun save(records: List<ScanRecord>): List<ScanRecord> {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        file.parentFile?.mkdirs()
        file.writeText(array.toString(2))
        return records
    }

    /**
     * What the dashboard shows.
     *
     * Counts are over scans, not over checks: an inspector asking "how many
     * packs failed" is not asking how many rules fired, and conflating the
     * two would inflate every figure by the size of the ruleset.
     */
    data class Summary(
        val total: Int,
        val byVerdict: Map<Verdict, Int>,
        val topViolations: List<Pair<String, Int>>,
        val distinctProducts: Int
    ) {
        val failed: Int get() = byVerdict[Verdict.FAIL] ?: 0
        val passed: Int get() = byVerdict[Verdict.PASS] ?: 0

        /** Share of scans that reached a definitive verdict either way. */
        val conclusiveRate: Double
            get() = if (total == 0) 0.0 else (failed + passed).toDouble() / total
    }

    fun summarise(records: List<ScanRecord> = load()): Summary = Summary(
        total = records.size,
        byVerdict = records.groupingBy { it.verdict }.eachCount(),
        topViolations = records
            .flatMap { it.checks }
            .filter { it.status == RuleStatus.FAIL }
            .groupingBy { it.ruleId }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(5),
        distinctProducts = records.mapNotNull { it.skuId ?: it.brand }.distinct().size
    )

    /**
     * Scans of the same product that disagree on a declaration.
     *
     * This is the one comparison that needs nobody to be trusted. If eleven
     * packs of a product read 20 and one reads 35, the outlier is visible
     * without any reference value having been supplied — the packs contradict
     * each other, and that contradiction is the evidence.
     */
    fun conflicts(records: List<ScanRecord> = load()): List<Conflict> =
        records
            .filter { it.title != "Unidentified pack" }
            .groupBy { it.title }
            .mapNotNull { (product, scans) ->
                val prices = scans.mapNotNull { it.mrp }.distinct()
                val quantities = scans.mapNotNull { it.netQuantity }.distinct()
                if (prices.size < 2 && quantities.size < 2) return@mapNotNull null
                Conflict(
                    product = product,
                    scans = scans.size,
                    conflictingPrices = prices.takeIf { it.size > 1 }.orEmpty(),
                    conflictingQuantities = quantities.takeIf { it.size > 1 }.orEmpty()
                )
            }
            .sortedByDescending { it.scans }

    data class Conflict(
        val product: String,
        val scans: Int,
        val conflictingPrices: List<String>,
        val conflictingQuantities: List<String>
    )

    val path: String get() = file.absolutePath

    companion object {
        const val MAX_RECORDS = 500
    }
}
