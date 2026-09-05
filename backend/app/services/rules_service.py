"""
Server-side rule evaluation over naively-extracted fields.

GOVERNING RULE, unchanged from HANDOFF.md: never emit a verdict the
pipeline cannot substantiate. That rule was written for the on-device
pipeline, but it binds this one exactly as hard -- if anything, harder,
because a server-side scan has no consensus-across-frames signal (see
labelguard.models.ConsensusResult) to fall back on the way the phone does.

labelguard.rules.engine (the Python port of RulesEngine.kt) is currently an
EMPTY STUB in this repo -- only the YAML loader (rules/loader.py) is
implemented on the Python side. Rather than block server-side OCR on that
port landing, this module evaluates a deliberately small subset of check
types itself, and reports every check type it does NOT yet understand as
NOT_ASSESSABLE with a specific reason, never as a guess in either
direction. Concretely, of ruleset.yaml's check types:

  field_present     IMPLEMENTED HERE. Gated on OCR confidence (below).
  matches_registry  NOT_APPLICABLE. Server-side SKU registry matching
                     is not wired up (the "registry" table exists in
                     db/models.py as Sku, but nothing in this request
                     path queries it yet).
  role_present      NOT_ASSESSABLE. Needs spatial layout (which address
                     block sits under which caption) -- naive keyword
                     matching cannot attribute an address to a role.
  date_not_future,
  date_marking,
  date_order,
  not_expired       NOT_ASSESSABLE. Date arithmetic-as-a-range
                     (LabelDate.kt / labelguard.dates) is implemented for
                     the on-device path but not yet plumbed into this
                     naive extractor's output.
  min_height_mm     NOT_ASSESSABLE. Needs camera calibration data
                     (focal length, focus distance) this endpoint never
                     receives -- an uploaded JPEG carries no lens metadata
                     a server can trust.

This keeps the honesty property intact while still producing a real,
partially-substantiated verdict from Step 4-style OCR + naive extraction,
exactly per the SIH implementation plan's own staging (Step 4 = working
demo; every later step improves it, none of them are load-bearing for a
demo to exist).
"""

from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache

from labelguard.models import RuleStatus, Verdict
from labelguard.rules.loader import Rule, Ruleset

from app.config import settings
from app.models.ocr import OcrResult
from app.services.field_extraction import ExtractedField, extract_fields, parse_quantity

# check.type values this module can actually evaluate. Everything else in
# the loaded ruleset is reported NOT_ASSESSABLE / NOT_APPLICABLE, never
# guessed. Keep this set and the branches in _evaluate_rule in sync.
_IMPLEMENTED_CHECK_TYPES = {"field_present"}

_NOT_ASSESSABLE_REASONS = {
    "role_present": (
        "Address-to-role attribution needs spatial layout analysis; this "
        "endpoint only does keyword/regex matching over raw OCR text."
    ),
    "date_not_future": (
        "Date-range arithmetic (labelguard.dates) is not yet wired into "
        "server-side field extraction."
    ),
    "date_marking": (
        "Date-range arithmetic (labelguard.dates) is not yet wired into "
        "server-side field extraction."
    ),
    "date_order": (
        "Date-range arithmetic (labelguard.dates) is not yet wired into "
        "server-side field extraction."
    ),
    "not_expired": (
        "Date-range arithmetic (labelguard.dates) is not yet wired into "
        "server-side field extraction."
    ),
    "min_height_mm": (
        "Letter-height measurement needs camera focal length/focus "
        "distance; an uploaded image carries no lens metadata a server "
        "can trust."
    ),
}


@dataclass(frozen=True)
class CheckResult:
    rule_id: str
    rule_name: str
    field: str
    status: RuleStatus
    citation: str
    message: str
    observed_value: str | None


@dataclass(frozen=True)
class EvaluationResult:
    verdict: Verdict
    ruleset_version: str
    extracted_fields: dict[str, ExtractedField]
    checks: list[CheckResult]


@lru_cache(maxsize=1)
def _load_ruleset() -> Ruleset:
    """
    Loads labelguard's canonical ruleset.yaml once per process. This is the
    SAME file the on-device app is supposed to mirror (see that file's own
    header comment) -- one source of truth for both pipelines.
    """
    return Ruleset.load_canonical()


def _op(name: str):
    return {
        "lte": lambda a, b: a <= b,
        "lt": lambda a, b: a < b,
        "gte": lambda a, b: a >= b,
        "gt": lambda a, b: a > b,
        "eq": lambda a, b: a == b,
    }[name]


