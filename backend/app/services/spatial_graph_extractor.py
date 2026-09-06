from __future__ import annotations

import re
from dataclasses import dataclass, field
from math import sqrt
from typing import Any

from scipy.optimize import linear_sum_assignment


# ---------------------------------------------------------------------------
# Data structures
# ---------------------------------------------------------------------------

@dataclass
class OCRToken:
    """
    One OCR result with spatial information.

    x, y represent the centre of the token.
    width and height represent its bounding-box dimensions.
    confidence is the OCR confidence.
    """

    text: str
    x: float
    y: float
    width: float = 0.0
    height: float = 0.0
    confidence: float = 1.0


@dataclass
class SpatialExtractionResult:
    """
    Result produced by the spatial extraction pipeline.

    identity:
        Normalized fields extracted from the OCR graph.

    candidates:
        Candidate values considered for each field.

    graph_edges:
        Spatial relationships between OCR tokens.
    """

    identity: dict[str, str]
    candidates: dict[str, list[dict[str, Any]]] = field(default_factory=dict)
    graph_edges: list[dict[str, Any]] = field(default_factory=list)


@dataclass
class FieldCandidate:
    field: str
    value: str
    score: float
    token_indices: list[int] = field(default_factory=list)
    evidence: dict[str, Any] = field(default_factory=dict)


# ---------------------------------------------------------------------------
# Normalisation
# ---------------------------------------------------------------------------

def normalize_text(text: str) -> str:
    """Basic normalization used by the matcher."""

    return " ".join(text.strip().split())


def normalize_brand(text: str) -> str:
    return normalize_text(text).casefold()


def normalize_mrp(text: str) -> str:
    """
    Normalize common MRP representations down to a bare numeral string,
    so "MRP Rs. 100", "Rs. 100", "M.R.P.: 100/-" and "100" all normalize
    to the same value and can be compared against a registry's
    mrp_exact. Stripping only the currency marks (the previous
    behaviour) left the "MRP"/"M.R.P" label itself in the string
    whenever a single OCR token carried both label and value together
    (e.g. "MRP Rs. 45.00"), which meant that value could never match a
    bare registered price -- this is what MRP-02 looked like before this
    fix: a real match silently scoring 0.
    """

    value = text.strip()

    # Strip a leading MRP/M.R.P./Maximum Retail Price label, if present.
    value = re.sub(
        r"(?i)^\s*(?:m\.?\s?r\.?\s?p\.?|maximum\s+retail\s+price|max\s+retail\s+price)\s*[:\-]?\s*",
        "",
        value,
    )

    value = value.replace("₹", "")
    value = re.sub(r"(?i)\bRs\.?\b", "", value)
    value = re.sub(r"(?i)\bINR\b", "", value)
    value = value.replace("/-", "")
    value = value.replace(",", "")

    # If a clean numeral is present, prefer it over any leftover
    # punctuation/whitespace -- this is what actually makes "45.00" and
    # "45" comparable to a registry's float-valued mrp_exact.
    match = re.search(r"[0-9]+(?:\.[0-9]+)?", value)
    if match:
        return match.group(0)

    return value.strip()


def normalize_quantity(text: str) -> str:
    """
    Normalize a net-quantity declaration down to "<number> <unit>", e.g.
    "Net Quantity 500 g" and "500 G" both normalize to "500 g", so they
    can be compared against a registry's net_quantity string. As with
    normalize_mrp, stripping only surrounding whitespace/case (the
    previous behaviour) left the "net quantity" label in the string
    whenever label and value were read as a single OCR token, which
    meant a real quantity match would silently score 0.
    """

    value = normalize_text(text).casefold()

    value = re.sub(
        r"^(?:net\s*(?:wt\.?|weight|qty\.?|quantity|vol\.?|volume)|quantity|qty)\s*[:\-]?\s*",
        "",
        value,
    )

    match = re.search(
        r"([0-9]+(?:\.[0-9]+)?)\s*(k?g|m?l|gms?|ltr|litres?)\b",
        value,
    )

    if match:
        number = match.group(1)
        unit = match.group(2)
        unit = {"gms": "g", "gm": "g", "ltr": "l", "litre": "l", "litres": "l"}.get(
            unit, unit
        )
        return f"{number} {unit}"

    return value


# ---------------------------------------------------------------------------
# Spatial graph
# ---------------------------------------------------------------------------

def _distance(a: OCRToken, b: OCRToken) -> float:
    return sqrt((a.x - b.x) ** 2 + (a.y - b.y) ** 2)


