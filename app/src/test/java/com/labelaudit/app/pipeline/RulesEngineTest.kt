package com.labelaudit.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RulesEngineTest {

    private val ruleset: Ruleset =
        Ruleset.load(File("src/main/assets/ruleset.yaml").inputStream())

    private fun field(value: String, agreement: Int = 5, frames: Int = 5) =
        Consensus.AgreedField(
            value = value,
            confidence = agreement.toFloat() / frames,
            box = Box(5, 5, 50, 25),
            agreement = agreement,
            frames = frames
        )

    private fun findingFor(evaluation: Evaluation, ruleId: String): Finding =
        evaluation.findings.first { it.ruleId == ruleId }

    // ------------------------------------------------------------ structure

    @Test
    fun `ruleset declares the required top level keys`() {
        assertTrue(ruleset.version.isNotBlank())
        assertTrue(ruleset.sourceCitation.isNotBlank())
        assertEquals("cap_height", ruleset.heightMetric)
    }

    @Test
    fun `every rule carries a citation`() {
        ruleset.rules.forEach { assertTrue("${it.id} has no citation", it.citation.isNotBlank()) }
    }

    @Test
    fun `every exemption carries a citation`() {
        ruleset.exemptions.forEach {
            assertTrue("${it.id} has no citation", it.citation.isNotBlank())
        }
    }

    @Test
    fun `a rule without a citation is refused at load`() {
        val yaml = """
            version: "1.0"
            source_citation: "test"
            height_metric: cap_height
            rules:
              - id: BAD
                field: mrp
                check:
                  type: field_present
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) { Ruleset.parse(yaml) }
    }

    // --------------------------------------------------- finding invariants

    @Test
    fun `a finding requires a citation`() {
        assertThrows(IllegalArgumentException::class.java) {
            Finding("X", "", Box.EMPTY, 0.9f, RuleStatus.PASS, "mrp")
        }
    }

    @Test
    fun `a finding requires a rule id`() {
        assertThrows(IllegalArgumentException::class.java) {
            Finding("", "cite", Box.EMPTY, 0.9f, RuleStatus.PASS, "mrp")
        }
    }

    @Test
    fun `a not assessable finding requires a reason`() {
        assertThrows(IllegalArgumentException::class.java) {
            Finding("X", "cite", Box.EMPTY, 0.5f, RuleStatus.NOT_ASSESSABLE, "mrp")
        }
    }

    @Test
    fun `confidence must be a probability`() {
        assertThrows(IllegalArgumentException::class.java) {
            Finding("X", "cite", Box.EMPTY, 1.4f, RuleStatus.PASS, "mrp")
        }
    }

    @Test
    fun `every emitted finding carries the required four`() {
        val evaluation = RulesEngine.evaluate(ruleset, mapOf("mrp" to field("45.00")))

        assertTrue(evaluation.findings.isNotEmpty())
        evaluation.findings.forEach {
            assertTrue(it.ruleId.isNotBlank())
            assertTrue(it.citation.isNotBlank())
            assertTrue(it.confidence in 0f..1f)
            if (it.status == RuleStatus.NOT_ASSESSABLE) {
                assertFalse(it.reason.isNullOrBlank())
            }
        }
    }

    // -------------------------------------------------- exemptions run first

    @Test
    fun `a small pack exempts consumer care`() {
        val evaluation = RulesEngine.evaluate(
            ruleset,
            mapOf("mrp" to field("45.00"), "net_quantity" to field("8 g"))
        )
        val care = findingFor(evaluation, "CARE-01")

        assertEquals(RuleStatus.EXEMPT, care.status)
        assertTrue(care.citation.contains("r. 26"))
        assertEquals("EX-SMALL-PACK", care.evidence["exemption_id"])
    }

    @Test
    fun `a large pack is not exempt`() {
        val evaluation = RulesEngine.evaluate(
            ruleset,
            mapOf("mrp" to field("45.00"), "net_quantity" to field("500 g"))
        )
        assertEquals(RuleStatus.FAIL, findingFor(evaluation, "CARE-01").status)
    }

    @Test
    fun `an absent quantity does not grant an exemption`() {
        // A missing field must never be read as satisfying a condition.
        val evaluation = RulesEngine.evaluate(ruleset, mapOf("mrp" to field("45.00")))
        assertNotEquals(RuleStatus.EXEMPT, findingFor(evaluation, "CARE-01").status)
    }

    @Test
    fun `millilitres do not satisfy a gram exemption`() {
        val evaluation = RulesEngine.evaluate(
            ruleset,
            mapOf("mrp" to field("45.00"), "net_quantity" to field("8 ml"))
        )
        // EX-SMALL-PACK-ML covers this, so it is still exempt — but via the
        // millilitre exemption, not the gram one.
        val care = findingFor(evaluation, "CARE-01")
        assertEquals(RuleStatus.EXEMPT, care.status)
        assertEquals("EX-SMALL-PACK-ML", care.evidence["exemption_id"])
    }

    // ------------------------------------------------------- substantiation

    @Test
    fun `an unpopulated registry yields not applicable rather than pass`() {
        val evaluation = RulesEngine.evaluate(ruleset, mapOf("mrp" to field("45.00")))
        val check = findingFor(evaluation, "MRP-02")

        // Not NOT_ASSESSABLE: having no registered SKU is a fact about the
        // deployment, not about this photograph, and must not suppress
        // violations found elsewhere on the pack.
        assertEquals(RuleStatus.NOT_APPLICABLE, check.status)
        assertEquals("registry_unpopulated", check.reason)
        assertNotEquals(RuleStatus.PASS, check.status)
    }

    @Test
    fun `an inapplicable check does not suppress a real violation`() {
        val evaluation = RulesEngine.evaluate(
            ruleset,
            mapOf("mrp" to field("45.00"))   // nothing declares a net quantity
        )

        assertEquals(RuleStatus.FAIL, findingFor(evaluation, "QTY-01").status)
        assertEquals(Verdict.FAIL, evaluation.verdict)
    }

    @Test
    fun `a scan where nothing could be evaluated is not assessable`() {
        // Every check inapplicable means the pack was not assessed at all;
        // reporting PASS would claim an audit that never happened.
        val allInapplicable = ruleset.copy(
            rules = ruleset.rules.filter { it.check.type == "matches_registry" }
        )
        assertEquals(
            Verdict.NOT_ASSESSABLE,
            RulesEngine.evaluate(allInapplicable, emptyMap()).verdict
        )
    }

    @Test
    fun `the height rule is deferred, so a scan is not permanently unassessable`() {
        // CAP-01 needs a millimetre scale, which the chips prototype has no
        // practical way to provide. Left active it would report
        // height_not_measured on every scan, and since NOT_ASSESSABLE outranks
        // both PASS and FAIL, one permanently unmeasurable rule would suppress
        // every verdict the pipeline can legitimately reach.
        assertTrue(
            "CAP-01 is active again; confirm a scale source exists first",
            ruleset.rules.none { it.id == "CAP-01" }
        )
    }

    @Test
    fun `a populated registry can now reach a definitive verdict`() {
        // The point of deferring the height rule: with the registry filled in,
        // a clean label reaches PASS instead of being stuck NOT_ASSESSABLE.
        val populated = ruleset.copy(
            registry = ruleset.registry.copy(
                populated = true,
                brandStrings = listOf("TASTY OATS"),
                mrpExact = 45.0,
                netQuantity = "500 g"
            )
        )
        val evaluation = RulesEngine.evaluate(
            populated,
            mapOf(
                "mrp" to field("45.00"),
                "net_quantity" to field("500 g"),
                "consumer_care" to field("care@acme.in"),
                "manufacturer_address" to field("Manufactured by Acme Foods"),
                "tax_inclusive" to field("incl. of all taxes"),
                "mfg_date" to field("06/2026"),
                "batch_number" to field("B2411"),
                "expiry" to field("08/2026"),
                "fssai_licence" to field("11422334455667"),
                "brand" to field("TASTY OATS")
            ),
            RulesEngine.Context(
                addressRoles = mapOf(
                    FieldExtractor.AddressRole.MANUFACTURER to listOf("Manufactured by Acme")
                )
            )
        )

        assertEquals(Verdict.PASS, evaluation.verdict)
        assertTrue(
            "expected every check to pass, got " +
                evaluation.findings.filter { it.status != RuleStatus.PASS }
                    .map { it.ruleId to it.status },
            evaluation.findings.all { it.status == RuleStatus.PASS }
        )
    }

    @Test
    fun `the height check still works when a rule supplies a measurement`() {
        // The check type stays covered so it is known-good whenever CAP-01
        // is restored alongside a scale source.
        val withHeightRule = ruleset.copy(
            rules = listOf(
                Ruleset.Rule(
                    id = "TEST-HEIGHT",
                    field = "mrp",
                    check = Ruleset.Check(
                        type = "min_height_mm",
                        minMm = 2.0,
                        needsLegalConfirmation = false
                    ),
                    citation = "test citation"
                )
            )
        )

        val tall = RulesEngine.evaluate(
            withHeightRule, mapOf("mrp" to field("45.00")),
            RulesEngine.Context(capHeightsMm = mapOf("mrp" to 3.0))
        )
        assertEquals(RuleStatus.PASS, findingFor(tall, "TEST-HEIGHT").status)

        val short = RulesEngine.evaluate(
            withHeightRule, mapOf("mrp" to field("45.00")),
            RulesEngine.Context(capHeightsMm = mapOf("mrp" to 1.0))
        )
        assertEquals(RuleStatus.FAIL, findingFor(short, "TEST-HEIGHT").status)

        val unmeasured = RulesEngine.evaluate(
            withHeightRule, mapOf("mrp" to field("45.00"))
        )
        val finding = findingFor(unmeasured, "TEST-HEIGHT")
        assertEquals(RuleStatus.NOT_ASSESSABLE, finding.status)
        assertEquals("height_not_measured", finding.reason)
    }

    @Test
    fun `an unconfirmed threshold yields needs review rather than fail`() {
        val unconfirmed = ruleset.copy(
            rules = listOf(
                Ruleset.Rule(
                    id = "TEST-UNCONFIRMED",
                    field = "mrp",
                    check = Ruleset.Check(
                        type = "min_height_mm",
                        minMm = 2.0,
                        needsLegalConfirmation = true
                    ),
                    citation = "test citation"
                )
            )
        )
        val evaluation = RulesEngine.evaluate(
            unconfirmed, mapOf("mrp" to field("45.00")),
            RulesEngine.Context(capHeightsMm = mapOf("mrp" to 0.5))
        )
        val finding = findingFor(evaluation, "TEST-UNCONFIRMED")

        // 0.5 mm is below the 2.0 mm minimum, yet nobody has confirmed that
        // minimum, so the engine must not assert a violation.
        assertEquals(RuleStatus.NEEDS_REVIEW, finding.status)
        assertNotEquals(RuleStatus.FAIL, finding.status)
    }


    @Test
    fun `an untagged address does not satisfy the manufacturer rule`() {
        val evaluation = RulesEngine.evaluate(
            ruleset,
            mapOf("mrp" to field("45.00"), "net_quantity" to field("500 g")),
            RulesEngine.Context(
                addressRoles = mapOf(
                    FieldExtractor.AddressRole.UNKNOWN to listOf("Acme Foods, Jaipur")
                )
            )
        )
        val mfr = findingFor(evaluation, "MFR-01")

        assertEquals(RuleStatus.NEEDS_REVIEW, mfr.status)
        assertNotEquals(RuleStatus.PASS, mfr.status)
    }

    @Test
    fun `an anchored manufacturer address passes`() {
        val evaluation = RulesEngine.evaluate(
            ruleset,
            mapOf("mrp" to field("45.00"), "net_quantity" to field("500 g")),
            RulesEngine.Context(
                addressRoles = mapOf(
                    FieldExtractor.AddressRole.MANUFACTURER to
                        listOf("Manufactured by Acme Foods")
                )
            )
        )
        assertEquals(RuleStatus.PASS, findingFor(evaluation, "MFR-01").status)
    }

    @Test
    fun `no address at all is a failure`() {
        val evaluation = RulesEngine.evaluate(
            ruleset,
            mapOf("mrp" to field("45.00"), "net_quantity" to field("500 g"))
        )
        assertEquals(RuleStatus.FAIL, findingFor(evaluation, "MFR-01").status)
    }

    @Test
    fun `a populated registry can match and mismatch`() {
        val populated = ruleset.copy(
            registry = ruleset.registry.copy(
                populated = true,
                mrpExact = 45.0,
                netQuantity = "500 g"
            )
        )

        val matching = RulesEngine.evaluate(populated, mapOf("mrp" to field("45.00")))
        assertEquals(RuleStatus.PASS, findingFor(matching, "MRP-02").status)

        val mismatched = RulesEngine.evaluate(populated, mapOf("mrp" to field("61.00")))
        assertEquals(RuleStatus.FAIL, findingFor(mismatched, "MRP-02").status)
    }

    @Test
    fun `registry matching uses normalised comparison`() {
        val populated = ruleset.copy(
            registry = ruleset.registry.copy(populated = true, netQuantity = "500 g")
        )
        // "0.5 kg" on the label must match a registered "500 g".
        val evaluation = RulesEngine.evaluate(
            populated,
            mapOf("net_quantity" to field("0.5 kg"))
        )
        assertEquals(RuleStatus.PASS, findingFor(evaluation, "QTY-02").status)
    }

    // ------------------------------------------------------------- verdicts

    private fun finding(status: RuleStatus, id: String = "X") = Finding(
        ruleId = id,
        citation = "cite",
        cropBox = Box.EMPTY,
        confidence = 0.9f,
        status = status,
        field = "mrp",
        reason = if (status == RuleStatus.NOT_ASSESSABLE) "reason" else null
    )

    @Test
    fun `all pass is pass`() {
        assertEquals(Verdict.PASS, RulesEngine.deriveVerdict(listOf(finding(RuleStatus.PASS))))
    }

    @Test
    fun `exempt does not block pass`() {
        assertEquals(
            Verdict.PASS,
            RulesEngine.deriveVerdict(
                listOf(finding(RuleStatus.PASS), finding(RuleStatus.EXEMPT, "Y"))
            )
        )
    }

    @Test
    fun `any fail is fail`() {
        assertEquals(
            Verdict.FAIL,
            RulesEngine.deriveVerdict(
                listOf(finding(RuleStatus.PASS), finding(RuleStatus.FAIL, "Y"))
            )
        )
    }

    @Test
    fun `not assessable outranks fail`() {
        // Partial assessment must not be reported as a definitive failure.
        assertEquals(
            Verdict.NOT_ASSESSABLE,
            RulesEngine.deriveVerdict(
                listOf(
                    finding(RuleStatus.FAIL, "Y"),
                    finding(RuleStatus.NOT_ASSESSABLE, "Z")
                )
            )
        )
    }

    @Test
    fun `needs review when nothing worse`() {
        assertEquals(
            Verdict.NEEDS_REVIEW,
            RulesEngine.deriveVerdict(
                listOf(finding(RuleStatus.PASS), finding(RuleStatus.NEEDS_REVIEW, "Y"))
            )
        )
    }

    @Test
    fun `no findings is not assessable`() {
        assertEquals(Verdict.NOT_ASSESSABLE, RulesEngine.deriveVerdict(emptyList()))
    }

    @Test
    fun `a pass states which checks did not apply`() {
        // The whole-pipeline guarantee: with no registered SKU values, a
        // clean label still cannot be declared compliant.
        val evaluation = RulesEngine.evaluate(
            ruleset,
            mapOf(
                "mrp" to field("45.00"),
                "net_quantity" to field("500 g"),
                "consumer_care" to field("care@acme.in"),
                "brand" to field("TASTY OATS")
            ),
            RulesEngine.Context(
                addressRoles = mapOf(
                    FieldExtractor.AddressRole.MANUFACTURER to listOf("Manufactured by Acme")
                )
            )
        )

        // A PASS reached with checks skipped must say so, or it reads as a
        // complete audit that it is not.
        val report = com.labelaudit.app.report.ScanReport.from(
            evaluation = evaluation,
            crops = emptyMap(),
            consensus = Consensus.Result(emptyMap(), emptyMap()),
            framesUsed = 5, framesGated = 0, elapsedMs = 100
        )
        assertTrue(
            "explanation must name the skipped checks: " + report.verdictExplanation,
            report.verdictExplanation.contains("did not apply")
        )
    }
}
