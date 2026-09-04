package com.labelaudit.app.registry

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Persistent store of registered SKUs.
 *
 * A JSON file in internal storage rather than a database: an inspector's
 * registry is tens of products, not thousands, and the whole file is read
 * once per scan. A database would add a dependency, a schema and migrations
 * for a collection that fits comfortably in memory. If the registry ever
 * grows to the point where it must be searched rather than scanned, that is
 * the moment to move to Room — not before.
 *
 * The file is plain JSON so it can be pulled off the device, edited, diffed
 * and put back, which matters while the reference data is still being built
 * up by hand.
 */
class SkuStore(context: Context) {

    private val file = File(context.filesDir, "sku_registry.json")

    /** Minimum score before a scan is considered to be a known SKU. */
    private val matchThreshold = 0.75

    fun load(): List<SkuRecord> {
        if (!file.exists()) return emptyList()

        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { SkuRecord.fromJson(array.getJSONObject(it)) }
        }.getOrElse {
            // A corrupt registry must not take the scanner down with it; an
            // empty registry simply makes comparisons inapplicable.
            emptyList()
        }
    }

    fun save(records: List<SkuRecord>) {
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        file.parentFile?.mkdirs()
        file.writeText(array.toString(2))
    }

    /** Adds or replaces a record, keyed on sku id. */
    fun put(record: SkuRecord) {
        save(load().filterNot { it.skuId == record.skuId } + record)
    }

    fun remove(skuId: String) {
        save(load().filterNot { it.skuId == skuId })
    }

    /**
     * The registered SKU this scan is most likely to be, or null.
     *
     * Returning null is the safe outcome: comparing a pack against the wrong
     * reference would report violations that are really just a mismatch of
     * products, which is worse than reporting nothing to compare against.
     */
    fun bestMatch(fields: Map<String, String>): SkuRecord? {
        val scored = load()
            .map { it to it.matchScore(fields) }
            .filter { it.second >= matchThreshold }
            .sortedByDescending { it.second }

        val best = scored.firstOrNull() ?: return null

        // An ambiguous match is no match. Two products scoring equally means
        // the fields available cannot tell them apart.
        val runnerUp = scored.getOrNull(1)
        if (runnerUp != null && runnerUp.second >= best.second) return null

        return best.first
    }

    /** For diagnostics and the registry screen. */
    val path: String get() = file.absolutePath
}
