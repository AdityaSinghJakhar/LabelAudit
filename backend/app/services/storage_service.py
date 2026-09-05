"""
Local-disk stand-in for object storage.

Not sharded -- images are content, not the thing query load falls on.
Sharding here splits database *rows* by device_id (db/sharding.py); the
images they reference can stay in one bucket, or move to per-shard buckets
later purely for storage-volume reasons, without touching the DB shard
routing at all. Swap this module for an S3/MinIO client when that need
arrives; build_key()'s output format is deliberately storage-backend
agnostic so that swap doesn't touch callers.
"""

from datetime import date
from pathlib import Path
from uuid import UUID

from app.config import settings

_EXTENSIONS = {
    "image/jpeg": ".jpg",
    "image/png": ".png",
}


def build_key(scan_id: UUID, content_type: str) -> str:
    """
    Object key for a scan image, partitioned by date so a directory listing
    stays manageable. Mirrors the layout we'll use in S3/MinIO later.
    """
    extension = _EXTENSIONS.get(content_type, ".bin")
    return f"{date.today():%Y/%m/%d}/{scan_id}{extension}"


def save_image(key: str, data: bytes) -> Path:
    """Writes the image and returns the path it landed on."""
    destination = settings.storage_dir / key
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    return destination


def load_image(key: str) -> bytes:
    return (settings.storage_dir / key).read_bytes()
