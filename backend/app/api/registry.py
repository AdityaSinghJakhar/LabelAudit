import time
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.core.security import Role, get_optional_device, require_role
from db import models
from db.session import get_db

router = APIRouter(prefix="/v1/skus", tags=["skus"])


class SkuCreateIn(BaseModel):
    sku_id: str = Field(..., description="Unique product SKU ID, e.g. GOKUL-NAMKEEN-500G")
    brand_strings: List[str] = Field(default_factory=list)
    mrp_exact: Optional[float] = None
    net_quantity: Optional[str] = None
    manufacturer_address: Optional[str] = None
    consumer_care: Optional[str] = None
    fssai_licence: Optional[str] = None
    source: str = Field(default="ENROLLED_FROM_SCAN", description="ENROLLED_FROM_SCAN | IMPORTED | MANUAL")
    note: str = ""
    saved_at: Optional[int] = None


class SkuOut(BaseModel):
    sku_id: str
    authority: str
    source: str
    brand_strings: List[str]
    mrp_exact: Optional[float] = None
    net_quantity: Optional[str] = None
    manufacturer_address: Optional[str] = None
    consumer_care: Optional[str] = None
    fssai_licence: Optional[str] = None
    note: str
    saved_at: int


def _resolve_authority(source: str) -> str:
    """
    Preserves AUTHORITATIVE vs ASSERTED.
    Only IMPORTED (official brand/regulator master) carries AUTHORITATIVE authority.
    Scanned or manual enrolments are ASSERTED, so an enrolled reference can never
    substantiate a FAIL on another pack.
    """
    src = source.upper()
    if src == "IMPORTED":
        return "AUTHORITATIVE"
    return "ASSERTED"


@router.post("", response_model=SkuOut, status_code=status.HTTP_201_CREATED)
def enrol_sku(
    payload: SkuCreateIn,
    device: models.Device = Depends(require_role(Role.INSPECTOR)),
    db: Session = Depends(get_db),
):
    """
    Inspector-only reference enrolment.
    Mirrors Enrolment.fromScan on Kotlin side.
    """
    source_upper = payload.source.upper()
    if source_upper not in {"ENROLLED_FROM_SCAN", "IMPORTED", "MANUAL"}:
        source_upper = "ENROLLED_FROM_SCAN"

    authority = _resolve_authority(source_upper)
    saved_at = payload.saved_at or int(time.time() * 1000)

    sku = db.query(models.Sku).filter(models.Sku.sku_id == payload.sku_id).one_or_none()
    if not sku:
        sku = models.Sku(
            sku_id=payload.sku_id,
            authority=authority,
            source=source_upper,
            brand_strings=payload.brand_strings,
            mrp_exact=payload.mrp_exact,
            net_quantity=payload.net_quantity,
            manufacturer_address=payload.manufacturer_address,
            consumer_care=payload.consumer_care,
            fssai_licence=payload.fssai_licence,
            note=payload.note,
            enrolled_by_device_id=device.id,
            saved_at=saved_at,
        )
        db.add(sku)
    else:
        sku.authority = authority
        sku.source = source_upper
        sku.brand_strings = payload.brand_strings
        sku.mrp_exact = payload.mrp_exact
        sku.net_quantity = payload.net_quantity
        sku.manufacturer_address = payload.manufacturer_address
        sku.consumer_care = payload.consumer_care
        sku.fssai_licence = payload.fssai_licence
        sku.note = payload.note
        sku.enrolled_by_device_id = device.id
        sku.saved_at = saved_at

    db.commit()
    db.refresh(sku)
    return _format_sku(sku)


@router.get("", response_model=List[SkuOut])
def list_skus(
    db: Session = Depends(get_db),
    device: Optional[models.Device] = Depends(get_optional_device),
):
    """
    Pulled by every device on app start/refresh.
    Open to Shoppers and Inspectors.
    """
    skus = db.query(models.Sku).order_by(models.Sku.sku_id.asc()).all()
    return [_format_sku(s) for s in skus]


@router.get("/{sku_id}", response_model=SkuOut)
def get_sku(
    sku_id: str,
    db: Session = Depends(get_db),
    device: Optional[models.Device] = Depends(get_optional_device),
):
    sku = db.query(models.Sku).filter(models.Sku.sku_id == sku_id).one_or_none()
    if not sku:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="SKU not found")
    return _format_sku(sku)


def _format_sku(sku: models.Sku) -> SkuOut:
    return SkuOut(
        sku_id=sku.sku_id,
        authority=sku.authority,
        source=sku.source,
        brand_strings=sku.brand_strings or [],
        mrp_exact=sku.mrp_exact,
        net_quantity=sku.net_quantity,
        manufacturer_address=sku.manufacturer_address,
        consumer_care=sku.consumer_care,
        fssai_licence=sku.fssai_licence,
        note=sku.note or "",
        saved_at=sku.saved_at,
    )
