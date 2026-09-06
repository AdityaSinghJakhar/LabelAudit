"""
SKU registry management -- CRUD over db.models.Sku.

This is the "registered reference products" table that
app/services/registry_matcher.py compares scans against (see that
module's and db/models.py's docstrings on AUTHORITATIVE vs ASSERTED). It
has no shard key of its own -- unlike Device/Scan/ScanCheck, a Sku is not
scoped to one device, so it is not sharded by device_id. It lives on shard
0 (the first configured shard), which db/sharding.py's ShardRouter always
constructs regardless of shard count. A deployment that never configures
DATABASE_SHARD_URLS (single-shard mode, e.g. local dev or a small SIH
prototype) is unaffected: shard 0 is the only shard there is.

Known limitation, stated rather than hidden per HANDOFF.md's own
convention: in a genuinely multi-shard deployment, every registry lookup
and write goes to shard 0 specifically. That is fine for a single-SKU or
small-catalog prototype (this endpoint exists to manage exactly that) but
would need the registry moved to a small shared/coordinator database, not
a per-device shard, before it could scale independently of shard 0's own
load.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Response, status
from sqlalchemy.orm import Session

from app.models.sku import SkuCreate, SkuOut, SkuUpdate
from db import models
from db.sharding import router as shard_router

router = APIRouter(tags=["registry"])


def _registry_session():
    """
    The registry lives on shard 0 -- see this module's docstring. Using
    session_for_key with a fixed key (rather than
    session_factories[0]() directly) keeps this call going through the
    same ShardRouter surface every other session in the app uses, so a
    future change to how shard 0 specifically is selected only has to
    happen in one place.
    """
    session, _shard_index = shard_router.session_for_key("__registry__")
    try:
        yield session
    finally:
        session.close()


def _get_or_404(db: Session, sku_id: str) -> models.Sku:
    sku = db.query(models.Sku).filter_by(sku_id=sku_id).one_or_none()
    if sku is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"No SKU registered with sku_id '{sku_id}'.",
        )
    return sku


@router.post(
    "/skus",
    response_model=SkuOut,
    status_code=status.HTTP_201_CREATED,
)
def create_sku(
    payload: SkuCreate,
    db: Session = Depends(_registry_session),
) -> models.Sku:
    existing = db.query(models.Sku).filter_by(sku_id=payload.sku_id).one_or_none()
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"A SKU with sku_id '{payload.sku_id}' is already registered.",
        )

    sku = models.Sku(
        sku_id=payload.sku_id,
        authority=payload.authority,
        brand_strings=payload.brand_strings,
        mrp_exact=payload.mrp_exact,
        net_quantity=payload.net_quantity,
        note=payload.note,
        enrolled_by_device_id=payload.enrolled_by_device_id,
    )
    db.add(sku)
    db.commit()
    db.refresh(sku)
    return sku


@router.get("/skus", response_model=list[SkuOut])
def list_skus(db: Session = Depends(_registry_session)) -> list[models.Sku]:
    return db.query(models.Sku).order_by(models.Sku.created_at.desc()).all()


@router.get("/skus/{sku_id}", response_model=SkuOut)
def get_sku(sku_id: str, db: Session = Depends(_registry_session)) -> models.Sku:
    return _get_or_404(db, sku_id)


@router.patch("/skus/{sku_id}", response_model=SkuOut)
def update_sku(
    sku_id: str,
    payload: SkuUpdate,
    db: Session = Depends(_registry_session),
) -> models.Sku:
    sku = _get_or_404(db, sku_id)

    updates = payload.model_dump(exclude_unset=True)
    for field, value in updates.items():
        setattr(sku, field, value)

    db.commit()
    db.refresh(sku)
    return sku


@router.delete(
    "/skus/{sku_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_model=None,
)
def delete_sku(sku_id: str, db: Session = Depends(_registry_session)) -> Response:
    sku = _get_or_404(db, sku_id)
    db.delete(sku)
    db.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)
