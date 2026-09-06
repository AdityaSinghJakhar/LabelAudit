import json
import secrets
from datetime import datetime, timezone
from pathlib import Path
from typing import List, Optional

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.config import settings
from app.core.security import get_optional_device
from db import models
from db.session import get_db

router = APIRouter(prefix="/v1/corpus", tags=["corpus"])


class CorpusUploadOut(BaseModel):
    corpus_id: str
    image_id: str
    verdict: str
    ruleset_version: str
    frames_saved: int
    storage_path: str


class CorpusItemOut(BaseModel):
    id: str
    image_id: str
    verdict: str
    ruleset_version: str
    frame_count: int
    frames_used: int
    created_at: datetime


@router.post("/upload", response_model=CorpusUploadOut, status_code=status.HTTP_201_CREATED)
async def upload_corpus_entry(
    scan_json: str = Form(..., description="JSON string in ResultsExport format"),
    frames: List[UploadFile] = File(default_factory=list, description="JPG frames from Keep scans for evaluation"),
    corpus_id: Optional[str] = Form(None, description="Optional entry id, e.g. 20260905-111114-a3f2"),
    device_id: Optional[str] = Form(None),
    device: Optional[models.Device] = Depends(get_optional_device),
    db: Session = Depends(get_db),
):
    """
    Opt-in endpoint that accepts frames and prediction json from
    'Keep scans for evaluation', allowing accuracy corpus to build passively.
    """
    try:
        data = json.loads(scan_json)
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid JSON in scan_json field: {e}",
        )

    cid = corpus_id or f"{datetime.now(timezone.utc):%Y%m%d-%H%M%S}-{secrets.token_hex(2)}"
    storage_root = Path(settings.storage_dir) / "corpus" / cid
    storage_root.mkdir(parents=True, exist_ok=True)

    # Save scan.json
    scan_json_path = storage_root / "scan.json"
    scan_json_path.write_text(json.dumps(data, indent=2), encoding="utf-8")

    # Save frames
    saved_frames = 0
    for idx, frame in enumerate(frames):
        filename = frame.filename or f"frame-{idx + 1:02d}.jpg"
        content = await frame.read()
        if content:
            (storage_root / filename).write_bytes(content)
            saved_frames += 1

    # Database metadata record
    resolved_dev_id = None
    if device:
        resolved_dev_id = device.id
    elif device_id:
        dev = db.query(models.Device).filter(models.Device.device_id == device_id).one_or_none()
        if dev:
            resolved_dev_id = dev.id

    corpus_record = models.CorpusScan(
        id=cid,
        device_id=resolved_dev_id,
        image_id=str(data.get("image_id", cid)),
        verdict=str(data.get("verdict", "")),
        ruleset_version=str(data.get("ruleset_version", "")),
        frames_used=int(data.get("frames_used", saved_frames)),
        frame_count=saved_frames,
        storage_path=str(storage_root),
        scan_json=data,
    )
    db.merge(corpus_record)
    db.commit()

    return CorpusUploadOut(
        corpus_id=cid,
        image_id=corpus_record.image_id,
        verdict=corpus_record.verdict,
        ruleset_version=corpus_record.ruleset_version,
        frames_saved=saved_frames,
        storage_path=str(storage_root),
    )


@router.get("", response_model=List[CorpusItemOut])
def list_corpus_entries(
    limit: int = 50,
    db: Session = Depends(get_db),
    device: Optional[models.Device] = Depends(get_optional_device),
):
    """Lists passively collected corpus entries for offline accuracy evaluation."""
    items = db.query(models.CorpusScan).order_by(models.CorpusScan.created_at.desc()).limit(limit).all()
    return [
        CorpusItemOut(
            id=item.id,
            image_id=item.image_id,
            verdict=item.verdict,
            ruleset_version=item.ruleset_version,
            frame_count=item.frame_count,
            frames_used=item.frames_used,
            created_at=item.created_at,
        )
        for item in items
    ]


@router.get("/{cid}/frames/{filename}")
def get_corpus_frame(cid: str, filename: str):
    """Retrieves a specific stored evaluation frame."""
    file_path = Path(settings.storage_dir) / "corpus" / cid / filename
    if not file_path.exists():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Frame not found")
    media_type = "image/jpeg" if filename.lower().endswith((".jpg", ".jpeg")) else "image/png"
    return FileResponse(file_path, media_type=media_type)


@router.get("/{cid}/scan.json")
def get_corpus_scan_json(cid: str):
    """Retrieves the scan.json prediction for a corpus entry."""
    file_path = Path(settings.storage_dir) / "corpus" / cid / "scan.json"
    if not file_path.exists():
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="scan.json not found")
    return FileResponse(file_path, media_type="application/json")
