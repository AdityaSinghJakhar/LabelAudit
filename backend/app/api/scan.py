from __future__ import annotations

import uuid
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field
from sqlalchemy import or_
from sqlalchemy.orm import Session, joinedload

from app.core.security import Role, get_current_device, get_optional_device
from db import models
from db.session import get_db
from labelguard.rules.loader import load_ruleset

router = APIRouter(prefix="/v1/scans", tags=["scans"])

# Load canonical ruleset once at startup to validate ruleset_version and citations
try:
    _CANONICAL_RULESET = load_ruleset()
    KNOWN_RULESET_VERSIONS = {_CANONICAL_RULESET.version}
    RULE_CITATIONS = {rule.id: rule.citation for rule in _CANONICAL_RULESET.rules}
except Exception:
    KNOWN_RULESET_VERSIONS = {"2026.1.0"}
    RULE_CITATIONS = {}

VALID_VERDICTS = {"PASS", "FAIL", "NEEDS_REVIEW", "NOT_ASSESSABLE"}
VALID_CHECK_STATUSES = {
    "PASS",
    "FAIL",
    "NEEDS_REVIEW",
    "NOT_ASSESSABLE",
    "NOT_APPLICABLE",
    "EXEMPT",
}


class CheckIn(BaseModel):
    rule_id: str
    rule_name: str = ""
    field: str
    status: str
    message: str = ""
    observed_value: Optional[str] = None
    citation: Optional[str] = None


class CheckOut(BaseModel):
    rule_id: str
    rule_name: str
    field: str
    status: str
    citation: str
    message: str
    observed_value: Optional[str] = None


class ScanSyncIn(BaseModel):
    id: Optional[str] = None
    scanned_at: int = Field(default=0, description="Epoch milliseconds timestamp")
    verdict: str
    ruleset_version: str
    sku_id: Optional[str] = None
    brand: Optional[str] = None
    mrp: Optional[str] = None
    net_quantity: Optional[str] = None
    batch_number: Optional[str] = None
    mfg_date: Optional[str] = None
    frames_used: int = 0
    raw_lines: List[str] = Field(default_factory=list)
    checks: List[CheckIn] = Field(default_factory=list)


class ScanDetailOut(BaseModel):
    id: str
    device_id: Optional[str] = None
    scanned_at: int
    verdict: str
    ruleset_version: str
    sku_id: Optional[str] = None
    brand: Optional[str] = None
    mrp: Optional[str] = None
    net_quantity: Optional[str] = None
    batch_number: Optional[str] = None
    mfg_date: Optional[str] = None
    frames_used: int
    raw_lines: List[str]
    checks: List[CheckOut]


class ScanPageOut(BaseModel):
    items: List[ScanDetailOut]
    total: int
    page: int
    limit: int
    pages: int


@router.post("", response_model=ScanDetailOut, status_code=status.HTTP_201_CREATED)
def sync_scan(
    payload: ScanSyncIn,
    device: Optional[models.Device] = Depends(get_optional_device),
    db: Session = Depends(get_db),
):
    """
    Accepts the ScanRecord.toJson() shape from Android client verbatim.
    Validates ruleset_version against canonical ruleset.
    Idempotent on scan ID.
    """
    # 1. Validate ruleset_version
    if payload.ruleset_version not in KNOWN_RULESET_VERSIONS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=(
                f"Unknown ruleset_version '{payload.ruleset_version}'. "
                f"Supported versions: {', '.join(KNOWN_RULESET_VERSIONS)}"
            ),
        )

    # 2. Validate verdict
    verdict_upper = payload.verdict.upper()
    if verdict_upper not in VALID_VERDICTS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Invalid verdict '{payload.verdict}'. Must be one of {VALID_VERDICTS}",
        )

    scan_id = payload.id or str(uuid.uuid4())

    # Idempotency check
    existing = (
        db.query(models.Scan)
        .options(joinedload(models.Scan.checks))
        .filter(models.Scan.id == scan_id)
        .one_or_none()
    )
    if existing:
        return _format_scan(existing)

    scan = models.Scan(
        id=scan_id,
        device_id=device.id if device else None,
        scanned_at=payload.scanned_at,
        verdict=verdict_upper,
        ruleset_version=payload.ruleset_version,
        sku_id=payload.sku_id,
        brand=payload.brand,
        mrp=payload.mrp,
        net_quantity=payload.net_quantity,
        batch_number=payload.batch_number,
        mfg_date=payload.mfg_date,
        frames_used=payload.frames_used,
        raw_lines=payload.raw_lines,
    )
    db.add(scan)
    db.flush()

    for c in payload.checks:
        status_upper = c.status.upper()
        if status_upper not in VALID_CHECK_STATUSES:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"Invalid check status '{c.status}' for rule '{c.rule_id}'",
            )
        # Populate statutory citation if missing
        citation = c.citation or RULE_CITATIONS.get(c.rule_id, "Statutory requirement")

        scan_check = models.ScanCheck(
            scan_id=scan.id,
            rule_id=c.rule_id,
            rule_name=c.rule_name,
            field=c.field,
            status=status_upper,
            citation=citation,
            message=c.message,
            observed_value=c.observed_value,
        )
        db.add(scan_check)

    db.commit()
    db.refresh(scan)
    return _format_scan(scan)


