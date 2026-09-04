package com.labelguard.app.pipeline

/**
 * Shared vocabulary for compliance results.
 *
 * Core invariant: never emit a verdict the pipeline cannot substantiate. The
 * enum is closed, and NOT_ASSESSABLE always carries a reason so a caller can
 * tell "the label is wrong" from "we could not look".
 */
enum class Verdict {
    PASS,
    FAIL,
    NEEDS_REVIEW,
    NOT_ASSESSABLE
}

/**
 * Outcome of a single rule. EXEMPT and NOT_APPLICABLE are rule-level states,
 * not verdicts.
 *
 * NOT_ASSESSABLE and NOT_APPLICABLE look similar and are not:
 *
 *   NOT_ASSESSABLE  we tried to read this label and could not — bad frames,
 *                   no OCR consensus. It is a fact about this photograph, so
 *                   it blocks the verdict: the pack was not fully assessed.
 *   NOT_APPLICABLE  the check does not apply to this deployment — no SKU is
 *                   registered to compare against, or the field has no
 *                   extractor. It is a fact about the configuration, not the
 *                   label, and must not suppress violations the pipeline did
 *                   substantiate.
 */
enum class RuleStatus {
    PASS,
    FAIL,
    NEEDS_REVIEW,
    NOT_ASSESSABLE,
    NOT_APPLICABLE,
    EXEMPT
}

/**
 * One rule's outcome.
 *
 * Every finding must carry ruleId, citation, cropBox and confidence. The
 * constructor enforces it — a finding missing any of the four is a
 * programming error, not a runtime condition to tolerate.
 */
data class Finding(
    val ruleId: String,
    val citation: String,
    val cropBox: Box,
    val confidence: Float,
    val status: RuleStatus,
    val field: String,
    /** Plain-words description of the check; falls back to the rule id. */
    val ruleName: String = "",
    val message: String = "",
    val reason: String? = null,
    /** Value the pipeline actually read, for the evidence column. */
    val observedValue: String? = null,
    val evidence: Map<String, String> = emptyMap()
) {
    /** What to show a reader: the plain name if there is one, else the id. */
    val label: String get() = ruleName.ifBlank { ruleId }

    init {
        require(ruleId.isNotBlank()) { "finding requires ruleId" }
        require(citation.isNotBlank()) { "finding $ruleId requires a citation" }
        require(confidence in 0f..1f) {
            "finding $ruleId confidence $confidence outside [0, 1]"
        }
        require(status != RuleStatus.NOT_ASSESSABLE || !reason.isNullOrBlank()) {
            "finding $ruleId is NOT_ASSESSABLE and must carry a reason"
        }
    }
}

data class Evaluation(
    val verdict: Verdict,
    val findings: List<Finding>,
    val rulesetVersion: String,
    val sourceCitation: String
) {
    val violations: List<Finding> get() = findings.filter { it.status == RuleStatus.FAIL }
    val unassessable: List<Finding> get() =
        findings.filter { it.status == RuleStatus.NOT_ASSESSABLE }
}
