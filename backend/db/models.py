"""
SQLAlchemy models for the LabelGuard / LabelAudit sync backend.

Mirrors, on the Kotlin side:
  devices       <- auth/Role.kt, auth/RoleStore.kt
  calibrations  <- measure/Calibration.kt, measure/CalibrationStore.kt
  skus          <- registry/SkuRecord.kt, registry/Enrolment.kt
  scans         <- history/ScanRecord.kt
  scan_checks   <- history/ScanRecord.Check
  corpus_scans  <- eval/CorpusStore.kt
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import (
    BigInteger,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    JSON,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from db.session import Base


def _uuid() -> str:
    return str(uuid.uuid4())


def _now() -> datetime:
    return datetime.now(timezone.utc)


class Device(Base):
    """
    A client device registered or claimed.
    role defaults to CONSUMER (Shopper).
    When claimed as INSPECTOR, passcode salt + hash are stored and a bearer token is issued.
    """
    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String, primary_key=True, default=_uuid)
    device_id: Mapped[str] = mapped_column(String, unique=True, index=True)
    role: Mapped[str] = mapped_column(String, default="CONSUMER")  # CONSUMER | INSPECTOR
    passcode_hash: Mapped[str | None] = mapped_column(String, nullable=True)
    passcode_salt: Mapped[str | None] = mapped_column(String, nullable=True)
    token: Mapped[str | None] = mapped_column(String, unique=True, nullable=True, index=True)

    model: Mapped[str | None] = mapped_column(String, nullable=True)
    app_version: Mapped[str | None] = mapped_column(String, nullable=True)
    claimed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now
    )

    calibrations: Mapped[list["Calibration"]] = relationship(
        back_populates="device", cascade="all, delete-orphan"
    )
    scans: Mapped[list["Scan"]] = relationship(back_populates="device")


class Calibration(Base):
    """
    Device camera optics calibration.
    Mirrors measure/Calibration.kt.
    """
    __tablename__ = "calibrations"

    id: Mapped[str] = mapped_column(String, primary_key=True, default=_uuid)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)

    correction: Mapped[float] = mapped_column(Float)
    reference_name: Mapped[str] = mapped_column(String)
    reference_mm: Mapped[float] = mapped_column(Float)
    measured_px: Mapped[int] = mapped_column(Integer)
    diopters: Mapped[float] = mapped_column(Float)
    at: Mapped[int] = mapped_column(BigInteger, default=0)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)

    device: Mapped[Device] = relationship(back_populates="calibrations")


class Sku(Base):
    """
    Shared SKU reference registry.
    authority: AUTHORITATIVE (can substantiate FAIL) vs ASSERTED (NEEDS_REVIEW only).
    """
    __tablename__ = "skus"

    id: Mapped[str] = mapped_column(String, primary_key=True, default=_uuid)
    sku_id: Mapped[str] = mapped_column(String, unique=True, index=True)

    authority: Mapped[str] = mapped_column(String, default="ASSERTED")  # AUTHORITATIVE | ASSERTED
    source: Mapped[str] = mapped_column(String, default="ENROLLED_FROM_SCAN")  # ENROLLED_FROM_SCAN | IMPORTED | MANUAL

    brand_strings: Mapped[list[str]] = mapped_column(JSON, default=list)
    mrp_exact: Mapped[float | None] = mapped_column(Float, nullable=True)
    net_quantity: Mapped[str | None] = mapped_column(String, nullable=True)
    manufacturer_address: Mapped[str | None] = mapped_column(String, nullable=True)
    consumer_care: Mapped[str | None] = mapped_column(String, nullable=True)
    fssai_licence: Mapped[str | None] = mapped_column(String, nullable=True)

    note: Mapped[str] = mapped_column(String, default="")
    enrolled_by_device_id: Mapped[str | None] = mapped_column(
        ForeignKey("devices.id"), nullable=True
    )
    saved_at: Mapped[int] = mapped_column(BigInteger, default=0)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now
    )


class Scan(Base):
    """
    One completed scan synced from device.
    Mirrors history/ScanRecord.kt.
    """
    __tablename__ = "scans"

    id: Mapped[str] = mapped_column(String, primary_key=True)  # Client UUID from ScanRecord.id
    device_id: Mapped[str | None] = mapped_column(ForeignKey("devices.id"), nullable=True, index=True)

    scanned_at: Mapped[int] = mapped_column(BigInteger, default=0)  # ms timestamp
    synced_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)

    verdict: Mapped[str] = mapped_column(String, index=True)  # PASS | FAIL | NEEDS_REVIEW | NOT_ASSESSABLE
    ruleset_version: Mapped[str] = mapped_column(String)

    sku_id: Mapped[str | None] = mapped_column(String, nullable=True, index=True)
    brand: Mapped[str | None] = mapped_column(String, nullable=True)
    mrp: Mapped[str | None] = mapped_column(String, nullable=True)
    net_quantity: Mapped[str | None] = mapped_column(String, nullable=True)
    batch_number: Mapped[str | None] = mapped_column(String, nullable=True)
    mfg_date: Mapped[str | None] = mapped_column(String, nullable=True)

    frames_used: Mapped[int] = mapped_column(Integer, default=0)
    raw_lines: Mapped[list[str]] = mapped_column(JSON, default=list)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)

    device: Mapped[Device | None] = relationship(back_populates="scans")
    checks: Mapped[list["ScanCheck"]] = relationship(
        back_populates="scan",
        cascade="all, delete-orphan",
    )

    @property
    def title(self) -> str:
        return self.sku_id or (self.brand or None) or "Unidentified pack"


class ScanCheck(Base):
    """
    One check within a synced scan.
    Mirrors history/ScanRecord.Check.
    """
    __tablename__ = "scan_checks"

    id: Mapped[str] = mapped_column(String, primary_key=True, default=_uuid)
    scan_id: Mapped[str] = mapped_column(ForeignKey("scans.id"), index=True)

    rule_id: Mapped[str] = mapped_column(String, index=True)
    rule_name: Mapped[str] = mapped_column(String, default="")
    field: Mapped[str] = mapped_column(String)
    status: Mapped[str] = mapped_column(String)  # PASS | FAIL | NEEDS_REVIEW | NOT_ASSESSABLE | NOT_APPLICABLE | EXEMPT
    citation: Mapped[str] = mapped_column(String, default="")
    message: Mapped[str] = mapped_column(String, default="")
    observed_value: Mapped[str | None] = mapped_column(String, nullable=True)

    scan: Mapped[Scan] = relationship(back_populates="checks")

    __table_args__ = (
        UniqueConstraint("scan_id", "rule_id", name="uq_scan_check_rule"),
    )


class CorpusScan(Base):
    """
    Passive accuracy corpus upload from "Keep scans for evaluation".
    Mirrors eval/CorpusStore.kt.
    """
    __tablename__ = "corpus_scans"

    id: Mapped[str] = mapped_column(String, primary_key=True)  # Corpus entry ID, e.g. 20260905-111114-a3f2
    device_id: Mapped[str | None] = mapped_column(ForeignKey("devices.id"), nullable=True)

    image_id: Mapped[str] = mapped_column(String, default="")
    verdict: Mapped[str] = mapped_column(String, default="")
    ruleset_version: Mapped[str] = mapped_column(String, default="")
    frames_used: Mapped[int] = mapped_column(Integer, default=0)
    frame_count: Mapped[int] = mapped_column(Integer, default=0)
    storage_path: Mapped[str] = mapped_column(String)
    scan_json: Mapped[dict] = mapped_column(JSON, default=dict)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)