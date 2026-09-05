package com.labelguard.app.pipeline

import com.labelguard.app.measure.Scale
import java.time.LocalDate

/**
 * Deterministic rules engine.
 *
 * Order of evaluation is load-bearing:
 *   1. Exemptions. A rule exempted for this package is reported EXEMPT with
 *      the exemption's citation and never evaluated.
 *   2. Rules. Each produces exactly one Finding.
 *
 * Two guarantees enforced rather than documented:
 *
 *   * No finding is emitted without ruleId, citation, cropBox and confidence
 *     — Finding's constructor throws if any is missing.
 *   * No rule asserts PASS or FAIL on evidence it does not have. Missing
 *     input, an unpopulated registry, or an unconfirmed statutory threshold
 *     all yield NOT_ASSESSABLE or NEEDS_REVIEW — never a silent pass.
 */
object RulesEngine {

    /**
     * Evidence available to the checks beyond the agreed field values.
     *
     * [capHeights] is empty when the device could not supply usable optics,
     * so height rules report NOT_ASSESSABLE rather than guessing a millimetre
     * figure. Each entry is a range, not a number — see [Scale].
     */
    data class Context(
        val addressRoles: Map<FieldExtractor.AddressRole, List<String>> = emptyMap(),
        val capHeights: Map<String, Scale.Measurement> = emptyMap(),
        /**
         * Why this device cannot measure character height at all, if it
         * cannot.
         *
         * The distinction matters as much here as it does for the registry. A
         * phone that does not report usable optics makes the height rules
         * NOT_APPLICABLE — a fact about the equipment, which must not suppress
         * a verdict the rest of the pipeline reached. A phone that can measure
         * but produced no box for this field is NOT_ASSESSABLE, a fact about
         * the photograph, and that genuinely does leave the pack unassessed.
         */
        val heightScaleUnavailable: String? = null,
        /**
         * The day the scan is judged against, injected rather than read from
         * the clock so date findings are reproducible — a test that depended
         * on today's real date would start failing on its own.
         *
         * In production this is the *device* clock, which is worth
         * remembering: a phone set wrongly makes every date finding wrong, so
         * the checks assert only what the range proves beyond doubt.
         */
        val today: LocalDate = LocalDate.now()
    )

    fun evaluate(
        ruleset: Ruleset,
        fields: Map<String, Consensus.AgreedField>,
        context: Context = Context()
    ): Evaluation {
        val exempted = resolveExemptions(ruleset, fields)
        val findings = mutableListOf<Finding>()

        for (rule in ruleset.rules) {
            val exemption = exempted[rule.id]
            if (exemption != null) {
                findings += Finding(
                    ruleId = rule.id,
                    citation = exemption.citation,
                    cropBox = Box.EMPTY,
                    confidence = 1f,
                    status = RuleStatus.EXEMPT,
                    field = rule.field,
                    ruleName = rule.name,
                    message = "Exempt under ${exemption.id}",
                    evidence = mapOf("exemption_id" to exemption.id)
                )
                continue
            }

            val observed = fields[rule.field]
            val outcome = when (rule.check.type) {
                "field_present" -> checkFieldPresent(rule, observed)
                "matches_registry" -> checkMatchesRegistry(rule, observed, ruleset)
                "role_present" -> checkRolePresent(rule, context)
                "date_marking" -> checkDateMarking(rule, observed, fields)
                "date_not_future" -> checkDateNotFuture(rule, observed, context)
                "date_order" -> checkDateOrder(rule, observed, fields)
                "not_expired" -> checkNotExpired(rule, observed, fields, context)
                "min_height_mm" -> checkMinHeight(rule, context)
                else -> throw IllegalArgumentException(
                    "rule ${rule.id} uses unknown check type '${rule.check.type}'"
                )
            }

            findings += Finding(
                ruleId = rule.id,
                citation = rule.citation,
                cropBox = observed?.box ?: Box.EMPTY,
                // No observation means no measured confidence. Defaulting to
                // 1f claimed maximum certainty for a finding backed by no
                // evidence at all, which is exactly backwards.
                confidence = observed?.confidence ?: 0f,
                status = outcome.status,
                field = rule.field,
                ruleName = rule.name,
                message = outcome.message,
                reason = outcome.reason,
                observedValue = observed?.value,
                evidence = buildMap {
                    put("check", rule.check.type)
                    observed?.let {
                        put("agreement", "${it.agreement}/${it.frames} frames")
                    }
                }
            )
        }

        return Evaluation(
            verdict = deriveVerdict(findings),
            findings = findings,
            rulesetVersion = ruleset.version,
            sourceCitation = ruleset.sourceCitation
        )
    }

