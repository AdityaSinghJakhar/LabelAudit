from collections import Counter
from typing import Dict, List, Optional

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel
from sqlalchemy.orm import Session, joinedload

from app.core.security import get_optional_device
from db import models
from db.session import get_db

router = APIRouter(prefix="/v1/reports", tags=["reports"])


class ViolationItem(BaseModel):
    rule_id: str
    count: int


class FleetSummaryOut(BaseModel):
    total: int
    by_verdict: Dict[str, int]
    failed: int
    passed: int
    conclusive_rate: float
    top_violations: List[ViolationItem]
    distinct_products: int


@router.get("/summary", response_model=FleetSummaryOut)
def fleet_summary(
    device_id: Optional[str] = Query(None, description="Filter by device ID"),
    since_ms: Optional[int] = Query(None, description="Start timestamp ms (inclusive)"),
    until_ms: Optional[int] = Query(None, description="End timestamp ms (inclusive)"),
    db: Session = Depends(get_db),
    device: Optional[models.Device] = Depends(get_optional_device),
):
    """
    Port of HistoryStore.Summary logic computed across all synced scans
    (fleet-wide or scoped by device / date range).
    """
    query = db.query(models.Scan).options(joinedload(models.Scan.checks))

    if device_id:
        matching_dev = db.query(models.Device).filter(models.Device.device_id == device_id).one_or_none()
        if matching_dev:
            query = query.filter(models.Scan.device_id == matching_dev.id)
        else:
            query = query.filter(models.Scan.device_id == device_id)

    if since_ms is not None:
        query = query.filter(models.Scan.scanned_at >= since_ms)

    if until_ms is not None:
        query = query.filter(models.Scan.scanned_at <= until_ms)

    scans = query.all()

    total = len(scans)
    by_verdict: Dict[str, int] = {}
    distinct_prods = set()
    fail_counts = Counter()

    for s in scans:
        v = s.verdict.upper()
        by_verdict[v] = by_verdict.get(v, 0) + 1

        prod = s.sku_id or (s.brand.strip() if s.brand and s.brand.strip() else None)
        if prod:
            distinct_prods.add(prod)

        for c in s.checks:
            if c.status == "FAIL":
                fail_counts[c.rule_id] += 1

    failed = by_verdict.get("FAIL", 0)
    passed = by_verdict.get("PASS", 0)
    conclusive_rate = (failed + passed) / total if total > 0 else 0.0

    top_violations = [
        ViolationItem(rule_id=rule_id, count=cnt)
        for rule_id, cnt in fail_counts.most_common(5)
    ]

    return FleetSummaryOut(
        total=total,
        by_verdict=by_verdict,
        failed=failed,
        passed=passed,
        conclusive_rate=round(conclusive_rate, 4),
        top_violations=top_violations,
        distinct_products=len(distinct_prods),
    )
