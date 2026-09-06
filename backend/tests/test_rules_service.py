"""
Unit tests for app/services/rules_service.py.

Uses the real canonical ruleset (labelguard.rules.ruleset.yaml) rather than
a hand-rolled fixture ruleset, since the module under test is specifically
about how that ruleset's check types map to CheckResults -- a fixture
ruleset would test a different set of rules than the ones actually shipped.
"""

from __future__ import annotations

from labelguard.models import RuleStatus, Verdict

from app.services import rules_service
from app.services.registry_matcher import MatchDecision
from db.models import Sku
from tests.conftest import make_ocr_result


def _checks_by_rule(evaluation):
    return {c.rule_id: c for c in evaluation.checks}


# ---------------------------------------------------------------------------
# field_present
# ---------------------------------------------------------------------------


def test_field_present_pass_when_declared():
    ocr = make_ocr_result(["MRP Rs. 45.00"])
    evaluation = rules_service.evaluate(ocr)

    assert _checks_by_rule(evaluation)["MRP-01"].status == RuleStatus.PASS


def test_field_present_fail_when_absent_and_confidence_ok():
    ocr = make_ocr_result(["nothing relevant here"], confidence=0.95)
    evaluation = rules_service.evaluate(ocr)

    assert _checks_by_rule(evaluation)["MRP-01"].status == RuleStatus.FAIL


def test_field_present_not_assessable_when_ocr_confidence_low():
    """
    A blurry photo must not manufacture a false "missing declaration"
    finding -- see rules_service's module docstring and config.py's
    min_ocr_confidence_for_verdict.
    """
    ocr = make_ocr_result(["nothing relevant here"], confidence=0.10)
    evaluation = rules_service.evaluate(ocr)

    check = _checks_by_rule(evaluation)["MRP-01"]
    assert check.status == RuleStatus.NOT_ASSESSABLE
    assert "confidence" in check.message.lower()


# ---------------------------------------------------------------------------
# Exemptions
# ---------------------------------------------------------------------------


def test_small_pack_exemption_applies_to_care_01():
    ocr = make_ocr_result(["Net Quantity 5 g"])
    evaluation = rules_service.evaluate(ocr)

    check = _checks_by_rule(evaluation)["CARE-01"]
    assert check.status == RuleStatus.EXEMPT
    assert "EX-SMALL-PACK" in check.message


def test_exemption_does_not_apply_above_threshold():
    ocr = make_ocr_result(["Net Quantity 500 g"])
    evaluation = rules_service.evaluate(ocr)

    check = _checks_by_rule(evaluation)["CARE-01"]
    assert check.status != RuleStatus.EXEMPT


# ---------------------------------------------------------------------------
# Not-yet-implemented check types stay honest
# ---------------------------------------------------------------------------


def test_date_checks_are_not_assessable_not_a_guess():
    ocr = make_ocr_result(["MFG 01/2025"])
    evaluation = rules_service.evaluate(ocr)

    check = _checks_by_rule(evaluation)["MFG-02"]
    assert check.status == RuleStatus.NOT_ASSESSABLE


def test_min_height_checks_are_not_assessable():
    ocr = make_ocr_result(["MRP Rs. 45.00"])
    evaluation = rules_service.evaluate(ocr)

    check = _checks_by_rule(evaluation)["CAP-01"]
    assert check.status == RuleStatus.NOT_ASSESSABLE


# ---------------------------------------------------------------------------
# matches_registry
# ---------------------------------------------------------------------------


def test_matches_registry_not_applicable_with_no_registry_decision():
    ocr = make_ocr_result(["MRP Rs. 45.00"])
    evaluation = rules_service.evaluate(ocr, registry=None)

    assert _checks_by_rule(evaluation)["MRP-02"].status == RuleStatus.NOT_APPLICABLE


def test_matches_registry_not_applicable_when_rejected():
    ocr = make_ocr_result(["MRP Rs. 45.00"])
    registry = MatchDecision(
        status="REJECTED",
        sku=None,
        score=0.1,
        rejection_threshold=0.72,
        match_method="hungarian-v1",
        evidence={},
        extracted_identity={"mrp": "45.00"},
    )

    evaluation = rules_service.evaluate(ocr, registry=registry)

    assert _checks_by_rule(evaluation)["MRP-02"].status == RuleStatus.NOT_APPLICABLE


