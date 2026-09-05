import pytest

from labelguard.rules.loader import Authority, Ruleset

MINIMAL_VALID = """
version: "1.0"
source_citation: "Some Act, 2011"
height_metric: cap_height
rules:
  - id: TEST-01
    field: mrp
    check:
      type: field_present
    citation: "r. 1"
"""


def test_parse_missing_version_raises():
    with pytest.raises(ValueError, match="version"):
        Ruleset.parse("source_citation: x\nheight_metric: cap_height\n")


def test_parse_missing_source_citation_raises():
    with pytest.raises(ValueError, match="source_citation"):
        Ruleset.parse('version: "1.0"\nheight_metric: cap_height\n')


def test_rule_without_citation_raises():
    yaml_text = """
    version: "1.0"
    source_citation: "x"
    height_metric: cap_height
    rules:
      - id: TEST-01
        field: mrp
        check:
          type: field_present
    """
    with pytest.raises(ValueError, match="TEST-01 has no citation"):
        Ruleset.parse(yaml_text)


def test_exemption_without_citation_raises():
    yaml_text = """
    version: "1.0"
    source_citation: "x"
    height_metric: cap_height
    exemptions:
      - id: EX-1
        condition:
          field: net_quantity
          op: lte
          value: 10
          unit: g
        exempts: [CARE-01]
    """
    with pytest.raises(ValueError, match="EX-1 has no citation"):
        Ruleset.parse(yaml_text)


def test_minimal_valid_ruleset_parses():
    ruleset = Ruleset.parse(MINIMAL_VALID)
    assert ruleset.version == "1.0"
    assert len(ruleset.rules) == 1
    assert ruleset.rules[0].id == "TEST-01"
    assert ruleset.rules[0].check.type == "field_present"
    assert ruleset.registry.populated is False
    assert ruleset.registry.authority is Authority.ASSERTED


def test_load_canonical_ruleset_from_package():
    ruleset = Ruleset.load_canonical()

    rule_ids = {r.id for r in ruleset.rules}
    assert "MRP-01" in rule_ids
    assert "CAP-01" in rule_ids
    assert len(ruleset.rules) == 17

    # Every rule must carry a citation - this is enforced at parse time,
    # so if parsing succeeded at all this is already guaranteed, but assert
    # it explicitly since it's the load-bearing invariant of the file.
    assert all(rule.citation.strip() for rule in ruleset.rules)

    cap_01 = next(r for r in ruleset.rules if r.id == "CAP-01")
    assert cap_01.check.type == "min_height_mm"
    assert cap_01.check.needs_legal_confirmation is True
    assert cap_01.check.needs_calibration is True
    assert cap_01.check.min_mm == 1.0

    assert len(ruleset.exemptions) == 2
    small_pack = next(e for e in ruleset.exemptions if e.id == "EX-SMALL-PACK")
    assert small_pack.exempts == ["CARE-01"]
    assert small_pack.condition.unit == "g"

    assert ruleset.registry.populated is False