def _vertical_distance(a: OCRToken, b: OCRToken) -> float:
    return abs(a.y - b.y)


def _horizontal_distance(a: OCRToken, b: OCRToken) -> float:
    return abs(a.x - b.x)


def build_spatial_graph(
    tokens: list[OCRToken],
    max_distance: float = 250.0,
) -> list[dict[str, Any]]:
    """
    Construct a simple spatial graph over OCR tokens.

    Each edge records the spatial relationship between two nearby tokens.

    This deliberately does not perform field extraction. It only captures
    spatial evidence that later stages can use.
    """

    edges: list[dict[str, Any]] = []

    for i in range(len(tokens)):
        for j in range(i + 1, len(tokens)):
            a = tokens[i]
            b = tokens[j]

            distance = _distance(a, b)

            if distance > max_distance:
                continue

            edges.append(
                {
                    "source": i,
                    "target": j,
                    "distance": distance,
                    "horizontal_distance": _horizontal_distance(a, b),
                    "vertical_distance": _vertical_distance(a, b),
                    "same_row": _vertical_distance(a, b)
                    <= max(a.height, b.height, 1.0),
                }
            )

    return edges


# ---------------------------------------------------------------------------
# Field detection heuristics
# ---------------------------------------------------------------------------

_MRP_LABELS = {
    "mrp",
    "m.r.p",
    "m.r.p.",
    "maximum retail price",
    "max retail price",
}

_QUANTITY_UNITS = {
    "g",
    "kg",
    "mg",
    "ml",
    "l",
    "ltr",
    "litre",
    "liter",
}

_BRAND_LABELS = {
    "brand",
    "manufactured by",
    "marketed by",
}


def _looks_like_mrp(text: str) -> bool:
    value = text.casefold().strip()

    if any(label in value for label in _MRP_LABELS):
        return True

    cleaned = normalize_mrp(value)

    try:
        float(cleaned)
        return True
    except ValueError:
        return False


def _looks_like_quantity(text: str) -> bool:
    """
    Detect a net quantity expressed either as:

        500 g
        1 kg
        250 ml
        1.5 L

    or with a label:

        Net Quantity 500 g
        Net Qty 500 g
        Net Weight 500 g
    """

    value = (
        text.casefold()
        .replace(",", "")
        .replace(":", " ")
    )

    # Remove common quantity labels.
    quantity_labels = (
        "net quantity",
        "net qty",
        "net weight",
        "quantity",
        "qty",
    )

    for label in quantity_labels:
        if value.startswith(label):
            value = value[len(label):].strip()

    parts = value.split()

    if len(parts) < 2:
        return False

    try:
        float(parts[0])
    except ValueError:
        return False

    return parts[1] in _QUANTITY_UNITS


def _is_mrp_label(text: str) -> bool:
    return text.casefold().strip() in _MRP_LABELS


def _is_brand_label(text: str) -> bool:
    return text.casefold().strip() in _BRAND_LABELS


def _candidate_score(
    token: OCRToken,
    field: str,
) -> float:
    """
    Initial field confidence.

    This is intentionally deterministic. More sophisticated models can
    replace this later without changing the public extractor interface.
    """

    score = max(0.0, min(1.0, token.confidence))

    text = token.text.casefold()

    if field == "mrp" and _looks_like_mrp(text):
        score += 0.15

    elif field == "net_quantity" and _looks_like_quantity(text):
        score += 0.15

    elif field == "brand":
        # Longer alphabetic strings are generally better brand candidates.
        alphabetic = sum(ch.isalpha() for ch in token.text)

        if alphabetic >= 3:
            score += 0.10

    return min(score, 1.0)


# ---------------------------------------------------------------------------
# Candidate generation
# ---------------------------------------------------------------------------

