import asyncio
from datetime import datetime, timezone
from uuid import uuid4

from fastapi import APIRouter, File, HTTPException, UploadFile, status

from app.config import settings
from app.models.response import ScanAccepted
from app.services import ocr_service, storage_service

router = APIRouter(tags=["scan"])


@router.post("/scan", response_model=ScanAccepted, status_code=status.HTTP_201_CREATED)
async def submit_scan(image: UploadFile = File(...)) -> ScanAccepted:
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
    key = storage_service.build_key(scan_id, image.content_type)
    storage_service.save_image(key, data)

    # OCR is CPU-bound and blocking; keep the event loop free while it runs.
    ocr = await asyncio.to_thread(ocr_service.extract_text, data)

    return ScanAccepted(
        scan_id=scan_id,
        image_key=key,
        size_bytes=len(data),
        received_at=datetime.now(timezone.utc),
        ocr=ocr,
    )
