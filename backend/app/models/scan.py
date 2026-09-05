from datetime import datetime
from uuid import UUID

from pydantic import BaseModel

from app.models.ocr import OcrResult


class ScanCheckOut(BaseModel):
    rule_id: str
    rule_name: str
    field: str
    status: str  # RuleStatus value
    citation: str
    message: str
    observed_value: str | None


class ScanResult(BaseModel):
    """
    Response for POST /api/scans. Mirrors what actually lands in the
    Scan + ScanCheck rows (db/models.py) for a source="server_ocr" scan, so
    a client reading this response and a client later reading the row back
    from an inspector endpoint see the same shape.
    """

    scan_id: UUID
    device_id: str
    shard_index: int
    source: str = "server_ocr"

    verdict: str  # Verdict value
    ruleset_version: str

    extracted_fields: dict[str, str]
    checks: list[ScanCheckOut]

    image_key: str
    size_bytes: int
    received_at: datetime

    ocr: OcrResult