    // ---------------------------------------------------------- exemptions

    /**
     * A condition only applies when its field is actually present and
     * comparable. An absent field must never silently grant an exemption.
     */
    private fun resolveExemptions(
        ruleset: Ruleset,
        fields: Map<String, Consensus.AgreedField>
    ): Map<String, Ruleset.Exemption> {
        val exempted = mutableMapOf<String, Ruleset.Exemption>()

        for (exemption in ruleset.exemptions) {
            if (!conditionApplies(exemption.condition, fields)) continue
            for (ruleId in exemption.exempts) exempted.putIfAbsent(ruleId, exemption)
        }
        return exempted
    }

    private fun conditionApplies(
        condition: Ruleset.Condition,
        fields: Map<String, Consensus.AgreedField>
    ): Boolean {
        val observed = fields[condition.field] ?: return false

        if (condition.unit != null) {
            val parsed = Normalize.quantity(observed.value) ?: return false
            val target = Normalize.quantity("${condition.value} ${condition.unit}")
                ?: return false
            // Comparing grams against millilitres would be meaningless.
            if (parsed.second != target.second) return false
            return compare(condition.op, parsed.first, target.first)
        }

        val magnitude = observed.value.toDoubleOrNull() ?: return false
        return compare(condition.op, magnitude, condition.value)
    }

    private fun compare(op: String, a: Double, b: Double): Boolean = when (op) {
        "lte" -> a <= b
        "lt" -> a < b
        "gte" -> a >= b
        "gt" -> a > b
        "eq" -> a == b
        else -> false
    }

    // -------------------------------------------------------------- checks

    private data class Outcome(
        val status: RuleStatus,
        val message: String,
        val reason: String? = null
    )

    /**
     * "The pipeline cannot look for this" is not "the label does not declare
     * it". A rule whose field has no extractor must refuse, not accuse.
     */
    private fun notExtractable(field: String): Outcome? =
        if (field in FieldExtractor.SUPPORTED_FIELDS) {
            null
        } else {
            Outcome(
                RuleStatus.NOT_APPLICABLE,
                "The pipeline has no extractor for '$field'",
                "field_not_extractable"
            )
        }

    private fun checkFieldPresent(
        rule: Ruleset.Rule,
        observed: Consensus.AgreedField?
    ): Outcome {
        if (observed != null) {
            // A printed caption with nothing after it — "MFG. DATE :" — is a
            // declaration the pack makes and does not honour. It fails, and
            // says so differently from a caption that is absent entirely.
            if (observed.anchorOnly) {
                return Outcome(
                    RuleStatus.FAIL,
                    "The caption is printed but no value follows it. Confirm it is blank on the pack and not merely outside the photographed area."
                )
            }
            // The value is already shown once, above the checks; repeating
            // it here made one declaration look like two readings.
            return Outcome(RuleStatus.PASS, "Declared")
        }
        return notExtractable(rule.field)
            ?: Outcome(RuleStatus.FAIL, "Not found on the label")
    }

    private fun checkMatchesRegistry(
        rule: Ruleset.Rule,
        observed: Consensus.AgreedField?,
        ruleset: Ruleset
    ): Outcome {
        if (!ruleset.registry.populated) {
            // No SKU registered for this deployment, so there is nothing to
            // compare against. That is a configuration state, not a failure to
            // read the label, and must not suppress the violations that were
            // substantiated.
            return Outcome(
                RuleStatus.NOT_APPLICABLE,
                "No SKU registered, so there is nothing to compare against",
                "registry_unpopulated"
            )
        }
        if (observed == null) {
            return notExtractable(rule.field)
                ?: Outcome(RuleStatus.FAIL, "Not found on the label")
        }
        if (observed.anchorOnly) {
            return Outcome(
                RuleStatus.FAIL,
                "The caption is printed but no value follows it. Confirm it is blank on the pack and not merely outside the photographed area."
            )
        }

        val key = rule.check.registryKey
            ?: return Outcome(
                RuleStatus.NOT_ASSESSABLE,
                "Rule has no registry key",
                "registry_key_missing"
            )

        val expected = ruleset.registry.valueFor(key)
            ?: return Outcome(
                RuleStatus.NOT_ASSESSABLE,
                "Registry entry '$key' is empty",
                "registry_unpopulated"
            )

        val candidates = if (expected is List<*>) expected else listOf(expected)
        val matched = candidates.any { Normalize.valuesEqual(rule.field, observed.value, it) }

        if (matched) {
            return Outcome(RuleStatus.PASS, "${rule.field} matches the registered value")
        }

        // The pack and the reference disagree. Which of them is wrong depends
        // entirely on where the reference came from: a brand's product master
        // settles it, a value somebody read off another pack does not.
        return when (ruleset.registry.authority) {
            Ruleset.Authority.AUTHORITATIVE -> Outcome(
                RuleStatus.FAIL,
                "Label shows ${observed.value}; registered value is $expected"
            )

            Ruleset.Authority.ASSERTED -> Outcome(
                RuleStatus.NEEDS_REVIEW,
                "Label shows ${observed.value}; the registered reference says " +
                    "$expected. That reference was recorded from a scanned " +
                    "pack rather than an authoritative source, so which one is " +
                    "wrong cannot be settled here."
            )
        }
    }