def test_matches_registry_pass_for_authoritative_agreement():
    ocr = make_ocr_result(["MRP Rs. 45.00"])
    sku = Sku(
        sku_id="SKU-1",
        authority="AUTHORITATIVE",
        brand_strings=["acme"],
        mrp_exact=45.00,
    )
    registry = MatchDecision(
        status="MATCHED",
        sku=sku,
        score=1.0,
        rejection_threshold=0.72,
        match_method="hungarian-v1",
        evidence={"mrp": {"extracted": "45.00", "registry": "45.00", "score": 1.0}},
        extracted_identity={"mrp": "45.00"},
    )

    evaluation = rules_service.evaluate(ocr, registry=registry)

    check = _checks_by_rule(evaluation)["MRP-02"]
    assert check.status == RuleStatus.PASS
    assert check.observed_value == "45.00"


def test_matches_registry_fail_for_authoritative_disagreement():
    ocr = make_ocr_result(["MRP Rs. 45.00"])
    sku = Sku(
        sku_id="SKU-1",
        authority="AUTHORITATIVE",
        brand_strings=["acme"],
        mrp_exact=99.00,
    )
    registry = MatchDecision(
        status="MATCHED",
        sku=sku,
        score=0.5,
        rejection_threshold=0.72,
        match_method="hungarian-v1",
        evidence={"mrp": {"extracted": "45.00", "registry": "99.00", "score": 0.0}},
        extracted_identity={"mrp": "45.00"},
    )

    evaluation = rules_service.evaluate(ocr, registry=registry)

    check = _checks_by_rule(evaluation)["MRP-02"]
    assert check.status == RuleStatus.FAIL
    assert "99.00" in check.message


def test_matches_registry_needs_review_for_asserted_reference_even_on_disagreement():
    """
    The core invariant from ARCHITECTURE.md: "an enrolled reference can
    never fail another pack". An ASSERTED SKU must cap out at
    NEEDS_REVIEW, even when the extracted value visibly disagrees with
    it -- only an AUTHORITATIVE reference can substantiate a FAIL.
    """
    ocr = make_ocr_result(["MRP Rs. 45.00"])
    sku = Sku(
        sku_id="SKU-1",
        authority="ASSERTED",
        brand_strings=["acme"],
        mrp_exact=99.00,
    )
    registry = MatchDecision(
        status="MATCHED",
        sku=sku,
        score=0.5,
        rejection_threshold=0.72,
        match_method="hungarian-v1",
        evidence={"mrp": {"extracted": "45.00", "registry": "99.00", "score": 0.0}},
        extracted_identity={"mrp": "45.00"},
    )

    evaluation = rules_service.evaluate(ocr, registry=registry)

    assert _checks_by_rule(evaluation)["MRP-02"].status == RuleStatus.NEEDS_REVIEW


def test_matches_registry_not_assessable_when_field_not_in_evidence():
    """
    A scan may match a SKU on brand alone (net_quantity never read off
    the label at all); QTY-02 must then be NOT_ASSESSABLE, not PASS or
    FAIL, since nothing was actually compared for that field.
    """
    ocr = make_ocr_result(["ACME brand"])
    sku = Sku(sku_id="SKU-1", authority="AUTHORITATIVE", brand_strings=["acme"])
    registry = MatchDecision(
        status="MATCHED",
        sku=sku,
        score=1.0,
        rejection_threshold=0.72,
        match_method="hungarian-v1",
        evidence={"brand": {"extracted": "acme", "registry": "acme", "score": 1.0}},
        extracted_identity={"brand": "acme"},
    )

    evaluation = rules_service.evaluate(ocr, registry=registry)

    assert _checks_by_rule(evaluation)["QTY-02"].status == RuleStatus.NOT_ASSESSABLE


# ---------------------------------------------------------------------------
# Overall verdict precedence
# ---------------------------------------------------------------------------


def test_overall_verdict_is_fail_when_any_check_fails():
    ocr = make_ocr_result(["nothing relevant here"], confidence=0.95)
    evaluation = rules_service.evaluate(ocr)

    assert evaluation.verdict == Verdict.FAIL


def test_overall_verdict_not_assessable_when_low_confidence_blocks_everything():
    ocr = make_ocr_result(["nothing relevant here"], confidence=0.05)
    evaluation = rules_service.evaluate(ocr)

    # field_present checks all become NOT_ASSESSABLE, and the
    # not-yet-implemented check types are already NOT_ASSESSABLE, so
    # nothing should be able to produce a FAIL from a low-confidence
    # image.
    assert evaluation.verdict == Verdict.NOT_ASSESSABLE
