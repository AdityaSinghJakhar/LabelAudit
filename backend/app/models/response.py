from datetime import datetime
from uuid import UUID

from pydantic import BaseModel

from app.models.ocr import OcrResult


class ScanAccepted(BaseModel):
    """
    Returned once an image has been received, stored and read. Field
    extraction and the compliance verdict are added in later phases.
    """

    scan_id: UUID
    image_key: str
    size_bytes: int
    received_at: datetime
    ocr: OcrResult