    /**
     * Date marking that a consumer can actually resolve to a date.
     *
     * "Best before 2 months from the date of packing" is only a date marking
     * if the packing date is declared. On a pack that prints the phrase and
     * leaves MFG. DATE blank, the consumer is given no determinable date at
     * all, so the declaration fails on its own terms — not merely because a
     * separate date rule also fails.
     */
    private fun checkDateMarking(
        rule: Ruleset.Rule,
        observed: Consensus.AgreedField?,
        fields: Map<String, Consensus.AgreedField>
    ): Outcome {
        if (observed == null) {
            return notExtractable(rule.field)
                ?: Outcome(RuleStatus.FAIL, "Not found on the label")
        }
        if (observed.anchorOnly) {
            return Outcome(
                RuleStatus.FAIL,
                "The caption is printed but no value follows it. Confirm it is blank on the pack and not merely outside the photographed area."
            )
        }

        if (observed.relative) {
            val anchorField = rule.check.relativeRequires
                ?: return Outcome(RuleStatus.PASS, "Declared")
            val anchor = fields[anchorField]

            if (anchor == null || anchor.anchorOnly) {
                val why = if (anchor == null) "is not declared" else "is left blank"
                return Outcome(
                    RuleStatus.FAIL,
                    "\"${observed.value}\" is stated relative to $anchorField, which " +
                        "$why, so no date can be determined from the label"
                )
            }
        }

        return Outcome(RuleStatus.PASS, "Declared")
    }

    /**
     * The date a field resolves to, following a relative marking back to the
     * date it counts from.
     *
     * "Best before 9 months from packing" is not a date until the packing
     * date is known; once it is, it becomes one, and a pack whose stated
     * shelf life has run out is a substantiated finding rather than a
     * shrugged NEEDS_REVIEW.
     */
    private fun resolveDate(
        observed: Consensus.AgreedField?,
        fields: Map<String, Consensus.AgreedField>,
        relativeTo: String?
    ): LabelDate? {
        if (observed == null || observed.anchorOnly || observed.value.isBlank()) return null

        LabelDate.parse(observed.value)?.let { return it }

        // No absolute date in the text; try it as a period counted from
        // another declared date.
        val period = LabelDate.parsePeriod(observed.value) ?: return null
        val anchorField = relativeTo ?: return null
        val anchor = fields[anchorField]?.takeIf { !it.anchorOnly } ?: return null
        val anchorDate = LabelDate.parse(anchor.value) ?: return null
        return anchorDate + period
    }

    /**
     * A declared date that has not happened yet.
     *
     * Only asserted when the *earliest* day the marking could mean is still
     * ahead. "12/2025" scanned in December 2025 could name any day of that
     * month, several of them already past, so it is not a violation.
     */
    private fun checkDateNotFuture(
        rule: Ruleset.Rule,
        observed: Consensus.AgreedField?,
        context: Context
    ): Outcome {
        if (observed == null || observed.anchorOnly) {
            // Absence is MFG-01's finding to report, not this rule's.
            return Outcome(
                RuleStatus.NOT_APPLICABLE,
                "No date declared to check",
                "no_date_declared"
            )
        }

        val date = LabelDate.parse(observed.value)
            ?: return Outcome(
                RuleStatus.NOT_ASSESSABLE,
                "\"" + observed.value + "\" could not be read as a date",
                "date_unparsed"
            )

        return when {
            date.earliest.isAfter(context.today) -> Outcome(
                RuleStatus.FAIL,
                "Declared as ${date.describe()}, which has not happened yet " +
                    "(scanned ${context.today}). Confirm the device date is correct."
            )
            date.possiblyAfter(context.today) -> Outcome(
                RuleStatus.NEEDS_REVIEW,
                "Declared as ${date.describe()}; part of that range is still " +
                    "ahead of ${context.today}, so it cannot be settled from the label alone"
            )
            else -> Outcome(RuleStatus.PASS, "Declared as ${date.describe()}")
        }
    }

