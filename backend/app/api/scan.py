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
from app.models.scan import (
    RegistryMatchOut,
    ScanCheckOut,
    ScanDetailOut,
    ScanResult,
    ScanSummaryOut,
)
from app.services import (
    ocr_service,
    rules_service,
    storage_service,
)
from app.services.registry_matcher import save_match_registry
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

    # Synchronous: db is a plain sqlalchemy.orm.Session from the shard
    # router, and the spatial/registry-matching pipeline is written
    # against that, not asyncio. See registry_matcher.py's module
    # docstring for why.
    identity_result = extract_and_match(
        db=db,
        ocr_result=ocr_result,
    )

    spatial_identity = identity_result.spatial.identity
    registry = identity_result.registry

    # -----------------------------------------------------------------------
    # 3. Rule evaluation
    # -----------------------------------------------------------------------

    # `registry` (the MatchDecision from step 2) feeds matches_registry
    # checks -- see rules_service._evaluate_matches_registry.
    evaluation = await asyncio.to_thread(
        rules_service.evaluate,
        ocr_result,
        registry,
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

    # scan_row.id only exists after the flush above, which is why this
    # step -- unlike spatial extraction and matching themselves -- happens
    # here rather than inside extract_and_match().
    save_match_registry(
        db=db,
        scan_id=scan_row.id,
        decision=registry,
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
# Read endpoints
# ---------------------------------------------------------------------------
#
# A scan's shard is determined by its owning DEVICE's device_id (see
# db/sharding.py's module docstring: device_id is the shard key, not
# scan_id). A caller retrieving one scan by id, or listing a device's
# scans, therefore always supplies device_id -- that is what lets these
# endpoints go straight to the correct shard instead of fanning out
# across every shard to find one row. This mirrors the write path's own
# _shard_session dependency.

@router.get(
    "/scans",
    response_model=list[ScanSummaryOut],
)
def list_scans(
    device_id: str,
    limit: int = 50,
) -> list[ScanSummaryOut]:
    """
    A device's scan history, most recent first. Mirrors the on-device
    history list (HANDOFF.md: "every scan recorded and searchable").
    """

    if not 1 <= limit <= 200:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="limit must be between 1 and 200.",
        )

    db, _shard_index = shard_router.session_for_key(device_id)

    try:
        device = db.query(models.Device).filter_by(device_id=device_id).one_or_none()

        if device is None:
            return []

        rows = (
            db.query(models.Scan)
            .filter_by(device_id=device.id)
            .order_by(models.Scan.scanned_at.desc())
            .limit(limit)
            .all()
        )

        return [
            ScanSummaryOut(
                scan_id=row.id,
                device_id=device_id,
                source=row.source,
                scanned_at=row.scanned_at,
                verdict=row.verdict,
                ruleset_version=row.ruleset_version,
                brand=row.brand,
                mrp=row.mrp,
                net_quantity=row.net_quantity,
                title=row.title,
            )
            for row in rows
        ]
    finally:
        db.close()


@router.get(
    "/scans/{scan_id}",
    response_model=ScanDetailOut,
)
def get_scan(
    scan_id: str,
    device_id: str,
) -> ScanDetailOut:
    """
    One scan's full stored detail: the Scan row, every ScanCheck, and its
    MatchesRegistry decision if one was recorded (server_ocr scans always
    have one; synced on-device scans never do, since matching only runs
    server-side today).

    device_id is required, not optional, because it is what selects the
    shard scan_id might live on -- without it this endpoint would have to
    query every shard to find one row. A client always has the device_id
    it scanned with, so this is not an extra burden in practice, only an
    explicit one.
    """

    db, _shard_index = shard_router.session_for_key(device_id)

    try:
        row = (
            db.query(models.Scan)
            .join(models.Device, models.Scan.device_id == models.Device.id)
            .filter(
                models.Scan.id == scan_id,
                models.Device.device_id == device_id,
            )
            .one_or_none()
        )

        if row is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=(
                    f"No scan '{scan_id}' found for device '{device_id}'. "
                    "Either the id is wrong, or this device_id does not "
                    "own that scan. Landing on the right shard is not "
                    "enough -- two different device_ids can hash to the "
                    "same shard, so ownership is checked explicitly, "
                    "not inferred from co-location."
                ),
            )

        checks = (
            db.query(models.ScanCheck)
            .filter_by(scan_id=scan_id)
            .all()
        )

        match = (
            db.query(models.MatchesRegistry)
            .filter_by(scan_id=scan_id)
            .order_by(models.MatchesRegistry.created_at.desc())
            .first()
        )

        return ScanDetailOut(
            scan_id=row.id,
            device_id=device_id,
            source=row.source,
            scanned_at=row.scanned_at,
            synced_at=row.synced_at,
            verdict=row.verdict,
            ruleset_version=row.ruleset_version,
            brand=row.brand,
            mrp=row.mrp,
            net_quantity=row.net_quantity,
            batch_number=row.batch_number,
            mfg_date=row.mfg_date,
            frames_used=row.frames_used,
            raw_lines=row.raw_lines,
            image_key=row.image_key,
            ocr_model=row.ocr_model,
            ocr_mean_confidence=row.ocr_mean_confidence,
            ocr_processing_time_ms=row.ocr_processing_time_ms,
            checks=[
                ScanCheckOut(
                    rule_id=c.rule_id,
                    rule_name=c.rule_name,
                    field=c.field,
                    status=c.status,
                    citation=c.citation,
                    message=c.message,
                    observed_value=c.observed_value,
                )
                for c in checks
            ],
            registry_match=(
                RegistryMatchOut(
                    status=match.status,
                    score=match.score,
                    rejection_threshold=match.rejection_threshold,
                    match_method=match.match_method,
                    sku_id=match.sku_id,
                    evidence=(
                        match.evidence
                        if isinstance(match.evidence, dict)
                        else {"items": match.evidence}
                    ),
                    extracted_identity=match.extracted_identity,
                )
                if match is not None
                else None
            ),
        )
    finally:
        db.close()


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