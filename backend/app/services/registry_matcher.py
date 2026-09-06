"""
SKU registry matching.

Input:
    SpatialExtractionResult

Process:
    1. Load registered SKU candidates.
    2. Calculate field-level similarities.
    3. Use Hungarian assignment to obtain the best one-to-one
       field correspondence.
    4. Calculate the overall match score.
    5. Apply a rejection threshold.

Output:
    MatchDecision

The matcher never invents a SKU. A candidate below the rejection
threshold is rejected.

SYNCHRONOUS by design. db/sharding.py builds plain sqlalchemy.orm.Session
objects (see ShardRouter.session_for_key) -- there is no async engine
anywhere in this backend's actual request path (app/api/scan.py's
_shard_session dependency yields one of those sessions directly). An
earlier version of this module was written against
sqlalchemy.ext.asyncio.AsyncSession, which cannot be driven by a sync
Session; that mismatch is what made the whole scan/registry-matching path
unreachable. Keep this synchronous unless db/sharding.py itself switches to
an async engine.
"""

from __future__ import annotations

from dataclasses import dataclass

from scipy.optimize import linear_sum_assignment
from sqlalchemy import select
from sqlalchemy.orm import Session

from db.models import Sku
from app.services.spatial_graph_extractor import SpatialExtractionResult


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------


DEFAULT_REJECTION_THRESHOLD = 0.72


# We only compare fields that actually exist in Sku.
MATCH_FIELDS = (
    "brand",
    "mrp",
    "net_quantity",
)


# ---------------------------------------------------------------------------
# Result model
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class FieldMatch:
    extracted_field: str
    registry_field: str
    score: float


@dataclass(frozen=True)
class MatchDecision:
    status: str
    sku: Sku | None
    score: float
    rejection_threshold: float
    match_method: str
    evidence: dict
    extracted_identity: dict[str, str]


# ---------------------------------------------------------------------------
# Normalization
# ---------------------------------------------------------------------------


def _normalize_text(value: str | None) -> str:
    if value is None:
        return ""

    return " ".join(
        value.strip().lower().split()
    )


def _normalize_brand(value: str | None) -> str:
    return _normalize_text(value)


def _normalize_mrp(value: str | float | int | None) -> str:
    if value is None:
        return ""

    try:
        return f"{float(value):.2f}"
    except (TypeError, ValueError):
        return _normalize_text(str(value))


def _normalize_quantity(value: str | None) -> str:
    value = _normalize_text(value)

    replacements = {
        "gm": "g",
        "grams": "g",
        "gram": "g",
        "kgs": "kg",
        "kilograms": "kg",
        "ltr": "l",
        "litre": "l",
        "litres": "l",
    }

    parts = value.split()

    if len(parts) == 2:
        number, unit = parts

        unit = replacements.get(unit, unit)

        return f"{number} {unit}"

    return value


# ---------------------------------------------------------------------------
# Similarity
# ---------------------------------------------------------------------------


def _token_similarity(a: str, b: str) -> float:
    a_tokens = set(_normalize_text(a).split())
    b_tokens = set(_normalize_text(b).split())

    if not a_tokens or not b_tokens:
        return 0.0

    intersection = len(a_tokens & b_tokens)
    union = len(a_tokens | b_tokens)

    return intersection / union


def _field_similarity(
    field: str,
    extracted: str | None,
    sku: Sku,
) -> float:
    if not extracted:
        return 0.0

    if field == "brand":
        extracted_value = _normalize_brand(extracted)

        registry_brands = [
            _normalize_brand(value)
            for value in (sku.brand_strings or [])
        ]

        if not registry_brands:
            return 0.0

        return max(
            _token_similarity(extracted_value, brand)
            for brand in registry_brands
        )

    if field == "mrp":
        extracted_value = _normalize_mrp(extracted)
        registry_value = _normalize_mrp(sku.mrp_exact)

        if not registry_value:
            return 0.0

        return 1.0 if extracted_value == registry_value else 0.0

    if field == "net_quantity":
        extracted_value = _normalize_quantity(extracted)
        registry_value = _normalize_quantity(sku.net_quantity)

        if not registry_value:
            return 0.0

        return 1.0 if extracted_value == registry_value else 0.0

    return 0.0


