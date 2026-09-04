package com.labelguard.app.history

import com.labelguard.app.pipeline.RuleStatus
import com.labelguard.app.pipeline.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Searching and summarising the inspection history.
 *
 * The searches tested here are the ones an inspector actually performs weeks
 * later: a batch number they wrote down, a rule they are chasing, a price
 * they remember. Matching only on the product name would make the record
 * technically complete and practically unusable.
 */
class ScanRecordTest {

    private fun check(
        ruleId: String,
        status: RuleStatus,
        name: String = "",
        field: String = "mrp"
    ) = ScanRecord.Check(ruleId, name, field, status, "", null)

    private fun record(
        id: String = "1",
        verdict: Verdict = Verdict.PASS,
        brand: String? = "Gokul",
        skuId: String? = null,
        mrp: String? = "140",
        quantity: String? = "500 g",
        batch: String? = "B2411",
        checks: List<ScanRecord.Check> = emptyList(),
        at: Long = 1_000L
    ) = ScanRecord(
        id = id,
        scannedAt = at,
        verdict = verdict,
        rulesetVersion = "2026.1.0",
        skuId = skuId,
        brand = brand,
        mrp = mrp,
        netQuantity = quantity,
        batchNumber = batch,
        mfgDate = "03/2025",
        framesUsed = 3,
        checks = checks,
        rawLines = emptyList()
    )

    // ------------------------------------------------------------- search

    @Test
    fun `a blank query matches everything`() {
        assertTrue(record().matches(""))
        assertTrue(record().matches("   "))
    }

    @Test
    fun `a product is found by brand`() {
        assertTrue(record().matches("gokul"))
        assertTrue(record().matches("GOKUL"))
    }

    @Test
    fun `a product is found by batch number`() {
        // What an inspector wrote in a notebook at the shop.
        assertTrue(record().matches("B2411"))
    }

    @Test
    fun `a product is found by price`() {
        assertTrue(record().matches("140"))
    }

    @Test
    fun `scans are found by the rule that fired`() {
        val r = record(checks = listOf(check("EXPIRY-03", RuleStatus.FAIL, "Pack is within its declared date marking")))
        assertTrue(r.matches("EXPIRY-03"))
        assertTrue(r.matches("date marking"))
    }

    @Test
    fun `scans are found by verdict`() {
        assertTrue(record(verdict = Verdict.FAIL).matches("fail"))
        assertFalse(record(verdict = Verdict.PASS).matches("fail"))
    }

    @Test
    fun `an unrelated query matches nothing`() {
        assertFalse(record().matches("kurkure"))
    }

    @Test
    fun `a pack with no identity is still listed`() {
        // A pack whose brand could not be read is exactly the kind worth
        // finding again, so it must not vanish from the history.
        assertEquals("Unidentified pack", record(brand = null, skuId = null).title)
    }

    @Test
    fun `a matched SKU names the row over the read brand`() {
        assertEquals("Gokul Namkeen 500g", record(skuId = "Gokul Namkeen 500g").title)
    }

    // ---------------------------------------------------------- violations

    @Test
    fun `violations are the failed checks only`() {
        val r = record(
            checks = listOf(
                check("MRP-01", RuleStatus.PASS),
                check("EXPIRY-03", RuleStatus.FAIL),
                check("QTY-02", RuleStatus.NOT_APPLICABLE),
                check("MFG-02", RuleStatus.NOT_ASSESSABLE)
            )
        )
        assertEquals(listOf("EXPIRY-03"), r.violations.map { it.ruleId })
    }
}
