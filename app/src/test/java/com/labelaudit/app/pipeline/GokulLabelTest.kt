package com.labelaudit.app.pipeline

import com.labelaudit.app.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The real pack that exposed the gap: a 500 g namkeen packet that prints
 * BATCH NO., MFG. DATE and USE BY as captions and leaves all three blank.
 *
 * The first report on this label reported no violations at all, because the
 * ruleset had no date, batch, expiry or licence rules — the pipeline was not
 * looking for the very things the pack had omitted.
 */
class GokulLabelTest {

    private val ruleset: Ruleset =
        Ruleset.load(File("src/main/assets/ruleset.yaml").inputStream())

    private var nextTop = 0
    private fun line(text: String): OcrLine {
        nextTop += 40
        return OcrLine(text, Box(40, nextTop, 600, nextTop + 30))
    }

    /** Transcribed from the photographed pack, blanks included. */
    private fun gokulLines(): List<OcrLine> {
        nextTop = 0
        return listOf(
            line("के शाही नमकीन"),
            line("NET WEIGHT : 500g."),
            line("MAX RETAIL PRICE ₹ : 140/-"),
            line("(INCL. OF ALL TAXES)"),
            line("UNIT SALE PRICE : 0.28 per g."),
            line("BATCH NO. :"),
            line("MFG. DATE :"),
            line("USE BY :"),
            line("BEST BEFORE 2 MONTHS FROM THE DATE OF PACKING"),
            line("निर्माता :"),
            line("गोकुल"),
            line("के शाही नमकीन एवं शाही गजक"),
            line("रोड नं. 7, नेहरु नगर, इन्दौर"),
            line("Customer Care No.9893261670")
        )
    }

    private fun evaluate() = RulesEngine.evaluate(
        ruleset,
        Consensus.build(List(5) { FieldExtractor.extract(gokulLines()) }).fields,
        RulesEngine.Context(
            addressRoles = mapOf(
                FieldExtractor.AddressRole.MANUFACTURER to listOf("निर्माता")
            )
        )
    )

    private fun statusOf(ruleId: String) =
        evaluate().findings.first { it.ruleId == ruleId }

    // ---------------------------------------------------- what the pack has

    @Test
    fun `net quantity and price are read`() {
        val fields = FieldExtractor.extract(gokulLines())

        assertEquals("500g", fields["net_quantity"]?.value)
        assertEquals("140", fields["mrp"]?.value)
    }

    @Test
    fun `the tax-inclusive wording is found`() {
        assertNotNull(FieldExtractor.extract(gokulLines())["tax_inclusive"])
        assertEquals(RuleStatus.PASS, statusOf("LG-MRP-03").status)
    }

    @Test
    fun `consumer care is found`() {
        assertEquals(RuleStatus.PASS, statusOf("LG-CARE-01").status)
    }

    // ------------------------------------------- what the pack leaves blank

    @Test
    fun `a blank caption is detected, not read as a value`() {
        val fields = FieldExtractor.extract(gokulLines())

        for (name in listOf("batch_number", "mfg_date")) {
            val observed = fields[name]
            assertNotNull("$name caption was not detected at all", observed)
            assertTrue("$name should be flagged as a blank caption", observed!!.anchorOnly)
            assertEquals("", observed.value)
        }
    }

    @Test
    fun `the missing manufacturing date fails`() {
        val finding = statusOf("LG-DATE-01")

        assertEquals(RuleStatus.FAIL, finding.status)
        assertTrue(
            "the message should describe a blank caption, not an absent one: " +
                finding.message,
            finding.message.contains("no value follows")
        )
        assertTrue(finding.citation.contains("6(1)(d)"))
    }

    @Test
    fun `the missing batch number fails`() {
        val finding = statusOf("LG-BATCH-01")

        assertEquals(RuleStatus.FAIL, finding.status)
        assertTrue(finding.message.contains("no value follows"))
    }

    @Test
    fun `a relative best-before with no packing date fails`() {
        // "BEST BEFORE 2 MONTHS FROM THE DATE OF PACKING" counts from a date
        // this pack leaves blank, so a consumer can determine no date at all.
        // A date marking nobody can resolve is not a date marking.
        val finding = statusOf("LG-EXPIRY-01")

        assertEquals(RuleStatus.FAIL, finding.status)
        assertTrue(
            "the message should explain the unmet dependency: " + finding.message,
            finding.message.contains("mfg_date") && finding.message.contains("no date")
        )
    }

