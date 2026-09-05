from app.services.spatial_graph_extractor import (
    OCRToken,
    SpatialExtractionResult,
    build_spatial_graph,
    extract,
    match_sku,
    normalize_brand,
    normalize_mrp,
    normalize_quantity,
)


# ---------------------------------------------------------------------------
# Normalization
# ---------------------------------------------------------------------------

def test_normalize_brand():
    assert normalize_brand("  ACME   Foods  ") == "acme foods"


def test_normalize_mrp():
    assert normalize_mrp("Rs. 100") == "100"


def test_normalize_quantity():
    assert normalize_quantity("  500   G  ") == "500 g"


# ---------------------------------------------------------------------------
# Spatial graph
# ---------------------------------------------------------------------------

def test_build_spatial_graph_creates_edges_for_nearby_tokens():
    tokens = [
        OCRToken(
            text="ACME",
            x=100,
            y=100,
            width=50,
            height=20,
            confidence=0.99,
        ),
        OCRToken(
            text="Foods",
            x=150,
            y=105,
            width=50,
            height=20,
            confidence=0.98,
        ),
    ]

    edges = build_spatial_graph(tokens, max_distance=100)

    assert isinstance(edges, list)
    assert len(edges) >= 1

    edge = edges[0]

    assert "source" in edge
    assert "target" in edge
    assert "distance" in edge


def test_build_spatial_graph_rejects_distant_tokens():
    tokens = [
        OCRToken(
            text="ACME",
            x=0,
            y=0,
        ),
        OCRToken(
            text="100",
            x=1000,
            y=1000,
        ),
    ]

    edges = build_spatial_graph(tokens, max_distance=100)

    assert edges == []


def test_build_spatial_graph_empty_input():
    edges = build_spatial_graph([])

    assert edges == []


# ---------------------------------------------------------------------------
# Spatial extraction
# ---------------------------------------------------------------------------

def test_extract_returns_spatial_extraction_result():
    tokens = [
        OCRToken(
            text="ACME",
            x=100,
            y=100,
            width=50,
            height=20,
            confidence=0.99,
        ),
        OCRToken(
            text="MRP Rs. 100",
            x=100,
            y=150,
            width=100,
            height=20,
            confidence=0.95,
        ),
        OCRToken(
            text="Net Quantity 500 g",
            x=100,
            y=200,
            width=120,
            height=20,
            confidence=0.96,
        ),
    ]

    result = extract(tokens)

    assert isinstance(result, SpatialExtractionResult)
    assert isinstance(result.identity, dict)
    assert isinstance(result.candidates, dict)
    assert isinstance(result.graph_edges, list)


def test_extract_empty_input():
    result = extract([])

    assert isinstance(result, SpatialExtractionResult)
    assert result.identity == {}
    assert result.candidates == {}
    assert result.graph_edges == []


def test_extract_identifies_mrp():
    tokens = [
        OCRToken(
            text="ACME",
            x=100,
            y=100,
            confidence=0.99,
        ),
        OCRToken(
            text="MRP Rs. 100",
            x=100,
            y=150,
            confidence=0.95,
        ),
    ]

    result = extract(tokens)

    assert "mrp" in result.identity
    assert result.identity["mrp"]


def test_extract_identifies_quantity():
    tokens = [
        OCRToken(
            text="ACME",
            x=100,
            y=100,
            confidence=0.99,
        ),
        OCRToken(
            text="Net Quantity 500 g",
            x=100,
            y=150,
            confidence=0.95,
        ),
    ]

    result = extract(tokens)

    assert "net_quantity" in result.identity
    assert result.identity["net_quantity"]


# ---------------------------------------------------------------------------
# Hungarian SKU matching
# ---------------------------------------------------------------------------

class FakeSku:
    """
    Lightweight stand-in for the SQLAlchemy Sku model.

    The matcher only needs these fields, so PostgreSQL is not required
    for testing the matching algorithm.
    """

    id = "sku-db-100"
    sku_id = "SKU-001"
    authority = "AUTHORITATIVE"
    brand_strings = ["Acme"]
    mrp_exact = 100.0
    net_quantity = "500 g"


def test_exact_registry_match():
    extracted = SpatialExtractionResult(
        identity={
            "brand": "Acme",
            "mrp": "100",
            "net_quantity": "500 g",
        },
        candidates={},
        graph_edges=[],
    )

    result = match_sku(
        extracted,
        FakeSku(),
        rejection_threshold=0.72,
    )

    assert result.status == "MATCHED"
    assert result.sku is not None
    assert result.sku.sku_id == "SKU-001"
    assert result.score >= 0.72
    assert result.match_method == "hungarian-v1"
    assert result.extracted_identity == extracted.identity


def test_low_score_match_is_rejected():
    extracted = SpatialExtractionResult(
        identity={
            "brand": "Completely Different Brand",
            "mrp": "999",
            "net_quantity": "2 kg",
        },
        candidates={},
        graph_edges=[],
    )

    result = match_sku(
        extracted,
        FakeSku(),
        rejection_threshold=0.72,
    )

    assert result.status == "REJECTED"
    assert result.sku is None
    assert result.score < 0.72
    assert result.match_method == "hungarian-v1"


def test_no_extracted_fields_returns_no_candidate():
    extracted = SpatialExtractionResult(
        identity={},
        candidates={},
        graph_edges=[],
    )

    result = match_sku(
        extracted,
        FakeSku(),
        rejection_threshold=0.72,
    )

    assert result.status == "NO_CANDIDATE"
    assert result.sku is None
    assert result.score == 0.0
    assert result.rejection_threshold == 0.72


def test_rejection_threshold_is_respected():
    extracted = SpatialExtractionResult(
        identity={
            "brand": "Acme",
        },
        candidates={},
        graph_edges=[],
    )

    result = match_sku(
        extracted,
        FakeSku(),
        rejection_threshold=1.01,
    )

    assert result.status == "REJECTED"
    assert result.sku is None