def generate_candidates(
    tokens: list[OCRToken],
) -> dict[str, list[FieldCandidate]]:
    """
    Generate field candidates from OCR tokens.

    Spatial relationships are deliberately kept separate from candidate
    generation so the Hungarian matcher can combine both kinds of evidence.
    """

    candidates: dict[str, list[FieldCandidate]] = {
        "brand": [],
        "mrp": [],
        "net_quantity": [],
    }

    for index, token in enumerate(tokens):
        text = normalize_text(token.text)

        if not text:
            continue

        # ---------------------------------------------------------------
        # MRP
        # ---------------------------------------------------------------
        if _looks_like_mrp(text) and not _is_mrp_label(text):
            candidates["mrp"].append(
                FieldCandidate(
                    field="mrp",
                    value=normalize_mrp(text),
                    score=_candidate_score(token, "mrp"),
                    token_indices=[index],
                    evidence={
                        "ocr_confidence": token.confidence,
                        "reason": "numeric_mrp_candidate",
                    },
                )
            )

        # ---------------------------------------------------------------
        # Net quantity
        # ---------------------------------------------------------------
        if _looks_like_quantity(text):
            candidates["net_quantity"].append(
                FieldCandidate(
                    field="net_quantity",
                    value=normalize_quantity(text),
                    score=_candidate_score(token, "net_quantity"),
                    token_indices=[index],
                    evidence={
                        "ocr_confidence": token.confidence,
                        "reason": "quantity_with_unit",
                    },
                )
            )

        # ---------------------------------------------------------------
        # Brand
        # ---------------------------------------------------------------
        #
        # Do NOT classify a token as a brand if it already looks like
        # another structured field.
        #
        # This prevents:
        #
        #   "Net Quantity 500 g"
        #
        # from becoming a brand candidate merely because it contains
        # alphabetic characters.
        #
        if (
            not _is_mrp_label(text)
            and not _is_brand_label(text)
            and not _looks_like_mrp(text)
            and not _looks_like_quantity(text)
        ):
            if any(ch.isalpha() for ch in text):
                candidates["brand"].append(
                    FieldCandidate(
                        field="brand",
                        value=normalize_brand(text),
                        score=_candidate_score(token, "brand"),
                        token_indices=[index],
                        evidence={
                            "ocr_confidence": token.confidence,
                            "reason": "alphabetic_brand_candidate",
                        },
                    )
                )

    return candidates

# ---------------------------------------------------------------------------
# Hungarian matching
# ---------------------------------------------------------------------------

def _field_similarity(
    extracted_value: str,
    candidate_value: str,
) -> float:
    """
    Similarity between an extracted value and a registry value.

    Exact normalized matches receive 1.0.

    A small character-level similarity is used otherwise.
    """

    a = normalize_text(extracted_value).casefold()
    b = normalize_text(candidate_value).casefold()

    if not a or not b:
        return 0.0

    if a == b:
        return 1.0

    # Simple character-set similarity.
    set_a = set(a)
    set_b = set(b)

    intersection = len(set_a & set_b)
    union = len(set_a | set_b)

    if union == 0:
        return 0.0

    return intersection / union


def _sku_field_value(sku: Any, field: str) -> str | None:
    """
    Extract the registry value corresponding to a field.

    Supports the current Sku ORM model without coupling the extractor to
    SQLAlchemy.
    """

    if field == "brand":
        values = getattr(sku, "brand_strings", None)

        if not values:
            return None

        return str(values[0])

    if field == "mrp":
        value = getattr(sku, "mrp_exact", None)

        if value is None:
            return None

        return str(value)

    if field == "net_quantity":
        value = getattr(sku, "net_quantity", None)

        if value is None:
            return None

        return str(value)

    return None


def _match_score(
    extracted: SpatialExtractionResult,
    sku: Any,
) -> tuple[float, dict[str, Any]]:
    """
    Calculate the global compatibility score between an extracted identity
    and a registry SKU.

    Field scores are combined using weighted evidence.
    """

    weights = {
        "brand": 0.45,
        "mrp": 0.30,
        "net_quantity": 0.25,
    }

    evidence: dict[str, Any] = {}

    weighted_score = 0.0
    total_weight = 0.0

    for field, weight in weights.items():
        extracted_value = extracted.identity.get(field)

        if not extracted_value:
            continue

        sku_value = _sku_field_value(sku, field)

        if sku_value is None:
            continue

        similarity = _field_similarity(
            extracted_value,
            sku_value,
        )

        evidence[field] = {
            "extracted": extracted_value,
            "registry": sku_value,
            "score": similarity,
        }

        weighted_score += similarity * weight
        total_weight += weight

    if total_weight == 0:
        return 0.0, evidence

    return weighted_score / total_weight, evidence


