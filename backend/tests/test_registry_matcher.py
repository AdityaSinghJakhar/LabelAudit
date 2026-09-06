"""
Unit tests for app/services/registry_matcher.py.

Several of these are regression tests for bugs found while wiring this
module into the scan endpoint (see git history / HANDOFF notes):

  1. normalize_mrp / normalize_quantity previously left the field's own
     label ("MRP", "Net Quantity") in the normalized value whenever OCR
     read label and value as a single token, which meant a textually
     correct match could never actually reach a similarity of 1.0.

  2. _hungarian_match previously discarded any field scoring <= 0, which
     silently excluded a genuinely wrong value (e.g. the label says
     45.00, the registry says 99.00) from both the evidence trail and
     the overall match score, instead of letting it count as a real
     mismatch.
"""

from __future__ import annotations

import pytest

from app.services.registry_matcher import (
    DEFAULT_REJECTION_THRESHOLD,
    _field_similarity,
    _hungarian_match,
    _normalize_mrp,
    _normalize_quantity,
    match_registry,
    match_sku,
)
from app.services.spatial_graph_extractor import (
    OCRToken,
    SpatialExtractionResult,
    extract,
)
from db.models import Sku


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _sku(
    *,
    brand_strings=("acme",),
    mrp_exact=45.00,
    net_quantity="500 g",
    authority="AUTHORITATIVE",
):
    return Sku(
        sku_id="SKU-TEST",
        authority=authority,
        brand_strings=list(brand_strings),
        mrp_exact=mrp_exact,
        net_quantity=net_quantity,
    )


# ---------------------------------------------------------------------------
# Normalisation
# ---------------------------------------------------------------------------


def test_normalize_mrp_formats_numeric_value():
    """
    registry_matcher._normalize_mrp formats an already-clean numeral
    (typically the output of spatial_graph_extractor.normalize_mrp, which
    is what actually strips "Rs."/"MRP"/etc -- see extract() and
    test_field_similarity_mrp_exact_match_after_label_strip below) to a
    fixed 2-decimal string for comparison against a registry mrp_exact.
    It is not itself responsible for stripping currency text.
    """
    assert _normalize_mrp("100") == "100.00"
    assert _normalize_mrp(100) == "100.00"
    assert _normalize_mrp(100.5) == "100.50"


def test_normalize_quantity_expands_units():
    assert _normalize_quantity("500 gm") == "500 g"
    assert _normalize_quantity("1.5 ltr") == "1.5 l"
    assert _normalize_quantity("2 kgs") == "2 kg"


# ---------------------------------------------------------------------------
# Field similarity
# ---------------------------------------------------------------------------


def test_field_similarity_brand_exact_match():
    sku = _sku(brand_strings=["Acme Foods"])
    assert _field_similarity("brand", "acme foods", sku) == 1.0


def test_field_similarity_brand_no_registry_value_scores_zero():
    sku = _sku(brand_strings=[])
    assert _field_similarity("brand", "acme", sku) == 0.0


def test_field_similarity_mrp_exact_match_after_label_strip():
    """
    Regression: a single OCR token "MRP Rs. 45.00" must normalize to the
    same value as a registry mrp_exact of 45.00, not to "MRP  45.00".
    """
    sku = _sku(mrp_exact=45.00)
    extracted = extract(
        [OCRToken(text="MRP Rs. 45.00", x=0, y=0, confidence=0.9)]
    ).identity["mrp"]

    assert _field_similarity("mrp", extracted, sku) == 1.0


def test_field_similarity_mrp_mismatch_scores_zero():
    sku = _sku(mrp_exact=99.00)
    assert _field_similarity("mrp", "45.00", sku) == 0.0


def test_field_similarity_net_quantity_exact_match_after_label_strip():
    sku = _sku(net_quantity="500 g")
    extracted = extract(
        [OCRToken(text="Net Quantity 500 g", x=0, y=0, confidence=0.9)]
    ).identity["net_quantity"]

    assert _field_similarity("net_quantity", extracted, sku) == 1.0


# ---------------------------------------------------------------------------
# Hungarian assignment
# ---------------------------------------------------------------------------


