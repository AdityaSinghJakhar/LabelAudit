"""
Runs spatial extraction and SKU registry matching over an OCR result.

SYNCHRONOUS, deliberately -- see registry_matcher.py's module docstring.
This module does not persist anything itself: app/api/scan.py owns the
Scan row's id (it does not exist until that row is inserted and flushed),
so the MatchesRegistry row is built and added to the session there, after
the Scan row has an id, using the MatchDecision this module returns. That
keeps "run the pipeline" and "decide what a row's foreign key points at"
in the caller, where the id is actually known, instead of threading a
not-yet-existing id down into this module.
"""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy.orm import Session

from app.models.ocr import OcrResult
from app.services.registry_matcher import (
    MatchDecision,
    match_registry,
)
from app.services.spatial_graph_extractor import (
    SpatialExtractionResult,
    extract_spatial_identity,
)


@dataclass(frozen=True)
class ScanIdentityResult:
    spatial: SpatialExtractionResult
    registry: MatchDecision


def extract_and_match(
    db: Session,
    ocr_result: OcrResult,
) -> ScanIdentityResult:
    """
    Run spatial extraction and SKU registry matching.

    Does not write anything to the database -- it only reads the Sku
    registry (via match_registry) to decide whether the extracted identity
    corresponds to a known product. Persisting the decision is the
    caller's job once a Scan row (and therefore a scan_id) exists.
    """

    spatial = extract_spatial_identity(ocr_result)

    registry = match_registry(
        db=db,
        extracted=spatial,
    )

    return ScanIdentityResult(
        spatial=spatial,
        registry=registry,
    )