def match_sku(
    extracted: SpatialExtractionResult,
    sku: Any,
    rejection_threshold: float = 0.72,
):
    """
    NOT CURRENTLY WIRED UP. An earlier, single-file version of SKU
    matching, kept for its existing test coverage
    (tests/test_spatial_graph_extractor.py) but superseded in the live
    scan pipeline by app/services/registry_matcher.py's match_sku /
    match_registry, which is what app/api/scan.py actually calls. The two
    differ in one behaviourally important way: this version's
    _match_score silently drops any field with no comparable registry
    value from its weighted average (via `if not extracted_value: /
    if sku_value is None: continue`), which does not distinguish "no
    registry value to compare" from "compared and disagreed" the way
    registry_matcher.py's _hungarian_match does. Do not switch the live
    pipeline to this version without carrying that fix over -- see
    registry_matcher.py's _hungarian_match docstring and
    tests/test_registry_matcher.py's
    test_hungarian_match_keeps_mismatched_field_in_evidence_and_score for
    why it matters.

    Match an extracted identity against one Sku.

    The Hungarian algorithm is used over field-to-field assignments.

    For a single SKU this may look excessive, but keeping the assignment
    stage explicit means the same matcher can later operate over multiple
    registry candidates without changing the decision model.
    """

    fields = [
        field
        for field in ("brand", "mrp", "net_quantity")
        if extracted.identity.get(field)
    ]

    if not fields:
        return MatchResult(
            status="NO_CANDIDATE",
            sku=None,
            score=0.0,
            rejection_threshold=rejection_threshold,
            match_method="hungarian-v1",
            evidence={},
            extracted_identity=extracted.identity,
        )

    # Build a field × registry-value matrix.
    #
    # For one SKU, each row represents an extracted field and the matching
    # column represents that SKU's corresponding field.
    matrix = []

    for field in fields:
        extracted_value = extracted.identity[field]
        registry_value = _sku_field_value(sku, field)

        if registry_value is None:
            matrix.append([0.0])
        else:
            matrix.append(
                [
                    _field_similarity(
                        extracted_value,
                        registry_value,
                    )
                ]
            )

    # Hungarian maximizes assignment score by minimizing its negative.
    import numpy as np

    cost_matrix = np.array(
        [
            [-value for value in row]
            for row in matrix
        ],
        dtype=float,
    )

    row_indices, column_indices = linear_sum_assignment(cost_matrix)

    assigned_scores = [
        -cost_matrix[row, column]
        for row, column in zip(row_indices, column_indices)
    ]

    # Combine assigned field similarities.
    score = (
        sum(assigned_scores) / len(assigned_scores)
        if assigned_scores
        else 0.0
    )

    _, evidence = _match_score(extracted, sku)

    if score >= rejection_threshold:
        status = "MATCHED"
        selected_sku = sku
    else:
        status = "REJECTED"
        selected_sku = None

    return MatchResult(
        status=status,
        sku=selected_sku,
        score=score,
        rejection_threshold=rejection_threshold,
        match_method="hungarian-v1",
        evidence=evidence,
        extracted_identity=extracted.identity,
    )


# ---------------------------------------------------------------------------
# Match result
# ---------------------------------------------------------------------------

@dataclass
class MatchResult:
    status: str
    sku: Any | None
    score: float
    rejection_threshold: float
    match_method: str
    evidence: dict[str, Any]
    extracted_identity: dict[str, str]


# ---------------------------------------------------------------------------
# High-level extractor
# ---------------------------------------------------------------------------

def extract(
    tokens: list[OCRToken],
    *,
    max_distance: float = 250.0,
) -> SpatialExtractionResult:
    """
    Run the spatial graph extraction pipeline.
    """

    graph_edges = build_spatial_graph(
        tokens,
        max_distance=max_distance,
    )

    candidates = generate_candidates(tokens) if tokens else {}

    identity: dict[str, str] = {}

    # Select the strongest candidate for each field.
    for field, field_candidates in candidates.items():
        if not field_candidates:
            continue

        best = max(
            field_candidates,
            key=lambda candidate: candidate.score,
        )

        identity[field] = best.value

    serialized_candidates = {
        field: [
            {
                "value": candidate.value,
                "score": candidate.score,
                "token_indices": candidate.token_indices,
                "evidence": candidate.evidence,
            }
            for candidate in field_candidates
        ]
        for field, field_candidates in candidates.items()
    }

    return SpatialExtractionResult(
        identity=identity,
        candidates=serialized_candidates,
        graph_edges=graph_edges,
    )

def extract_spatial_identity(ocr_result) -> SpatialExtractionResult:
    """
    Adapter between the OCR service and the spatial graph extractor.

    The OCR service is expected to provide OCR tokens containing:
        text
        x
        y
        width
        height
        confidence
    """

    tokens = []

    for token in ocr_result.tokens:
        tokens.append(
            OCRToken(
                text=token.text,
                x=token.x,
                y=token.y,
                width=getattr(token, "width", 0.0),
                height=getattr(token, "height", 0.0),
                confidence=getattr(token, "confidence", 1.0),
            )
        )

    return extract(tokens)