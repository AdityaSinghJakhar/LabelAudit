"""
POST /api/scans -- server-side OCR path.

Distinct from the phone's own sync of on-device scans.

This endpoint:
    image
      -> OCR
      -> spatial extraction
      -> SKU registry matching
      -> rule evaluation
      -> Scan + ScanChecks + MatchesRegistry
"""

import asyncio
from datetime import datetime, timezone
from uuid import uuid4

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    UploadFile,
    status,
)

from app.config import settings
from app.models.scan import ScanCheckOut, ScanResult
from app.services import (
    ocr_service,
    rules_service,
    storage_service,
)
from app.services.scan_pipeline import extract_and_match

from db import models
from db.sharding import router as shard_router


router = APIRouter(tags=["scan"])


# ---------------------------------------------------------------------------
# Shard session
# ---------------------------------------------------------------------------

def _shard_session(device_id: str = Form(...)):
    """
    Read device_id from the multipart request and obtain the database
    session for the shard associated with that device.
    """

    session, shard_index = shard_router.session_for_key(device_id)

    try:
        yield session, shard_index, device_id
    finally:
        session.close()


# ---------------------------------------------------------------------------
# Device
# ---------------------------------------------------------------------------

def _get_or_create_device(db, device_id: str) -> models.Device:
    """
    Get the device associated with device_id.

    Create it if this is the first scan received from the device.
    """

    device = (
        db.query(models.Device)
        .filter_by(device_id=device_id)
        .one_or_none()
    )

    if device is None:
        device = models.Device(
            device_id=device_id,
            role="CONSUMER",
        )

        db.add(device)

        # Populate device.id before Scan is created because Scan.device_id
        # is a foreign key to Device.id.
        db.flush()

    return device


# ---------------------------------------------------------------------------
# Scan endpoint
# ---------------------------------------------------------------------------

@router.post(
    "/scans",
    response_model=ScanResult,
    status_code=status.HTTP_201_CREATED,
)
async def submit_scan(
    image: UploadFile = File(...),
    shard=Depends(_shard_session),
) -> ScanResult:

    # The dependency gives us:
    #
    #   db          -> synchronous SQLAlchemy session
    #   shard_index -> selected database shard
    #   device_id   -> device identifier from request
    #
    db, shard_index, device_id = shard

    # -----------------------------------------------------------------------
    # Validate upload
    # -----------------------------------------------------------------------

    if image.content_type not in settings.allowed_content_types:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail=(
                f"Unsupported image type '{image.content_type}'. "
                f"Expected one of: "
                f"{', '.join(settings.allowed_content_types)}"
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

    # -----------------------------------------------------------------------
    # Store image
    # -----------------------------------------------------------------------

    scan_id = uuid4()

    image_key = storage_service.build_key(
        scan_id,
        image.content_type,
    )

    storage_service.save_image(
        image_key,
        data,
    )

    # -----------------------------------------------------------------------
    # 1. OCR
    # -----------------------------------------------------------------------

    # OCR is CPU-bound/blocking, so run it outside the event loop.
    ocr_result = await asyncio.to_thread(
        ocr_service.extract_text,
        data,
    )

    # -----------------------------------------------------------------------
    # 2. Spatial extraction + SKU registry matching
    # -----------------------------------------------------------------------

    identity_result = extract_and_match(
        db=db,
        ocr_result=ocr_result,
    )

    spatial_identity = identity_result.spatial.identity
    registry = identity_result.registry

    # -----------------------------------------------------------------------
    # 3. Rule evaluation
    # -----------------------------------------------------------------------

    # Keep the existing rules pipeline.
    evaluation = await asyncio.to_thread(
        rules_service.evaluate,
        ocr_result,
    )

    # -----------------------------------------------------------------------
    # 4. Device
    # -----------------------------------------------------------------------

    device = _get_or_create_device(
        db,
        device_id,
    )

    # -----------------------------------------------------------------------
    # 5. Create Scan
    # -----------------------------------------------------------------------

    scan_row = models.Scan(
        id=str(scan_id),
        device_id=device.id,
        source="server_ocr",
        scanned_at=datetime.now(timezone.utc),

        verdict=evaluation.verdict.value,
        ruleset_version=evaluation.ruleset_version,

        # Prefer the spatially extracted identity where available.
        brand=spatial_identity.get("brand"),

        # Keep rule extraction as the fallback for fields not found by
        # spatial extraction.
        mrp=(
            spatial_identity.get("mrp")
            or _field_value(evaluation, "mrp")
        ),

        net_quantity=(
            spatial_identity.get("net_quantity")
            or _field_value(evaluation, "net_quantity")
        ),

        batch_number=_field_value(
            evaluation,
            "batch_number",
        ),

        mfg_date=_field_value(
            evaluation,
            "mfg_date",
        ),

        frames_used=1,

        raw_lines=[
            token.text
            for token in ocr_result.tokens
        ],

        image_key=image_key,

        ocr_model=ocr_result.model,
        ocr_mean_confidence=ocr_result.mean_confidence,
        ocr_processing_time_ms=ocr_result.processing_time_ms,

        shard_index=shard_index,
    )

    db.add(scan_row)

    # Populate scan_row.id before creating MatchesRegistry.
    db.flush()

    # -----------------------------------------------------------------------
    # 6. Persist registry matching decision
    # -----------------------------------------------------------------------

    db.add(
        models.MatchesRegistry(
            scan_id=scan_row.id,

            sku_id=(
                registry.sku.id
                if registry.sku is not None
                else None
            ),

            status=registry.status,
            score=registry.score,
            rejection_threshold=registry.rejection_threshold,
            match_method=registry.match_method,

            evidence=registry.evidence,
            extracted_identity=registry.extracted_identity,
        )
    )

    # -----------------------------------------------------------------------
    # 7. Persist rule checks
    # -----------------------------------------------------------------------

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

    # -----------------------------------------------------------------------
    # 8. Commit everything together
    # -----------------------------------------------------------------------

    db.commit()

    # -----------------------------------------------------------------------
    # 9. Response
    # -----------------------------------------------------------------------

    return ScanResult(
        scan_id=scan_id,
        device_id=device_id,
        shard_index=shard_index,

        verdict=evaluation.verdict.value,
        ruleset_version=evaluation.ruleset_version,

        extracted_fields={
            name: field.value
            for name, field in evaluation.extracted_fields.items()
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


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _field_value(
    evaluation,
    name: str,
) -> str | None:
    """
    Safely retrieve a field from the rule evaluation.
    """

    field = evaluation.extracted_fields.get(name)

    return field.value if field else None