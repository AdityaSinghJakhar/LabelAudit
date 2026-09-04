package com.labelaudit.app.registry

import com.labelaudit.app.pipeline.Box
import com.labelaudit.app.pipeline.Consensus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matching a scan to a registered SKU, and building a record from a scan.
 *
 * The consequential property is which fields take part in matching: a pack
 * whose price is wrong must still be recognised as the product it is, or the
 * packs most worth catching would be the ones never compared.
 */
class SkuRecordTest {

    private val gokul = SkuRecord(
        skuId = "Gokul Namkeen 500g",
        brandStrings = listOf("गोकुल"),
        mrpExact = 140.0,
        netQuantity = "500 g",
        fssaiLicence = "10015022001234",
        source = RegistrySource.ENROLLED_FROM_SCAN
    )

    private fun field(value: String, anchorOnly: Boolean = false) =
        Consensus.AgreedField(value, 1f, Box(0, 0, 10, 10), 3, 3, anchorOnly = anchorOnly)

    // ------------------------------------------------------------ matching

    @Test
    fun `a pack matching brand and quantity is recognised`() {
        val score = gokul.matchScore(
            mapOf("brand" to "गोकुल", "net_quantity" to "500g")
        )
        assertEquals(1.0, score, 0.001)
    }

    @Test
    fun `a wrong price does not prevent recognition`() {
        // An overprinted price is exactly the violation being looked for. If
        // it stopped the pack being matched, the comparison that would catch
        // it would never run.
        val score = gokul.matchScore(
            mapOf("brand" to "गोकुल", "net_quantity" to "500 g", "mrp" to "999")
        )
        assertEquals(1.0, score, 0.001)
    }

    @Test
    fun `a different pack size is not the same SKU`() {
        val score = gokul.matchScore(
            mapOf("brand" to "गोकुल", "net_quantity" to "200 g")
        )
        assertTrue("200 g should not match a 500 g SKU: $score", score < 0.75)
    }

    @Test
    fun `quantity is compared after normalisation`() {
        val score = gokul.matchScore(
            mapOf("brand" to "गोकुल", "net_quantity" to "0.5 kg")
        )
        assertEquals(1.0, score, 0.001)
    }

    @Test
    fun `a scan with nothing comparable scores zero`() {
        assertEquals(0.0, gokul.matchScore(mapOf("mrp" to "140")), 0.001)
    }

    // ----------------------------------------------------------- enrolment

    @Test
    fun `a record is built from what the scan agreed on`() {
        val record = Enrolment.fromScan(
            "Gokul Namkeen 500g",
            mapOf(
                "brand" to field("गोकुल"),
                "mrp" to field("140"),
                "net_quantity" to field("500g"),
                "fssai_licence" to field("10015022001234")
            )
        )

        assertEquals(listOf("गोकुल"), record.brandStrings)
        assertEquals(140.0, record.mrpExact!!, 0.001)
        assertEquals("500g", record.netQuantity)
        assertEquals(RegistrySource.ENROLLED_FROM_SCAN, record.source)
    }

    @Test
    fun `a blank caption is not enrolled as a value`() {
        // "MFG. DATE :" with nothing after it must not become the reference
        // every later pack is measured against.
        val record = Enrolment.fromScan(
            "test",
            mapOf(
                "mrp" to field("140"),
                "net_quantity" to field("", anchorOnly = true)
            )
        )

        assertNull(record.netQuantity)
    }

    @Test
    fun `an enrolled record says where it came from`() {
        val record = Enrolment.fromScan("test", mapOf("mrp" to field("140")))

        assertTrue(
            "the note must not present a scanned reference as authoritative",
            record.source.trustNote.contains("not an authoritative source")
        )
    }

    // Serialisation is exercised in SkuStoreTest: org.json is a stub under
    // plain JVM unit tests, so a round trip here would assert against mocks.

    // -------------------------------------------------- applying to a scan

    @Test
    fun `applying a record populates the ruleset registry`() {
        val ruleset = com.labelaudit.app.pipeline.Ruleset.load(
            java.io.File("src/main/assets/ruleset.yaml").inputStream()
        )
        assertTrue("the shipped registry must start empty", !ruleset.registry.populated)

        val applied = Enrolment.applyTo(ruleset, gokul)

        assertTrue(applied.registry.populated)
        assertEquals(140.0, applied.registry.mrpExact!!, 0.001)
        assertEquals("Gokul Namkeen 500g", applied.registry.skuId)
    }

    @Test
    fun `an applied record turns registry checks into real comparisons`() {
        val ruleset = com.labelaudit.app.pipeline.Ruleset.load(
            java.io.File("src/main/assets/ruleset.yaml").inputStream()
        )
        val applied = Enrolment.applyTo(ruleset, gokul)

        val matching = com.labelaudit.app.pipeline.RulesEngine.evaluate(
            applied, mapOf("mrp" to field("140"))
        ).findings.first { it.ruleId == "LG-MRP-02" }
        assertEquals(com.labelaudit.app.pipeline.RuleStatus.PASS, matching.status)

        // An overprinted price is now a substantiated violation rather than an
        // inapplicable check.
        val overprinted = com.labelaudit.app.pipeline.RulesEngine.evaluate(
            applied, mapOf("mrp" to field("999"))
        ).findings.first { it.ruleId == "LG-MRP-02" }
        assertEquals(com.labelaudit.app.pipeline.RuleStatus.FAIL, overprinted.status)
    }

    @Test
    fun `matchable fields exclude blank captions`() {
        val matchable = Enrolment.matchableFields(
            mapOf(
                "brand" to field("गोकुल"),
                "mfg_date" to field("", anchorOnly = true)
            )
        )

        assertEquals(setOf("brand"), matchable.keys)
        assertNotNull(matchable["brand"])
    }
}
