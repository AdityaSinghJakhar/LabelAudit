package com.labelguard.app.history

import com.labelguard.app.pipeline.RuleStatus
import com.labelguard.app.pipeline.Verdict
import com.labelguard.app.report.ScanReport
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One completed scan, kept so it can be found again.
 *
 * Deliberately not the whole ScanReport. Bitmaps are dropped, and each check
 * keeps only what a reader needs months later — which rule, what status, what
 * was read. A history entry is evidence about an inspection, so it stores the
 * ruleset version that produced it: a verdict recorded under one version of
 * the rules cannot be defended by quoting a later one.
 */
data class ScanRecord(
    val id: String = UUID.randomUUID().toString(),
    val scannedAt: Long = System.currentTimeMillis(),
    val verdict: Verdict,
    val rulesetVersion: String,
    val skuId: String?,
    val brand: String?,
    val mrp: String?,
    val netQuantity: String?,
    val batchNumber: String?,
    val mfgDate: String?,
    val framesUsed: Int,
    val checks: List<Check>,
    val rawLines: List<String>
) {
    data class Check(
        val ruleId: String,
        val ruleName: String,
        val field: String,
        val status: RuleStatus,
        val message: String,
        val observedValue: String?
    )

    val violations: List<Check> get() = checks.filter { it.status == RuleStatus.FAIL }

    /** What the list row shows when no SKU was matched. */
    val title: String
        get() = skuId ?: brand?.takeIf { it.isNotBlank() } ?: "Unidentified pack"

    /**
     * Free-text search over the fields an inspector would actually remember:
     * the product, its batch, a price, or a rule they are chasing.
     */
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return listOfNotNull(skuId, brand, mrp, netQuantity, batchNumber, mfgDate)
            .any { it.lowercase().contains(q) } ||
            verdict.name.lowercase().contains(q) ||
            checks.any {
                it.ruleId.lowercase().contains(q) || it.ruleName.lowercase().contains(q)
            }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("scanned_at", scannedAt)
        .put("verdict", verdict.name)
        .put("ruleset_version", rulesetVersion)
        .put("sku_id", skuId ?: JSONObject.NULL)
        .put("brand", brand ?: JSONObject.NULL)
        .put("mrp", mrp ?: JSONObject.NULL)
        .put("net_quantity", netQuantity ?: JSONObject.NULL)
        .put("batch_number", batchNumber ?: JSONObject.NULL)
        .put("mfg_date", mfgDate ?: JSONObject.NULL)
        .put("frames_used", framesUsed)
        .put("raw_lines", JSONArray(rawLines))
        .put("checks", JSONArray().apply {
            checks.forEach {
                put(
                    JSONObject()
                        .put("rule_id", it.ruleId)
                        .put("rule_name", it.ruleName)
                        .put("field", it.field)
                        .put("status", it.status.name)
                        .put("message", it.message)
                        .put("observed_value", it.observedValue ?: JSONObject.NULL)
                )
            }
        })

    companion object {

        /** Absent and the literal string "null" are not the same thing. */
        private fun JSONObject.text(name: String): String? =
            if (isNull(name)) null else optString(name).ifBlank { null }

        fun fromJson(json: JSONObject): ScanRecord {
            val checksArray = json.optJSONArray("checks") ?: JSONArray()
            val linesArray = json.optJSONArray("raw_lines") ?: JSONArray()

            return ScanRecord(
                id = json.optString("id", UUID.randomUUID().toString()),
                scannedAt = json.optLong("scanned_at", 0L),
                verdict = runCatching { Verdict.valueOf(json.optString("verdict")) }
                    .getOrDefault(Verdict.NOT_ASSESSABLE),
                rulesetVersion = json.optString("ruleset_version"),
                skuId = json.text("sku_id"),
                brand = json.text("brand"),
                mrp = json.text("mrp"),
                netQuantity = json.text("net_quantity"),
                batchNumber = json.text("batch_number"),
                mfgDate = json.text("mfg_date"),
                framesUsed = json.optInt("frames_used"),
                rawLines = (0 until linesArray.length()).map { linesArray.getString(it) },
                checks = (0 until checksArray.length()).map { i ->
                    val c = checksArray.getJSONObject(i)
                    Check(
                        ruleId = c.optString("rule_id"),
                        ruleName = c.optString("rule_name"),
                        field = c.optString("field"),
                        status = runCatching { RuleStatus.valueOf(c.optString("status")) }
                            .getOrDefault(RuleStatus.NOT_ASSESSABLE),
                        message = c.optString("message"),
                        observedValue = c.text("observed_value")
                    )
                }
            )
        }

        /** Condense a finished report into the entry that outlives it. */
        fun from(report: ScanReport): ScanRecord {
            fun read(field: String) = report.fields
                .firstOrNull { it.field == field }
                ?.observedValue
                ?.takeIf { it.isNotBlank() }

            return ScanRecord(
                verdict = report.verdict,
                rulesetVersion = report.rulesetVersion,
                skuId = report.matchedSkuId,
                brand = read("brand"),
                mrp = read("mrp"),
                netQuantity = read("net_quantity"),
                batchNumber = read("batch_number"),
                mfgDate = read("mfg_date"),
                framesUsed = report.framesUsed,
                rawLines = report.rawLines,
                checks = report.fields.flatMap { group ->
                    group.checks.map {
                        Check(
                            ruleId = it.ruleId,
                            ruleName = it.ruleName.ifBlank { it.label },
                            field = it.field,
                            status = it.status,
                            message = it.message,
                            observedValue = it.observedValue
                        )
                    }
                }
            )
        }
    }
}
