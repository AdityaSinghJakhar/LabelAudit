"""
POST /api/scans -- server-side OCR path.

Distinct from the phone's own sync of on-device scans (which would live in
a separate `POST /api/scans/sync` accepting an already-computed verdict --
not built here, out of scope for this change). This endpoint does the work
itself: an image goes in, OCR + naive extraction + rule evaluation run on
this backend, and a Scan (source="server_ocr") + its ScanChecks come out
persisted on whichever shard the device_id hashes to.
"""

import asyncio
from datetime import datetime, timezone
from uuid import uuid4

from fastapi import APIRouter, File, Form, HTTPException, UploadFile, status
from fastapi.params import Depends

from app.config import settings
from app.models.scan import ScanCheckOut, ScanResult
from app.services import ocr_service, rules_service, storage_service
from db import models
from db.sharding import router as shard_router

router = APIRouter(tags=["scan"])


def _shard_session(device_id: str = Form(...)):
    """
    Dependency that reads device_id from the same multipart body the route
    handler reads `image` from, and yields the session bound to whichever
    shard that device_id hashes to (see db/sharding.py).
    """
    session, shard_index = shard_router.session_for_key(device_id)
    try:
        yield session, shard_index, device_id
    finally:
        session.close()


def _get_or_create_device(db, device_id: str) -> models.Device:
    device = db.query(models.Device).filter_by(device_id=device_id).one_or_none()
    if device is None:
        device = models.Device(device_id=device_id, role="CONSUMER")
        db.add(device)
        db.flush()  # populate device.id (PK) for the FK below, before commit
    return device


@router.post("/scans", response_model=ScanResult, status_code=status.HTTP_201_CREATED)
async def submit_scan(
    image: UploadFile = File(...),
    shard=Depends(_shard_session),
) -> ScanResult:
    db, shard_index, device_id = shard

    if image.content_type not in settings.allowed_content_types:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=(
                f"Unsupported image type '{image.content_type}'. "
                f"Expected one of: {', '.join(settings.allowed_content_types)}"
            ),
        )

    data = await image.read()

    if not data:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Uploaded image is empty.",
        )

    if len(data) > settings.max_upload_bytes:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"Image is {len(data)} bytes, over the "
                f"{settings.max_upload_bytes} byte limit."
            ),
        )

    scan_id = uuid4()
    image_key = storage_service.build_key(scan_id, image.content_type)
    storage_service.save_image(image_key, data)

    # OCR is CPU-bound and blocking; keep the event loop free while it runs.
    ocr_result = await asyncio.to_thread(ocr_service.extract_text, data)

    # Same reasoning for rule evaluation: regex work over potentially long
    # OCR text is cheap per call, but under bulk/e-commerce load (Step 11 of
    # the implementation plan) this still shouldn't block the loop.
    evaluation = await asyncio.to_thread(rules_service.evaluate, ocr_result)

    device = _get_or_create_device(db, device_id)

    scan_row = models.Scan(
        id=str(scan_id),
        device_id=device.id,
        source="server_ocr",
        scanned_at=datetime.now(timezone.utc),
        verdict=evaluation.verdict.value,
        ruleset_version=evaluation.ruleset_version,
        brand=None,
        mrp=_field_value(evaluation, "mrp"),
        net_quantity=_field_value(evaluation, "net_quantity"),
        batch_number=_field_value(evaluation, "batch_number"),
        mfg_date=_field_value(evaluation, "mfg_date"),
        frames_used=1,  # one uploaded image, not a multi-frame burst
        raw_lines=[token.text for token in ocr_result.tokens],
        image_key=image_key,
        ocr_model=ocr_result.model,
        ocr_mean_confidence=ocr_result.mean_confidence,
        ocr_processing_time_ms=ocr_result.processing_time_ms,
        shard_index=shard_index,
    )
    db.add(scan_row)
    db.flush()

    for check in evaluation.checks:
        db.add(
            models.ScanCheck(
                scan_id=scan_row.id,
                rule_id=check.rule_id,
                rule_name=check.rule_name,
                field=check.field,
                status=check.status.value,
                citation=check.citation,
                message=check.message,
                observed_value=check.observed_value,
            )
        )

    db.commit()

    return ScanResult(
        scan_id=scan_id,
        device_id=device_id,
        shard_index=shard_index,
        verdict=evaluation.verdict.value,
        ruleset_version=evaluation.ruleset_version,
        extracted_fields={
            name: field.value for name, field in evaluation.extracted_fields.items()
        },
        checks=[
            ScanCheckOut(
                rule_id=c.rule_id,
                rule_name=c.rule_name,
                field=c.field,
                status=c.status.value,
                citation=c.citation,
                message=c.message,
                observed_value=c.observed_value,
            )
            for c in evaluation.checks
        ],
        image_key=image_key,
        size_bytes=len(data),
        received_at=datetime.now(timezone.utc),
        ocr=ocr_result,
    )


def _field_value(evaluation, name: str) -> str | None:
    field = evaluation.extracted_fields.get(name)
    return field.value if field else None