def _sku_has_value(field: str, sku: Sku) -> bool:
    """
    Whether the registry has anything recorded for this field at all --
    distinct from _field_similarity's 0.0, which means either "no
    registry value" or "registry value present but doesn't match".
    _hungarian_match needs to tell those apart: the first is a fact about
    the registry's completeness (exclude the field from scoring
    entirely), the second is a real mismatch (keep it, and let it drag
    the match score down -- see that function's comments).
    """

    if field == "brand":
        return bool(sku.brand_strings)

    if field == "mrp":
        return sku.mrp_exact is not None

    if field == "net_quantity":
        return bool(sku.net_quantity)

    return False


def _sku_field_display_value(sku: Sku, field: str) -> str | None:
    """
    Human-readable registry value for a field, for evidence/messages --
    NOT used for scoring (see _field_similarity for that). Kept separate
    so a display tweak here can never silently change match outcomes.
    """

    if field == "brand":
        brands = sku.brand_strings or []
        return brands[0] if brands else None

    if field == "mrp":
        return _normalize_mrp(sku.mrp_exact) or None

    if field == "net_quantity":
        return sku.net_quantity

    return None


# ---------------------------------------------------------------------------
# Hungarian assignment
# ---------------------------------------------------------------------------


def _hungarian_match(
    extracted_identity: dict[str, str],
    sku: Sku,
) -> tuple[float, list[FieldMatch], dict]:
    """
    Match extracted fields against the SKU's available fields.

    Hungarian assignment is used instead of independently taking the
    maximum score for every field.

    This guarantees a one-to-one assignment.
    """

    extracted_fields = [
        field
        for field in MATCH_FIELDS
        if extracted_identity.get(field)
    ]

    if not extracted_fields:
        return 0.0, [], {}

    # Every SKU has the same conceptual field slots.
    registry_fields = list(MATCH_FIELDS)

    matrix: list[list[float]] = []

    for extracted_field in extracted_fields:
        row = []

        for registry_field in registry_fields:
            if extracted_field != registry_field:
                row.append(0.0)
                continue

            score = _field_similarity(
                extracted_field,
                extracted_identity[extracted_field],
                sku,
            )

            row.append(score)

        matrix.append(row)

    # Hungarian solves minimum-cost assignment.
    # Therefore convert similarity into cost.
    costs = [
        [
            1.0 - score
            for score in row
        ]
        for row in matrix
    ]

    row_indices, col_indices = linear_sum_assignment(costs)

    matches: list[FieldMatch] = []
    evidence: dict = {}

    total_score = 0.0

    for row_index, col_index in zip(
        row_indices,
        col_indices,
    ):
        extracted_field = extracted_fields[row_index]
        registry_field = registry_fields[col_index]

        # A genuine non-assignment: the extracted field has no
        # corresponding registry field at all (only same-named fields
        # ever score > 0 in this matrix -- see the loop building `matrix`
        # above). Exclude these; they carry no comparison to report.
        if extracted_field != registry_field:
            continue

        # A registry field with no value recorded is a fact about the
        # registry ("this SKU never had an MRP entered"), not a
        # comparison outcome -- exclude it, rather than letting a blank
        # registry field count as agreement OR disagreement.
        if not _sku_has_value(registry_field, sku):
            continue

        score = matrix[row_index][col_index]

        # IMPORTANT: score == 0.0 (a real mismatch -- e.g. the label says
        # 45.00 and the registry says 99.00) is kept, not dropped. A
        # previous version of this function discarded any `score <= 0`
        # row here, which meant a wrong MRP silently vanished from both
        # the overall match score and the evidence trail instead of
        # dragging the match down or surfacing as a comparable FAIL --
        # the registry match would report MATCHED at full confidence
        # while quietly never having checked the one field that was
        # actually wrong.
        matches.append(
            FieldMatch(
                extracted_field=extracted_field,
                registry_field=registry_field,
                score=score,
            )
        )

        evidence[extracted_field] = {
            "registry_field": registry_field,
            "score": round(score, 4),
            "extracted": extracted_identity[extracted_field],
            # The registry's own value for this field, so a FAIL message
            # can say what the pack should have said, not just that it
            # didn't match.
            "registry": _sku_field_display_value(sku, registry_field),
        }

        total_score += score

    if not matches:
        return 0.0, [], evidence

    overall_score = total_score / len(matches)

    return (
        overall_score,
        matches,
        evidence,
    )