@router.get("", response_model=ScanPageOut)
def list_scans(
    page: int = Query(1, ge=1),
    limit: int = Query(20, ge=1, le=100),
    device_id: Optional[str] = None,
    verdict: Optional[str] = None,
    q: Optional[str] = None,
    device: Optional[models.Device] = Depends(get_optional_device),
    db: Session = Depends(get_db),
):
    """
    Paginated, filterable scans.
    CONSUMER role only sees scans created by their own device.
    INSPECTOR role can see fleet-wide scans or filter by device_id.
    """
    query = db.query(models.Scan).options(joinedload(models.Scan.checks))

    # Role-based scoping
    if device and device.role == Role.CONSUMER.value:
        query = query.filter(models.Scan.device_id == device.id)
    elif device_id:
        # Inspector or explicit filter
        matching_dev = db.query(models.Device).filter(models.Device.device_id == device_id).one_or_none()
        if matching_dev:
            query = query.filter(models.Scan.device_id == matching_dev.id)
        else:
            query = query.filter(models.Scan.device_id == device_id)

    if verdict:
        query = query.filter(models.Scan.verdict == verdict.upper())

    if q and q.strip():
        term = f"%{q.strip().lower()}%"
        # Search over sku_id, brand, mrp, net_quantity, batch_number, mfg_date, verdict
        query = query.filter(
            or_(
                models.Scan.sku_id.ilike(term),
                models.Scan.brand.ilike(term),
                models.Scan.mrp.ilike(term),
                models.Scan.net_quantity.ilike(term),
                models.Scan.batch_number.ilike(term),
                models.Scan.mfg_date.ilike(term),
                models.Scan.verdict.ilike(term),
            )
        )

    total = query.count()
    offset = (page - 1) * limit
    scans = query.order_by(models.Scan.scanned_at.desc()).offset(offset).limit(limit).all()
    pages = (total + limit - 1) // limit if total > 0 else 1

    return ScanPageOut(
        items=[_format_scan(s) for s in scans],
        total=total,
        page=page,
        limit=limit,
        pages=pages,
    )


@router.get("/{scan_id}", response_model=ScanDetailOut)
def get_scan(
    scan_id: str,
    device: Optional[models.Device] = Depends(get_optional_device),
    db: Session = Depends(get_db),
):
    scan = (
        db.query(models.Scan)
        .options(joinedload(models.Scan.checks))
        .filter(models.Scan.id == scan_id)
        .one_or_none()
    )
    if not scan:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Scan not found")

    # If consumer, ensure it belongs to them
    if device and device.role == Role.CONSUMER.value and scan.device_id != device.id:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Access denied")

    return _format_scan(scan)


def _format_scan(scan: models.Scan) -> ScanDetailOut:
    return ScanDetailOut(
        id=scan.id,
        device_id=scan.device_id,
        scanned_at=scan.scanned_at,
        verdict=scan.verdict,
        ruleset_version=scan.ruleset_version,
        sku_id=scan.sku_id,
        brand=scan.brand,
        mrp=scan.mrp,
        net_quantity=scan.net_quantity,
        batch_number=scan.batch_number,
        mfg_date=scan.mfg_date,
        frames_used=scan.frames_used,
        raw_lines=scan.raw_lines or [],
        checks=[
            CheckOut(
                rule_id=c.rule_id,
                rule_name=c.rule_name,
                field=c.field,
                status=c.status,
                citation=c.citation,
                message=c.message,
                observed_value=c.observed_value,
            )
            for c in scan.checks
        ],
    )