def _exempt_rule_ids(ruleset: Ruleset, fields: dict[str, ExtractedField]) -> set[str]:
    """
    Evaluates exemptions BEFORE rules, per ruleset.yaml's own ordering
    comment. Only net_quantity-keyed exemptions exist today
    (EX-SMALL-PACK / EX-SMALL-PACK-ML); this generalises to any
    field+op+value+unit condition the YAML might add later.
    """
    exempt: set[str] = set()
    for exemption in ruleset.exemptions:
        cond = exemption.condition
        raw_value = fields.get(cond.field)
        if raw_value is None:
            continue
        number, unit = parse_quantity(raw_value.value)
        if number is None:
            continue
        if cond.unit and unit != cond.unit:
            continue
        if _op(cond.op)(number, cond.value):
            exempt.update(exemption.exempts)
    return exempt


def _evaluate_rule(
    rule: Rule,
    fields: dict[str, ExtractedField],
    ocr_confidence_ok: bool,
) -> CheckResult:
    check_type = rule.check.type

    if check_type == "field_present":
        if not ocr_confidence_ok:
            return CheckResult(
                rule_id=rule.id,
                rule_name=rule.name,
                field=rule.field,
                status=RuleStatus.NOT_ASSESSABLE,
                citation=rule.citation,
                message=(
                    "OCR confidence on this image was too low to assert "
                    "whether this declaration is genuinely absent or "
                    "simply misread."
                ),
                observed_value=None,
            )
        found = fields.get(rule.field)
        if found is not None:
            return CheckResult(
                rule_id=rule.id,
                rule_name=rule.name,
                field=rule.field,
                status=RuleStatus.PASS,
                citation=rule.citation,
                message="Declaration found on the label.",
                observed_value=found.value,
            )
        return CheckResult(
            rule_id=rule.id,
            rule_name=rule.name,
            field=rule.field,
            status=RuleStatus.FAIL,
            citation=rule.citation,
            message="This declaration was not found on the label.",
            observed_value=None,
        )

    if check_type == "matches_registry":
        return CheckResult(
            rule_id=rule.id,
            rule_name=rule.name,
            field=rule.field,
            status=RuleStatus.NOT_APPLICABLE,
            citation=rule.citation,
            message=(
                "Server-side SKU registry matching is not wired up for "
                "this endpoint yet."
            ),
            observed_value=None,
        )

    reason = _NOT_ASSESSABLE_REASONS.get(
        check_type, f"Check type '{check_type}' is not implemented server-side."
    )
    return CheckResult(
        rule_id=rule.id,
        rule_name=rule.name,
        field=rule.field,
        status=RuleStatus.NOT_ASSESSABLE,
        citation=rule.citation,
        message=reason,
        observed_value=None,
    )


def _overall_verdict(checks: list[CheckResult]) -> Verdict:
    """
    Precedence FAIL > NOT_ASSESSABLE > NEEDS_REVIEW > PASS, per
    ARCHITECTURE.md. EXEMPT and NOT_APPLICABLE are excluded from the
    comparison entirely -- they are facts about the setup, not about
    whether the pack complies, and must not be able to either manufacture
    or suppress a verdict.
    """
    statuses = {
        c.status
        for c in checks
        if c.status not in (RuleStatus.EXEMPT, RuleStatus.NOT_APPLICABLE)
    }
    if not statuses:
        return Verdict.NOT_ASSESSABLE
    if RuleStatus.FAIL in statuses:
        return Verdict.FAIL
    if RuleStatus.NOT_ASSESSABLE in statuses:
        return Verdict.NOT_ASSESSABLE
    if RuleStatus.NEEDS_REVIEW in statuses:
        return Verdict.NEEDS_REVIEW
    return Verdict.PASS


def evaluate(ocr_result: OcrResult) -> EvaluationResult:
    ruleset = _load_ruleset()
    fields = extract_fields(ocr_result.full_text)
    ocr_confidence_ok = ocr_result.mean_confidence >= settings.min_ocr_confidence_for_verdict

    exempt_ids = _exempt_rule_ids(ruleset, fields)

    checks: list[CheckResult] = []
    for rule in ruleset.rules:
        if rule.id in exempt_ids:
            exemption = next(
                e for e in ruleset.exemptions if rule.id in e.exempts
            )
            checks.append(
                CheckResult(
                    rule_id=rule.id,
                    rule_name=rule.name,
                    field=rule.field,
                    status=RuleStatus.EXEMPT,
                    citation=exemption.citation,
                    message=f"Exempted under {exemption.id}.",
                    observed_value=None,
                )
            )
            continue
        checks.append(_evaluate_rule(rule, fields, ocr_confidence_ok))

    return EvaluationResult(
        verdict=_overall_verdict(checks),
        ruleset_version=ruleset.version,
        extracted_fields=fields,
        checks=checks,
    )