    /**
     * One date must fall after another — an expiry cannot precede the
     * manufacture it is measured from.
     */
    private fun checkDateOrder(
        rule: Ruleset.Rule,
        observed: Consensus.AgreedField?,
        fields: Map<String, Consensus.AgreedField>
    ): Outcome {
        val afterField = rule.check.afterField
            ?: return Outcome(
                RuleStatus.NOT_ASSESSABLE,
                "Rule names no field to compare against",
                "order_unconfigured"
            )

        val later = resolveDate(observed, fields, afterField)
        val earlier = resolveDate(fields[afterField], fields, null)

        if (later == null || earlier == null) {
            return Outcome(
                RuleStatus.NOT_APPLICABLE,
                "Both dates must be readable to compare them",
                "date_pair_incomplete"
            )
        }

        return when {
            earlier.certainlyAfter(later) -> Outcome(
                RuleStatus.FAIL,
                "${rule.field} (${later.describe()}) is before $afterField " +
                    "(${earlier.describe()}), which cannot be right"
            )
            later.certainlyAfter(earlier) || later.earliest == earlier.earliest ->
                Outcome(RuleStatus.PASS, "${later.describe()} follows ${earlier.describe()}")
            else -> Outcome(
                RuleStatus.NEEDS_REVIEW,
                "${later.describe()} and ${earlier.describe()} overlap, so the " +
                    "order cannot be settled from the label"
            )
        }
    }

    /**
     * The pack is still within the date marking it declares.
     *
     * Only asserted when the *latest* day the marking could mean has gone —
     * a pack marked "12/2025" is not expired on 15 December 2025.
     */
    private fun checkNotExpired(
        rule: Ruleset.Rule,
        observed: Consensus.AgreedField?,
        fields: Map<String, Consensus.AgreedField>,
        context: Context
    ): Outcome {
        if (observed == null || observed.anchorOnly) {
            return Outcome(
                RuleStatus.NOT_APPLICABLE,
                "No date marking declared to check",
                "no_date_declared"
            )
        }

        val date = resolveDate(observed, fields, rule.check.relativeRequires)
            ?: return Outcome(
                RuleStatus.NOT_ASSESSABLE,
                "\"" + observed.value + "\" could not be resolved to a date",
                "date_unresolved"
            )

        return when {
            date.certainlyBefore(context.today) -> Outcome(
                RuleStatus.FAIL,
                "Date marking ${date.describe()} passed before ${context.today}"
            )
            date.earliest.isBefore(context.today) -> Outcome(
                RuleStatus.NEEDS_REVIEW,
                "Date marking ${date.describe()} may have passed as at ${context.today}"
            )
            else -> Outcome(RuleStatus.PASS, "Within ${date.describe()}")
        }
    }

    private fun checkRolePresent(rule: Ruleset.Rule, context: Context): Outcome {
        val role = rule.check.role
            ?: return Outcome(
                RuleStatus.NOT_ASSESSABLE,
                "Rule has no role",
                "role_missing"
            )

        val target = FieldExtractor.AddressRole.entries
            .firstOrNull { it.name.equals(role, ignoreCase = true) }
            ?: return Outcome(
                RuleStatus.NOT_ASSESSABLE,
                "Unknown role '$role'",
                "role_unknown"
            )

        if (!context.addressRoles[target].isNullOrEmpty()) {
            return Outcome(RuleStatus.PASS, "Address declared for role '$role'")
        }

        // An untagged block exists but does not say it is the manufacturer.
        // Reviewable, not a violation we can assert.
        if (!context.addressRoles[FieldExtractor.AddressRole.UNKNOWN].isNullOrEmpty()) {
            return Outcome(
                RuleStatus.NEEDS_REVIEW,
                "An address was found but carries no '$role' anchor"
            )
        }

        return Outcome(RuleStatus.FAIL, "No address declared for role '$role'")
    }

