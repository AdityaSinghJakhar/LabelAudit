from collections import defaultdict
from typing import List, Optional

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.core.security import get_optional_device
from db import models
from db.session import get_db

router = APIRouter(prefix="/v1/conflicts", tags=["conflicts"])


class ConflictOut(BaseModel):
    product: str
    scans: int
    conflicting_prices: List[str]
    conflicting_quantities: List[str]


@router.get("", response_model=List[ConflictOut])
def list_conflicts(
    device_id: Optional[str] = Query(None, description="Optionally limit to scans from one device"),
    limit: int = Query(50, ge=1, le=500),
    db: Session = Depends(get_db),
    device: Optional[models.Device] = Depends(get_optional_device),
):
    """
    Port of HistoryStore.conflicts() logic server-side across all devices.
    Groups scans by product (sku_id ?: brand), identifies packs with
    conflicting distinct MRPs or net quantities.
    """
    query = db.query(models.Scan)
    if device_id:
        query = query.filter(models.Scan.device_id == device_id)

    scans = query.all()

    # Group by product
    by_product = defaultdict(list)
    for s in scans:
        product = s.sku_id or (s.brand.strip() if s.brand and s.brand.strip() else None)
        if not product:
            continue
        by_product[product].append(s)

    conflicts: List[ConflictOut] = []
    for product, product_scans in by_product.items():
        # Distinct prices and quantities
        prices = list(dict.fromkeys(s.mrp.strip() for s in product_scans if s.mrp and s.mrp.strip()))
        quantities = list(dict.fromkeys(s.net_quantity.strip() for s in product_scans if s.net_quantity and s.net_quantity.strip()))

        if len(prices) < 2 and len(quantities) < 2:
            continue

        conflicts.append(
            ConflictOut(
                product=product,
                scans=len(product_scans),
                conflicting_prices=prices if len(prices) > 1 else [],
                conflicting_quantities=quantities if len(quantities) > 1 else [],
            )
        )

    conflicts.sort(key=lambda c: c.scans, reverse=True)
    return conflicts[:limit]
