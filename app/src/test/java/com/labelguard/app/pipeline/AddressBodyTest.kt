package com.labelguard.app.pipeline

import com.labelguard.app.ocr.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An address is the place, not the word introducing it.
 *
 * The Gokul pack prints "निर्माता :" on one line and the address on the two
 * below. Tagging each line on its own left the manufacturer address as the
 * caption itself, and the rule passed a pack on the strength of a word meaning
 * "manufacturer" — the very declaration it exists to check.
 */
class AddressBodyTest {

    private fun line(text: String, row: Int) =
        OcrLine(text, Box(60, row * 100, 560, row * 100 + 40))

    private val ruleset = Ruleset.load(File("src/main/assets/ruleset.yaml").inputStream())

    private fun manufacturer(lines: List<OcrLine>) =
        FieldExtractor.extract(lines)["manufacturer_address"]

    /** One frame through consensus, the way the pipeline feeds the engine. */
    private fun agreed(lines: List<OcrLine>) =
        Consensus.build(listOf(FieldExtractor.extract(lines))).fields

    // ------------------------------------------------- following the address

    @Test
    fun `an address on the lines below the caption is picked up`() {
        val lines = listOf(
            line("निर्माता :", 1),
            line("गोकुल", 2),
            line("रोड़ नं. 7, नेहरू नगर, इन्दौर", 3)
        )

        val address = manufacturer(lines)!!
        assertFalse("the caption alone is not an address", address.anchorOnly)
        assertTrue("expected the street to be carried: ${address.value}",
            address.value.contains("नेहरू नगर"))
    }

    @Test
    fun `an inline address still works`() {
        val lines = listOf(line("Manufactured by Acme Foods, Indore", 1))

        val address = manufacturer(lines)!!
        assertFalse(address.anchorOnly)
        assertTrue(address.value.contains("Acme Foods"))
    }

    @Test
    fun `a caption with nothing under it is a blank caption`() {
        val lines = listOf(line("निर्माता :", 1))

        assertTrue(manufacturer(lines)!!.anchorOnly)
    }

    @Test
    fun `the address stops at the next caption`() {
        // BATCH NO. belongs to the batch check. Swallowing it would put a
        // batch number into the manufacturer's address and take it away from
        // the field that needs it.
        val lines = listOf(
            line("निर्माता :", 1),
            line("गोकुल, इन्दौर", 2),
            line("BATCH NO. : B2411", 3)
        )

        val address = manufacturer(lines)!!
        assertFalse(address.value.contains("B2411"))
        assertEquals("B2411", FieldExtractor.extract(lines)["batch_number"]?.value)
    }

    @Test
    fun `the address does not run on forever`() {
        val lines = listOf(line("निर्माता :", 1)) +
            (2..10).map { line("line $it of unrelated pack text", it) }

        val value = manufacturer(lines)!!.value
        assertFalse("only a few lines may be absorbed: $value", value.contains("line 7"))
    }

    // ------------------------------------------------------------ the rule

    @Test
    fun `a named role with no address fails`() {
        val evaluation = RulesEngine.evaluate(
            ruleset,
            agreed(listOf(line("निर्माता :", 1)))
        )
        val finding = evaluation.findings.first { it.ruleId == "MFR-01" }

        assertEquals(RuleStatus.FAIL, finding.status)
        assertTrue(
            "the message must say what is missing: ${finding.message}",
            finding.message.contains("no address follows")
        )
    }

    @Test
    fun `a named role with an address passes`() {
        val lines = listOf(
            line("निर्माता :", 1),
            line("गोकुल, रोड़ नं. 7, नेहरू नगर, इन्दौर", 2)
        )
        val fields = agreed(lines)

        val evaluation = RulesEngine.evaluate(
            ruleset,
            fields,
            RulesEngine.Context(
                addressRoles = mapOf(
                    FieldExtractor.AddressRole.MANUFACTURER to
                        listOf(fields["manufacturer_address"]!!.value)
                )
            )
        )

        assertEquals(
            RuleStatus.PASS,
            evaluation.findings.first { it.ruleId == "MFR-01" }.status
        )
    }

    @Test
    fun `a caption-only address never reaches the rule as a role`() {
        // Even if the role map were built carelessly, the blank caption must
        // win: an empty string in a non-empty list would otherwise satisfy
        // the presence check.
        val evaluation = RulesEngine.evaluate(
            ruleset,
            agreed(listOf(line("निर्माता :", 1))),
            RulesEngine.Context(
                addressRoles = mapOf(
                    FieldExtractor.AddressRole.MANUFACTURER to listOf("")
                )
            )
        )

        assertEquals(
            RuleStatus.FAIL,
            evaluation.findings.first { it.ruleId == "MFR-01" }.status
        )
    }
}