# ---------------------------------------------------------------------------
# Candidate matching
# ---------------------------------------------------------------------------


def match_sku(
    extracted: SpatialExtractionResult,
    sku: Sku,
    rejection_threshold: float = DEFAULT_REJECTION_THRESHOLD,
) -> MatchDecision:
    score, matches, evidence = _hungarian_match(
        extracted.identity,
        sku,
    )

    if not matches:
        return MatchDecision(
            status="REJECTED",
            sku=None,
            score=0.0,
            rejection_threshold=rejection_threshold,
            match_method="hungarian-v1",
            evidence=evidence,
            extracted_identity=extracted.identity,
        )

    if score < rejection_threshold:
        return MatchDecision(
            status="REJECTED",
            sku=None,
            score=score,
            rejection_threshold=rejection_threshold,
            match_method="hungarian-v1",
            evidence=evidence,
            extracted_identity=extracted.identity,
        )

    return MatchDecision(
        status="MATCHED",
        sku=sku,
        score=score,
        rejection_threshold=rejection_threshold,
        match_method="hungarian-v1",
        evidence=evidence,
        extracted_identity=extracted.identity,
    )


# ---------------------------------------------------------------------------
# Registry search
# ---------------------------------------------------------------------------


def match_registry(
    db: Session,
    extracted: SpatialExtractionResult,
    rejection_threshold: float = DEFAULT_REJECTION_THRESHOLD,
) -> MatchDecision:
    """
    Search the Sku registry and select the highest-scoring candidate.
    """

    result = db.execute(
        select(Sku)
    )

    skus = result.scalars().all()

    if not skus:
        return MatchDecision(
            status="NO_CANDIDATE",
            sku=None,
            score=0.0,
            rejection_threshold=rejection_threshold,
            match_method="hungarian-v1",
            evidence={},
            extracted_identity=extracted.identity,
        )

    best_decision: MatchDecision | None = None

    for sku in skus:
        decision = match_sku(
            extracted,
            sku,
            rejection_threshold,
        )

        if best_decision is None:
            best_decision = decision
            continue

        if decision.score > best_decision.score:
            best_decision = decision

    assert best_decision is not None

    return best_decision


# ---------------------------------------------------------------------------
# Persistence
# ---------------------------------------------------------------------------


def save_match_registry(
    db: Session,
    scan_id: str,
    decision: MatchDecision,
):
    """
    Persist the registry matching decision for a scan. Returns the
    inserted db.models.MatchesRegistry row (imported locally to avoid a
    module-level import cycle with db.models, which does not itself
    import from app.services).

    Does not commit -- the caller (app/api/scan.py) commits once, together
    with the Scan and ScanCheck rows, so a scan and its match decision are
    never observable in an inconsistent state.
    """

    from db.models import MatchesRegistry

    record = MatchesRegistry(
        scan_id=scan_id,
        sku_id=decision.sku.id if decision.sku is not None else None,
        status=decision.status,
        score=decision.score,
        rejection_threshold=decision.rejection_threshold,
        match_method=decision.match_method,
        evidence=decision.evidence,
        extracted_identity=decision.extracted_identity,
    )

    db.add(record)
    db.flush()

    return record