from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from db.models import MatchesRegistry, Sku

from app.services.registry_matcher import MatchDecision

async def find_matching_sku(
    db: AsyncSession,
    extracted_identity: dict[str, Any],
) -> tuple[Sku | None, float, dict[str, Any]]:
    """
    Find the best registered SKU for an extracted identity.

    Matching is intentionally conservative.

    The registry should never silently convert weak evidence into an
    authoritative SKU relationship.
    """

    sku_query = await db.execute(
        select(Sku)
    )

    skus = sku_query.scalars().all()

    if not skus:
        return None, 0.0, {
            "reason": "NO_REGISTERED_SKUS",
        }

    best_sku: Sku | None = None
    best_score = 0.0
    best_evidence: dict[str, Any] = {}

    for sku in skus:

        score, evidence = _sku_similarity(
            extracted_identity,
            sku,
        )

        if score > best_score:
            best_score = score
            best_sku = sku
            best_evidence = evidence

    return (
        best_sku,
        best_score,
        best_evidence,
    )


def _normalise(value: Any) -> str:
    if value is None:
        return ""

    return " ".join(
        str(value)
        .strip()
        .lower()
        .split()
    )


def _sku_similarity(
    extracted: dict[str, Any],
    sku: Sku,
) -> tuple[float, dict[str, Any]]:
    """
    Compare extracted identity against a registered SKU.

    Signals:
      brand
      MRP
      net quantity

    The score is deliberately transparent and stored as evidence.
    """

    signals: list[tuple[str, float]] = []

    evidence: dict[str, Any] = {
        "signals": [],
    }

    # ---------------------------------------------------------------
    # Brand
    # ---------------------------------------------------------------

    extracted_brand = _normalise(
        extracted.get("brand")
        or extracted.get("manufacturer")
    )

    if extracted_brand:
        registered_brands = [
            _normalise(x)
            for x in (sku.brand_strings or [])
        ]

        brand_match = (
            extracted_brand in registered_brands
            or any(
                extracted_brand in brand
                or brand in extracted_brand
                for brand in registered_brands
            )
        )

        brand_score = 1.0 if brand_match else 0.0

        signals.append(
            ("brand", brand_score)
        )

        evidence["signals"].append(
            {
                "field": "brand",
                "observed": extracted_brand,
                "expected": registered_brands,
                "score": brand_score,
            }
        )

    # ---------------------------------------------------------------
    # MRP
    # ---------------------------------------------------------------

    extracted_mrp = extracted.get("mrp")

    if extracted_mrp is not None and sku.mrp_exact is not None:

        try:
            observed = float(
                str(extracted_mrp)
                .replace("₹", "")
                .replace(",", "")
                .strip()
            )

            expected = float(sku.mrp_exact)

            if expected == 0:
                mrp_score = 0.0
            elif abs(observed - expected) < 0.01:
                mrp_score = 1.0
            else:
                mrp_score = 0.0

            signals.append(
                ("mrp", mrp_score)
            )

            evidence["signals"].append(
                {
                    "field": "mrp",
                    "observed": observed,
                    "expected": expected,
                    "score": mrp_score,
                }
            )

        except (TypeError, ValueError):
            pass

    # ---------------------------------------------------------------
    # Net quantity
    # ---------------------------------------------------------------

    extracted_quantity = _normalise(
        extracted.get("net_quantity")
    )

    expected_quantity = _normalise(
        sku.net_quantity
    )

    if extracted_quantity and expected_quantity:

        quantity_match = (
            extracted_quantity == expected_quantity
        )

        quantity_score = (
            1.0
            if quantity_match
            else 0.0
        )

        signals.append(
            ("net_quantity", quantity_score)
        )

        evidence["signals"].append(
            {
                "field": "net_quantity",
                "observed": extracted_quantity,
                "expected": expected_quantity,
                "score": quantity_score,
            }
        )

    # ---------------------------------------------------------------
    # Aggregate
    # ---------------------------------------------------------------

    if not signals:
        return 0.0, evidence

    score = sum(
        score
        for _, score in signals
    ) / len(signals)

    evidence["score"] = score

    return score, evidence


async def persist_match(
    db: AsyncSession,
    *,
    scan_id: str,
    sku_id: str | None,
    status: str,
    score: float,
    rejection_threshold: float,
    match_method: str,
    evidence: dict[str, Any] | list[Any],
    extracted_identity: dict[str, Any],
) -> MatchesRegistry:

    registry = MatchesRegistry(
        scan_id=scan_id,
        sku_id=sku_id,
        status=status,
        score=score,
        rejection_threshold=rejection_threshold,
        match_method=match_method,
        evidence=evidence,
        extracted_identity=extracted_identity,
        created_at=datetime.now(timezone.utc),
    )

    db.add(registry)

    await db.flush()

    return registry

from db.models import MatchesRegistry


async def save_match_registry(
    db: AsyncSession,
    scan_id: str,
    decision: MatchDecision,
) -> MatchesRegistry:
    """
    Persist the registry matching decision for a scan.
    """

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

    await db.flush()

    return record