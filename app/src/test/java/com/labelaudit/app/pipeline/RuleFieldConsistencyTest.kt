package com.labelaudit.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the seam between the ruleset and the extractor.
 *
 * A rule naming a field the extractor never produces looks fine in the YAML
 * and compiles fine, but at runtime the finding silently loses its observed
 * value, its agreement and its evidence crop — and, once the registry is
 * populated, would assert a violation the pipeline never actually looked for.
 * That is precisely the class of bug the core invariant forbids, so it is
 * checked here rather than left to inspection.
 */
class RuleFieldConsistencyTest {

    private val ruleset: Ruleset =
        Ruleset.load(File("src/main/assets/ruleset.yaml").inputStream())

    private fun field(value: String) = Consensus.AgreedField(
        value = value,
        confidence = 1f,
        box = Box(0, 0, 10, 10),
        agreement = 5,
        frames = 5
    )

    @Test
    fun `presence and registry rules only name extractable fields`() {
        val offenders = ruleset.rules
            .filter { it.check.type in setOf("field_present", "matches_registry") }
            .filter { it.field !in FieldExtractor.SUPPORTED_FIELDS }
            .map { "${it.id} -> ${it.field}" }

        assertEquals(
            "rules naming fields with no extractor: $offenders",
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `the manufacturer rule names the field the extractor produces`() {
        val rule = ruleset.rules.first { it.id == "MFR-01" }

        // Naming "addresses" left a PASSing row with no read value, no
        // agreement and no evidence crop.
        assertNotEquals("addresses", rule.field)
        assertEquals("manufacturer_address", rule.field)
        assertTrue(rule.field in FieldExtractor.SUPPORTED_FIELDS)
    }

    @Test
    fun `a rule for an unextractable field refuses rather than accuses`() {
        // A field no extractor produces must never be reported as a missing
        // declaration: that would accuse the label of omitting something the
        // pipeline never looked for.
        val invented = ruleset.copy(
            registry = ruleset.registry.copy(populated = true),
            rules = listOf(
                Ruleset.Rule(
                    id = "TEST-NOFIELD",
                    field = "nutrition_panel",
                    check = Ruleset.Check(type = "field_present"),
                    citation = "test citation"
                )
            )
        )
        val evaluation = RulesEngine.evaluate(invented, mapOf("mrp" to field("45.00")))
        val finding = evaluation.findings.first { it.ruleId == "TEST-NOFIELD" }

        assertEquals(RuleStatus.NOT_APPLICABLE, finding.status)
        assertEquals("field_not_extractable", finding.reason)
        assertNotEquals(RuleStatus.FAIL, finding.status)
    }

    @Test
    fun `an extractable field that is genuinely absent still fails`() {
        // The guard must not turn every absence into NOT_ASSESSABLE.
        val evaluation = RulesEngine.evaluate(ruleset, emptyMap())
        val mrp = evaluation.findings.first { it.ruleId == "MRP-01" }

        assertEquals(RuleStatus.FAIL, mrp.status)
    }

    @Test
    fun `a finding with no observation claims no confidence`() {
        val evaluation = RulesEngine.evaluate(ruleset, emptyMap())
        val mrp = evaluation.findings.first { it.ruleId == "MRP-01" }

        // Defaulting to 1f claimed maximum certainty for a finding backed by
        // no evidence at all.
        assertEquals(0f, mrp.confidence, 0.0001f)
    }

    @Test
    fun `a finding with an observation carries its agreement confidence`() {
        val evaluation = RulesEngine.evaluate(
            ruleset,
            mapOf("mrp" to Consensus.AgreedField("45.00", 0.6f, Box(0, 0, 5, 5), 3, 5))
        )
        val mrp = evaluation.findings.first { it.ruleId == "MRP-01" }

        assertEquals(0.6f, mrp.confidence, 0.0001f)
    }

    @Test
    fun `every exemption targets a rule that exists`() {
        val ids = ruleset.rules.map { it.id }.toSet()
        ruleset.exemptions.forEach { exemption ->
            exemption.exempts.forEach {
                assertTrue("$it exempted by ${exemption.id} is not a rule", it in ids)
            }
        }
    }

    @Test
    fun `every exemption condition names an extractable field`() {
        ruleset.exemptions.forEach {
            assertTrue(
                "${it.id} conditions on '${it.condition.field}', which has no extractor",
                it.condition.field in FieldExtractor.SUPPORTED_FIELDS
            )
        }
    }
}
