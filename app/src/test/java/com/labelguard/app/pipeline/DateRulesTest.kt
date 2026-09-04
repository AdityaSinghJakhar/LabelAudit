package com.labelguard.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Date findings against the shipped ruleset.
 *
 * These are the checks that produce an accusation about somebody's stock, so
 * the tests are written around the boundary rather than the obvious cases: a
 * month-only marking must not be called expired while its month is still
 * running, and an ambiguous day order must not be resolved by guessing.
 */
class DateRulesTest {

    private val ruleset = Ruleset.load(File("src/main/assets/ruleset.yaml").inputStream())

    private fun field(value: String, anchorOnly: Boolean = false, relative: Boolean = false) =
        Consensus.AgreedField(value, 1f, Box(0, 0, 10, 10), 3, 3,
            anchorOnly = anchorOnly, relative = relative)

    private fun evaluate(fields: Map<String, Consensus.AgreedField>, today: LocalDate) =
        RulesEngine.evaluate(ruleset, fields, RulesEngine.Context(today = today))

    private fun statusOf(
        fields: Map<String, Consensus.AgreedField>,
        today: LocalDate,
        ruleId: String
    ) = evaluate(fields, today).findings.first { it.ruleId == ruleId }.status

    // -------------------------------------------------- manufacturing date

    @Test
    fun `a manufacturing date in the future fails`() {
        assertEquals(
            RuleStatus.FAIL,
            statusOf(mapOf("mfg_date" to field("12/2027")), LocalDate.of(2026, 9, 5), "MFG-02")
        )
    }

    @Test
    fun `a past manufacturing date passes`() {
        assertEquals(
            RuleStatus.PASS,
            statusOf(mapOf("mfg_date" to field("03/2025")), LocalDate.of(2026, 9, 5), "MFG-02")
        )
    }

    @Test
    fun `the current month is not a future date`() {
        // "09/2026" scanned on 5 September 2026 could name any day of that
        // month, several of them already past. Calling it impossible would be
        // an accusation the label does not support.
        assertEquals(
            RuleStatus.NEEDS_REVIEW,
            statusOf(mapOf("mfg_date" to field("09/2026")), LocalDate.of(2026, 9, 5), "MFG-02")
        )
    }

    @Test
    fun `an unreadable date is not assessable rather than failed`() {
        assertEquals(
            RuleStatus.NOT_ASSESSABLE,
            statusOf(mapOf("mfg_date" to field("MFD BY")), LocalDate.of(2026, 9, 5), "MFG-02")
        )
    }

    @Test
    fun `a missing date leaves this rule to MFG-01`() {
        // Absence is already reported once. Reporting it twice would inflate
        // the violation count on a single defect.
        val evaluation = evaluate(emptyMap(), LocalDate.of(2026, 9, 5))
        assertEquals(
            RuleStatus.NOT_APPLICABLE,
            evaluation.findings.first { it.ruleId == "MFG-02" }.status
        )
        assertEquals(
            RuleStatus.FAIL,
            evaluation.findings.first { it.ruleId == "MFG-01" }.status
        )
    }

    // ----------------------------------------------------------- ordering

    @Test
    fun `an expiry before the manufacturing date fails`() {
        assertEquals(
            RuleStatus.FAIL,
            statusOf(
                mapOf("mfg_date" to field("06/2026"), "expiry" to field("01/2026")),
                LocalDate.of(2026, 9, 5), "EXPIRY-02"
            )
        )
    }

    @Test
    fun `an expiry after the manufacturing date passes`() {
        assertEquals(
            RuleStatus.PASS,
            statusOf(
                mapOf("mfg_date" to field("01/2026"), "expiry" to field("06/2026")),
                LocalDate.of(2026, 9, 5), "EXPIRY-02"
            )
        )
    }

    @Test
    fun `two markings in the same month cannot be ordered`() {
        assertEquals(
            RuleStatus.PASS,
            statusOf(
                mapOf("mfg_date" to field("06/2026"), "expiry" to field("06/2026")),
                LocalDate.of(2026, 9, 5), "EXPIRY-02"
            )
        )
    }

    // ------------------------------------------------------ shelf life

    @Test
    fun `a pack past its date marking fails`() {
        assertEquals(
            RuleStatus.FAIL,
            statusOf(mapOf("expiry" to field("01/2026")), LocalDate.of(2026, 9, 5), "EXPIRY-03")
        )
    }

    @Test
    fun `a pack within its date marking passes`() {
        assertEquals(
            RuleStatus.PASS,
            statusOf(mapOf("expiry" to field("12/2026")), LocalDate.of(2026, 9, 5), "EXPIRY-03")
        )
    }

    @Test
    fun `a marking expiring this month is not yet certainly expired`() {
        assertEquals(
            RuleStatus.NEEDS_REVIEW,
            statusOf(mapOf("expiry" to field("09/2026")), LocalDate.of(2026, 9, 5), "EXPIRY-03")
        )
    }

    @Test
    fun `a relative marking is resolved against the packing date`() {
        // "Best before 9 months from packing" + "MFG 03/2025" = expired by
        // September 2026. This is the pattern the relabelling raids turned on.
        val fields = mapOf(
            "mfg_date" to field("03/2025"),
            "expiry" to field("BEST BEFORE 9 MONTHS FROM PACKING", relative = true)
        )
        assertEquals(
            RuleStatus.FAIL,
            statusOf(fields, LocalDate.of(2026, 9, 5), "EXPIRY-03")
        )
    }

    @Test
    fun `a relative marking with no packing date cannot be resolved`() {
        val fields = mapOf(
            "expiry" to field("BEST BEFORE 9 MONTHS FROM PACKING", relative = true)
        )
        assertEquals(
            RuleStatus.NOT_ASSESSABLE,
            statusOf(fields, LocalDate.of(2026, 9, 5), "EXPIRY-03")
        )
    }

    @Test
    fun `a relative marking still within its period passes`() {
        val fields = mapOf(
            "mfg_date" to field("06/2026"),
            "expiry" to field("BEST BEFORE 9 MONTHS FROM PACKING", relative = true)
        )
        assertEquals(
            RuleStatus.PASS,
            statusOf(fields, LocalDate.of(2026, 9, 5), "EXPIRY-03")
        )
    }

    // ------------------------------------------------------ the invariant

    @Test
    fun `no date finding claims more than the marking supports`() {
        // Every FAIL emitted by a date rule must be one the range proves.
        // A month-only marking read inside its own month can never be one.
        val inMonth = evaluate(
            mapOf("mfg_date" to field("09/2026"), "expiry" to field("09/2026")),
            LocalDate.of(2026, 9, 15)
        )
        val dateFails = inMonth.findings.filter {
            it.ruleId in setOf("MFG-02", "EXPIRY-02", "EXPIRY-03") &&
                it.status == RuleStatus.FAIL
        }
        assertTrue("nothing here is certain enough to fail: $dateFails", dateFails.isEmpty())
    }
}
