import pytest

from labelguard.models import (
    Box,
    Evaluation,
    Finding,
    RuleStatus,
    Verdict,
)


def make_finding(**overrides):
    defaults = dict(
        rule_id="MRP-01",
        citation="r. 6(1)(e)",
        crop_box=Box.EMPTY,
        confidence=1.0,
        status=RuleStatus.PASS,
        field="mrp",
    )
    defaults.update(overrides)
    return Finding(**defaults)


def test_finding_requires_rule_id():
    with pytest.raises(ValueError, match="requires rule_id"):
        make_finding(rule_id="")


def test_finding_requires_citation():
    with pytest.raises(ValueError, match="requires a citation"):
        make_finding(citation="")


def test_finding_confidence_out_of_range():
    with pytest.raises(ValueError, match="outside \\[0, 1\\]"):
        make_finding(confidence=1.5)


def test_finding_not_assessable_requires_reason():
    with pytest.raises(ValueError, match="must carry a reason"):
        make_finding(status=RuleStatus.NOT_ASSESSABLE, reason=None)


def test_finding_not_assessable_with_reason_is_fine():
    finding = make_finding(status=RuleStatus.NOT_ASSESSABLE, reason="ocr_no_consensus")
    assert finding.status is RuleStatus.NOT_ASSESSABLE


def test_finding_label_falls_back_to_rule_id():
    assert make_finding(rule_name="").label == "MRP-01"
    assert make_finding(rule_name="Price is declared").label == "Price is declared"


def test_evaluation_filters_violations_and_unassessable():
    findings = [
        make_finding(rule_id="A", status=RuleStatus.FAIL),
        make_finding(rule_id="B", status=RuleStatus.PASS),
        make_finding(rule_id="C", status=RuleStatus.NOT_ASSESSABLE, reason="x"),
    ]
    evaluation = Evaluation(
        verdict=Verdict.FAIL,
        findings=findings,
        ruleset_version="2026.1.0",
        source_citation="LMPC 2011",
    )
    assert [f.rule_id for f in evaluation.violations] == ["A"]
    assert [f.rule_id for f in evaluation.unassessable] == ["C"]


def test_box_dimensions():
    box = Box(left=10, top=20, right=110, bottom=70)
    assert box.width == 100
    assert box.height == 50
    assert Box.EMPTY.width == 0
