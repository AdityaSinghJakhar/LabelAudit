"""
Persistence for the backend.

UPDATED (server-OCR branch): this is no longer sync-only. Two kinds of scan
now exist, distinguished by Scan.source:

  "device"     OCR, field extraction, consensus and rule evaluation all ran
               on the phone (the original ARCHITECTURE.md design). This
               table stores the *output* of a scan the device already
               finished -- no image, no recomputation.

  "server_ocr" OCR ran here, on the backend (see app/services/ocr_service.py),
               against an image the client uploaded. Field extraction and
               rule evaluation are also server-side (app/services/
               field_extraction.py, app/services/rules_service.py) and are
               DELIBERATELY NARROWER than the on-device pipeline: presence
               checks only, no spatial/date/height reasoning yet. See those
               modules' docstrings for exactly what is and isn't assessed,
               and HANDOFF.md's governing rule -- never emit a verdict the
               pipeline cannot substantiate -- applies here exactly as it
               does on the phone.

Configuration that needs to be shared across devices (roles, calibration,
SKU references) is unaffected by this change.

Mirrors, on the Kotlin side:
  devices       <- auth/Role.kt, auth/RoleStore.kt
  calibrations  <- measure/Calibration.kt, measure/CalibrationStore.kt
  skus          <- registry/SkuRecord.kt, registry/Enrolment.kt
  scans         <- history/ScanRecord.kt
  scan_checks   <- history/ScanRecord.Check

Enum *values* (not the Python enum type) are stored as plain strings so a
row can always be inspected without decoding through this ORM -- the same
reasoning `models.py`'s docstring gives for using literal uppercase strings
in the shared vocabulary.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import (
    Boolean,
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
    A phone that has claimed a role.

    The passcode itself is never stored -- only its hash, mirroring
    RoleStore's "salted SHA-256 digest, never in the clear" on-device
    behaviour. Here the hash is what a claim is checked against, and the
    issued bearer token is what every later request is checked against
    instead of the passcode.

    role defaults to CONSUMER: a device is a shopper's phone until it
    successfully claims INSPECTOR, exactly as Role.kt's enum ordering and
    RoleStore's default both assume.
    """

    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String, primary_key=True, default=_uuid)
    device_id: Mapped[str] = mapped_column(String, unique=True, index=True)
    model: Mapped[str | None] = mapped_column(String, nullable=True)
    app_version: Mapped[str | None] = mapped_column(String, nullable=True)

    role: Mapped[str] = mapped_column(String, default="CONSUMER")
    passcode_hash: Mapped[str | None] = mapped_column(String, nullable=True)

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
    A device's correction factor, measured once against an object of known
    size.

    Mirrors measure/Calibration.kt field for field. Kept server-side so a
    reinstall, or an inspector switching to a second phone, does not lose
    the correction -- CalibrationStore's on-device JSON has no such
    continuity.
    """

    __tablename__ = "calibrations"

    id: Mapped[str] = mapped_column(String, primary_key=True, default=_uuid)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)

    correction: Mapped[float] = mapped_column(Float)
    reference_name: Mapped[str] = mapped_column(String)
    reference_mm: Mapped[float] = mapped_column(Float)
    measured_px: Mapped[int] = mapped_column(Integer)
    # LENS_FOCUS_DISTANCE, in diopters, when the reference was measured --
    # Calibration.appliesAt() needs this to decide whether the correction
    # is valid at a later scan's focus distance.
    diopters: Mapped[float] = mapped_column(Float)

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)

    device: Mapped[Device] = relationship(back_populates="calibrations")


class Sku(Base):
    """
    A registered reference product.

    authority distinguishes AUTHORITATIVE (brand/regulator master, can
    substantiate a FAIL) from ASSERTED (enrolled from a scan or typed by
    hand, NEEDS_REVIEW only) -- see rules/loader.py's Authority enum and
    ARCHITECTURE.md's "Reference data and trust" section. That distinction
    must survive the trip to the server unchanged, or an asserted reference
    could quietly acquire authoritative weight once shared across devices,
    which is exactly the relabelling risk the design exists to prevent.

    brand_strings / addresses / consumer_care are stored as JSON rather than
    normalised out into their own tables: this mirrors SkuRecord.kt, which
    keeps them as a small embedded structure because a SKU record is read
    whole, never queried field-by-field.
    """

    __tablename__ = "skus"

    id: Mapped[str] = mapped_column(String, primary_key=True, default=_uuid)
    sku_id: Mapped[str] = mapped_column(String, unique=True, index=True)

    authority: Mapped[str] = mapped_column(String)  # AUTHORITATIVE | ASSERTED

    brand_strings: Mapped[list[str]] = mapped_column(JSON, default=list)
    addresses: Mapped[dict] = mapped_column(JSON, default=dict)
    consumer_care: Mapped[dict] = mapped_column(JSON, default=dict)
    mrp_exact: Mapped[float | None] = mapped_column(Float, nullable=True)
    net_quantity: Mapped[str | None] = mapped_column(String, nullable=True)

    note: Mapped[str] = mapped_column(String, default="")
    enrolled_by_device_id: Mapped[str | None] = mapped_column(
        ForeignKey("devices.id"), nullable=True
    )

    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=_now, onupdate=_now
    )


class Scan(Base):
    """
    One completed scan -- either synced from the phone after it finished its
    own pipeline, or run here on the backend against an uploaded image. See
    Scan.source and the module docstring above.

    Mirrors history/ScanRecord.kt for the device case. This table never
    stores frames or bounding boxes -- only what a reader needs later: which
    product, what verdict, under which ruleset version. rawLines is kept
    because HANDOFF.md's regression-test discipline is built from real OCR
    output.

    ruleset_version is not optional. ScanRecord.kt's own docstring: "a
    verdict recorded under one version of the rules cannot be defended by
    quoting a later one."
    """

    __tablename__ = "scans"

    # Client-generated UUID when the device produces one (matches
    # ScanRecord.id from the phone), or server-generated for a fresh
    # server_ocr scan -- either way, a re-POST of the same id is treated as
    # idempotent instead of creating a duplicate.
    id: Mapped[str] = mapped_column(String, primary_key=True)

    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)

    # "device" (phone ran the whole pipeline, this row is a sync) or
    # "server_ocr" (this backend ran OCR + naive extraction + rules against
    # an uploaded image). See the module docstring for what each implies
    # about how much the recorded checks actually assessed.
    source: Mapped[str] = mapped_column(String, default="device", index=True)

    scanned_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    synced_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now)

    verdict: Mapped[str] = mapped_column(String, index=True)  # Verdict enum value
    ruleset_version: Mapped[str] = mapped_column(String)

    sku_id: Mapped[str | None] = mapped_column(String, nullable=True, index=True)
    brand: Mapped[str | None] = mapped_column(String, nullable=True)
    mrp: Mapped[str | None] = mapped_column(String, nullable=True)
    net_quantity: Mapped[str | None] = mapped_column(String, nullable=True)
    batch_number: Mapped[str | None] = mapped_column(String, nullable=True)
    mfg_date: Mapped[str | None] = mapped_column(String, nullable=True)

    frames_used: Mapped[int] = mapped_column(Integer, default=0)
    raw_lines: Mapped[list[str]] = mapped_column(JSON, default=list)

    # --- server_ocr only. Null for synced device scans. ---
    image_key: Mapped[str | None] = mapped_column(String, nullable=True)
    ocr_model: Mapped[str | None] = mapped_column(String, nullable=True)
    ocr_mean_confidence: Mapped[float | None] = mapped_column(Float, nullable=True)
    ocr_processing_time_ms: Mapped[int | None] = mapped_column(Integer, nullable=True)
    shard_index: Mapped[int | None] = mapped_column(Integer, nullable=True)

    device: Mapped[Device] = relationship(back_populates="scans")
    checks: Mapped[list["ScanCheck"]] = relationship(
        back_populates="scan", cascade="all, delete-orphan"
    )

    @property
    def title(self) -> str:
        """Mirrors ScanRecord.title: what a list row shows with no SKU match."""
        return self.sku_id or (self.brand or None) or "Unidentified pack"


class ScanCheck(Base):
    """
    One rule's outcome within a synced scan.

    Mirrors ScanRecord.Check. status is RuleStatus (not Verdict) because a
    check can be EXEMPT or NOT_APPLICABLE, which a scan's overall verdict
    never is -- see models.py's RuleStatus docstring for why the two are
    not interchangeable.

    citation is intentionally NOT NULL at the schema level, not only
    enforced in application code: rules/loader.py refuses to load a rule
    with no citation, and a finding with no statutory source must be
    impossible to persist here for the same reason it is impossible to
    load there.
    """

    __tablename__ = "scan_checks"

    id: Mapped[str] = mapped_column(String, primary_key=True, default=_uuid)
    scan_id: Mapped[str] = mapped_column(ForeignKey("scans.id"), index=True)

    rule_id: Mapped[str] = mapped_column(String)
    rule_name: Mapped[str] = mapped_column(String, default="")
    field: Mapped[str] = mapped_column(String)
    status: Mapped[str] = mapped_column(String)  # RuleStatus enum value
    citation: Mapped[str] = mapped_column(String)
    message: Mapped[str] = mapped_column(String, default="")
    observed_value: Mapped[str | None] = mapped_column(String, nullable=True)

    scan: Mapped[Scan] = relationship(back_populates="checks")

    __table_args__ = (
        UniqueConstraint("scan_id", "rule_id", name="uq_scan_check_rule"),
    )