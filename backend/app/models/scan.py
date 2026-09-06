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


class ScanSummaryOut(BaseModel):
    """
    One row of GET /api/scans -- enough to populate a history list without
    pulling every check and the full OCR payload per scan.
    """

    scan_id: str
    device_id: str
    source: str
    scanned_at: datetime
    verdict: str
    ruleset_version: str
    brand: str | None
    mrp: str | None
    net_quantity: str | None
    title: str


class ScanDetailOut(BaseModel):
    """
    Response for GET /api/scans/{scan_id} -- everything persisted about
    one scan, read back from the Scan + ScanCheck (+ MatchesRegistry, when
    present) rows rather than recomputed.
    """

    scan_id: str
    device_id: str
    source: str
    scanned_at: datetime
    synced_at: datetime

    verdict: str
    ruleset_version: str

    brand: str | None
    mrp: str | None
    net_quantity: str | None
    batch_number: str | None
    mfg_date: str | None

    frames_used: int
    raw_lines: list[str]

    image_key: str | None
    ocr_model: str | None
    ocr_mean_confidence: float | None
    ocr_processing_time_ms: int | None

    checks: list[ScanCheckOut]

    registry_match: "RegistryMatchOut | None" = None


class RegistryMatchOut(BaseModel):
    status: str
    score: float
    rejection_threshold: float
    match_method: str
    sku_id: str | None
    evidence: dict
    extracted_identity: dict[str, str]


ScanDetailOut.model_rebuild()
