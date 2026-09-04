package com.labelguard.app.pipeline

import com.labelguard.app.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FSSAI licence detection.
 *
 * The licence number is almost never printed after its caption on the same
 * line. It sits under the fssai logo, on its own line, and the logo itself is
 * a stylised mark OCR may not read as text at all. Looking only to the right
 * of an anchor meant a clearly visible licence was reported as missing.
 */
class FssaiTest {

    private var top = 0
    private fun line(text: String): OcrLine {
        top += 40
        return OcrLine(text, Box(40, top, 600, top + 30))
    }
    private fun lines(vararg text: String): List<OcrLine> {
        top = 0
        return text.map { line(it) }
    }

    @Test
    fun `a licence number on the line below the logo is found`() {
        val found = FieldExtractor.findFssai(
            lines("LIC. fssai", "10015022001234")
        )

        assertNotNull("the number below the logo was not found", found)
        assertEquals("10015022001234", found!!.value)
    }

    @Test
    fun `a licence number is found even when the logo is not read as text`() {
        // The mark is stylised; OCR frequently returns nothing for it. The
        // 14-digit number is the distinctive, legally meaningful part.
        val found = FieldExtractor.findFssai(
            lines("NET WEIGHT : 500g.", "10015022001234")
        )

        assertNotNull(found)
        assertEquals("10015022001234", found!!.value)
    }

    @Test
    fun `digits spaced out by OCR are still recognised`() {
        val found = FieldExtractor.findFssai(lines("fssai", "1001 5022 0012 34"))

        assertNotNull(found)
        assertEquals("10015022001234", found!!.value)
    }

    @Test
    fun `a licence on the same line as its caption is found`() {
        val found = FieldExtractor.findFssai(lines("FSSAI LIC. NO. 10015022001234"))

        assertNotNull(found)
        assertEquals("10015022001234", found!!.value)
    }

    @Test
    fun `a ten digit phone number is not mistaken for a licence`() {
        // Consumer care numbers are 10 digits; licences are 14.
        assertNull(FieldExtractor.findFssai(lines("Customer Care No.9893261670")))
    }

    @Test
    fun `a caption with no number anywhere is reported as blank`() {
        val found = FieldExtractor.findFssai(lines("LIC. fssai", "गोकुल"))

        assertNotNull(found)
        assertTrue("should be flagged as a caption with no value", found!!.anchorOnly)
    }

    @Test
    fun `no caption and no number yields nothing`() {
        assertNull(FieldExtractor.findFssai(lines("NET WEIGHT : 500g.", "गोकुल")))
    }

    @Test
    fun `the licence flows through to the rule`() {
        val ruleset = Ruleset.load(
            java.io.File("src/main/assets/ruleset.yaml").inputStream()
        )
        val label = lines(
            "MAX RETAIL PRICE : 140/-",
            "LIC. fssai",
            "10015022001234"
        )
        val evaluation = RulesEngine.evaluate(
            ruleset,
            Consensus.build(List(3) { FieldExtractor.extract(label) }).fields,
            RulesEngine.Context()
        )

        assertEquals(
            RuleStatus.PASS,
            evaluation.findings.first { it.ruleId == "FSSAI-01" }.status
        )
    }
}
