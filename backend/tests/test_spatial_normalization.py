"""
Regression tests for app/services/spatial_graph_extractor.py's
normalize_mrp / normalize_quantity.

These are kept separate from the pre-existing test_spatial_graph_extractor.py
(which already covers build_spatial_graph, extract(), and the legacy
extract()-local match_sku()) so the specific bug fixed here -- labels not
being stripped from combined label+value OCR tokens -- has its own clearly
named regression coverage.
"""

from app.services.spatial_graph_extractor import (
    OCRToken,
    extract,
    normalize_mrp,
    normalize_quantity,
)


# ---------------------------------------------------------------------------
# normalize_mrp
# ---------------------------------------------------------------------------


def test_normalize_mrp_bare_number():
    assert normalize_mrp("100") == "100"


def test_normalize_mrp_strips_rupee_symbol():
    assert normalize_mrp("₹100") == "100"


def test_normalize_mrp_strips_rs_prefix():
    assert normalize_mrp("Rs. 100") == "100"
    assert normalize_mrp("Rs 100") == "100"


def test_normalize_mrp_strips_trailing_slash_dash():
    assert normalize_mrp("100/-") == "100"


def test_normalize_mrp_strips_mrp_label():
    """
    The original bug: "MRP Rs. 45.00" (label and value read as one OCR
    token) normalized to "MRP  45.00" instead of "45.00", so it could
    never match a registry's bare mrp_exact value.
    """
    assert normalize_mrp("MRP Rs. 45.00") == "45.00"
    assert normalize_mrp("MRP 45.00") == "45.00"
    assert normalize_mrp("M.R.P. 45.00") == "45.00"
    assert normalize_mrp("M.R.P.: 45.00/-") == "45.00"


def test_normalize_mrp_keeps_decimal_precision():
    assert normalize_mrp("MRP Rs. 45.50") == "45.50"


# ---------------------------------------------------------------------------
# normalize_quantity
# ---------------------------------------------------------------------------


def test_normalize_quantity_bare_value():
    assert normalize_quantity("500 g") == "500 g"


def test_normalize_quantity_case_and_whitespace():
    assert normalize_quantity("  500   G  ") == "500 g"


def test_normalize_quantity_strips_net_quantity_label():
    """
    The original bug: "Net Quantity 500 g" normalized to
    "net quantity 500 g" instead of "500 g", so it could never match a
    registry's bare net_quantity value.
    """
    assert normalize_quantity("Net Quantity 500 g") == "500 g"
    assert normalize_quantity("Net Qty: 500g") == "500 g"
    assert normalize_quantity("Net Weight 1.5 kg") == "1.5 kg"


def test_normalize_quantity_expands_unit_aliases():
    assert normalize_quantity("500 gms") == "500 g"
    assert normalize_quantity("1 ltr") == "1 l"


# ---------------------------------------------------------------------------
# End-to-end through extract()
# ---------------------------------------------------------------------------


def test_extract_normalizes_combined_label_and_value_tokens():
    """
    The scenario that surfaced the bug: a single OCR token carrying both
    the field's caption and its value, as PaddleOCR/ML Kit will often
    read it off a real label.
    """
    tokens = [
        OCRToken(text="ACME", x=0, y=0, confidence=0.95),
        OCRToken(text="MRP Rs. 45.00", x=0, y=20, confidence=0.9),
        OCRToken(text="Net Quantity 500 g", x=0, y=40, confidence=0.92),
    ]

    result = extract(tokens)

    assert result.identity["mrp"] == "45.00"
    assert result.identity["net_quantity"] == "500 g"