def test_hungarian_match_all_fields_agree():
    sku = _sku(brand_strings=["acme"], mrp_exact=45.00, net_quantity="500 g")
    identity = {"brand": "acme", "mrp": "45.00", "net_quantity": "500 g"}

    score, matches, evidence = _hungarian_match(identity, sku)

    assert score == 1.0
    assert {m.extracted_field for m in matches} == {"brand", "mrp", "net_quantity"}
    assert evidence["mrp"]["score"] == 1.0


def test_hungarian_match_keeps_mismatched_field_in_evidence_and_score():
    """
    Regression: previously a field scoring 0 (a genuine mismatch) was
    dropped from `matches`/`evidence` entirely, so a wrong price could
    never surface as a comparison, and the overall score only ever
    averaged over fields that happened to agree.
    """
    sku = _sku(brand_strings=["acme"], mrp_exact=99.00)
    identity = {"brand": "acme", "mrp": "45.00"}

    score, matches, evidence = _hungarian_match(identity, sku)

    assert "mrp" in evidence
    assert evidence["mrp"]["score"] == 0.0
    assert evidence["mrp"]["registry"] == "99.00"
    # Average of a perfect brand match (1.0) and a total mismatch (0.0).
    assert score == pytest.approx(0.5)


def test_hungarian_match_excludes_fields_with_no_registry_value():
    """
    A field the registry never recorded a value for (e.g. net_quantity
    left null) must not count as either agreement or disagreement -- it
    should simply be excluded from scoring, distinct from a field that
    was compared and found to disagree.
    """
    sku = _sku(brand_strings=["acme"], mrp_exact=45.00, net_quantity=None)
    identity = {"brand": "acme", "mrp": "45.00", "net_quantity": "500 g"}

    score, matches, evidence = _hungarian_match(identity, sku)

    assert "net_quantity" not in evidence
    assert score == 1.0


def test_hungarian_match_no_extracted_fields():
    sku = _sku()
    score, matches, evidence = _hungarian_match({}, sku)

    assert score == 0.0
    assert matches == []
    assert evidence == {}


# ---------------------------------------------------------------------------
# match_sku / rejection threshold
# ---------------------------------------------------------------------------


def test_match_sku_matches_above_threshold():
    sku = _sku()
    extracted = SpatialExtractionResult(
        identity={"brand": "acme", "mrp": "45.00", "net_quantity": "500 g"},
    )

    decision = match_sku(extracted, sku)

    assert decision.status == "MATCHED"
    assert decision.sku is sku
    assert decision.score >= DEFAULT_REJECTION_THRESHOLD


def test_match_sku_rejects_mismatched_price():
    """
    Regression: before the _hungarian_match fix, this scenario matched
    at full confidence because the mismatched MRP was silently excluded
    from scoring instead of dragging the match down.
    """
    sku = _sku(mrp_exact=99.00)
    extracted = SpatialExtractionResult(
        identity={"brand": "acme", "mrp": "45.00"},
    )

    decision = match_sku(extracted, sku)

    assert decision.status == "REJECTED"
    assert decision.sku is None
    assert decision.score < DEFAULT_REJECTION_THRESHOLD


def test_match_sku_no_extracted_identity_is_rejected_not_matched():
    sku = _sku()
    extracted = SpatialExtractionResult(identity={})

    decision = match_sku(extracted, sku)

    assert decision.status == "REJECTED"
    assert decision.sku is None
    assert decision.score == 0.0


# ---------------------------------------------------------------------------
# match_registry (DB-backed)
# ---------------------------------------------------------------------------


def test_match_registry_no_skus_returns_no_candidate(db_session):
    extracted = SpatialExtractionResult(identity={"brand": "acme"})

    decision = match_registry(db_session, extracted)

    assert decision.status == "NO_CANDIDATE"
    assert decision.sku is None


def test_match_registry_selects_best_scoring_sku(db_session, make_sku):
    make_sku(sku_id="SKU-WRONG", brand_strings=["wrongbrand"], mrp_exact=10.0, net_quantity="1 kg")
    correct = make_sku(sku_id="SKU-RIGHT", brand_strings=["acme"], mrp_exact=45.00, net_quantity="500 g")

    extracted = SpatialExtractionResult(
        identity={"brand": "acme", "mrp": "45.00", "net_quantity": "500 g"},
    )

    decision = match_registry(db_session, extracted)

    assert decision.status == "MATCHED"
    assert decision.sku.sku_id == correct.sku_id