    /**
     * Printed character height against a statutory minimum.
     *
     * The measurement is a range, because the scale it rests on comes from
     * the phone's reported focus distance rather than a ruler. A violation is
     * asserted only when the entire range falls short; a range that straddles
     * the threshold is a question this photograph did not answer.
     *
     * Two separate gates hold this back from asserting anything at all:
     * `needs_legal_confirmation` on the threshold, and `needs_calibration` on
     * the tolerance the range is built from. While either stands, the finding
     * is capped at NEEDS_REVIEW — the measurement is reported and a person
     * decides. Neither an unverified statutory figure nor an unvalidated
     * device tolerance may produce an accusation on its own.
     */
    private fun provenanceOf(measured: Scale.Measurement): String =
        when (measured.source) {
            Scale.Source.CALIBRATED_DEVICE -> " (calibrated camera)"
            Scale.Source.REPORTED_OPTICS -> " (uncalibrated; the device's own estimate)"
        }

    private fun checkMinHeight(rule: Ruleset.Rule, context: Context): Outcome {
        val measured = context.capHeights[rule.field] ?: return context
            .heightScaleUnavailable
            ?.let {
                Outcome(
                    RuleStatus.NOT_APPLICABLE,
                    "Character height cannot be measured because " + it,
                    "height_scale_unavailable"
                )
            }
            ?: Outcome(
                RuleStatus.NOT_ASSESSABLE,
                "No character box was read for " + rule.field,
                "height_not_measured"
            )

        val minMm = rule.check.minMm
            ?: return Outcome(
                RuleStatus.NOT_ASSESSABLE,
                "No minimum height configured",
                "threshold_unconfigured"
            )

        val unconfirmed = buildList {
            if (rule.check.needsLegalConfirmation) add("the statutory minimum is unverified")
            if (rule.check.needsCalibration) add("the measurement tolerance is unvalidated")
        }

        if (unconfirmed.isNotEmpty()) {
            return Outcome(
                RuleStatus.NEEDS_REVIEW,
                "Measured ${measured.describe()}${provenanceOf(measured)} against " +
                    "a minimum of %.2f mm. Reported for review because ".format(minMm) +
                    unconfirmed.joinToString(" and ") + "."
            )
        }

        // Where the figure came from belongs in the finding: an uncalibrated
        // reading and a calibrated one differ by sixfold in precision, and a
        // reader comparing two reports has no other way to tell them apart.
        val provenance = provenanceOf(measured)

        return when {
            measured.certainlyAtLeast(minMm) -> Outcome(
                RuleStatus.PASS,
                "Measured ${measured.describe()}, at or above %.2f mm".format(minMm) +
                    provenance
            )
            measured.certainlyBelow(minMm) -> Outcome(
                RuleStatus.FAIL,
                "Measured ${measured.describe()}, entirely below %.2f mm".format(minMm) +
                    provenance
            )
            else -> Outcome(
                RuleStatus.NEEDS_REVIEW,
                "Measured ${measured.describe()}, which spans the %.2f mm "
                    .format(minMm) + "minimum; the photograph cannot settle it" + provenance
            )
        }
    }

    // ------------------------------------------------------------- verdict

    /**
     * Collapse findings to one verdict, worst-first.
     *
     * FAIL outranks NOT_ASSESSABLE: a violation the pipeline substantiated
     * stays substantiated even though some other check could not run. The
     * report names what went unassessed so the verdict is not read as a
     * complete audit.
     */
    fun deriveVerdict(findings: List<Finding>): Verdict {
        if (findings.isEmpty()) return Verdict.NOT_ASSESSABLE
        val statuses = findings.map { it.status }.toSet()

        // A package where nothing could actually be evaluated has not been
        // assessed, however many checks were skipped as inapplicable.
        val evaluated = findings.count {
            it.status != RuleStatus.NOT_APPLICABLE && it.status != RuleStatus.EXEMPT
        }
        if (evaluated == 0) return Verdict.NOT_ASSESSABLE

        return when {
            // A substantiated violation is not retracted by a check that
            // could not run. Failing to read the expiry does not un-prove
            // that the price is absent, and reporting NOT_ASSESSABLE over a
            // proven FAIL would claim nothing was learned when two things
            // were. The report carries [ScanReport.partialAssessmentCaveat]
            // so the verdict is never read as a complete audit.
            RuleStatus.FAIL in statuses -> Verdict.FAIL
            RuleStatus.NOT_ASSESSABLE in statuses -> Verdict.NOT_ASSESSABLE
            RuleStatus.NEEDS_REVIEW in statuses -> Verdict.NEEDS_REVIEW
            else -> Verdict.PASS
        }
    }
}
