package com.labelaudit.app.pipeline

import com.labelaudit.app.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The "inclusive of all taxes" wording.
 *
 * This is the smallest, faintest text on most packs — parenthesised, set
 * under the price in a lighter weight — so OCR reads it inconsistently or
 * splits it across lines. A pack that plainly carries the wording was being
 * reported as omitting it, which is the worst kind of false violation.
 */
class TaxWordingTest {

    private var top = 0
    private fun lines(vararg text: String): List<OcrLine> {
        top = 0
        return text.map {
            top += 40
            OcrLine(it, Box(40, top, 600, top + 30))
        }
    }

    @Test
    fun `the wording is found on its own line`() {
        assertNotNull(FieldExtractor.findTaxInclusive(lines("(INCL. OF ALL TAXES)")))
    }

    @Test
    fun `the wording is found when merged into the price line`() {
        assertNotNull(
            FieldExtractor.findTaxInclusive(
                lines("MAX RETAIL PRICE : 140/- (INCL. OF ALL TAXES)")
            )
        )
    }

    @Test
    fun `the wording is found when OCR splits it across lines`() {
        // The parentheses and small type make this split common.
        val found = FieldExtractor.findTaxInclusive(
            lines("MAX RETAIL PRICE : 140/-", "(INCL. OF", "ALL TAXES)")
        )
        assertNotNull("a split phrase should still be recognised", found)
    }

    @Test
    fun `common phrasings are all accepted`() {
        for (wording in listOf(
            "Inclusive of all taxes",
            "incl of all tax",
            "MRP (inclusive of all taxes)",
            "Rs 140 incl. all taxes",
            "सभी करों सहित"
        )) {
            assertNotNull(
                "should recognise: $wording",
                FieldExtractor.findTaxInclusive(lines(wording))
            )
        }
    }

    @Test
    fun `a label with no such wording is not credited with it`() {
        assertNull(
            FieldExtractor.findTaxInclusive(
                lines("MAX RETAIL PRICE : 140/-", "NET WEIGHT : 500g.")
            )
        )
    }

    @Test
    fun `the wording reaches the rule as a pass`() {
        val ruleset = Ruleset.load(File("src/main/assets/ruleset.yaml").inputStream())
        val label = lines("MAX RETAIL PRICE : 140/-", "(INCL. OF", "ALL TAXES)")

        val evaluation = RulesEngine.evaluate(
            ruleset,
            Consensus.build(List(3) { FieldExtractor.extract(label) }).fields,
            RulesEngine.Context()
        )

        assertEquals(
            RuleStatus.PASS,
            evaluation.findings.first { it.ruleId == "TAX-01" }.status
        )
    }

    @Test
    fun `every rule carries a plain-words name`() {
        // The report showed bare ids, and two rules under one clause could not
        // be told apart by their citation.
        val ruleset = Ruleset.load(File("src/main/assets/ruleset.yaml").inputStream())

        ruleset.rules.forEach {
            org.junit.Assert.assertTrue("${it.id} has no name", it.name.isNotBlank())
        }
    }

    @Test
    fun `the two price rules are named distinctly`() {
        val ruleset = Ruleset.load(File("src/main/assets/ruleset.yaml").inputStream())
        val presence = ruleset.rules.first { it.id == "MRP-01" }
        val registry = ruleset.rules.first { it.id == "MRP-02" }

        // They share a clause, so only the names distinguish them.
        assertEquals(presence.citation.take(60), registry.citation.take(60))
        org.junit.Assert.assertNotEquals(presence.name, registry.name)
    }
}