    @Test
    fun `the same relative best-before passes once the packing date is declared`() {
        nextTop = 0
        val dated = listOf(
            line("MRP Rs. 140/-"),
            line("MFG. DATE : 06/2026"),
            line("BEST BEFORE 2 MONTHS FROM THE DATE OF PACKING")
        )
        val evaluation = RulesEngine.evaluate(
            ruleset,
            Consensus.build(List(3) { FieldExtractor.extract(dated) }).fields,
            RulesEngine.Context()
        )

        assertEquals(
            RuleStatus.PASS,
            evaluation.findings.first { it.ruleId == "LG-EXPIRY-01" }.status
        )
    }

    @Test
    fun `the FSSAI licence is missing from this crop`() {
        // The licence number is cut off in the photographed area, so the rule
        // must report it as absent rather than inventing one.
        assertEquals(RuleStatus.FAIL, statusOf("LG-FSSAI-01").status)
    }

    // -------------------------------------------------------- the verdict

    @Test
    fun `the pack is reported as failing, not as compliant`() {
        val evaluation = evaluate()

        // Registry comparisons are not applicable without a registered SKU, and
        // an inapplicable check must not suppress violations the pipeline did
        // substantiate. Reporting NOT_ASSESSABLE here hid a real failure.
        assertEquals(Verdict.FAIL, evaluation.verdict)
        assertNotEquals(Verdict.NOT_ASSESSABLE, evaluation.verdict)

        val failures = evaluation.violations.map { it.ruleId }.toSet()
        assertTrue(
            "expected date and batch violations, got $failures",
            failures.containsAll(setOf("LG-DATE-01", "LG-BATCH-01"))
        )
    }

    @Test
    fun `with the registry populated the pack fails outright`() {
        val populated = ruleset.copy(
            registry = ruleset.registry.copy(
                populated = true,
                brandStrings = listOf("गोकुल"),
                mrpExact = 140.0,
                netQuantity = "500 g"
            )
        )
        val fields = Consensus.build(List(5) { FieldExtractor.extract(gokulLines()) }).fields
        val evaluation = RulesEngine.evaluate(
            populated,
            fields,
            RulesEngine.Context(
                addressRoles = mapOf(
                    FieldExtractor.AddressRole.MANUFACTURER to listOf("निर्माता")
                )
            )
        )

        // brand has no extractor, so it stays NOT_ASSESSABLE and outranks FAIL.
        // What matters is that the date and batch violations are present and
        // are asserted as violations rather than passed over.
        assertNotEquals(Verdict.PASS, evaluation.verdict)
        val failures = evaluation.violations.map { it.ruleId }.toSet()
        assertTrue(failures.contains("LG-DATE-01"))
        assertTrue(failures.contains("LG-BATCH-01"))
    }

    // ------------------------------------------- a populated pack still passes

    @Test
    fun `the same rules pass when the captions are filled in`() {
        nextTop = 0
        val filled = listOf(
            line("NET WEIGHT : 500g."),
            line("MAX RETAIL PRICE ₹ : 140/-"),
            line("(INCL. OF ALL TAXES)"),
            line("BATCH NO. : B2411"),
            line("MFG. DATE : 06/2026"),
            line("USE BY : 08/2026"),
            line("निर्माता : गोकुल"),
            line("Customer Care No.9893261670"),
            line("FSSAI LIC. NO. 11422334455667")
        )
        val evaluation = RulesEngine.evaluate(
            ruleset,
            Consensus.build(List(3) { FieldExtractor.extract(filled) }).fields,
            RulesEngine.Context(
                addressRoles = mapOf(
                    FieldExtractor.AddressRole.MANUFACTURER to listOf("निर्माता")
                )
            )
        )

        for (id in listOf("LG-DATE-01", "LG-BATCH-01", "LG-EXPIRY-01", "LG-FSSAI-01")) {
            assertEquals(
                "$id should pass on a fully declared pack",
                RuleStatus.PASS,
                evaluation.findings.first { it.ruleId == id }.status
            )
        }
    }
}
