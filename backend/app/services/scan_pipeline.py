from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.ocr import OcrResult
from app.services.registry_matcher import (
    MatchDecision,
    match_registry,
)
from app.services.match_registry import save_match_registry
from app.services.spatial_graph_extractor import (
    SpatialExtractionResult,
    extract_spatial_identity,
)


@dataclass(frozen=True)
class ScanIdentityResult:
    spatial: SpatialExtractionResult
    registry: MatchDecision


async def extract_and_match(
    db: AsyncSession,
    ocr_result: OcrResult,
    scan_id: str,
) -> ScanIdentityResult:
    """
    Run spatial extraction, SKU registry matching,
    and persist the matching decision.
    """

    spatial = extract_spatial_identity(
        ocr_result
    )

    registry = await match_registry(
        db=db,
        extracted=spatial,
    )

    await save_match_registry(
        db=db,
        scan_id=scan_id,
        decision=registry,
    )

    return ScanIdentityResult(
        spatial=spatial,
        registry=registry,
    )