package com.labelaudit.app.report

import android.graphics.Bitmap
import com.labelaudit.app.pipeline.Consensus
import com.labelaudit.app.pipeline.Evaluation
import com.labelaudit.app.pipeline.Finding
import com.labelaudit.app.pipeline.RuleStatus
import com.labelaudit.app.pipeline.Verdict
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the report screen and the PDF need, assembled once.
 *
 * Organised per field, not per rule. Several rules can bear on one field —
 * mrp alone is checked for presence, against the registry, and for text
 * height — and listing them as separate top-level rows repeated the same read
 * value and evidence crop three times, which read as inconsistency rather
 * than as three checks on one declaration.
 */
data class ScanReport(
    val verdict: Verdict,
    val fields: List<FieldGroup>,
    val unresolved: List<Unresolved>,
    val rulesetVersion: String,
    val sourceCitation: String,
    val framesUsed: Int,
    val framesGated: Int,
    val elapsedMs: Long,
    /**
     * Every line OCR returned, verbatim.
     *
     * When a declaration that is plainly on the pack is reported missing, the
     * question is always whether the text was read and not matched, or never
     * read at all. Without this the two are indistinguishable and the only
     * recourse is guessing at patterns.
     */
    val rawLines: List<String> = emptyList(),
    /**
     * The registered SKU this pack was matched to, and where that reference
     * came from. A comparison is only as trustworthy as its reference, so the
     * report says which one was used rather than leaving a PASS on a registry
     * check looking like an independent verification.
     */
    val matchedSkuId: String? = null,
    val referenceNote: String? = null,
    val capturedAt: Date = Date()
) {
    /** One declaration, with every rule that bears on it. */
    data class FieldGroup(
        val field: String,
        val status: RuleStatus,
        val observedValue: String?,
        val agreement: String?,
        val crop: Bitmap?,
        val checks: List<Finding>
    ) {
        // `this.` is required: bare `field` inside a getter is Kotlin's
        // backing-field keyword, not the constructor parameter.
        val label: String get() = this.field.replace('_', ' ')
    }

    /** A field the frames could not agree on. Reported, never hidden. */
    data class Unresolved(
        val field: String,
        val reason: String,
        val candidates: List<Consensus.Candidate>
    )

    val timestamp: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(capturedAt)

    /** Counted over rules, since that is what the statuses belong to. */
    val counts: Map<RuleStatus, Int>
        get() = fields.flatMap { it.checks }.groupingBy { it.status }.eachCount()

    /**
     * Names the checks that did not run, so a PASS is never mistaken for a
     * complete audit. Silence about skipped checks would overstate the result.
     */
    val notApplicableCaveat: String
        get() {
            val skipped = fields.flatMap { it.checks }
                .filter { it.status == RuleStatus.NOT_APPLICABLE }
            if (skipped.isEmpty()) return ""
            return " ${skipped.size} check(s) did not apply and were not " +
                "evaluated: " + skipped.joinToString(", ") { it.ruleId } + "."
        }

    /**
     * The rules responsible for the headline verdict. Without this, a report
     * reading NOT_ASSESSABLE next to several PASS rows looks self-contradictory.
     */
    val blockingChecks: List<Finding>
        get() {
            val blocking = when (verdict) {
                Verdict.NOT_ASSESSABLE -> RuleStatus.NOT_ASSESSABLE
                Verdict.FAIL -> RuleStatus.FAIL
                Verdict.NEEDS_REVIEW -> RuleStatus.NEEDS_REVIEW
                Verdict.PASS -> return emptyList()
            }
            return fields.flatMap { it.checks }.filter { it.status == blocking }
        }

    val verdictExplanation: String
        get() = when (verdict) {
            Verdict.PASS -> "Every check that applied to this pack passed." +
                notApplicableCaveat
            Verdict.FAIL ->
                "One or more declarations do not meet the cited requirements." +
                    notApplicableCaveat
            Verdict.NEEDS_REVIEW -> "A human reviewer must decide; see the flagged checks."
            Verdict.NOT_ASSESSABLE ->
                "Not enough evidence to judge. This is not a finding of non-compliance."
        }

    companion object {
        /**
         * Worst-first, matching the overall verdict's precedence:
         * NOT_ASSESSABLE outranks FAIL, because a check that could not be
         * performed must not be reported as one that was and failed.
         */
        private val SEVERITY = listOf(
            RuleStatus.NOT_ASSESSABLE,
            RuleStatus.FAIL,
            RuleStatus.NEEDS_REVIEW,
            RuleStatus.PASS,
            // Neither of these blocks a verdict, so they sort last. Omitting
            // NOT_APPLICABLE made a field whose only check was skipped fall
            // through to NOT_ASSESSABLE, which claims an evidence gap that
            // does not exist.
            RuleStatus.NOT_APPLICABLE,
            RuleStatus.EXEMPT
        )

        private fun worst(statuses: Collection<RuleStatus>): RuleStatus =
            SEVERITY.firstOrNull { it in statuses } ?: RuleStatus.NOT_ASSESSABLE

        fun from(
            evaluation: Evaluation,
            crops: Map<String, Bitmap>,
            consensus: Consensus.Result,
            framesUsed: Int,
            framesGated: Int,
            elapsedMs: Long,
            rawLines: List<String> = emptyList(),
            matchedSkuId: String? = null,
            referenceNote: String? = null
        ): ScanReport {
            val groups = evaluation.findings
                .groupBy { it.field }
                .map { (field, checks) ->
                    FieldGroup(
                        field = field,
                        status = worst(checks.map { it.status }),
                        observedValue = checks.firstNotNullOfOrNull { it.observedValue },
                        agreement = checks.firstNotNullOfOrNull { it.evidence["agreement"] },
                        crop = crops[field],
                        checks = checks
                    )
                }
                // Fields needing attention first; a reviewer should not have to
                // scroll past passing declarations to find the problem.
                .sortedWith(compareBy({ SEVERITY.indexOf(it.status) }, { it.field }))

            return ScanReport(
                verdict = evaluation.verdict,
                fields = groups,
                unresolved = consensus.failures.map { (field, failure) ->
                    Unresolved(field, failure.reason, failure.candidates)
                }.sortedBy { it.field },
                rulesetVersion = evaluation.rulesetVersion,
                sourceCitation = evaluation.sourceCitation,
                framesUsed = framesUsed,
                framesGated = framesGated,
                elapsedMs = elapsedMs,
                rawLines = rawLines,
                matchedSkuId = matchedSkuId,
                referenceNote = referenceNote
            )
        }
    }
}
